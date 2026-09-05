package cc.uukanshu.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import cc.uukanshu.data.parse.Parser
import kotlinx.coroutines.CancellationException

/**
 * One HTML page per load for the home lists (recent / category).
 *
 * The live recent feed shifts between requests: the same book id can end
 * one page and start the next (verified live: id 25745 on both page 1 and
 * 2). Appending without dedup produced duplicate LazyColumn keys and a
 * hard crash. Each source instance filters already-seen ids, so keys can
 * never collide no matter how the feed shifts; first occurrence wins and
 * order stays stable. A new instance (new Pager) restarts the set, so
 * tab/category switches never leak ids across lists.
 *
 * End of list follows the raw page: an empty raw page means no next key.
 * A fully-overlapped (but non-empty) raw page still advances — the next
 * page may hold fresh ids.
 *
 * Takes a plain [loadPage] lambda instead of [cc.uukanshu.data.repo.BookRepo]
 * so unit tests inject fakes with no mocking framework.
 */
class BookPagingSource(
    private val loadPage: suspend (page: Int) -> List<Parser.BookItem>,
) : PagingSource<Int, Parser.BookItem>() {

    private val seenIds = mutableSetOf<String>()

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Parser.BookItem> {
        val page = params.key ?: 1
        return try {
            val raw = loadPage(page)
            val fresh = raw.filter { seenIds.add(it.id) }
            LoadResult.Page(
                data = fresh,
                prevKey = if (page == 1) null else page - 1,
                nextKey = if (raw.isEmpty()) null else page + 1,
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, Parser.BookItem>): Int? =
        state.anchorPosition?.let { anchor ->
            state.closestPageToPosition(anchor)?.prevKey?.plus(1)
                ?: state.closestPageToPosition(anchor)?.nextKey?.minus(1)
        }
}
