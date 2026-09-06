package cc.uukanshu.data.net

import cc.uukanshu.BASE_URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Plain-HTTPS client for uukanshu.cc.
 *
 * Mirrors uukanshu-cli `fetch()`: browser-like headers, gzip (OkHttp
 * transparent), 3x retry with backoff on 408/429/5xx + transport errors,
 * Cloudflare interstitial sniff on <title>. No images are ever fetched:
 * callers only request HTML pages.
 *
 * Concurrency: every HTTP attempt holds [UukanshuGate] only for the
 * blocking execute itself. Retry backoff (`delay`) and HTML sniffing run
 * outside the gate so one failing request never head-of-line blocks
 * interactive taps for ~4.5s. Cancellation propagates immediately —
 * `CancellationException` is never swallowed by the retry loop.
 */
class SiteApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val gate: UukanshuGate = UukanshuGate(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SiteGateway {
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 12; Pixel 5) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "zh-TW,zh;q=0.9,en;q=0.8",
        "Upgrade-Insecure-Requests" to "1",
    )

    /** Marker for deterministic failures (404 and friends) that must fail fast. */
    private class NonRetryable(val failure: IOException) : IOException(failure.message)

    @Throws(IOException::class, CancellationException::class)
    override suspend fun get(url: String): String {
        val builder = request(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        return send(
            label = url,
            emptyBodyMessage = "empty body for $url",
            failurePrefix = "failed to fetch $url",
            call = builder.build(),
        )
    }

    @Throws(IOException::class, CancellationException::class)
    override suspend fun search(keyword: String): String {
        val form = FormBody.Builder()
            .add("searchkey", keyword)
            .add("searchtype", "all")
            .build()
        val builder = request("$BASE_URL/search").post(form)
        headers.forEach { (k, v) -> builder.header(k, v) }
        return send(
            label = "search",
            emptyBodyMessage = "empty search body",
            failurePrefix = "search failed",
            call = builder.build(),
        )
    }

    private fun request(url: String): Request.Builder =
        Request.Builder().url(url)

    /**
     * Single retry policy for every request: 3x with cancellable backoff on
     * 408/429/5xx + transport errors, fail fast on other 4xx, Cloudflare
     * sniff on the body. The gate is held per HTTP attempt only — backoff
     * sleeps and body sniffing run outside so a retrying request never
     * blocks the single-flight lane while doing no network I/O.
     */
    @Throws(IOException::class, CancellationException::class)
    private suspend fun send(
        label: String,
        emptyBodyMessage: String,
        failurePrefix: String,
        call: Request,
    ): String {
        var last: IOException? = null
        repeat(3) { attempt ->
            coroutineContext.ensureActive()
            try {
                val body: String = gate.withPermit {
                    withContext(ioDispatcher) {
                        runInterruptible {
                            client.newCall(call).execute().use { res ->
                                val code = res.code
                                if (code == 408 || code == 429 || code >= 500) {
                                    throw IOException("HTTP $code for $label")
                                }
                                // Deterministic client errors never heal on retry: fail
                                // fast instead of burning backoff delays.
                                if (!res.isSuccessful) throw NonRetryable(IOException("HTTP $code for $label"))
                                res.body?.string() ?: throw IOException(emptyBodyMessage)
                            }
                        }
                    }
                }
                throwIfBlocked(body)
                return body
            } catch (e: CancellationException) {
                throw e
            } catch (e: NonRetryable) {
                throw e.failure
            } catch (e: IOException) {
                last = e
                if (attempt < 2) delay(1500L * (attempt + 1))
            }
        }
        throw IOException("$failurePrefix: $last")
    }

    private val titleRe =
        Regex("<title>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    /**
     * Cloudflare blocks never clear within the retry window: fail fast via
     * [NonRetryable] instead of burning 3 attempts + backoff against a host
     * that just told us to go away (see SCRAPING.md).
     */
    private fun throwIfBlocked(page: String) {
        val title = titleRe.find(page)?.groupValues?.getOrNull(1) ?: return
        if ("Attention Required" in title || "Just a moment" in title || "you have been blocked" in title) {
            throw NonRetryable(IOException("blocked by Cloudflare — try again later or from a different network"))
        }
    }
}
