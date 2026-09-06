package cc.uukanshu.data.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** Lane for a uukanshu.cc request: taps jump, bulk crawl waits (see below). */
enum class FetchPriority {
    INTERACTIVE,
    BULK,
}

/**
 * Marker in the coroutine context for bulk crawl work (full-book downloads,
 * next-5 prefetch). [SiteApi] reads it to pick the bulk lane + bulk
 * timeouts; callers opt in with `withContext(BulkFetch) { … }` so no
 * gateway/repo interface changes (same precedent as [UukanshuGate.GateClaim]).
 * Single chapter opens, TOC/search fetches and the download-loop TOC refresh
 * stay interactive — they are user-initiated and deserve the fast lane.
 */
data object BulkFetch : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> = Key
    object Key : CoroutineContext.Key<BulkFetch>
}

/**
 * Single-flight gate for uukanshu.cc HTTP, with an interactive priority lane.
 *
 * All [SiteApi] traffic funnels through [withPermit], held per HTTP
 * attempt inside `SiteApi.send` (never by callers — `BookRepo` must not
 * wrap calls or nested Mutex acquisition would deadlock). Concurrent
 * callers (home refresh/loadMore, search, detail refresh, reader
 * load/revalidate/prefetch, background full-book downloads) suspend here
 * so at most one request is in flight.
 *
 * Priority, honestly scoped: bulk defers to *waiting* interactive taps
 * (spins cancellably while interactives wait or the permit is held, so it
 * never parks ahead of a tap in the mutex queue). In-flight is never
 * preempted — cancelling a bulk execute mid-flight would abort user
 * downloads, so an interactive tap waits at most one in-flight bulk
 * attempt (bounded by the short bulk timeouts in `SiteApi`). Under a
 * continuous stream of taps bulk can starve; taps are user-paced, so in
 * practice bulk always progresses in the gaps.
 *
 * Scope is one HTTP attempt, never a whole download loop or DB transaction:
 * bulk downloads hold the permit only per chapter fetch (blocking execute),
 * releasing it during backoff delays and
 * [cc.uukanshu.data.repo.BookRepo.crawlDelay]
 * so interactive taps interleave instead of head-of-line blocking.
 *
 * GitHub update traffic ([cc.uukanshu.data.update.UpdateApi]) is a different
 * host and stays ungated.
 */
class UukanshuGate {
    private val mutex = Mutex()
    private val interactiveWaiters = AtomicInteger(0)

    /** Marker in the coroutine context while the permit is held. */
    private data object GateClaim : CoroutineContext.Element {
        override val key: CoroutineContext.Key<*> = Key
        object Key : CoroutineContext.Key<GateClaim>
    }

    /**
     * Run [block] with the single-flight permit held.
     *
     * Fail-fast on nesting: `Mutex` is not reentrant, so wrapping a
     * `BookRepo` call (which itself calls `SiteApi`, which acquires the
     * permit per attempt) would deadlock forever. Detect the claim in the
     * coroutine context and throw instead of hanging.
     */
    suspend fun <T> withPermit(block: suspend () -> T): T =
        withPermit(FetchPriority.INTERACTIVE, block)

    /**
     * Priority variant: [FetchPriority.BULK] yields to waiting interactive
     * taps (see class KDoc). Cancellation propagates immediately from the
     * spin or the mutex wait — `cancelDownload` never wedges here.
     */
    suspend fun <T> withPermit(priority: FetchPriority, block: suspend () -> T): T {
        if (coroutineContext[GateClaim.Key] != null) {
            error("nested UukanshuGate.withPermit would deadlock: call SiteApi (per-attempt gating) instead of wrapping BookRepo")
        }
        if (priority == FetchPriority.BULK) {
            // Defer while taps wait OR the lane is busy: parking on the
            // mutex here would queue ahead of a tap that arrives next.
            // Check-then-lock has a tiny race (a tap arriving between the
            // check and the lock still queues behind us), but the loser
            // holds at most one bounded bulk attempt (see SiteApi timeouts).
            while (interactiveWaiters.get() > 0 || mutex.isLocked) {
                coroutineContext.ensureActive()
                delay(BULK_YIELD_MS)
            }
            return mutex.withLock {
                withContext(GateClaim) { block() }
            }
        }
        interactiveWaiters.incrementAndGet()
        try {
            return mutex.withLock {
                withContext(GateClaim) { block() }
            }
        } finally {
            interactiveWaiters.decrementAndGet()
        }
    }

    companion object {
        /** Bulk yield granularity: negligible next to 1–3s crawl delays. */
        const val BULK_YIELD_MS = 20L
    }
}
