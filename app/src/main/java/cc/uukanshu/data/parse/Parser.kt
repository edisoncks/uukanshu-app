package cc.uukanshu.data.parse

/** Pure HTML parsers. Full port of uukanshu-cli lands in milestone 2. */
object Parser {
    /** Canonical book URL, or null when `url` is not a book index page. */
    fun bookUrlOrNull(url: String): String? {
        val m = Regex("""https?://(?:www\.)?uukanshu\.cc/book/(\d+)/?(?:index\.html)?""")
            .matchEntire(url.trim()) ?: return null
        return "https://uukanshu.cc/book/${m.groupValues[1].toInt()}/"
    }
}
