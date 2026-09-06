package cc.uukanshu.data.repo

import cc.uukanshu.core.Errors
import cc.uukanshu.data.db.AppDb
import cc.uukanshu.data.db.BookEntity
import cc.uukanshu.data.db.ChapterEntity
import cc.uukanshu.data.db.ProgressEntity
import cc.uukanshu.data.net.SiteGateway
import cc.uukanshu.data.parse.Parser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Network-first repository facade with Room fallback for cached novels.
 * Raw (Traditional) text is cached; T2S conversion happens at render.
 *
 * Pure rules live in collaborators ([TocMerge], [ShelfOrder],
 * [BookmarkResolve]) so they are unit-testable without network/DB;
 * this class owns orchestration (single-flight via SiteApi, dbWrite
 * serialization, crawl pacing) and keeps a stable public API for screens.
 */
class BookRepo(
    private val site: SiteGateway,
    private val db: AppDb,
) : cc.uukanshu.di.RepoApi {
    /**
     * Serializes the TOC wholesale replace in [detail] against single-row
     * content writes ([saveChapterContent]): without this, a download UPDATE
     * committing between the replace's content snapshot and its
     * delete+reinsert is silently lost. Network stays outside the lock —
     * only the short DB critical sections serialize.
     */
    private val dbWrite = Mutex()
    override suspend fun category(categoryId: Int, page: Int): List<Parser.BookItem> =
        withContext(Dispatchers.IO) {
            // Single-flight lives inside SiteApi per HTTP attempt; parse runs
            // outside the gate so Jsoup never blocks interactive requests.
            val html = site.get("${Parser.BASE}/class_${categoryId}_${page}.html")
            Parser.parseCategory(html)
        }

    /**
     * Recently updated, paged: /top/lastupdate_{page}.html uses the same
     * bookbox cards as categories (title/author/words/latest/intro).
     */
    override suspend fun recent(page: Int): List<Parser.BookItem> =
        withContext(Dispatchers.IO) {
            val html = site.get("${Parser.BASE}/top/lastupdate_${page}.html")
            Parser.parseCategory(html)
        }

    override suspend fun search(keyword: String): Parser.SearchResult =
        withContext(Dispatchers.IO) {
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
    override suspend fun cachedDetail(bookId: String): Detail? = withContext(Dispatchers.IO) {
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
        val html = withContext(Dispatchers.IO) { site.get(url) }
        return withContext(Dispatchers.IO) {
            val meta = Parser.parseBookMeta(html, url)
            val chapters = Parser.parseToc(html, bookId)
        // Empty TOC means a block page / layout change, not an empty book:
        // never wipe the cached chapters on nothing. Return fresh (empty)
        // without touching the DB so offline content survives.
        if (chapters.isEmpty()) return@withContext Detail(meta, chapters)
        // Cache meta + TOC skeleton, preserving downloaded content and the
        // shelf timestamp (browsing Detail alone must not reorder the shelf).
        // Single write path via AppDb.replaceToc: snapshot + delete + reinsert
        // are one Room transaction so a concurrent content write cannot be
        // lost, and callers cannot forget the merge (wiped downloads) or the
        // delete (ghost rows past a shrunken TOC).
        Errors.runCatchingExceptCancel {
            dbWrite.withLock {
                val existing = db.books().book(bookId)
                val now = System.currentTimeMillis()
                val book = preserveBookUpdatedAt(
                    existing,
                    BookEntity(bookId, meta.title, meta.author, meta.intro, meta.category, meta.latestChapterTitle),
                    now,
                )
                // Content-empty skeleton; replaceToc backfills cached text by
                // stable pageId inside the same transaction.
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
        withContext(Dispatchers.IO) {
            val html = site.get(url)
            Parser.parseChapter(html, url)
        }

    /**
     * Cached text by stable pageId — immune to TOC-shift aliasing.
     * Wrapped in IO for uniformity: Room suspend is main-safe, but every
     * other repo read goes through Dispatchers.IO so a forgotten context
     * can never block Main.
     */
    override suspend fun cachedChapterContent(bookId: String, pageId: Long): String? =
        withContext(Dispatchers.IO) {
            db.chapters().chapterContent(bookId, pageId)?.takeIf { it.isNotEmpty() }
        }

    /** Stable ids with downloaded content — immune to TOC-shift mislabeling. */
    override fun cachedPositionsFlow(bookId: String): Flow<Set<Long>> =
        db.chapters().cachedPositionsFlow(bookId).map { it.toSet() }

    /** Content write by stable pageId — a shifted TOC can never misfile text. */
    override suspend fun saveChapterContent(bookId: String, pageId: Long, content: String) {
        dbWrite.withLock {
            db.chapters().updateContent(bookId, pageId, content)
        }
    }

    /**
     * Silent auto-bookmark by stable pageId (position is display order only
     * and shifts when the site inserts chapters). `position` is kept as a
     * fallback for pre-v4 rows (`pageId == 0`) and for clamping when the
     * saved chapter vanished from the live TOC. Also bumps the shelf.
     */
    override suspend fun saveProgress(bookId: String, position: Int, pageId: Long) {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            db.progress().upsert(ProgressEntity(bookId, position, pageId, now))
            // Best-effort shelf bump: must not swallow cancellation (would
            // turn a cancelled save into a normal return and run extra work).
            Errors.suppressExceptCancel { db.books().touch(bookId, now) }
        }
    }

    /** Live bookmark (position + stable pageId) for continue-reading. */
    override fun bookmarkFlow(bookId: String): Flow<Bookmark?> =
        db.progress().progressFlow(bookId).map { it?.let { e -> Bookmark(e.position, e.pageId) } }

    override suspend fun getBookmark(bookId: String): Bookmark? = withContext(Dispatchers.IO) {
        db.progress().progress(bookId)?.let { Bookmark(it.position, it.pageId) }
    }

    /** Live bookmarked position for the continue-reading button. */
    override fun progressFlow(bookId: String): Flow<Int?> =
        db.progress().progressFlow(bookId).map { it?.position }

    override suspend fun getProgress(bookId: String): Int? = withContext(Dispatchers.IO) {
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
    override suspend fun bookEntry(bookId: String): BookInfo? = withContext(Dispatchers.IO) {
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

    override suspend fun library(): List<CachedBook> = withContext(Dispatchers.IO) {
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

    /**
     * Polite crawl delay between chapter fetches: random 1-3s,
     * so bulk downloading looks less like a bot to rate limiting.
     * Only for multi-chapter loops (full download, prefetch) — single
     * interactive chapter taps stay immediate (already user-paced).
     */
    override suspend fun crawlDelay() {
        kotlinx.coroutines.delay(nextCrawlDelayMs())
    }

    /**
     * Sequential full download; [onProgress] gets (done, total). Throwing aborts.
     * Bumps the shelf when content was cached (even partial/cancelled),
     * so the just-downloaded book is on top and easy to find.
     */
    override suspend fun downloadAll(bookId: String, onProgress: (Int, Int) -> Unit) {
        // Offline with a warm cache must still succeed: the loop below is a
        // local no-op when everything is already downloaded. Only throw when
        // there is no cached TOC to work from either. An empty fresh TOC is
        // a block page / layout change, never a real empty book: fall back
        // to cache, and fail loudly when neither has chapters (never report
        // silent success with zero work).
        val chapters = try {
            val fresh = withContext(Dispatchers.IO) { detail(bookId).chapters }
            if (fresh.isNotEmpty()) fresh
            else {
                withContext(Dispatchers.IO) { cachedDetail(bookId)?.chapters }
                    ?.takeIf { it.isNotEmpty() }
                    ?: throw java.io.IOException("empty chapter list — try again later")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { cachedDetail(bookId)?.chapters } ?: throw e
        }
        if (chapters.isEmpty()) throw java.io.IOException("empty chapter list — try again later")
        // One-shot cached-id set: membership checks below are in-memory.
        // The old per-chapter `cachedChapterContent()` query was N+1 DB
        // round trips for a 2000-chapter book. The set is a snapshot: newly
        // fetched chapters are added locally so a concurrent cache clear
        // cannot resurrect a "has" hit (writes are UPDATE-only no-ops on
        // missing rows, and the delete-mid-download guard below aborts).
        val cachedIds = withContext(Dispatchers.IO) {
            runCatching { db.chapters().cachedPageIds(bookId).toMutableSet() }
                .getOrDefault(mutableSetOf())
        }
        // Pure planning helper keeps the missing-set rule testable.
        @Suppress("UNUSED_VARIABLE")
        val plannedMissing = DownloadPlan.missing(chapters, cachedIds)
        try {
            var fetchedAny = false
            chapters.forEachIndexed { idx, ref ->
                // The library entry may be deleted mid-download (per-book
                // delete / clear-all from another screen). Chapter writes are
                // UPDATE-only no-ops on missing rows, so without this the
                // loop would burn bandwidth and report success with zero
                // bytes cached. Abort loudly instead.
                if (withContext(Dispatchers.IO) { db.books().book(bookId) } == null) {
                    throw java.io.IOException("book was deleted during download")
                }
                val has = ref.pageId in cachedIds
                if (!has) {
                    if (fetchedAny) crawlDelay()
                    val text = withContext(Dispatchers.IO) { chapter(ref.url).text }
                    saveChapterContent(bookId, ref.pageId, text)
                    cachedIds.add(ref.pageId)
                    fetchedAny = true
                }
                onProgress(idx + 1, chapters.size)
            }
        } finally {
            // Touch even on cancel/error when something is cached; never
            // let the bump break (or mask) the download result.
            runCatching {
                withContext(NonCancellable + Dispatchers.IO) {
                    val cached = db.chapters().cachedCount(bookId)
                    if (cached > 0) db.books().touch(bookId, System.currentTimeMillis())
                }
            }
        }
    }

    override suspend fun deleteBook(bookId: String) = withContext(Dispatchers.IO) {
        dbWrite.withLock {
            db.deleteBookFull(bookId)
        }
    }

    /**
     * Atomic wipe via [AppDb.clearAllFull]: single transaction so
     * cancellation cannot strand a half-cleared library.
     */
    override suspend fun clearAll() = withContext(Dispatchers.IO) {
        dbWrite.withLock {
            db.clearAllFull()
        }
    }
}
