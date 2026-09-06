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

    @Test fun errorTextFollowsTheSameRenderRule() {
        // Friendly errors are Traditional source strings; every screen must
        // render them through display() so Simplified mode converts them too.
        val t2s = TestConvert()
        val err = "章節列表為空，請稍後再試"
        assertEquals(err, Display.text(t2s, err, simplified = false))
        assertEquals("S:$err", Display.text(t2s, err, simplified = true))
    }
}
