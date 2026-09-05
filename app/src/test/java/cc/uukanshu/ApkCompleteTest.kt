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
            // Byte-exact when size known.
            assertTrue(UpdateDownloader.isInstallable(f, 10L))
            assertFalse(UpdateDownloader.isInstallable(f, 11L))
            // Sizeless release: any non-empty file from a DM Success is installable
            // (alreadyHave/enqueue stay strict via isComplete).
            assertTrue(UpdateDownloader.isInstallable(f, null))
        } finally {
            f.delete()
        }
        val empty = File.createTempFile("apk", ".apk")
        try {
            assertFalse(UpdateDownloader.isInstallable(empty, null))
        } finally {
            empty.delete()
        }
    }
}
