package cc.uukanshu.data.parse

/**
 * Shared HTML character-reference decoding (single rule for all parsers).
 *
 * Delegates to Jsoup's entity table instead of a hand-written replace chain:
 * the old list covered six named entities, so numeric references (`&#20013;`,
 * `&#x4E2D;`) and typographic ones (`&ldquo;`, `&rdquo;` — common in Chinese
 * fiction) survived into cached text and reached the reader as literal markup.
 * The only numeric reference the old chain handled was the literal `&#39;`.
 * It also decoded `&amp;lt;` twice (a replace-chain artefact); Jsoup decodes
 * each reference exactly once.
 *
 * `&nbsp;` becomes a plain space rather than U+00A0, because Kotlin's `trim()`
 * and the parser's `\s`-based nav cut do not treat NBSP as whitespace; leaving
 * U+00A0 in place would let footer nav rows slip past the cut. Called on
 * already tag-stripped text, so this is a text decode, never an HTML parse.
 */
internal object ParserText {
    fun unescape(s: String): String =
        org.jsoup.parser.Parser.unescapeEntities(s, false).replace('\u00A0', ' ')
}
