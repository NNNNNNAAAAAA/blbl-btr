package blbl.cat3399.core.update

import org.json.JSONObject
import java.net.URI
import java.util.Locale

/** Release metadata is selected before any APK request is made. */
internal object UpdateManifest {
    const val URL = "https://github.com/NNNNNNAAAAAA/blbl-btr/releases/latest/download/update.json"
    private const val RELEASE_PATH = "/NNNNNNAAAAAA/blbl-btr/releases/download/"
    private val supportedAssetAbis = setOf("armeabi-v7a", "arm64-v8a")
    private val tagPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    private val hashPattern = Regex("[0-9a-fA-F]{64}")
    private val apkNamePattern = Regex("[A-Za-z0-9._-]+\\.apk")

    fun parse(raw: String, variant: String, supportedAbis: List<String>): ApkUpdater.RemoteUpdate {
        require(raw.length <= 1024 * 1024) { "更新清单过大" }
        require(variant == "tv" || variant == "phone") { "未知的更新类型" }
        val root = JSONObject(raw)
        val schema = root.get("schema")
        require(schema is Number && schema.toString() == "1") { "不支持的更新清单格式" }
        val rawCode = root.get("version_code")
        val versionCode = rawCode.toString().toLongOrNull()
        require(rawCode is Number && versionCode != null && versionCode in 1..Int.MAX_VALUE.toLong()) { "无效的更新版本号" }
        val tag = root.getString("release_tag")
        require(tagPattern.matches(tag)) { "无效的发布标签" }
        val selected = root.getJSONObject("variants").getJSONObject(variant)
        val versionName = selected.getString("version_name").trim()
        require(versionName.isNotBlank()) { "更新版本名称为空" }
        val assets = selected.getJSONObject("assets")
        val abi = supportedAbis.firstOrNull { it in supportedAssetAbis && assets.optJSONObject(it) != null }
            ?: error("暂无适合当前设备的 BTR 更新包")
        require(variant != "phone" || abi == "arm64-v8a") { "手机更新包仅支持 ARM64" }
        val asset = assets.getJSONObject(abi)
        val url = asset.getString("url")
        validateApkUrl(url, tag)
        val sha256 = asset.getString("sha256")
        require(hashPattern.matches(sha256)) { "更新包缺少有效的 SHA-256" }
        return ApkUpdater.RemoteUpdate(
            versionName = versionName,
            changelog = root.optString("notes", ""),
            versionCode = versionCode.toInt(),
            apkUrl = url,
            sha256 = sha256.lowercase(Locale.ROOT),
            variant = variant,
            abi = abi,
            releaseTag = tag,
        )
    }

    fun validateApkUrl(raw: String, releaseTag: String) {
        require(tagPattern.matches(releaseTag)) { "无效的发布标签" }
        val uri = URI(raw)
        val prefix = "$RELEASE_PATH$releaseTag/"
        val filename = uri.rawPath?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
        require(
            uri.scheme == "https" && uri.host.equals("github.com", ignoreCase = true) &&
                uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
                uri.rawQuery == null && uri.rawFragment == null &&
                filename != null && apkNamePattern.matches(filename),
        ) { "更新包必须来自本仓库对应的 GitHub Release" }
    }

    fun isNewer(remoteVersionCode: Int, installedVersionCode: Int): Boolean = remoteVersionCode > installedVersionCode
}
