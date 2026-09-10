package cc.uukanshu.data.update

import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * APK download gateway for the in-app updater.
 *
 * Extracted from [UpdateDownloader] so the update ViewModel depends on
 * this narrow contract. JVM tests fake it; production wires
 * [UpdateDownloader]. Static file-state helpers
 * ([UpdateDownloader.apkState]/[UpdateDownloader.apkStateIO]/etc.) stay on
 * the companion — one decision table (pure primitives + single-stat IO
 * wrapper + ApkFailure classifier), already unit-tested.
 */
interface ApkDownloader {
    fun apkFile(info: UpdateInfo): File
    fun enqueue(info: UpdateInfo): Long
    fun cancel(downloadId: Long)
    fun observe(downloadId: Long): Flow<DownloadStatus>
}
