package cc.uukanshu.data.parse

/**
 * TOC parser. Keeps the LAST occurrence of each (book, chapter) pair (page
 * leads with a 'latest updates' dup block) and drops links for other books.
 * Tolerates `?query`/`#fragment` tracking params (canonicalized).
 */
object TocParser {
    private val tocLink = Regex(
        """href=["'](?:https?://(?:www\.)?uukanshu\.cc)?(/book/(\d+)/(\d+)\.html)(?:[?#][^"']*)?["'][^>]*>\s*([^<]+?)\s*</a>""",
        RegexOption.IGNORE_CASE,
    )

    fun parseToc(html: String, bookId: String? = null): List<Parser.ChapterRef> {
        val matches = tocLink.findAll(html).toList()
        val wanted = bookId?.let { BookIds.bookIdIntOrNull(it) ?: -1 }
        val lastIdx = mutableMapOf<Pair<String, String>, Int>()
        matches.forEachIndexed { i, m ->
            if (wanted != null && m.groupValues[2].toIntOrNull() != wanted) return@forEachIndexed
            lastIdx[m.groupValues[2] to m.groupValues[3]] = i
        }
        val out = mutableListOf<Parser.ChapterRef>()
        val seen = mutableSetOf<Pair<String, String>>()
        matches.forEachIndexed { i, m ->
            if (wanted != null && m.groupValues[2].toIntOrNull() != wanted) return@forEachIndexed
            val key = m.groupValues[2] to m.groupValues[3]
            if (key in seen || lastIdx[key] != i) return@forEachIndexed
            val pageId = BookIds.chapterPageIdOrNull(Parser.BASE + m.groupValues[1])
                ?: return@forEachIndexed
            seen += key
            out += Parser.ChapterRef(
                position = out.size + 1,
                pageId = pageId,
                title = ParserText.unescape(m.groupValues[4].trim()),
                url = Parser.BASE + m.groupValues[1],
            )
        }
        return out
    }
}
