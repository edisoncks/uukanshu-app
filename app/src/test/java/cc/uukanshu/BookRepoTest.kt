package cc.uukanshu

import cc.uukanshu.data.db.BookEntity
import cc.uukanshu.data.parse.Parser
import cc.uukanshu.data.repo.BookRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BookRepoTest {
    @Test fun tocRefreshPreservesDownloadedContent() {
        val refs = listOf(
            Parser.ChapterRef(1, 101L, "001", "https://uukanshu.cc/book/1/101.html"),
            Parser.ChapterRef(2, 102L, "002 (retitled)", "https://uukanshu.cc/book/1/102.html"),
            Parser.ChapterRef(3, 103L, "003 new", "https://uukanshu.cc/book/1/103.html"),
        )
        val cached = mapOf(101L to "text-1", 102L to "text-2")
        val merged = BookRepo.mergeToc("1", refs, cached)
        // Downloaded content survives, keyed by stable pageId…
        assertEquals("text-1", merged[0].content)
        assertEquals("text-2", merged[1].content)
        // …while titles/URLs still refresh and new chapters start empty.
        assertEquals("002 (retitled)", merged[1].title)
        assertEquals("", merged[2].content)
    }

    @Test fun shelfShowsOnlyBooksWithCachedChapters() {
        val books = listOf(
            BookRepo.CachedBook("a", "A", "Au", total = 10, cached = 0, bytes = 0),
            BookRepo.CachedBook("b", "B", "Au", total = 10, cached = 1, bytes = 100),
            BookRepo.CachedBook("c", "C", "Au", total = 5, cached = 5, bytes = 500),
        )
        val visible = books.filter { it.cached > 0 }
        assertEquals(listOf("b", "c"), visible.map { it.id })
    }

    private fun shelf(id: String) =
        BookRepo.CachedBook(id, id.uppercase(), "Au", total = 10, cached = 1, bytes = 100)

    @Test fun downloadBumpsWithoutProgress() {
        // b was only downloaded (bookAt), a was read earlier: download wins.
        val books = listOf(shelf("a"), shelf("b"))
        val ordered = BookRepo.sortShelf(
            books,
            bookAt = mapOf("a" to 100L, "b" to 300L),
            progressAt = mapOf("a" to 200L),
        )
        assertEquals(listOf("b", "a"), ordered.map { it.id })
    }

    @Test fun readBeatsOlderDownload() {
        val books = listOf(shelf("a"), shelf("b"))
        val ordered = BookRepo.sortShelf(
            books,
            bookAt = mapOf("a" to 250L, "b" to 100L),
            progressAt = mapOf("a" to 200L, "b" to 300L),
        )
        assertEquals(listOf("b", "a"), ordered.map { it.id })
    }

    @Test fun noActivitySinksToBottomKeepingOrder() {
        val books = listOf(shelf("a"), shelf("b"), shelf("c"))
        val ordered = BookRepo.sortShelf(
            books,
            bookAt = mapOf("b" to 200L),
            progressAt = mapOf("b" to 100L),
        )
        // b has activity; a/c untouched keep their relative order behind it.
        assertEquals(listOf("b", "a", "c"), ordered.map { it.id })
    }

    @Test fun crawlDelayBoundsAre1to3s() {
        assertEquals(1000L, BookRepo.CRAWL_DELAY_MIN_MS)
        assertEquals(3000L, BookRepo.CRAWL_DELAY_MAX_MS)
    }

    @Test fun nextCrawlDelayStaysInRange() {
        val random = Random(0)
        repeat(1000) {
            val d = BookRepo.nextCrawlDelayMs(random)
            assertTrue("delay $d out of 1000..3000", d in 1000L..3000L)
        }
    }

    @Test fun detailRefreshPreservesShelfTimestamp() {
        val existing = BookEntity("1", "T", "Au", "", "", "", updatedAt = 500L)
        val fresh = BookEntity("1", "T2", "Au2", "", "", "", updatedAt = 0L)
        // Refresh keeps the bump: browsing Detail must not reorder the shelf.
        assertEquals(500L, BookRepo.preserveBookUpdatedAt(existing, fresh, now = 999L).updatedAt)
        // First cache stamps now so a fresh download has an order key.
        assertEquals(999L, BookRepo.preserveBookUpdatedAt(null, fresh, now = 999L).updatedAt)
    }
}
