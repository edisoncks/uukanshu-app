package cc.uukanshu

import cc.uukanshu.core.Errors
import cc.uukanshu.data.update.UpdateDownloader
import cc.uukanshu.data.update.UpdateInfo
import cc.uukanshu.ui.update.DownloadSuccess
import cc.uukanshu.ui.update.UpdateViewModel
import cc.uukanshu.ui.update.applyDownloadSuccess
import cc.uukanshu.ui.update.canStartCheck
import cc.uukanshu.ui.update.canStartInstall
import cc.uukanshu.ui.update.classifyDownloadSuccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure updater decisions: no Android, no Robolectric, no threads. Covers the
 * transition table that the VM integration tests can only reach through IO.
 */
class UpdateDecisionsTest {
    private fun info(
        version: String = "9.9.9",
        sha256: String? = "a".repeat(64),
        size: Long? = 5L,
    ) = UpdateInfo(
        tag = "v$version",
        version = version,
        changelog = "",
        apkUrl = "https://example.com/u.apk",
        apkName = "uukanshu-$version.apk",
        htmlUrl = "https://example.com/rel",
        size = size,
        sha256 = sha256,
    )

    // -- gates ------------------------------------------------------------

    @Test fun `check gate is open only when not checking`() {
        assertTrue(canStartCheck(checking = false))
        assertFalse(canStartCheck(checking = true))
    }

    @Test fun `install gate is open only when not installing`() {
        assertTrue(canStartInstall(installing = false))
        assertFalse(canStartInstall(installing = true))
    }

    // -- classifyDownloadSuccess -----------------------------------------

    @Test fun `different enqueued version is stale`() {
        val enqueued = info(version = "9.9.9")
        val current = info(version = "9.9.10")
        assertEquals(
            DownloadSuccess.StaleInfo,
            classifyDownloadSuccess(
                currentInfo = current,
                enqueued = enqueued,
                apkState = UpdateDownloader.ApkState.Ready,
                fileLength = 5L,
            ),
        )
    }

    @Test fun `same version different digest is stale`() {
        // Full-info pin, not version-string: a swap mid-flight must not mint.
        val enqueued = info(sha256 = "a".repeat(64))
        val current = info(sha256 = "b".repeat(64))
        assertEquals(
            DownloadSuccess.StaleInfo,
            classifyDownloadSuccess(
                currentInfo = current,
                enqueued = enqueued,
                apkState = UpdateDownloader.ApkState.Ready,
                fileLength = 5L,
            ),
        )
    }

    @Test fun `null info is stale`() {
        assertEquals(
            DownloadSuccess.StaleInfo,
            classifyDownloadSuccess(
                currentInfo = null,
                enqueued = info(),
                apkState = UpdateDownloader.ApkState.Ready,
                fileLength = 5L,
            ),
        )
    }

    @Test fun `no release digest accepts regardless of probe state`() {
        // DM success is the whole verdict when there is no digest: the size
        // probe still runs (and skips hashing), but its result is not consulted.
        val enqueued = info(sha256 = null)
        assertEquals(
            DownloadSuccess.Ready,
            classifyDownloadSuccess(
                currentInfo = enqueued,
                enqueued = enqueued,
                apkState = UpdateDownloader.ApkState.Missing,
                fileLength = 0L,
            ),
        )
    }

    @Test fun `matching digest is ready`() {
        val enqueued = info()
        assertEquals(
            DownloadSuccess.Ready,
            classifyDownloadSuccess(
                currentInfo = enqueued,
                enqueued = enqueued,
                apkState = UpdateDownloader.ApkState.Ready,
                fileLength = 5L,
            ),
        )
    }

    @Test fun `same size wrong digest is a checksum failure`() {
        val enqueued = info()
        assertEquals(
            DownloadSuccess.ChecksumFailed(UpdateDownloader.ApkFailure.CHECKSUM_MISMATCH),
            classifyDownloadSuccess(
                currentInfo = enqueued,
                enqueued = enqueued,
                apkState = UpdateDownloader.ApkState.Partial,
                fileLength = 5L,
            ),
        )
    }

    @Test fun `wrong size with digest is incomplete not checksum`() {
        val enqueued = info()
        assertEquals(
            DownloadSuccess.ChecksumFailed(UpdateDownloader.ApkFailure.INCOMPLETE),
            classifyDownloadSuccess(
                currentInfo = enqueued,
                enqueued = enqueued,
                apkState = UpdateDownloader.ApkState.Partial,
                fileLength = 3L,
            ),
        )
    }

    @Test fun `missing file is incomplete`() {
        val enqueued = info()
        assertEquals(
            DownloadSuccess.ChecksumFailed(UpdateDownloader.ApkFailure.INCOMPLETE),
            classifyDownloadSuccess(
                currentInfo = enqueued,
                enqueued = enqueued,
                apkState = UpdateDownloader.ApkState.Missing,
                fileLength = 0L,
            ),
        )
    }

    // -- applyDownloadSuccess --------------------------------------------

    @Test fun `stale outcome only clears terminal download state`() {
        val before = UpdateViewModel.Ui(
            visible = true,
            downloading = true,
            progress = 0.5f,
            downloadId = 42L,
            info = info(),
        )
        val after = applyDownloadSuccess(before, DownloadSuccess.StaleInfo)
        assertFalse(after.downloading)
        assertNull(after.downloadId)
        assertFalse(after.fileReady)
        assertFalse(after.downloadSucceeded)
        assertTrue(after.visible)
        assertEquals(0.5f, after.progress)
        assertNull(after.error)
    }

    @Test fun `ready outcome mints the receipt`() {
        val before = UpdateViewModel.Ui(downloading = true, downloadId = 42L, info = info())
        val after = applyDownloadSuccess(before, DownloadSuccess.Ready)
        assertFalse(after.downloading)
        assertNull(after.downloadId)
        assertTrue(after.fileReady)
        assertTrue(after.downloadSucceeded)
        assertNull(after.error)
    }

    @Test fun `checksum failure fails closed with the mapped message`() {
        val before = UpdateViewModel.Ui(downloading = true, downloadId = 42L, info = info())
        val after = applyDownloadSuccess(
            before,
            DownloadSuccess.ChecksumFailed(UpdateDownloader.ApkFailure.CHECKSUM_MISMATCH),
        )
        assertFalse(after.downloading)
        assertNull(after.downloadId)
        assertFalse(after.fileReady)
        assertFalse(after.downloadSucceeded)
        assertEquals(
            Errors.friendly(UpdateDownloader.ApkFailure.CHECKSUM_MISMATCH),
            after.error,
        )
    }
}
