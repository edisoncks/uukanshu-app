package cc.uukanshu.data.repo

import cc.uukanshu.data.parse.Parser

/**
 * Bookmark resolution against the live TOC.
 *
 * Prefer stable pageId, fall back to position for pre-v4 rows (pageId == 0)
 * or vanished chapters only when it still names a live chapter. Never a
 * neighbor. Pure + unit-tested via [BookRepo] delegation.
 */
object BookmarkResolve {
    fun resolve(
        chapters: List<Parser.ChapterRef>,
        bookmark: BookRepo.Bookmark?,
    ): Parser.ChapterRef? {
        if (bookmark == null || chapters.isEmpty()) return null
        if (bookmark.pageId != 0L) {
            chapters.firstOrNull { it.pageId == bookmark.pageId }?.let { return it }
        }
        return chapters.firstOrNull { it.position == bookmark.position }
    }
}
