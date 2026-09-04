package cc.uukanshu.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String = "",
    val intro: String = "",
    val category: String = "",
    val lastChapterTitle: String = "",
    val updatedAt: Long = 0L,
)

@Entity(tableName = "chapters", primaryKeys = ["bookId", "position"])
data class ChapterEntity(
    val bookId: String,
    val position: Int,
    val pageId: Long,
    val title: String,
    val url: String,
    val content: String = "",
    val updatedAt: Long = 0L,
)

@Entity(tableName = "progress", primaryKeys = ["bookId"])
data class ProgressEntity(
    val bookId: String,
    val position: Int,
    val updatedAt: Long = 0L,
)

@Dao
interface BookDao {
    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun book(id: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteBook(id: String)

    @Query("SELECT * FROM books ORDER BY updatedAt DESC")
    suspend fun cachedBooks(): List<BookEntity>
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY position")
    suspend fun chapters(bookId: String): List<ChapterEntity>

    /** Positions with downloaded content, as a live stream for badges. */
    @Query("SELECT position FROM chapters WHERE bookId = :bookId AND content != ''")
    fun cachedPositionsFlow(bookId: String): Flow<List<Int>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(chapters: List<ChapterEntity>)

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteBook(bookId: String)
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM progress WHERE bookId = :bookId")
    suspend fun progress(bookId: String): ProgressEntity?

    @Query("SELECT * FROM progress WHERE bookId = :bookId")
    fun progressFlow(bookId: String): Flow<ProgressEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: ProgressEntity)

    @Query("DELETE FROM progress WHERE bookId = :bookId")
    suspend fun deleteBook(bookId: String)
}
