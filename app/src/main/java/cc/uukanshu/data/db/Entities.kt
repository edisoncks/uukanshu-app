package cc.uukanshu.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
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

/**
 * Chapters are keyed by stable `pageId` (never by `position`, which shifts
 * when the site inserts chapters). `position` is display order only.
 * Writes/reads by pageId stay correct across TOC revalidations; the
 * old `(bookId, position)` key silently misfiled text after shifts.
 */
@Entity(
    tableName = "chapters",
    primaryKeys = ["bookId", "pageId"],
    indices = [Index(value = ["bookId", "position"])],
)
data class ChapterEntity(
    val bookId: String,
    val position: Int,
    val pageId: Long,
    val title: String,
    val url: String,
    val content: String = "",
)

@Entity(tableName = "progress", primaryKeys = ["bookId"])
data class ProgressEntity(
    val bookId: String,
    val position: Int,
    /** Stable chapter id; 0 = written before v4 (position-only fallback). */
    val pageId: Long = 0L,
    val updatedAt: Long = 0L,
)

/** Projection for TOC metadata diff: everything except the body (see TocDiff). */
data class ChapterMetaRef(
    val pageId: Long,
    val position: Int,
    val title: String,
    val url: String,
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

    /** Reactive shelf source for [cc.uukanshu.data.repo.BookRepo.libraryFlow] (no migration). */
    @Query("SELECT * FROM books ORDER BY updatedAt DESC")
    fun cachedBooksFlow(): Flow<List<BookEntity>>

    /** Bump shelf order without touching meta; no-op when the row is missing. */
    @Query("UPDATE books SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long)

    /** Cheap existence probe for the mid-download delete-abort check (no entity load). */
    @Query("SELECT EXISTS(SELECT 1 FROM books WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    /** Bulk wipe for clear-all (single statement, runs inside a transaction). */
    @Query("DELETE FROM books")
    suspend fun clearAll()
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY position")
    suspend fun chapters(bookId: String): List<ChapterEntity>

    /**
     * Stable ids with downloaded content, as a live stream for badges.
     * Keyed by pageId (never position): positions shift when the site inserts
     * chapters, which would mislabel badges until the next TOC revalidate.
     */
    @Query("SELECT pageId FROM chapters WHERE bookId = :bookId AND content != ''")
    fun cachedPositionsFlow(bookId: String): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(chapters: List<ChapterEntity>)

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteBook(bookId: String)

    /** Bulk wipe for clear-all (single statement, runs inside a transaction). */
    @Query("DELETE FROM chapters")
    suspend fun clearAll()

    /**
     * Single-row content read by stable pageId: no full-table scan to
     * render one chapter, and immune to TOC-shift aliasing (position may
     * already name a different chapter after a background revalidate).
     */
    @Query("SELECT content FROM chapters WHERE bookId = :bookId AND pageId = :pageId")
    suspend fun chapterContent(bookId: String, pageId: Long): String?

    /**
     * Atomic single-row content write by stable pageId. Never
     * read-modify-write the whole table here: a background TOC revalidate
     * does a wholesale upsert, and copying a stale row back over it loses
     * fresh titles or content. No-op when the TOC row is missing.
     * Keyed by pageId so a shifted TOC can never misfile text under the
     * wrong chapter (the old position-keyed write needed a caller-side
     * guard for the same hazard).
     */
    @Query("UPDATE chapters SET content = :content WHERE bookId = :bookId AND pageId = :pageId")
    suspend fun updateContent(bookId: String, pageId: Long, content: String)

    @Query("SELECT COUNT(*) FROM chapters WHERE bookId = :bookId AND content != ''")
    suspend fun cachedCount(bookId: String): Int

    /** Row count for the TOC shrink guard (see TocRevalidator): no entity load. */
    @Query("SELECT COUNT(*) FROM chapters WHERE bookId = :bookId")
    suspend fun countByBook(bookId: String): Int

    /**
     * Metadata snapshot for [TocDiff]: pageId + position/title/url, never
     * bodies. The old full-entity load pulled tens of MB of chapter text
     * just to preserve downloads across a refresh that no longer rewrites them.
     */
    @Query("SELECT pageId, position, title, url FROM chapters WHERE bookId = :bookId")
    suspend fun metas(bookId: String): List<ChapterMetaRef>

    /**
     * In-place metadata refresh by stable pageId. Never touches `content`:
     * TOC refreshes must not rewrite bodies (see TocDiff).
     */
    @Query("UPDATE chapters SET position = :position, title = :title, url = :url WHERE bookId = :bookId AND pageId = :pageId")
    suspend fun updateMeta(bookId: String, pageId: Long, position: Int, title: String, url: String)

    /** Prune pageIds absent from the accepted fresh TOC (see TocDiff). */
    @Query("DELETE FROM chapters WHERE bookId = :bookId AND pageId IN (:pageIds)")
    suspend fun deleteByPageIds(bookId: String, pageIds: List<Long>)

    /**
     * One-shot cached-id set for bulk planning (see `BookRepo.downloadAll`).
     * Lets the download loop check membership in memory instead of one
     * `chapterContent()` query per chapter (N+1).
     */
    @Query("SELECT pageId FROM chapters WHERE bookId = :bookId AND content != ''")
    suspend fun cachedPageIds(bookId: String): List<Long>

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

    /** Reactive stats source for libraryFlow (same SQL as [statsByBook]). */
    @Query(
        "SELECT bookId, COUNT(*) AS total, " +
            "SUM(CASE WHEN content != '' THEN 1 ELSE 0 END) AS cached, " +
            "COALESCE(SUM(LENGTH(CAST(content AS BLOB))), 0) AS bytes " +
            "FROM chapters GROUP BY bookId",
    )
    fun statsByBookFlow(): Flow<List<ChapterStats>>
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

    /** Reactive shelf-order source for libraryFlow. */
    @Query("SELECT * FROM progress")
    fun allFlow(): Flow<List<ProgressEntity>>

    @Query("DELETE FROM progress WHERE bookId = :bookId")
    suspend fun deleteBook(bookId: String)

    /**
     * Bulk wipe for clear-all. Unconditional by design: progress rows with
     * no book row (saveProgress upserts progress while touch() no-ops on a
     * missing book) would otherwise survive "clear all" forever.
     */
    @Query("DELETE FROM progress")
    suspend fun clearAll()
}
