package cc.uukanshu.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.uukanshu.core.Display
import cc.uukanshu.di.ConvertApi
import cc.uukanshu.data.parse.Parser
import cc.uukanshu.di.PrefsApi
import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.di.RepoApi
import cc.uukanshu.core.Errors
import cc.uukanshu.data.net.BulkFetch
import cc.uukanshu.data.repo.TocRevalidator
import cc.uukanshu.ui.ThemeIconButton
import cc.uukanshu.ui.vmFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReaderViewModel(
    private val repo: RepoApi,
    private val t2s: ConvertApi,
    private val prefs: PrefsApi,
    private val bookId: String,
    startPosition: Int,
    private val startPageId: Long = 0L,
) : ViewModel() {

    /** Sealed Ui (Loading/Content/Error); prefs on the interface for the sticky bottom bar. */
    sealed interface Ui {
        val position: Int
        val total: Int
        val simplified: Boolean
        val fontScale: Float
        val theme: String
        val isLoading: Boolean

        data class Loading(
            override val position: Int,
            override val total: Int = 0,
            override val simplified: Boolean = false,
            override val fontScale: Float = 1f,
            override val theme: String = Prefs.SYSTEM,
        ) : Ui {
            override val isLoading: Boolean = true
        }

        data class Content(
            override val position: Int,
            override val total: Int,
            val book: String,
            val title: String,
            val text: String,
            override val simplified: Boolean,
            override val fontScale: Float,
            override val theme: String,
        ) : Ui {
            override val isLoading: Boolean = false
        }

        data class Error(
            override val position: Int,
            override val total: Int = 0,
            val message: String,
            override val simplified: Boolean = false,
            override val fontScale: Float = 1f,
            override val theme: String = Prefs.SYSTEM,
        ) : Ui {
            override val isLoading: Boolean = false
        }
    }

    /** Copy helper for sealed Ui (null = keep). */
    private fun Ui.copyWith(
        simplified: Boolean? = null,
        fontScale: Float? = null,
        theme: String? = null,
        total: Int? = null,
    ): Ui = when (this) {
        is Ui.Loading -> copy(
            simplified = simplified ?: this.simplified,
            fontScale = fontScale ?: this.fontScale,
            theme = theme ?: this.theme,
            total = total ?: this.total,
        )
        is Ui.Content -> copy(
            simplified = simplified ?: this.simplified,
            fontScale = fontScale ?: this.fontScale,
            theme = theme ?: this.theme,
            total = total ?: this.total,
        )
        is Ui.Error -> copy(
            simplified = simplified ?: this.simplified,
            fontScale = fontScale ?: this.fontScale,
            theme = theme ?: this.theme,
            total = total ?: this.total,
        )
    }

    private val _ui = MutableStateFlow<Ui>(Ui.Loading(position = startPosition))
    val ui: StateFlow<Ui> = _ui
    private var chapters: List<Parser.ChapterRef> = emptyList()
    // First load resolves by stable pageId (position may name a neighbor after a TOC shift).
    private var pendingPageId: Long = startPageId
    private var bookTitleRaw: String = ""
    // Serialized loads: rapid prev/next taps, last-tapped wins.
    private var loadJob: Job? = null
    private var prefetchJob: Job? = null
    private var revalidateJob: Job? = null
    private val toc = TocRevalidator(repo)
    // Last raw chapter for no-network language re-render.
    private var currentRaw: Parser.ChapterContent? = null

    init {
        viewModelScope.launch {
            _ui.update {
                it.copyWith(
                    simplified = prefs.simplified.first(),
                    fontScale = prefs.fontScale.first(),
                    theme = prefs.theme.first(),
                )
            }
            load(startPosition)
        }
    }

    companion object {
        /** Stable pageId wins over display position (pure + tested). */
        fun resolveEffectivePosition(
            chapters: List<Parser.ChapterRef>,
            requestedPosition: Int,
            requestedPageId: Long,
        ): Int {
            if (requestedPageId != 0L) {
                chapters.firstOrNull { it.pageId == requestedPageId }?.let { return it.position }
            }
            return requestedPosition
        }
    }

    private fun render(raw: Parser.ChapterContent, simplified: Boolean): Triple<String, String, String> =
        if (simplified) Triple(t2s.convert(raw.book), t2s.convert(raw.title), t2s.convert(raw.text))
        else Triple(raw.book, raw.title, raw.text)

    fun load(position: Int) {
        loadJob?.cancel()
        prefetchJob?.cancel()
        revalidateJob?.cancel()
        loadJob = viewModelScope.launch {
            val cur = _ui.value
            _ui.value = Ui.Loading(
                position = position,
                total = cur.total,
                simplified = cur.simplified,
                fontScale = cur.fontScale,
                theme = cur.theme,
            )
            try {
                if (chapters.isEmpty()) {
                    // Stale-while-revalidate for TOC (see TocRevalidator):
                    // paint cached TOC instantly, then refresh silently.
                    val cachedToc = toc.cached(bookId)
                    if (cachedToc != null) {
                        chapters = cachedToc.chapters
                        if (cachedToc.meta.title.isNotEmpty()) bookTitleRaw = cachedToc.meta.title
                    }
                    if (chapters.isEmpty() || position < 1 || position > chapters.size) {
                        // Blocking fetch doubles as the revalidation — no extra
                        // background request. Empty fresh TOC is rejected
                        // (see TocRevalidator): keep stale, fail loudly on nothing.
                        try {
                            val fresh = repo.detail(bookId)
                            if (TocRevalidator.shouldAcceptFresh(fresh.chapters, chapters.size)) {
                                chapters = fresh.chapters
                                if (fresh.meta.title.isNotEmpty()) bookTitleRaw = fresh.meta.title
                                _ui.update { it.copyWith(total = chapters.size) }
                            } else if (chapters.isEmpty()) {
                                throw java.io.IOException("empty chapter list — try again later")
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            // Offline with cache: keep stale TOC if we have it.
                            if (chapters.isEmpty()) throw e
                        }
                    } else if (cachedToc != null) {
                        // Serving from stale TOC: revalidate silently.
                        // Stale size guards against truncated parses.
                        // Child of the load (not a viewModelScope sibling):
                        // cancelling the load cancels its revalidate, so a
                        // superseded revalidate can never commit stale totals
                        // after a newer load resolved. See SCRAPING.md.
                        val staleCount = chapters.size
                        revalidateJob = launch {
                            when (val res = toc.revalidate(bookId, staleCount)) {
                                is TocRevalidator.Revalidate.Accepted -> {
                                    chapters = res.detail.chapters
                                    if (res.detail.meta.title.isNotEmpty()) bookTitleRaw = res.detail.meta.title
                                    _ui.update { it.copyWith(total = chapters.size) }
                                }
                                else -> Unit // Empty/shrunken/failed: keep stale, reading never breaks.
                            }
                        }
                    }
                }
                // One-shot pageId resolution for the initial open: a TOC shift
                // between Detail tap and Reader load must not alias to a
                // neighbor. Subsequent prev/next loads pass position only
                // (pendingPageId already consumed → 0).
                val effective = if (pendingPageId != 0L) {
                    val r = resolveEffectivePosition(chapters, position, pendingPageId)
                    pendingPageId = 0L
                    r
                } else position
                val total = chapters.size
                if (effective < 1 || effective > total) {
                    val cur = _ui.value
                    _ui.value = Ui.Error(
                        position = effective,
                        total = total,
                        message = "章節超出範圍",
                        simplified = cur.simplified,
                        fontScale = cur.fontScale,
                        theme = cur.theme,
                    )
                    return@launch
                }
                val ref = chapters[effective - 1]
                // Room cache first (by stable pageId), else network (then save raw).
                val cached = repo.cachedChapterContent(bookId, ref.pageId)
                val raw = if (cached != null) {
                    // Reconstruct nav from TOC positions (shape-validated chapter URLs only).
                    // Book comes from TOC meta via [ReaderTitle]: never ref.title.
                    Parser.ChapterContent(
                        book = ReaderTitle.resolve(bookTitleRaw, "", (_ui.value as? Ui.Content)?.book.orEmpty()),
                        title = ref.title, text = cached,
                        prevUrl = chapters.getOrNull(effective - 2)?.url,
                        tocUrl = null,
                        nextUrl = chapters.getOrNull(effective)?.url,
                    )
                } else {
                    val fetched = repo.chapter(ref.url)
                    // Backfill the authoritative name when TOC meta was empty
                    // (offline edge) but the chapter page knows the book.
                    if (bookTitleRaw.isEmpty() && fetched.book.isNotEmpty()) {
                        bookTitleRaw = fetched.book
                    }
                    val withBook = if (fetched.book.isEmpty() && bookTitleRaw.isNotEmpty()) {
                        fetched.copy(book = bookTitleRaw)
                    } else fetched
                    // PageId-keyed write: correct even if a background
                    // revalidate shifted positions mid-fetch.
                    withBook.also {
                        repo.saveChapterContent(bookId, ref.pageId, it.text)
                    }
                }
                currentRaw = raw
                val cur = _ui.value
                val (book, title, text) = render(raw, cur.simplified)
                _ui.value = Ui.Content(
                    position = effective,
                    total = total,
                    book = book, title = title, text = text,
                    simplified = cur.simplified,
                    fontScale = cur.fontScale,
                    theme = cur.theme,
                )
                // Silent auto-bookmark by stable pageId (position shifts on
                // TOC inserts); never break reading on save failure
                // (cancellation still propagates).
                try {
                    repo.saveProgress(bookId, effective, ref.pageId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
                prefetchNext5(effective)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val cur = _ui.value
                _ui.value = Ui.Error(
                    position = cur.position,
                    total = cur.total,
                    message = Errors.friendly(e),
                    simplified = cur.simplified,
                    fontScale = cur.fontScale,
                    theme = cur.theme,
                )
            }
        }
    }

    /** Auto-cache the next 5 chapters, sequential with crawl delay, silent-fail. */
    private fun prefetchNext5(from: Int) {
        prefetchJob?.cancel()
        // Snapshot: a background TOC revalidate may swap [chapters] mid-loop.
        val snapshot = chapters.toList()
        prefetchJob = viewModelScope.launch {
            var fetchedAny = false
            for (pos in (from + 1)..minOf(from + 5, snapshot.size)) {
                val ref = snapshot[pos - 1]
                if (repo.cachedChapterContent(bookId, ref.pageId) != null) continue
                if (fetchedAny) repo.crawlDelay()
                try {
                    // Bulk lane (see BulkFetch): prefetch never jumps ahead of taps.
                    val text = withContext(BulkFetch) { repo.chapter(ref.url).text }
                    // PageId-keyed write: safe even if the live TOC moved.
                    repo.saveChapterContent(bookId, ref.pageId, text)
                    fetchedAny = true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Silent: a failed prefetch must neither break reading
                    // nor force a crawl delay on the next chapter.
                }
            }
        }
    }

    fun toggleSimplified() {
        // Compute and publish synchronously on the caller (Main) thread:
        // two rapid taps must toggle twice, never read the same stale value.
        val next = !_ui.value.simplified
        val raw = currentRaw
        // Re-render current chapter without refetch or reload.
        val cur = _ui.value
        _ui.value = if (raw != null && cur is Ui.Content) {
            val (book, title, text) = render(raw, next)
            cur.copy(simplified = next, book = book, title = title, text = text)
        } else {
            cur.copyWith(simplified = next)
        }
        viewModelScope.launch {
            prefs.setSimplified(next)
            if (raw == null) load(_ui.value.position)
        }
    }

    fun font(delta: Float) {
        // Atomic read-modify-write on Main: the DataStore write below
        // suspends, so reading inside the coroutine would let two rapid
        // taps both read the old scale and lose one step.
        val next = Prefs.coerceFontScale(_ui.value.fontScale + delta)
        _ui.update { it.copyWith(fontScale = next) }
        viewModelScope.launch { prefs.setFontScale(next) }
    }

    fun display(raw: String): String =
        Display.text(t2s, raw, _ui.value.simplified)

    /** Converted theme-mode label for the settings menu. */
    fun themeLabel(): String = display(when (_ui.value.theme) {
        Prefs.LIGHT -> "主題：淺色"
        Prefs.DARK -> "主題：深色"
        else -> "主題：自動"
    })

    /** Cycle system → light → dark theme. Applied app-wide via prefs. */
    fun cycleTheme() {
        val next = Prefs.next(_ui.value.theme)
        _ui.update { it.copyWith(theme = next) }
        viewModelScope.launch { prefs.setTheme(next) }
    }
}
