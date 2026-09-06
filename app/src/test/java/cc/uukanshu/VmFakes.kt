package cc.uukanshu

import cc.uukanshu.data.download.BookDownloadManager
import cc.uukanshu.data.parse.Parser
import cc.uukanshu.data.repo.BookRepo
import cc.uukanshu.di.ConvertApi
import cc.uukanshu.di.DownloadsApi
import cc.uukanshu.di.PrefsApi
import cc.uukanshu.di.RepoApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

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
    private val _simplified = MutableStateFlow(simplified)
    override val simplified: Flow<Boolean> = _simplified
    override val fontScale: Flow<Float> = flowOf(1f)
    override val theme: Flow<String> = flowOf("system")
    override val lastUpdateCheck: Flow<Long> = flowOf(lastCheck)
    override val skippedVersion: Flow<String?> = flowOf(null)
    override suspend fun setSimplified(v: Boolean) {
        started += "simplified=$v"
        _simplified.value = v
    }
    override suspend fun setFontScale(v: Float) = Unit
    override suspend fun setTheme(v: String) = Unit
    override suspend fun setLastUpdateCheck(now: Long) = Unit
    override suspend fun setSkippedVersion(v: String?) {
        skipped += v
    }
}

class RecordingDownloads : DownloadsApi {
    private val _states = MutableStateFlow<Map<String, BookDownloadManager.State>>(emptyMap())
    override val states: StateFlow<Map<String, BookDownloadManager.State>> = _states.asStateFlow()
    val started = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    val forgotten = mutableListOf<String>()
    var forgetAllCalls = 0

    fun publish(bookId: String, state: BookDownloadManager.State) {
        _states.update { it + (bookId to state) }
    }

    override fun observe(bookId: String): Flow<BookDownloadManager.State?> =
        _states.map { it[bookId] }
    override fun isDownloading(bookId: String) = _states.value[bookId]?.downloading == true
    override fun start(bookId: String) {
        started += bookId
    }
    override fun cancel(bookId: String) {
        cancelled += bookId
    }
    override fun forget(bookId: String) {
        forgotten += bookId
    }
    override fun forgetAll() {
        forgetAllCalls++
    }
}

class TestConvert : ConvertApi {
    override fun convert(s: String) = "S:$s"
}
