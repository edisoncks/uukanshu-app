package cc.uukanshu

import android.app.Application
import cc.uukanshu.data.db.AppDb
import cc.uukanshu.data.download.BookDownloadManager
import cc.uukanshu.data.net.SiteApi
import cc.uukanshu.data.net.UukanshuGate
import cc.uukanshu.data.repo.BookRepo

class App : Application() {
    val gate by lazy { UukanshuGate() }
    val db by lazy { AppDb.get(this) }
    val site by lazy { SiteApi() }
    val repo by lazy { BookRepo(site, db, gate) }
    // App-scoped so full-book downloads survive popping detail.
    val downloadManager by lazy { BookDownloadManager(repo) }
}

fun android.content.Context.repo(): BookRepo =
    (applicationContext as App).repo
