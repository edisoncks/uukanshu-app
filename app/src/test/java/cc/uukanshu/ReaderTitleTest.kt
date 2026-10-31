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

    @Test fun emptyInputsResolveToEmpty() {
        // The resolver takes no chapter title at all (see ReaderTitle), so the
        // chapter-title property is enforced by the signature, not here. What
        // is left to pin is the boundary: no TOC meta, no network title and no
        // previous book resolve to empty, never to an invented title.
        assertEquals("", ReaderTitle.resolve("", "", ""))
        // A stale previous book must still lose to the TOC meta title.
        assertEquals("書名", ReaderTitle.resolve("書名", "", "舊書名"))
    }
}
