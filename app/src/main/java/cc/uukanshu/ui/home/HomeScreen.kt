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
import cc.uukanshu.core.Errors
import cc.uukanshu.core.Display
import cc.uukanshu.data.convert.T2S
import cc.uukanshu.data.paging.BookPagingSource
import cc.uukanshu.data.parse.Parser
import cc.uukanshu.data.repo.BookRepo
import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.ui.vmFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(onBook: (String) -> Unit) {
    val container = cc.uukanshu.di.LocalContainer.current
    val vm: HomeViewModel = viewModel(factory = vmFactory {
        HomeViewModel(container.repo, container.prefs, container.t2s)
    })
    val ui by vm.ui.collectAsState()
    // Stable Flow per list: the same instance across detail->back replays
    // cached pages instead of refetching page 1 (see pagingFor). remember(key)
    // swaps the instance only on a real tab/category switch.
    val key = vm.listKey(ui.tab, ui.categoryId)
    val pagingFlow = remember(key) { vm.pagingFor(ui.tab, ui.categoryId) }
    val books = pagingFlow.collectAsLazyPagingItems()

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
        // Per-list scroll: one saveable slot per recent/category key.
        // - detail->back restores via the HOME back-stack entry (saveable +
        //   ViewModel map, which survives because HOME is retained) and —
        //   now that pagingFor reuses pages — the restored index still points
        //   at loaded items instead of a fresh spinner.
        // - explicit tab/category switch resets to top via the one-shot
        //   pendingTop flag (selectTab/Category set it; consumed here).
        // - staying on the same list (e.g. simplified toggle) keeps position.
        // NOTE: `key =` is the SaveableStateRegistry slot, not the `inputs`
        // vararg: passing the list key as inputs (rememberSaveable(key)) would
        // share one slot across lists and drop the previous list's position.
        val listState = rememberSaveable(key = "home-$key", saver = LazyListState.Saver) {
            val (index, offset) = vm.scrollFor(key)
            LazyListState(index, offset)
        }
        LaunchedEffect(key, listState) {
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                .collect { (index, offset) -> vm.saveScroll(key, index, offset) }
        }
        LaunchedEffect(key, listState) {
            if (vm.consumePendingTop(key)) {
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
                            vm.display(Errors.friendly(refresh.error)),
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
                                    vm.display(Errors.friendly(append.error)),
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
