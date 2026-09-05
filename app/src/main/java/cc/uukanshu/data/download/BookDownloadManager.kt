package cc.uukanshu.data.download

import cc.uukanshu.core.Errors
import cc.uukanshu.data.repo.BookRepo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * App-scoped full-book downloads.
 *
 * Previously the download lived in `DetailViewModel.viewModelScope`, so
 * popping `detail/{bookId}` cancelled it. Jobs here run in an
 * application-scope so navigating away (home/library/search, another book)
 * never aborts them; re-opening the detail re-attaches via [observe].
 *
 * Full-book downloads run one at a time: a second tapped book queues
 * behind the running one instead of interleaving chapter fetches, so
 * request pacing is always the single-book 1-3s `crawlDelay` profile.
 * Network stays single-flight: [BookRepo.downloadAll] funnels each chapter
 * fetch through `UukanshuGate`, so a background download and foreground
 * browsing serialize per HTTP request instead of overlapping.
 * `crawlDelay` stays outside the gate, so interactive taps interleave
 * per-chapter rather than blocking behind a whole book.
 */
class BookDownloadManager(
    private val downloadFn: suspend (String, (Int, Int) -> Unit) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    constructor(
        repo: BookRepo,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ) : this(
        downloadFn = { id, cb -> repo.downloadAll(id, cb) },
        scope = scope,
    )

    data class State(
        val downloading: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val error: String? = null,
    )

    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states

    private val jobs = ConcurrentHashMap<String, Job>()

    /** Bulk slot: whole-book downloads queue here, one at a time. */
    private val slot = Mutex()

    fun observe(bookId: String): Flow<State?> =
        _states.map { it[bookId] }.distinctUntilChanged()

    fun isDownloading(bookId: String): Boolean =
        jobs[bookId]?.isActive == true

    /** Idempotent start: a live job for [bookId] wins, second tap is a no-op. */
    fun start(bookId: String) {
        val existing = jobs[bookId]
        if (existing?.isActive == true) return
        _states.update { it + (bookId to State(downloading = true, done = 0, total = 0, error = null)) }
        val job = scope.launch {
            // Identity for the forget-race guard below.
            val self = coroutineContext[Job]!!
            try {
                slot.withLock {
                    downloadFn(bookId) { done, total ->
                        // A dying job's in-flight callback must not resurrect
                        // downloading=true after cancel() published false: drop
                        // publishes once this id no longer has a live job.
                        if (jobs[bookId]?.isActive == true) {
                            _states.update { cur ->
                                cur + (bookId to State(downloading = true, done = done, total = total, error = null))
                            }
                        }
                    }
                }
                // A concurrent forget()/forgetAll() (delete/clear-all) wins over
                // a late terminal publish: never resurrect stale done/total
                // for zero cached bytes after the cache was deleted.
                if (!self.isActive || jobs[bookId] !== self) return@launch
                _states.update { cur ->
                    val prev = cur[bookId]
                    cur + (bookId to State(
                        downloading = false,
                        done = prev?.done ?: 0,
                        total = prev?.total ?: 0,
                        error = null,
                    ))
                }
            } catch (e: CancellationException) {
                // cancel()/forget() already published downloading=false; keep done/total.
                throw e
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Same forget-wins guard as the success path.
                val self = coroutineContext[Job]
                if (self != null && jobs[bookId] !== self) return@launch
                _states.update { cur ->
                    val prev = cur[bookId]
                    cur + (bookId to State(
                        downloading = false,
                        done = prev?.done ?: 0,
                        total = prev?.total ?: 0,
                        error = Errors.userMessage(e),
                    ))
                }
            } finally {
                // Remove only our own entry: an old job finishing must never
                // evict a new job started after forget()+re-download.
                val self = coroutineContext[Job]
                if (self != null) jobs.remove(bookId, self) else jobs.remove(bookId)
            }
        }
        jobs[bookId] = job
    }

    fun cancel(bookId: String) {
        jobs.remove(bookId)?.cancel()
        _states.update { cur ->
            val prev = cur[bookId] ?: return@update cur
            if (!prev.downloading) return@update cur
            cur + (bookId to prev.copy(downloading = false))
        }
    }

    /**
     * Drop retained terminal state (finished/error/cancelled progress).
     * Call when the book's cache is deleted so a re-opened detail can't
     * replay a stale `done/total` (e.g. offering 重新下載整本 for zero
     * cached bytes). Cancels any live job for the id first.
     */
    fun forget(bookId: String) {
        jobs.remove(bookId)?.cancel()
        _states.update { cur -> cur - bookId }
    }

    /** Drop all retained state (used by clear-all). */
    fun forgetAll() {
        jobs.values.forEach { runCatching { it.cancel() } }
        jobs.clear()
        _states.update { emptyMap() }
    }
}
