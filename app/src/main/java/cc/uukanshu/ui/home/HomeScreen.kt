package cc.uukanshu.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.uukanshu.CATEGORIES
import cc.uukanshu.data.convert.T2S
import cc.uukanshu.data.parse.Parser
import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.repo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class HomeViewModel(
    private val repo: cc.uukanshu.data.repo.BookRepo,
    private val prefs: Prefs,
    private val t2s: T2S,
) : ViewModel() {
    data class Ui(
        val tab: Int = 0, // 0 recent, 1 category
        val categoryId: Int = 1,
        val page: Int = 1,
        val books: List<Parser.BookItem> = emptyList(),
        val loading: Boolean = false,
        val loadingMore: Boolean = false,
        val error: String? = null,
        val endOfList: Boolean = false,
        val simplified: Boolean = false,
        val theme: String = Prefs.SYSTEM,
    )

    private val _ui = MutableStateFlow(Ui(loading = true))
    val ui: StateFlow<Ui> = _ui

    init {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(
                simplified = prefs.simplified.first(),
                theme = prefs.theme.first(),
            )
            refresh()
        }
    }

    fun toggleSimplified() {
        viewModelScope.launch {
            prefs.setSimplified(!_ui.value.simplified)
            _ui.value = _ui.value.copy(simplified = !_ui.value.simplified)
        }
    }

    fun display(raw: String): String =
        if (_ui.value.simplified) t2s.convert(raw) else raw

    /** Cycle system → light → dark theme. Applied app-wide via prefs. */
    fun cycleTheme() {
        viewModelScope.launch {
            val next = when (_ui.value.theme) {
                Prefs.SYSTEM -> Prefs.LIGHT
                Prefs.LIGHT -> Prefs.DARK
                else -> Prefs.SYSTEM
            }
            prefs.setTheme(next)
            _ui.value = _ui.value.copy(theme = next)
        }
    }

    fun themeLabel(): String = display(when (_ui.value.theme) {
        Prefs.LIGHT -> "淺色"
        Prefs.DARK -> "深色"
        else -> "自動"
    })

    fun selectTab(tab: Int) {
        if (_ui.value.tab == tab) return
        _ui.value = Ui(tab = tab, categoryId = _ui.value.categoryId, loading = true,
            simplified = _ui.value.simplified)
        refresh()
    }

    fun selectCategory(id: Int) {
        _ui.value = _ui.value.copy(tab = 1, categoryId = id, page = 1, books = emptyList(),
            loading = true, endOfList = false, error = null)
        refresh()
    }

    fun refresh() {
        val s = _ui.value
        viewModelScope.launch {
            _ui.value = s.copy(loading = true, error = null)
            try {
                val books = if (s.tab == 0) repo.recent() else repo.category(s.categoryId, 1)
                _ui.value = s.copy(
                    books = books, loading = false,
                    page = 1, endOfList = s.tab == 0 || books.isEmpty(),
                )
            } catch (e: Exception) {
                _ui.value = s.copy(loading = false, error = "${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    fun loadMore() {
        val s = _ui.value
        if (s.tab == 0 || s.loading || s.loadingMore || s.endOfList) return
        viewModelScope.launch {
            _ui.value = s.copy(loadingMore = true)
            try {
                val next = repo.category(s.categoryId, s.page + 1)
                _ui.value = s.copy(
                    books = s.books + next, page = s.page + 1,
                    loadingMore = false, endOfList = next.isEmpty(),
                )
            } catch (e: Exception) {
                _ui.value = s.copy(loadingMore = false, error = "${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
}

@Composable
fun HomeScreen(onBook: (String) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as cc.uukanshu.App
    val vm: HomeViewModel = viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(ctx.repo(), Prefs(app), T2S(app)) as T
    })
    val ui by vm.ui.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "uukanshu",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
            TextButton(onClick = { vm.toggleSimplified() }) {
                Text(if (ui.simplified) "简" else "繁")
            }
            TextButton(onClick = { vm.cycleTheme() }) {
                Text(vm.themeLabel())
            }
        }
        TabRow(selectedTabIndex = ui.tab) {
            Tab(selected = ui.tab == 0, onClick = { vm.selectTab(0) }, text = { Text(vm.display("最近更新")) })
            Tab(selected = ui.tab == 1, onClick = { vm.selectTab(1) }, text = { Text(vm.display("分類")) })
        }
        if (ui.tab == 1) {
            LazyRow(
                Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(CATEGORIES) { c ->
                    AssistChip(
                        onClick = { vm.selectCategory(c.id) },
                        label = { Text(vm.display(c.name)) },
                    )
                }
            }
        }
        when {
            ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            ui.error != null && ui.books.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(ui.error!!, style = MaterialTheme.typography.bodyMedium)
                    Button({ vm.refresh()}, Modifier.padding(top = 12.dp)) { Text(vm.display("重試 / Retry")) }
                }
            }
            else -> {
                val listState = rememberLazyListState()
                LaunchedEffect(listState, ui.tab, ui.categoryId) {
                    snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                        .collect { idx ->
                            if (idx != null && idx >= ui.books.size - 3) vm.loadMore()
                        }
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    items(ui.books, key = { it.id + it.title }) { b ->
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
                                if (b.intro.isNotEmpty()) Text(
                                    vm.display(b.intro.take(120)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (ui.loadingMore) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }
    }
}
