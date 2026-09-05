package cc.uukanshu.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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
        // Settings tab owns the toggles now: follow DataStore so the list
        // re-renders when the user flips Simplified/Theme there.
        viewModelScope.launch {
            prefs.simplified.collect { v -> _ui.update { it.copy(simplified = v) } }
        }
        viewModelScope.launch {
            prefs.theme.collect { v -> _ui.update { it.copy(theme = v) } }
        }
    }

    fun display(raw: String): String =
        if (_ui.value.simplified) t2s.convert(raw) else raw

    fun selectTab(tab: Int) {
        if (_ui.value.tab == tab) return
        val cur = _ui.value
        _ui.value = Ui(tab = tab, categoryId = cur.categoryId, loading = true,
            simplified = cur.simplified, theme = cur.theme)
        refresh()
    }

    fun selectCategory(id: Int) {
        _ui.value = _ui.value.copy(tab = 1, categoryId = id, page = 1, books = emptyList(),
            loading = true, loadingMore = false, endOfList = false, error = null)
        refresh()
    }

    fun refresh() {
        val s = _ui.value
        // Set loading synchronously so a concurrent loadMore() sees it and stands down.
        _ui.value = s.copy(loading = true, loadingMore = false, error = null)
        viewModelScope.launch {
            try {
                val books = if (s.tab == 0) repo.recent(1) else repo.category(s.categoryId, 1)
                val distinct = books.distinctBy { it.id }
                _ui.update { cur ->
                    // Tab/category changed while fetching: drop this stale page-1.
                    if (cur.tab != s.tab || cur.categoryId != s.categoryId) cur
                    else cur.copy(
                        books = distinct, loading = false, error = null,
                        page = 1, endOfList = books.isEmpty(),
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _ui.update { cur ->
                    if (cur.tab != s.tab || cur.categoryId != s.categoryId) cur
                    else cur.copy(loading = false, error = "${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    fun loadMore() {
        val s = _ui.value
        if (s.loading || s.loadingMore || s.endOfList) return
        // Set the flag synchronously on the caller (Main) thread. Setting it
        // inside launch{} lets two rapid scroll events both pass the guard
        // before either flag is visible, double-fetching the same page.
        _ui.value = s.copy(loadingMore = true, error = null)
        viewModelScope.launch {
            try {
                val next = if (s.tab == 0) repo.recent(s.page + 1)
                else repo.category(s.categoryId, s.page + 1)
                _ui.update { cur ->
                    // Tab/category switched, or a refresh() started while we were
                    // fetching: drop this stale page instead of overwriting fresh state.
                    // (Fresh Ui already has loadingMore=false, so just return it.)
                    if (cur.tab != s.tab || cur.categoryId != s.categoryId ||
                        cur.page != s.page || cur.loading
                    ) cur
                    else {
                        // Live-shifted recent feed overlaps pages (verified: id 25745
                        // on both page 1 and 2): dedup by stable id so LazyColumn
                        // keys never collide and crash.
                        val merged = mergeBooks(cur.books, next)
                        cur.copy(
                            books = merged, page = cur.page + 1, error = null,
                            loadingMore = false, endOfList = next.isEmpty(),
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _ui.update { cur ->
                    if (cur.tab != s.tab || cur.categoryId != s.categoryId ||
                        cur.page != s.page || cur.loading
                    ) cur
                    else cur.copy(loadingMore = false, error = "${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    companion object {
        /** Append a page, dropping overlap by stable book id (feed shifts live). */
        fun mergeBooks(
            old: List<Parser.BookItem>,
            next: List<Parser.BookItem>,
        ): List<Parser.BookItem> = (old + next).distinctBy { it.id }
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
        Text(
            "uukanshu",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
        )
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
        // Saveable so detail->back restores index/offset via the home
        // back-stack entry; plain remember is discarded with the composition.
        // Reset only on a real tab/category change, not on first composition
        // or return from detail (lastResetKey is null then, so we skip).
        val listState = rememberSaveable(saver = LazyListState.Saver) {
            LazyListState()
        }
        var lastResetKey by remember { mutableStateOf<Pair<Int, Int>?>(null) }
        LaunchedEffect(ui.tab, ui.categoryId) {
            val key = ui.tab to ui.categoryId
            if (lastResetKey != null && lastResetKey != key) {
                listState.scrollToItem(0)
            }
            lastResetKey = key
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
            // Empty list with no error (empty category / parse miss with HTTP
            // 200): say so with a retry instead of a blank screen.
            ui.books.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        vm.display("暫無更新"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button({ vm.refresh() }, Modifier.padding(top = 12.dp)) { Text(vm.display("重試 / Retry")) }
                }
            }
            else -> {
                LaunchedEffect(listState, ui.tab, ui.categoryId) {
                    // Observe live totalItemsCount, not a stale ui.books.size capture:
                    // the old threshold never advanced after appends, refiring
                    // loadMore() for pages 3,4,5... without further scrolling.
                    snapshotFlow {
                        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                        val total = listState.layoutInfo.totalItemsCount
                        last to total
                    }.distinctUntilChanged().collect { (idx, total) ->
                        if (idx != null && total > 0 && idx >= total - 3) vm.loadMore()
                    }
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    // Stable id key: recent feed shifts live (same id across pages),
                    // and id+title duplicates crash LazyColumn. Dedup happens in VM.
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
                    // Page-N failure with earlier pages visible: footer error + retry
                    // instead of silently swallowing (was invisible when books non-empty).
                    if (ui.error != null && ui.books.isNotEmpty() && !ui.loadingMore) {
                        item {
                            Column(
                                Modifier.fillMaxWidth().padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    ui.error!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Button({ vm.loadMore() }, Modifier.padding(top = 8.dp)) {
                                    Text(vm.display("重試 / Retry"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
