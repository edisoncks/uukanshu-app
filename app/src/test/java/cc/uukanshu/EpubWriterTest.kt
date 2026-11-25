package cc.uukanshu

import cc.uukanshu.data.export.EpubMeta
import cc.uukanshu.data.export.EpubPaths
import cc.uukanshu.data.export.EpubWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/** EPUB container shape and XML escaping; the writer is dependency-free. */
class EpubWriterTest {
    private data class Entry(val name: String, val method: Int, val bytes: ByteArray)

    private val dc = "http://purl.org/dc/elements/1.1/"

    private fun entries(bytes: ByteArray): List<Entry> {
        val result = mutableListOf<Entry>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                result += Entry(e.name, e.method, zin.readBytes())
            }
        }
        return result
    }

    private fun map(bytes: ByteArray): Map<String, ByteArray> =
        entries(bytes).associate { it.name to it.bytes }

    private fun build(
        meta: EpubMeta = EpubMeta(
            identifier = "urn:uuid:123e4567-e89b-12d3-a456-426614174000",
            title = "書名",
            author = "作者",
            intro = "簡介\n\n第二段",
            category = "玄幻",
            source = "https://example.test/book/1/",
        ),
        modified: Instant = Instant.parse("2025-01-02T03:04:05Z"),
        chapters: List<Pair<String, String>> = listOf(
            "第一章" to "第一段\n\n第二段",
            "第二章" to "內容",
        ),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        EpubWriter(out, meta, modified).use { writer ->
            chapters.forEach { (title, text) -> writer.addChapter(title, text) }
        }
        return out.toByteArray()
    }

    private fun parse(bytes: ByteArray): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(InputSource(ByteArrayInputStream(bytes)))

    private fun text(doc: Document, ns: String, local: String): String =
        doc.getElementsByTagNameNS(ns, local).item(0)?.textContent.orEmpty()

    private fun modified(doc: Document): String =
        (0 until doc.getElementsByTagName("meta").length)
            .map { doc.getElementsByTagName("meta").item(it) }
            .firstOrNull { it.attributes.getNamedItem("property")?.nodeValue == "dcterms:modified" }
            ?.textContent.orEmpty()

    @Test fun mimetypeIsFirstAndStored() {
        val first = entries(build()).first()
        assertEquals("mimetype", first.name)
        assertEquals(ZipEntry.STORED, first.method)
        assertEquals("application/epub+zip", first.bytes.toString(Charsets.US_ASCII))
    }

    @Test fun containerAndOpfDescribeThePackage() {
        val files = map(build())
        val container = parse(files.getValue("META-INF/container.xml"))
        assertEquals(
            "OEBPS/content.opf",
            container.getElementsByTagName("rootfile").item(0).attributes
                .getNamedItem("full-path").nodeValue,
        )

        val opf = parse(files.getValue("OEBPS/content.opf"))
        assertEquals("package", opf.documentElement.tagName)
        assertEquals("3.0", opf.documentElement.getAttribute("version"))
        assertEquals("urn:uuid:123e4567-e89b-12d3-a456-426614174000", text(opf, dc, "identifier"))
        assertEquals("書名", text(opf, dc, "title"))
        assertEquals("作者", text(opf, dc, "creator"))
        assertEquals("zh-Hant", text(opf, dc, "language"))
        assertEquals("簡介\n\n第二段", text(opf, dc, "description"))
        assertEquals("玄幻", text(opf, dc, "subject"))
        assertEquals("https://example.test/book/1/", text(opf, dc, "source"))
        assertEquals("2025-01-02T03:04:05Z", modified(opf))

        val items = opf.getElementsByTagName("item")
        assertEquals(5, items.length)
        val nav = (0 until items.length).map { items.item(it) }
            .first { it.attributes.getNamedItem("id").nodeValue == "nav" }
        assertEquals("nav", nav.attributes.getNamedItem("properties").nodeValue)
        assertEquals("nav.xhtml", nav.attributes.getNamedItem("href").nodeValue)

        val spine = opf.getElementsByTagName("itemref")
        assertEquals(3, spine.length)
        assertEquals("titlepage", spine.item(0).attributes.getNamedItem("idref").nodeValue)
        assertEquals("ch1", spine.item(1).attributes.getNamedItem("idref").nodeValue)
        assertEquals("ch2", spine.item(2).attributes.getNamedItem("idref").nodeValue)
    }

    @Test fun navListsChaptersInOrder() {
        val files = map(build())
        val nav = parse(files.getValue("OEBPS/nav.xhtml"))
        val links = nav.getElementsByTagName("a")
        assertEquals(2, links.length)
        assertEquals("chap0001.xhtml", links.item(0).attributes.getNamedItem("href").nodeValue)
        assertEquals("第一章", links.item(0).textContent)
        assertEquals("chap0002.xhtml", links.item(1).attributes.getNamedItem("href").nodeValue)
        assertEquals("第二章", links.item(1).textContent)
    }

    @Test fun chapterXhtmlEscapesTextAndSplitsParagraphs() {
        val bytes = build(
            chapters = listOf("A & B" to "one & two < three\n\n  second  'q' \"x\"  "),
        )
        val chapter = map(bytes).getValue("OEBPS/chap0001.xhtml")
        val xml = chapter.toString(Charsets.UTF_8)
        assertTrue(xml.contains("&amp;"))
        assertTrue(xml.contains("&lt;"))
        assertTrue(xml.contains("&quot;"))
        assertTrue(xml.contains("&apos;"))

        val doc = parse(chapter)
        assertEquals("A & B", doc.getElementsByTagName("h1").item(0).textContent)
        val ps = doc.getElementsByTagName("p")
        assertEquals(2, ps.length)
        assertEquals("one & two < three", ps.item(0).textContent)
        assertEquals("second  'q' \"x\"", ps.item(1).textContent)
    }

    @Test fun illegalControlCharactersAreDroppedAndEmojiSurvive() {
        val bytes = build(chapters = listOf("t" to "a\u0000b\u000Bc\u000C🐋"))
        val chapter = map(bytes).getValue("OEBPS/chap0001.xhtml")
        val xml = chapter.toString(Charsets.UTF_8)
        assertFalse(xml.contains('\u0000'))
        assertFalse(xml.contains('\u000B'))
        assertTrue("emoji must survive as one code point: $xml", xml.contains("🐋"))
        assertEquals("abc🐋", parse(chapter).getElementsByTagName("p").item(0).textContent)
    }

    @Test fun fixedModifiedTimestampIsUtcSeconds() {
        val modified = modified(parse(map(build(modified = Instant.parse("2030-12-31T23:59:59Z"))).getValue("OEBPS/content.opf")))
        assertEquals("2030-12-31T23:59:59Z", modified)
    }

    @Test fun optionalMetadataIsOmittedWhenBlank() {
        val opf = parse(
            map(build(meta = EpubMeta(identifier = "urn:uuid:x", title = "T"))).getValue("OEBPS/content.opf"),
        )
        assertEquals(0, opf.getElementsByTagNameNS(dc, "creator").length)
        assertEquals(0, opf.getElementsByTagNameNS(dc, "description").length)
        assertEquals(0, opf.getElementsByTagNameNS(dc, "subject").length)
        assertEquals(0, opf.getElementsByTagNameNS(dc, "source").length)
    }

    @Test fun titlePageCarriesTitleAuthorAndIntro() {
        val doc = parse(map(build()).getValue("OEBPS/titlepage.xhtml"))
        assertEquals("書名", doc.getElementsByTagName("h1").item(0).textContent)
        val ps = doc.getElementsByTagName("p")
        assertEquals("作者", ps.item(0).textContent)
        assertEquals("簡介", ps.item(1).textContent)
        assertEquals("第二段", ps.item(2).textContent)
    }

    @Test fun fileNamePolicyProtectsTheReceiver() {
        assertEquals("A_B_ C_.epub", EpubPaths.fileNameFor("A/B: C?", "7"))
        assertEquals("book-7.epub", EpubPaths.fileNameFor("...", "7"))
        assertEquals("book-7.epub", EpubPaths.fileNameFor("", "7"))
        assertEquals("hidden.epub", EpubPaths.fileNameFor("  . hidden . ", "7"))
        val long = EpubPaths.fileNameFor("x".repeat(200), "7")
        assertTrue(long.length <= 85)
        assertTrue(long.endsWith(".epub"))
    }

    /**
     * Keeps a real fixture under the build directory for a manual EPUBCheck
     * run (see docs/DEVELOPMENT.md § Test); the test suite itself only checks
     * the container shape above.
     */
    @Test fun writesSampleForManualValidation() {
        val bytes = build(
            chapters = (1..30).map { "第${it}章" to "第${it}章的正文。\n\n第二段 with & < >." },
        )
        val file = File("build/test-epub/sample.epub")
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        assertNotNull(file.parentFile)
        assertTrue(file.length() > 0)
    }
}
