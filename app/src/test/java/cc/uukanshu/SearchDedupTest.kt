package cc.uukanshu

import cc.uukanshu.data.parse.Parser
import cc.uukanshu.ui.search.SearchViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression: search cards come from the same parser as Home, so the same
 * duplicate-card payload must not produce duplicate LazyColumn keys.
 */
class SearchDedupTest {
    private fun item(id: String, title: String = "t-$id") =
        Parser.BookItem(id = id, title = title)

    @Test fun duplicateCardsCollapseById() {
        val books = listOf(item("1", "same"), item("1", "same"), item("2", "other"))
        val deduped = SearchViewModel.dedupBooks(books)
        assertEquals(listOf("1", "2"), deduped.map { it.id })
        // Deduped ids are valid unique LazyColumn keys.
        assertEquals(deduped.size, deduped.map { it.id }.toSet().size)
    }

    @Test fun retitledDuplicateKeepsFirst() {
        val deduped = SearchViewModel.dedupBooks(
            listOf(item("1", "old"), item("1", "new")),
        )
        assertEquals(1, deduped.size)
        assertEquals("old", deduped[0].title)
    }
}
