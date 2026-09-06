package cc.uukanshu.ui.detail

import cc.uukanshu.data.repo.BookRepo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.uukanshu.core.Display
import cc.uukanshu.data.convert.T2S
import cc.uukanshu.data.download.BookDownloadManager
import cc.uukanshu.data.parse.Parser
import cc.uukanshu.di.RepoApi
import cc.uukanshu.di.PrefsApi
import cc.uukanshu.core.Errors
import cc.uukanshu.data.repo.TocRevalidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DetailViewModel(
    private val repo: RepoApi,
    private val prefs: PrefsApi,
    private val t2s: T2S,
    private val bookId: String,
    private val downloads: BookDownloadManager,
) : ViewModel() {
    /** Load split from live overlays (progress/badges/bookmark compose orthogonally). */
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
    // Serialized refresh (last-tapped wins); independent of download jobs.
    private var refreshJob: kotlinx.coroutines.Job? = null
    private val toc = TocRevalidator(repo)

    init {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(simplified = prefs.simplified.first())
            refresh()
        }
        // Live badges/bookmark/download re-attach (survive nav).
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
        // Re-attach to app-scoped download.
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
        /** Single empty-TOC guard — see [TocRevalidator]. */
        fun shouldAcceptFresh(
            freshChapters: List<Parser.ChapterRef>,
            cachedCount: Int = 0,
        ): Boolean =
            TocRevalidator.shouldAcceptFresh(freshChapters, cachedCount)
    }

    fun refresh() {
        // Refresh never cancels downloads (independent jobs).
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            // Paint cache instantly, refresh silently (see TocRevalidator).
            val cached = toc.cached(bookId)
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
            // Stale count guards against truncated parses (see TocRevalidator):
            // a shrunken TOC never wipes painted cache.
            val staleCount = (cached?.chapters?.size) ?: 0
            when (val res = toc.revalidate(bookId, staleCount)) {
                is TocRevalidator.Revalidate.Accepted -> {
                    val fresh = res.detail
                    _ui.update {
                        it.copy(
                            load = Load.Ready(
                                meta = fresh.meta, chapters = fresh.chapters,
                                offline = false, refreshing = false,
                            ),
                        )
                    }
                }
                is TocRevalidator.Revalidate.RejectedEmpty,
                is TocRevalidator.Revalidate.RejectedShrink -> {
                    // Empty/shrunken TOC never wipes painted cache.
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
                }
                is TocRevalidator.Revalidate.Failed -> {
                    val e = res.error
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
    }

    /** Manual full-novel download: app-scoped, survives leaving detail. */
    fun downloadAll() {
        if (_ui.value.downloading) return
        // Seed from retained manager progress: a failed done/total stays
        // visible until fresh callbacks arrive instead of flashing 0/0.
        val retained = downloads.states.value[bookId]
        _ui.value = _ui.value.copy(
            downloading = true,
            done = retained?.done ?: 0,
            downloadTotal = retained?.total ?: 0,
            downloadError = null,
        )
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
