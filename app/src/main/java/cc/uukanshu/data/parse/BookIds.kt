package cc.uukanshu.data.parse

/**
 * Book/chapter URL helpers. Single normalization point for every book id:
 * numeric compare ("001" == "1"), `Int`-overflow rejected as null (never
 * throw). All regexes precompiled — the old inline `Regex(...)` per call
 * allocated on every card/TOC row.
 */
object BookIds {
    private val bookUrlRe = Regex("""https?://(?:www\.)?uukanshu\.cc/book/(\d+)/?(?:index\.html)?/?""")
    private val bookIdRe = Regex("""/book/(\d+)/?""")
    private val chapterPageRe = Regex("""/book/\d+/(\d+)\.html""")

    // Shape check for canonical chapter URLs (after query/fragment strip).
    // Accepts http/https, with/without www, or root-relative.
    private val chapterHrefRe =
        Regex("""(?:https?://(?:www\.)?uukanshu\.cc)?/book/\d+/\d+\.html(?:[?#].*)?""")

    fun normalizeBookId(raw: String?): String? {
        val t = raw?.trim() ?: return null
        if (t.isEmpty()) return null
        val i = t.toIntOrNull() ?: return null
        if (i < 0) return null
        return i.toString()
    }

    fun bookIdIntOrNull(raw: String?): Int? =
        raw?.trim()?.toIntOrNull()?.takeIf { it >= 0 }

    fun bookUrlOrNull(url: String): String? {
        val clean = url.trim().substringBefore('?').substringBefore('#')
        val m = bookUrlRe.matchEntire(clean) ?: return null
        val id = normalizeBookId(m.groupValues[1]) ?: return null
        return "${Parser.BASE}/book/$id/"
    }

    fun bookIdOrNull(url: String): String? =
        bookIdRe.find(url)?.groupValues?.getOrNull(1)?.let { normalizeBookId(it) }

    fun chapterPageIdOrNull(url: String): Long? =
        chapterPageRe.find(url)?.groupValues?.getOrNull(1)?.toLongOrNull()

    fun absolutize(href: String, base: String): String {
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        return try {
            java.net.URI(base).resolve(href).toString()
        } catch (_: Exception) {
            if (href.startsWith("/")) Parser.BASE + href else "${Parser.BASE}/$href"
        }
    }

    /**
     * Single resolve → strip `?query`/`#fragment` → shape-validate step.
     * Returns canonical URL or null when not a chapter (TOC index,
     * lastchapter.php = end-of-book).
     */
    fun canonicalChapterUrl(href: String, base: String): String? {
        val abs = absolutize(href, base)
        val clean = abs.substringBefore('?').substringBefore('#')
        return if (chapterHrefRe.matchEntire(clean) != null) clean else null
    }
}
