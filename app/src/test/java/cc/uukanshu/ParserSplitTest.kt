package cc.uukanshu

import cc.uukanshu.data.convert.T2S
import cc.uukanshu.data.parse.BookIds
import cc.uukanshu.data.parse.Parser
import cc.uukanshu.ui.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserSplitTest {
    @Test fun bookIdsNormalizeOnce() {
        assertEquals("1", BookIds.normalizeBookId("001"))
        assertEquals("1", Parser.normalizeBookId("001"))
        assertNull(BookIds.normalizeBookId("99999999999999999999"))
    }

    @Test fun tocKeepsLastOccurrence() {
        val html = """
            <a href="/book/1/101.html">Old title</a>
            <a href="/book/2/999.html">Other book</a>
            <a href="/book/1/101.html?from=hot">New title</a>
            <a href="/book/1/102.html">Second</a>
        """.trimIndent()
        val toc = Parser.parseToc(html, "1")
        assertEquals(listOf(101L, 102L), toc.map { it.pageId })
        assertEquals("New title", toc[0].title)
    }

    @Test fun chapterNavToleratesTrackingParams() {
        assertEquals(
            "https://uukanshu.cc/book/1/102.html",
            Parser.canonicalChapterUrl("/book/1/102.html?from=x#y", "https://uukanshu.cc/book/1/101.html"),
        )
        assertNull(Parser.canonicalChapterUrl("/book/1/", "https://uukanshu.cc/book/1/101.html"))
    }

    @Test fun appThemeIsDarkPure() {
        assertTrue(AppTheme.isDark("dark", false))
        assertFalse(AppTheme.isDark("light", true))
        assertTrue(AppTheme.isDark("system", true))
        assertFalse(AppTheme.isDark("system", false))
        assertFalse(AppTheme.isDark("bogus", false))
    }

    @Test fun t2sCachePolicyBoundsMemory() {
        assertTrue(T2S.CachePolicy.shouldCache("短標題"))
        assertFalse(T2S.CachePolicy.shouldCache(""))
        assertFalse(T2S.CachePolicy.shouldCache("x".repeat(5000)))
        assertEquals(500, T2S.CachePolicy.MAX_ENTRIES)
    }
}
