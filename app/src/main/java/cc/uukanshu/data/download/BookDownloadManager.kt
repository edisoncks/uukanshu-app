package cc.uukanshu.data.download

import cc.uukanshu.core.Errors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * App-scoped full-book downloads (survive leaving detail; re-attach via observe).
 * One book at a time (second queues); per-chapter gate keeps interactive taps interleaved.
 * See ARCHITECTURE.md.
 */
class BookDownloadManager(
    private val downloadFn: suspend (String, (Int, Int) -> Unit) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
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
    val states: StateFlow<Map<String, State>> = _states

    /**
     * Ownership boundary for [jobs] and [_states]. A job registry operation and
     * its state transition must linearize together: a concurrent map plus a
     * separate StateFlow CAS cannot prevent a stale job from publishing after
     * a replacement registers. No suspend function runs while this monitor is
     * held; [slot] remains the separate bulk-download queue.
     */
    private val ownershipLock = Any()
    private val jobs = HashMap<String, Job>()

    /** Bulk slot: whole-book downloads queue here, one at a time. */
    private val slot = Mutex()

    fun observe(bookId: String): Flow<State?> =
        _states.map { it[bookId] }.distinctUntilChanged()

    /** True while a registered job exists (queued, running, or finishing). */
    fun isDownloading(bookId: String): Boolean = synchronized(ownershipLock) {
        jobs.containsKey(bookId)
    }

    /** Idempotent start (second tap no-op). */
    fun start(bookId: String) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]!!
            try {
                slot.withLock {
                    downloadFn(bookId) { done, total ->
                        publish(self, bookId) {
                            State(downloading = true, done = done, total = total, error = null)
                        }
                    }
                }
                publish(self, bookId) { prev ->
                    State(
                        downloading = false,
                        done = prev?.done ?: 0,
                        total = prev?.total ?: 0,
                        error = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                publish(self, bookId) { prev ->
                    State(
                        downloading = false,
                        done = prev?.done ?: 0,
                        total = prev?.total ?: 0,
                        error = Errors.friendly(e),
                    )
                }
            } finally {
                finish(bookId, self)
            }
        }

        val installed = synchronized(ownershipLock) {
            if (jobs.containsKey(bookId)) {
                false
            } else {
                jobs[bookId] = job
                val prev = _states.value[bookId]
                _states.value = _states.value + (
                    bookId to State(
                        downloading = true,
                        done = prev?.done ?: 0,
                        total = prev?.total ?: 0,
                        error = null,
                    )
                )
                true
            }
        }
        if (!installed) {
            // Lost the registration race: cancel silently. Only the registered
            // owner ever writes state.
            job.cancel()
            return
        }

        // A lazy job can be cancelled before its body starts, in which case
        // it has no finally block to clean the registry. The handler is
        // idempotent with the finally cleanup and covers that lifecycle gap.
        job.invokeOnCompletion { finish(bookId, job) }
        if (!job.start()) finish(bookId, job)
    }

    /**
     * Remove [self] only while it is still the registered owner. External
     * scope cancellation can bypass [cancel], so an owned downloading state
     * is cleared here; manager-driven cancel/forget already removed the job
     * and state under [ownershipLock].
     */
    private fun finish(bookId: String, self: Job) {
        synchronized(ownershipLock) {
            if (jobs[bookId] !== self) return
            jobs.remove(bookId)
            val prev = _states.value[bookId] ?: return
            if (prev.downloading) {
                _states.value = _states.value + (bookId to prev.copy(downloading = false))
            }
        }
    }

    /** Publish only while [self] owns [bookId], atomically with that ownership check. */
    private fun publish(self: Job, bookId: String, next: (State?) -> State) {
        synchronized(ownershipLock) {
            if (jobs[bookId] !== self) return
            val cur = _states.value
            _states.value = cur + (bookId to next(cur[bookId]))
        }
    }

    fun cancel(bookId: String) {
        val job = synchronized(ownershipLock) {
            val removed = jobs.remove(bookId)
            val prev = _states.value[bookId]
            if (prev?.downloading == true) {
                _states.value = _states.value + (bookId to prev.copy(downloading = false))
            }
            removed
        }
        job?.cancel()
    }

    /** Drop retained terminal state (call on cache delete so re-open can't replay stale progress). */
    fun forget(bookId: String) {
        val job = synchronized(ownershipLock) {
            val removed = jobs.remove(bookId)
            _states.value = _states.value - bookId
            removed
        }
        job?.cancel()
    }

    /** Drop all retained state (used by clear-all). */
    fun forgetAll() {
        // Linearize the complete wipe so a racing start() is either detached
        // and cancelled by this call or registers after the empty state is
        // published. Cancellation happens outside the monitor because Job
        // handlers can execute arbitrary coroutine cleanup.
        val toCancel = synchronized(ownershipLock) {
            val detached = jobs.values.toList()
            jobs.clear()
            _states.value = emptyMap()
            detached
        }
        toCancel.forEach { job -> runCatching { job.cancel() } }
    }

    /** Test-only seed for retained progress (avoids hand-mirrored fakes). */
    fun seedForTest(bookId: String, state: State) {
        synchronized(ownershipLock) {
            _states.value = _states.value + (bookId to state)
        }
    }
}
