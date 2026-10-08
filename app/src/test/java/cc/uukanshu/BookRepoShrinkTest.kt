package cc.uukanshu

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cc.uukanshu.core.TocShrunkException
import cc.uukanshu.data.db.AppDb
import cc.uukanshu.data.net.SiteGateway
import cc.uukanshu.data.repo.BookRepo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shrink guard over a real DB, and why Detail needs 重新同步章節列表.
 *
 * The guard's baseline is the cached row count and only an accepted fetch lowers
 * it, so a site-side deletion stays rejected no matter how often it is fetched
 * again — the state cannot clear itself (see SCRAPING.md). `detailAcceptingShrink`
 * is the user-confirmed way out: it still refuses an empty fresh TOC (no
 * confirmation may wipe a chapter list on a block page), and pruning stays
 * [cc.uukanshu.data.repo.TocDiff]'s, so chapters the site kept keep their bodies.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class BookRepoShrinkTest {
    private lateinit var db: AppDb
    private var html = ""

    private fun tocHtml(ids: IntRange): String = buildString {
        append("<html><body><h1 class=\"booktitle\">T</h1>")
        for (i in ids) append("<a href=\"/book/1/$i.html\">c$i</a>")
        append("</body></html>")
    }

    private val repo by lazy {
        BookRepo(
            object : SiteGateway {
                override suspend fun get(url: String) = html
                override suspend fun search(keyword: String) = ""
            },
            db,
        )
    }

    @Before fun open() {
        html = tocHtml(1..3)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDb::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun close() = db.close()

    @Test fun repeatedFetchNeverHealsAShrunkenToc() = runBlocking {
        assertEquals(3, repo.detail("1").chapters.size)
        repo.saveChapterContent("1", 2L, "saved-2")
        html = tocHtml(1..2)
        repeat(5) { attempt ->
            val rejected = try {
                repo.detail("1")
                false
            } catch (e: TocShrunkException) {
                true
            }
            assertTrue("attempt ${attempt + 1} must stay rejected", rejected)
        }
        // Nothing was written, so the next attempt compares against the same
        // baseline: the state is sticky until the site grows back or the user
        // confirms the resync.
        assertEquals(3, db.chapters().countByBook("1"))
        assertEquals("saved-2", db.chapters().chapterContent("1", 2L))
    }

    @Test fun confirmedResyncAcceptsShrinkAndPrunesOnlyRemovedChapters() = runBlocking {
        repo.detail("1")
        repo.saveChapterContent("1", 1L, "saved-1")
        repo.saveChapterContent("1", 3L, "saved-3")
        html = tocHtml(1..2)
        assertEquals(2, repo.detailAcceptingShrink("1").chapters.size)
        assertEquals(2, db.chapters().countByBook("1"))
        assertEquals("survivor keeps its body", "saved-1", db.chapters().chapterContent("1", 1L))
        assertNull("site-dropped chapter is pruned", db.chapters().chapterContent("1", 3L))
    }

    @Test fun confirmedResyncNeverWipesCacheOnEmptyFresh() = runBlocking {
        repo.detail("1")
        html = "<html><body><h1 class=\"booktitle\">T</h1></body></html>"
        assertEquals(0, repo.detailAcceptingShrink("1").chapters.size)
        assertEquals("empty parse must not touch the cache", 3, db.chapters().countByBook("1"))
    }
}
