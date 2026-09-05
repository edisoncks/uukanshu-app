package cc.uukanshu.data.parse

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Pure HTML parsers — Kotlin port of uukanshu-cli (`__init__.py`).
 *
 * Pitfalls preserved from the CLI (do not "simplify"):
 * - TOC keeps the LAST occurrence of each (book, chapter) pair (the page
 *   leads with a 'latest updates' dup block) and drops links for other
 *   books (recommendation blocks). Book ids compare numerically.
 * - Chapter body cuts at `<div class="mulu-box"` first, then at the LAST
 *   standalone nav row (`上一章 ... 下一章`), so in-body mentions don't
 *   truncate text.
 * - Prev/next hrefs resolve via urljoin semantics BEFORE shape validation;
 *   non-chapter hrefs (TOC index, lastchapter.php) mean end-of-book (null).
 * - Search/category cards strip `<span class=hot>` highlights via text().
 * - Text only: images/iframes/scripts are never surfaced.
 */
object Parser {
    const val BASE = "https://uukanshu.cc"

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

    // -- book URL ------------------------------------------------------

    /** Canonical book URL, or null when `url` is not a book index page. */
    fun bookUrlOrNull(url: String): String? {
        val m = Regex("""https?://(?:www\.)?uukanshu\.cc/book/(\d+)/?(?:index\.html)?""")
            .matchEntire(url.trim()) ?: return null
        // Huge digit runs match \d+ but overflow Int: null, never throw.
        val id = m.groupValues[1].toIntOrNull() ?: return null
        return "$BASE/book/$id/"
    }

    fun bookIdOrNull(url: String): String? =
        Regex("""/book/(\d+)/?""").find(url)?.groupValues?.getOrNull(1)
            ?.let { runCatching { it.toInt().toString() }.getOrNull() }

    fun chapterPageIdOrNull(url: String): Long? =
        Regex("""/book/\d+/(\d+)\.html""").find(url)?.groupValues?.getOrNull(1)?.toLongOrNull()

    // -- category / search cards ----------------------------------------

    private fun parseBookBoxes(html: String): List<BookItem> {
        val doc: Document = Jsoup.parse(html)
        return doc.select("div.bookbox").mapNotNull { box ->
            // Title anchor: .bookname a (search wraps hot spans; category is plain).
            val nameAnchor = box.selectFirst(".bookname a") ?: return@mapNotNull null
            val href = nameAnchor.attr("href")
            val id = bookIdOrNull(href) ?: return@mapNotNull null
            val title = nameAnchor.text().trim()
            if (title.isEmpty()) return@mapNotNull null
            // Authors appear as `作者：X` plain text or `作者：<a>X</a>`.
            val author = box.select(".author").firstOrNull()
                ?.text()?.substringAfter("作者", "")?.trim('：', ':', ' ', ' ')?.trim().orEmpty()
            val words = box.select(".author").map { it.text() }
                .firstOrNull { "字" in it }?.trim().orEmpty()
            val reads = box.select(".author").map { it.text() }
                .firstOrNull { "閱讀" in it || "阅读" in it }?.trim().orEmpty()
            val catAnchor = box.selectFirst(".cat a")
            val latestTitle = catAnchor?.text()?.trim().orEmpty()
            val latestUrl = catAnchor?.attr("href")?.takeIf { it.isNotBlank() }
                ?.let { absolutize(it, "$BASE/book/$id/") }
            val intro = box.selectFirst(".update")?.text()
                ?.substringAfter("簡介", "")?.trim('：', ':', ' ', ' ')?.trim().orEmpty()
            BookItem(id, title, author, words, reads, latestTitle, latestUrl, intro)
        }
    }

    fun parseCategory(html: String): List<BookItem> = parseBookBoxes(html)

    data class SearchResult(val total: Int?, val books: List<BookItem>)

    fun parseSearch(html: String): SearchResult {
        val total = Regex("""共有\s*(\d+)\s*條""").find(html)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        return SearchResult(total, parseBookBoxes(html))
    }

    // -- TOC ------------------------------------------------------------

