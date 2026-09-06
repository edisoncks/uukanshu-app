package cc.uukanshu.di

import androidx.compose.runtime.staticCompositionLocalOf
import cc.uukanshu.App
import cc.uukanshu.data.convert.T2S
import cc.uukanshu.data.download.BookDownloadManager
import cc.uukanshu.data.parse.Parser
import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.data.repo.BookRepo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Manual constructor DI (no framework).
 *
 * Screens used to call `ctx.app()` and pass `app.repo / app.prefs / app.t2s`
 * positionally into six hand-wired `vmFactory { … }` blocks. That hid what
 * each screen needs, made ViewModels untestable without an Application, and
 * blocked Compose previews. ViewModels now depend on these narrow
 * interfaces; [AppContainer] is provided once in MainActivity via
 * [LocalContainer] and faked in JVM tests / previews.
 *
 * If the app ever grows to multiple modules or needs scoped workers, each
 * [AppContainer] val maps 1:1 to a Hilt `@Module` — no redesign needed.
 */

interface RepoApi {
    suspend fun category(categoryId: Int, page: Int): List<Parser.BookItem>
    suspend fun recent(page: Int): List<Parser.BookItem>
    suspend fun search(keyword: String): Parser.SearchResult
    suspend fun cachedDetail(bookId: String): BookRepo.Detail?
    suspend fun detail(bookId: String): BookRepo.Detail
    suspend fun chapter(url: String): Parser.ChapterContent
    suspend fun cachedChapterContent(bookId: String, pageId: Long): String?
    fun cachedPositionsFlow(bookId: String): Flow<Set<Long>>
    suspend fun saveChapterContent(bookId: String, pageId: Long, content: String)
    suspend fun saveProgress(bookId: String, position: Int, pageId: Long = 0L)
    fun bookmarkFlow(bookId: String): Flow<BookRepo.Bookmark?>
    suspend fun getBookmark(bookId: String): BookRepo.Bookmark?
    suspend fun getProgress(bookId: String): Int?
    fun progressFlow(bookId: String): Flow<Int?>
    suspend fun bookEntry(bookId: String): cc.uukanshu.data.db.BookEntity?
    suspend fun library(): List<BookRepo.CachedBook>
    suspend fun crawlDelay()
    suspend fun downloadAll(bookId: String, onProgress: (Int, Int) -> Unit)
    suspend fun deleteBook(bookId: String)
    suspend fun clearAll()
}

interface PrefsApi {
    val simplified: Flow<Boolean>
    val fontScale: Flow<Float>
    val theme: Flow<String>
    val lastUpdateCheck: Flow<Long>
    val skippedVersion: Flow<String?>
    suspend fun setSimplified(v: Boolean)
    suspend fun setFontScale(v: Float)
    suspend fun setTheme(v: String)
    suspend fun setLastUpdateCheck(now: Long)
    suspend fun setSkippedVersion(v: String?)
}

interface ConvertApi {
    fun convert(s: String): String
}

interface DownloadsApi {
    val states: StateFlow<Map<String, BookDownloadManager.State>>
    fun observe(bookId: String): Flow<BookDownloadManager.State?>
    fun isDownloading(bookId: String): Boolean
    fun start(bookId: String)
    fun cancel(bookId: String)
    fun forget(bookId: String)
    fun forgetAll()
}

interface AppContainer {
    val repo: RepoApi
    val prefs: PrefsApi
    val t2s: ConvertApi
    val downloads: DownloadsApi
}

class RealAppContainer(app: App) : AppContainer {
    // App singletons already implement the narrow interfaces (see `: RepoApi`
    // on BookRepo etc.), so this is delegation with zero new instances.
    override val repo: RepoApi = app.repo as RepoApi
    override val prefs: PrefsApi = app.prefs as PrefsApi
    override val t2s: ConvertApi = app.t2s as ConvertApi
    override val downloads: DownloadsApi = app.downloadManager as DownloadsApi
}

val LocalContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer not provided — wrap in CompositionLocalProvider(LocalContainer provides …)")
}
