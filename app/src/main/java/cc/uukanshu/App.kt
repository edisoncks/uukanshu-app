package cc.uukanshu

import android.app.Application
import cc.uukanshu.data.convert.T2S
import cc.uukanshu.data.db.AppDb
import cc.uukanshu.data.download.BookDownloadManager
import cc.uukanshu.data.net.SiteApi
import cc.uukanshu.data.net.UukanshuGate
import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.data.repo.BookRepo
import cc.uukanshu.data.update.UpdateApi
import cc.uukanshu.data.update.UpdateDownloader

class App : Application() {
    val gate by lazy { UukanshuGate() }
    val db by lazy { AppDb.get(this) }
    // Single-flight lives in SiteApi per HTTP attempt; BookRepo no longer
    // wraps calls (nested Mutex acquisition would deadlock).
    val site by lazy { SiteApi(gate = gate) }
    val repo by lazy { BookRepo(site, db) }
    // App-scoped so full-book downloads survive popping detail.
    val downloadManager by lazy { BookDownloadManager(repo) }
    // Singletons: screens must use these, never `Prefs(app)`
    // per-composition. Fresh instances per screen wasted work and risked
    // divergent state; one instance also makes fakes injectable in tests.
    val prefs by lazy { Prefs(this) }
    val t2s by lazy { T2S() }
    // Updater singletons: owned here (not per-ViewModel) so MainActivity
    // and tests inject the same instances via RealAppContainer.
    val updateApi by lazy { UpdateApi() }
    val updateDownloader by lazy { UpdateDownloader(this) }
}
