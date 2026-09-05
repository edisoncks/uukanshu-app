package cc.uukanshu

import cc.uukanshu.data.parse.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserTest {
    @Test fun bookUrlNormalization() {
        assertEquals("https://uukanshu.cc/book/18957/", Parser.bookUrlOrNull("https://uukanshu.cc/book/18957/"))
        assertEquals("https://uukanshu.cc/book/18957/", Parser.bookUrlOrNull("http://www.uukanshu.cc/book/18957/index.html"))
        assertNull(Parser.bookUrlOrNull("https://uukanshu.cc/book/18957/11326074.html"))
    }

    @Test fun bookUrlOverflowReturnsNullInsteadOfThrowing() {
        assertNull(Parser.bookUrlOrNull("https://uukanshu.cc/book/99999999999999999999/"))
    }

    @Test fun bookIdAcceptsMissingTrailingSlash() {
        assertEquals("23674", Parser.bookIdOrNull("https://uukanshu.cc/book/23674"))
        assertEquals("23674", Parser.bookIdOrNull("/book/23674"))
        assertEquals("23674", Parser.bookIdOrNull("/book/23674/"))
    }

    @Test fun categoryAcceptsSlashlessHref() {
        val html = """
            <div class="bookbox"><div class="bookinfo"><div class="bookname">
            <a href="/book/23674">Slashless</a></div>
            </div></div>
        """.trimIndent()
        val books = Parser.parseCategory(html)
        assertEquals(1, books.size)
        assertEquals("23674", books[0].id)
    }

    @Test fun tocKeepsLastOccurrenceAndFiltersBook() {
        val html = """
            <a href="/book/18957/11326074.html">200，夜訪</a>
            <dd><a href="/book/18957/10921502.html">001，第一章</a></dd>
            <dd><a href="/book/18957/10921505.html">002，第二章</a></dd>
            <dd><a href="/book/99999/111.html">別書推薦</a></dd>
            <dd><a href="/book/18957/11326074.html">200，夜訪</a></dd>
        """.trimIndent()
        val all = Parser.parseToc(html, "18957")
        assertEquals(listOf("001，第一章", "002，第二章", "200，夜訪"), all.map { it.title })
        assertEquals(listOf(1, 2, 3), all.map { it.position })
        // numeric book id: "018957" still matches 18957
        assertEquals(3, Parser.parseToc(html, "018957").size)
        // no filter accepts every book
        assertEquals(4, Parser.parseToc(html, null).size)
    }

    @Test fun tocDedupsLatestBlockFirstOccurrence() {
        // 'latest updates' block duplicates the first chapter; reading order wins.
        val html = """
            <a href="https://uukanshu.cc/book/100/200.html">最新：003</a>
            <a href="/book/100/101.html">001</a>
            <a href="/book/100/102.html">002</a>
            <a href="/book/100/200.html">003</a>
        """.trimIndent()
        val toc = Parser.parseToc(html, "100")
        assertEquals(listOf("001", "002", "003"), toc.map { it.title })
    }

    @Test fun chapterCutsAtMuluBoxAndNavRow() {
        val html = """
            <html><body>
            <a href="/book/18957/">天魔降臨</a>
            <h1>001，我怎麼成了魔教教主？</h1>
            <div class="readcotent">第一段。<br>第二段。<br>
            有人說上一章很好笑但是正文繼續。
            <div class="mulu-box"><a href="/book/18957/1.html">上一章</a></div>
            </div></body></html>
        """.trimIndent()
        val c = Parser.parseChapter(html, "https://uukanshu.cc/book/18957/10921502.html")
        assertEquals("天魔降臨", c.book)
        assertTrue(c.text.contains("第一段"))
        assertTrue("footer noise must be cut", !c.text.contains("mulu-box"))
    }

    @Test fun chapterNavAcceptsHttpAndWwwHosts() {
        // bookUrlOrNull treats http+www as valid; nav validation must agree,
        // or mid-book links read as end-of-book (null).
        val html = """
            <a href="http://uukanshu.cc/book/18957/10921502.html">上一章</a>
            <a href="https://www.uukanshu.cc/book/18957/10921508.html">下一章</a>
        """.trimIndent()
        val c = Parser.parseChapter(
            "<h1>T</h1><div class=\"readcotent\">正文。<br></div>$html",
            "https://uukanshu.cc/book/18957/10921505.html",
        )
        assertEquals("http://uukanshu.cc/book/18957/10921502.html", c.prevUrl)
        assertEquals("https://www.uukanshu.cc/book/18957/10921508.html", c.nextUrl)
    }

    @Test fun tocAcceptsHttpAndWwwHosts() {
        val html = """
            <dd><a href="http://uukanshu.cc/book/100/101.html">001</a></dd>
            <dd><a href="https://www.uukanshu.cc/book/100/102.html">002</a></dd>
        """.trimIndent()
        val toc = Parser.parseToc(html, "100")
        assertEquals(listOf("001", "002"), toc.map { it.title })
    }

    @Test fun chapterNavValidation() {
        val html = """
            <a href="/book/18957/10921502.html">上一章</a>
            <a href="/book/18957/">目录</a>
            <a href="lastchapter.php">下一章</a>
        """.trimIndent()
        val c = Parser.parseChapter(
            "<h1>T</h1><div class=\"readcotent\">正文。<br></div>$html",
            "https://uukanshu.cc/book/18957/10921505.html",
        )
        assertEquals("https://uukanshu.cc/book/18957/10921502.html", c.prevUrl)
        // TOC index / php stubs are not chapters -> null (end-of-book semantics)
        assertNull(c.nextUrl)
    }

    @Test fun categoryStripsHotSpans() {
        val html = """
            <div class="bookbox"><div class="p10"><span class="num">1</span>
            <div class="bookinfo"><div class="bookname">
            <a href="https://uukanshu.cc/book/23674/">斗破之平<span class="hot">凡人</span>生</a></div>
            <div class="author">作者：流雲香菇</div><div class="author">字數：4444133</div>
            <div class="cat"><span>更新到：</span><a href="https://uukanshu.cc/book/23674/17754351.html">第一千二百六十八章 深入</a></div>
            <div class="update"><span>簡介：</span>穿越斗破。</div>
            </div></div></div>
        """.trimIndent()
        val books = Parser.parseCategory(html)
        assertEquals(1, books.size)
        assertEquals("斗破之平凡人生", books[0].title)
        assertEquals("23674", books[0].id)
        assertEquals("流雲香菇", books[0].author)
    }

    @Test fun searchExactMatchBookPageYieldsSingleResult() {
        // POST /search with an exact title returns the book detail page
        // (no .bookbox cards); parseSearch must synthesize the one result.
        val html = """
            <html><head><title>天魔降臨最新章節</title>
            <meta property="og:type" content="novel">
            <meta property="og:book_id" content="18957">
            <meta property="og:novel:read_url" content="https://uukanshu.cc/book/18957/">
            </head><body>
            <h1 class="booktitle">天魔降臨</h1>
            <p class="booktag"><a class="red" href="/x" title="作者：佚名">佚名</a>
            <span class="blue">1139054字</span> <span class="blue">玄幻奇幻</span>
            <span class="red">連載</span></p>
            <p class="bookintro">穿越者成了魔教教主。</p>
            <a class="bookchapter" href="/book/18957/11326074.html">200，夜訪</a>
            </body></html>
        """.trimIndent()
        val res = Parser.parseSearch(html)
        assertEquals(1, res.total)
        assertEquals(1, res.books.size)
        assertEquals("18957", res.books[0].id)
        assertEquals("天魔降臨", res.books[0].title)
        assertEquals("佚名", res.books[0].author)
    }

    @Test fun searchBookPageWithoutIdStaysEmpty() {
        val html = "<html><body><h1 class=\"booktitle\">孤書</h1></body></html>"
        val res = Parser.parseSearch(html)
        assertEquals(0, res.books.size)
    }

    @Test fun searchTotalWithHottextTagParses() {
        // Live markup: 共有<b class="hottext"> 4 </b>條.
        val html = """共有<b class="hottext"> 4 </b>條
            <div class="bookbox"><div class="bookinfo"><div class="bookname">
            <a href="/book/26841/">X</a></div></div></div>""".trimIndent()
        val res = Parser.parseSearch(html)
        assertEquals(4, res.total)
        assertEquals(1, res.books.size)
    }

    @Test fun lastupdateCardVariant() {
        // /top/lastupdate_N.html uses <h4 class="bookname"> (categories and
        // search use <div>); the same parser must handle it, with intro.
        val html = """
            <div class="bookbox"><div class="p10"><span class="num">1</span>
            <div class="bookinfo"><h4 class="bookname"><a href="https://uukanshu.cc/book/26544/">獨守要塞三年，我成了長夜領主</a></h4>
            <div class="author">作者：三陽開太泰</div><div class="author">字數：833947</div>
            <div class="cat"><span>更新到：</span><a href="https://uukanshu.cc/book/26544/17772657.html">第218章</a></div>
            <div class="update"><span>簡介：</span>黑暗入侵，全球崩潰。</div>
            </div></div></div>
        """.trimIndent()
        val books = Parser.parseCategory(html)
        assertEquals(1, books.size)
        assertEquals("26544", books[0].id)
        assertEquals("獨守要塞三年，我成了長夜領主", books[0].title)
        assertEquals("黑暗入侵，全球崩潰。", books[0].intro)
    }
}
