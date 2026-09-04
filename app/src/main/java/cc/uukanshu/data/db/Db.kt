package cc.uukanshu.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [BookEntity::class, ChapterEntity::class, ProgressEntity::class],
    version = 1,
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
            ).build().also { instance = it }
        }
    }
}
