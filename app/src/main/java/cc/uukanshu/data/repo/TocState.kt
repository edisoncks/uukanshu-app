package cc.uukanshu.data.repo

import cc.uukanshu.data.parse.Parser

/**
 * One TOC generation, emitted whole by [TocSource]. Every emission is a
 * self-consistent snapshot; consumers derive flags, never track them.
 *
 * [Phase] replaces a correlated `(stale, refreshing)` boolean pair — three
 * valid states, one field, compiler-checked:
 *  - [Phase.Syncing]: cache painted, refresh in flight (Detail thin bar).
 *  - [Phase.Fresh]:   network TOC accepted this run (badge clear, total bump).
 *  - [Phase.Stale]:   refresh rejected/failed; painted cache stays the truth
 *                     (Detail 離線模式 banner; reader keeps reading).
 */
sealed interface TocState {
    /** No cache and fetch in flight (also the producer's no-cache first emission). */
    data object Loading : TocState

    data class Ready(
        val meta: Parser.BookMeta,
        val chapters: List<Parser.ChapterRef>,
        val phase: Phase,
    ) : TocState

    /** No cache AND fetch failed. Terminal until the producer restarts. */
    data class Failed(val message: String) : TocState

    enum class Phase { Syncing, Fresh, Stale }
}
