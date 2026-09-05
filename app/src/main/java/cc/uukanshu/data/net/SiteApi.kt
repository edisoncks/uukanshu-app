package cc.uukanshu.data.net

import cc.uukanshu.BASE_URL
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Plain-HTTPS client for uukanshu.cc.
 *
 * Mirrors uukanshu-cli `fetch()`: browser-like headers, gzip (OkHttp
 * transparent), 3x retry with backoff on 408/429/5xx + transport errors,
 * Cloudflare interstitial sniff on <title>. No images are ever fetched:
 * callers only request HTML pages.
 *
 * GET and POST used to carry their own copies of the retry loop (backoff,
 * fail-fast, empty-body and final-failure messages) — any policy tweak had
 * to land twice identically. All requests now go through [send], with only
 * the per-endpoint message nouns parameterized.
 */
class SiteApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
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

    @Throws(IOException::class)
    fun get(url: String): String {
        val builder = request(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        return send(
            label = url,
            emptyBodyMessage = "empty body for $url",
            failurePrefix = "failed to fetch $url",
            call = builder.build(),
        )
    }

    @Throws(IOException::class)
    fun search(keyword: String): String {
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
     * Single retry policy for every request: 3x with backoff on 408/429/5xx
     * + transport errors, fail fast on other 4xx, Cloudflare sniff on the
     * body. Only the message nouns vary per endpoint.
     */
    @Throws(IOException::class)
    private fun send(
        label: String,
        emptyBodyMessage: String,
        failurePrefix: String,
        call: Request,
    ): String {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                client.newCall(call).execute().use { res ->
                    val code = res.code
                    if (code == 408 || code == 429 || code >= 500) {
                        throw IOException("HTTP $code for $label")
                    }
                    // Deterministic client errors never heal on retry: fail
                    // fast instead of burning ~4.5s of backoff sleeps.
                    if (!res.isSuccessful) throw NonRetryable(IOException("HTTP $code for $label"))
                    val body = res.body?.string() ?: throw IOException(emptyBodyMessage)
                    throwIfBlocked(body)
                    return body
                }
            } catch (e: NonRetryable) {
                throw e.failure
            } catch (e: Exception) {
                last = e
                if (attempt < 2) Thread.sleep(1500L * (attempt + 1))
            }
        }
        throw IOException("$failurePrefix: $last")
    }

    private fun throwIfBlocked(page: String) {
        val title = Regex("<title>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(page)?.groupValues?.getOrNull(1) ?: return
        if ("Attention Required" in title || "Just a moment" in title || "you have been blocked" in title) {
            throw IOException("blocked by Cloudflare — try again later or from a different network")
        }
    }
}
