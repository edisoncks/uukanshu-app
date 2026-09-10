package cc.uukanshu

import cc.uukanshu.data.update.UpdateDownloader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** The strict enqueue/already-have table, asserted through
 *  [UpdateDownloader.apkState] — the same table the production gates use;
 *  there is no separate predicate layer anymore. */
class ApkCompleteTest {
    @Test fun exactSizeIsComplete() {
        val f = File.createTempFile("apk", ".apk").apply { writeBytes(ByteArray(10)) }
        try {
            assertEquals(UpdateDownloader.ApkState.Ready, UpdateDownloader.apkState(f, 10L, dmSuccess = false))
            assertEquals(UpdateDownloader.ApkState.Partial, UpdateDownloader.apkState(f, 11L, dmSuccess = false))
            assertEquals(UpdateDownloader.ApkState.Partial, UpdateDownloader.apkState(f, null, dmSuccess = false))
        } finally {
            f.delete()
        }
    }

    @Test fun missingIsNotComplete() {
        val f = File("/tmp/uukanshu-test-missing-${System.nanoTime()}.apk")
        assertFalse(f.exists())
        assertEquals(UpdateDownloader.ApkState.Missing, UpdateDownloader.apkState(f, 10L, dmSuccess = false))
    }

    @Test fun installableAllowsSizelessSuccess() {
        val f = File.createTempFile("apk", ".apk").apply { writeBytes(ByteArray(10)) }
        try {
            // Byte-exact when size known (receipt irrelevant).
            assertEquals(UpdateDownloader.ApkState.Ready, UpdateDownloader.apkState(f, 10L, dmSuccess = false))
            assertEquals(UpdateDownloader.ApkState.Partial, UpdateDownloader.apkState(f, 11L, dmSuccess = false))
            assertEquals(UpdateDownloader.ApkState.Partial, UpdateDownloader.apkState(f, 11L, dmSuccess = true))
            // Sizeless release: only a non-empty file with a fresh DM Success
            // receipt is installable — a killed-process partial with unknown
            // size and no receipt must re-download, never install.
            assertEquals(UpdateDownloader.ApkState.Ready, UpdateDownloader.apkState(f, null, dmSuccess = true))
            assertEquals(UpdateDownloader.ApkState.Partial, UpdateDownloader.apkState(f, null, dmSuccess = false))
        } finally {
            f.delete()
        }
        val empty = File.createTempFile("apk", ".apk")
        try {
            assertEquals(UpdateDownloader.ApkState.Missing, UpdateDownloader.apkState(empty, null, dmSuccess = true))
            assertEquals(UpdateDownloader.ApkState.Missing, UpdateDownloader.apkState(empty, null, dmSuccess = false))
        } finally {
            empty.delete()
        }
    }
}
