package cc.uukanshu.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.uukanshu.core.Display
import cc.uukanshu.data.convert.T2S
import cc.uukanshu.data.parse.Parser
import cc.uukanshu.di.PrefsApi
import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.di.RepoApi
import cc.uukanshu.core.Errors
import cc.uukanshu.data.net.BulkFetch
import cc.uukanshu.data.repo.TocSource
import cc.uukanshu.data.repo.TocState
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
    private val t2s: T2S,
    private val prefs: PrefsApi,
    private val bookId: String,
    startPosition: Int,
    private val startPageId: Long = 0L,
) : ViewModel() {

    /** Next-chapter step: AtEnd stays put (caller shows snackbar), Started loads. */
    sealed interface NextStep {
        data object AtEnd : NextStep
        data class Started(val position: Int) : NextStep
    }

    /** Content state only (position/total/payload); prefs live below so toggles never rebuild content. */
    sealed interface Ui {
        val position: Int
        val total: Int
        val isLoading: Boolean

        data class Loading(
            override val position: Int,
            override val total: Int = 0,
        ) : Ui {
            override val isLoading: Boolean = true
        }

        data class Content(
            override val position: Int,
            override val total: Int,
            val book: String,
            val title: String,
            val text: String,
        ) : Ui {
            override val isLoading: Boolean = false
        }

        data class Error(
            override val position: Int,
            override val total: Int = 0,
            val message: String,
            val kind: ReaderErrorKind = ReaderErrorKind.Network,
            // Stable chapter id for retry: position alone aliases to a neighbor
            // after a TOC shift, so retry must re-resolve by pageId (see load).
            val pageId: Long = 0L,
        ) : Ui {
            override val isLoading: Boolean = false
        }
    }

    private val _ui = MutableStateFlow<Ui>(Ui.Loading(position = startPosition))
    val ui: StateFlow<Ui> = _ui
    // Prefs mirror: seeded once, mutated synchronously by toggles (Main-thread
    // atomicity for rapid taps), written through to prefs. Screens collect these.
    private val _simplified = MutableStateFlow(false)
    val simplified: StateFlow<Boolean> = _simplified
    private val _fontScale = MutableStateFlow(1f)
    val fontScale: StateFlow<Float> = _fontScale
    private val _theme = MutableStateFlow(Prefs.SYSTEM)
    val theme: StateFlow<String> = _theme
    // Last known book title (TOC meta) for chrome: Ui.Loading carries no book,
    // so the top bar would flicker blank on every chapter turn without this.
    private val _bookTitle = MutableStateFlow("")
    val bookTitle: StateFlow<String> = _bookTitle

    private fun setBookTitle(raw: String) {
        bookTitleRaw = raw
        _bookTitle.value = raw
    }

    /** Totals follow the latest accepted Fresh generation — total only:
     *  never position or content, which are owned by the in-flight load. */
    private fun setTotal(total: Int) {
        _ui.update {
            when (it) {
                is Ui.Loading -> it.copy(total = total)
                is Ui.Content -> it.copy(total = total)
                is Ui.Error -> it.copy(total = total)
            }
        }
    }

    // -- TOC: one producer, one collector (see TocSource) -------------------

    private val tocSource = TocSource(repo)
    private val _toc = MutableStateFlow<TocState>(TocState.Loading)
    private var tocJob: Job? = null
    // Generation guard (Main-confined): revalidateToc invalidates late emits
    // from the cancelled run structurally; cancel() alone stops the fetch
    // but a queued Fresh could still emit after the Loading reset.
    private var tocGen: Long = 0
    // Terminal = Fresh/Stale/Failed; Syncing/Loading still have a fetch in flight.
    private fun isTerminal(st: TocState): Boolean =
        st is TocState.Failed || (st is TocState.Ready && st.phase != TocState.Phase.Syncing)
    // First load resolves by stable pageId (position may name a neighbor after a TOC shift).
    private var pendingPageId: Long = startPageId
    private var bookTitleRaw: String = ""
    // Serialized loads: rapid prev/next taps, last-tapped wins.
    private var loadJob: Job? = null
    private var prefetchJob: Job? = null
    // Last raw chapter for no-network language re-render.
    private var currentRaw: Parser.ChapterContent? = null

    init {
        viewModelScope.launch {
            _simplified.value = prefs.simplified.first()
            _fontScale.value = prefs.fontScale.first()
            _theme.value = prefs.theme.first()
            // Single collector + tocGen invalidation: a late emit from the
            // cancelled run is dropped by generation check, so it can only
            // touch totals/book title via the current run, never stale content.
            revalidateToc()
            load(startPosition)
        }
    }

    companion object {
        /**
         * Stable pageId wins over display position (pure + tested). A missed
         * non-zero pageId means a deleted chapter: yield -1 so the caller
         * takes its out-of-range Error path instead of aliasing to the
         * neighbor now sitting at the requested position. Position fallback
         * is pre-v4 rows only (pageId == 0).
         */
        fun resolveEffectivePosition(
            chapters: List<Parser.ChapterRef>,
            requestedPosition: Int,
            requestedPageId: Long,
        ): Int {
            if (requestedPageId != 0L) {
                chapters.firstOrNull { it.pageId == requestedPageId }?.let { return it.position }
                return -1
            }
            return requestedPosition
        }
    }

    private fun render(raw: Parser.ChapterContent, simplified: Boolean): Triple<String, String, String> =
        if (simplified) Triple(t2s.convert(raw.book), t2s.convert(raw.title), t2s.convert(raw.text))
        else Triple(raw.book, raw.title, raw.text)

    /**
     * Cancel + relaunch the one producer. The reset to Loading makes
     * awaiters re-suspend; the fresh attempt is bounded by SiteApi's own
     * deadline (no external timeout). Late emits from the cancelled run are
     * dropped by the tocGen check (cancel stops the fetch, gen drops the race).
     * This is today's "blocking fetch doubles as the revalidation" retry,
     * expressed as one bounded restart.
     */
    private fun revalidateToc() {
        tocJob?.cancel()
        tocGen++
        val myGen = tocGen
        _toc.value = TocState.Loading
        tocJob = viewModelScope.launch {
            tocSource.toc(bookId).collect { st ->
                if (myGen != tocGen) return@collect
                _toc.value = st
                if (st is TocState.Ready) {
                    if (st.meta.title.isNotEmpty()) setBookTitle(st.meta.title)
                    if (st.phase == TocState.Phase.Fresh) setTotal(st.chapters.size)
                }
            }
        }
    }

    /**
     * One consistent TOC generation for this load: returns the painted cache
     * (Syncing) immediately for fast open; when the producer has terminally
     * failed with no cache, gives it exactly one restart (today's fetch retry).
     * Combined with awaitFreshAttempt's miss-restart, one load() does at most
     * 2 fetches, never a loop. Returns Ready, or null after painting Ui.Error
     * (retry re-enters with the Error pageId, never a loop).
     */
    private suspend fun awaitGeneration(position: Int, resolveId: Long): TocState.Ready? {
        var st = _toc.value
        if (st is TocState.Loading) st = _toc.first { it !is TocState.Loading }
        if (st is TocState.Failed) {
            revalidateToc()
            st = _toc.first { it !is TocState.Loading }
        }
        return (st as? TocState.Ready) ?: run {
            val failed = st as TocState.Failed
            _ui.value = Ui.Error(
                position = position,
                total = _ui.value.total,
                message = failed.message,
                kind = ReaderErrorKind.Network,
                pageId = resolveId,
            )
            null
        }
    }

    /**
     * Bounded fresh attempt(s) when the target misses the current generation
     * (TOC shift between Detail tap and Reader open). Syncing: the attempt is
     * already in flight — wait for its terminal (isTerminal, not just
     * non-Syncing — Loading from a concurrent restart must not short-circuit).
     * Stale: it landed failed — restart once and wait for the new terminal
     * (not the Syncing paint). Fresh: the pageId is genuinely gone, no fetch
     * changes that. Combined with awaitGeneration's no-cache restart, one load()
     * does at most 2 fetches, never a loop.
     */
    private suspend fun awaitFreshAttempt(gen: TocState.Ready): TocState.Ready? = when (gen.phase) {
        TocState.Phase.Syncing ->
            _toc.first { isTerminal(it) } as? TocState.Ready
        TocState.Phase.Stale -> {
            revalidateToc()
            _toc.first { isTerminal(it) } as? TocState.Ready
        }
        TocState.Phase.Fresh -> null
    }

    private fun paintBoundsError(position: Int, total: Int, pageId: Long) {
        val kind = ReaderErrors.boundsKind(position)
        _ui.value = Ui.Error(
            position = position,
            total = total,
            message = if (kind == ReaderErrorKind.Deleted) ReaderErrors.deletedMessage() else "章節超出範圍",
            kind = kind,
            pageId = pageId,
        )
    }

    fun load(position: Int, pageId: Long = 0L) {
        loadJob?.cancel()
        prefetchJob?.cancel()
        // pendingPageId consumed synchronously on entry (Main): retry carries
        // the failed chapter's pageId explicitly and failure paints Error.pageId,
        // so pending must not survive for retry — otherwise a rapid second load
        // steals the first load's id and resolves to the wrong chapter.
        val resolveId = if (pageId != 0L) { pendingPageId = 0L; pageId } else { val r = pendingPageId; pendingPageId = 0L; r }
        var errorPageId = resolveId
        var errorPos = position
        loadJob = viewModelScope.launch {
            _ui.value = Ui.Loading(position = position, total = _ui.value.total)
            try {
                val gen = awaitGeneration(position, resolveId) ?: return@launch
                // The generation is owned by this load until done: every read
                // below goes through this snapshot, never a shared mutable field.
                var snapshot = gen.chapters
                var effective = resolveEffectivePosition(snapshot, position, resolveId)
                if (effective < 1 || effective > snapshot.size) {
                    // Target misses this generation (shift between Detail tap and
                    // open): one bounded fresh attempt, then re-resolve — the same
                    // rescue the blocking fetch used to provide, deterministic.
                    val fresh = awaitFreshAttempt(gen)
                    if (fresh == null) {
                        paintBoundsError(effective, snapshot.size, resolveId)
                        return@launch
                    }
                    snapshot = fresh.chapters
                    effective = resolveEffectivePosition(snapshot, position, resolveId)
                    if (effective < 1 || effective > snapshot.size) {
                        paintBoundsError(effective, snapshot.size, resolveId)
                        return@launch
                    }
                }
                // From here the target chapter is fixed: track it for catch/retry
                // so a fetch failure re-opens the same pageId, not the stale arg.
                errorPos = effective
                errorPageId = snapshot[effective - 1].pageId
                val ref = snapshot[effective - 1]
                // Room cache first (by stable pageId), else network (then save raw).
                val cached = repo.cachedChapterContent(bookId, ref.pageId)
                val raw = if (cached != null) {
                    // Reconstruct nav from TOC positions (shape-validated chapter URLs only).
                    // Book comes from TOC meta via [ReaderTitle]: never ref.title.
                    Parser.ChapterContent(
                        book = ReaderTitle.resolve(bookTitleRaw, "", (_ui.value as? Ui.Content)?.book.orEmpty()),
                        title = ref.title, text = cached,
                        prevUrl = snapshot.getOrNull(effective - 2)?.url,
                        tocUrl = null,
                        nextUrl = snapshot.getOrNull(effective)?.url,
                    )
                } else {
                    val fetched = repo.chapter(ref.url)
                    // Backfill the authoritative name when TOC meta was empty
                    // (offline edge) but the chapter page knows the book.
                    if (bookTitleRaw.isEmpty() && fetched.book.isNotEmpty()) {
                        setBookTitle(fetched.book)
                    }
                    val withBook = if (fetched.book.isEmpty() && bookTitleRaw.isNotEmpty()) {
                        fetched.copy(book = bookTitleRaw)
                    } else fetched
                    // PageId-keyed write: correct even if a TOC refresh lands mid-fetch.
                    withBook.also {
                        repo.saveChapterContent(bookId, ref.pageId, it.text)
                    }
                }
                currentRaw = raw
                val (book, title, text) = render(raw, _simplified.value)
                // Total follows the latest accepted Fresh generation — read HERE,
                // after the fetch, not before it: a Fresh that landed mid-fetch
                // already bumped the Loading total via setTotal, and painting a
                // pre-fetch snapshot.size would clobber it back (and false-AtEnd
                // next()). Shrink is rejected by the guard, so Fresh is only ever
                // >= snapshot.size. No suspend sits between this read and the paint.
                val freshTotal = (_toc.value as? TocState.Ready)
                    ?.takeIf { it.phase == TocState.Phase.Fresh }?.chapters?.size ?: 0
                _ui.value = Ui.Content(
                    position = effective,
                    total = maxOf(snapshot.size, freshTotal),
                    book = book, title = title, text = text,
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
                prefetchNext5(effective, snapshot)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Retry the resolved chapter, not the stale tap arg: after a
                // TOC shift the arg names a neighbor (see errorPos/errorPageId).
                val cur = _ui.value
                _ui.value = Ui.Error(
                    position = errorPos,
                    total = cur.total,
                    message = Errors.friendly(e),
                    pageId = errorPageId,
                )
            }
        }
    }

    /** Auto-cache the next 5 chapters, sequential with crawl delay, silent-fail. */
    private fun prefetchNext5(from: Int, snapshot: List<Parser.ChapterRef>) {
        prefetchJob?.cancel()
        // Snapshot passed in by the load: the prefetch scans exactly the
        // generation the user is reading from; a background TOC refresh can
        // only touch totals, never this list.
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

    /** Prev chapter: false = already first (caller stays put). Last-tap wins via load(). */
    fun prev(): Boolean {
        val cur = _ui.value
        if (cur.position <= 1) return false
        load(cur.position - 1)
        return true
    }

    /** Next chapter: AtEnd stays put (caller shows snackbar), else loads. */
    fun next(): NextStep {
        val cur = _ui.value
        // Deleted-chapter error carries position -1: clamp to first instead of
        // loading 0 (which would just re-error).
        val target = if (cur.position < 1) 1 else cur.position + 1
        if (cur.total > 0 && target > cur.total) return NextStep.AtEnd
        load(target)
        return NextStep.Started(target)
    }

    fun toggleSimplified() {
        setSimplified(!_simplified.value)
    }

    /** Idempotent set for radio/switch rows: tapping the active option is a no-op. */
    fun setSimplified(v: Boolean) {
        if (v == _simplified.value) return
        // Compute and publish synchronously on the caller (Main) thread:
        // two rapid taps must toggle twice, never read the same stale value.
        _simplified.value = v
        val raw = currentRaw
        // Re-render current chapter without refetch or reload.
        val cur = _ui.value
        if (raw != null && cur is Ui.Content) {
            val (book, title, text) = render(raw, v)
            _ui.value = cur.copy(book = book, title = title, text = text)
        }
        viewModelScope.launch {
            prefs.setSimplified(v)
            if (raw == null) load(_ui.value.position)
        }
    }

    fun font(delta: Float) = setFontScale(_fontScale.value + delta)

    /** Absolute set for the Slider: coerced to [Prefs.FONT_MIN]..[Prefs.FONT_MAX], idempotent. */
    fun setFontScale(v: Float) {
        // Atomic read-modify-write on Main: the DataStore write below
        // suspends, so reading inside the coroutine would let two rapid
        // taps both read the old scale and lose one step.
        val next = Prefs.coerceFontScale(v)
        if (next == _fontScale.value) return
        _fontScale.value = next
        viewModelScope.launch { prefs.setFontScale(next) }
    }

    fun display(raw: String): String =
        Display.text(t2s, raw, _simplified.value)

    /** Converted theme-mode label for the settings menu. */
    fun themeLabel(): String = display(when (_theme.value) {
        Prefs.LIGHT -> "主題：淺色"
        Prefs.DARK -> "主題：深色"
        else -> "主題：自動"
    })

    /** Cycle system → light → dark theme. Applied app-wide via prefs. */
    fun cycleTheme() {
        setTheme(Prefs.next(_theme.value))
    }

    /** Idempotent set for radio rows: tapping the active mode is a no-op. */
    fun setTheme(mode: String) {
        val next = Prefs.normalizeTheme(mode)
        if (next == _theme.value) return
        _theme.value = next
        viewModelScope.launch { prefs.setTheme(next) }
    }
}
