package cc.uukanshu

import android.app.Application
import cc.uukanshu.data.db.AppDb
import cc.uukanshu.data.net.SiteApi
import cc.uukanshu.data.repo.BookRepo

class App : Application() {
    val db by lazy { AppDb.get(this) }
    val site by lazy { SiteApi() }
    val repo by lazy { BookRepo(site, db) }
}

fun android.content.Context.repo(): BookRepo =
    (applicationContext as App).repo