    private val tocLink = Regex(
        """href=["'](?:https?://(?:www\.)?uukanshu\.cc)?(/book/(\d+)/(\d+)\.html)["'][^>]*>\s*([^<]+?)\s*</a>""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * [(position, pageId, title, url)] in reading order.
     * Keeps LAST occurrence per (book, chapter); filters to [bookId]
     * numerically when given.
     */
    fun parseToc(html: String, bookId: String? = null): List<ChapterRef> {
        val matches = tocLink.findAll(html).toList()
        val wanted = bookId?.let { runCatching { it.trim().toInt() }.getOrNull() ?: -1 }
        val lastIdx = mutableMapOf<Pair<String, String>, Int>()
        matches.forEachIndexed { i, m ->
            if (wanted != null && m.groupValues[2].toIntOrNull() != wanted) return@forEachIndexed
            lastIdx[m.groupValues[2] to m.groupValues[3]] = i
        }
        val out = mutableListOf<ChapterRef>()
        val seen = mutableSetOf<Pair<String, String>>()
        matches.forEachIndexed { i, m ->
            if (wanted != null && m.groupValues[2].toIntOrNull() != wanted) return@forEachIndexed
            val key = m.groupValues[2] to m.groupValues[3]
            if (key in seen || lastIdx[key] != i) return@forEachIndexed
            val pageId = chapterPageIdOrNull(BASE + m.groupValues[1]) ?: return@forEachIndexed
            seen += key
            out += ChapterRef(
                position = out.size + 1,
                pageId = pageId,
                title = unescape(m.groupValues[4].trim()),
                url = BASE + m.groupValues[1],
            )
        }
        return out
    }

    // -- book detail meta -------------------------------------------------

    fun parseBookMeta(html: String, pageUrl: String): BookMeta {
        val doc = Jsoup.parse(html, pageUrl)
        val title = doc.selectFirst("h1.booktitle")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim().orEmpty()
        val tag = doc.selectFirst("p.booktag")?.text().orEmpty()
        val author = doc.selectFirst("p.booktag a.red")?.text()?.trim()
            ?: Regex("""作者[：:]\s*(\S+)""").find(tag)?.groupValues?.getOrNull(1).orEmpty()
        val spans = doc.select("p.booktag span").map { it.text().trim() }
        val words = spans.firstOrNull { "字" in it }.orEmpty()
        val category = spans.firstOrNull { "字" !in it && it != spans.firstOrNull() }?.let {
            // category is the non-words blue span; status is the red span.
            doc.select("p.booktag span.blue").map { s -> s.text().trim() }
                .firstOrNull { s -> "字" !in s }.orEmpty()
        }.orEmpty()
        val status = doc.select("p.booktag span.red").map { it.text().trim() }
            .firstOrNull { it == "連載" || it == "完結" || it == "连载" || it == "完结" }.orEmpty()
        val intro = doc.selectFirst("p.bookintro")?.text()?.trim().orEmpty()
        val latest = doc.selectFirst("a.bookchapter")
        val updatedAt = doc.selectFirst("p.booktime")?.text()
            ?.substringAfter("更新時間", "")?.trim('：', ':', ' ').orEmpty()
        return BookMeta(
            title = title,
            author = author,
            words = words,
            category = category,
            status = status,
            intro = intro,
            latestChapterTitle = latest?.text()?.trim().orEmpty(),
            latestChapterUrl = latest?.attr("href")?.takeIf { it.isNotBlank() }
                ?.let { absolutize(it, pageUrl) },
            updatedAt = updatedAt,
        )
    }

    // -- chapter ----------------------------------------------------------

    // Accept http/https, with/without www, or root-relative. bookUrlOrNull
    // already treats http+www as valid book URLs; nav validation must agree
    // or mid-book prev/next links read as end-of-book (null).
    private val chapterHref = Regex("""(?:https?://(?:www\.)?uukanshu\.cc)?/book/\d+/\d+\.html""")

    private fun absolutize(href: String, base: String): String {
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        return try {
            java.net.URI(base).resolve(href).toString()
        } catch (_: Exception) {
            if (href.startsWith("/")) BASE + href else "$BASE/$href"
        }
    }

    private fun navLink(page: String, pageUrl: String, label: Regex): String? {
        // href may precede or follow other attrs; resolve BEFORE validating.
        val m = Regex(
            """<a\s[^>]*?href=["']([^"']+)["'][^>]*>\s*${label.pattern}\s*</a>""",
        ).find(page) ?: return null
        val abs = absolutize(m.groupValues[1], pageUrl)
        return if (chapterHref.matchEntire(abs) != null) abs else null
    }

    fun parseChapter(page: String, pageUrl: String): ChapterContent {
        val h1 = Regex("<h1[^>]*>(.*?)</h1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(page)?.groupValues?.getOrNull(1)
        val title = if (h1 != null) {
            unescape(Jsoup.parse(h1).text().trim()).ifEmpty { pageUrl }
        } else pageUrl

        // Book name: prefer breadcrumb anchor for THIS book id.
        var book = ""
        val ownId = bookIdOrNull(pageUrl)
        if (ownId != null) {
            book = Regex(
                """<a href=["'](?:https?://[^"']*)?/book/$ownId/["'][^>]*>([^<]+)</a>""",
            ).find(page)?.groupValues?.getOrNull(1)?.let { unescape(it.trim()) }.orEmpty()
        }
        if (book.isEmpty()) {
            book = Regex("""<a href=["'](?:https?://[^"']*)?/book/\d+/["'][^>]*>([^<]+)</a>""")
                .findAll(page).lastOrNull()?.groupValues?.getOrNull(1)
                ?.let { unescape(it.trim()) }.orEmpty()
        }

        val bodyRaw = Regex(
            """<div\s+class=["']readcotent[^"']*["'][^>]*>(.*)""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        ).find(page)?.groupValues?.getOrNull(1)
            ?: throw IllegalArgumentException(
                "could not find chapter content on the page (is this a chapter URL?)",
            )
        var body = bodyRaw.split(Regex("""<div\s+class=["']mulu-box["']"""), limit = 2)[0]
        body = Regex("<script[^>]*>.*?</script>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .replace(body, "")
        body = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE).replace(body, "\n")
        body = Regex("<[^>]+>").replace(body, "")
        body = body.replace("&emsp;", "")
        var text = unescape(body).lines().map { it.trim() }
            .filter { it.isNotEmpty() }.joinToString("\n\n")
        // Cut at the LAST standalone nav row (tolerate 简/繁 prefix).
        val nav = Regex("""\n上一章\s+(?:章节|章節)?目[录錄]\s+下一章(?=\s|$)""")
        val navHits = nav.findAll(text).toList()
        if (navHits.isNotEmpty()) text = text.substring(0, navHits.last().range.first).trimEnd()

        return ChapterContent(
            book = book,
            title = title,
            text = text,
            prevUrl = navLink(page, pageUrl, Regex("上一章")),
            tocUrl = navLink(page, pageUrl, Regex("目[录錄]")),
            nextUrl = navLink(page, pageUrl, Regex("下一章")),
        )
    }

    private fun unescape(s: String): String =
        s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
}
