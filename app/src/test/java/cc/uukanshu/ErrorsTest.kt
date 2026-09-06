package cc.uukanshu

import cc.uukanshu.core.Errors
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class ErrorsTest {
    @Test fun friendlyFallsBackToMessage() {
        assertEquals("boom", Errors.friendly(IOException("boom")))
    }

    @Test fun friendlyFallsBackToClassWhenBlank() {
        assertEquals("IOException", Errors.friendly(IOException()))
        assertEquals("IOException", Errors.friendly(IOException("  ")))
    }
}
