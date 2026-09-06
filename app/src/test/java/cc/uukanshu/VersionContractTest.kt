package cc.uukanshu

import cc.uukanshu.data.update.UpdateApi
import cc.uukanshu.data.update.VersionCompare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Updater release-shape contract: tag vX.Y.Z == versionName X.Y.Z, exactly
 * one asset named uukanshu-X.Y.Z.apk. Guards the version bump + gh release
 * steps so a mismatched tag/asset fails closed instead of bricking updates.
 */
class VersionContractTest {
    @Test fun expectedAssetNameDerivesFromTag() {
        val tag = "v1.0.34"
        assertEquals("uukanshu-1.0.34.apk", "uukanshu-${VersionCompare.normalize(tag)}.apk")
    }

    @Test fun mismatchedAssetFailsClosed() {
        assertNull(
            UpdateApi.parse(
                """{"tag_name":"v1.0.34","assets":[{"name":"uukanshu-1.0.33.apk","browser_download_url":"https://example.com/old.apk"}]}""",
            ),
        )
    }

    @Test fun secondApkNeverOffered() {
        // First exact match wins; a payload with only a stray apk yields null.
        assertNull(
            UpdateApi.parse(
                """{"tag_name":"v1.0.34","assets":[{"name":"app.apk","browser_download_url":"https://example.com/a.apk"}]}""",
            ),
        )
    }

    @Test fun newerDetection() {
        assertTrue(VersionCompare.isNewer("1.0.34", "1.0.33"))
        assertFalse(VersionCompare.isNewer("1.0.33", "1.0.34"))
        assertFalse(VersionCompare.isNewer("1.0.34", "1.0.34"))
    }

    @Test fun currentReleaseParses() {
        val info = UpdateApi.parse(
            """{"tag_name":"v1.0.34","body":"hardening","html_url":"https://github.com/edisoncks/uukanshu-app/releases/tag/v1.0.34","assets":[{"name":"uukanshu-1.0.34.apk","browser_download_url":"https://example.com/u.apk","size":12345}]}""",
        )
        assertNotNull(info)
        assertEquals("1.0.34", info!!.version)
        assertEquals(12345L, info.size)
    }
}
