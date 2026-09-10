package cc.uukanshu.ui.reader

/**
 * Reader error kinds: what the error slot should offer.
 *
 * - Deleted: stable pageId missed (TOC shift deleted the chapter, position -1).
 *   Retrying the same position loops forever — offer back-to-detail instead.
 * - OutOfRange: bad position arg (never -1). Retry loops too, but it signals
 *   a nav bug, not a deletion — keep retry for now (existing behaviour).
 * - Network: fetch/cache failure. Retry is correct.
 *
 * Traditional source strings here so Display.text converts in 簡體 mode.
 */
enum class ReaderErrorKind {
    Network,
    Deleted,
    OutOfRange,
}

object ReaderErrors {
    fun boundsKind(effectivePosition: Int): ReaderErrorKind =
        if (effectivePosition == -1) ReaderErrorKind.Deleted else ReaderErrorKind.OutOfRange

    fun deletedMessage(): String = "該章節已刪除"
}
