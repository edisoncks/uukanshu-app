package cc.uukanshu.data.export

import java.io.Closeable
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Exact bytes the EPUB container requires: US-ASCII, no newline. */
internal const val EPUB_MIMETYPE = "application/epub+zip"

private const val CONTAINER_PATH = "META-INF/container.xml"
private const val OEBPS = "OEBPS/"
private const val OPF_PATH = "${OEBPS}content.opf"
private const val NAV_PATH = "${OEBPS}nav.xhtml"
private const val STYLE_PATH = "${OEBPS}style.css"
private const val TITLE_PAGE_PATH = "${OEBPS}titlepage.xhtml"

private val MODIFIED_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
        .withZone(ZoneOffset.UTC)

internal data class EpubMeta(
    val identifier: String,
    val title: String,
    val author: String = "",
    val intro: String = "",
    val category: String = "",
    val source: String = "",
    val language: String = "zh-Hant",
)

private data class ChapterEntry(val id: String, val href: String, val title: String)

/**
 * Minimal EPUB 3 writer (OCF + OPF + nav), no third-party dependency.
 *
 * Fixed files and chapters are written as they arrive; the nav and OPF come
 * last because they need the full chapter list. Reading systems find content
 * through the OPF, so only `mimetype` has a fixed position.
 *
 * `mimetype` must be the first entry and STORED (see ARCHITECTURE.md
 * § Invariants): a compressed or misplaced one makes strict readers reject
 * the package.
 */
