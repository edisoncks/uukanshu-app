package cc.uukanshu.data.repo

import cc.uukanshu.data.db.BookEntity
import cc.uukanshu.data.db.ChapterStats

/**
 * Shelf ordering: last interaction wins.
 *
 * Reading writes progress.updatedAt, downloading writes books.updatedAt;
 * either bumps the book to the top. Browsing Detail alone must never
 * reorder the shelf ([preserve] keeps the stamp across TOC refreshes).
 * Pure + unit-tested via [BookRepo] delegation.
 */
object ShelfOrder {
    fun lastActivity(bookAt: Long, progressAt: Long?): Long =
        maxOf(bookAt, progressAt ?: 0L)

    fun preserve(existing: BookEntity?, fresh: BookEntity, now: Long): BookEntity =
        if (existing != null) fresh.copy(updatedAt = existing.updatedAt)
        else fresh.copy(updatedAt = now)

    fun sort(
        books: List<BookRepo.CachedBook>,
        bookAt: Map<String, Long>,
        progressAt: Map<String, Long>,
    ): List<BookRepo.CachedBook> =
        books.sortedByDescending { lastActivity(bookAt[it.id] ?: 0L, progressAt[it.id]) }

    /**
     * Pure shelf assembly: rows + SQL stats → visible books (cached > 0),
     * then [sort]. Lets libraryFlow and one-shot library() share one rule,
     * unit-tested without Room.
     */
    fun assemble(
        rows: List<BookEntity>,
        stats: List<ChapterStats>,
        progressAt: Map<String, Long>,
    ): List<BookRepo.CachedBook> {
        val byId = stats.associateBy { it.bookId }
        val bookAt = rows.associate { it.id to it.updatedAt }
        return rows.mapNotNull { b ->
            val s = byId[b.id] ?: return@mapNotNull null
            if (s.cached == 0) return@mapNotNull null
            BookRepo.CachedBook(b.id, b.title, b.author, total = s.total, cached = s.cached, bytes = s.bytes)
        }.let { sort(it, bookAt, progressAt) }
    }
}
