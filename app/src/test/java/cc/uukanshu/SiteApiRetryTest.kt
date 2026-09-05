package cc.uukanshu

import cc.uukanshu.data.net.SiteApi
import java.io.IOException
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

/**
 * Retry policy: 408/429/5xx + transport errors retry 3x with backoff;
 * deterministic client errors (404 and friends) fail fast on attempt 1.
 */
class SiteApiRetryTest {
    private fun clientFor(code: Int, counter: () -> Unit) = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            counter()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code).message("status")
                .body("".toResponseBody(null))
                .build()
        })
        .build()

    @Test fun notFoundFailsFastWithoutRetry() = runBlocking {
        var attempts = 0
        try {
            SiteApi(clientFor(404) { attempts++ }).get("https://uukanshu.cc/book/1/")
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("404"))
        }
        assertEquals("404 must not be retried", 1, attempts)
    }

    @Test fun serverErrorRetriesThreeTimes() = runBlocking {
        var attempts = 0
        try {
            SiteApi(clientFor(500) { attempts++ }).get("https://uukanshu.cc/book/1/")
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("500"))
        }
        assertEquals(3, attempts)
    }

    @Test fun searchNotFoundFailsFastWithoutRetry() = runBlocking {
        var attempts = 0
        try {
            SiteApi(clientFor(404) { attempts++ }).search("whatever")
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("404"))
        }
        assertEquals("search 404 must not be retried", 1, attempts)
    }
}
