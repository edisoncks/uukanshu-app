package cc.uukanshu.data.download

import cc.uukanshu.core.Errors
import cc.uukanshu.data.repo.BookRepo
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
     * Dedup registry: at most one live job per book. A job is registered
     * ([putIfAbsent]) before it can run (see [start]) and removed by value at
     * the end of its life, so presence is exactly "this book has a live job"
     * and only its owner ever publishes state. [_states] is publish-only,
     * never a lock.
     */
    private val jobs = ConcurrentHashMap<String, Job>()

    /** Bulk slot: whole-book downloads queue here, one at a time. */
    private val slot = Mutex()

    fun observe(bookId: String): Flow<State?> =
        _states.map { it[bookId] }.distinctUntilChanged()

    /** True while a registered job exists (queued, running, or finishing). */
    fun isDownloading(bookId: String): Boolean = jobs.containsKey(bookId)

    /** Idempotent start (second tap no-op). */
    fun start(bookId: String) {
        if (jobs.containsKey(bookId)) return
        // LAZY: the body cannot run before `putIfAbsent` registers it below, so
        // `jobs[bookId] === self` holds from its first instruction and every
        // publish can be identity-checked (see publish). A body that ran before
        // registration would publish into the void and wedge the state on
        // "downloading" with no live job left to clear it.
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
                // Remove-by-value: a stale job must never evict its replacement.
                jobs.remove(bookId, self)
            }
        }
        if (jobs.putIfAbsent(bookId, job) != null) {
            // Lost the registration race: cancel silently. Only the registered
            // owner ever writes state.
            job.cancel()
            return
        }
        // Seed from retained progress: a failed done/total stays visible
        // until fresh callbacks arrive instead of flashing 0/0 while the
        // job queues behind the slot or fetches its TOC. Through publish like
        // every job-side write: if cancel/forget already unregistered us, the
        // seed is dropped AND the job is cancelled — never state with no job.
        publish(job, bookId) { prev ->
            State(downloading = true, done = prev?.done ?: 0, total = prev?.total ?: 0, error = null)
        }
        job.start()
    }

    /**
     * Single publish path: applies [next] only while [self] is still the
     * registered owner of [bookId]. The identity check runs inside the state
     * CAS, so a job that `forget`/`cancel` unregistered can never resurrect
     * state — forget wins over any late publish, cancellable or not — and a
     * dead job's callbacks can't clobber a replacement's live progress.
     */
    private fun publish(self: Job, bookId: String, next: (State?) -> State) {
        _states.update { cur ->
            if (jobs[bookId] === self) cur + (bookId to next(cur[bookId])) else cur
        }
    }

    fun cancel(bookId: String) {
        jobs.remove(bookId)?.cancel()
        _states.update { cur ->
            val prev = cur[bookId] ?: return@update cur
            // A replacement job registered concurrently owns the state now.
            if (!prev.downloading || jobs.containsKey(bookId)) cur
            else cur + (bookId to prev.copy(downloading = false))
        }
    }

    /** Drop retained terminal state (call on cache delete so re-open can't replay stale progress). */
    fun forget(bookId: String) {
        jobs.remove(bookId)?.cancel()
        _states.update { cur -> cur - bookId }
    }

    /** Drop all retained state (used by clear-all). */
    fun forgetAll() {
        // Remove-by-value sweeps until the map is empty — never a blanket
        // clear(). A clear() would untrack a start() that landed mid-wipe
        // WITHOUT cancelling it: a live job whose publishes are dropped
        // (`publish` identity check) and which keeps crawling into the cache
        // the wipe just deleted. Sweeping by value, a racing start() is either
        // removed + cancelled by a later pass, or lands after the loop exits
        // (tracked, publishes flow — coherent). remove-by-value never evicts a
        // newer job for the same book.
        while (true) {
            // Weakly-consistent snapshot is safe on CHM; each entry is
            // cancelled exactly once via remove-by-value.
            val snapshot = jobs.entries.toList()
            if (snapshot.isEmpty()) break
            for ((id, job) in snapshot) {
                if (jobs.remove(id, job)) runCatching { job.cancel() }
            }
        }
        _states.update { emptyMap() }
    }

    /** Test-only seed for retained progress (avoids hand-mirrored fakes). */
    fun seedForTest(bookId: String, state: State) {
        _states.update { it + (bookId to state) }
    }
}
