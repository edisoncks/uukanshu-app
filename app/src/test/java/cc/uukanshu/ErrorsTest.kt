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

    @Test fun userMessageStripsClassPrefix() {
        assertEquals("boom", Errors.userMessage(IOException("boom")))
    }

    @Test fun userMessageFallsBackToClassWhenBlank() {
        assertEquals("IOException", Errors.userMessage(IOException()))
        assertEquals("IOException", Errors.userMessage(IOException("  ")))
    }

    @Test fun userMessageOrThrowRethrowsCancellation() {
        try {
            Errors.userMessageOrThrow(CancellationException("stop"))
            fail("must rethrow CancellationException")
        } catch (e: CancellationException) {
            assertEquals("stop", e.message)
        }
    }

    @Test fun userMessageOrThrowReturnsFriendlyOtherwise() {
        assertEquals("boom", Errors.userMessageOrThrow(IOException("boom")))
    }
}
