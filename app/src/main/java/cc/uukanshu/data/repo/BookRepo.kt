package cc.uukanshu.data.repo

import cc.uukanshu.core.BookDeletedDuringDownloadException
import cc.uukanshu.core.EmptyChapterListException
import cc.uukanshu.core.Errors
import cc.uukanshu.core.TocShrunkException
import cc.uukanshu.data.db.AppDb
import cc.uukanshu.data.db.BookEntity
import cc.uukanshu.data.db.ChapterEntity
import cc.uukanshu.data.db.ProgressEntity
import cc.uukanshu.data.net.SiteGateway
import cc.uukanshu.data.net.BulkFetch
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

/** Network-first facade with Room fallback. Raw Traditional cached; T2S at render. */
class BookRepo(
    private val site: SiteGateway,
    private val db: AppDb,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : cc.uukanshu.di.RepoApi {
    /** Serializes TOC replace vs single-row writes so concurrent refreshes can't regress size. */
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

        /** pageId-first; vanished non-zero pageId yields null, never a neighbor. Pre-v4 (pageId 0) falls back to position. */
        fun resolveBookmark(
            chapters: List<Parser.ChapterRef>,
            bookmark: Bookmark?,
        ): Parser.ChapterRef? {
            if (bookmark == null || chapters.isEmpty()) return null
            if (bookmark.pageId != 0L) return chapters.firstOrNull { it.pageId == bookmark.pageId }
            return chapters.firstOrNull { it.position == bookmark.position }
        }

        /** Chapters without cached text, in TOC order. */
        fun missing(
            chapters: List<Parser.ChapterRef>,
            cachedIds: Set<Long>,
        ): List<Parser.ChapterRef> = chapters.filter { it.pageId !in cachedIds }

        /**
         * Visible shelf ids only (cached > 0), oldest-check first, capped.
         * TOC-only skeletons (browsed but never downloaded) are invisible
         * on the shelf — checking them wastes fetches + delays real badges.
         * Rows are tiny stubs (no bodies); filtering in Kotlin keeps the
         * rule pure + JVM-tested instead of a SQL join. Pure.
         */
        fun visibleIds(
            ordered: List<BookEntity>,
            stats: List<cc.uukanshu.data.db.ChapterStats>,
            limit: Int,
        ): List<String> {
            val cachedById = stats.associate { it.bookId to it.cached }
            return ordered.asSequence()
                .filter { (cachedById[it.id] ?: 0) > 0 }
                .take(limit.coerceAtLeast(1))
                .map { it.id }
                .toList()
        }

        /** True when every chapter already has cached text. */
        fun isDownloadComplete(chapters: List<Parser.ChapterRef>, cachedIds: Set<Long>): Boolean =
            chapters.isNotEmpty() && chapters.all { it.pageId in cachedIds }
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
            // Preserve downloads + shelf order via AppDb.replaceToc (single transaction).
            // DB failures propagate to the caller (stale + offline via
            // TocRevalidator.Failed) — never silent success with a stale DB.
            // Cancellation propagates out of the Mutex/Room calls untouched.
            // Guard read + replace are atomic under dbWrite (see its KDoc).
            dbWrite.withLock {
                // Shrunken TOC is the same failure shape (truncated parse): fail
                // closed before replaceToc can delete downloaded chapters whose
                // pageIds are absent from the short parse. See SCRAPING.md.
                val cachedCount = db.chapters().countByBook(bookId)
                if (!TocRevalidator.shouldAcceptFresh(chapters, cachedCount)) {
                    throw TocShrunkException(cachedCount, chapters.size)
                }
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
        val newCount: Int = 0,
    )

    /** Cached book meta (TOC skeleton) for shelf rows of fresh downloads. */
    override suspend fun bookEntry(bookId: String): BookInfo? = withContext(ioDispatcher) {
        db.books().book(bookId)?.let {
            BookInfo(it.id, it.title, it.author, it.intro, it.category, it.lastChapterTitle, it.updatedAt, it.newCount)
        }
    }

    override fun bookInfoFlow(bookId: String): Flow<BookInfo?> =
        db.books().bookFlow(bookId).map {
            it?.let { e -> BookInfo(e.id, e.title, e.author, e.intro, e.category, e.lastChapterTitle, e.updatedAt, e.newCount) }
        }

    // -- offline library (milestone 7): sequential, no hard cap ------------

    data class CachedBook(
        val id: String,
        val title: String,
        val author: String,
        val total: Int,
        val cached: Int,
        val bytes: Long,
        val newChapters: Int = 0,
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
                    ?: throw EmptyChapterListException()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            withContext(ioDispatcher) { cachedDetail(bookId)?.chapters } ?: throw e
        }
        if (chapters.isEmpty()) throw EmptyChapterListException()
        // In-memory id set avoids N+1 queries; snapshot so concurrent clear can't fake hits.
        val cachedIds = withContext(ioDispatcher) {
            runCatching { db.chapters().cachedPageIds(bookId).toMutableSet() }
                .getOrDefault(mutableSetOf())
        }
        val missingIds = missing(chapters, cachedIds).mapTo(mutableSetOf()) { it.pageId }
        try {
            var fetchedAny = false
            chapters.forEachIndexed { idx, ref ->
                // Abort if the book was deleted mid-download (writes are no-ops on missing rows).
                // Cheap EXISTS probe: the old full-entity load was N point queries per book.
                if (!withContext(ioDispatcher) { db.books().exists(bookId) }) {
                    throw BookDeletedDuringDownloadException()
                }
                if (ref.pageId in missingIds) {
                    if (fetchedAny) crawlDelay()
                    // Bulk timeouts (see BulkFetch); gate is per-attempt so taps interleave.
                    val text = withContext(ioDispatcher + BulkFetch) { chapter(ref.url).text }
                    saveChapterContent(bookId, ref.pageId, text)
                    missingIds.remove(ref.pageId)
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

    /** Result of one book's 追更 check: badge count when > 0, else no change. */
    sealed interface UpdateCheck {
        data class Ok(val newCount: Int) : UpdateCheck
        data object SkippedEmpty : UpdateCheck
        data object SkippedShrink : UpdateCheck
        data class Failed(val error: Exception) : UpdateCheck
    }

    data class CheckAllResult(
        val checked: Int,
        val newBooks: Int,
        val newChapters: Int,
        val perBook: Map<String, Int> = emptyMap(),
    )

    /**
     * One book's update check: fresh TOC via detail() (which merges via
     * replaceToc without bumping shelf order), then badge diff vs seenTotal.
     * seenTotal never advances here — only markSeen advances it — so the
     * badge survives until the user opens Detail. First run seeds baseline
     * with no false badge. Empty/shrink never wipes cache (same guard as Detail).
     */
    override suspend fun checkUpdate(bookId: String): UpdateCheck {
        val before = withContext(ioDispatcher) { db.books().book(bookId) }
            ?: return UpdateCheck.Failed(java.io.IOException("not cached"))
        val seenBefore = before.seenTotal
        val freshSize: Int = try {
            withContext(ioDispatcher) { detail(bookId).chapters.size }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TocShrunkException) {
            withContext(ioDispatcher) {
                dbWrite.withLock {
                    val cur = db.books().book(bookId)
                    if (cur != null) db.books().updateCheckState(bookId, cur.seenTotal, cur.newCount, System.currentTimeMillis())
                }
            }
            return UpdateCheck.SkippedShrink
        } catch (e: Exception) {
            return UpdateCheck.Failed(e)
        }
        if (freshSize == 0) return UpdateCheck.SkippedEmpty
        return withContext(ioDispatcher) {
            dbWrite.withLock {
                val cur = db.books().book(bookId) ?: return@withLock UpdateCheck.Failed(java.io.IOException("deleted"))
                val now = System.currentTimeMillis()
                if (seenBefore == 0 && cur.seenTotal == 0) {
                    // First baseline: no badge, remember current size.
                    db.books().updateCheckState(bookId, freshSize, 0, now)
                    UpdateCheck.Ok(0)
                } else {
                    val base = cur.seenTotal.takeIf { it > 0 } ?: freshSize
                    val n = (freshSize - base).coerceAtLeast(0)
                    db.books().updateCheckState(bookId, cur.seenTotal.takeIf { it > 0 } ?: freshSize, n, now)
                    UpdateCheck.Ok(n)
                }
            }
        }
    }

    /**
     * Bounded background run: visible shelf only (cached > 0), oldest-checked
     * first, at most [limit] books, sequential with crawlDelay between fetches.
     * TOC-only skeletons skip without timestamp bump so they never starve
     * visible badges. Per-book failures are swallowed (skip) so one Cloudflare
     * block never fails the whole run. Never throws except on cancellation.
     */
    override suspend fun checkAllUpdates(limit: Int): CheckAllResult = withContext(ioDispatcher) {
        val ids = try {
            val ordered = db.books().booksByCheckTime()
            val stats = db.chapters().statsByBook()
            visibleIds(ordered, stats, limit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext CheckAllResult(0, 0, 0)
        }
        var fetchedAny = false
        val per = mutableMapOf<String, Int>()
        var books = 0
        var chapters = 0
        for (id in ids) {
            try {
                if (!db.books().exists(id)) continue
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            if (fetchedAny) {
                try {
                    crawlDelay()
                } catch (e: CancellationException) {
                    throw e
                }
            }
            val res = try {
                checkUpdate(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            fetchedAny = true
            if (res is UpdateCheck.Ok && res.newCount > 0) {
                per[id] = res.newCount
                books++
                chapters += res.newCount
            }
        }
        CheckAllResult(checked = ids.size, newBooks = books, newChapters = chapters, perBook = per)
    }

    /** User opened Detail: baseline advances to current TOC, badge clears. Serialized with checks. */
    override suspend fun markSeen(bookId: String) = withContext(ioDispatcher) {
        dbWrite.withLock {
            val total = try {
                db.chapters().countByBook(bookId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withLock
            }
            if (total == 0) return@withLock
            val cur = db.books().book(bookId) ?: return@withLock
            db.books().updateCheckState(bookId, total, 0, cur.lastCheckedAt)
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
