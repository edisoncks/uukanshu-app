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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.uukanshu.data.parse.Parser
import cc.uukanshu.repo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SearchViewModel(private val repo: cc.uukanshu.data.repo.BookRepo) : ViewModel() {
    data class Ui(
        val books: List<Parser.BookItem> = emptyList(),
        val total: Int? = null,
        val loading: Boolean = false,
        val error: String? = null,
        val searched: Boolean = false,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui
    private var job: Job? = null

    fun query(q: String) {
        job?.cancel()
        if (q.isBlank()) {
            _ui.value = Ui()
            return
        }
        job = viewModelScope.launch {
            delay(400) // debounce
            _ui.value = Ui(loading = true, searched = true)
            try {
                val res = repo.search(q.trim())
                _ui.value = Ui(books = res.books, total = res.total, searched = true)
            } catch (e: Exception) {
                _ui.value = Ui(error = "${e.javaClass.simpleName}: ${e.message}", searched = true)
            }
        }
    }
}

@Composable
fun SearchScreen(onBook: (String) -> Unit) {
    val ctx = LocalContext.current
    val vm: SearchViewModel = viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SearchViewModel(ctx.repo()) as T
    })
    val ui by vm.ui.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; vm.query(it) },
            label = { Text("書名搜索…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (ui.total != null) {
            Text("共 ${ui.total} 條結果", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
        when {
            ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            ui.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(ui.error!!)
                    Button({ vm.query(text) }, Modifier.padding(top = 12.dp)) { Text("重試") }
                }
            }
            ui.searched && ui.books.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("沒有結果", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(ui.books, key = { it.id + it.title }) { b ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onBook(b.id) }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(b.title, style = MaterialTheme.typography.titleMedium)
                            if (b.author.isNotEmpty()) Text("作者：${b.author}", style = MaterialTheme.typography.bodySmall)
                            if (b.latestChapterTitle.isNotEmpty()) Text(
                                "更新到：${b.latestChapterTitle}",
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
