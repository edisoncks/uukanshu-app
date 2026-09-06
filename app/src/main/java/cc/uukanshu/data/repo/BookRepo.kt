package cc.uukanshu.data.repo

import cc.uukanshu.core.Errors
import cc.uukanshu.data.db.AppDb
import cc.uukanshu.data.db.BookEntity
import cc.uukanshu.data.db.ChapterEntity
import cc.uukanshu.data.db.ProgressEntity
import cc.uukanshu.data.net.SiteGateway
import cc.uukanshu.data.parse.Parser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Network-first facade with Room fallback. Raw Traditional cached; T2S at render.
 * Pure rules in TocMerge/ShelfOrder/BookmarkResolve. See ARCHITECTURE.md.
 */
class BookRepo(
    private val site: SiteGateway,
    private val db: AppDb,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : cc.uukanshu.di.RepoApi {
    /** Serializes TOC replace vs single-row writes (lost-update guard). */
    private val dbWrite = Mutex()
    override suspend fun category(categoryId: Int, page: Int): List<Parser.BookItem> =
        withContext(ioDispatcher) {
            val html = site.get("${Parser.BASE}/class_${categoryId}_${page}.html")
            Parser.parseCategory(html)
        }

    /**
     * Recently updated, paged: /top/lastupdate_{page}.html uses the same
     * bookbox cards as categories (title/author/words/latest/intro).
     */
    override suspend fun recent(page: Int): List<Parser.BookItem> =
        withContext(ioDispatcher) {
            val html = site.get("${Parser.BASE}/top/lastupdate_${page}.html")
            Parser.parseCategory(html)
        }

    override suspend fun search(keyword: String): Parser.SearchResult =
        withContext(ioDispatcher) {
            val html = site.search(keyword)
            Parser.parseSearch(html)
        }

    data class Detail(val meta: Parser.BookMeta, val chapters: List<Parser.ChapterRef>)

    companion object {
        /** Delegates to [TocMerge.merge] (stable pageId, never position). */
        fun mergeToc(
            bookId: String,
            refs: List<Parser.ChapterRef>,
            cachedByPageId: Map<Long, String>,
        ): List<ChapterEntity> = TocMerge.merge(bookId, refs, cachedByPageId)

        /** Delegates to [ShelfOrder.lastActivity]. */
        fun lastActivity(bookAt: Long, progressAt: Long?): Long =
            ShelfOrder.lastActivity(bookAt, progressAt)

        /** Delegates to [ShelfOrder.preserve]. */
        fun preserveBookUpdatedAt(
            existing: BookEntity?,
            fresh: BookEntity,
            now: Long,
        ): BookEntity = ShelfOrder.preserve(existing, fresh, now)

        /** Delegates to [ShelfOrder.sort]. */
        fun sortShelf(
            books: List<CachedBook>,
            bookAt: Map<String, Long>,
            progressAt: Map<String, Long>,
        ): List<CachedBook> = ShelfOrder.sort(books, bookAt, progressAt)

        /** Polite crawl delay bounds (ms): random 1-3s between chapter fetches. */
        const val CRAWL_DELAY_MIN_MS = 1000L
        const val CRAWL_DELAY_MAX_MS = 3000L

        /** Pure helper for [crawlDelay], testable without sleeping. */
        fun nextCrawlDelayMs(random: Random = Random): Long =
            random.nextLong(CRAWL_DELAY_MIN_MS, CRAWL_DELAY_MAX_MS + 1)

        /** Delegates to [BookmarkResolve.resolve] (pageId-first, never neighbor). */
        fun resolveBookmark(
            chapters: List<Parser.ChapterRef>,
            bookmark: Bookmark?,
        ): Parser.ChapterRef? = BookmarkResolve.resolve(chapters, bookmark)
    }

    data class Bookmark(val position: Int, val pageId: Long)

    /**
     * Book + TOC reconstructed purely from cache. Null when the book was
     * never opened/downloaded — offline reading depends on this.
     */
    override suspend fun cachedDetail(bookId: String): Detail? = withContext(ioDispatcher) {
        val book = db.books().book(bookId) ?: return@withContext null
        val rows = db.chapters().chapters(bookId)
        if (rows.isEmpty()) return@withContext null
        Detail(
            meta = Parser.BookMeta(
                title = book.title,
                author = book.author,
                words = "",
                category = book.category,
                status = "",
                intro = book.intro,
                latestChapterTitle = book.lastChapterTitle,
                latestChapterUrl = null,
                updatedAt = "",
            ),
            chapters = rows.map { Parser.ChapterRef(it.position, it.pageId, it.title, it.url) },
        )
    }

    override suspend fun detail(bookId: String): Detail {
        val url = "${Parser.BASE}/book/$bookId/"
        // Single-flight lives inside SiteApi per HTTP attempt; parse + DB
        // merge run outside the gate so a slow transaction never blocks others.
        val html = withContext(ioDispatcher) { site.get(url) }
        return withContext(ioDispatcher) {
            val meta = Parser.parseBookMeta(html, url)
            val chapters = Parser.parseToc(html, bookId)
        // Empty TOC means a block page / layout change, not an empty book:
        // never wipe the cached chapters on nothing. Return fresh (empty)
        // without touching the DB so offline content survives.
        if (chapters.isEmpty()) return@withContext Detail(meta, chapters)
        // Shrunken TOC is the same failure shape (truncated parse): fail
        // closed before replaceToc can delete downloaded chapters whose
        // pageIds are absent from the short parse. See SCRAPING.md.
        val cachedCount = db.chapters().countByBook(bookId)
        if (!TocRevalidator.shouldAcceptFresh(chapters, cachedCount)) {
            throw TocShrunkException(cachedCount, chapters.size)
        }
        // Preserve downloads + shelf order via AppDb.replaceToc (single transaction).
        Errors.runCatchingExceptCancel {
            dbWrite.withLock {
                val existing = db.books().book(bookId)
                val now = System.currentTimeMillis()
                val book = preserveBookUpdatedAt(
                    existing,
                    BookEntity(bookId, meta.title, meta.author, meta.intro, meta.category, meta.latestChapterTitle),
                    now,
                )
                val skeleton = chapters.map {
                    ChapterEntity(bookId, it.position, it.pageId, it.title, it.url, content = "")
                }
                db.replaceToc(book, skeleton)
            }
        }.onFailure { if (it is CancellationException) throw it }
            Detail(meta, chapters)
        }
    }

    override suspend fun chapter(url: String): Parser.ChapterContent =
        withContext(ioDispatcher) {
            val html = site.get(url)
            Parser.parseChapter(html, url)
        }

    /** Cached text by stable pageId (immune to TOC shifts). */
    override suspend fun cachedChapterContent(bookId: String, pageId: Long): String? =
        withContext(ioDispatcher) {
            db.chapters().chapterContent(bookId, pageId)?.takeIf { it.isNotEmpty() }
        }

    /** Stable ids with downloaded content. */
    override fun cachedPositionsFlow(bookId: String): Flow<Set<Long>> =
        db.chapters().cachedPositionsFlow(bookId).map { it.toSet() }

    /** Content write by stable pageId. */
    override suspend fun saveChapterContent(bookId: String, pageId: Long, content: String) {
        dbWrite.withLock {
            db.chapters().updateContent(bookId, pageId, content)
        }
    }

    /** Auto-bookmark by stable pageId (position = pre-v4 fallback); bumps shelf. */
    override suspend fun saveProgress(bookId: String, position: Int, pageId: Long) {
        withContext(ioDispatcher) {
            val now = System.currentTimeMillis()
            db.progress().upsert(ProgressEntity(bookId, position, pageId, now))
            Errors.suppressExceptCancel { db.books().touch(bookId, now) }
        }
    }

    /** Live bookmark (position + stable pageId) for continue-reading. */
    override fun bookmarkFlow(bookId: String): Flow<Bookmark?> =
        db.progress().progressFlow(bookId).map { it?.let { e -> Bookmark(e.position, e.pageId) } }

    override suspend fun getBookmark(bookId: String): Bookmark? = withContext(ioDispatcher) {
        db.progress().progress(bookId)?.let { Bookmark(it.position, it.pageId) }
    }

    /** Live bookmarked position for the continue-reading button. */
    override fun progressFlow(bookId: String): Flow<Int?> =
        db.progress().progressFlow(bookId).map { it?.position }

    override suspend fun getProgress(bookId: String): Int? = withContext(ioDispatcher) {
        db.progress().progress(bookId)?.position
    }

    /**
     * Domain view of a cached book row for shelf rows of fresh downloads.
     * Returns [BookInfo] (never the Room [BookEntity]) so `data.db` types
     * cannot leak into `ui/`.
     */
    data class BookInfo(
        val id: String,
        val title: String,
        val author: String = "",
        val intro: String = "",
        val category: String = "",
        val lastChapterTitle: String = "",
        val updatedAt: Long = 0L,
    )

    /** Cached book meta (TOC skeleton) for shelf rows of fresh downloads. */
    override suspend fun bookEntry(bookId: String): BookInfo? = withContext(ioDispatcher) {
        db.books().book(bookId)?.let {
            BookInfo(it.id, it.title, it.author, it.intro, it.category, it.lastChapterTitle, it.updatedAt)
        }
    }

    // -- offline library (milestone 7): sequential, no hard cap ------------

    data class CachedBook(
        val id: String,
        val title: String,
        val author: String,
        val total: Int,
        val cached: Int,
        val bytes: Long,
    )

    override suspend fun library(): List<CachedBook> = withContext(ioDispatcher) {
        val rows = db.books().cachedBooks()
        val stats = db.chapters().statsByBook()
        val progressAt = db.progress().all().associate { it.bookId to it.updatedAt }
        // Zero content strings loaded. Books without chapter rows or without
        // cached content stay off the shelf, as before.
        ShelfOrder.assemble(rows, stats, progressAt)
    }

    /**
     * Reactive shelf: books + stats + progress as Flows, assembled by the
     * same pure [ShelfOrder.assemble] as one-shot [library]. The shelf
     * re-renders on read/download bumps without manual refresh; failures
     * are the VM's footer-retry/full-screen split, not an empty list.
     */
    override fun libraryFlow(): Flow<List<CachedBook>> =
        kotlinx.coroutines.flow.combine(
            db.books().cachedBooksFlow(),
            db.chapters().statsByBookFlow(),
            db.progress().allFlow(),
        ) { rows, stats, progress ->
            ShelfOrder.assemble(rows, stats, progress.associate { it.bookId to it.updatedAt })
        }

    /** Polite 1-3s delay between bulk fetches (single taps stay immediate). */
    override suspend fun crawlDelay() {
        kotlinx.coroutines.delay(nextCrawlDelayMs())
    }

    /** Sequential full download; throwing aborts. See SCRAPING.md politeness. */
    override suspend fun downloadAll(bookId: String, onProgress: (Int, Int) -> Unit) {
        // Empty fresh TOC = block page, fall back to cache; fail loudly on neither.
        val chapters = try {
            val fresh = withContext(ioDispatcher) { detail(bookId).chapters }
            if (fresh.isNotEmpty()) fresh
            else {
                withContext(ioDispatcher) { cachedDetail(bookId)?.chapters }
                    ?.takeIf { it.isNotEmpty() }
                    ?: throw java.io.IOException("empty chapter list — try again later")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            withContext(ioDispatcher) { cachedDetail(bookId)?.chapters } ?: throw e
        }
        if (chapters.isEmpty()) throw java.io.IOException("empty chapter list — try again later")
        // In-memory id set avoids N+1 queries; snapshot so concurrent clear can't fake hits.
        val cachedIds = withContext(ioDispatcher) {
            runCatching { db.chapters().cachedPageIds(bookId).toMutableSet() }
                .getOrDefault(mutableSetOf())
        }
        // Pure planning helper keeps the missing-set rule testable.
        @Suppress("UNUSED_VARIABLE")
        val plannedMissing = DownloadPlan.missing(chapters, cachedIds)
        try {
            var fetchedAny = false
            chapters.forEachIndexed { idx, ref ->
                // Abort if the book was deleted mid-download (writes are no-ops on missing rows).
                if (withContext(ioDispatcher) { db.books().book(bookId) } == null) {
                    throw java.io.IOException("book was deleted during download")
                }
                val has = ref.pageId in cachedIds
                if (!has) {
                    if (fetchedAny) crawlDelay()
                    val text = withContext(ioDispatcher) { chapter(ref.url).text }
                    saveChapterContent(bookId, ref.pageId, text)
                    cachedIds.add(ref.pageId)
                    fetchedAny = true
                }
                onProgress(idx + 1, chapters.size)
            }
        } finally {
            runCatching {
                withContext(NonCancellable + ioDispatcher) {
                    val cached = db.chapters().cachedCount(bookId)
                    if (cached > 0) db.books().touch(bookId, System.currentTimeMillis())
                }
            }
        }
    }

    override suspend fun deleteBook(bookId: String) = withContext(ioDispatcher) {
        dbWrite.withLock {
            db.deleteBookFull(bookId)
        }
    }

    /** Atomic wipe via [AppDb.clearAllFull]. */
    override suspend fun clearAll() = withContext(ioDispatcher) {
        dbWrite.withLock {
            db.clearAllFull()
        }
    }
}
