package cc.uukanshu.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.uukanshu.data.convert.T2S
import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.data.repo.BookRepo
import cc.uukanshu.repo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val repo: BookRepo,
    private val prefs: Prefs,
    private val t2s: T2S,
) : ViewModel() {
    data class Ui(
        // No default on purpose: every construction must state loading
        // explicitly, so success paths can never leave the spinner stuck.
        val loading: Boolean,
        val books: List<BookRepo.CachedBook> = emptyList(),
        val simplified: Boolean = false,
    )

    private val _ui = MutableStateFlow(Ui(loading = true))
    val ui: StateFlow<Ui> = _ui

    init {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(simplified = prefs.simplified.first())
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true)
            _ui.value = try {
                _ui.value.copy(loading = false, books = repo.library())
            } catch (e: Exception) {
                _ui.value.copy(loading = false, books = emptyList())
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            repo.deleteBook(id)
            refresh()
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            repo.clearAll()
            refresh()
        }
    }

    fun display(raw: String): String =
        if (_ui.value.simplified) t2s.convert(raw) else raw
}

private fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> String.format("%.1f KB", b / 1024.0)
    else -> String.format("%.1f MB", b / 1024.0 / 1024.0)
}

@Composable
fun LibraryScreen(onBook: (String) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as cc.uukanshu.App
    val vm: LibraryViewModel = viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LibraryViewModel(ctx.repo(), Prefs(app), T2S(app)) as T
    })
    val ui by vm.ui.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(vm.display("已緩存"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (ui.books.isNotEmpty()) {
                TextButton(onClick = { vm.clearAll() }) { Text(vm.display("清空全部")) }
            }
        }
        val total = ui.books.sumOf { it.bytes }
        Text(
            "${ui.books.size} ${vm.display("本")} · ${formatBytes(total)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ui.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (ui.books.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(vm.display("尚無緩存 — 在書籍詳情頁下載整本"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(ui.books, key = { it.id }) { b ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onBook(b.id) }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(vm.display(b.title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${vm.display(b.author)} · ${b.cached}/${b.total} ${vm.display("章")} · ${formatBytes(b.bytes)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button({ vm.delete(b.id) }, Modifier.padding(top = 8.dp)) {
                                Text(vm.display("刪除緩存"))
                            }
                        }
                    }
                }
            }
        }
    }
}
