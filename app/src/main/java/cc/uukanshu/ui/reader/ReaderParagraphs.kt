package cc.uukanshu.ui.reader

/**
 * Pure paragraph split for reader bodies.
 *
 * [cc.uukanshu.data.parse.ChapterParser] joins lines with "\n\n" (trimmed,
 * empties dropped), so this split is the exact inverse. No Regex: bodies are
 * already normalized, split-trim-drop is enough and cheap.
 */
object ReaderParagraphs {
    fun split(text: String): List<String> =
        text.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
}
