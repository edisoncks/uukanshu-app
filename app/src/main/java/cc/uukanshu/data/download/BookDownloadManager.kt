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
 * App-scoped full-book downloads (survive leaving detail; re-attach via observe).
 * One book at a time (second queues); per-chapter gate keeps interactive taps interleaved.
 * See ARCHITECTURE.md.
 */
class BookDownloadManager(
    private val downloadFn: suspend (String, (Int, Int) -> Unit) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : cc.uukanshu.di.DownloadsApi {
    constructor(
        repo: cc.uukanshu.di.RepoApi,
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
    override val states: StateFlow<Map<String, State>> = _states

    private val jobs = ConcurrentHashMap<String, Job>()

    /** Guards check-then-act (CHM get-check-put is not atomic; prevents duplicate starts). */
    private val startLock = Any()

    /** Bulk slot: whole-book downloads queue here, one at a time. */
    private val slot = Mutex()

    override fun observe(bookId: String): Flow<State?> =
        _states.map { it[bookId] }.distinctUntilChanged()

    override fun isDownloading(bookId: String): Boolean =
        jobs[bookId]?.isActive == true

    /** Idempotent start (second tap no-op). */
    override fun start(bookId: String) {
        synchronized(startLock) {
            if (jobs[bookId]?.isActive == true) return
            _states.update { it + (bookId to State(downloading = true, done = 0, total = 0, error = null)) }
            val job = scope.launch {
            val self = coroutineContext[Job]!!
            try {
                slot.withLock {
                    downloadFn(bookId) { done, total ->
                        // Drop publishes once the job is gone (cancel can't lose to in-flight callback).
                        if (jobs[bookId]?.isActive == true) {
                            _states.update { cur ->
                                cur + (bookId to State(downloading = true, done = done, total = total, error = null))
                            }
                        }
                    }
                }
                // forget() wins over late terminal publishes (never resurrect stale progress).
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
                throw e
            } catch (e: Exception) {
                // forget-wins guard as above.
                val self = coroutineContext[Job]
                if (self != null && jobs[bookId] !== self) return@launch
                _states.update { cur ->
                    val prev = cur[bookId]
                    cur + (bookId to State(
                        downloading = false,
                        done = prev?.done ?: 0,
                        total = prev?.total ?: 0,
                        error = Errors.friendly(e),
                    ))
                }
            } finally {
                // Remove only our own entry (old job must never evict a new job).
                val self = coroutineContext[Job]
                if (self != null) jobs.remove(bookId, self) else jobs.remove(bookId)
            }
            }
            jobs[bookId] = job
        }
    }

    override fun cancel(bookId: String) {
        synchronized(startLock) { jobs.remove(bookId)?.cancel() }
        _states.update { cur ->
            val prev = cur[bookId] ?: return@update cur
            if (!prev.downloading) return@update cur
            cur + (bookId to prev.copy(downloading = false))
        }
    }

    /** Drop retained terminal state (call on cache delete so re-open can't replay stale progress). */
    override fun forget(bookId: String) {
        synchronized(startLock) { jobs.remove(bookId)?.cancel() }
        _states.update { cur -> cur - bookId }
    }

    /** Drop all retained state (used by clear-all). */
    override fun forgetAll() {
        synchronized(startLock) {
            jobs.values.forEach { runCatching { it.cancel() } }
            jobs.clear()
        }
        _states.update { emptyMap() }
    }
}
