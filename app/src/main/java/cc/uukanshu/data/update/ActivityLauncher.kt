package cc.uukanshu.data.update

import android.content.Intent

/**
 * System activity fire for the in-app updater (installer, unknown-sources
 * settings, browser fallback).
 *
 * Extracted so [cc.uukanshu.ui.update.UpdateViewModel] stays JVM-testable:
 * production passes `app.startActivity`, tests inject a throwing fake to
 * prove intent failures surface as dialog errors instead of crashing the
 * process. See ARCHITECTURE.md (in-app update).
 */
fun interface ActivityLauncher {
    fun start(intent: Intent)
}
