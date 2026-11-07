package blbl.cat3399.core.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestTest {
    private val tag = "v20261107.120000"
    private val prefix = "https://github.com/NNNNNNAAAAAA/blbl-btr/releases/download/$tag/"
    private val hash = "ab".repeat(32)

    private fun asset(name: String): JSONObject =
        JSONObject().put("url", prefix + name).put("sha256", hash)

    private fun manifest(): JSONObject =
        JSONObject()
            .put("schema", 1)
            .put("version_code", 1794052800)
            .put("release_tag", tag)
            .put("notes", "- 修复缓冲")
            .put(
                "variants",
                JSONObject()
                    .put(
                        "tv",
                        JSONObject().put("version_name", "0.1.29-btr.20261107.120000")
                            .put("assets", JSONObject().put("arm64-v8a", asset("tv-arm64-v8a.apk")).put("armeabi-v7a", asset("tv-armeabi-v7a.apk"))),
                    )
                    .put(
                        "phone",
                        JSONObject().put("version_name", "0.1.29-btr-phone.20261107.120000")
                            .put("assets", JSONObject().put("arm64-v8a", asset("phone-arm64-v8a.apk"))),
                    ),
            )

    private fun tvAsset(root: JSONObject): JSONObject =
        root.getJSONObject("variants").getJSONObject("tv").getJSONObject("assets").getJSONObject("arm64-v8a")

    @Test
    fun tvSelectsFirstSupportedDeviceAbi() {
        val update = UpdateManifest.parse(manifest().toString(), "tv", listOf("arm64-v8a", "armeabi-v7a"))
        assertEquals("arm64-v8a", update.abi)
        assertEquals(prefix + "tv-arm64-v8a.apk", update.apkUrl)
        assertEquals(1794052800, update.versionCode)
        assertEquals("- 修复缓冲", update.changelog)
        assertEquals(tag, update.releaseTag)
    }

    @Test
    fun armv7DeviceReceivesArmv7Asset() {
        val update = UpdateManifest.parse(manifest().toString(), "tv", listOf("armeabi-v7a", "armeabi"))
        assertEquals("armeabi-v7a", update.abi)
        assertEquals(prefix + "tv-armeabi-v7a.apk", update.apkUrl)
    }

    @Test
    fun supportedArmv7FallbackIsAllowedWhenArm64AssetIsAbsent() {
        val root = manifest()
        root.getJSONObject("variants").getJSONObject("tv").getJSONObject("assets").remove("arm64-v8a")
        val update = UpdateManifest.parse(root.toString(), "tv", listOf("arm64-v8a", "armeabi-v7a"))
        assertEquals("armeabi-v7a", update.abi)
    }

    @Test
    fun phoneCannotAccidentallySelectTelevisionAsset() {
        val update = UpdateManifest.parse(manifest().toString(), "phone", listOf("arm64-v8a", "armeabi-v7a"))
        assertEquals("phone", update.variant)
        assertEquals(prefix + "phone-arm64-v8a.apk", update.apkUrl)
        assertTrue(update.versionName.contains("phone"))
    }

    @Test
    fun phoneRejectsDeviceWithoutArm64RatherThanUsingTvArmv7() {
        assertThrows(IllegalStateException::class.java) {
            UpdateManifest.parse(manifest().toString(), "phone", listOf("armeabi-v7a"))
        }
    }

    @Test
    fun unsupportedArchitectureIsRejected() {
        assertThrows(IllegalStateException::class.java) {
            UpdateManifest.parse(manifest().toString(), "tv", listOf("x86_64", "x86"))
        }
    }

    @Test
    fun missingRequestedVariantDoesNotFallBackToAnotherProfile() {
        val root = manifest()
        root.getJSONObject("variants").remove("phone")
        assertThrows(org.json.JSONException::class.java) {
            UpdateManifest.parse(root.toString(), "phone", listOf("arm64-v8a"))
        }
    }

    @Test
    fun unknownSchemaIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifest.parse(manifest().put("schema", 2).toString(), "tv", listOf("arm64-v8a"))
        }
    }

    @Test
    fun invalidVersionCodesAreRejectedWithoutCoercion() {
        for (code in listOf<Any>(0, -1, 2147483648L, 1.5, "1794052800")) {
            assertThrows("invalid code: $code", IllegalArgumentException::class.java) {
                UpdateManifest.parse(manifest().put("version_code", code).toString(), "tv", listOf("arm64-v8a"))
            }
        }
    }

    @Test
    fun invalidHashesCannotReachDownloader() {
        for (sha in listOf("", "a".repeat(63), "g".repeat(64))) {
            val root = manifest()
            tvAsset(root).put("sha256", sha)
            assertThrows(IllegalArgumentException::class.java) {
                UpdateManifest.parse(root.toString(), "tv", listOf("arm64-v8a"))
            }
        }
    }

    @Test
    fun uppercaseHashIsNormalized() {
        val root = manifest()
        tvAsset(root).put("sha256", hash.uppercase())
        assertEquals(hash, UpdateManifest.parse(root.toString(), "tv", listOf("arm64-v8a")).sha256)
    }

    @Test
    fun unsafeOrWrongReleaseAssetUrlsAreRejected() {
        val badUrls = listOf(
            prefix.replace("https://", "http://") + "tv.apk",
            prefix.replace("github.com", "github.com.attacker.test") + "tv.apk",
            prefix.replace("NNNNNNAAAAAA/blbl-btr", "cat3399/blbl") + "tv.apk",
            prefix.replace(tag, "other-tag") + "tv.apk",
            prefix + "nested/tv.apk",
            prefix + "%2e%2e%2ftv.apk",
            prefix + "tv.apk?redirect=https://attacker.test",
            prefix + "tv.apk#fragment",
            prefix.replace("https://", "https://user@") + "tv.apk",
            prefix + "update.json",
        )
        for (url in badUrls) {
            val root = manifest()
            tvAsset(root).put("url", url)
            assertThrows("unsafe URL: $url", IllegalArgumentException::class.java) {
                UpdateManifest.parse(root.toString(), "tv", listOf("arm64-v8a"))
            }
        }
    }

    @Test
    fun emptyVersionNameAndInvalidReleaseTagAreRejected() {
        val noVersion = manifest()
        noVersion.getJSONObject("variants").getJSONObject("tv").put("version_name", " ")
        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifest.parse(noVersion.toString(), "tv", listOf("arm64-v8a"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifest.parse(manifest().put("release_tag", "../another-release").toString(), "tv", listOf("arm64-v8a"))
        }
    }

    @Test
    fun versionCodeDeterminesUpdateEvenWhenVersionNameIsUnchanged() {
        val update = UpdateManifest.parse(manifest().toString(), "tv", listOf("arm64-v8a"))
        assertTrue(ApkUpdater.isRemoteNewer(update, update.versionCode - 1))
        assertFalse(ApkUpdater.isRemoteNewer(update, update.versionCode))
        assertFalse(ApkUpdater.isRemoteNewer(update, update.versionCode + 1))
    }
}
