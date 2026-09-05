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

/** Shelf stats per book, computed in SQL (see [ChapterDao.statsByBook]). */
data class ChapterStats(
    val bookId: String,
    val total: Int,
    val cached: Int,
    val bytes: Long,
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

    /** Bump shelf order without touching meta; no-op when the row is missing. */
    @Query("UPDATE books SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long)
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

    /** Single-row content read: no full-table scan to render one chapter. */
    @Query("SELECT content FROM chapters WHERE bookId = :bookId AND position = :position")
    suspend fun chapterContent(bookId: String, position: Int): String?

    /**
     * Atomic single-row content write. Never read-modify-write the whole
     * table here: a background TOC revalidate does a wholesale upsert, and
     * copying a stale row back over it loses fresh titles or content.
     * No-op when the TOC row is missing.
     */
    @Query("UPDATE chapters SET content = :content WHERE bookId = :bookId AND position = :position")
    suspend fun updateContent(bookId: String, position: Int, content: String)

    @Query("SELECT COUNT(*) FROM chapters WHERE bookId = :bookId AND content != ''")
    suspend fun cachedCount(bookId: String): Int

    /**
     * Shelf stats in one round trip, without loading chapter contents.
     * LENGTH(CAST(content AS BLOB)) counts stored UTF-8 bytes, matching
     * the previous Kotlin-side accounting.
     */
    @Query(
        "SELECT bookId, COUNT(*) AS total, " +
            "SUM(CASE WHEN content != '' THEN 1 ELSE 0 END) AS cached, " +
            "COALESCE(SUM(LENGTH(CAST(content AS BLOB))), 0) AS bytes " +
            "FROM chapters GROUP BY bookId",
    )
    suspend fun statsByBook(): List<ChapterStats>
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM progress WHERE bookId = :bookId")
    suspend fun progress(bookId: String): ProgressEntity?

    @Query("SELECT * FROM progress WHERE bookId = :bookId")
    fun progressFlow(bookId: String): Flow<ProgressEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: ProgressEntity)

    @Query("SELECT * FROM progress")
    suspend fun all(): List<ProgressEntity>

    @Query("DELETE FROM progress WHERE bookId = :bookId")
    suspend fun deleteBook(bookId: String)
}
