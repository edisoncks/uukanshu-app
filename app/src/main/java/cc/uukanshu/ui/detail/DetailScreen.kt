package cc.uukanshu.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.uukanshu.data.convert.T2S
import cc.uukanshu.data.download.BookDownloadManager
import cc.uukanshu.data.parse.Parser
import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.app
import cc.uukanshu.core.Errors
import cc.uukanshu.ui.vmFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DetailViewModel(
    private val repo: cc.uukanshu.data.repo.BookRepo,
    private val prefs: Prefs,
    private val t2s: T2S,
    private val bookId: String,
    private val downloads: BookDownloadManager,
) : ViewModel() {
    /**
     * Load state, split from live overlays. Content is loading, failed, or
     * ready — never loading+failed, never a null-meta success (the old
     * `meta!!` crash site). Download progress, cache badges, bookmarks and
     * display prefs compose orthogonally on [Ui] and keep updating no
     * matter which load state is showing.
     */
    sealed interface Load {
        data object Loading : Load
        data class Failed(val message: String) : Load
        data class Ready(
            val meta: Parser.BookMeta,
            val chapters: List<Parser.ChapterRef>,
            val offline: Boolean = false,
            val refreshing: Boolean = false,
        ) : Load
    }

    data class Ui(
        val load: Load = Load.Loading,
        val downloading: Boolean = false,
        val done: Int = 0,
        val downloadError: String? = null,
        val simplified: Boolean = false,
        val cached: Set<Int> = emptySet(),
        val bookmarkedPosition: Int? = null,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui
    // Serialized refresh: rapid retry taps cancel the previous fetch so two
    // wholesale TOC replaces never run concurrently (last-tapped wins).
    // Never touched by downloadAll/cancelDownload — those are independent.
    private var refreshJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(simplified = prefs.simplified.first())
            refresh()
        }
        // Live badge state: every cache write (reader, prefetch, download)
        // re-emits, so badges stay correct when returning to this screen.
        viewModelScope.launch {
            repo.cachedPositionsFlow(bookId).collect { positions ->
                _ui.value = _ui.value.copy(cached = positions)
            }
        }
        // Live bookmark: reader auto-saves on every successful open,
        // so the continue button stays correct when navigating back.
        viewModelScope.launch {
            repo.progressFlow(bookId).collect { pos ->
                _ui.value = _ui.value.copy(bookmarkedPosition = pos)
            }
        }
        // App-scoped download: re-attach to an in-flight or finished job
        // when re-opening this detail after navigating away.
        viewModelScope.launch {
            downloads.observe(bookId).collect { st ->
                if (st == null) return@collect
                _ui.value = _ui.value.copy(
                    downloading = st.downloading,
                    done = st.done,
                    downloadError = st.error,
                )
            }
        }
    }

    companion object {
        /**
         * An empty fresh TOC is never a book with zero chapters — it's a
         * failed parse (block page / captive portal / layout change) and
         * must not overwrite painted cache. Accept only non-empty TOCs.
         */
        fun shouldAcceptFresh(freshChapters: List<Parser.ChapterRef>): Boolean =
            freshChapters.isNotEmpty()
    }

    fun refresh() {
        // Never cancel the download here: refresh (init/retry) and the
        // manual full download are independent jobs. Killing the download
        // on any refresh would silently abort user-requested work.
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            // Stale-while-revalidate: paint cache instantly, refresh silently.
            val cached = try {
                repo.cachedDetail(bookId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (cached != null) {
                _ui.update {
                    it.copy(
                        load = Load.Ready(
                            meta = cached.meta, chapters = cached.chapters,
                            offline = false, refreshing = true,
                        ),
                    )
                }
            } else {
                _ui.update { it.copy(load = Load.Loading) }
            }
            try {
                val fresh = repo.detail(bookId)
                if (!shouldAcceptFresh(fresh.chapters)) {
                    // Empty TOC: keep stale content visible, flag offline
                    // when we have something; error only when we have nothing.
                    _ui.update { cur ->
                        when (val l = cur.load) {
                            is Load.Ready -> cur.copy(
                                load = l.copy(refreshing = false, offline = true),
                            )
                            else -> cur.copy(
                                load = Load.Failed("empty chapter list — try again later"),
                            )
                        }
                    }
                } else {
                    _ui.update {
                        it.copy(
                            load = Load.Ready(
                                meta = fresh.meta, chapters = fresh.chapters,
                                offline = false, refreshing = false,
                            ),
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _ui.update { cur ->
                    when (val l = cur.load) {
                        // Keep stale content visible, flag offline.
                        is Load.Ready -> cur.copy(load = l.copy(refreshing = false, offline = true))
                        else -> cur.copy(load = Load.Failed(Errors.message(e)))
                    }
                }
            }
        }
    }

    /** Manual full-novel download: app-scoped, survives leaving detail. */
    fun downloadAll() {
        if (_ui.value.downloading) return
        _ui.value = _ui.value.copy(downloading = true, done = 0, downloadError = null)
        downloads.start(bookId)
    }

    fun cancelDownload() {
        downloads.cancel(bookId)
        // Manager publishes downloading=false; reflect instantly for snappy UI.
        _ui.value = _ui.value.copy(downloading = false)
    }

    fun displayTitle(raw: String): String =
        if (_ui.value.simplified) t2s.convert(raw) else raw
}

@Composable
fun DetailScreen(bookId: String, onChapter: (bookId: String, position: Int) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.app()
    val vm: DetailViewModel = viewModel(
        key = bookId,
        factory = vmFactory {
            DetailViewModel(app.repo, app.prefs, app.t2s, bookId, app.downloadManager)
        },
    )
    val ui by vm.ui.collectAsState()

    when (val load = ui.load) {
        is DetailViewModel.Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is DetailViewModel.Load.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(load.message)
                Button({ vm.refresh() }, Modifier.padding(top = 12.dp)) { Text(vm.displayTitle("重試")) }
            }
        }
        is DetailViewModel.Load.Ready -> LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
            item {
                val m = load.meta
                if (load.refreshing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                }
                Text(vm.displayTitle(m.title), style = MaterialTheme.typography.headlineSmall)
                if (load.offline) {
                    Text(
                        vm.displayTitle("離線模式 · 緩存版本"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
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
                val bookmarked = ui.bookmarkedPosition?.takeIf { it in 1..load.chapters.size }
                    ?.let { pos -> load.chapters.firstOrNull { it.position == pos } }
                if (ui.downloading) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (bookmarked != null) {
                            Button(
                                onClick = { onChapter(bookId, bookmarked.position) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    vm.displayTitle("繼續閱讀：第 ${bookmarked.position} 章 ${bookmarked.title}"),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        LinearProgressIndicator(
                            progress = { ui.done.toFloat() / load.chapters.size.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(vm.displayTitle("下載中") + " ${ui.done}/${load.chapters.size}", style = MaterialTheme.typography.bodySmall)
                        Button({ vm.cancelDownload() }, Modifier.fillMaxWidth()) { Text(vm.displayTitle("取消")) }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (bookmarked != null) {
                            Button(
                                onClick = { onChapter(bookId, bookmarked.position) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    vm.displayTitle("繼續閱讀：第 ${bookmarked.position} 章 ${bookmarked.title}"),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        // fullyCached survives process restart (manager done/total
                        // don't); the 已緩存 count line below shows the same source.
                        val fullyCached = load.chapters.isNotEmpty() && ui.cached.size >= load.chapters.size
                        Button({ vm.downloadAll() }, Modifier.fillMaxWidth()) {
                            Text(if (fullyCached || (ui.done > 0 && ui.done >= load.chapters.size)) vm.displayTitle("重新下載整本") else vm.displayTitle("下載整本"))
                        }
                    }
                }
                ui.downloadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(
                    "${vm.displayTitle("共")} ${load.chapters.size} ${vm.displayTitle("章")} · ${vm.displayTitle("已緩存")} ${ui.cached.size} ${vm.displayTitle("章")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                HorizontalDivider()
            }
            items(load.chapters, key = { it.position }) { c ->
                Row(
                    Modifier.fillMaxWidth().clickable { onChapter(bookId, c.position) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${c.position}. ${vm.displayTitle(c.title)}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        color = if (c.position == ui.bookmarkedPosition) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    if (c.position == ui.bookmarkedPosition) {
                        Text(
                            "▶ ${vm.displayTitle("繼續")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                    if (c.position in ui.cached) {
                        Text(
                            "✓ ${vm.displayTitle("已緩存")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
