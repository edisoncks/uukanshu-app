package cc.uukanshu

import cc.uukanshu.data.parse.Parser
import cc.uukanshu.data.repo.BookRepo
import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.di.PrefsApi
import cc.uukanshu.di.RepoApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Test

fun testMeta(title: String = "T") =
    Parser.BookMeta(title, "A", "", "", "", "", "", null, "")

fun testRef(pos: Int, pageId: Long) =
    Parser.ChapterRef(pos, pageId, "t-$pos", "https://uukanshu.cc/book/1/$pageId.html")

fun testDetail(vararg ids: Long, title: String = "T"): BookRepo.Detail =
    BookRepo.Detail(testMeta(title), ids.mapIndexed { i, id -> testRef(i + 1, id) })

/** Mutable repo fake for VM orchestration tests (defaults: everything empty, no failure). */
class MutableFakeRepo(
    var cached: BookRepo.Detail? = null,
    var fresh: BookRepo.Detail? = null,
    var failure: Exception? = null,
    var chaptersText: MutableMap<Long, String> = mutableMapOf(),
    var searchResult: Parser.SearchResult = Parser.SearchResult(null, emptyList()),
    var searchFailure: Exception? = null,
    var libraryRows: List<BookRepo.CachedBook> = emptyList(),
    var libraryFlowRows: List<BookRepo.CachedBook> = emptyList(),
    var libraryFailure: Exception? = null,
    var libraryFlowFailure: Exception? = null,
) : RepoApi {
    val savedProgress = mutableListOf<Triple<String, Int, Long>>()
    val savedContent = mutableListOf<Triple<String, Long, String>>()
    var downloadAllCalls = 0
    var deleted = mutableListOf<String>()
    var cleared = 0

    override suspend fun category(categoryId: Int, page: Int) = emptyList<Parser.BookItem>()
    override suspend fun recent(page: Int) = emptyList<Parser.BookItem>()
    override suspend fun search(keyword: String): Parser.SearchResult =
        searchFailure?.let { throw it } ?: searchResult
    override suspend fun cachedDetail(bookId: String) = cached
    override suspend fun detail(bookId: String): BookRepo.Detail =
        failure?.let { throw it } ?: fresh ?: throw java.io.IOException("no fresh stub")
    override suspend fun chapter(url: String): Parser.ChapterContent {
        val pageId = Regex("""/(\d+)\.html""").find(url)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val text = chaptersText[pageId] ?: "text-$pageId"
        return Parser.ChapterContent(book = "T", title = "c", text = text, prevUrl = null, tocUrl = null, nextUrl = null)
    }
    override suspend fun cachedChapterContent(bookId: String, pageId: Long): String? =
        chaptersText[pageId]
    override fun cachedPositionsFlow(bookId: String): Flow<Set<Long>> =
        flowOf(chaptersText.keys.toSet())
    override suspend fun saveChapterContent(bookId: String, pageId: Long, content: String) {
        savedContent += Triple(bookId, pageId, content)
        chaptersText[pageId] = content
    }
    override suspend fun saveProgress(bookId: String, position: Int, pageId: Long) {
        savedProgress += Triple(bookId, position, pageId)
    }
    override fun bookmarkFlow(bookId: String): Flow<BookRepo.Bookmark?> = flowOf(null)
    override suspend fun getBookmark(bookId: String): BookRepo.Bookmark? = null
    override fun progressFlow(bookId: String): Flow<Int?> = flowOf(null)
    override suspend fun getProgress(bookId: String): Int? = null
    override suspend fun bookEntry(bookId: String): BookRepo.BookInfo? =
        cached?.let { BookRepo.BookInfo(bookId, it.meta.title) }
    override suspend fun library(): List<BookRepo.CachedBook> =
        libraryFailure?.let { throw it } ?: libraryRows
    override fun libraryFlow(): Flow<List<BookRepo.CachedBook>> =
        libraryFlowFailure?.let { throw it } ?: flowOf(libraryFlowRows)
    override suspend fun crawlDelay() = Unit
    override suspend fun downloadAll(bookId: String, onProgress: (Int, Int) -> Unit) {
        downloadAllCalls++
    }
    override suspend fun deleteBook(bookId: String) {
        deleted += bookId
    }
    override suspend fun clearAll() {
        cleared++
    }
}

class MutableFakePrefs(
    simplified: Boolean = false,
    lastCheck: Long = 0L,
    val started: MutableList<String> = mutableListOf(),
    val skipped: MutableList<String?> = mutableListOf(),
) : PrefsApi {
    /** Last value passed to setLastUpdateCheck (null = never called). */
    var lastCheckSet: Long? = null
    private val _simplified = MutableStateFlow(simplified)
    override val simplified: Flow<Boolean> = _simplified
    private val _theme = MutableStateFlow(Prefs.SYSTEM)
    override val theme: Flow<String> = _theme
    override val fontScale: Flow<Float> = flowOf(1f)
    override val lastUpdateCheck: Flow<Long> = flowOf(lastCheck)
    override val skippedVersion: Flow<String?> = flowOf(null)
    override suspend fun setSimplified(v: Boolean) {
        started += "simplified=$v"
        _simplified.value = v
    }
    override suspend fun setFontScale(v: Float) = Unit
    override suspend fun setTheme(v: String) {
        // Mirror production write-normalization so tests cannot pass on the
        // fake while failing on Prefs (see PrefsStoreTest.themeWriteNormalizesUnknown).
        _theme.value = Prefs.normalizeTheme(v)
    }
    override suspend fun setLastUpdateCheck(now: Long) {
        lastCheckSet = now
    }
    override suspend fun setSkippedVersion(v: String?) {
        skipped += v
    }
}

/** Fake fidelity: MutableFakePrefs must mirror production write contracts. */
class MutableFakePrefsContractTest {
    @Test fun themeWriteNormalizesLikeProduction() = kotlinx.coroutines.runBlocking {
        val p = MutableFakePrefs()
        p.setTheme("dark-mode")
        assertEquals(Prefs.SYSTEM, p.theme.first())
        p.setTheme(Prefs.DARK)
        assertEquals(Prefs.DARK, p.theme.first())
    }
}
