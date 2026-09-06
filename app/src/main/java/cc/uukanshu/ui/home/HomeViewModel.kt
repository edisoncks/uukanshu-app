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
import cc.uukanshu.di.ConvertApi
import cc.uukanshu.data.paging.BookPagingSource
import cc.uukanshu.data.parse.Parser
import cc.uukanshu.di.RepoApi
import cc.uukanshu.di.PrefsApi
import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.ui.vmFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(
    private val repo: RepoApi,
    private val prefs: PrefsApi,
    private val t2s: ConvertApi,
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
     * detail->back (HOME back-stack entry is retained); `rememberSaveable`
     * in the composable is the second layer for rotation / process death.
     * One entry per list (recent + each category) so lists never clobber
     * each other — the old single shared LazyListState did.
     *
     * Thread-safe: `ConcurrentHashMap` because `snapshotFlow` collectors
     * and composable `remember(key)` reads can race tab switches on
     * different dispatchers. Plain `mutableMap` lost updates under rapid
     * tab/category flapping.
     */
    private val scrolls = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Int>>()

    /**
     * Explicit tab/category switches that want top. Consumed once by the
     * composable (`scrollToItem(0)`). A plain "VM entry is 0,0" check
     * cannot distinguish explicit-reset from a fresh ViewModel after
     * process death (where the saveable position must win), so this
     * one-shot flag exists. detail->back never sets it, so back keeps
     * position.
     *
     * Thread-safe set backed by a `ConcurrentHashMap` for the same reason
     * as [scrolls]: one-shot top-scroll flags must survive concurrent
     * produce/consume without lost updates.
     */
    private val pendingTop: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun listKey(tab: Int, categoryId: Int): String =
        if (tab == 0) "recent" else "cat-$categoryId"

    fun scrollFor(key: String): Pair<Int, Int> = scrolls[key] ?: (0 to 0)

    fun saveScroll(key: String, index: Int, offset: Int) {
        scrolls[key] = index to offset
    }

    fun consumePendingTop(key: String): Boolean = pendingTop.remove(key)

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
        Display.text(t2s, raw, _ui.value.simplified)

    fun selectTab(tab: Int) {
        if (_ui.value.tab == tab) return
        // Explicit user switch resets the target list to top (per-list reset
        // on tab switch); detail->back never calls here so its saved pos stays.
        val key = listKey(tab, _ui.value.categoryId)
        scrolls.remove(key)
        pendingTop.add(key)
        _ui.update { it.copy(tab = tab) }
    }

    fun selectCategory(id: Int) {
        val cur = _ui.value
        if (cur.tab == 1 && cur.categoryId == id) return
        // Same reset rule as selectTab: switching categories starts at top.
        val key = listKey(1, id)
        scrolls.remove(key)
        pendingTop.add(key)
        _ui.update { it.copy(tab = 1, categoryId = id) }
    }

    /**
     * One cached Pager per tab/category, keyed by [listKey].
     *
     * The previous `_ui.map{...}.distinctUntilChanged().flatMapLatest{
     * Pager... }` Flow looked equivalent but rebuilt a brand-new Pager on
     * every new collection: `_ui` is a StateFlow, so re-collecting after
     * detail->back (HOME recomposes, `collectAsLazyPagingItems` resubscribes)
     * replays the current tab/category and `flatMapLatest` creates a fresh
     * Pager/PagingSource. That refetches page 1 from network, swaps the
     * LazyColumn for the full-screen spinner (`refresh Loading &&
     * itemCount == 0`), and the restored index points at pages that no
     * longer exist (the recent feed also shifts between requests) — so the
     * scroll position was lost on every back navigation. `cachedIn` inside
     * `flatMapLatest` did not help: each re-collection cached a *new* flow
     * and abandoned the old pages.
     *
     * Caching the Flow per key keeps the same PagingData (and loaded pages)
     * across detail->back, so no refetch and the saved scroll stays valid.
     * Switching lists still uses a separate Pager (and seen-ids set), so ids
     * never leak across lists. Retries, prefetch and refresh-load-states
     * come from Paging — the old hand-rolled loadMore/refresh/stale-drop
     * methods are gone.
     *
     * Thread-safe like [scrolls]: `pagingFor` is called from the composable
     * (`remember(key)`) and may race across recompositions; use the atomic
     * `computeIfAbsent` so two threads never build two Pagers for one key
     * (which would fork seen-id sets and refetch page 1 twice).
     */
    private val pagers =
        java.util.concurrent.ConcurrentHashMap<String, Flow<PagingData<Parser.BookItem>>>()

    fun pagingFor(tab: Int, categoryId: Int): Flow<PagingData<Parser.BookItem>> {
        val key = listKey(tab, categoryId)
        return pagers.computeIfAbsent(key) {
            Pager(PagingConfig(pageSize = 20, enablePlaceholders = false)) {
                BookPagingSource { page ->
                    if (tab == 0) repo.recent(page)
                    else repo.category(categoryId, page)
                }
            }.flow.cachedIn(viewModelScope)
        }
    }
}
