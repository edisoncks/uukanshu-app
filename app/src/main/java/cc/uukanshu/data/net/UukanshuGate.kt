package cc.uukanshu.data.net

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    suspend fun <T> withPermit(block: suspend () -> T): T =
        mutex.withLock { block() }
}
