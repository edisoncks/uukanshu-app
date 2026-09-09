package cc.uukanshu

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cc.uukanshu.data.db.AppDb
import cc.uukanshu.data.net.SiteGateway
import cc.uukanshu.data.repo.BookRepo
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Seed → grow → clear → skip interaction over a real in-memory DB.
 * Exercises detail()+replaceToc()+badge diff (not faked Detail) via a
 * minimal TOC fixture (see DbDaoTest pattern).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class BookRepoUpdateCheckTest {
    private lateinit var db: AppDb
    private var html: String = tocHtml(10)
    private val site = object : SiteGateway {
        override suspend fun get(url: String) = html
        override suspend fun search(keyword: String) = ""
    }
    private lateinit var repo: BookRepo

    companion object {
        fun tocHtml(n: Int): String {
            val links = (101..100 + n).joinToString("") { id ->
                "<a href=\"/book/1/$id.html\">c$id</a>"
            }
            return "<html><body><h1 class=\"booktitle\">T</h1>$links</body></html>"
        }
    }

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDb::class.java,
        ).allowMainThreadQueries().build()
        // Single-book runs never hit crawlDelay (delay only between books).
        repo = BookRepo(site, db, kotlinx.coroutines.Dispatchers.Unconfined)
    }

    @After fun close() {
        db.close()
    }

    @Test fun seedGrowsClears() = runTest {
        // Seed DB via first detail (10 chapters, seenTotal=0).
        repo.detail("1")
        // Make it visible (cached>0) so shelf/visible rules apply.
        db.chapters().updateContent("1", 101L, "x")
        // First check seeds baseline, no badge.
        val first = repo.checkUpdate("1")
        assertTrue(first is BookRepo.UpdateCheck.Ok && first.newCount == 0)
        assertEquals(10, db.books().book("1")!!.seenTotal)
        assertEquals(0, db.books().book("1")!!.newCount)
        // Grow to 12 → badge 2, baseline stays.
        html = tocHtml(12)
        val grown = repo.checkUpdate("1")
        assertTrue(grown is BookRepo.UpdateCheck.Ok && grown.newCount == 2)
        assertEquals(10, db.books().book("1")!!.seenTotal)
        assertEquals(2, db.books().book("1")!!.newCount)
        // checkAll sees the badge (visible: cached>0).
        val all = repo.checkAllUpdates(limit = 20)
        assertEquals(false, all.failed)
        // markSeen advances baseline, clears badge.
        repo.markSeen("1")
        assertEquals(12, db.books().book("1")!!.seenTotal)
        assertEquals(0, db.books().book("1")!!.newCount)
        val again = repo.checkUpdate("1")
        assertTrue(again is BookRepo.UpdateCheck.Ok && again.newCount == 0)
    }

    @Test fun shrinkAndEmptyNeverWipe() = runTest {
        repo.detail("1")
        db.chapters().updateContent("1", 101L, "x")
        repo.checkUpdate("1")
        assertEquals(10, db.books().book("1")!!.seenTotal)
        // Shrink to 5 → skip, cache intact, timestamp advances past oldest-first head.
        html = tocHtml(5)
        val shrunk = repo.checkUpdate("1")
        assertTrue(shrunk is BookRepo.UpdateCheck.SkippedShrink)
        assertEquals(10, db.chapters().countByBook("1"))
        assertEquals(10, db.books().book("1")!!.seenTotal)
        assertTrue(db.books().book("1")!!.lastCheckedAt > 0L)
        // Empty → skip without wipe, timestamp still advances so it sorts last next run.
        html = "<html><body><h1 class=\"booktitle\">T</h1></body></html>"
        val beforeEmpty = db.books().book("1")!!
        val empty = repo.checkUpdate("1")
        assertTrue(empty is BookRepo.UpdateCheck.SkippedEmpty)
        assertEquals(10, db.chapters().countByBook("1"))
        assertEquals(beforeEmpty.seenTotal, db.books().book("1")!!.seenTotal)
        assertEquals(beforeEmpty.newCount, db.books().book("1")!!.newCount)
        assertTrue(db.books().book("1")!!.lastCheckedAt >= beforeEmpty.lastCheckedAt)
        assertTrue(db.books().book("1")!!.lastCheckedAt > 0L)
    }
}
