package cc.uukanshu.data.parse

import org.jsoup.Jsoup

/**
 * Chapter body parser. Cuts at `<div class="mulu-box"` first, then at the
 * LAST standalone nav row, so in-body mentions never truncate text.
 * Accepts both `readcotent` (live misspelling) and `readcontent`.
 * All patterns precompiled (the old code built ~10 `Regex` per chapter).
 */
object ChapterParser {
    private val h1Re = Regex("<h1[^>]*>(.*?)</h1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val bodyRe = Regex(
        """<div\s+class=["']readcon?tent[^"']*["'][^>]*>(.*)""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val muluSplitRe = Regex("""<div\s+class=["']mulu-box[^"']*["']""")
    private val scriptRe = Regex("<script[^>]*>.*?</script>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val brRe = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val tagRe = Regex("<[^>]+>")
    private val navCutRe = Regex("""\n上一章\s+(?:章节|章節)?目[录錄]\s+下一章(?=\s|$)""")
    private val prevHrefRe = Regex("""<a\s[^>]*?href=["']([^"']+)["'][^>]*>\s*上一章\s*</a>""")
    private val tocHrefRe = Regex("""<a\s[^>]*?href=["']([^"']+)["'][^>]*>\s*目[录錄]\s*</a>""")
    private val nextHrefRe = Regex("""<a\s[^>]*?href=["']([^"']+)["'][^>]*>\s*下一章\s*</a>""")

    private fun navLink(page: String, pageUrl: String, re: Regex): String? {
        val m = re.find(page) ?: return null
        return BookIds.canonicalChapterUrl(m.groupValues[1], pageUrl)
    }

    fun parseChapter(page: String, pageUrl: String): Parser.ChapterContent {
        val h1 = h1Re.find(page)?.groupValues?.getOrNull(1)
        val title = if (h1 != null) {
            ParserText.unescape(Jsoup.parse(h1).text().trim()).ifEmpty { pageUrl }
        } else pageUrl

        var book = ""
        val ownId = BookIds.bookIdOrNull(pageUrl)
        if (ownId != null) {
            // Own-book breadcrumb is id-specific (precompiled per id would
            // cache unbounded ids, so this one Regex stays per call — it runs
            // once per chapter open, not per row).
            book = Regex(
                """<a href=["'](?:https?://[^"']*)?/book/$ownId/["'][^>]*>([^<]+)</a>""",
            ).find(page)?.groupValues?.getOrNull(1)?.let { ParserText.unescape(it.trim()) }.orEmpty()
        }
        if (book.isEmpty()) {
            book = Regex("""<a href=["'](?:https?://[^"']*)?/book/\d+/["'][^>]*>([^<]+)</a>""")
                .findAll(page).lastOrNull()?.groupValues?.getOrNull(1)
                ?.let { ParserText.unescape(it.trim()) }.orEmpty()
        }

        val bodyRaw = bodyRe.find(page)?.groupValues?.getOrNull(1)
            ?: throw IllegalArgumentException(
                "could not find chapter content on the page (is this a chapter URL?)",
            )
        var body = bodyRaw.split(muluSplitRe, limit = 2)[0]
        body = scriptRe.replace(body, "")
        body = brRe.replace(body, "\n")
        body = tagRe.replace(body, "")
        body = body.replace("&emsp;", "")
        var text = ParserText.unescape(body).lines().map { it.trim() }
            .filter { it.isNotEmpty() }.joinToString("\n\n")
        val navHits = navCutRe.findAll(text).toList()
        if (navHits.isNotEmpty()) text = text.substring(0, navHits.last().range.first).trimEnd()

        return Parser.ChapterContent(
            book = book,
            title = title,
            text = text,
            prevUrl = navLink(page, pageUrl, prevHrefRe),
            tocUrl = navLink(page, pageUrl, tocHrefRe),
            nextUrl = navLink(page, pageUrl, nextHrefRe),
        )
    }
}
