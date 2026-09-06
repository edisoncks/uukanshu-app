package cc.uukanshu.data.convert

import android.content.Context

/**
 * Traditional -> Simplified converter (OpenCC data via opencc4j).
 *
 * Raw text is always cached; conversion applies at render time only.
 * Any failure falls back to the raw string — a missing/broken dict must
 * never crash the reader. No whitelist post-pass (see CLI note: it
 * corrupted 土著/見微知著 while missing its own purpose).
 *
 * Small synchronized LRU (500 entries) so list recompositions re-converting
 * the same titles do not hit OpenCC on every frame. Long chapter bodies
 * (>4k chars) bypass the cache to bound memory. Pure eviction logic lives
 * in [CachePolicy] for JVM tests (no Android needed).
 */
class T2S(private val appContext: Context) : cc.uukanshu.di.ConvertApi {
    object CachePolicy {
        const val MAX_ENTRIES = 500
        const val MAX_CACHED_LEN = 4000
        fun shouldCache(s: String): Boolean = s.isNotEmpty() && s.length <= MAX_CACHED_LEN
    }

    // Access-order LinkedHashMap as LRU; guarded by its own monitor.
    // Never holds long bodies (see shouldCache) so worst-case ~500 short UI strings.
    private val cache = object : LinkedHashMap<String, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
            size > CachePolicy.MAX_ENTRIES
    }

    override fun convert(s: String): String {
        if (s.isEmpty()) return s
        if (!CachePolicy.shouldCache(s)) return convertUncached(s)
        synchronized(cache) { cache[s]?.let { return it } }
        val out = convertUncached(s)
        synchronized(cache) { cache[s] = out }
        return out
    }

    private fun convertUncached(s: String): String = runCatching {
        com.github.houbb.opencc4j.util.ZhConverterUtil.toSimple(s)
    }.getOrElse { s }

    /** Test seam: current cache size. */
    fun cacheSizeForTest(): Int = synchronized(cache) { cache.size }
}
