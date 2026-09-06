package cc.uukanshu

import cc.uukanshu.data.net.BulkFetch
import cc.uukanshu.data.net.SiteApi
import cc.uukanshu.data.net.UukanshuGate
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
 * deterministic client errors (404 and friends) and Cloudflare blocks fail
 * fast on attempt 1 (a block never clears inside the retry window).
 */
class SiteApiRetryTest {
    private fun clientFor(code: Int, body: String = "", counter: () -> Unit) = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            counter()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code).message("status")
                .body(body.toResponseBody(null))
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

    @Test fun cloudflareBlockFailsFastWithoutRetry() = runBlocking {
        var attempts = 0
        val challenge = "<html><head><title>Just a moment...</title></head><body>cf</body></html>"
        try {
            SiteApi(clientFor(200, challenge) { attempts++ }).get("https://uukanshu.cc/book/1/")
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Cloudflare"))
        }
        assertEquals("Cloudflare block must not be retried", 1, attempts)
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

    @Test fun bulkContextRoutesToBulkClient() = runBlocking {
        var mainCalls = 0
        var bulkCalls = 0
        val api = SiteApi(
            clientFor(200, "ok") { mainCalls++ },
            clientFor(200, "ok") { bulkCalls++ },
        )
        api.get("https://uukanshu.cc/book/1/")
        assertEquals(1, mainCalls)
        assertEquals(0, bulkCalls)
        kotlinx.coroutines.withContext(BulkFetch) {
            api.get("https://uukanshu.cc/book/1/")
        }
        assertEquals(1, mainCalls)
        assertEquals(1, bulkCalls)
    }

    @Test fun totalDeadlineBoundsHangingRequestAndReleasesGate() = runBlocking {
        // A dead network must time out instead of wedging the lane;
        // the gate must be free for the next call afterwards.
        val gate = UukanshuGate()
        val hanging = OkHttpClient.Builder()
            .addInterceptor(Interceptor {
                java.util.concurrent.CountDownLatch(1).await()
                throw IOException("unreachable")
            })
            .build()
        val stuck = SiteApi(hanging, hanging, gate, interactiveDeadlineMs = 500L)
        try {
            stuck.get("https://uukanshu.cc/book/1/")
            fail("expected timeout")
        } catch (e: Exception) {
            assertTrue(
                "expected TimeoutCancellationException, got $e",
                e is kotlinx.coroutines.TimeoutCancellationException,
            )
        }
        var fastCalls = 0
        val fast = SiteApi(clientFor(200, "ok") { fastCalls++ }, clientFor(200, "ok") {}, gate)
        fast.get("https://uukanshu.cc/book/1/")
        assertEquals(1, fastCalls)
    }
}
