package cc.uukanshu.ui.library

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
import cc.uukanshu.data.convert.T2S
import cc.uukanshu.data.db.BookEntity
import cc.uukanshu.data.download.BookDownloadManager
import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.data.repo.BookRepo
import cc.uukanshu.app
import cc.uukanshu.core.Errors
import cc.uukanshu.ui.vmFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val repo: BookRepo,
    private val prefs: Prefs,
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
        val pendingTitles: Map<String, BookEntity> = emptyMap(),
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    init {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(simplified = prefs.simplified.first())
        }
        // Live download progress: update rows directly from done/total
        // (no DB hit per chapter); refresh library stats only when a job
        // appears or finishes so cached/total/bytes catch up.
        viewModelScope.launch {
            downloads.states.collect { states ->
                val prevActive = _ui.value.downloading.filterValues { it.downloading }.keys
                val nextActive = states.filterValues { it.downloading }.keys
                val newActive = nextActive - prevActive
                val justFinished = prevActive - nextActive
                _ui.update { it.copy(downloading = states) }
                if (newActive.isNotEmpty()) {
                    viewModelScope.launch {
                        val titles = newActive.mapNotNull { id ->
                            try {
                                repo.bookEntry(id)?.let { id to it }
                            } catch (_: Exception) {
                                null
                            }
                        }.toMap()
                        if (titles.isNotEmpty()) {
                            _ui.update { cur ->
                                cur.copy(pendingTitles = cur.pendingTitles + titles)
                            }
                        }
                    }
                    refresh()
                }
                if (justFinished.isNotEmpty()) {
                    refresh()
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
                        is Load.Shelf -> cur.copy(load = l.copy(error = Errors.message(e)))
                        else -> cur.copy(load = Load.Failed(Errors.message(e)))
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
        if (_ui.value.simplified) t2s.convert(raw) else raw
}

private fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    // Fixed locale: some locales render %.1f with a decimal comma.
    b < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", b / 1024.0)
    else -> String.format(java.util.Locale.US, "%.1f MB", b / 1024.0 / 1024.0)
}

@Composable
fun LibraryScreen(onBook: (String) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.app()
    val vm: LibraryViewModel = viewModel(factory = vmFactory {
        LibraryViewModel(app.repo, app.prefs, app.t2s, app.downloadManager)
    })
    val ui by vm.ui.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }
    // Saveable so detail->back restores index/offset via the library
    // back-stack entry; plain remember is discarded with the composition.
    val listState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState()
    }
    // Destructive, unrecoverable (includes metered downloads): confirm first.
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        // Shelf rows only: fresh-download progress rows below don't gate this.
        val shelfBooks = (ui.load as? LibraryViewModel.Load.Shelf)?.books.orEmpty()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(vm.display("已緩存"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (shelfBooks.isNotEmpty()) {
                TextButton(onClick = { confirmClear = true }) { Text(vm.display("清空全部")) }
            }
        }
        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text(vm.display("清空全部緩存？")) },
                text = { Text(vm.display("將刪除所有已下載的章節，且無法還原。")) },
                confirmButton = {
                    TextButton(onClick = { confirmClear = false; vm.clearAll() }) {
                        Text(vm.display("清空"))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClear = false }) { Text(vm.display("取消")) }
                },
            )
        }
        val total = shelfBooks.sumOf { it.bytes }
        Text(
            "${shelfBooks.size} ${vm.display("本")} · ${formatBytes(total)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Stale-while-revalidate: keep the old list visible during
        // return-refresh so the saveable scroll position isn't lost to a
        // full-screen spinner; spinner only for the initial empty load.
        // Fresh downloads (0 cached) still show as progress rows below.
        val load = ui.load
        val bookIds = shelfBooks.map { it.id }.toSet()
        val extraIds = ui.downloading.keys.filter { it !in bookIds && (ui.downloading[it]?.downloading == true || ui.downloading[it]?.error != null) }
        val hasContent = shelfBooks.isNotEmpty() || extraIds.isNotEmpty()
        // Footer error: stale-list refresh failure, or a DB failure kept
        // off the error screen by in-flight fresh-download rows.
        val footerError = when (load) {
            is LibraryViewModel.Load.Shelf -> load.error
            is LibraryViewModel.Load.Failed -> load.message
            else -> null
        }
        if (load is LibraryViewModel.Load.Loading && !hasContent) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (load is LibraryViewModel.Load.Failed && !hasContent) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        load.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button({ vm.refresh() }, Modifier.padding(top = 12.dp)) { Text(vm.display("重試")) }
                }
            }
        } else if (!hasContent) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(vm.display("尚無緩存 — 在書籍詳情頁下載整本"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                // Fresh downloads not yet qualified for library(): title from
                // the cached TOC skeleton, progress live from the manager.
                items(extraIds, key = { "dl-$it" }) { id ->
                    val st = ui.downloading[id]
                    val meta = ui.pendingTitles[id]
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onBook(id) }) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(vm.display(meta?.title ?: id), style = MaterialTheme.typography.titleMedium)
                            if (st?.downloading == true) {
                                LinearProgressIndicator(
                                    progress = { st.done.toFloat() / st.total.coerceAtLeast(1) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    vm.display("下載中") + " ${st.done}/${st.total}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button({ vm.cancelDownload(id) }) { Text(vm.display("取消")) }
                                    TextButton({ vm.delete(id) }) { Text(vm.display("刪除緩存")) }
                                }
                            }
                            st?.error?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                // Failed fresh download: retry here instead of
                                // forcing a trip to the detail page.
                                if (st.downloading != true) {
                                    Button({ vm.retryDownload(id) }) { Text(vm.display("重試")) }
                                }
                            }
                        }
                    }
                }
                items(shelfBooks, key = { it.id }) { b ->
                    val st = ui.downloading[b.id]
                    val isDownloading = st?.downloading == true
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onBook(b.id) }) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(vm.display(b.title), style = MaterialTheme.typography.titleMedium)
                            // While downloading, the DB snapshot (b.cached/b.total)
                            // is stale until the job finishes — show only the live
                            // done/total line below so progress never appears twice.
                            Text(
                                if (isDownloading) {
                                    "${vm.display(b.author)} · ${formatBytes(b.bytes)}"
                                } else {
                                    "${vm.display(b.author)} · ${b.cached}/${b.total} ${vm.display("章")} · ${formatBytes(b.bytes)}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (st?.downloading == true) {
                                LinearProgressIndicator(
                                    progress = { st.done.toFloat() / st.total.coerceAtLeast(1) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    vm.display("下載中") + " ${st.done}/${st.total}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            st?.error?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (st?.downloading == true) {
                                    Button({ vm.cancelDownload(b.id) }) { Text(vm.display("取消")) }
                                } else if (st?.error != null) {
                                    Button({ vm.retryDownload(b.id) }) { Text(vm.display("重試")) }
                                }
                                Button({ vm.delete(b.id) }) {
                                    Text(vm.display("刪除緩存"))
                                }
                            }
                        }
                    }
                }
                // Refresh failure with a stale list on screen: footer error +
                // retry instead of silently keeping the old rows.
                if (footerError != null) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                footerError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Button({ vm.refresh() }, Modifier.padding(top = 8.dp)) {
                                Text(vm.display("重試"))
                            }
                        }
                    }
                }
            }
        }
    }
}
