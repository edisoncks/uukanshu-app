package cc.uukanshu.data.download

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
import java.util.concurrent.ConcurrentHashMap

/**
 * App-scoped full-book downloads.
 *
 * Previously the download lived in `DetailViewModel.viewModelScope`, so
 * popping `detail/{bookId}` cancelled it. Jobs here run in an
 * application-scope so navigating away (home/library/search, another book)
 * never aborts them; re-opening the detail re-attaches via [observe].
 *
 * Network stays single-flight: [BookRepo.downloadAll] funnels each chapter
 * fetch through `UukanshuGate`, so a background download and foreground
 * browsing serialize per HTTP request instead of overlapping.
 * `crawlDelay` stays outside the gate, so interactive taps interleave
 * per-chapter rather than blocking behind a whole book.
 */
class BookDownloadManager(
    private val repo: BookRepo,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    data class State(
        val downloading: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val error: String? = null,
    )

    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states

    private val jobs = ConcurrentHashMap<String, Job>()

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
            try {
                repo.downloadAll(bookId) { done, total ->
                    _states.update { cur ->
                        cur + (bookId to State(downloading = true, done = done, total = total, error = null))
                    }
                }
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
                // cancel() already published downloading=false; keep done/total.
                throw e
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _states.update { cur ->
                    val prev = cur[bookId]
                    cur + (bookId to State(
                        downloading = false,
                        done = prev?.done ?: 0,
                        total = prev?.total ?: 0,
                        error = "${e.javaClass.simpleName}: ${e.message}",
                    ))
                }
            } finally {
                jobs.remove(bookId)
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
}
