package cc.uukanshu

import cc.uukanshu.data.update.UpdateDownloadRecord
import cc.uukanshu.data.update.UpdateInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Request-identity rules for the durable updater record (see UpdateViewModel.clearDownloadRecord). */
class UpdateDownloadRecordTest {
    private fun info(
        tag: String = "v2.0.0",
        version: String = "2.0.0",
        size: Long? = 5L,
        sha256: String? = "a".repeat(64),
    ) = UpdateInfo(
        tag = tag,
        version = version,
        changelog = "notes",
        apkUrl = "https://example.com/uukanshu-$version.apk",
        apkName = "uukanshu-$version.apk",
        htmlUrl = "https://example.com/releases/$version",
        size = size,
        sha256 = sha256,
    )

    @Test fun sameRequestMatchesDespiteRefreshedSizeOrDigest() {
        // A mid-flight re-check can refresh size/digest; the request is still the
        // same one, so its record must be clearable.
        val enqueued = UpdateDownloadRecord(info(), downloadId = 42L)
        val dialogCopy = UpdateDownloadRecord(info(size = 6L, sha256 = "b".repeat(64)))
        assertTrue(enqueued.sameRequestAs(dialogCopy))
    }

    @Test fun sameReleaseWithOnlyOneKnownIdMatches() {
        // The post-success cancel path has a null id; release identity must still match.
        assertTrue(
            UpdateDownloadRecord(info()).sameRequestAs(UpdateDownloadRecord(info(), downloadId = 42L)),
        )
    }

    @Test fun differentRequestIdsAreNotTheSameRequest() {
        assertFalse(
            UpdateDownloadRecord(info(), downloadId = 42L)
                .sameRequestAs(UpdateDownloadRecord(info(), downloadId = 43L)),
        )
    }

    @Test fun differentReleaseWithUnknownIdsIsNotTheSameRequest() {
        val other = info(tag = "v3.0.0", version = "3.0.0")
        assertFalse(
            UpdateDownloadRecord(info()).sameRequestAs(UpdateDownloadRecord(other)),
        )
    }
}
