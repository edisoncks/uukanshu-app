package cc.uukanshu.data.repo

import cc.uukanshu.data.db.AppDb
import cc.uukanshu.data.db.BookEntity
import cc.uukanshu.data.db.ChapterEntity
import cc.uukanshu.data.db.ProgressEntity
import cc.uukanshu.data.net.SiteApi
import cc.uukanshu.data.net.UukanshuGate
import cc.uukanshu.data.parse.Parser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Network-first repository with Room fallback for cached novels.
 * Raw (Traditional) text is cached; T2S conversion happens at render.
 */
class BookRepo(
    private val site: SiteApi,
    private val db: AppDb,
    private val gate: UukanshuGate = UukanshuGate(),
) {
    /**
     * Serializes the TOC wholesale replace in [detail] against single-row
     * content writes ([saveChapterContent]): without this, a download UPDATE
     * committing between the replace's content snapshot and its
     * delete+reinsert is silently lost. Network stays outside the lock —
     * only the short DB critical sections serialize.
     */
    private val dbWrite = Mutex()
    suspend fun category(categoryId: Int, page: Int): List<Parser.BookItem> =
        gate.withPermit {
            withContext(Dispatchers.IO) {
                Parser.parseCategory(site.get("${Parser.BASE}/class_${categoryId}_${page}.html"))
            }
        }

    /**
     * Recently updated, paged: /top/lastupdate_{page}.html uses the same
     * bookbox cards as categories (title/author/words/latest/intro).
     */
    suspend fun recent(page: Int): List<Parser.BookItem> =
        gate.withPermit {
            withContext(Dispatchers.IO) {
                Parser.parseCategory(site.get("${Parser.BASE}/top/lastupdate_${page}.html"))
            }
        }

    suspend fun search(keyword: String): Parser.SearchResult =
        gate.withPermit {
            withContext(Dispatchers.IO) {
                Parser.parseSearch(site.search(keyword))
            }
        }

    data class Detail(val meta: Parser.BookMeta, val chapters: List<Parser.ChapterRef>)

    companion object {
        /**
         * Merge a fresh TOC with already-downloaded content, keyed by the
         * stable pageId (never by position, which can shift). Without this,
         * re-opening a book would REPLACE cached rows with empty content
         * and silently wipe downloads.
         */
        fun mergeToc(
            bookId: String,
            refs: List<Parser.ChapterRef>,
            cachedByPageId: Map<Long, String>,
        ): List<ChapterEntity> = refs.map {
            ChapterEntity(bookId, it.position, it.pageId, it.title, it.url,
                content = cachedByPageId[it.pageId].orEmpty())
        }

        /**
         * Shelf order key: last interaction wins. Reading writes
         * [ProgressEntity.updatedAt], downloading writes
         * [BookEntity.updatedAt]; either bumps the book to the top.
         */
        fun lastActivity(bookAt: Long, progressAt: Long?): Long =
            maxOf(bookAt, progressAt ?: 0L)

        /**
         * Preserve the shelf timestamp across TOC refreshes: browsing
         * Detail must never reorder the shelf, only reads/downloads bump.
         */
        fun preserveBookUpdatedAt(
            existing: BookEntity?,
            fresh: BookEntity,
            now: Long,
        ): BookEntity =
            if (existing != null) fresh.copy(updatedAt = existing.updatedAt)
            else fresh.copy(updatedAt = now)

        /**
         * Shelf order: most-recently read or downloaded first; never-touched
         * sinks to the bottom, ties keep the input (DB) order (stable sort).
         */
        fun sortShelf(
            books: List<CachedBook>,
            bookAt: Map<String, Long>,
            progressAt: Map<String, Long>,
        ): List<CachedBook> =
            books.sortedByDescending { lastActivity(bookAt[it.id] ?: 0L, progressAt[it.id]) }

        /** Polite crawl delay bounds (ms): random 1-3s between chapter fetches. */
        const val CRAWL_DELAY_MIN_MS = 1000L
        const val CRAWL_DELAY_MAX_MS = 3000L

        /** Pure helper for [crawlDelay], testable without sleeping. */
        fun nextCrawlDelayMs(random: Random = Random): Long =
            random.nextLong(CRAWL_DELAY_MIN_MS, CRAWL_DELAY_MAX_MS + 1)
    }

    /**
     * Book + TOC reconstructed purely from cache. Null when the book was
     * never opened/downloaded — offline reading depends on this.
     */
    suspend fun cachedDetail(bookId: String): Detail? = withContext(Dispatchers.IO) {
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

    suspend fun detail(bookId: String): Detail {
        val url = "${Parser.BASE}/book/$bookId/"
        // Single-flight: only the HTTP fetch holds the gate; parse + DB
        // merge run outside so a slow transaction never blocks other screens.
        val html = gate.withPermit {
            withContext(Dispatchers.IO) { site.get(url) }
        }
        return withContext(Dispatchers.IO) {
            val meta = Parser.parseBookMeta(html, url)
            val chapters = Parser.parseToc(html, bookId)
        // Empty TOC means a block page / layout change, not an empty book:
        // never wipe the cached chapters on nothing. Return fresh (empty)
        // without touching the DB so offline content survives.
        if (chapters.isEmpty()) return@withContext Detail(meta, chapters)
        // Cache meta + TOC skeleton, preserving downloaded content and the
        // shelf timestamp (browsing Detail alone must not reorder the shelf).
        // Atomic replace: without the delete, a shrunken TOC leaves stale
        // rows past the new end (ghost chapters with stale text/counts).
        runCatching {
            dbWrite.withLock {
                db.withTransaction {
                    val existing = db.books().book(bookId)
                    val now = System.currentTimeMillis()
                    db.books().upsert(
                        preserveBookUpdatedAt(
                            existing,
                            BookEntity(bookId, meta.title, meta.author, meta.intro, meta.category, meta.latestChapterTitle),
                            now,
                        ),
                    )
                    val cached = db.chapters().chapters(bookId)
                        .associate { it.pageId to it.content }
                    db.chapters().deleteBook(bookId)
                    db.chapters().upsertAll(mergeToc(bookId, chapters, cached))
                }
            }
        }.onFailure { if (it is CancellationException) throw it }
            Detail(meta, chapters)
        }
    }

    suspend fun chapter(url: String): Parser.ChapterContent =
        gate.withPermit {
            withContext(Dispatchers.IO) {
                Parser.parseChapter(site.get(url), url)
            }
        }

    suspend fun cachedChapterContent(bookId: String, position: Int): String? =
        db.chapters().chapterContent(bookId, position)?.takeIf { it.isNotEmpty() }

    /** Positions with downloaded content — live stream driving chapter-list badges. */
    fun cachedPositionsFlow(bookId: String): Flow<Set<Int>> =
        db.chapters().cachedPositionsFlow(bookId).map { it.toSet() }

    suspend fun saveChapterContent(bookId: String, position: Int, content: String) {
        dbWrite.withLock {
            db.chapters().updateContent(bookId, position, content)
        }
    }

    /**
     * Silent auto-bookmark: overwrite on every successful chapter open.
     * Also bumps the shelf (same clock for progress + book rows).
     */
    suspend fun saveProgress(bookId: String, position: Int) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        db.progress().upsert(ProgressEntity(bookId, position, now))
        runCatching { db.books().touch(bookId, now) }
    }

    /** Live bookmarked position for the continue-reading button. */
    fun progressFlow(bookId: String): Flow<Int?> =
        db.progress().progressFlow(bookId).map { it?.position }

    suspend fun getProgress(bookId: String): Int? = withContext(Dispatchers.IO) {
        db.progress().progress(bookId)?.position
    }

    /** Cached book meta (TOC skeleton) for shelf rows of fresh downloads. */
    suspend fun bookEntry(bookId: String): BookEntity? = withContext(Dispatchers.IO) {
        db.books().book(bookId)
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

    suspend fun library(): List<CachedBook> = withContext(Dispatchers.IO) {
        val rows = db.books().cachedBooks()
        val bookAt = rows.associate { it.id to it.updatedAt }
        val progressAt = db.progress().all().associate { it.bookId to it.updatedAt }
        // Three round trips total, zero content strings loaded (was N+1
        // queries plus every cached byte). Books without chapter rows or
        // without cached content stay off the shelf, as before.
        val stats = db.chapters().statsByBook().associateBy { it.bookId }
        rows.mapNotNull { b ->
            val s = stats[b.id] ?: return@mapNotNull null
            if (s.cached == 0) return@mapNotNull null
            CachedBook(b.id, b.title, b.author, total = s.total, cached = s.cached, bytes = s.bytes)
        }.let { sortShelf(it, bookAt, progressAt) }
    }

    /**
     * Polite crawl delay between chapter fetches: random 1-3s,
     * so bulk downloading looks less like a bot to rate limiting.
     * Only for multi-chapter loops (full download, prefetch) — single
     * interactive chapter taps stay immediate (already user-paced).
     */
    suspend fun crawlDelay() {
        kotlinx.coroutines.delay(nextCrawlDelayMs())
    }

    /**
     * Sequential full download; [onProgress] gets (done, total). Throwing aborts.
     * Bumps the shelf when content was cached (even partial/cancelled),
     * so the just-downloaded book is on top and easy to find.
     */
    suspend fun downloadAll(bookId: String, onProgress: (Int, Int) -> Unit) {
        // Offline with a warm cache must still succeed: the loop below is a
        // local no-op when everything is already downloaded. Only throw when
        // there is no cached TOC to work from either.
        val chapters = try {
            withContext(Dispatchers.IO) { detail(bookId).chapters }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { cachedDetail(bookId)?.chapters } ?: throw e
        }
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
                val has = withContext(Dispatchers.IO) {
                    cachedChapterContent(bookId, ref.position) != null
                }
                if (!has) {
                    if (fetchedAny) crawlDelay()
                    val text = withContext(Dispatchers.IO) { chapter(ref.url).text }
                    saveChapterContent(bookId, ref.position, text)
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

    suspend fun deleteBook(bookId: String) = withContext(Dispatchers.IO) {
        dbWrite.withLock {
            db.withTransaction {
                db.chapters().deleteBook(bookId)
                db.books().deleteBook(bookId)
                db.progress().deleteBook(bookId)
            }
        }
    }

    /**
     * Atomic wipe of every table in one transaction: the old per-book loop
     * could strand a half-cleared library on cancellation, and could never
     * reach progress rows with no book row (see [ProgressDao.clearAll]).
     */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dbWrite.withLock {
            db.withTransaction {
                db.chapters().clearAll()
                db.books().clearAll()
                db.progress().clearAll()
            }
        }
    }
}
