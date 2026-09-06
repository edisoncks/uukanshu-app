package cc.uukanshu

import cc.uukanshu.data.download.BookDownloadManager
import cc.uukanshu.data.parse.Parser
import cc.uukanshu.data.repo.BookRepo
import cc.uukanshu.data.update.ApkDownloader
import cc.uukanshu.data.update.DownloadStatus
import cc.uukanshu.data.update.ReleaseFetcher
import cc.uukanshu.data.update.UpdateInfo
import cc.uukanshu.data.convert.T2S
import cc.uukanshu.di.AppContainer
import cc.uukanshu.di.PrefsApi
import cc.uukanshu.di.RepoApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory fakes proving the DI seam: VMs accept these without an Application. */
class FakeRepo(
    var cached: BookRepo.Detail? = null,
    var fresh: BookRepo.Detail? = null,
    var failure: Exception? = null,
) : RepoApi {
    override suspend fun category(categoryId: Int, page: Int) = emptyList<Parser.BookItem>()
    override suspend fun recent(page: Int) = emptyList<Parser.BookItem>()
    override suspend fun search(keyword: String) = Parser.SearchResult(null, emptyList())
    override suspend fun cachedDetail(bookId: String) = cached
    override suspend fun detail(bookId: String): BookRepo.Detail =
        failure?.let { throw it } ?: fresh ?: throw java.io.IOException("no fresh")
    override suspend fun chapter(url: String) =
        Parser.ChapterContent("", "", "", null, null, null)
    override suspend fun cachedChapterContent(bookId: String, pageId: Long): String? = null
    override fun cachedPositionsFlow(bookId: String): Flow<Set<Long>> = flowOf(emptySet())
    override suspend fun saveChapterContent(bookId: String, pageId: Long, content: String) = Unit
    override suspend fun saveProgress(bookId: String, position: Int, pageId: Long) = Unit
    override fun bookmarkFlow(bookId: String): Flow<BookRepo.Bookmark?> = flowOf(null)
    override suspend fun getBookmark(bookId: String): BookRepo.Bookmark? = null
    override fun progressFlow(bookId: String): Flow<Int?> = flowOf(null)
    override suspend fun getProgress(bookId: String): Int? = null
    override suspend fun bookEntry(bookId: String): BookRepo.BookInfo? = null
    override suspend fun library() = emptyList<BookRepo.CachedBook>()
    override fun libraryFlow(): Flow<List<BookRepo.CachedBook>> = flowOf(emptyList())
    override suspend fun crawlDelay() = Unit
    override suspend fun downloadAll(bookId: String, onProgress: (Int, Int) -> Unit) = Unit
    override suspend fun deleteBook(bookId: String) = Unit
    override suspend fun clearAll() = Unit
}

class FakePrefs : PrefsApi {
    override val simplified: Flow<Boolean> = flowOf(false)
    override val fontScale: Flow<Float> = flowOf(1f)
    override val theme: Flow<String> = flowOf("system")
    override val lastUpdateCheck: Flow<Long> = flowOf(0L)
    override val skippedVersion: Flow<String?> = flowOf(null)
    override suspend fun setSimplified(v: Boolean) = Unit
    override suspend fun setFontScale(v: Float) = Unit
    override suspend fun setTheme(v: String) = Unit
    override suspend fun setLastUpdateCheck(now: Long) = Unit
    override suspend fun setSkippedVersion(v: String?) = Unit
}

class FakeReleaseFetcher(var info: UpdateInfo? = null) : ReleaseFetcher {
    override fun fetchLatest(): UpdateInfo =
        info ?: throw java.io.IOException("no release")
}

class FakeApkDownloader : ApkDownloader {
    override fun apkFile(info: UpdateInfo): java.io.File =
        java.io.File.createTempFile("uukanshu-test", ".apk")
    override fun enqueue(info: UpdateInfo): Long = -1L
    override fun cancel(downloadId: Long) = Unit
    override fun observe(downloadId: Long): kotlinx.coroutines.flow.Flow<DownloadStatus> =
        flowOf(DownloadStatus.Success)
}

class FakeContainer(
    repo: RepoApi = FakeRepo(),
    prefs: PrefsApi = FakePrefs(),
    t2s: T2S = T2S(),
    downloads: BookDownloadManager = BookDownloadManager({ _, _ -> }),
    releaseApi: ReleaseFetcher = FakeReleaseFetcher(),
    apkDownloader: ApkDownloader = FakeApkDownloader(),
) : AppContainer {
    override val repo: RepoApi = repo
    override val prefs: PrefsApi = prefs
    override val t2s: T2S = t2s
    override val downloads: BookDownloadManager = downloads
    override val releaseApi: ReleaseFetcher = releaseApi
    override val apkDownloader: ApkDownloader = apkDownloader
}

class ContainerSeamTest {
    private fun meta() = Parser.BookMeta("T", "A", "", "", "", "", "", null, "")

    @Test fun fakeRepoServesCacheWhenNetworkFails() = runBlocking {
        val cachedDetail = BookRepo.Detail(
            meta(),
            listOf(Parser.ChapterRef(1, 101L, "c1", "https://uukanshu.cc/book/1/101.html")),
        )
        val repo = FakeRepo(cached = cachedDetail, failure = java.io.IOException("offline"))
        val container: AppContainer = FakeContainer(repo = repo)
        // Seam check without Android: cache paints, network throws → offline path.
        assertEquals(cachedDetail, container.repo.cachedDetail("1"))
        try {
            container.repo.detail("1")
            throw AssertionError("must throw")
        } catch (e: java.io.IOException) {
            assertEquals("offline", e.message)
        }
    }

    @Test fun realConvertAppliesAtRender() {
        val container: AppContainer = FakeContainer()
        assertEquals("生命不息，奋斗不止", container.t2s.convert("生命不息，奮鬥不止"))
        assertTrue(!container.downloads.isDownloading("1"))
    }
}
