package cc.uukanshu

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cc.uukanshu.data.db.AppDb
import cc.uukanshu.data.db.BookEntity
import cc.uukanshu.data.db.ChapterEntity
import cc.uukanshu.data.db.ProgressEntity
import cc.uukanshu.data.net.SiteGateway
import cc.uukanshu.data.repo.BookRepo
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class DbDaoTest {
    private lateinit var db: AppDb

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDb::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun close() {
        db.close()
    }

    @Test fun replaceTocPreservesContentByPageId() = runTest {
        db.books().upsert(BookEntity("b1", "T", "A", "", "", ""))
        db.chapters().upsertAll(
            listOf(
                ChapterEntity("b1", 1, 101L, "c1", "u1", content = "saved-101"),
                ChapterEntity("b1", 2, 102L, "c2", "u2", content = ""),
            ),
        )
        // Retitled + one new chapter, one dropped.
        db.replaceToc(
            BookEntity("b1", "T2", "A", "", "", ""),
            listOf(
                ChapterEntity("b1", 1, 101L, "c1-retitled", "u1", content = ""),
                ChapterEntity("b1", 2, 103L, "c3-new", "u3", content = ""),
            ),
        )
        assertEquals("saved-101", db.chapters().chapterContent("b1", 101L))
        assertEquals("", db.chapters().chapterContent("b1", 103L))
        assertEquals(2, db.chapters().chapters("b1").size)
    }

    @Test fun contentWriteRoundtrip() = runTest {
        db.books().upsert(BookEntity("b1", "T", "A", "", "", ""))
        db.chapters().upsertAll(listOf(ChapterEntity("b1", 1, 101L, "c1", "u1", content = "")))
        db.chapters().updateContent("b1", 101L, "hello")
        assertEquals("hello", db.chapters().chapterContent("b1", 101L))
        assertEquals(listOf(101L), db.chapters().cachedPageIds("b1"))
    }

    @Test fun deleteBookFullWipesAllTables() = runTest {
        db.books().upsert(BookEntity("b1", "T", "A", "", "", ""))
        db.chapters().upsertAll(listOf(ChapterEntity("b1", 1, 101L, "c1", "u1", content = "x")))
        db.progress().upsert(ProgressEntity("b1", 1, 101L, 0L))
        db.deleteBookFull("b1")
        assertNull(db.books().book("b1"))
        assertEquals(0, db.chapters().chapters("b1").size)
        assertNull(db.progress().progress("b1"))
    }

    @Test fun updateMetaChangesTitleKeepsContent() = runTest {
        // TOC refresh path: bodies survive metadata-only updates without
        // ever being read or rewritten (see TocDiff).
        db.books().upsert(BookEntity("b1", "T", "A", "", "", ""))
        db.chapters().upsertAll(
            listOf(ChapterEntity("b1", 1, 101L, "c1", "u1", content = "saved")),
        )
        db.chapters().updateMeta("b1", 101L, 2, "c1-retitled", "u1-new")
        val rows = db.chapters().chapters("b1")
        assertEquals(1, rows.size)
        assertEquals("c1-retitled", rows[0].title)
        assertEquals(2, rows[0].position)
        assertEquals("u1-new", rows[0].url)
        assertEquals("saved", rows[0].content)
    }

    @Test fun replaceTocNoopKeepsRows() = runTest {
        db.books().upsert(BookEntity("b1", "T", "A", "", "", ""))
        db.chapters().upsertAll(
            listOf(ChapterEntity("b1", 1, 101L, "c1", "u1", content = "saved")),
        )
        db.replaceToc(
            BookEntity("b1", "T", "A", "", "", ""),
            listOf(ChapterEntity("b1", 1, 101L, "c1", "u1", content = "")),
        )
        assertEquals("saved", db.chapters().chapterContent("b1", 101L))
        assertEquals(1, db.chapters().chapters("b1").size)
    }

    @Test fun detailPropagatesDbFailureInsteadOfSilentSuccess() = runTest {
        // A failed replaceToc must surface (stale + offline upstream), never
        // return fresh chapters over a stale DB. The trigger fails exactly
        // the metadata-write step on a retitle refresh, so this guards the
        // old swallow, not an earlier query.
        var html = "<html><body><h1 class=\"booktitle\">T</h1>" +
            "<a href=\"/book/1/101.html\">c1</a></body></html>"
        val site = object : SiteGateway {
            override suspend fun get(url: String) = html
            override suspend fun search(keyword: String) = ""
        }
        val repo = BookRepo(site, db, kotlinx.coroutines.Dispatchers.Unconfined)
        repo.detail("1")
        assertEquals(1, db.chapters().chapters("1").size)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_update BEFORE UPDATE ON chapters " +
                "BEGIN SELECT RAISE(ABORT, 'boom'); END",
        )
        html = "<html><body><h1 class=\"booktitle\">T</h1>" +
            "<a href=\"/book/1/101.html\">c1-retitled</a></body></html>"
        try {
            repo.detail("1")
            fail("expected DB failure to propagate")
        } catch (e: Exception) {
            assertTrue(e !is kotlinx.coroutines.CancellationException)
            assertTrue(e is android.database.SQLException)
        }
    }

    @Test fun clearAllFullWipesEverything() = runTest {
        db.books().upsert(BookEntity("b1", "T", "A", "", "", ""))
        db.chapters().upsertAll(listOf(ChapterEntity("b1", 1, 101L, "c1", "u1", content = "x")))
        db.progress().upsert(ProgressEntity("b1", 1, 101L, 0L))
        db.clearAllFull()
        assertEquals(0, db.books().cachedBooks().size)
        assertEquals(0, db.chapters().chapters("b1").size)
        assertEquals(0, db.progress().all().size)
    }
}
