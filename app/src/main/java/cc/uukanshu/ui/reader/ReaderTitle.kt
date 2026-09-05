package cc.uukanshu.ui.reader

/**
 * Pure book-title resolution for the reader header.
 *
 * `ui.book` must always be the book name, never a chapter title.
 * The cached-chapter path has no network payload, so it must use the
 * authoritative TOC meta title — never `ref.title` and never stale
 * `_ui.value.book` as the primary source. `ref.title` is deliberately
 * not a parameter: passing a chapter title in here is the bug.
 */
object ReaderTitle {
    fun resolve(metaTitle: String, rawBook: String, previousBook: String): String =
        when {
            metaTitle.isNotEmpty() -> metaTitle
            rawBook.isNotEmpty() -> rawBook
            else -> previousBook
        }
}
