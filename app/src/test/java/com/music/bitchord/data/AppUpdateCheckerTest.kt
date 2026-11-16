package com.music.bitchord.data

import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdateCheckerTest {
    private fun release(vararg names: String, state: String = "uploaded") = buildJsonObject {
        put("tag_name", "2.4")
        putJsonArray("assets") {
            names.forEach { name ->
                addJsonObject {
                    put("name", name)
                    put("state", state)
                    put("browser_download_url", "https://github.com/bijimathew/PureBits/releases/download/2.4/$name")
                }
            }
        }
    }

    @Test fun selectsExistingPublishedUniversalApk() {
        val name = "app-prod-universal-release.apk"
        val selected = AppUpdateChecker.apkAssetUrl(release("app-prod-arm64-v8a-release.apk", name), listOf("arm64-v8a"))
        assertEquals("https://github.com/bijimathew/PureBits/releases/download/2.4/$name", selected)
    }

    @Test fun usesDeviceAbiOrderWhenUniversalIsMissing() {
        val name = "app-prod-arm64-v8a-release.apk"
        val selected = AppUpdateChecker.apkAssetUrl(release("app-prod-armeabi-v7a-release.apk", name), listOf("arm64-v8a", "armeabi-v7a"))
        assertEquals("https://github.com/bijimathew/PureBits/releases/download/2.4/$name", selected)
    }

    @Test fun acceptsVersionedPureBitsNames() {
        for (name in listOf("PureBits-v2.4.apk", "PureBits-2.4.apk", "PureBits-v2.4_arm64-v8a.apk")) {
            assertEquals("https://github.com/bijimathew/PureBits/releases/download/2.4/$name",
                AppUpdateChecker.apkAssetUrl(release(name), listOf("arm64-v8a")))
        }
    }

    @Test fun rejectsOtherAppsUnsignedDebugAndIncompatibleAssets() {
        assertNull(AppUpdateChecker.apkAssetUrl(release(
            "BitChord-v2.4.apk", "BitChordTV-v2.4.apk", "app-dev-universal-debug.apk",
            "app-prod-universal-release-unsigned.apk", "app-prod-x86_64-release.apk",
            "PureBits-2.4-pb-ui-arm64-dev-debug.apk", "PureBits-v2.4_x86_64.apk",
        ), listOf("arm64-v8a")))
    }

    @Test fun ignoresIncompleteUploadsAndMissingAssets() {
        assertNull(AppUpdateChecker.apkAssetUrl(release("app-prod-universal-release.apk", state = "new"), listOf("arm64-v8a")))
        assertNull(AppUpdateChecker.apkAssetUrl(buildJsonObject {}, listOf("arm64-v8a")))
    }
}
