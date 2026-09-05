package cc.uukanshu

import cc.uukanshu.core.Errors
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class ErrorsTest {
    @Test fun messageFormatsClassAndMessage() {
        assertEquals(
            "IOException: boom",
            Errors.message(IOException("boom")),
        )
    }

    @Test fun messageOrThrowRethrowsCancellation() {
        try {
            Errors.messageOrThrow(CancellationException("stop"))
            fail("must rethrow CancellationException")
        } catch (e: CancellationException) {
            assertEquals("stop", e.message)
        }
    }

    @Test fun messageOrThrowReturnsMessageOtherwise() {
        assertEquals(
            "IOException: boom",
            Errors.messageOrThrow(IOException("boom")),
        )
    }
}
