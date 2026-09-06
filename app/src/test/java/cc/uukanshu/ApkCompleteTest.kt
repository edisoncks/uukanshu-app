package cc.uukanshu

import cc.uukanshu.data.update.UpdateDownloader
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ApkCompleteTest {
    @Test fun exactSizeIsComplete() {
        val f = File.createTempFile("apk", ".apk").apply { writeBytes(ByteArray(10)) }
        try {
            assertTrue(UpdateDownloader.isComplete(f, 10L))
            assertFalse(UpdateDownloader.isComplete(f, 11L))
            assertFalse(UpdateDownloader.isComplete(f, null))
        } finally {
            f.delete()
        }
    }

    @Test fun missingIsNotComplete() {
        val f = File("/tmp/uukanshu-test-missing-${System.nanoTime()}.apk")
        assertFalse(f.exists())
        assertFalse(UpdateDownloader.isComplete(f, 10L))
    }

    @Test fun installableAllowsSizelessSuccess() {
        val f = File.createTempFile("apk", ".apk").apply { writeBytes(ByteArray(10)) }
        try {
            // Byte-exact when size known (receipt irrelevant).
            assertTrue(UpdateDownloader.isInstallable(f, 10L, dmSuccess = false))
            assertFalse(UpdateDownloader.isInstallable(f, 11L, dmSuccess = false))
            assertFalse(UpdateDownloader.isInstallable(f, 11L, dmSuccess = true))
            // Sizeless release: only a non-empty file with a fresh DM Success
            // receipt is installable — a killed-process partial with unknown
            // size and no receipt must re-download, never install.
            assertTrue(UpdateDownloader.isInstallable(f, null, dmSuccess = true))
            assertFalse(UpdateDownloader.isInstallable(f, null, dmSuccess = false))
        } finally {
            f.delete()
        }
        val empty = File.createTempFile("apk", ".apk")
        try {
            assertFalse(UpdateDownloader.isInstallable(empty, null, dmSuccess = true))
            assertFalse(UpdateDownloader.isInstallable(empty, null, dmSuccess = false))
        } finally {
            empty.delete()
        }
    }
}
