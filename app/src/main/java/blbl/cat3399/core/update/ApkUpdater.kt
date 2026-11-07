package blbl.cat3399.core.update

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import blbl.cat3399.BuildConfig
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.net.await
import blbl.cat3399.core.net.ipv4OnlyDns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

object ApkUpdater {
    const val UPDATE_MANIFEST_URL = UpdateManifest.URL

    private const val COOLDOWN_MS = 5_000L

    @Volatile
    private var lastStartedAtMs: Long = 0L

    private val okHttpLazy: Lazy<OkHttpClient> =
        lazy {
            OkHttpClient.Builder()
                .dns(ipv4OnlyDns { BiliClient.prefs.ipv4OnlyEnabled })
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .addNetworkInterceptor { chain ->
                    val request = chain.request()
                    val host = request.url.host
                    check(
                        request.url.isHttps &&
                            (host == "github.com" || host == "release-assets.githubusercontent.com" || host == "objects.githubusercontent.com"),
                    ) { "更新请求重定向到了不受信任的地址" }
                    chain.proceed(request)
                }
                .build()
        }

    private val okHttp: OkHttpClient
        get() = okHttpLazy.value

    fun evictConnections() {
        if (okHttpLazy.isInitialized()) okHttp.connectionPool.evictAll()
    }

    sealed class Progress {
        data object Connecting : Progress()

        data class Downloading(
            val downloadedBytes: Long,
            val totalBytes: Long?,
            val bytesPerSecond: Long,
        ) : Progress() {
            val percent: Int? =
                totalBytes?.takeIf { it > 0 }?.let { total ->
                    ((downloadedBytes.toDouble() / total.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
                }

            val hint: String =
                buildString {
                    if (totalBytes != null && totalBytes > 0) {
                        append("${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}")
                    } else {
                        append(formatBytes(downloadedBytes))
                    }
                    if (bytesPerSecond > 0) append("（${formatBytes(bytesPerSecond)}/s）")
                }
        }
    }

    data class RemoteUpdate(
        val versionName: String,
        val changelog: String,
        val versions: List<RemoteUpdate> = emptyList(),
        val versionCode: Int = 0,
        val apkUrl: String = "",
        val sha256: String = "",
        val variant: String = "",
        val abi: String = "",
        val releaseTag: String = "",
    ) {
        val displayChangelog: String
            get() = changelog.ifBlank { "暂无更新日志" }
    }

    fun markStarted(nowMs: Long = System.currentTimeMillis()) {
        lastStartedAtMs = nowMs
    }

    fun cooldownLeftMs(nowMs: Long = System.currentTimeMillis()): Long {
        val last = lastStartedAtMs
        val left = (last + COOLDOWN_MS) - nowMs
        return left.coerceAtLeast(0)
    }

    suspend fun fetchLatestUpdate(): RemoteUpdate {
        return withContext(Dispatchers.IO) {
            var lastError: Throwable? = null
            val maxAttempts = 3
            for (attempt in 1..maxAttempts) {
                ensureActive()
                try {
                    return@withContext fetchLatestUpdateOnce()
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    lastError = t
                    val shouldRetry =
                        attempt < maxAttempts &&
                            (t is IOException || t.message?.startsWith("HTTP ") == true)
                    if (!shouldRetry) throw t
                    delay(400L * attempt)
                }
            }
            throw lastError ?: IllegalStateException("fetch latest version failed")
        }
    }

    private suspend fun fetchLatestUpdateOnce(): RemoteUpdate {
        val req =
            Request.Builder()
                .url(UPDATE_MANIFEST_URL)
                .header("Cache-Control", "no-cache")
                .get()
                .build()
        val call = okHttp.newCall(req)
        val res = call.await()
        res.use { r ->
            check(r.isSuccessful) { "HTTP ${r.code} ${r.message}" }
            val body = r.body ?: error("empty body")
            return UpdateManifest.parse(
                raw = body.string(),
                variant = if (BuildConfig.PHONE_PORTRAIT) "phone" else "tv",
                supportedAbis = Build.SUPPORTED_ABIS.toList(),
            )
        }
    }

    internal fun parseChangelog(raw: String): RemoteUpdate {
        val versions = parseChangelogVersions(raw)
        return versions.first().copy(versions = versions)
    }

    internal fun parseChangelogVersions(raw: String): List<RemoteUpdate> {
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
        check(normalized.isNotBlank()) { "更新日志为空" }

        val lines = normalized.lines()
        val allHeadings =
            lines.withIndex()
                .mapNotNull { (index, line) ->
                    parseVersionHeading(line)?.let { heading -> index to heading }
                }
        check(allHeadings.isNotEmpty()) { "未找到版本标题" }

        val versionLevel = allHeadings.first().second.level
        val headings = allHeadings.filter { (_, heading) -> heading.level == versionLevel }
        return headings.mapIndexed { index, (headingIndex, heading) ->
            val nextHeadingIndex = headings.getOrNull(index + 1)?.first ?: lines.size
            val sectionLines =
                lines.subList(headingIndex + 1, nextHeadingIndex)
                    .dropLastWhile { it.isBlank() }
            val changelog =
                sectionLines
                    .joinToString("\n")
                    .trim()

            RemoteUpdate(
                versionName = heading.versionName,
                changelog = changelog,
            )
        }
    }

    private data class VersionHeading(
        val level: Int,
        val versionName: String,
    )

    private fun parseVersionHeading(line: String): VersionHeading? {
        val trimmed = line.trim()
        val match = Regex("""^(#{1,6})\s+\[?v?([0-9]+(?:\.[0-9]+)*(?:[-+][A-Za-z0-9_.-]+)?)\]?(?:\s+.*)?$""").matchEntire(trimmed)
            ?: return null
        val level = match.groupValues[1].length
        val versionName = match.groupValues[2].trim()
        if (parseVersion(versionName) == null) return null
        return VersionHeading(level = level, versionName = versionName)
    }

    fun isRemoteNewer(update: RemoteUpdate, currentVersionCode: Int = BuildConfig.VERSION_CODE): Boolean =
        UpdateManifest.isNewer(update.versionCode, currentVersionCode)

    suspend fun downloadApkToCache(
        context: Context,
        update: RemoteUpdate,
        onProgress: (Progress) -> Unit,
    ): File {
        UpdateManifest.validateApkUrl(update.apkUrl, update.releaseTag)
        check(update.variant == if (BuildConfig.PHONE_PORTRAIT) "phone" else "tv") { "更新包类型与当前应用不符" }
        check(update.abi in Build.SUPPORTED_ABIS) { "更新包不支持当前设备" }
        check(Regex("[0-9a-f]{64}").matches(update.sha256)) { "无效的更新包校验值" }
        onProgress(Progress.Connecting)

        val dir = File(context.cacheDir, "test_update").apply { mkdirs() }
        val part = File(dir, "update.apk.part")
        val target = File(dir, "update.apk")
        runCatching { part.delete() }
        runCatching { target.delete() }
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            val req = Request.Builder().url(update.apkUrl).get().build()
            okHttp.newCall(req).await().use { r ->
                check(r.isSuccessful) { "HTTP ${r.code} ${r.message}" }
                val body = r.body ?: error("empty body")
                val total = body.contentLength().takeIf { it > 0 }
                withContext(Dispatchers.IO) {
                    body.byteStream().use { input ->
                        FileOutputStream(part).use { output ->
                            val buf = ByteArray(32 * 1024)
                            var downloaded = 0L
                            var lastEmitAtMs = 0L
                            var speedAtMs = System.currentTimeMillis()
                            var speedBytes = 0L
                            var bytesPerSecond = 0L
                            while (true) {
                                ensureActive()
                                val read = input.read(buf)
                                if (read <= 0) break
                                output.write(buf, 0, read)
                                digest.update(buf, 0, read)
                                downloaded += read
                                speedBytes += read
                                val nowMs = System.currentTimeMillis()
                                val speedElapsedMs = nowMs - speedAtMs
                                if (speedElapsedMs >= 1_000) {
                                    bytesPerSecond = (speedBytes * 1_000L / speedElapsedMs.coerceAtLeast(1)).coerceAtLeast(0)
                                    speedBytes = 0L
                                    speedAtMs = nowMs
                                }
                                if (nowMs - lastEmitAtMs >= 200) {
                                    lastEmitAtMs = nowMs
                                    onProgress(Progress.Downloading(downloaded, total, bytesPerSecond))
                                }
                            }
                            check(total == null || downloaded == total) { "更新包下载不完整" }
                            output.fd.sync()
                        }
                    }
                    check(part.length() > 0) { "更新包为空" }
                    val actualHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    check(actualHash == update.sha256) { "更新包 SHA-256 校验失败" }
                    validateDownloadedApk(context, part, update)
                    check(part.renameTo(target)) { "无法保存更新包" }
                }
            }
            return target
        } finally {
            runCatching { part.delete() }
        }
    }

