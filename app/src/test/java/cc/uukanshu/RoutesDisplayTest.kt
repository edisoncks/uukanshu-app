package cc.uukanshu

import cc.uukanshu.core.Display
import cc.uukanshu.ui.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutesDisplayTest {
    @Test fun detailRouteShape() {
        assertEquals("detail/12", Routes.detail("12"))
        assertEquals("detail/12", Routes.detailBase("12"))
    }

    @Test fun readerRouteCarriesPageId() {
        assertEquals("reader/1/2/101", Routes.reader("1", 2, 101L))
        // Default pageId keeps old call sites compiling; reader falls back to position.
        assertEquals("reader/1/2/0", Routes.reader("1", 2))
    }

    @Test fun patternsDeclarePlaceholders() {
        assertTrue(Routes.DETAIL_PATTERN.contains("{bookId}"))
        assertTrue(Routes.READER_PATTERN.contains("{pageId}"))
    }

    @Test fun displayConvertsOnlyWhenSimplified() {
        val t2s = TestConvert()
        assertEquals("raw", Display.text(t2s, "raw", simplified = false))
        assertEquals("S:raw", Display.text(t2s, "raw", simplified = true))
        assertEquals("", Display.text(t2s, "", simplified = false))
    }
}
