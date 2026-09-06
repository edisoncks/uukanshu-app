package cc.uukanshu

import cc.uukanshu.core.Display
import cc.uukanshu.data.convert.T2S
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
        val t2s = T2S()
        val trad = "生命不息，奮鬥不止"
        val simp = "生命不息，奋斗不止"
        assertEquals(trad, Display.text(t2s, trad, simplified = false))
        assertEquals(simp, Display.text(t2s, trad, simplified = true))
        assertEquals("", Display.text(t2s, "", simplified = false))
    }

    @Test fun errorTextFollowsTheSameRenderRule() {
        // Friendly errors are Traditional source strings; every screen must
        // render them through display() so Simplified mode converts them too.
        val t2s = T2S()
        val err = "章節列表為空，請稍後再試"
        assertEquals(err, Display.text(t2s, err, simplified = false))
        assertEquals(t2s.convert(err), Display.text(t2s, err, simplified = true))
    }
}
