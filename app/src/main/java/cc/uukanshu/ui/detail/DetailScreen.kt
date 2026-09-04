package cc.uukanshu.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import cc.uukanshu.data.parse.Parser
import cc.uukanshu.repo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DetailViewModel(
    private val repo: cc.uukanshu.data.repo.BookRepo,
    private val bookId: String,
) : ViewModel() {
    data class Ui(
        val meta: Parser.BookMeta? = null,
        val chapters: List<Parser.ChapterRef> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _ui.value = Ui(loading = true)
            try {
                val d = repo.detail(bookId)
                _ui.value = Ui(meta = d.meta, chapters = d.chapters)
            } catch (e: Exception) {
                _ui.value = Ui(loading = false, error = "${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
}

@Composable
fun DetailScreen(bookId: String, onChapter: (bookId: String, position: Int) -> Unit) {
    val ctx = LocalContext.current
    val vm: DetailViewModel = viewModel(
        key = bookId,
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DetailViewModel(ctx.repo(), bookId) as T
        },
    )
    val ui by vm.ui.collectAsState()
    LaunchedEffect(bookId) { vm.refresh() }

    when {
        ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        ui.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(ui.error!!)
                Button({ vm.refresh() }, Modifier.padding(top = 12.dp)) { Text("重試") }
            }
        }
        else -> LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
            item {
                val m = ui.meta!!
                Text(m.title, style = MaterialTheme.typography.headlineSmall)
                Text("作者：${m.author}  ${m.status}  ${m.words}".trim(), style = MaterialTheme.typography.bodySmall)
                if (m.category.isNotEmpty()) Text(m.category, style = MaterialTheme.typography.bodySmall)
                if (m.intro.isNotEmpty()) Text(m.intro, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                Text(
                    "共 ${ui.chapters.size} 章",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                HorizontalDivider()
            }
            items(ui.chapters, key = { it.position }) { c ->
                Text(
                    "${c.position}. ${c.title}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().clickable { onChapter(bookId, c.position) }.padding(vertical = 8.dp),
                )
                HorizontalDivider()
            }
        }
    }
}
