package cc.uukanshu.ui.library

import cc.uukanshu.data.repo.BookRepo
import cc.uukanshu.data.download.BookDownloadManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.uukanshu.core.Display
import cc.uukanshu.di.ConvertApi
import cc.uukanshu.di.DownloadsApi
import cc.uukanshu.di.PrefsApi
import cc.uukanshu.di.RepoApi
import cc.uukanshu.core.Errors
import cc.uukanshu.ui.vmFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val repo: RepoApi,
    private val prefs: PrefsApi,
    private val t2s: ConvertApi,
    private val downloads: DownloadsApi,
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
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    init {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(simplified = prefs.simplified.first())
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
