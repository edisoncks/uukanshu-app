package cc.uukanshu.data.repo

import cc.uukanshu.data.db.AppDb
import cc.uukanshu.data.db.BookEntity
import cc.uukanshu.data.db.ChapterEntity
import cc.uukanshu.data.db.ProgressEntity
import cc.uukanshu.data.net.SiteApi
import cc.uukanshu.data.parse.Parser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Network-first repository with Room fallback for cached novels.
 * Raw (Traditional) text is cached; T2S conversion happens at render.
 */
class BookRepo(
    private val site: SiteApi,
    private val db: AppDb,
) {
    suspend fun category(categoryId: Int, page: Int): List<Parser.BookItem> =
        withContext(Dispatchers.IO) {
            Parser.parseCategory(site.get("${Parser.BASE}/class_${categoryId}_${page}.html"))
        }

    /**
     * Recently updated, paged: /top/lastupdate_{page}.html uses the same
     * bookbox cards as categories (title/author/words/latest/intro).
     */
    suspend fun recent(page: Int): List<Parser.BookItem> =
        withContext(Dispatchers.IO) {
            Parser.parseCategory(site.get("${Parser.BASE}/top/lastupdate_${page}.html"))
        }

    suspend fun search(keyword: String): Parser.SearchResult =
        withContext(Dispatchers.IO) {
            Parser.parseSearch(site.search(keyword))
        }

    data class Detail(val meta: Parser.BookMeta, val chapters: List<Parser.ChapterRef>)

    data class DetailResult(val detail: Detail, val offline: Boolean)

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

    /**
     * Network-first, cached fallback: makes Detail/Reader work offline as
     * long as the book was opened or downloaded before.
     */
    suspend fun detailOrCached(bookId: String): DetailResult {
        return try {
            DetailResult(detail(bookId), offline = false)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            DetailResult(cachedDetail(bookId) ?: throw e, offline = true)
        }
    }

    suspend fun detail(bookId: String): Detail = withContext(Dispatchers.IO) {
        val url = "${Parser.BASE}/book/$bookId/"
        val html = site.get(url)
        val meta = Parser.parseBookMeta(html, url)
        val chapters = Parser.parseToc(html, bookId)
        // Cache meta + TOC skeleton, preserving downloaded content.
        runCatching {
            db.books().upsert(
                BookEntity(bookId, meta.title, meta.author, meta.intro, meta.category, meta.latestChapterTitle),
            )
            val cached = db.chapters().chapters(bookId)
                .associate { it.pageId to it.content }
            db.chapters().upsertAll(mergeToc(bookId, chapters, cached))
        }
        Detail(meta, chapters)
    }

    suspend fun chapter(url: String): Parser.ChapterContent =
        withContext(Dispatchers.IO) {
            Parser.parseChapter(site.get(url), url)
        }

    suspend fun cachedChapterContent(bookId: String, position: Int): String? =
        db.chapters().chapters(bookId).firstOrNull { it.position == position }
            ?.content?.takeIf { it.isNotEmpty() }

    /** Positions with downloaded content — live stream driving chapter-list badges. */
    fun cachedPositionsFlow(bookId: String): Flow<Set<Int>> =
        db.chapters().cachedPositionsFlow(bookId).map { it.toSet() }

    suspend fun saveChapterContent(bookId: String, position: Int, content: String) {
        val list = db.chapters().chapters(bookId)
        val row = list.firstOrNull { it.position == position } ?: return
        db.chapters().upsertAll(listOf(row.copy(content = content)))
    }

    /** Silent auto-bookmark: overwrite on every successful chapter open. */
    suspend fun saveProgress(bookId: String, position: Int) = withContext(Dispatchers.IO) {
        db.progress().upsert(ProgressEntity(bookId, position, System.currentTimeMillis()))
    }

    /** Live bookmarked position for the continue-reading button. */
    fun progressFlow(bookId: String): Flow<Int?> =
        db.progress().progressFlow(bookId).map { it?.position }

    suspend fun getProgress(bookId: String): Int? = withContext(Dispatchers.IO) {
        db.progress().progress(bookId)?.position
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
        db.books().cachedBooks().map { b ->
            val chapters = db.chapters().chapters(b.id)
            CachedBook(
                b.id, b.title, b.author,
                total = chapters.size,
                cached = chapters.count { it.content.isNotEmpty() },
                bytes = chapters.sumOf { it.content.toByteArray().size.toLong() },
            )
        }
    }

    /**
     * Polite crawl delay between chapter fetches: 3s + random 0-1s jitter,
     * so bulk downloading looks less like a bot to rate limiting.
     * Only for multi-chapter loops (full download, prefetch) — single
     * interactive chapter taps stay immediate (already user-paced).
     */
    suspend fun crawlDelay() {
        kotlinx.coroutines.delay(3000L + Random.nextLong(0, 1001))
    }

    /** Sequential full download; [onProgress] gets (done, total). Throwing aborts. */
    suspend fun downloadAll(bookId: String, onProgress: (Int, Int) -> Unit) {
        val chapters = withContext(Dispatchers.IO) { detail(bookId).chapters }
        var fetchedAny = false
        chapters.forEachIndexed { idx, ref ->
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
    }

    suspend fun deleteBook(bookId: String) = withContext(Dispatchers.IO) {
        db.chapters().deleteBook(bookId)
        db.books().deleteBook(bookId)
        db.progress().deleteBook(bookId)
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        library().forEach { deleteBook(it.id) }
    }
}
