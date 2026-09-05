package cc.uukanshu.data.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** Terminal states for one [DownloadManager] id. */
sealed interface DownloadStatus {
    /** [progress] is 0..1, or null when the total size is unknown. */
    data class Running(val progress: Float?) : DownloadStatus
    data object Success : DownloadStatus
    /** [reason] is already human-readable for the dialog. */
    data class Failed(val reason: String) : DownloadStatus
}

/**
 * APK download via the system [DownloadManager] (survives process death,
 * shows its own completion notification) plus package-installer intents.
 *
 * Files land in the app-private downloads dir, so no storage permission is
 * needed on minSdk 31; sharing with the installer goes through FileProvider.
 */
class UpdateDownloader(private val context: Context) {
    private val dm: DownloadManager
        get() = context.getSystemService(DownloadManager::class.java)

    /** File the APK for [info] downloads to (deleted first if stale). */
    fun apkFile(info: UpdateInfo): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), info.apkName)

    /**
     * Enqueue the download. Returns the [DownloadManager] id, or -1 when a
     * complete file for this version is already on disk (caller can install).
     * Completeness means byte-exact match against the GitHub asset size;
     * anything else (missing, empty, partial, or unknown size) is deleted
     * and re-downloaded. Length > 0 alone proves nothing after a kill.
     */
    fun enqueue(info: UpdateInfo): Long {
        deleteStaleApks(keepName = info.apkName)
        val file = apkFile(info)
        if (isComplete(file, info.size)) return -1L
        if (file.exists()) file.delete()
        val req = DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle("uukanshu ${info.tag}")
            .setDescription(info.apkName)
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
            )
            .setDestinationInExternalFilesDir(
                context, Environment.DIRECTORY_DOWNLOADS, info.apkName,
            )
            .setAllowedOverMetered(true)
        return dm.enqueue(req)
    }

    fun cancel(downloadId: Long) {
        runCatching { dm.remove(downloadId) }
    }

    /**
     * Terminal-state flow for one [DownloadManager] id: emits [query]
     * until it leaves Running, then completes. The ViewModel used to own
     * this poll loop inline (delay + error mapping included); owning it
     * here keeps all DownloadManager knowledge in one place and leaves
     * the VM as a pure collector that maps states to dialog state.
     */
    fun observe(downloadId: Long, intervalMs: Long = 500): Flow<DownloadStatus> = flow {
        while (true) {
            val s = query(downloadId)
            emit(s)
            if (s !is DownloadStatus.Running) return@flow
            delay(intervalMs)
        }
    }.flowOn(Dispatchers.IO)

    fun query(downloadId: Long): DownloadStatus {
        dm.query(DownloadManager.Query().setFilterById(downloadId)).use { c ->
            if (!c.moveToFirst()) return DownloadStatus.Failed("download not found")
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(
                c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
            )
            val total = c.getLong(
                c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
            )
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.Success
                DownloadManager.STATUS_FAILED -> {
                    val reason = c.getInt(
                        c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON),
                    )
                    DownloadStatus.Failed("download failed (reason $reason)")
                }
                else -> {
                    val p = if (total > 0 && done >= 0) (done.toFloat() / total).coerceIn(0f, 1f)
                    else null
                    DownloadStatus.Running(p)
                }
            }
        }
    }

    /** Remove `uukanshu-*.apk` files from previous versions. */
    fun deleteStaleApks(keepName: String) {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        dir.listFiles { f ->
            f.isFile && f.name.startsWith("uukanshu-") && f.name.endsWith(".apk") &&
                f.name != keepName
        }?.forEach { runCatching { it.delete() } }
    }

    /** Single decision table for APK file state: call the wrong gate and a
     * killed-process partial either blocks install or gets installed.
     * Use [apkState] at call sites; [isComplete]/[isInstallable] stay as
     * the tested predicates behind it. */
    sealed interface ApkState {
        data object Missing : ApkState
        /** Present but shorter/longer than the release size (or empty). */
        data object Partial : ApkState
        /** Byte-exact match, or non-empty after a fresh DM SUCCESS with unknown size. */
        data object Ready : ApkState
    }

    companion object {

        /**
         * Resolve [ApkState] for [file]. `dmSuccess=true` only immediately
         * after DownloadManager reported SUCCESS for this id (system receipt);
         * everywhere else (alreadyHave/enqueue) pass false so unknown size
         * never counts as complete.
         */
        fun apkState(file: File, expectedSize: Long?, dmSuccess: Boolean = false): ApkState {
            if (!file.exists() || file.length() <= 0) return ApkState.Missing
            if (expectedSize != null) {
                return if (file.length() == expectedSize) ApkState.Ready else ApkState.Partial
            }
            // Unknown size: strict without a DM receipt, lenient with one.
            return if (dmSuccess) ApkState.Ready else ApkState.Partial
        }

        /** Byte-exact completeness check shared by enqueue/alreadyHave.
         *
         * Strict by design: unknown size never counts as complete, so a
         * partial file left by a killed process can never skip re-download.
         */
        fun isComplete(file: File, expectedSize: Long?): Boolean =
            apkState(file, expectedSize, dmSuccess = false) == ApkState.Ready

        /**
         * Install gate: byte-exact when the release reports a size, otherwise
         * any non-empty file that DownloadManager just reported SUCCESS for.
         * The strict [isComplete] path stays for alreadyHave/enqueue (no DM
         * receipt there); this lenient path only runs after a fresh Success
         * or an explicit user tap, so a killed-process partial can never
         * sneak through alreadyHave, but a sizeless release stays installable.
         */
        fun isInstallable(file: File, expectedSize: Long?): Boolean =
            // Explicit user tap / post-SUCCESS: treat unknown size leniently.
            apkState(file, expectedSize, dmSuccess = true) == ApkState.Ready

        /** Local version via PackageManager (no BuildConfig flag needed). */
        fun currentVersion(context: Context): String = runCatching {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0),
                ).versionName
            } else {
                pm.getPackageInfo(context.packageName, 0).versionName
            }.orEmpty()
        }.getOrElse { "" }

        /** Whether the system will let us fire the installer directly. */
        fun canInstall(context: Context): Boolean =
            runCatching { context.packageManager.canRequestPackageInstalls() }
                .getOrDefault(false)

        /** System screen where the user enables "install unknown apps". */
        fun unknownSourcesIntent(context: Context): Intent =
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Installer intent for a fully-downloaded [file]. */
        fun installIntent(context: Context, file: File): Intent {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file,
            )
            return Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        /** Fallback: open the release page in the browser. */
        fun browserIntent(url: String): Intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
