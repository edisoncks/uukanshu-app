package cc.uukanshu.data.repo

import cc.uukanshu.data.db.BookEntity

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
}
