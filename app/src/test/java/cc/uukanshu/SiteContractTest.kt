package cc.uukanshu

import cc.uukanshu.data.net.SiteApi
import cc.uukanshu.data.parse.Parser
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** Single base-URL truth + scraper-drift guards (no network). */
class SiteContractTest {
    @Test fun parserBaseAliasesSiteBaseUrl() {
        assertEquals(BASE_URL, Parser.BASE)
        assertEquals("https://uukanshu.cc", BASE_URL)
    }

    private fun bodyClient(body: String) = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(body.toResponseBody(null))
                .build()
        })
        .build()

    @Test fun cloudflareInterstitialFailsLoudly() = runBlocking {
        val block = "<html><head><title>Just a moment...</title></head><body>cf</body></html>"
        try {
            SiteApi(bodyClient(block)).get("https://uukanshu.cc/book/1/")
            fail("expected Cloudflare IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Cloudflare"))
        }
    }

    @Test fun normalPagePassesThrough() = runBlocking {
        val html = "<html><head><title>Book</title></head><body>ok</body></html>"
        assertEquals(html, SiteApi(bodyClient(html)).get("https://uukanshu.cc/book/1/"))
    }
}
