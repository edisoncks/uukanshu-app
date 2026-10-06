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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioural atomicity pin for [AppDb]'s multi-statement write paths.
 *
 * Each test installs a SQLite trigger that aborts the LAST statement of the
 * method under test. A real transaction rolls back every earlier statement,
 * so the DB must be byte-identical to before the call; without a transaction
 * the earlier writes are already committed and survive.
 *
 * Why behavioural: the `@Transaction` annotation on a non-abstract method of a
 * `@Database` class is not enough here — Room generated no override for these
 * three methods (see AppDb), so nothing wrapped them. This test fails if that
 * regresses again, independent of any Room code-generation detail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class DbTransactionTest {
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

    private fun abortOn(sql: String) {
        db.openHelper.writableDatabase.execSQL(sql)
    }

    private suspend fun seedBook() {
        db.books().upsert(BookEntity("b1", "before"))
        db.chapters().upsertAll(
            listOf(
                ChapterEntity("b1", 1, 101L, "c1", "u1", content = "saved"),
                ChapterEntity("b1", 2, 102L, "c2", "u2", content = ""),
            ),
        )
        db.progress().upsert(ProgressEntity("b1", 1, 101L, updatedAt = 7L))
    }

    private suspend fun assertUntouched() {
        assertEquals("before", db.books().book("b1")?.title)
        assertEquals(listOf(101L, 102L), db.chapters().chapters("b1").map { it.pageId })
        assertEquals(7L, db.progress().progress("b1")?.updatedAt)
    }

    /** Must fail, and the failure must be the trigger's RAISE(ABORT) — not any incidental error. */
    private fun assertAbortedByTrigger(stmt: String, err: Throwable?) {
        if (err == null) throw AssertionError("trigger must abort the $stmt")
        assertTrue(
            "failure must be the trigger's RAISE(ABORT), was: $err",
            err.message?.contains("boom") == true,
        )
    }

    @Test fun replaceTocRollsBackWhenALaterStatementFails() = runTest {
        seedBook()
        // Fires on the insert — the LAST statement only because this fixture's
        // TocDiff yields no metadata updates; keep that true when changing it.
        abortOn("CREATE TRIGGER boom BEFORE INSERT ON chapters BEGIN SELECT RAISE(ABORT, 'boom'); END")

        val err = runCatching {
            db.replaceToc(
                BookEntity("b1", "after"),
                listOf(ChapterEntity("b1", 1, 103L, "c3", "u3", content = "")),
            )
        }.exceptionOrNull()
        assertAbortedByTrigger("insert", err)

        assertUntouched()
    }

    @Test fun deleteBookFullRollsBackWhenALaterStatementFails() = runTest {
        seedBook()
        abortOn("CREATE TRIGGER boom BEFORE DELETE ON progress BEGIN SELECT RAISE(ABORT, 'boom'); END")

        val err = runCatching { db.deleteBookFull("b1") }.exceptionOrNull()
        assertAbortedByTrigger("delete", err)

        assertUntouched()
    }

    @Test fun clearAllFullRollsBackWhenALaterStatementFails() = runTest {
        seedBook()
        abortOn("CREATE TRIGGER boom BEFORE DELETE ON progress BEGIN SELECT RAISE(ABORT, 'boom'); END")

        val err = runCatching { db.clearAllFull() }.exceptionOrNull()
        assertAbortedByTrigger("delete", err)

        assertUntouched()
    }
}
