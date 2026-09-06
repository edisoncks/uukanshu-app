package cc.uukanshu.data.parse

import org.jsoup.Jsoup

/**
 * Book detail meta parser (pure, precompiled patterns).
 */
object MetaParser {
    private val authorFallbackRe = Regex("""作者[：:]\s*(\S+)""")

    fun parseBookMeta(html: String, pageUrl: String): Parser.BookMeta {
        val doc = Jsoup.parse(html, pageUrl)
        val title = doc.selectFirst("h1.booktitle")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim().orEmpty()
        val tag = doc.selectFirst("p.booktag")?.text().orEmpty()
        val author = doc.selectFirst("p.booktag a.red")?.text()?.trim()
            ?: authorFallbackRe.find(tag)?.groupValues?.getOrNull(1).orEmpty()
        val spans = doc.select("p.booktag span").map { it.text().trim() }
        val words = spans.firstOrNull { "字" in it }.orEmpty()
        val category = doc.select("p.booktag span.blue").map { s -> s.text().trim() }
            .firstOrNull { s -> "字" !in s }.orEmpty()
        val status = doc.select("p.booktag span.red").map { it.text().trim() }
            .firstOrNull { it == "連載" || it == "完結" || it == "连载" || it == "完结" }.orEmpty()
        val intro = doc.selectFirst("p.bookintro")?.text()?.trim().orEmpty()
        val latest = doc.selectFirst("a.bookchapter")
        val updatedAt = doc.selectFirst("p.booktime")?.text()
            ?.substringAfter("更新時間", "")?.trim('：', ':', ' ').orEmpty()
        return Parser.BookMeta(
            title = title,
            author = author,
            words = words,
            category = category,
            status = status,
            intro = intro,
            latestChapterTitle = latest?.text()?.trim().orEmpty(),
            latestChapterUrl = latest?.attr("href")?.takeIf { it.isNotBlank() }
                ?.let { BookIds.absolutize(it, pageUrl) },
            updatedAt = updatedAt,
        )
    }
}
