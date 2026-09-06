package cc.uukanshu.data.update

import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * APK download gateway for the in-app updater.
 *
 * Extracted from [UpdateDownloader] so the update ViewModel depends on
 * this narrow contract. JVM tests fake it; production wires
 * [UpdateDownloader]. Static file-state helpers
 * ([UpdateDownloader.isComplete]/[isInstallable]/etc.) stay on the
 * companion — they are pure and already unit-tested.
 */
interface ApkDownloader {
    fun apkFile(info: UpdateInfo): File
    fun enqueue(info: UpdateInfo): Long
    fun cancel(downloadId: Long)
    fun observe(downloadId: Long): Flow<DownloadStatus>
}
