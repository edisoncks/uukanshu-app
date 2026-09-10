package cc.uukanshu.ui.reader

/**
 * Single builder for TopBar line 2 (`position / total + title`).
 *
 * Progress was built in two composables (TopBar + sticky header) with two
 * formats — that's how it duplicated and drifted. One function, one call
 * site (TopBar). Content title is already converted in the VM, so this
 * takes it as-is and never calls display() itself.
 */
object ReaderHeader {
    fun line2(position: Int, total: Int, title: String): String =
        if (title.isEmpty()) "$position / $total" else "$position / $total  $title"
}
