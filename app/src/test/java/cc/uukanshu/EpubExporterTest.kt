package cc.uukanshu

import cc.uukanshu.core.BookNotCachedException
import cc.uukanshu.core.NoDownloadedChaptersException
import cc.uukanshu.data.convert.T2S
import cc.uukanshu.data.export.EpubExporter
import cc.uukanshu.data.parse.Parser
import cc.uukanshu.data.repo.BookRepo
import cc.uukanshu.di.RepoApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Document
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.time.Instant
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

class EpubExporterTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val dc = "http://purl.org/dc/elements/1.1/"

    /** Only the three read methods the exporter is allowed to use are live. */
    private class ExportRepo(
        var info: BookRepo.BookInfo? = BookRepo.BookInfo("1", "書名", "作者", "簡介", "玄幻"),
        var refs: List<Parser.ChapterRef> = listOf(
            Parser.ChapterRef(1, 101L, "第一章", "https://uukanshu.cc/book/1/101.html"),
            Parser.ChapterRef(2, 102L, "第二章", "https://uukanshu.cc/book/1/102.html"),
        ),
        var bodies: MutableMap<Long, String> = mutableMapOf(
            101L to "第一段\n\n第二段",
            102L to "第二章內容",
        ),
        var failOn: Long? = null,
        var blockOn: Long? = null,
    ) : RepoApi by MutableFakeRepo() {
        var listCalls = 0

        override suspend fun bookEntry(bookId: String): BookRepo.BookInfo? = info

        override suspend fun cachedChapters(bookId: String): List<Parser.ChapterRef> {
            listCalls++
            return refs
        }

        override suspend fun cachedChapterContent(bookId: String, pageId: Long): String? {
            if (pageId == blockOn) awaitCancellation()
            if (pageId == failOn) throw IOException("body read failed")
            return bodies[pageId]
        }
    }

    private fun exporter(
        repo: RepoApi,
        dir: File,
        now: Instant = Instant.parse("2025-01-02T03:04:05Z"),
    ) = EpubExporter(repo, T2S(), dir, main.dispatcher, now = { now })

    private fun parse(xml: String): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))

    private fun zipMap(file: File): Map<String, String> =
        ZipFile(file).use { zip ->
            zip.entries().asSequence().associate { entry ->
                entry.name to zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
            }
        }

    private fun navLinks(file: File): List<Pair<String, String>> {
        val nav = parse(zipMap(file).getValue("OEBPS/nav.xhtml"))
        val links = nav.getElementsByTagName("a")
        return (0 until links.length).map {
            links.item(it).attributes.getNamedItem("href").nodeValue to links.item(it).textContent
        }
    }

    @Test fun writesOnlyCachedChaptersInTocOrder() = runTest(main.dispatcher) {
        val repo = ExportRepo(
            refs = listOf(
                Parser.ChapterRef(1, 101L, "c1", "u1"),
                Parser.ChapterRef(2, 102L, "c2", "u2"),
                Parser.ChapterRef(3, 103L, "c3", "u3"),
            ),
            bodies = mutableMapOf(101L to "one", 103L to "three"),
        )
        val file = exporter(repo, tmp.newFolder()).export("1", simplified = false)
        assertEquals(
            listOf("chap0001.xhtml" to "c1", "chap0002.xhtml" to "c3"),
            navLinks(file),
        )
    }

    @Test fun simplifiedConvertsMetadataAndBodies() = runTest(main.dispatcher) {
        val repo = ExportRepo(
            info = BookRepo.BookInfo("1", "奮鬥", "作者", "簡介", "玄幻"),
            refs = listOf(Parser.ChapterRef(1, 101L, "第一節", "u1")),
            bodies = mutableMapOf(101L to "生命不息，奮鬥不止"),
        )
        val file = exporter(repo, tmp.newFolder()).export("1", simplified = true)
        val files = zipMap(file)
        val opf = parse(files.getValue("OEBPS/content.opf"))
        assertEquals("奋斗", opf.getElementsByTagNameNS(dc, "title").item(0).textContent)
        assertEquals("zh-Hans", opf.getElementsByTagNameNS(dc, "language").item(0).textContent)
        val chapter = parse(files.getValue("OEBPS/chap0001.xhtml"))
        assertEquals("第一节", chapter.getElementsByTagName("h1").item(0).textContent)
        assertEquals(
            "生命不息，奋斗不止",
            chapter.getElementsByTagName("p").item(0).textContent,
        )
    }

    @Test fun traditionalIsTheDefault() = runTest(main.dispatcher) {
        val repo = ExportRepo(
            info = BookRepo.BookInfo("1", "奮鬥", "作者", "簡介", "玄幻"),
            refs = listOf(Parser.ChapterRef(1, 101L, "第一節", "u1")),
            bodies = mutableMapOf(101L to "生命不息，奮鬥不止"),
        )
        val file = exporter(repo, tmp.newFolder()).export("1", simplified = false)
        val files = zipMap(file)
        val opf = parse(files.getValue("OEBPS/content.opf"))
        assertEquals("zh-Hant", opf.getElementsByTagNameNS(dc, "language").item(0).textContent)
        assertEquals("奮鬥", opf.getElementsByTagNameNS(dc, "title").item(0).textContent)
        assertTrue(files.getValue("OEBPS/chap0001.xhtml").contains("奮鬥不止"))
    }

    @Test fun fileIsNamedFromTheSanitizedTitle() = runTest(main.dispatcher) {
        val repo = ExportRepo(info = BookRepo.BookInfo("1", "A/B: C?", "作者"))
        val file = exporter(repo, tmp.newFolder()).export("1", simplified = false)
        assertEquals("A_B_ C_.epub", file.name)
    }

    @Test fun noCachedBookRowFailsTyped() = runTest(main.dispatcher) {
        val repo = ExportRepo(info = null)
        var thrown: Throwable? = null
        try {
            exporter(repo, tmp.newFolder()).export("1", simplified = false)
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue("expected BookNotCachedException, got $thrown", thrown is BookNotCachedException)
    }

    @Test fun emptyChapterListFailsTyped() = runTest(main.dispatcher) {
        val repo = ExportRepo(refs = emptyList(), bodies = mutableMapOf())
        var thrown: Throwable? = null
        try {
            exporter(repo, tmp.newFolder()).export("1", simplified = false)
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue("expected NoDownloadedChaptersException, got $thrown", thrown is NoDownloadedChaptersException)
    }

    @Test fun progressIsThrottledAndEndsAtTotal() = runTest(main.dispatcher) {
        val refs = (1..45).map { Parser.ChapterRef(it, 100L + it, "c$it", "u$it") }
        val repo = ExportRepo(
            refs = refs,
            bodies = refs.associate { it.pageId to "body" }.toMutableMap(),
        )
        val progress = mutableListOf<Pair<Int, Int>>()
        exporter(repo, tmp.newFolder()).export("1", simplified = false) { done, total ->
            progress += done to total
        }
        assertEquals(listOf(20 to 45, 40 to 45, 45 to 45), progress)
    }

    @Test fun staleExportsArePrunedAndRecentOnesStay() = runTest(main.dispatcher) {
        val dir = tmp.newFolder()
        val now = Instant.parse("2025-01-02T03:04:05Z")
        val old = File(dir, "old.epub").apply { writeText("old"); setLastModified(0L) }
        val recent = File(dir, "recent.epub").apply {
            writeText("recent")
            setLastModified(now.toEpochMilli())
        }
        exporter(ExportRepo(), dir, now).export("1", simplified = false)
        assertFalse(old.exists())
        assertTrue(recent.exists())
    }

    @Test fun failedBodyReadLeavesNoTempFile() = runTest(main.dispatcher) {
        val dir = tmp.newFolder()
        val repo = ExportRepo(failOn = 102L)
        var thrown: Throwable? = null
        try {
            exporter(repo, dir).export("1", simplified = false)
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue("expected IOException, got $thrown", thrown is IOException)
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test fun cancellationLeavesNoTempFile() = runTest(main.dispatcher) {
        val dir = tmp.newFolder()
        val repo = ExportRepo(blockOn = 102L)
        val job = launch { exporter(repo, dir).export("1", simplified = false) }
        main.dispatcher.scheduler.advanceUntilIdle()
        assertTrue(
            "a partial temp should exist while the body read is blocked",
            dir.listFiles().orEmpty().any { it.name.endsWith(".tmp") },
        )
        job.cancelAndJoin()
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }
}
