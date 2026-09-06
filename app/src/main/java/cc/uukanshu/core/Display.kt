package cc.uukanshu.core

import cc.uukanshu.di.ConvertApi

/**
 * Single Traditional/Simplified rendering rule for the whole app.
 *
 * Raw text is always cached/stored Traditional; conversion applies at render
 * time only. Every `display(raw)` in ViewModels and `MainActivity` delegates
 * here so a forgotten `if (simplified)` cannot leave one screen mixing
 * scripts while the rest follow the toggle.
 */
object Display {
    fun text(t2s: ConvertApi, raw: String, simplified: Boolean): String =
        if (simplified) t2s.convert(raw) else raw
}
