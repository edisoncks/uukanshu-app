package cc.uukanshu

import cc.uukanshu.data.parse.Parser
import cc.uukanshu.ui.home.HomeViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the recent-tab scroll crash:
 * /top/lastupdate_N.html shifts live, so page 1 and 2 can share an id
 * (verified live: 25745 ended page 1 and started page 2). Appending without
 * dedup produced duplicate LazyColumn keys (id+title) -> hard crash.
 */
class HomeMergeTest {
    private fun item(id: String, title: String = "t-$id") =
        Parser.BookItem(id = id, title = title)

    @Test fun overlappingPagesDedupById() {
        val p1 = (1..29).map { item("$it") } + item("25745", "你有天眼不去賭石，又在亂看")
        val p2 = listOf(item("25745", "你有天眼不去賭石，又在亂看")) +
            (30..58).map { item("$it") }
        val merged = HomeViewModel.mergeBooks(p1, p2)
        assertEquals(59, merged.size)
        assertEquals(59, merged.map { it.id }.toSet().size)
        // First occurrence wins, order stable.
        assertEquals("1", merged.first().id)
        assertEquals("58", merged.last().id)
    }

    @Test fun sameIdRetitledKeepsFirstAndStaysUnique() {
        val merged = HomeViewModel.mergeBooks(
            listOf(item("1", "old")),
            listOf(item("1", "new")),
        )
        assertEquals(1, merged.size)
        assertEquals("old", merged[0].title)
    }

    @Test fun emptyNextKeepsOld() {
        val old = listOf(item("1"), item("2"))
        assertEquals(old, HomeViewModel.mergeBooks(old, emptyList()))
    }

    @Test fun mergedIdsAreValidLazyKeys() {
        val merged = HomeViewModel.mergeBooks(
            listOf(item("1", "a"), item("2", "b")),
            listOf(item("2", "b"), item("3", "c")),
        )
        val keys = merged.map { it.id }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.containsAll(listOf("1", "2", "3")))
    }
}
