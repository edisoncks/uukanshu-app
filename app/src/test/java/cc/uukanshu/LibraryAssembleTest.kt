package cc.uukanshu

import cc.uukanshu.data.db.BookEntity
import cc.uukanshu.data.db.ChapterStats
import cc.uukanshu.data.repo.ShelfOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure shelf assembly shared by one-shot library() and libraryFlow(). */
class LibraryAssembleTest {
    private fun book(id: String, at: Long) = BookEntity(id, "T$id", "A", "", "", "", updatedAt = at)
    private fun stats(id: String, total: Int, cached: Int) = ChapterStats(id, total, cached, bytes = cached * 10L)

    @Test fun hidesUncachedAndOrdersByActivity() {
        val rows = listOf(book("a", 100L), book("b", 300L), book("c", 200L))
        val stats = listOf(stats("a", 10, 0), stats("b", 10, 5), stats("c", 10, 2))
        val out = ShelfOrder.assemble(rows, stats, progressAt = mapOf("c" to 400L))
        // a has zero cached → hidden; c read-bump beats b download-bump.
        assertEquals(listOf("c", "b"), out.map { it.id })
        assertEquals(2, out[0].cached)
    }

    @Test fun missingStatsStayOffShelf() {
        val rows = listOf(book("a", 100L))
        assertEquals(emptyList<String>(), ShelfOrder.assemble(rows, emptyList(), emptyMap()).map { it.id })
    }
}
