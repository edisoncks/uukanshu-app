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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import cc.uukanshu.data.parse.Parser
import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.repo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class DetailViewModel(
    private val repo: cc.uukanshu.data.repo.BookRepo,
    private val prefs: Prefs,
    private val t2s: T2S,
    private val bookId: String,
) : ViewModel() {
    data class Ui(
        // No default on purpose: every construction must state loading
        // explicitly, so success paths can never leave the spinner stuck.
        val loading: Boolean,
        val meta: Parser.BookMeta? = null,
        val chapters: List<Parser.ChapterRef> = emptyList(),
        val error: String? = null,
        val downloading: Boolean = false,
        val done: Int = 0,
        val downloadError: String? = null,
        val simplified: Boolean = false,
    )

    private val _ui = MutableStateFlow(Ui(loading = true))
    val ui: StateFlow<Ui> = _ui

    init {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(simplified = prefs.simplified.first())
            refresh()
        }
    }

    fun refresh() {
        downloadJob?.cancel()
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            try {
                val d = repo.detail(bookId)
                _ui.value = _ui.value.copy(
                    loading = false, meta = d.meta, chapters = d.chapters,
                )
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(
                    loading = false,
                    error = "${e.javaClass.simpleName}: ${e.message}",
                )
            }
        }
    }

    private var downloadJob: kotlinx.coroutines.Job? = null

    /** Manual full-novel download: sequential 1-at-a-time, cancelable. */
    fun downloadAll() {
        if (_ui.value.downloading) return
        downloadJob = viewModelScope.launch {
            _ui.value = _ui.value.copy(downloading = true, done = 0, downloadError = null)
            try {
                repo.downloadAll(bookId) { done, _ ->
                    _ui.value = _ui.value.copy(done = done)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _ui.value = _ui.value.copy(downloadError = "${e.javaClass.simpleName}: ${e.message}")
            } finally {
                _ui.value = _ui.value.copy(downloading = false)
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _ui.value = _ui.value.copy(downloading = false)
    }

    fun displayTitle(raw: String): String =
        if (_ui.value.simplified) t2s.convert(raw) else raw
}

@Composable
fun DetailScreen(bookId: String, onChapter: (bookId: String, position: Int) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as cc.uukanshu.App
    val vm: DetailViewModel = viewModel(
        key = bookId,
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DetailViewModel(ctx.repo(), Prefs(app), T2S(app), bookId) as T
        },
    )
    val ui by vm.ui.collectAsState()

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
                Text(vm.displayTitle(m.title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    "作者：${vm.displayTitle(m.author)}  ${vm.displayTitle(m.status)}  ${m.words}".trim(),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (m.category.isNotEmpty()) Text(vm.displayTitle(m.category), style = MaterialTheme.typography.bodySmall)
                if (m.intro.isNotEmpty()) Text(
                    vm.displayTitle(m.intro),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (ui.downloading) {
                    LinearProgressIndicator(
                        progress = { ui.done.toFloat() / ui.chapters.size.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Text("下載中 ${ui.done}/${ui.chapters.size}", style = MaterialTheme.typography.bodySmall)
                    Button({ vm.cancelDownload() }, Modifier.padding(top = 4.dp)) { Text("取消") }
                } else {
                    Button({ vm.downloadAll() }, Modifier.padding(top = 8.dp)) {
                        Text(if (ui.done > 0 && ui.done >= ui.chapters.size) "重新下載整本" else "下載整本")
                    }
                }
                ui.downloadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
                    "${c.position}. ${vm.displayTitle(c.title)}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().clickable { onChapter(bookId, c.position) }.padding(vertical = 8.dp),
                )
                HorizontalDivider()
            }
        }
    }
}
