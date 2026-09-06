package cc.uukanshu.data.parse

/** Shared HTML entity unescape (single rule for all parsers). */
internal object ParserText {
    fun unescape(s: String): String =
        s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
}
