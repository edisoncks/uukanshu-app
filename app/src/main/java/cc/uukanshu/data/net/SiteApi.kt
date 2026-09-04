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

    @Throws(IOException::class)
    fun get(url: String): String {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                val builder = Request.Builder().url(url)
                headers.forEach { (k, v) -> builder.header(k, v) }
                client.newCall(builder.build()).execute().use { res ->
                    val code = res.code
                    if (code == 408 || code == 429 || code >= 500) {
                        throw IOException("HTTP $code for $url")
                    }
                    if (!res.isSuccessful) throw IOException("HTTP $code for $url")
                    val body = res.body?.string() ?: throw IOException("empty body for $url")
                    throwIfBlocked(body)
                    return body
                }
            } catch (e: Exception) {
                last = e
                if (attempt < 2) Thread.sleep(1500L * (attempt + 1))
            }
        }
        throw IOException("failed to fetch $url: $last")
    }

    @Throws(IOException::class)
    fun search(keyword: String): String {
        val form = FormBody.Builder()
            .add("searchkey", keyword)
            .add("searchtype", "all")
            .build()
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                val builder = Request.Builder().url("$BASE_URL/search").post(form)
                headers.forEach { (k, v) -> builder.header(k, v) }
                client.newCall(builder.build()).execute().use { res ->
                    if (!res.isSuccessful) throw IOException("HTTP ${res.code} for search")
                    val body = res.body?.string() ?: throw IOException("empty search body")
                    throwIfBlocked(body)
                    return body
                }
            } catch (e: Exception) {
                last = e
                if (attempt < 2) Thread.sleep(1500L * (attempt + 1))
            }
        }
        throw IOException("search failed: $last")
    }

    private fun throwIfBlocked(page: String) {
        val title = Regex("<title>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(page)?.groupValues?.getOrNull(1) ?: return
        if ("Attention Required" in title || "Just a moment" in title || "you have been blocked" in title) {
            throw IOException("blocked by Cloudflare — try again later or from a different network")
        }
    }
}
