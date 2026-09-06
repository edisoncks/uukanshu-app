package cc.uukanshu

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cc.uukanshu.data.db.AppDb
import cc.uukanshu.data.db.BookEntity
import cc.uukanshu.data.db.ChapterEntity
import cc.uukanshu.data.net.SiteGateway
import cc.uukanshu.data.repo.BookRepo
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Guard-read + replace are atomic under `dbWrite`: concurrent same-book
 * refreshes must never regress the TOC size via a short parse slipping past
 * a stale count.
 *
 * Statistical by necessity — the bad interleave (short counts low, growth
 * writes, short writes last) cannot be forced deterministically from outside,
 * since the pause point sits between two statements inside `detail()`. With
 * 19 short-tail racers vs 1 grower over real threads, the pre-fix code
 * regresses to 110 most runs; the fixed code ends at 120 every run (a short
 * running last counts >= 120 inside the lock and throws instead of writing).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class BookRepoGuardRaceTest {
    private lateinit var db: AppDb

    private fun tocHtml(ids: IntRange): String = buildString {
        append("<html><body><h1 class=\"booktitle\">T</h1>")
        for (i in ids) append("<a href=\"/book/1/$i.html\">c$i</a>")
        append("</body></html>")
    }

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDb::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun close() {
        db.close()
    }

    @Test fun concurrentRefreshNeverRegressesTocSize() = runBlocking {
        db.books().upsert(BookEntity("1", "T", "A", "", "", ""))
        db.chapters().upsertAll((1..100).map { i ->
            ChapterEntity("1", i, i.toLong(), "c$i", "https://uukanshu.cc/book/1/$i.html", content = "saved-$i")
        })
        val growHtml = tocHtml(1..120)
        val shortHtml = tocHtml(1..110)
        val calls = AtomicInteger(0)
        val site = object : SiteGateway {
            override suspend fun get(url: String): String =
                if (calls.getAndIncrement() == 0) growHtml else shortHtml
            override suspend fun search(keyword: String) = ""
        }
        val repo = BookRepo(site, db)
        (1..20).map {
            launch { runCatching { repo.detail("1") } }
        }.joinAll()
        // Growth always completes (120 passes any guard); a trailing short
        // must throw, never write.
        assertEquals(120, db.chapters().countByBook("1"))
        for (i in 1..100) {
            assertEquals("saved-$i", db.chapters().chapterContent("1", i.toLong()))
        }
    }
}
