package cc.uukanshu.data.net

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Single-flight gate for uukanshu.cc HTTP.
 *
 * All [SiteApi] traffic funnels through [withPermit], held per HTTP
 * attempt inside `SiteApi.send` (never by callers — `BookRepo` must not
 * wrap calls or nested Mutex acquisition would deadlock). Concurrent
 * callers (home refresh/loadMore, search, detail refresh, reader
 * load/revalidate/prefetch, background full-book downloads) suspend here
 * so at most one request is in flight.
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
    suspend fun <T> withPermit(block: suspend () -> T): T {
        if (coroutineContext[GateClaim.Key] != null) {
            error("nested UukanshuGate.withPermit would deadlock: call SiteApi (per-attempt gating) instead of wrapping BookRepo")
        }
        return mutex.withLock {
            withContext(GateClaim) { block() }
        }
    }
}
