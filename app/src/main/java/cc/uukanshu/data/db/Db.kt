package cc.uukanshu.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 -> v2: drop the never-written `chapters.updatedAt` column.
 * Hand-written (no DROP COLUMN: SQLite on minSdk predates it), recreating
 * the table with Room's exact expected shape.
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

@Database(
    entities = [BookEntity::class, ChapterEntity::class, ProgressEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDb : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun chapters(): ChapterDao
    abstract fun progress(): ProgressDao

    companion object {
        @Volatile private var instance: AppDb? = null

        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDb::class.java,
                "uukanshu.db",
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
