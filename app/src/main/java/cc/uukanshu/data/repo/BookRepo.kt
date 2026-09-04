package cc.uukanshu.data.repo

import cc.uukanshu.data.db.AppDb
import cc.uukanshu.data.db.BookEntity
import cc.uukanshu.data.db.ChapterEntity
import cc.uukanshu.data.net.SiteApi
import cc.uukanshu.data.parse.Parser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
}
