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
     * between two prereleases the larger suffix wins, with embedded numbers
     * compared numerically (`beta10` > `beta2`, which lexicographic order
     * gets wrong).
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
        return comparePreRelease(rPre, lPre) > 0
    }

    private val chunkRe = Regex("\\d+|\\D+")

    /**
     * Prerelease order: split into digit/non-digit runs so trailing numbers
     * compare numerically. Pure + unit-tested (see `UpdateCheckTest`).
     */
    fun comparePreRelease(a: String, b: String): Int {
        val ac = chunkRe.findAll(a).map { it.value }.toList()
        val bc = chunkRe.findAll(b).map { it.value }.toList()
        for (i in 0 until maxOf(ac.size, bc.size)) {
            val x = ac.getOrElse(i) { "" }
            val y = bc.getOrElse(i) { "" }
            val xn = x.toLongOrNull()
            val yn = y.toLongOrNull()
            val d = if (xn != null && yn != null) xn.compareTo(yn) else x.compareTo(y)
            if (d != 0) return d
        }
        return 0
    }
}

class UpdateApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) : ReleaseFetcher {
    /** Blocking; call on Dispatchers.IO. Throws [IOException] on failure. */
    @Throws(IOException::class)
    override fun fetchLatest(): UpdateInfo {
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
            // Fail closed: only a uukanshu-*.apk asset is ever offered for
            // install. A stray .apk must never be served to the installer.
            var match: Map<*, *>? = null
            for (entry in assets) {
                val a = entry as? Map<*, *> ?: continue
                val name = (a["name"] as? String).orEmpty()
                if (name.startsWith("uukanshu-") && name.endsWith(".apk")) {
                    match = a
                    break
                }
            }
            val asset = match ?: return null
            val url = ((asset["browser_download_url"] as? String) ?: "").trim()
            val name = ((asset["name"] as? String) ?: "").trim()
            if (url.isEmpty() || name.isEmpty()) return null
            // Strict contract: tag vX.Y.Z must ship exactly uukanshu-X.Y.Z.apk.
            // A version-mismatched asset (stale upload, second APK) fails
            // closed instead of offering the wrong binary to the installer.
            val expectedName = "uukanshu-${VersionCompare.normalize(tag)}.apk"
            if (name != expectedName) return null
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
