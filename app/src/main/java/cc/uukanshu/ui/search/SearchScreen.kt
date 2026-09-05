package cc.uukanshu.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import cc.uukanshu.data.parse.Parser
import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.app
import cc.uukanshu.core.Errors
import cc.uukanshu.ui.vmFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SearchViewModel(
    private val repo: cc.uukanshu.data.repo.BookRepo,
    private val prefs: Prefs,
    private val t2s: T2S,
) : ViewModel() {
    data class Ui(
        val books: List<Parser.BookItem> = emptyList(),
        val total: Int? = null,
        val loading: Boolean = false,
        val error: String? = null,
        val searched: Boolean = false,
        val simplified: Boolean = false,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui
    private var job: Job? = null
    // Latest submitted query, written synchronously on Main: a response for
    // a superseded query (cleared mid-fetch) must not paint over fresh state.
    private var activeQuery: String = ""

    init {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(simplified = prefs.simplified.first())
        }
    }

    fun display(raw: String): String =
        if (_ui.value.simplified) t2s.convert(raw) else raw

    companion object {
        /** Drop duplicate cards by stable book id (same live-shift dup source as Home). */
        fun dedupBooks(books: List<Parser.BookItem>): List<Parser.BookItem> =
            books.distinctBy { it.id }
    }

    fun query(q: String) {
        job?.cancel()
        activeQuery = q.trim()
        if (q.isBlank()) {
            _ui.value = _ui.value.copy(books = emptyList(), total = null, loading = false, searched = false)
            return
        }
        job = viewModelScope.launch {
            delay(400) // debounce
            _ui.value = _ui.value.copy(loading = true, searched = true, error = null)
            try {
                val res = repo.search(q.trim())
                // Superseded (cleared/retyped) while fetching: drop instead
                // of painting stale results — cancellation only bites at
                // suspend points, and nothing suspends past this point.
                if (q.trim() != activeQuery) return@launch
                _ui.value = _ui.value.copy(books = dedupBooks(res.books), total = res.total, loading = false, searched = true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (q.trim() != activeQuery) return@launch
                _ui.value = _ui.value.copy(
                    loading = false,
                    error = Errors.message(e), searched = true,
                )
            }
        }
    }
}

@Composable
fun SearchScreen(onBook: (String) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.app()
    val vm: SearchViewModel = viewModel(factory = vmFactory {
        SearchViewModel(app.repo, app.prefs, app.t2s)
    })
    val ui by vm.ui.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; vm.query(it) },
            label = { Text(vm.display("書名搜索…")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (ui.total != null) {
            Text(
                "${vm.display("共")} ${ui.total} ${vm.display("條結果")}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        when {
            ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            ui.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(ui.error!!)
                    Button({ vm.query(text) }, Modifier.padding(top = 12.dp)) { Text(vm.display("重試")) }
                }
            }
            ui.searched && ui.books.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(vm.display("沒有結果"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(ui.books, key = { it.id }) { b ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onBook(b.id) }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(vm.display(b.title), style = MaterialTheme.typography.titleMedium)
                            if (b.author.isNotEmpty()) Text(
                                "${vm.display("作者")}：${vm.display(b.author)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (b.latestChapterTitle.isNotEmpty()) Text(
                                "${vm.display("更新到")}：${vm.display(b.latestChapterTitle)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}
