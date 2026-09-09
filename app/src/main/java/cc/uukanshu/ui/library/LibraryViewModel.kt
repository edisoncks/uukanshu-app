package cc.uukanshu.ui.library

import cc.uukanshu.data.repo.BookRepo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.uukanshu.core.Display
import cc.uukanshu.data.convert.T2S
import cc.uukanshu.data.download.BookDownloadManager
import cc.uukanshu.di.PrefsApi
import cc.uukanshu.di.RepoApi
import cc.uukanshu.core.Errors
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "LibraryVM"

class LibraryViewModel(
    private val repo: RepoApi,
    private val prefs: PrefsApi,
    private val t2s: T2S,
    private val downloads: BookDownloadManager,
) : ViewModel() {
    /**
     * Load state, split from live overlays. The shelf is loading, failed,
     * or showing rows — never loading+failed, never a stuck spinner (the
     * sealed initial is Loading by construction). Download progress and
     * fresh-download titles compose orthogonally on [Ui] and keep updating
     * under any load state.
     */
    sealed interface Load {
        data object Loading : Load
        data class Failed(val message: String) : Load
        data class Shelf(
            val books: List<BookRepo.CachedBook>,
            /** Refresh failure with a stale list on screen (footer retry). */
            val error: String? = null,
        ) : Load
    }

    data class Ui(
        val load: Load = Load.Loading,
        val simplified: Boolean = false,
        val downloading: Map<String, BookDownloadManager.State> = emptyMap(),
        // Titles for fresh downloads not yet qualified for library().
        // Domain type (never Room entities — see BookRepo.BookInfo).
        val pendingTitles: Map<String, BookRepo.BookInfo> = emptyMap(),
        // 追更 overlay (never a new Load variant): manual/background checks
        // write badges to Room; this flag is only the thin-bar spinner.
        val checking: Boolean = false,
        val lastCheck: Long = 0L,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui
    // Tap debouncer for 追更 check (not serialization; Room serializes via dbWrite).
    private val checkingNow = AtomicBoolean(false)

    init {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(simplified = prefs.simplified.first())
        }
        viewModelScope.launch {
            try {
                prefs.lastBookCheck.collect { t -> _ui.update { it.copy(lastCheck = t) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "lastBookCheck collect failed", e)
            }
        }
        // Reactive shelf: DB bumps (read/download/delete/clear) re-render
        // rows without manual refresh. Stale-while-revalidate: keep rows on
        // flow success, spinner only for the initial empty load.
        viewModelScope.launch {
            try {
                repo.libraryFlow().collect { books ->
                    _ui.update { cur ->
                        when (val l = cur.load) {
                            is Load.Shelf -> cur.copy(load = l.copy(books = books, error = null))
                            else -> cur.copy(load = Load.Shelf(books))
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _ui.update { cur ->
                    when (val l = cur.load) {
                        is Load.Shelf -> cur.copy(load = l.copy(error = Errors.friendly(e)))
                        else -> cur.copy(load = Load.Failed(Errors.friendly(e)))
                    }
                }
            }
        }
        // Live download progress: update rows directly from done/total
        // (no DB hit per chapter). Shelf stats come from `libraryFlow`
        // (chapters/stats flows emit on every write), so no manual
        // `refresh()` here — the old start/finish refresh duplicated the
        // flow with an extra one-shot `library()` query per event.
        viewModelScope.launch {
            downloads.states.collect { states ->
                val prevActive = _ui.value.downloading.filterValues { it.downloading }.keys
                val nextActive = states.filterValues { it.downloading }.keys
                val newActive = nextActive - prevActive
                _ui.update { it.copy(downloading = states) }
                if (newActive.isNotEmpty()) {
                    viewModelScope.launch {
                        val titles = newActive.mapNotNull { id ->
                            // Must not swallow cancellation: a cleared VM would
                            // otherwise keep this child alive past teardown.
                            Errors.suppressExceptCancel { repo.bookEntry(id)?.let { id to it } }
                        }.toMap()
                        if (titles.isNotEmpty()) {
                            _ui.update { cur ->
                                cur.copy(pendingTitles = cur.pendingTitles + titles)
                            }
                        }
                    }
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            // Clear a stale footer error at refresh start; the shelf stays
            // visible (stale-while-revalidate), a fresh load shows Loading.
            _ui.update { cur ->
                when (val l = cur.load) {
                    is Load.Shelf -> cur.copy(load = l.copy(error = null))
                    else -> cur.copy(load = Load.Loading)
                }
            }
            try {
                val books = repo.library()
                _ui.update { it.copy(load = Load.Shelf(books)) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _ui.update { cur ->
                    // DB failure is a failure, not an empty shelf: footer
                    // when rows are on screen, full-screen when empty.
                    when (val l = cur.load) {
                        is Load.Shelf -> cur.copy(load = l.copy(error = Errors.friendly(e)))
                        else -> cur.copy(load = Load.Failed(Errors.friendly(e)))
                    }
                }
            }
        }
    }

    fun cancelDownload(id: String) {
        downloads.cancel(id)
    }

    /**
     * Manual 追更: oldest-first bounded visible run (see BookRepo, 20/run).
     * Tap debouncer so rapid taps run once; stale-while-revalidate keeps rows,
     * thin bar shows progress, footer shows retry.
     * [auto] suppresses footer noise for silent foreground runs.
     * Single write path via UpdateChecker (owns lastBookCheck stamp on success only).
     */
    fun checkUpdates(auto: Boolean = false) {
        if (!checkingNow.compareAndSet(false, true)) return
        _ui.update { it.copy(checking = true) }
        viewModelScope.launch {
            try {
                val r = cc.uukanshu.data.updatecheck.UpdateChecker.checkAll(repo, prefs)
                if (r.failed) {
                    // Whole-run failure: footer shows the real cause directly.
                    // No fake throw for control flow (see review #3).
                    if (!auto) {
                        val cause = r.cause ?: java.io.IOException("check failed")
                        _ui.update { cur ->
                            when (val l = cur.load) {
                                is Load.Shelf -> cur.copy(load = l.copy(error = Errors.friendly(cause)))
                                else -> cur.copy(load = Load.Failed(Errors.friendly(cause)))
                            }
                        }
                    }
                } else {
                    // Badges arrive via libraryFlow (Room); nothing to copy here.
                    _ui.update { cur ->
                        when (val l = cur.load) {
                            is Load.Shelf -> cur.copy(load = l.copy(error = null))
                            else -> cur
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!auto) {
                    _ui.update { cur ->
                        when (val l = cur.load) {
                            is Load.Shelf -> cur.copy(load = l.copy(error = Errors.friendly(e)))
                            else -> cur.copy(load = Load.Failed(Errors.friendly(e)))
                        }
                    }
                }
            } finally {
                checkingNow.set(false)
                _ui.update { it.copy(checking = false) }
            }
        }
    }

    /** Silent foreground check on library open (6h throttle, failures ignored). */
    fun autoCheckUpdates() {
        viewModelScope.launch {
            val last = try {
                prefs.lastBookCheck.first()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return@launch
            }
            if (!cc.uukanshu.data.updatecheck.UpdateChecker.shouldForegroundCheck(last)) return@launch
            checkUpdates(auto = true)
        }
    }

    /** Restart a failed download from the shelf (idempotent start). */
    fun retryDownload(id: String) {
        downloads.start(id)
    }

    fun delete(id: String) {
        viewModelScope.launch {
            repo.deleteBook(id)
            // Evict retained manager state so a re-opened detail can't
            // replay stale done/total for zero cached bytes.
            downloads.forget(id)
            _ui.update { cur -> cur.copy(pendingTitles = cur.pendingTitles - id) }
            refresh()
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            repo.clearAll()
            downloads.forgetAll()
            _ui.update { cur -> cur.copy(pendingTitles = emptyMap()) }
            refresh()
        }
    }

    fun display(raw: String): String =
        Display.text(t2s, raw, _ui.value.simplified)
}

internal fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    // Fixed locale: some locales render %.1f with a decimal comma.
    b < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", b / 1024.0)
    else -> String.format(java.util.Locale.US, "%.1f MB", b / 1024.0 / 1024.0)
}
