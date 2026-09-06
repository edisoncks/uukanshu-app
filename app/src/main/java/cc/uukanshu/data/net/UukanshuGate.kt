package cc.uukanshu.data.net

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** Lane for a uukanshu.cc request (kept for timeout selection in SiteApi). */
enum class FetchPriority {
    INTERACTIVE,
    BULK,
}

/**
 * Marker in the coroutine context for bulk crawl work (full-book downloads,
 * next-5 prefetch). [SiteApi] reads it to pick bulk timeouts.
 */
data object BulkFetch : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> = Key
    object Key : CoroutineContext.Key<BulkFetch>
}

/**
 * Single-flight gate for uukanshu.cc HTTP: at most one request in flight.
 * Held per HTTP attempt inside `SiteApi.send`, never across a whole download
 * loop — bulk releases it during backoff/`crawlDelay` so taps interleave.
 */
class UukanshuGate {
    private val mutex = Mutex()

    /** Marker while the permit is held; nesting would deadlock (Mutex is not reentrant). */
    private data object GateClaim : CoroutineContext.Element {
        override val key: CoroutineContext.Key<*> = Key
        object Key : CoroutineContext.Key<GateClaim>
    }

    suspend fun <T> withPermit(block: suspend () -> T): T =
        withPermit(FetchPriority.INTERACTIVE, block)

    suspend fun <T> withPermit(priority: FetchPriority, block: suspend () -> T): T {
        if (coroutineContext[GateClaim.Key] != null) {
            error("nested UukanshuGate.withPermit would deadlock: call SiteApi instead of wrapping BookRepo")
        }
        return mutex.withLock {
            withContext(GateClaim) { block() }
        }
    }
}
