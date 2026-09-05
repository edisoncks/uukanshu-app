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
import cc.uukanshu.data.repo.BookRepo
import cc.uukanshu.app
import cc.uukanshu.core.Errors
import cc.uukanshu.ui.vmFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SearchViewModel(
    private val repo: BookRepo,
    private val prefs: Prefs,
    private val t2s: T2S,
) : ViewModel() {
    /**
     * Sealed states: impossible combinations (loading + error, error +
     * books, spinner-stuck defaults) are unrepresentable. Every `when`
     * over Ui is compiler-checked as exhaustive — adding a state breaks
     * the build until the UI handles it.
     */
    sealed interface Ui {
        val simplified: Boolean
        val totalOrNull: Int?
        data class Idle(override val simplified: Boolean = false) : Ui {
            override val totalOrNull: Int? = null
        }
        data class Loading(override val simplified: Boolean, override val totalOrNull: Int? = null) : Ui
        data class Success(
            val books: List<Parser.BookItem>,
            val total: Int?,
            override val simplified: Boolean,
        ) : Ui {
            override val totalOrNull: Int? = total
        }
        data class Error(
            val message: String,
            override val totalOrNull: Int? = null,
            override val simplified: Boolean,
        ) : Ui
    }

    private fun Ui.withSimplified(v: Boolean): Ui = when (this) {
        is Ui.Idle -> copy(simplified = v)
        is Ui.Loading -> copy(simplified = v)
        is Ui.Success -> copy(simplified = v)
        is Ui.Error -> copy(simplified = v)
    }

    private val _ui = MutableStateFlow<Ui>(Ui.Idle())
    val ui: StateFlow<Ui> = _ui
    // Raw keystrokes; the pipeline below debounces + cancels superseded
    // searches, so no manual Job/activeQuery guard can be forgotten.
    private val queries = MutableStateFlow("")

    init {
        viewModelScope.launch {
            _ui.update { it.withSimplified(prefs.simplified.first()) }
        }
        // Settings owns the toggle now: follow it live so results
        // re-render when the user flips Simplified/Traditional there.
        viewModelScope.launch {
            prefs.simplified.collect { v -> _ui.update { it.withSimplified(v) } }
        }
        viewModelScope.launch {
            @OptIn(FlowPreview::class)
            queries.debounce(400).flatMapLatest { raw ->
                val q = raw.trim()
                if (q.isEmpty()) {
                    flowOf<Ui>(Ui.Idle(simplified = _ui.value.simplified))
                } else {
                    flow<Ui> {
                        val s = _ui.value
                        emit(Ui.Loading(simplified = s.simplified, totalOrNull = s.totalOrNull))
                        try {
                            val res = repo.search(q)
                            // No stale-check needed: flatMapLatest cancels the
                            // previous flow, so a superseded result is never
                            // collected (the old code needed activeQuery because
                            // cancellation only bites at suspend points and
                            // nothing suspended past this point).
                            emit(
                                Ui.Success(
                                    books = dedupBooks(res.books), total = res.total,
                                    simplified = _ui.value.simplified,
                                ),
                            )
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            emit(
                                Ui.Error(
                                    message = Errors.userMessage(e),
                                    totalOrNull = _ui.value.totalOrNull,
                                    simplified = _ui.value.simplified,
                                ),
                            )
                        }
                    }
                }
            }.collect { _ui.value = it }
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
        queries.value = q
        if (q.isBlank()) {
            // Clear instantly for snappy UX; the debounced pipeline re-emits
            // the same empty state idempotently.
            _ui.value = Ui.Idle(simplified = _ui.value.simplified)
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
        ui.totalOrNull?.let { total ->
            Text(
                "${vm.display("共")} $total ${vm.display("條結果")}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        when (val s = ui) {
            is SearchViewModel.Ui.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is SearchViewModel.Ui.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(s.message)
                    Button({ vm.query(text) }, Modifier.padding(top = 12.dp)) { Text(vm.display("重試")) }
                }
            }
            is SearchViewModel.Ui.Success -> if (s.books.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(vm.display("沒有結果"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else LazyColumn(Modifier.fillMaxSize()) {
                items(s.books, key = { it.id }) { b ->
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
            // Never searched / cleared: blank, matching the old empty-list branch.
            is SearchViewModel.Ui.Idle -> Box(Modifier.fillMaxSize()) {}
        }
    }
}
