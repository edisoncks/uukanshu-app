package cc.uukanshu.data.update

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * In-app updater backend: reads the latest GitHub Release and picks the
 * `uukanshu-{version}.apk` asset for download.
 *
 * Release contract (see RELEASING.md): tag `vX.Y.Z` matches
 * `versionName X.Y.Z`, exactly one `uukanshu-*.apk` asset, body = changelog.
 */
data class UpdateInfo(
    /** Raw tag, e.g. `v1.0.15`. */
    val tag: String,
    /** Normalized version without leading `v`, e.g. `1.0.15`. */
    val version: String,
    /** Release notes shown in the update dialog. May be empty. */
    val changelog: String,
    val apkUrl: String,
    val apkName: String,
    val htmlUrl: String,
    /** Asset size in bytes from GitHub, null when unknown. */
    val size: Long? = null,
)

/** Numeric dot-separated compare (`1.0.15` > `1.0.9`); pure + unit-tested. */
object VersionCompare {
    /** Strip leading `v`, cut `+build` metadata, keep `1.0.15[-suffix]`. */
    fun normalize(v: String): String =
        v.trim().trimStart('v', 'V').substringBefore('+').trim()

    private fun coreParts(v: String): List<Int> =
        normalize(v).substringBefore('-').split('.')
            .map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }

    /**
     * True when [remote] is strictly newer than [local].
     * A bare release beats its prerelease (`1.0.15` > `1.0.15-beta`);
     * between two prereleases the lexicographically larger suffix wins.
     */
    fun isNewer(remote: String, local: String): Boolean {
        val r = normalize(remote)
        val l = normalize(local)
        val rp = coreParts(r)
        val lp = coreParts(l)
        for (i in 0 until maxOf(rp.size, lp.size)) {
            val d = (rp.getOrElse(i) { 0 }).compareTo(lp.getOrElse(i) { 0 })
            if (d != 0) return d > 0
        }
        val rPre = r.substringAfter('-', "")
        val lPre = l.substringAfter('-', "")
        if (rPre.isEmpty() && lPre.isNotEmpty()) return true
        if (rPre.isNotEmpty() && lPre.isEmpty()) return false
        return rPre > lPre
    }
}

class UpdateApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    /** Blocking; call on Dispatchers.IO. Throws [IOException] on failure. */
    @Throws(IOException::class)
    fun fetchLatest(): UpdateInfo {
        val req = Request.Builder()
            .url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "uukanshu-app (Android)")
            .build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code} for $LATEST_URL")
            val body = res.body?.string() ?: throw IOException("empty release body")
            return parse(body) ?: throw IOException("no uukanshu-*.apk asset in latest release")
        }
    }

    companion object {
        const val REPO = "edisoncks/uukanshu-app"
        const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"

        /**
         * Pure parse of a `releases/latest` payload; null when unusable.
         * Uses [JsonMini] (not org.json) so it also runs in JVM unit tests.
         */
        fun parse(json: String): UpdateInfo? = runCatching {
            val root = JsonMini.parse(json) as? Map<*, *> ?: return null
            val tag = (root["tag_name"] as? String)?.trim().orEmpty()
            if (tag.isEmpty()) return null
            val assets = root["assets"] as? List<*> ?: return null
            var fallback: Map<*, *>? = null
            var match: Map<*, *>? = null
            for (entry in assets) {
                val a = entry as? Map<*, *> ?: continue
                val name = (a["name"] as? String).orEmpty()
                if (!name.endsWith(".apk")) continue
                if (fallback == null) fallback = a
                if (name.startsWith("uukanshu-") && name.endsWith(".apk")) {
                    match = a
                    break
                }
            }
            val asset = match ?: fallback ?: return null
            val url = ((asset["browser_download_url"] as? String) ?: "").trim()
            val name = ((asset["name"] as? String) ?: "").trim()
            if (url.isEmpty() || name.isEmpty()) return null
            // GitHub reports asset size as a JSON number; absent on old payloads.
            val size = (asset["size"] as? Number)?.toLong()?.takeIf { it > 0 }
            UpdateInfo(
                tag = tag,
                version = VersionCompare.normalize(tag),
                changelog = ((root["body"] as? String) ?: "").trim(),
                apkUrl = url,
                apkName = name,
                htmlUrl = ((root["html_url"] as? String) ?: "").trim(),
                size = size,
            )
        }.getOrNull()
    }
}
