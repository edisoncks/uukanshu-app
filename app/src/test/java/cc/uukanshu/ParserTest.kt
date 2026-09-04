package cc.uukanshu

import cc.uukanshu.data.parse.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParserTest {
    @Test fun bookUrlNormalization() {
        assertEquals("https://uukanshu.cc/book/18957/", Parser.bookUrlOrNull("https://uukanshu.cc/book/18957/"))
        assertEquals("https://uukanshu.cc/book/18957/", Parser.bookUrlOrNull("http://www.uukanshu.cc/book/18957/index.html"))
        assertNull(Parser.bookUrlOrNull("https://uukanshu.cc/book/18957/11326074.html"))
    }
}
