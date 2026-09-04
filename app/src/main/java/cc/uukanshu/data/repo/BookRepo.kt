package cc.uukanshu.data.repo

import cc.uukanshu.data.db.AppDb
import cc.uukanshu.data.db.BookEntity
import cc.uukanshu.data.db.ChapterEntity
import cc.uukanshu.data.net.SiteApi
import cc.uukanshu.data.parse.Parser
import kotlinx.coroutines.Dispatchers
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

    suspend fun recent(): List<Parser.BookItem> =
        withContext(Dispatchers.IO) {
            Parser.parseRecent(site.get("${Parser.BASE}/"))
        }

    suspend fun search(keyword: String): Parser.SearchResult =
        withContext(Dispatchers.IO) {
            Parser.parseSearch(site.search(keyword))
        }

    data class Detail(val meta: Parser.BookMeta, val chapters: List<Parser.ChapterRef>)

    suspend fun detail(bookId: String): Detail = withContext(Dispatchers.IO) {
        val url = "${Parser.BASE}/book/$bookId/"
        val html = site.get(url)
        val meta = Parser.parseBookMeta(html, url)
        val chapters = Parser.parseToc(html, bookId)
        // Cache meta + TOC skeleton (no content yet) for offline listing.
        runCatching {
            db.books().upsert(
                BookEntity(bookId, meta.title, meta.author, meta.intro, meta.category, meta.latestChapterTitle),
            )
            db.chapters().upsertAll(
                chapters.map {
                    ChapterEntity(bookId, it.position, it.pageId, it.title, it.url)
                },
            )
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

    suspend fun saveChapterContent(bookId: String, position: Int, content: String) {
        val list = db.chapters().chapters(bookId)
        val row = list.firstOrNull { it.position == position } ?: return
        db.chapters().upsertAll(listOf(row.copy(content = content)))
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
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        library().forEach { deleteBook(it.id) }
    }
}
