package cc.uukanshu

import android.app.Application
import cc.uukanshu.data.convert.T2S
import cc.uukanshu.data.db.AppDb
import cc.uukanshu.data.download.BookDownloadManager
import cc.uukanshu.data.net.SiteApi
import cc.uukanshu.data.net.UukanshuGate
import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.data.repo.BookRepo

class App : Application() {
    val gate by lazy { UukanshuGate() }
    val db by lazy { AppDb.get(this) }
    // Single-flight lives in SiteApi per HTTP attempt; BookRepo no longer
    // wraps calls (nested Mutex acquisition would deadlock).
    val site by lazy { SiteApi(gate = gate) }
    val repo by lazy { BookRepo(site, db) }
    // App-scoped so full-book downloads survive popping detail.
    val downloadManager by lazy { BookDownloadManager(repo) }
    // Singletons: screens must use these, never `Prefs(app)` / `T2S(app)`
    // per-composition. Fresh instances per screen wasted work and risked
    // divergent state; one instance also makes fakes injectable in tests.
    val prefs by lazy { Prefs(this) }
    val t2s by lazy { T2S(this) }
}

/** Single cast site for `applicationContext as App` (was copy-pasted per screen). */
fun android.content.Context.app(): App =
    applicationContext as App

fun android.content.Context.repo(): BookRepo = app().repo
