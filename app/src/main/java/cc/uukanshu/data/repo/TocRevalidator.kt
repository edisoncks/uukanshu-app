package cc.uukanshu.data.repo

import cc.uukanshu.core.Errors
import cc.uukanshu.core.TocShrunkException
import cc.uukanshu.data.parse.Parser
import kotlinx.coroutines.CancellationException

/** Shared stale-while-revalidate rule: empty/shrunken fresh TOC never wipes cache. */
class TocRevalidator(
    private val repo: cc.uukanshu.di.RepoApi,
) {
    sealed interface Revalidate {
        data class Accepted(val detail: BookRepo.Detail) : Revalidate
        data object RejectedEmpty : Revalidate
        /** Fresh TOC shrank vs cache: truncated parse, never wipe (see SCRAPING.md). */
        data object RejectedShrink : Revalidate
        data class Failed(val error: Exception) : Revalidate
    }

    companion object {
        /** Any regression vs [cachedCount] rejects; empty always rejects. */
        fun shouldAcceptFresh(
            freshChapters: List<Parser.ChapterRef>,
            cachedCount: Int = 0,
        ): Boolean =
            freshChapters.isNotEmpty() && freshChapters.size >= cachedCount
    }

    suspend fun cached(bookId: String): BookRepo.Detail? =
        Errors.suppressExceptCancel { repo.cachedDetail(bookId) }
            ?.takeIf { it.chapters.isNotEmpty() }

    suspend fun revalidate(bookId: String, cachedCount: Int = 0): Revalidate {
        try {
            val fresh = repo.detail(bookId)
            if (fresh.chapters.isEmpty()) return Revalidate.RejectedEmpty
            if (!shouldAcceptFresh(fresh.chapters, cachedCount)) return Revalidate.RejectedShrink
            return Revalidate.Accepted(fresh)
        } catch (e: TocShrunkException) {
            return Revalidate.RejectedShrink
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Revalidate.Failed(e)
        }
    }
}
