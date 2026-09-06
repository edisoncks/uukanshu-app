package cc.uukanshu.data.repo

import cc.uukanshu.data.parse.Parser

/**
 * Pure bulk-download planning.
 *
 * Extracted from `BookRepo.downloadAll` so the "which chapters are missing"
 * rule is unit-testable without Room/network. The repo preloads the cached
 * id set once (`ChapterDao.cachedPageIds`) and checks membership in memory
 * instead of one `chapterContent()` query per chapter (N+1).
 */
object DownloadPlan {
    /** Chapters without cached text, in TOC order. */
    fun missing(
        chapters: List<Parser.ChapterRef>,
        cachedIds: Set<Long>,
    ): List<Parser.ChapterRef> = chapters.filter { it.pageId !in cachedIds }

    /** True when every chapter already has cached text (loop is a local no-op). */
    fun isComplete(chapters: List<Parser.ChapterRef>, cachedIds: Set<Long>): Boolean =
        chapters.isNotEmpty() && chapters.all { it.pageId in cachedIds }
}
