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
}
