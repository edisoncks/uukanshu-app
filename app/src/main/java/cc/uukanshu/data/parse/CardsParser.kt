package cc.uukanshu.data.parse

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Search/category card parser. Strips `<span class=hot>` highlights via
 * `.text()`; word-count anchors on the 字數 label so an author name
 * containing 字 cannot hijack the field.
 */
object CardsParser {
    private val countRe = Regex("""共有\s*(\d+)\s*條""")
    private val tagStripRe = Regex("<[^>]+>")

    fun parseBookBoxes(html: String): List<Parser.BookItem> {
        val doc: Document = Jsoup.parse(html)
        return doc.select("div.bookbox").mapNotNull { box ->
            val nameAnchor = box.selectFirst(".bookname a") ?: return@mapNotNull null
            val href = nameAnchor.attr("href")
            val id = BookIds.bookIdOrNull(href) ?: return@mapNotNull null
            val title = nameAnchor.text().trim()
            if (title.isEmpty()) return@mapNotNull null
            val author = box.select(".author").firstOrNull()
                ?.text()?.substringAfter("作者", "")?.trim('：', ':', ' ', ' ')?.trim().orEmpty()
            val words = box.select(".author").map { it.text() }
                .firstOrNull { "字數" in it }?.trim().orEmpty()
            val reads = box.select(".author").map { it.text() }
                .firstOrNull { "閱讀" in it || "阅读" in it }?.trim().orEmpty()
            val catAnchor = box.selectFirst(".cat a")
            val latestTitle = catAnchor?.text()?.trim().orEmpty()
            val latestUrl = catAnchor?.attr("href")?.takeIf { it.isNotBlank() }
                ?.let { BookIds.absolutize(it, "${Parser.BASE}/book/$id/") }
            val intro = box.selectFirst(".update")?.text()
                ?.substringAfter("簡介", "")?.trim('：', ':', ' ', ' ')?.trim().orEmpty()
            Parser.BookItem(id, title, author, words, reads, latestTitle, latestUrl, intro)
        }
    }

    fun parseSearch(html: String): Parser.SearchResult {
        val doc: Document = Jsoup.parse(html)
        if (doc.selectFirst("h1.booktitle") != null ||
            doc.selectFirst("meta[property=og:type]")?.attr("content") == "novel"
        ) {
            val id = doc.selectFirst("meta[property=og:book_id]")?.attr("content")?.trim()
                ?.takeIf { it.toIntOrNull() != null }
                ?: doc.selectFirst("meta[property=og:novel:read_url]")?.attr("content")
                    ?.let { BookIds.bookIdOrNull(it) }
            val normId = BookIds.normalizeBookId(id)
            if (normId != null) {
                val meta = MetaParser.parseBookMeta(html, "${Parser.BASE}/book/$normId/")
                return Parser.SearchResult(
                    1,
                    listOf(
                        Parser.BookItem(
                            id = normId,
                            title = meta.title,
                            author = meta.author,
                            words = meta.words,
                            latestChapterTitle = meta.latestChapterTitle,
                            latestChapterUrl = meta.latestChapterUrl,
                            intro = meta.intro,
                            category = meta.category,
                        ),
                    ),
                )
            }
            return Parser.SearchResult(null, emptyList())
        }
        val total = countRe.find(html.replace(tagStripRe, " "))
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        return Parser.SearchResult(total, parseBookBoxes(html))
    }
}
