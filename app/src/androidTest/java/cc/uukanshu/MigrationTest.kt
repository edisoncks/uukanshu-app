package cc.uukanshu

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.uukanshu.data.db.AppDb
import cc.uukanshu.data.db.MIGRATION_1_2
import cc.uukanshu.data.db.MIGRATION_2_3
import cc.uukanshu.data.db.MIGRATION_3_4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Data-level migration runs (needs emulator/device):
 * v1→v2 drops never-written chapters.updatedAt, v2→v3 rekeys to stable
 * pageId preserving downloads, v3→v4 adds progress.pageId default 0.
 * JVM wiring is locked by DbSchemaTest; this proves data survives.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDb::class.java,
    )

    @Test fun migrate1To2PreservesRows() {
        helper.createDatabase("mig1", 1).apply {
            execSQL(
                "INSERT INTO books (id, title, author, intro, category, lastChapterTitle, updatedAt) " +
                    "VALUES ('b1', 'T', 'A', '', '', '', 0)",
            )
            execSQL(
                "INSERT INTO chapters (bookId, position, pageId, title, url, content, updatedAt) " +
                    "VALUES ('b1', 1, 101, 'c1', 'u', 'text', 0)",
            )
            close()
        }
        helper.runMigrationsAndValidate("mig1", 2, true, MIGRATION_1_2).apply {
            query("SELECT bookId, content FROM chapters").apply {
                assertEquals(1, count)
                moveToFirst()
                assertEquals("b1", getString(0))
                assertEquals("text", getString(1))
                close()
            }
            close()
        }
    }

    @Test fun migrate2To3RekeysToPageIdKeepingContent() {
        helper.createDatabase("mig2", 2).apply {
            execSQL(
                "INSERT INTO books (id, title, author, intro, category, lastChapterTitle, updatedAt) " +
                    "VALUES ('b1', 'T', 'A', '', '', '', 0)",
            )
            execSQL(
                "INSERT INTO chapters (bookId, position, pageId, title, url, content) " +
                    "VALUES ('b1', 1, 101, 'c1', 'u', 'text-101')",
            )
            close()
        }
        helper.runMigrationsAndValidate("mig2", 3, true, MIGRATION_2_3).apply {
            query("SELECT content FROM chapters WHERE bookId = 'b1' AND pageId = 101").apply {
                assertEquals(1, count)
                moveToFirst()
                assertEquals("text-101", getString(0))
                close()
            }
            close()
        }
    }

    @Test fun migrate3To4AddsPageIdDefaultZero() {
        helper.createDatabase("mig3", 3).apply {
            execSQL(
                "INSERT INTO books (id, title, author, intro, category, lastChapterTitle, updatedAt) " +
                    "VALUES ('b1', 'T', 'A', '', '', '', 0)",
            )
            execSQL("INSERT INTO progress (bookId, position, updatedAt) VALUES ('b1', 2, 0)")
            close()
        }
        helper.runMigrationsAndValidate("mig3", 4, true, MIGRATION_3_4).apply {
            query("SELECT position, pageId FROM progress WHERE bookId = 'b1'").apply {
                assertEquals(1, count)
                moveToFirst()
                assertEquals(2, getInt(0))
                assertEquals(0, getInt(1))
                close()
            }
            close()
        }
    }
}
