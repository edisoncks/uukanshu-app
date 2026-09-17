package cc.uukanshu

import cc.uukanshu.core.Errors
import cc.uukanshu.core.TocShrunkException
import cc.uukanshu.data.repo.BookRepo
import cc.uukanshu.data.repo.TocSource
import cc.uukanshu.data.repo.TocState
import cc.uukanshu.di.RepoApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Contract table for [TocSource]: cache paint + one terminal emission per run.
 * Failure arms (see TocState doc): cache survives empty/shrink/throw; no cache
 * + failure = Failed. Termination-by-construction is pinned here so callers
 * may `first {}` without a timeout band-aid.
 */
class TocSourceTest {

    @Test fun cacheFirstEmitsSyncingThenFresh() = runTest {
        val repo = MutableFakeRepo(cached = testDetail(1L, 2L), fresh = testDetail(1L, 2L, 3L))
        val states = TocSource(repo).toc("1").toList()
        assertEquals(
            listOf(
                TocState.Ready(testMeta(), testDetail(1L, 2L).chapters, TocState.Phase.Syncing),
                TocState.Ready(testMeta(), testDetail(1L, 2L, 3L).chapters, TocState.Phase.Fresh),
            ),
            states,
        )
    }

    @Test fun noCacheAcceptedFreshEmitsLoadingThenFresh() = runTest {
        val repo = MutableFakeRepo(fresh = testDetail(1L, 2L))
        val states = TocSource(repo).toc("1").toList()
        assertEquals(
            listOf(
                TocState.Loading,
                TocState.Ready(testMeta(), testDetail(1L, 2L).chapters, TocState.Phase.Fresh),
            ),
            states,
        )
    }

    @Test fun emptyFreshKeepsCacheAsStale() = runTest {
        val repo = MutableFakeRepo(cached = testDetail(1L, 2L), fresh = testDetail())
        val states = TocSource(repo).toc("1").toList()
        assertEquals(
            listOf(
                TocState.Ready(testMeta(), testDetail(1L, 2L).chapters, TocState.Phase.Syncing),
                TocState.Ready(testMeta(), testDetail(1L, 2L).chapters, TocState.Phase.Stale),
            ),
            states,
        )
    }

    @Test fun shrunkenFreshKeepsCacheAsStale() = runTest {
        val repo = MutableFakeRepo(cached = testDetail(1L, 2L), failure = TocShrunkException(2, 1))
        val states = TocSource(repo).toc("1").toList()
        assertEquals(
            listOf(
                TocState.Ready(testMeta(), testDetail(1L, 2L).chapters, TocState.Phase.Syncing),
                TocState.Ready(testMeta(), testDetail(1L, 2L).chapters, TocState.Phase.Stale),
            ),
            states,
        )
    }

    @Test fun shrunkenListReturnedWithoutThrowKeepsCacheAsStale() = runTest {
        // Defense in depth: the guard holds even when detail() returns a
        // shrunken list instead of throwing TocShrunkException (any RepoApi
        // may do so; BookRepo.detail's own throw is the second layer).
        val repo = MutableFakeRepo(
            cached = testDetail(1L, 2L, 3L, 4L, 5L),
            fresh = testDetail(1L, 2L),
        )
        val states = TocSource(repo).toc("1").toList()
        assertEquals(
            listOf(
                TocState.Ready(testMeta(), testDetail(1L, 2L, 3L, 4L, 5L).chapters, TocState.Phase.Syncing),
                TocState.Ready(testMeta(), testDetail(1L, 2L, 3L, 4L, 5L).chapters, TocState.Phase.Stale),
            ),
            states,
        )
    }

    @Test fun noCacheEmptyFreshFailsWithEmptyMessage() = runTest {
        val repo = MutableFakeRepo(fresh = testDetail())
        val states = TocSource(repo).toc("1").toList()
        assertEquals(
            listOf(TocState.Loading, TocState.Failed("章節列表為空，請稍後再試")),
            states,
        )
    }

    @Test fun noCacheNetworkFailureFailsFriendly() = runTest {
        val ioe = IOException("connection reset")
        val repo = MutableFakeRepo(failure = ioe)
        val states = TocSource(repo).toc("1").toList()
        assertEquals(
            listOf(TocState.Loading, TocState.Failed(Errors.friendly(ioe))),
            states,
        )
    }

    @Test fun networkFailureKeepsCacheAsStale() = runTest {
        val repo = MutableFakeRepo(cached = testDetail(1L), failure = IOException("down"))
        val states = TocSource(repo).toc("1").toList()
        assertEquals(
            listOf(
                TocState.Ready(testMeta(), testDetail(1L).chapters, TocState.Phase.Syncing),
                TocState.Ready(testMeta(), testDetail(1L).chapters, TocState.Phase.Stale),
            ),
            states,
        )
    }

    @Test fun cachedDetailFailureTreatedAsMissing() = runTest {
        val base = MutableFakeRepo(fresh = testDetail(1L))
        val repo = object : RepoApi by base {
            override suspend fun cachedDetail(bookId: String): BookRepo.Detail? =
                throw IOException("db down")
        }
        val states = TocSource(repo).toc("1").toList()
        assertEquals(
            listOf(
                TocState.Loading,
                TocState.Ready(testMeta(), testDetail(1L).chapters, TocState.Phase.Fresh),
            ),
            states,
        )
    }

    @Test fun cancellationPropagates() = runTest {
        val repo = object : RepoApi by MutableFakeRepo() {
            override suspend fun detail(bookId: String): BookRepo.Detail =
                throw CancellationException("cancelled")
        }
        try {
            TocSource(repo).toc("1").toList()
            throw AssertionError("expected cancellation to propagate, not be swallowed")
        } catch (e: CancellationException) {
            // expected: cancellation is never converted to Failed
        }
    }

    /** Every failure arm emits exactly paint + terminal — the invariant that lets
     *  callers `first { isTerminal }` without a timeout. A throw here fails the build. */
    @Test fun everyFailureArmEmitsTerminal() = runTest {
        val setups = listOf<(MutableFakeRepo) -> Unit>(
            { it.fresh = testDetail() },                                              // no cache, empty fresh
            { it.cached = testDetail(1L, 2L); it.failure = TocShrunkException(2, 1) }, // cache, shrink
            { it.failure = IOException("down") },                                     // no cache, network
            { it.cached = testDetail(1L); it.failure = IOException("down") },         // cache, network
        )
        for (setup in setups) {
            val repo = MutableFakeRepo()
            setup(repo)
            val states = TocSource(repo).toc("1").toList()
            assertEquals("expected paint + terminal, got $states", 2, states.size)
            val last = states.last()
            assertTrue(
                "expected terminal (Ready/Failed), got $states",
                last is TocState.Ready || last is TocState.Failed,
            )
        }
    }
}
