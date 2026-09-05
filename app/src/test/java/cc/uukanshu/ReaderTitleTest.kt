package cc.uukanshu

import cc.uukanshu.ui.reader.ReaderTitle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression test for the reader duplicate/stale title bug.
 *
 * `ui.book` must always be the book name. The old cached path used
 * `_ui.value.book.ifEmpty { ref.title }`, so a fresh cached open showed
 * the chapter title twice, and paging kept the previous chapter title
 * in the header. `ref.title` must never become `ui.book`.
 */
class ReaderTitleTest {
    @Test fun freshCachedLoadUsesMetaNotChapterTitle() {
        // meta="天魔降臨", chapter ref.title="001，第一章" — header must be the book.
        assertEquals("天魔降臨", ReaderTitle.resolve("天魔降臨", "", ""))
    }

    @Test fun nextCachedChapterKeepsBookTitle() {
        // Paging N -> N+1 while both cached: header stays constant.
        val first = ReaderTitle.resolve("天魔降臨", "", "")
        val second = ReaderTitle.resolve("天魔降臨", "", first)
        assertEquals("天魔降臨", first)
        assertEquals("天魔降臨", second)
    }

    @Test fun emptyMetaFallsBackToNetworkBook() {
        assertEquals("書名-net", ReaderTitle.resolve("", "書名-net", ""))
    }

    @Test fun emptyMetaAndNetworkKeepsPrevious() {
        assertEquals("書名-prev", ReaderTitle.resolve("", "", "書名-prev"))
    }

    @Test fun neverUsesChapterTitle() {
        // Chapter titles must not leak into the book header slot.
        val chapterTitle = "002，第二章"
        val resolved = ReaderTitle.resolve("書名", "", "舊書名")
        assertEquals("書名", resolved)
        // Sanity: the resolver has no chapter-title input to confuse.
        assert(resolved != chapterTitle)
    }
}
