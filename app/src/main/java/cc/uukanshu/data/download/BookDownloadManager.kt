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
     * Dedup registry: at most one live job per book. Check-then-act goes
     * through [ConcurrentHashMap] atomics ([putIfAbsent]/[replace]/remove-by-value)
     * so concurrent `start(id)` runs the download exactly once with no separate
     * lock. The only remaining lock is [slot] (the suspend FIFO queue — a
     * different job from dedup). [_states] is publish-only, never a lock.
     */
    private val jobs = ConcurrentHashMap<String, Job>()

    /** Bulk slot: whole-book downloads queue here, one at a time. */
    private val slot = Mutex()

    fun observe(bookId: String): Flow<State?> =
        _states.map { it[bookId] }.distinctUntilChanged()

    fun isDownloading(bookId: String): Boolean =
        jobs[bookId]?.isActive == true

    /** Idempotent start (second tap no-op). */
    fun start(bookId: String) {
        if (jobs[bookId]?.isActive == true) return
        // Seed from retained progress: a failed done/total stays visible
        // until fresh callbacks arrive instead of flashing 0/0 while the
        // job queues behind the slot or fetches its TOC. Idempotent: a racy
        // second seeder must not regress live progress published in between.
        _states.update { cur ->
            val prev = cur[bookId]
            if (prev?.downloading == true) cur
            else cur + (bookId to State(downloading = true, done = prev?.done ?: 0, total = prev?.total ?: 0, error = null))
        }
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
        // Atomic install: the loser cancels before doing real work (its body
        // blocks on [slot] first, and terminal publishes are identity-guarded).
        val prev = jobs.putIfAbsent(bookId, job)
        if (prev == null) return
        if (prev.isActive) {
            job.cancel()
            return
        }
        // Stale completed entry whose `finally` hasn't removed it yet: take over.
        if (jobs.replace(bookId, prev, job)) return
        // Another newcomer won the same window; back off (next tap retries).
        job.cancel()
    }

    fun cancel(bookId: String) {
        jobs.remove(bookId)?.cancel()
        _states.update { cur ->
            val prev = cur[bookId] ?: return@update cur
            if (!prev.downloading) return@update cur
            cur + (bookId to prev.copy(downloading = false))
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
        // (`jobs[bookId] !== self` guards) and which keeps crawling into the
        // cache the wipe just deleted. Sweeping by value, a racing start()
        // is either removed + cancelled by a later pass, or lands after the
        // loop exits (tracked, publishes flow — coherent). remove-by-value
        // never evicts a newer job that replaced an old entry.
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
