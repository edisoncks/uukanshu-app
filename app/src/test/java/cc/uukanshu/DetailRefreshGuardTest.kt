package cc.uukanshu

import cc.uukanshu.data.parse.Parser
import cc.uukanshu.ui.detail.DetailViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailRefreshGuardTest {
    private fun ref(pos: Int, pageId: Long) =
        Parser.ChapterRef(pos, pageId, "t-$pos", "https://uukanshu.cc/book/1/$pageId.html")

    @Test fun nonEmptyFreshIsAccepted() {
        assertTrue(DetailViewModel.shouldAcceptFresh(listOf(ref(1, 101L))))
    }

    @Test fun emptyFreshIsRejected() {
        // Block page / captive portal / layout change: never wipe paint.
        assertFalse(DetailViewModel.shouldAcceptFresh(emptyList()))
    }

    @Test fun shrunkenFreshIsRejected() {
        // Truncated parse: 2 fresh vs 5 cached must not wipe.
        val fresh = listOf(ref(1, 101L), ref(2, 102L))
        assertFalse(DetailViewModel.shouldAcceptFresh(fresh, 5))
    }

    @Test fun equalOrGrownFreshIsAccepted() {
        val fresh = listOf(ref(1, 101L), ref(2, 102L))
        assertTrue(DetailViewModel.shouldAcceptFresh(fresh, 2))
        assertTrue(DetailViewModel.shouldAcceptFresh(fresh, 1))
    }
}
