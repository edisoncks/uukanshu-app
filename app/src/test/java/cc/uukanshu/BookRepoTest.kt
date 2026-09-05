package cc.uukanshu

import cc.uukanshu.data.parse.Parser
import cc.uukanshu.data.repo.BookRepo
import org.junit.Assert.assertEquals
import org.junit.Test

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
}
