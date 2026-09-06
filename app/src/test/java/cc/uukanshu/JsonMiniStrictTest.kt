package cc.uukanshu

import cc.uukanshu.data.update.JsonMini
import cc.uukanshu.data.update.UpdateApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Strictness locks for the updater payload path.
 *
 * GitHub payloads are exact; a truncated/corrupt body plus junk must fail
 * closed (no update offered) rather than mask corruption as a valid release.
 * JsonMini exists because org.json is stubbed in JVM tests — keep it, test it.
 */
class JsonMiniStrictTest {
    @Test fun trailingGarbageFails() {
        try {
            JsonMini.parse("""{"a":1} junk""")
            throw AssertionError("must throw on trailing data")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        assertNull(UpdateApi.parse("""{"tag_name":"v1.0.15","assets":[]} trailing"""))
    }

    @Test fun escapesRoundTrip() {
        @Suppress("UNCHECKED_CAST")
        val m = JsonMini.parse("""{"s":"a\nb\"c\\d\u4e2d"}""") as Map<String, Any?>
        assertEquals("a\nb\"c\\d中", m["s"])
    }

    @Test fun numbersAndLiterals() {
        @Suppress("UNCHECKED_CAST")
        val m = JsonMini.parse("""{"i":123,"f":1.5,"t":true,"f2":false,"n":null,"arr":[1,2]}""") as Map<String, Any?>
        assertEquals(123L, m["i"])
        assertEquals(true, m["t"])
        assertEquals(null, m["n"])
    }

    @Test fun truncatedPayloadFailsClosed() {
        assertNull(UpdateApi.parse("""{"tag_name":"v1.0.15","assets":[{"name":"uukanshu-1.0.15.apk""""))
        assertNull(UpdateApi.parse(""))
        assertNull(UpdateApi.parse("not json"))
    }

    @Test fun badEscapeFails() {
        try {
            JsonMini.parse("""{"s":"\q"}""")
            throw AssertionError("must throw on bad escape")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
