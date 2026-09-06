package cc.uukanshu

import cc.uukanshu.data.parse.Parser
import cc.uukanshu.data.repo.BookRepo
import cc.uukanshu.data.repo.TocRevalidator
import cc.uukanshu.di.ReadingApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Shared TOC rule: single definition, both screens delegate here. */
class TocRevalidatorTest {
    private fun ref(pos: Int, pageId: Long) =
        Parser.ChapterRef(pos, pageId, "t-$pos", "https://uukanshu.cc/book/1/$pageId.html")

    private fun detail(vararg ids: Long): BookRepo.Detail {
        val chapters = ids.mapIndexed { i, id -> ref(i + 1, id) }
        return BookRepo.Detail(
            meta = Parser.BookMeta("T", "A", "", "", "", "", "", null, ""),
            chapters = chapters,
        )
    }

    private fun fake(
        cached: BookRepo.Detail? = null,
        fresh: Result<BookRepo.Detail>? = null,
    ): ReadingApi = object : ReadingApi {
        override suspend fun cachedDetail(bookId: String) = cached
        override suspend fun detail(bookId: String): BookRepo.Detail =
            fresh?.getOrThrow() ?: error("no fresh stub")
        override suspend fun chapter(url: String): Parser.ChapterContent = error("unused")
        override suspend fun cachedChapterContent(bookId: String, pageId: Long) = null
        override fun cachedPositionsFlow(bookId: String): Flow<Set<Long>> = emptyFlow()
        override suspend fun saveChapterContent(bookId: String, pageId: Long, content: String) = Unit
        override suspend fun saveProgress(bookId: String, position: Int, pageId: Long) = Unit
        override fun bookmarkFlow(bookId: String): Flow<BookRepo.Bookmark?> = emptyFlow()
        override suspend fun getBookmark(bookId: String) = null
        override suspend fun getProgress(bookId: String) = null
        override fun progressFlow(bookId: String): Flow<Int?> = emptyFlow()
    }

    @Test fun emptyFreshIsRejected() {
        assertTrue(TocRevalidator.shouldAcceptFresh(listOf(ref(1, 1L))))
        assertEquals(false, TocRevalidator.shouldAcceptFresh(emptyList()))
    }

    @Test fun acceptedFreshPassesThrough() = runBlocking {
        val fresh = detail(101L, 102L)
        val res = TocRevalidator(fake(fresh = Result.success(fresh))).revalidate("1")
        assertTrue(res is TocRevalidator.Revalidate.Accepted)
        assertEquals(fresh, (res as TocRevalidator.Revalidate.Accepted).detail)
    }

    @Test fun emptyFreshIsRejectedNotAccepted() = runBlocking {
        val res = TocRevalidator(fake(fresh = Result.success(detail()))).revalidate("1")
        assertTrue(res is TocRevalidator.Revalidate.RejectedEmpty)
    }

    @Test fun networkFailureSurfacesAsFailed() = runBlocking {
        val err = IOException("network down")
        val res = TocRevalidator(fake(fresh = Result.failure(err))).revalidate("1")
        assertTrue(res is TocRevalidator.Revalidate.Failed)
        assertEquals(err, (res as TocRevalidator.Revalidate.Failed).error)
    }

    @Test fun cachedEmptyDetailIsTreatedAsMissing() = runBlocking {
        val toc = TocRevalidator(fake(cached = detail()))
        assertNull(toc.cached("1"))
    }

    @Test fun cachedNonEmptyIsReturned() = runBlocking {
        val cached = detail(101L)
        assertEquals(cached, TocRevalidator(fake(cached = cached)).cached("1"))
    }
}
