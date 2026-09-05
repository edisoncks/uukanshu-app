package cc.uukanshu

import androidx.paging.PagingSource
import cc.uukanshu.data.paging.BookPagingSource
import cc.uukanshu.data.parse.Parser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Regression for the recent-tab scroll crash (replaces HomeMergeTest):
 * /top/lastupdate_N.html shifts live, so page 1 and 2 can share an id
 * (verified live: 25745 ended page 1 and started page 2). The dedup now
 * lives in [BookPagingSource] instead of a hand-rolled VM merge.
 */
class BookPagingSourceTest {
    private fun item(id: String, title: String = "t-$id") =
        Parser.BookItem(id = id, title = title)

    private fun refresh(key: Int? = null) =
        PagingSource.LoadParams.Refresh(key = key, loadSize = 20, placeholdersEnabled = false)

    @Test fun overlappingPagesDedupById() = runBlocking {
        val p1 = (1..29).map { item("$it") } + item("25745", "你有天眼不去賭石，又在亂看")
        val p2 = listOf(item("25745", "你有天眼不去賭石，又在亂看")) +
            (30..58).map { item("$it") }
        val pages = mapOf(1 to p1, 2 to p2)
        val src = BookPagingSource { pages[it] ?: emptyList() }

        val r1 = src.load(refresh()) as PagingSource.LoadResult.Page
        assertEquals(30, r1.data.size)
        val r2 = src.load(refresh(key = 2)) as PagingSource.LoadResult.Page
        // Shared id filtered on page 2: 29 fresh items, first wins.
        assertEquals(29, r2.data.size)
        assertEquals((r1.data.map { it.id } + r2.data.map { it.id }).size,
            (r1.data.map { it.id } + r2.data.map { it.id }).toSet().size)
        assertEquals("1", (r1.data as List<Parser.BookItem>).first().id)
        assertEquals("58", (r2.data as List<Parser.BookItem>).last().id)
    }

    @Test fun sameIdRetitledKeepsFirst() = runBlocking {
        val src = BookPagingSource {
            if (it == 1) listOf(item("1", "old")) else listOf(item("1", "new"))
        }
        src.load(refresh())
        val r2 = src.load(refresh(key = 2)) as PagingSource.LoadResult.Page
        assertTrue(r2.data.isEmpty())
    }

    @Test fun emptyRawPageEndsList() = runBlocking {
        val src = BookPagingSource {
            if (it == 1) listOf(item("1"), item("2")) else emptyList()
        }
        val r1 = src.load(refresh()) as PagingSource.LoadResult.Page
        assertEquals(2, r1.data.size)
        assertEquals(2, r1.nextKey)
        assertNull(r1.prevKey)
        val r2 = src.load(refresh(key = 2)) as PagingSource.LoadResult.Page
        assertTrue(r2.data.isEmpty())
        assertNull(r2.nextKey)
        assertEquals(1, r2.prevKey)
    }

    @Test fun transportErrorSurfaces() = runBlocking {
        val src = BookPagingSource { throw IOException("boom") }
        val r = src.load(refresh())
        assertTrue(r is PagingSource.LoadResult.Error)
        assertEquals("boom", (r as PagingSource.LoadResult.Error).throwable.message)
    }
}
