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
import cc.uukanshu.data.convert.T2S
import cc.uukanshu.data.parse.Parser
import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.app
import cc.uukanshu.ui.ThemeIconButton
import cc.uukanshu.ui.vmFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ReaderViewModel(
    private val repo: cc.uukanshu.data.repo.BookRepo,
    private val t2s: T2S,
    private val prefs: Prefs,
    private val bookId: String,
    startPosition: Int,
) : ViewModel() {
    companion object {
        /**
         * Guard against TOC-shift aliasing: only save fetched text when the
         * live TOC still maps [position] to [expectedPageId]. A background
         * revalidate may have swapped the list mid-fetch; writing by bare
         * position would then file text under the wrong chapter.
         */
        fun shouldSaveChapter(
            current: List<Parser.ChapterRef>,
            position: Int,
            expectedPageId: Long,
        ): Boolean = current.getOrNull(position - 1)?.pageId == expectedPageId
    }

    data class Ui(
        val position: Int = 1,
        val total: Int = 0,
        val book: String = "",
        val title: String = "",
        val text: String = "",
        val loading: Boolean = true,
        val error: String? = null,
        val simplified: Boolean = false,
        val fontScale: Float = 1f,
        val theme: String = Prefs.SYSTEM,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui
    private var chapters: List<Parser.ChapterRef> = emptyList()
    private var bookTitleRaw: String = ""
    // Serialized chapter loads: rapid prev/next taps must not overlap —
    // last-tapped wins, never last-to-finish.
    private var loadJob: Job? = null
    private var prefetchJob: Job? = null
    private var revalidateJob: Job? = null
    // Last rendered raw chapter: language toggle re-renders from this with
    // no network, no spinner, and no extra prefetch spawn.
    private var currentRaw: Parser.ChapterContent? = null
    // Authoritative book name from TOC meta (raw Traditional, converted at
    // render). Cached chapters have no network payload, so they must use
    // this — never ref.title (chapter title) nor stale UI state.

    init {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(
                simplified = prefs.simplified.first(),
                fontScale = prefs.fontScale.first(),
                theme = prefs.theme.first(),
            )
            load(startPosition)
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
            _ui.value = _ui.value.copy(loading = true, error = null, position = position)
            try {
                if (chapters.isEmpty()) {
                    // Stale-while-revalidate for TOC: paint cached TOC instantly
                    // so cached chapters render without waiting for network,
                    // then refresh silently in the background.
                    val cachedToc = try {
                        repo.cachedDetail(bookId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (cachedToc != null) {
                        chapters = cachedToc.chapters
                        if (cachedToc.meta.title.isNotEmpty()) bookTitleRaw = cachedToc.meta.title
                    }
                    if (chapters.isEmpty() || position < 1 || position > chapters.size) {
                        // First open (no cache) or stale TOC can't cover the
                        // requested position: blocking fetch before rendering.
                        // This fetch doubles as the revalidation — no extra
                        // background request, so no concurrent double-fetch.
                        try {
                            val fresh = repo.detail(bookId)
                            chapters = fresh.chapters
                            if (fresh.meta.title.isNotEmpty()) bookTitleRaw = fresh.meta.title
                            _ui.value = _ui.value.copy(total = chapters.size)
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            // Offline with cache: keep stale TOC if we have it.
                            if (chapters.isEmpty()) throw e
                        }
                    } else if (cachedToc != null) {
                        // Serving from stale TOC: revalidate silently.
                        // Never blocks reading, never wipes content (mergeToc
                        // preserves downloads by pageId).
                        revalidateJob = viewModelScope.launch {
                            runCatching { repo.detail(bookId) }.onSuccess { fresh ->
                                // Empty TOC is a failed parse, never a real book:
                                // accepting it would zero `total` mid-read and turn
                                // the next tap into a bogus "out of range".
                                if (fresh.chapters.isEmpty()) return@onSuccess
                                chapters = fresh.chapters
                                if (fresh.meta.title.isNotEmpty()) bookTitleRaw = fresh.meta.title
                                _ui.value = _ui.value.copy(total = chapters.size)
                            }
                        }
                    }
                }
                val total = chapters.size
                if (position < 1 || position > total) {
                    _ui.value = _ui.value.copy(loading = false, total = total, error = "out of range")
                    return@launch
                }
                val ref = chapters[position - 1]
                // Room cache first, else network (then save raw).
                val cached = repo.cachedChapterContent(bookId, position)
                val raw = if (cached != null) {
                    // Reconstruct nav from TOC positions (shape-validated chapter URLs only).
                    // Book comes from TOC meta via [ReaderTitle]: never ref.title.
                    Parser.ChapterContent(
                        book = ReaderTitle.resolve(bookTitleRaw, "", _ui.value.book),
                        title = ref.title, text = cached,
                        prevUrl = chapters.getOrNull(position - 2)?.url,
                        tocUrl = null,
                        nextUrl = chapters.getOrNull(position)?.url,
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
                    // Skip the write when a background revalidate shifted the TOC
                    // mid-fetch (position now names a different pageId).
                    if (shouldSaveChapter(chapters, position, ref.pageId)) {
                        withBook.also {
                            repo.saveChapterContent(bookId, position, it.text)
                        }
                    } else withBook
                }
                currentRaw = raw
                val (book, title, text) = render(raw, _ui.value.simplified)
                _ui.value = _ui.value.copy(
                    book = book, title = title, text = text,
                    total = total, loading = false,
                )
                // Silent auto-bookmark: never break reading on save failure
                // (cancellation still propagates).
                try {
                    repo.saveProgress(bookId, position)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
                prefetchNext5(position)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _ui.value = _ui.value.copy(
                    loading = false,
                    error = "${e.javaClass.simpleName}: ${e.message}",
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
                if (repo.cachedChapterContent(bookId, pos) != null) continue
                if (fetchedAny) repo.crawlDelay()
                try {
                    val ref = snapshot[pos - 1]
                    val text = repo.chapter(ref.url).text
                    // Live TOC moved under us: skip instead of filing text
                    // under the wrong chapter (self-heals on next open).
                    if (!shouldSaveChapter(chapters, pos, ref.pageId)) continue
                    repo.saveChapterContent(bookId, pos, text)
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
        if (raw != null) {
            // Re-render current chapter without refetch or reload.
            val (book, title, text) = render(raw, next)
            _ui.value = _ui.value.copy(simplified = next, book = book, title = title, text = text)
        } else {
            _ui.value = _ui.value.copy(simplified = next)
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
        val next = (_ui.value.fontScale + delta).coerceIn(0.8f, 1.6f)
        _ui.value = _ui.value.copy(fontScale = next)
        viewModelScope.launch { prefs.setFontScale(next) }
    }

    fun display(raw: String): String =
        if (_ui.value.simplified) t2s.convert(raw) else raw

    /** Converted theme-mode label for the settings menu. */
    fun themeLabel(): String = display(when (_ui.value.theme) {
        Prefs.LIGHT -> "主題：淺色"
        Prefs.DARK -> "主題：深色"
        else -> "主題：自動"
    })

    /** Cycle system → light → dark theme. Applied app-wide via prefs. */
    fun cycleTheme() {
        val next = Prefs.next(_ui.value.theme)
        _ui.value = _ui.value.copy(theme = next)
        viewModelScope.launch { prefs.setTheme(next) }
    }
}

@Composable
fun ReaderScreen(bookId: String, position: Int) {
    val ctx = LocalContext.current
    val app = ctx.app()
    // Keyed on bookId only: paging reuses this VM via load(), and the nav
    // graph holds at most one reader per book, so position is just the
    // initial load argument, not an identity.
    val vm: ReaderViewModel = viewModel(
        key = "reader-$bookId",
        factory = vmFactory {
            ReaderViewModel(app.repo, app.t2s, app.prefs, bookId, position)
        },
    )
    val ui by vm.ui.collectAsState()
    val snacks = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        // Reading content flexes; single sticky bottom bar (Option C:
        // [⋯ | prev | next]) stays pinned so no duplicate nav row is needed.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                ui.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(ui.error!!)
                        Button({ vm.load(ui.position) }, Modifier.padding(top = 12.dp)) { Text(vm.display("重試")) }
                    }
                }
                else -> {
                    val scroll = rememberScrollState()
                    // Paging chapters reuses this composition: jump to top.
                    LaunchedEffect(ui.position) { runCatching { scroll.scrollTo(0) } }
                    Column(
                        Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
                    ) {
                        if (ui.book.isNotEmpty()) Text(ui.book, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${ui.position}/${ui.total} ${ui.title}",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                        Text(ui.text, fontSize = (17 * ui.fontScale).sp, lineHeight = (28 * ui.fontScale).sp)
                    }
                }
            }
            SnackbarHost(snacks, Modifier.align(Alignment.BottomCenter).padding(8.dp))
        }
        // Single sticky bottom nav: settings in ⋯ (left, out of thumb way),
        // prev/next dominate the thumb zone.
        Surface(tonalElevation = 3.dp) {
            Column {
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        TextButton(onClick = { menu = true }) {
                            Text("⋯", fontSize = 20.sp)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(if (ui.simplified) vm.display("簡體 ✓") else vm.display("繁體 ✓")) },
                                onClick = { vm.toggleSimplified(); menu = false },
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(vm.display("字體"))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { vm.font(-0.1f) }) { Text("A-") }
                                    TextButton(onClick = { vm.font(0.1f) }) { Text("A+") }
                                }
                            }
                            DropdownMenuItem(
                                text = { Text(vm.themeLabel()) },
                                trailingIcon = {
                                    ThemeIconButton(ui.theme, { vm.cycleTheme() }, vm::display)
                                },
                                onClick = { vm.cycleTheme() },
                            )
                        }
                    }
                    Button(
                        onClick = { vm.load(ui.position - 1) },
                        enabled = !ui.loading && ui.position > 1,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(vm.display("上一章"))
                    }
                    Button(
                        onClick = {
                            if (ui.position >= ui.total && ui.total > 0) {
                                scope.launch { snacks.showSnackbar("已是最新一章 / end of book") }
                            } else vm.load(ui.position + 1)
                        },
                        enabled = !ui.loading,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(vm.display("下一章"))
                    }
                }
            }
        }
    }
}
