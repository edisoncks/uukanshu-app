package cc.uukanshu

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cc.uukanshu.data.db.AppDb
import cc.uukanshu.data.db.BookEntity
import cc.uukanshu.data.db.ChapterEntity
import cc.uukanshu.data.net.SiteGateway
import cc.uukanshu.data.parse.Parser
import cc.uukanshu.data.repo.BookRepo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * `BookRepo.downloadAll` loop: the `BookRepo.missing` set drives fetching
 * (cached chapters are skipped, progress still spans all), and mid-download
 * deletion aborts loudly. Real in-memory Room, stub network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class BookDownloadLoopTest {
    private lateinit var db: AppDb

    private val tocHtml = "<html><body><h1 class=\"booktitle\">T</h1>" +
        "<a href=\"/book/1/101.html\">c1</a>" +
        "<a href=\"/book/1/102.html\">c2</a></body></html>"

    private val tocHtml3 = "<html><body><h1 class=\"booktitle\">T</h1>" +
        "<a href=\"/book/1/101.html\">c1</a>" +
        "<a href=\"/book/1/102.html\">c2</a>" +
        "<a href=\"/book/1/103.html\">c3</a></body></html>"

    private val emptyTocHtml =
        "<html><body><h1 class=\"booktitle\">T</h1></body></html>"

    private fun chapterUrl(pageId: Long) = "https://uukanshu.cc/book/1/$pageId.html"

    private fun chapterHtml(text: String) =
        "<h1>c</h1><div class=\"readcotent\">$text<br></div>"

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDb::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun close() {
        db.close()
    }

    @Test fun fetchesOnlyPlannedMissingReportsProgressOverAll() = runBlocking {
        db.books().upsert(BookEntity("1", "T", "A", "", "", ""))
        db.chapters().upsertAll(
            listOf(
                ChapterEntity("1", 1, 101L, "c1", "https://uukanshu.cc/book/1/101.html", content = "saved"),
                ChapterEntity("1", 2, 102L, "c2", "https://uukanshu.cc/book/1/102.html", content = ""),
            ),
        )
        val fetched = mutableListOf<String>()
        var lastProgress = 0 to 0
        val site = object : SiteGateway {
            override suspend fun get(url: String): String {
                if (url.endsWith("/book/1/")) return tocHtml
                fetched += url
                return chapterHtml("fresh-102")
            }
            override suspend fun search(keyword: String) = ""
        }
        // No crawl-delay override needed: a single missing chapter never hits
        // crawlDelay (it runs before the second fetch), and the abort test
        // throws before reaching it.
        val repo = BookRepo(site, db)
        repo.downloadAll("1") { done, total -> lastProgress = done to total }
        assertEquals(listOf("https://uukanshu.cc/book/1/102.html"), fetched)
        assertEquals(2 to 2, lastProgress)
        assertEquals("saved", db.chapters().chapterContent("1", 101L))
        assertEquals("fresh-102", db.chapters().chapterContent("1", 102L))
    }

    @Test fun chapterFailureAbortsButKeepsSavedChapters() = runBlocking {
        db.books().upsert(BookEntity("1", "T", "A", "", "", "", updatedAt = 1000L))
        db.chapters().upsertAll(
            listOf(
                ChapterEntity("1", 1, 101L, "c1", chapterUrl(101L), content = "saved-1"),
                ChapterEntity("1", 2, 102L, "c2", chapterUrl(102L), content = ""),
                ChapterEntity("1", 3, 103L, "c3", chapterUrl(103L), content = ""),
            ),
        )
        val fetched = mutableListOf<String>()
        var lastProgress = 0 to 0
        val site = object : SiteGateway {
            override suspend fun get(url: String): String {
                if (url.endsWith("/book/1/")) return tocHtml3
                fetched += url
                throw IOException("chapter boom for $url")
            }
            override suspend fun search(keyword: String) = ""
        }
        val repo = BookRepo(site, db)
        try {
            repo.downloadAll("1") { done, total -> lastProgress = done to total }
            fail("expected abort on chapter failure")
        } catch (e: IOException) {
            assertTrue("expected chapter failure, got $e", e.message?.contains("boom") == true)
        }
        // ch1 skipped (cached) then ch2 blew up before its progress report.
        assertEquals(listOf(chapterUrl(102L)), fetched)
        assertEquals(1 to 3, lastProgress)
        assertEquals("saved-1", db.chapters().chapterContent("1", 101L))
        assertEquals("", db.chapters().chapterContent("1", 102L))
        // finally/touch still runs on failure so the shelf re-sorts.
        assertTrue((db.books().book("1")?.updatedAt ?: 0L) > 1000L)
    }

    @Test fun shrunkenFreshTocFallsBackToCache() = runBlocking {
        db.books().upsert(BookEntity("1", "T", "A", "", "", ""))
        db.chapters().upsertAll(
            listOf(
                ChapterEntity("1", 1, 101L, "c1", chapterUrl(101L), content = "saved-1"),
                ChapterEntity("1", 2, 102L, "c2", chapterUrl(102L), content = "saved-2"),
                ChapterEntity("1", 3, 103L, "c3", chapterUrl(103L), content = ""),
            ),
        )
        // Truncated parse: only 2 of the 3 cached chapters. detail()'s shrink
        // guard must throw before replaceToc can wipe the third row.
        val site = object : SiteGateway {
            override suspend fun get(url: String): String {
                if (url.endsWith("/book/1/")) return tocHtml
                return chapterHtml("fresh-103")
            }
            override suspend fun search(keyword: String) = ""
        }
        val repo = BookRepo(site, db)
        var lastProgress = 0 to 0
        repo.downloadAll("1") { done, total -> lastProgress = done to total }
        assertEquals(3 to 3, lastProgress)
        assertEquals(3, db.chapters().countByBook("1"))
        assertEquals("saved-1", db.chapters().chapterContent("1", 101L))
        assertEquals("saved-2", db.chapters().chapterContent("1", 102L))
        assertEquals("fresh-103", db.chapters().chapterContent("1", 103L))
    }

    @Test fun emptyFreshTocFallsBackToCache() = runBlocking {
        assertTrue(Parser.parseToc(emptyTocHtml, "1").isEmpty())
        db.books().upsert(BookEntity("1", "T", "A", "", "", ""))
        db.chapters().upsertAll(
            listOf(
                ChapterEntity("1", 1, 101L, "c1", chapterUrl(101L), content = "saved"),
                ChapterEntity("1", 2, 102L, "c2", chapterUrl(102L), content = ""),
            ),
        )
        val fetched = mutableListOf<String>()
        val site = object : SiteGateway {
            override suspend fun get(url: String): String {
                if (url.endsWith("/book/1/")) return emptyTocHtml
                fetched += url
                return chapterHtml("fresh-102")
            }
            override suspend fun search(keyword: String) = ""
        }
        val repo = BookRepo(site, db)
        var lastProgress = 0 to 0
        repo.downloadAll("1") { done, total -> lastProgress = done to total }
        assertEquals(listOf(chapterUrl(102L)), fetched)
        assertEquals(2 to 2, lastProgress)
        assertEquals("fresh-102", db.chapters().chapterContent("1", 102L))
    }

    @Test fun emptyFreshAndNoCacheFailsLoudly() = runBlocking {
        assertTrue(Parser.parseToc(emptyTocHtml, "1").isEmpty())
        val site = object : SiteGateway {
            override suspend fun get(url: String): String = emptyTocHtml
            override suspend fun search(keyword: String) = ""
        }
        val repo = BookRepo(site, db)
        try {
            repo.downloadAll("1") { _, _ -> }
            fail("expected loud failure on empty TOC without cache")
        } catch (e: IOException) {
            assertTrue("expected empty-list failure, got $e", e.message?.contains("empty chapter list") == true)
        }
        assertEquals(0, db.chapters().countByBook("1"))
    }

    @Test fun networkFailureFallsBackToCacheThenRethrowsWithoutIt() = runBlocking {
        db.books().upsert(BookEntity("1", "T", "A", "", "", ""))
        db.chapters().upsertAll(
            listOf(
                ChapterEntity("1", 1, 101L, "c1", chapterUrl(101L), content = "saved"),
                ChapterEntity("1", 2, 102L, "c2", chapterUrl(102L), content = ""),
            ),
        )
        val site = object : SiteGateway {
            override suspend fun get(url: String): String {
                if (url.endsWith("/book/1/")) throw IOException("network down")
                return chapterHtml("fresh-102")
            }
            override suspend fun search(keyword: String) = ""
        }
        val repo = BookRepo(site, db)
        repo.downloadAll("1") { _, _ -> }
        assertEquals("fresh-102", db.chapters().chapterContent("1", 102L))
        repo.deleteBook("1")
        try {
            repo.downloadAll("1") { _, _ -> }
            fail("expected rethrow without cache")
        } catch (e: IOException) {
            assertEquals("network down", e.message)
        }
    }

    @Test fun allCachedSkipsFetching() = runBlocking {
        db.books().upsert(BookEntity("1", "T", "A", "", "", ""))
        db.chapters().upsertAll(
            listOf(
                ChapterEntity("1", 1, 101L, "c1", chapterUrl(101L), content = "saved-1"),
                ChapterEntity("1", 2, 102L, "c2", chapterUrl(102L), content = "saved-2"),
                ChapterEntity("1", 3, 103L, "c3", chapterUrl(103L), content = "saved-3"),
            ),
        )
        val fetched = mutableListOf<String>()
        val site = object : SiteGateway {
            override suspend fun get(url: String): String {
                if (url.endsWith("/book/1/")) return tocHtml3
                fetched += url
                return chapterHtml("must-not-fetch")
            }
            override suspend fun search(keyword: String) = ""
        }
        val repo = BookRepo(site, db)
        var lastProgress = 0 to 0
        repo.downloadAll("1") { done, total -> lastProgress = done to total }
        assertTrue("nothing to fetch, got $fetched", fetched.isEmpty())
        assertEquals(3 to 3, lastProgress)
    }

    @Test fun deleteMidDownloadAbortsLoudly() = runBlocking {
        val chapterCalls = AtomicInteger(0)
        val fetchGate = CompletableDeferred<Unit>()
        val site = object : SiteGateway {
            override suspend fun get(url: String): String {
                if (url.endsWith("/book/1/")) return tocHtml
                chapterCalls.incrementAndGet()
                fetchGate.await()
                return chapterHtml("x")
            }
            override suspend fun search(keyword: String) = ""
        }
        val repo = BookRepo(site, db)
        var thrown: Throwable? = null
        val job = launch {
            try {
                repo.downloadAll("1") { _, _ -> }
            } catch (e: Throwable) {
                thrown = e
            }
        }
        // Wait until the first chapter fetch is hanging inside the loop.
        withTimeout(10000) {
            while (chapterCalls.get() == 0) delay(10)
        }
        repo.deleteBook("1")
        fetchGate.complete(Unit)
        withTimeout(10000) { job.join() }
        if (thrown == null) fail("expected abort on deleted book")
        assertTrue("expected delete-abort, got $thrown", thrown!!.message?.contains("deleted") == true)
    }
}
