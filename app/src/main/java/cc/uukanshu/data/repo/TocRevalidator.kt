package cc.uukanshu.data.repo

import cc.uukanshu.core.Errors
import cc.uukanshu.data.parse.Parser
import kotlinx.coroutines.CancellationException

/**
 * Shared stale-while-revalidate rule for Detail and Reader.
 *
 * An empty fresh TOC is a failed parse (block page / layout change), never
 * a real empty book — see SCRAPING.md. Single definition so the two screens
 * cannot drift (previously duplicated in both ViewModels).
 */
class TocRevalidator(
    private val repo: cc.uukanshu.di.ReadingApi,
) {
    sealed interface Revalidate {
        data class Accepted(val detail: BookRepo.Detail) : Revalidate
        data object RejectedEmpty : Revalidate
        data class Failed(val error: Exception) : Revalidate
    }

    companion object {
        fun shouldAcceptFresh(freshChapters: List<Parser.ChapterRef>): Boolean =
            freshChapters.isNotEmpty()
    }

    suspend fun cached(bookId: String): BookRepo.Detail? =
        Errors.suppressExceptCancel { repo.cachedDetail(bookId) }
            ?.takeIf { it.chapters.isNotEmpty() }

    suspend fun revalidate(bookId: String): Revalidate {
        try {
            val fresh = repo.detail(bookId)
            if (!shouldAcceptFresh(fresh.chapters)) return Revalidate.RejectedEmpty
            return Revalidate.Accepted(fresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Revalidate.Failed(e)
        }
    }
}
