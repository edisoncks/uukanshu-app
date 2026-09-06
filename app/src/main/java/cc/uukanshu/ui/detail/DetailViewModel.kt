package cc.uukanshu.ui.detail

import cc.uukanshu.data.repo.BookRepo
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
import cc.uukanshu.core.Display
import cc.uukanshu.di.ConvertApi
import cc.uukanshu.di.DownloadsApi
import cc.uukanshu.data.parse.Parser
import cc.uukanshu.di.RepoApi
import cc.uukanshu.di.PrefsApi
import cc.uukanshu.core.Errors
import cc.uukanshu.ui.vmFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DetailViewModel(
    private val repo: RepoApi,
    private val prefs: PrefsApi,
    private val t2s: ConvertApi,
    private val bookId: String,
    private val downloads: DownloadsApi,
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
        val downloadTotal: Int = 0,
        val downloadError: String? = null,
        val simplified: Boolean = false,
        val cached: Set<Long> = emptySet(),
        val bookmark: BookRepo.Bookmark? = null,
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
        // Live bookmark by stable pageId: reader auto-saves on every
        // successful open, so the continue button stays correct across
        // TOC shifts (position alone would misdirect after inserts).
        viewModelScope.launch {
            repo.bookmarkFlow(bookId).collect { bm ->
                _ui.value = _ui.value.copy(bookmark = bm)
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
                    downloadTotal = st.total,
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
                                load = Load.Failed("章節列表為空，請稍後再試"),
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
                        else -> cur.copy(load = Load.Failed(Errors.friendly(e)))
                    }
                }
            }
        }
    }

    /** Manual full-novel download: app-scoped, survives leaving detail. */
    fun downloadAll() {
        if (_ui.value.downloading) return
        _ui.value = _ui.value.copy(downloading = true, done = 0, downloadTotal = 0, downloadError = null)
        downloads.start(bookId)
    }

    fun cancelDownload() {
        downloads.cancel(bookId)
        // Manager publishes downloading=false; reflect instantly for snappy UI.
        _ui.value = _ui.value.copy(downloading = false)
    }

    fun displayTitle(raw: String): String =
        Display.text(t2s, raw, _ui.value.simplified)

    /** Continue target resolved against the live TOC (pageId first). */
    fun continueChapter(chapters: List<Parser.ChapterRef>): Parser.ChapterRef? =
        BookRepo.resolveBookmark(chapters, _ui.value.bookmark)

    fun isBookmarked(c: Parser.ChapterRef): Boolean {
        val bm = _ui.value.bookmark ?: return false
        if (bm.pageId != 0L) return c.pageId == bm.pageId
        return c.position == bm.position
    }

    /** Denominator for the live progress line: manager total when known. */
    fun progressTotal(liveSize: Int): Int =
        when {
            _ui.value.downloadTotal > 0 -> _ui.value.downloadTotal
            else -> liveSize
        }
}
