package cc.uukanshu.data.repo

import cc.uukanshu.data.parse.Parser

/**
 * Bookmark resolution against the live TOC.
 *
 * Stable pageId wins; the position fallback is pre-v4 rows only
 * (pageId == 0). A vanished non-zero pageId means a deleted chapter:
 * yield no target, never the neighbor now sitting at the old position.
 * Pure + unit-tested via [BookRepo] delegation.
 */
object BookmarkResolve {
    fun resolve(
        chapters: List<Parser.ChapterRef>,
        bookmark: BookRepo.Bookmark?,
    ): Parser.ChapterRef? {
        if (bookmark == null || chapters.isEmpty()) return null
        if (bookmark.pageId != 0L) {
            return chapters.firstOrNull { it.pageId == bookmark.pageId }
        }
        return chapters.firstOrNull { it.position == bookmark.position }
    }
}