    @Suppress("DEPRECATION")
    private fun validateDownloadedApk(context: Context, file: File, update: RemoteUpdate) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("无法读取更新 APK")
        check(archive.packageName == BuildConfig.APPLICATION_ID) { "更新 APK 包名不匹配" }
        val versionCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        check(versionCode == update.versionCode.toLong()) { "更新 APK 版本号与清单不匹配" }
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val archiveSigners = signerDigests(archive)
        check(archiveSigners.isNotEmpty() && archiveSigners == signerDigests(installed)) { "更新 APK 签名与已安装应用不一致" }
        // A split without native libraries is architecture-neutral; any packaged native code must match the selected asset.
        ZipFile(file).use { zip ->
            val nativeAbis = zip.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith("lib/") && it.endsWith(".so") }
                .map { it.split('/')[1] }
                .toSet()
            check(nativeAbis.isEmpty() || update.abi in nativeAbis) { "更新 APK 架构与清单不匹配" }
        }
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }.toSet()
    }

    fun installApk(context: Context, apkFile: File) {
        val uri = installUriFor(context, apkFile)
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        grantInstallerReadPermissions(context, intent, uri)
        context.startActivity(intent)
    }

    @SuppressLint("SetWorldReadable")
    private fun installUriFor(context: Context, apkFile: File): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val authority = "${context.packageName}.fileprovider"
            FileProvider.getUriForFile(context, authority, apkFile)
        } else {
            apkFile.setReadable(true, false)
            Uri.fromFile(apkFile)
        }
    }

    @Suppress("DEPRECATION")
    private fun grantInstallerReadPermissions(
        context: Context,
        intent: Intent,
        uri: Uri,
    ) {
        if (uri.scheme != "content") return

        val installers =
            context.packageManager.queryIntentActivities(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY,
            )
        for (installer in installers) {
            val packageName = installer.activityInfo?.packageName ?: continue
            context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun formatBytes(bytes: Long): String {
        val b = bytes.coerceAtLeast(0)
        if (b < 1024) return "${b}B"
        val kb = b / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1fKB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1fMB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2fGB", gb)
    }

    private fun parseVersion(raw: String): List<Int>? {
        val cleaned = raw.trim().removePrefix("v")
        val digitsOnly =
            cleaned.takeWhile { ch ->
                ch.isDigit() || ch == '.'
            }
        if (digitsOnly.isBlank()) return null
        val parts = digitsOnly.split('.').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        val nums = parts.map { it.toIntOrNull() ?: return null }
        return nums
    }

}
