package cc.uukanshu.data.convert

import android.content.Context

/**
 * Traditional -> Simplified converter (OpenCC data via opencc4j).
 *
 * Raw text is always cached; conversion applies at render time only.
 * Any failure falls back to the raw string — a missing/broken dict must
 * never crash the reader. No whitelist post-pass (see CLI note: it
 * corrupted 土著/見微知著 while missing its own purpose).
 */
class T2S(private val appContext: Context) {
    fun convert(s: String): String {
        if (s.isEmpty()) return s
        return runCatching {
            com.github.houbb.opencc4j.util.ZhConverterUtil.toSimple(s)
        }.getOrElse { s }
    }
}
