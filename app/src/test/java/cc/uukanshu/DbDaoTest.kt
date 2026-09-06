package cc.uukanshu

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cc.uukanshu.data.db.AppDb
import cc.uukanshu.data.db.BookEntity
import cc.uukanshu.data.db.ChapterEntity
import cc.uukanshu.data.db.ProgressEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
