package cc.uukanshu

import cc.uukanshu.data.update.UpdateDownloader
import org.junit.Assert.assertEquals
import org.junit.Test

/** The strict enqueue/already-have table, asserted through pure
 *  [UpdateDownloader.apkState] — no temp files: purity means literals in,
 *  state out. IO wrapper coverage lives in UpdateIntegrityTest. */
class ApkCompleteTest {
    @Test fun exactSizeIsComplete() {
        assertEquals(UpdateDownloader.ApkState.Ready, UpdateDownloader.apkState(true, 10L, 10L, dmSuccess = false))
        assertEquals(UpdateDownloader.ApkState.Partial, UpdateDownloader.apkState(true, 10L, 11L, dmSuccess = false))
        assertEquals(UpdateDownloader.ApkState.Partial, UpdateDownloader.apkState(true, 10L, null, dmSuccess = false))
    }

    @Test fun missingIsNotComplete() {
        assertEquals(UpdateDownloader.ApkState.Missing, UpdateDownloader.apkState(false, 0L, 10L, dmSuccess = false))
        assertEquals(UpdateDownloader.ApkState.Missing, UpdateDownloader.apkState(true, 0L, 10L, dmSuccess = false))
    }

    @Test fun installableAllowsSizelessSuccess() {
        // Byte-exact when size known (receipt irrelevant).
        assertEquals(UpdateDownloader.ApkState.Ready, UpdateDownloader.apkState(true, 10L, 10L, dmSuccess = false))
        assertEquals(UpdateDownloader.ApkState.Partial, UpdateDownloader.apkState(true, 10L, 11L, dmSuccess = false))
        assertEquals(UpdateDownloader.ApkState.Partial, UpdateDownloader.apkState(true, 10L, 11L, dmSuccess = true))
        // Sizeless release: only a non-empty file with a fresh DM Success
        // receipt is installable — a killed-process partial with unknown
        // size and no receipt must re-download, never install.
        assertEquals(UpdateDownloader.ApkState.Ready, UpdateDownloader.apkState(true, 10L, null, dmSuccess = true))
        assertEquals(UpdateDownloader.ApkState.Partial, UpdateDownloader.apkState(true, 10L, null, dmSuccess = false))
        assertEquals(UpdateDownloader.ApkState.Missing, UpdateDownloader.apkState(true, 0L, null, dmSuccess = true))
        assertEquals(UpdateDownloader.ApkState.Missing, UpdateDownloader.apkState(true, 0L, null, dmSuccess = false))
        assertEquals(UpdateDownloader.ApkState.Missing, UpdateDownloader.apkState(false, 0L, null, dmSuccess = true))
    }
}
