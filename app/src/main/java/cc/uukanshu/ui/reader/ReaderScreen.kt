package cc.uukanshu.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

class ReaderViewModel(
    private val repo: cc.uukanshu.data.repo.BookRepo,
    private val t2s: T2S,
    private val prefs: Prefs,
    private val bookId: String,
    startPosition: Int,
) : ViewModel() {
    data class Ui(
        val position: Int = 1,
        val total: Int = 0,
        val book: String = "",
        val title: String = "",
        val text: String = "",
        val loading: Boolean = true,
        val error: String? = null,
        val simplified: Boolean = false,
        val fontScale: Float = 1f,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui
    private var chapters: List<Parser.ChapterRef> = emptyList()

    init {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(
                simplified = prefs.simplified.first(),
                fontScale = prefs.fontScale.first(),
            )
            load(startPosition)
        }
    }

    private fun render(raw: Parser.ChapterContent, simplified: Boolean): Triple<String, String, String> =
        if (simplified) Triple(t2s.convert(raw.book), t2s.convert(raw.title), t2s.convert(raw.text))
        else Triple(raw.book, raw.title, raw.text)

    fun load(position: Int) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null, position = position)
            try {
                if (chapters.isEmpty()) {
                    val d = repo.detail(bookId)
                    chapters = d.chapters
                }
                val total = chapters.size
                if (position < 1 || position > total) {
                    _ui.value = _ui.value.copy(loading = false, total = total, error = "out of range")
                    return@launch
                }
                val ref = chapters[position - 1]
                // Room cache first, else network (then save raw).
                val cached = repo.cachedChapterContent(bookId, position)
                val raw = if (cached != null) {
                    // Reconstruct nav from TOC positions (shape-validated chapter URLs only).
                    Parser.ChapterContent(
                        book = _ui.value.book.ifEmpty { ref.title },
                        title = ref.title, text = cached,
                        prevUrl = chapters.getOrNull(position - 2)?.url,
                        tocUrl = null,
                        nextUrl = chapters.getOrNull(position)?.url,
                    )
                } else {
                    repo.chapter(ref.url).also {
                        repo.saveChapterContent(bookId, position, it.text)
                    }
                }
                val (book, title, text) = render(raw, _ui.value.simplified)
                _ui.value = _ui.value.copy(
                    book = book, title = title, text = text,
                    total = total, loading = false,
                )
                prefetchNext5(position)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(
                    loading = false,
                    error = "${e.javaClass.simpleName}: ${e.message}",
                )
            }
        }
    }

    /** Auto-cache the next 5 chapters, sequential, silent-fail. */
    private fun prefetchNext5(from: Int) {
        viewModelScope.launch {
            for (pos in (from + 1)..minOf(from + 5, chapters.size)) {
                runCatching {
                    if (repo.cachedChapterContent(bookId, pos) == null) {
                        val ref = chapters[pos - 1]
                        repo.saveChapterContent(bookId, pos, repo.chapter(ref.url).text)
                    }
                }
            }
        }
    }

    fun toggleSimplified() {
        viewModelScope.launch {
            val next = !_ui.value.simplified
            prefs.setSimplified(next)
            _ui.value = _ui.value.copy(simplified = next)
            // Re-render current chapter without refetch.
            load(_ui.value.position)
        }
    }

    fun font(delta: Float) {
        viewModelScope.launch {
            val next = (_ui.value.fontScale + delta).coerceIn(0.8f, 1.6f)
            prefs.setFontScale(next)
            _ui.value = _ui.value.copy(fontScale = next)
        }
    }
}

@Composable
fun ReaderScreen(bookId: String, position: Int) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as cc.uukanshu.App
    val vm: ReaderViewModel = viewModel(
        key = "$bookId-$position",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ReaderViewModel(app.repo, T2S(app), Prefs(app), bookId, position) as T
        },
    )
    val ui by vm.ui.collectAsState()
    val snacks = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp)) {
            Button(onClick = { vm.load(ui.position - 1) }, enabled = !ui.loading && ui.position > 1) {
                Text("上一章")
            }
            TextButton(onClick = { vm.toggleSimplified() }) {
                Text(if (ui.simplified) "简" else "繁")
            }
            TextButton(onClick = { vm.font(-0.1f) }) { Text("A-") }
            TextButton(onClick = { vm.font(0.1f) }) { Text("A+") }
            Button(
                onClick = {
                    if (ui.position >= ui.total && ui.total > 0) {
                        scope.launch { snacks.showSnackbar("已是最新一章 / end of book") }
                    } else vm.load(ui.position + 1)
                },
                enabled = !ui.loading,
            ) {
                Text("下一章")
            }
        }
        when {
            ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            ui.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(ui.error!!)
                    Button({ vm.load(ui.position) }, Modifier.padding(top = 12.dp)) { Text("重試") }
                }
            }
            else -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            ) {
                if (ui.book.isNotEmpty()) Text(ui.book, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${ui.position}/${ui.total} ${ui.title}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Text(ui.text, fontSize = (17 * ui.fontScale).sp, lineHeight = (28 * ui.fontScale).sp)
                Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Button(
                        onClick = {
                            if (ui.position <= 1) scope.launch { snacks.showSnackbar("已是第一章 / start of book") }
                            else vm.load(ui.position - 1)
                        },
                        modifier = Modifier.weight(1f).padding(end = 4.dp),
                    ) { Text("上一章") }
                    Button(
                        onClick = {
                            if (ui.position >= ui.total) scope.launch { snacks.showSnackbar("已是最新一章 / end of book") }
                            else vm.load(ui.position + 1)
                        },
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    ) { Text("下一章") }
                }
            }
        }
        SnackbarHost(snacks)
    }
}
