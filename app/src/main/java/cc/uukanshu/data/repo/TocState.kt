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
 *
 * [Ready.staleReason] tells apart the Stale causes a consumer must treat
 * differently: [StaleReason.Shrunk] cannot clear itself (the guard's baseline is
 * the cached row count, so retrying keeps failing — see SCRAPING.md) and Detail
 * therefore offers a user-confirmed override for it; the others resolve on the
 * next fetch or when the network returns.
 */
sealed interface TocState {
    /** No cache and fetch in flight (also the producer's no-cache first emission). */
    data object Loading : TocState

    data class Ready(
        val meta: Parser.BookMeta,
        val chapters: List<Parser.ChapterRef>,
        val phase: Phase,
        /** Set on [Phase.Stale] only; null for Syncing/Fresh. */
        val staleReason: StaleReason? = null,
    ) : TocState

    /** No cache AND fetch failed. Terminal until the producer restarts. */
    data class Failed(val message: String) : TocState

    enum class Phase { Syncing, Fresh, Stale }

    enum class StaleReason {
        /** Fresh TOC shrank vs the cached row count: sticky, needs the override. */
        Shrunk,

        /** Fresh TOC came back empty (block page / layout change); nothing was written. */
        Empty,

        /** Fetch threw (network/Cloudflare); the cache stays the truth. */
        Error,
    }
}
