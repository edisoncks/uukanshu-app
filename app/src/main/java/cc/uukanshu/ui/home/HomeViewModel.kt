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

    /** Per-list scroll positions (ConcurrentHashMap: collectors race tab switches). */
    private val scrolls = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Int>>()

    /** One-shot scroll-to-top flags for explicit tab/category switches (detail->back never sets). */
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
        // Explicit switch resets target list to top; detail->back keeps position.
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
     * One cached Pager per tab/category.
     * Why not flatMapLatest over _ui: re-collection after detail->back would
     * rebuild the Pager, refetch page 1 and lose scroll. Per-key cache keeps
     * pages; bounded to MAX_PAGERS. See ARCHITECTURE.md.
     */
    private val pagers =
        java.util.concurrent.ConcurrentHashMap<String, Flow<PagingData<Parser.BookItem>>>()

    companion object {
        /** recent + 10 category lists. */
        const val MAX_PAGERS = 11
    }

    /** Test seam: current pager cache size. */
    fun pagerCountForTest(): Int = pagers.size

    fun pagingFor(tab: Int, categoryId: Int): Flow<PagingData<Parser.BookItem>> {
        val key = listKey(tab, categoryId)
        pagers[key]?.let { return it }
        synchronized(pagers) {
            pagers[key]?.let { return it }
            if (pagers.size >= MAX_PAGERS) {
                // Deterministic victim (sorted-first, never the incoming key):
                // ConcurrentHashMap order is unspecified, so an unordered pick
                // evicts a random live pager per run.
                pagers.keys.sorted().firstOrNull { it != key }?.let { pagers.remove(it) }
            }
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
}
