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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import cc.uukanshu.CATEGORIES
import cc.uukanshu.app
import cc.uukanshu.core.Errors
import cc.uukanshu.data.convert.T2S
import cc.uukanshu.data.paging.BookPagingSource
import cc.uukanshu.data.parse.Parser
import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.ui.vmFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(
    private val repo: cc.uukanshu.data.repo.BookRepo,
    private val prefs: Prefs,
    private val t2s: T2S,
) : ViewModel() {
    /**
     * Chrome state only (tab, category, display prefs). Page bookkeeping
     * (page index, loading flags, stale-drop guards, merge dedup) used to
     * live here as six cooperating fields — every one a chance to double-
     * fetch or paint stale pages. Paging 3 owns all of that now; this flow
     * just selects which list to show.
     */
    data class Ui(
        val tab: Int = 0, // 0 recent, 1 category
        val categoryId: Int = 1,
        val simplified: Boolean = false,
        val theme: String = Prefs.SYSTEM,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    /**
     * Per-list scroll positions, keyed by [listKey]. The ViewModel survives
     * detail->back (HOME back-stack entry is retained), so this covers the
     * reported bug; `rememberSaveable(key)` in the composable is the second
     * layer for rotation / process death. One entry per list (recent + each
     * category) so lists never clobber each other — the old single shared
     * LazyListState did.
     */
    private val scrolls = mutableMapOf<String, Pair<Int, Int>>()

    fun listKey(tab: Int, categoryId: Int): String =
        if (tab == 0) "recent" else "cat-$categoryId"

    fun scrollFor(key: String): Pair<Int, Int> = scrolls[key] ?: (0 to 0)

    fun saveScroll(key: String, index: Int, offset: Int) {
        scrolls[key] = index to offset
    }

    init {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(
                simplified = prefs.simplified.first(),
                theme = prefs.theme.first(),
            )
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
        // Explicit user switch resets the target list to top (per-list reset
        // on tab switch); detail->back never calls here so its saved pos stays.
        scrolls.remove(listKey(tab, _ui.value.categoryId))
        _ui.update { it.copy(tab = tab) }
    }

    fun selectCategory(id: Int) {
        val cur = _ui.value
        if (cur.tab == 1 && cur.categoryId == id) return
        // Same reset rule as selectTab: switching categories starts at top.
        scrolls.remove(listKey(1, id))
        _ui.update { it.copy(tab = 1, categoryId = id) }
    }

    /**
     * One Pager per tab/category: switching lists invalidates the old
     * PagingSource (and its seen-ids set), so ids never leak across lists.
     * Retries, prefetch and refresh-load-states come from Paging — the old
     * hand-rolled loadMore/refresh/stale-drop methods are gone.
     */
    val pagingData: Flow<PagingData<Parser.BookItem>> = _ui
        .map { it.tab to it.categoryId }
        .distinctUntilChanged()
        .flatMapLatest { (tab, categoryId) ->
            Pager(PagingConfig(pageSize = 20, enablePlaceholders = false)) {
                BookPagingSource { page ->
                    if (tab == 0) repo.recent(page)
                    else repo.category(categoryId, page)
                }
            }.flow.cachedIn(viewModelScope)
        }
}

@Composable
fun HomeScreen(onBook: (String) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.app()
    val vm: HomeViewModel = viewModel(factory = vmFactory {
        HomeViewModel(app.repo, app.prefs, app.t2s)
    })
    val ui by vm.ui.collectAsState()
    val books = vm.pagingData.collectAsLazyPagingItems()

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
        // Per-list scroll: one saveable state per recent/category key.
        // - detail->back restores via the HOME back-stack entry (saveable +
        //   ViewModel map, which survives because HOME is retained).
        // - explicit tab/category switch resets to top (selectTab/Category
        //   cleared the VM entry; scroll to 0 in case the saveable for this
        //   key still holds an old position from a previous visit).
        // - staying on the same list (e.g. simplified toggle) keeps position.
        val key = vm.listKey(ui.tab, ui.categoryId)
        val initial = remember(key) { vm.scrollFor(key) }
        val listState = rememberSaveable(key, saver = LazyListState.Saver) {
            LazyListState(initial.first, initial.second)
        }
        LaunchedEffect(key, listState) {
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                .collect { (index, offset) -> vm.saveScroll(key, index, offset) }
        }
        LaunchedEffect(key) {
            if (vm.scrollFor(key) == (0 to 0) &&
                (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0)
            ) {
                listState.scrollToItem(0)
            }
        }
        val refresh = books.loadState.refresh
        val append = books.loadState.append
        when {
            refresh is androidx.paging.LoadState.Loading && books.itemCount == 0 ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            refresh is androidx.paging.LoadState.Error && books.itemCount == 0 ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            Errors.message(refresh.error),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button({ books.retry() }, Modifier.padding(top = 12.dp)) {
                            Text(vm.display("重試 / Retry"))
                        }
                    }
                }
            // Empty list with no error (empty category / parse miss with HTTP
            // 200): say so with a retry instead of a blank screen.
            books.itemCount == 0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        vm.display("暫無更新"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button({ books.refresh() }, Modifier.padding(top = 12.dp)) {
                        Text(vm.display("重試 / Retry"))
                    }
                }
            }
            else -> {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    // Stable id keys via peek (no load trigger); cross-page
                    // duplicates are already filtered in BookPagingSource, so
                    // keys can never collide and crash.
                    items(
                        count = books.itemCount,
                        key = books.itemKey { it.id },
                    ) { i ->
                        val b = books[i] ?: return@items
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
                    if (append is androidx.paging.LoadState.Loading) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                    // Page-N failure with earlier pages visible: footer error + retry
                    // instead of silently swallowing (was invisible when books non-empty).
                    if (append is androidx.paging.LoadState.Error) {
                        item {
                            Column(
                                Modifier.fillMaxWidth().padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    Errors.message(append.error),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Button({ books.retry() }, Modifier.padding(top = 8.dp)) {
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
