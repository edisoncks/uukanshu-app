package cc.uukanshu.data.repo

import cc.uukanshu.data.parse.Parser

/**
 * Shared TOC shrink rule: empty/shrunken fresh TOC never wipes cache.
 * Pure + unit-tested; checked at consumption by the screen SWR in [TocSource]
 * and enforced at merge by `BookRepo.detail` (which owns the dbWrite-serialized
 * merge and throws `TocShrunkException` before any write).
 */
object TocRevalidator {
    /** Any regression vs [cachedCount] rejects; empty always rejects. */
    fun shouldAcceptFresh(
        freshChapters: List<Parser.ChapterRef>,
        cachedCount: Int = 0,
    ): Boolean =
        freshChapters.isNotEmpty() && freshChapters.size >= cachedCount
}
