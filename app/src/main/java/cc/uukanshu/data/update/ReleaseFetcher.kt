package cc.uukanshu.data.update

/**
 * Release fetcher for the in-app updater.
 *
 * Extracted from [UpdateApi] so [cc.uukanshu.ui.update.UpdateViewModel]
 * depends on this narrow contract. JVM tests fake it; production wires
 * [UpdateApi]. Blocking — call on Dispatchers.IO.
 */
interface ReleaseFetcher {
    @Throws(java.io.IOException::class)
    fun fetchLatest(): UpdateInfo
}
