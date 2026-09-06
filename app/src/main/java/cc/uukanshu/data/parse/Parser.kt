package cc.uukanshu.data.parse

import cc.uukanshu.BASE_URL

/**
 * Pure HTML parsers — Kotlin port of uukanshu-cli (`__init__.py`).
 *
 * Facade over focused sub-parsers ([BookIds], [CardsParser], [TocParser],
 * [MetaParser], [ChapterParser]) so each quirk lives in one small file with
 * precompiled patterns. This object keeps the stable public API (data
 * classes + `parse*` functions) so callers and tests do not churn.
 *
 * Pitfalls preserved from the CLI (do not "simplify") — see SCRAPING.md:
 * LAST-occurrence TOC dedup, `mulu-box*` + LAST nav-row cut,
 * urljoin-then-validate nav, `readcotent`/`readcontent` tolerance,
 * canonical book URLs, `<span class=hot>` strip. Text only.
 */
object Parser {
    /** Canonical host — single source is [BASE_URL]; this alias keeps call sites stable. */
    const val BASE = BASE_URL

    data class BookItem(
        val id: String,
        val title: String,
        val author: String = "",
        val words: String = "",
        val reads: String = "",
        val latestChapterTitle: String = "",
        val latestChapterUrl: String? = null,
        val intro: String = "",
        val category: String = "",
    )

    data class ChapterRef(
        val position: Int,
        val pageId: Long,
        val title: String,
        val url: String,
    )

    data class ChapterContent(
        val book: String,
        val title: String,
        val text: String,
        val prevUrl: String?,
        val tocUrl: String?,
        val nextUrl: String?,
    )

    data class BookMeta(
        val title: String,
        val author: String,
        val words: String,
        val category: String,
        val status: String,
        val intro: String,
        val latestChapterTitle: String,
        val latestChapterUrl: String?,
        val updatedAt: String,
    )

    data class SearchResult(val total: Int?, val books: List<BookItem>)

    // -- book URL (see BookIds) ------------------------------------------

    fun normalizeBookId(raw: String?): String? = BookIds.normalizeBookId(raw)

    fun bookUrlOrNull(url: String): String? = BookIds.bookUrlOrNull(url)

    fun bookIdOrNull(url: String): String? = BookIds.bookIdOrNull(url)

    fun chapterPageIdOrNull(url: String): Long? = BookIds.chapterPageIdOrNull(url)

    fun canonicalChapterUrl(href: String, base: String): String? =
        BookIds.canonicalChapterUrl(href, base)

    // -- cards / search (see CardsParser) ---------------------------------

    fun parseCategory(html: String): List<BookItem> = CardsParser.parseBookBoxes(html)

    fun parseSearch(html: String): SearchResult = CardsParser.parseSearch(html)

    // -- TOC (see TocParser) ----------------------------------------------

    fun parseToc(html: String, bookId: String? = null): List<ChapterRef> =
        TocParser.parseToc(html, bookId)

    // -- meta (see MetaParser) --------------------------------------------

    fun parseBookMeta(html: String, pageUrl: String): BookMeta =
        MetaParser.parseBookMeta(html, pageUrl)

    // -- chapter (see ChapterParser) --------------------------------------

    fun parseChapter(page: String, pageUrl: String): ChapterContent =
        ChapterParser.parseChapter(page, pageUrl)
}