internal class EpubWriter(
    out: OutputStream,
    private val meta: EpubMeta,
    modified: Instant,
) : Closeable {
    private val zip = ZipOutputStream(out)
    private val modifiedText = MODIFIED_FORMAT.format(modified.truncatedTo(ChronoUnit.SECONDS))
    private val chapters = mutableListOf<ChapterEntry>()
    private var closed = false

    init {
        writeMimetype()
        writeEntry(CONTAINER_PATH, containerXml())
        writeEntry(STYLE_PATH, CSS)
        writeEntry(TITLE_PAGE_PATH, titlePageXhtml())
    }

    fun addChapter(title: String, text: String) {
        check(!closed) { "EPUB writer is closed" }
        val number = chapters.size + 1
        val href = "chap" + number.toString().padStart(4, '0') + ".xhtml"
        val id = "ch$number"
        writeEntry(OEBPS + href, chapterXhtml(title, text))
        chapters += ChapterEntry(id, href, title)
    }

    override fun close() {
        if (closed) return
        closed = true
        writeEntry(NAV_PATH, navXhtml())
        writeEntry(OPF_PATH, opfXml())
        zip.close()
    }

    private fun writeMimetype() {
        val bytes = EPUB_MIMETYPE.toByteArray(Charsets.US_ASCII)
        val entry = ZipEntry("mimetype").apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().apply { update(bytes) }.value
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun writeEntry(path: String, xml: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(xml.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun containerXml(): String = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="$OPF_PATH" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""

    private fun titlePageXhtml(): String {
        val body = buildString {
            append("<h1 class=\"title\">").append(Xml.escape(meta.title)).append("</h1>")
            if (meta.author.isNotBlank()) {
                append("<p class=\"author\">").append(Xml.escape(meta.author)).append("</p>")
            }
            paragraphs(meta.intro).forEach {
                append("<p class=\"intro\">").append(Xml.escape(it)).append("</p>")
            }
        }
        return xhtmlDocument(meta.title, body)
    }

    private fun chapterXhtml(title: String, text: String): String {
        val body = buildString {
            append("<h1>").append(Xml.escape(title)).append("</h1>")
            paragraphs(text).forEach {
                append("<p>").append(Xml.escape(it)).append("</p>")
            }
        }
        return xhtmlDocument(title, body)
    }

    private fun xhtmlDocument(title: String, body: String): String {
        val lang = Xml.escape(meta.language)
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="$lang" lang="$lang">
<head>
<meta charset="UTF-8"/>
<title>${Xml.escape(title)}</title>
<link rel="stylesheet" type="text/css" href="style.css"/>
</head>
<body>
$body
</body>
</html>
"""
    }

    private fun navXhtml(): String {
        val items = chapters.joinToString("\n") {
            "      <li><a href=\"${it.href}\">${Xml.escape(it.title)}</a></li>"
        }
        val body = """<nav epub:type="toc" id="toc">
  <h1>${Xml.escape(meta.title)}</h1>
  <ol>
$items
  </ol>
</nav>"""
        val lang = Xml.escape(meta.language)
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="$lang" lang="$lang">
<head>
<meta charset="UTF-8"/>
<title>${Xml.escape(meta.title)}</title>
</head>
<body>
$body
</body>
</html>
"""
    }

    private fun opfXml(): String {
        val manifest = buildString {
            append("    <item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n")
            append("    <item id=\"style\" href=\"style.css\" media-type=\"text/css\"/>\n")
            append("    <item id=\"titlepage\" href=\"titlepage.xhtml\" media-type=\"application/xhtml+xml\"/>\n")
            chapters.forEach {
                append("    <item id=\"${it.id}\" href=\"${it.href}\" media-type=\"application/xhtml+xml\"/>\n")
            }
        }
        val spine = buildString {
            append("    <itemref idref=\"titlepage\"/>\n")
            chapters.forEach { append("    <itemref idref=\"${it.id}\"/>\n") }
        }
        val optional = buildString {
            if (meta.author.isNotBlank()) {
                append("    <dc:creator>").append(Xml.escape(meta.author)).append("</dc:creator>\n")
            }
            if (meta.intro.isNotBlank()) {
                append("    <dc:description>").append(Xml.escape(meta.intro)).append("</dc:description>\n")
            }
            if (meta.category.isNotBlank()) {
                append("    <dc:subject>").append(Xml.escape(meta.category)).append("</dc:subject>\n")
            }
            if (meta.source.isNotBlank()) {
                append("    <dc:source>").append(Xml.escape(meta.source)).append("</dc:source>\n")
            }
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bookid">${Xml.escape(meta.identifier)}</dc:identifier>
    <dc:title>${Xml.escape(meta.title)}</dc:title>
    <dc:language>${Xml.escape(meta.language)}</dc:language>
$optional    <meta property="dcterms:modified">$modifiedText</meta>
  </metadata>
  <manifest>
$manifest  </manifest>
  <spine>
$spine  </spine>
</package>
"""
    }

    private companion object {
        const val CSS = """
body { margin: 1em; line-height: 1.7; }
h1 { font-size: 1.3em; line-height: 1.4; margin: 0 0 1em; }
p { margin: 0 0 0.8em; text-indent: 2em; }
h1.title, p.author { text-align: center; text-indent: 0; }
p.author { color: #555555; }
p.intro { font-size: 0.95em; text-indent: 0; }
"""
    }
}

/** Plain-text to XHTML helpers, shared by the writer and its tests. */
internal object Xml {
    fun escape(raw: String): String {
        val safe = sanitize(raw)
        val needsEscape = safe.any { it == '&' || it == '<' || it == '>' || it == '"' || it == '\'' }
        if (!needsEscape) return safe
        return buildString(safe.length + 16) {
            safe.forEach { c ->
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(c)
                }
            }
        }
    }

    /** Drops code points XML 1.0 forbids, including unpaired surrogates. */
    private fun sanitize(raw: String): String {
        var i = 0
        var needsClean = false
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            if (!isXmlChar(cp)) {
                needsClean = true
                break
            }
            i += Character.charCount(cp)
        }
        if (!needsClean) return raw

        i = 0
        val out = StringBuilder(raw.length)
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            if (isXmlChar(cp)) out.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    private fun isXmlChar(cp: Int): Boolean =
        cp == 0x9 || cp == 0xA || cp == 0xD ||
            cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF
}

/**
 * The parser joins chapter lines with "\n\n" (see data/parse/ChapterParser),
 * so splitting on exactly that pair is its inverse. A lone newline stays in
 * the paragraph and XML collapses it to a space.
 */
internal fun paragraphs(text: String): List<String> =
    text.replace("\r\n", "\n")
        .replace('\r', '\n')
        .split("\n\n")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

/** File-name policy for the shared EPUB (a receiving app may save it anywhere). */
internal object EpubPaths {
    private const val MAX_BASE_LENGTH = 80
    private val illegal = Regex("""[\\/:*?"<>|\u0000-\u001F\u007F-\u009F]""")
    private val spaces = Regex("""\s+""")

    fun fileNameFor(title: String, bookId: String): String {
        var base = illegal.replace(title.trim(), "_")
            .replace(spaces, " ")
            .trim()
            .trimStart('.')
            .trim()
            .trimEnd('.', ' ')
        if (base.isEmpty()) base = "book-$bookId"
        if (base.length > MAX_BASE_LENGTH) {
            base = base.take(MAX_BASE_LENGTH).trimEnd('.', ' ')
            if (base.isNotEmpty() && base.last().isHighSurrogate()) base = base.dropLast(1)
        }
        if (base.isEmpty()) base = "book-$bookId"
        return "$base.epub"
    }
}
