package cc.uukanshu.data.repo

import cc.uukanshu.core.EmptyChapterListException
import cc.uukanshu.core.Errors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Screen-facing TOC stale-while-revalidate: paint cache instantly, refresh
 * once per producer run; empty/shrunken/failed refresh never wipes painted
 * cache (see SCRAPING.md). Cold flow per screen — no stored state, nothing
 * to supersede; a restart (cancel + re-collect) is the only re-trigger.
 *
 * Termination is by construction: every path emits a terminal state; the
 * only deadline is SiteApi's (interactive 90s — see SiteApi.INTERACTIVE_DEADLINE_MS).
 * Cancellation always propagates.
 *
 * Failure table (see TocState.Phase):
 *  - cache + empty/shrunken fresh → Ready(Stale) (block page / truncated parse;
 *      shrunken lists are rejected here via TocRevalidator even when detail()
 *      returns instead of throwing TocShrunkException)
 *  - cache + throw              → Ready(Stale)   (TocShrunkException, network)
 *  - no cache + empty fresh     → Failed(章節列表為空)   (same string as Detail)
 *  - no cache + throw           → Failed(Errors.friendly(e))
 */
class TocSource(private val repo: cc.uukanshu.di.RepoApi) {

    fun toc(bookId: String): Flow<TocState> = flow {
        val cached = Errors.runCatchingExceptCancel { repo.cachedDetail(bookId) }
            .getOrNull()
            ?.takeIf { it.chapters.isNotEmpty() }
        if (cached != null) {
            emit(TocState.Ready(cached.meta, cached.chapters, TocState.Phase.Syncing))
        } else {
            // No cache: paint Loading so a manual refresh shows a spinner
            // instead of leaving a stale error screen while the fetch runs.
            emit(TocState.Loading)
        }
        try {
            val fresh = repo.detail(bookId)
            // Defense in depth alongside BookRepo.detail's own guard: a shrunken
            // list must never wipe painted cache even when detail() returns it
            // instead of throwing TocShrunkException (any RepoApi may do so).
            if (!TocRevalidator.shouldAcceptFresh(fresh.chapters, cached?.chapters?.size ?: 0)) {
                // Single source for the empty-TOC string: Errors.friendly maps
                // EmptyChapterListException to Traditional "章節列表為空，請稍後再試"
                // (render-time converted); pinned by TocSourceTest + ErrorsFriendlyTest.
                emit(terminal(cached, Errors.friendly(EmptyChapterListException())))
            } else {
                emit(TocState.Ready(fresh.meta, fresh.chapters, TocState.Phase.Fresh))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(terminal(cached, Errors.friendly(e)))
        }
    }

    /** Cache present → Ready(Stale) with the painted rows; else loud Failed. */
    private fun terminal(cached: BookRepo.Detail?, failedMessage: String): TocState =
        cached?.let { TocState.Ready(it.meta, it.chapters, TocState.Phase.Stale) }
            ?: TocState.Failed(failedMessage)
}
