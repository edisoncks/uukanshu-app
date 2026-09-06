package cc.uukanshu.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 -> v2: drop never-written `chapters.updatedAt` (no DROP COLUMN on minSdk SQLite). See SCRAPING.md.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE chapters_new (`bookId` TEXT NOT NULL, " +
                "`position` INTEGER NOT NULL, `pageId` INTEGER NOT NULL, " +
                "`title` TEXT NOT NULL, `url` TEXT NOT NULL, " +
                "`content` TEXT NOT NULL, PRIMARY KEY(`bookId`, `position`))",
        )
        db.execSQL(
            "INSERT INTO chapters_new " +
                "(bookId, position, pageId, title, url, content) " +
                "SELECT bookId, position, pageId, title, url, content FROM chapters",
        )
        db.execSQL("DROP TABLE chapters")
        db.execSQL("ALTER TABLE chapters_new RENAME TO chapters")
    }
}

/**
 * v2 -> v3: rekey `chapters` to stable `(bookId, pageId)`; empty-first copy preserves downloads.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE chapters_new (`bookId` TEXT NOT NULL, " +
                "`position` INTEGER NOT NULL, `pageId` INTEGER NOT NULL, " +
                "`title` TEXT NOT NULL, `url` TEXT NOT NULL, " +
                "`content` TEXT NOT NULL, PRIMARY KEY(`bookId`, `pageId`))",
        )
        db.execSQL(
            "INSERT OR REPLACE INTO chapters_new " +
                "(bookId, position, pageId, title, url, content) " +
                "SELECT bookId, position, pageId, title, url, content FROM chapters " +
                "ORDER BY (content != ''), position",
        )
        db.execSQL("DROP TABLE chapters")
        db.execSQL("ALTER TABLE chapters_new RENAME TO chapters")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_chapters_bookId_position` " +
                "ON `chapters` (`bookId`, `position`)",
        )
    }
}

/** v3 -> v4: bookmark by stable `pageId` (0 = pre-v4 position-only fallback). */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE progress ADD COLUMN `pageId` INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(
    entities = [BookEntity::class, ChapterEntity::class, ProgressEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class AppDb : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun chapters(): ChapterDao
    abstract fun progress(): ProgressDao

    /**
     * Atomic TOC replace preserving downloads by stable pageId.
     * Callers must serialize against `updateContent` via repo `dbWrite` Mutex.
     * See ARCHITECTURE.md § Offline cache model.
     */
    @Transaction
    open suspend fun replaceToc(book: BookEntity, skeleton: List<ChapterEntity>) {
        books().upsert(book)
        val cached = chapters().contents(book.id).associate { it.pageId to it.content }
        chapters().deleteBook(book.id)
        chapters().upsertAll(skeleton.map { it.copy(content = cached[it.pageId].orEmpty()) })
    }

    /** Atomic per-book wipe (single transaction). */
    @Transaction
    open suspend fun deleteBookFull(bookId: String) {
        chapters().deleteBook(bookId)
        books().deleteBook(bookId)
        progress().deleteBook(bookId)
    }

    /** Atomic wipe of all tables (single transaction). */
    @Transaction
    open suspend fun clearAllFull() {
        chapters().clearAll()
        books().clearAll()
        progress().clearAll()
    }

    companion object {
        @Volatile private var instance: AppDb? = null

        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDb::class.java,
                "uukanshu.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
        }
    }
}
