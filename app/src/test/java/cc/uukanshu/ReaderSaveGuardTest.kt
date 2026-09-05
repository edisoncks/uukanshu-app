package cc.uukanshu

import cc.uukanshu.data.parse.Parser
import cc.uukanshu.ui.reader.ReaderViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSaveGuardTest {
    private fun ref(pos: Int, pageId: Long) =
        Parser.ChapterRef(pos, pageId, "t-$pos", "https://uukanshu.cc/book/1/$pageId.html")

    @Test fun matchingPageIdSaves() {
        val toc = listOf(ref(1, 101L), ref(2, 102L))
        assertTrue(ReaderViewModel.shouldSaveChapter(toc, 2, 102L))
    }

    @Test fun shiftedTocSkips() {
        val snapshot = listOf(ref(1, 101L), ref(2, 102L))
        val shifted = listOf(ref(1, 101L), ref(2, 999L))
        // Fetched for old pageId 102, live is 999 -> skip.
        assertFalse(ReaderViewModel.shouldSaveChapter(shifted, 2, snapshot[1].pageId))
    }

    @Test fun outOfRangeSkips() {
        val toc = listOf(ref(1, 101L))
        assertFalse(ReaderViewModel.shouldSaveChapter(toc, 5, 105L))
        assertFalse(ReaderViewModel.shouldSaveChapter(emptyList(), 1, 101L))
    }
}
