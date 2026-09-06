package cc.uukanshu.data.repo

import cc.uukanshu.data.db.ChapterEntity
import cc.uukanshu.data.parse.Parser

/**
 * TOC merge keyed by stable pageId (never position).
 *
 * Extracted from [BookRepo] companion so the rule is testable without the
 * network/DB. Without this, re-opening a book would REPLACE cached rows
 * with empty content and silently wipe downloads.
 */
object TocMerge {
    fun merge(
        bookId: String,
        refs: List<Parser.ChapterRef>,
        cachedByPageId: Map<Long, String>,
    ): List<ChapterEntity> = refs.map {
        ChapterEntity(
            bookId, it.position, it.pageId, it.title, it.url,
            content = cachedByPageId[it.pageId].orEmpty(),
        )
    }
}
