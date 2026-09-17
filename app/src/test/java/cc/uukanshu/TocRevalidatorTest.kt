package cc.uukanshu

import cc.uukanshu.data.parse.Parser
import cc.uukanshu.data.repo.BookRepo
import cc.uukanshu.data.repo.TocRevalidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure shrink guard applied by `BookRepo.detail` (dbWrite-serialized merge).
 * Screen stale-while-revalidate lives in [TocSource] — its contract table is
 * pinned in TocSourceTest.
 */
class TocRevalidatorTest {
    private fun ref(pos: Int, pageId: Long) =
        Parser.ChapterRef(pos, pageId, "t-$pos", "https://uukanshu.cc/book/1/$pageId.html")

    private fun detail(vararg ids: Long): BookRepo.Detail {
        val chapters = ids.mapIndexed { i, id -> ref(i + 1, id) }
        return BookRepo.Detail(
            meta = Parser.BookMeta("T", "A", "", "", "", "", "", null, ""),
            chapters = chapters,
        )
    }

    @Test fun emptyFreshIsRejected() {
        assertTrue(TocRevalidator.shouldAcceptFresh(listOf(ref(1, 1L))))
        assertEquals(false, TocRevalidator.shouldAcceptFresh(emptyList()))
    }

    @Test fun shrunkenFreshIsRejected() {
        // Truncated parse (2 chapters vs 5 cached): never wipe.
        val fresh = listOf(ref(1, 1L), ref(2, 2L))
        assertEquals(false, TocRevalidator.shouldAcceptFresh(fresh, 5))
    }

    @Test fun equalOrGrownFreshIsAccepted() {
        val fresh = listOf(ref(1, 1L), ref(2, 2L))
        assertTrue(TocRevalidator.shouldAcceptFresh(fresh, 2))
        assertTrue(TocRevalidator.shouldAcceptFresh(fresh, 1))
        assertTrue(TocRevalidator.shouldAcceptFresh(fresh))
    }
}
