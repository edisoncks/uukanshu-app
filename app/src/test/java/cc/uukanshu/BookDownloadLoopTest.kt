package cc.uukanshu

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cc.uukanshu.data.db.AppDb
import cc.uukanshu.data.db.BookEntity
import cc.uukanshu.data.db.ChapterEntity
import cc.uukanshu.data.net.SiteGateway
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * `BookRepo.downloadAll` loop: the `DownloadPlan` missing-set drives fetching
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
