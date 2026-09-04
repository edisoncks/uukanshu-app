package cc.uukanshu.data.convert

import android.content.Context

/**
 * Traditional -> Simplified converter.
 *
 * Milestone 1 stub: identity. Milestone 6 bundles the OpenCC t2s
 * dictionary under assets (no whitelist — see CLI note about 土著/見微知著)
 * and applies it at render time; raw text stays cached.
 */
class T2S(private val appContext: Context) {
    fun convert(s: String): String = s
}
