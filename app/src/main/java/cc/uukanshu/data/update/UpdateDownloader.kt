package cc.uukanshu.data.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.annotation.WorkerThread
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest
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
class UpdateDownloader(private val context: Context) : ApkDownloader {
    private val dm: DownloadManager
        get() = context.getSystemService(DownloadManager::class.java)

    /** File the APK for [info] downloads to (deleted first if stale). */
    override fun apkFile(info: UpdateInfo): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), info.apkName)

    /**
     * Enqueue the download. Returns the [DownloadManager] id, or -1 when a
     * complete file for this version is already on disk (caller can install).
     * Completeness means byte-exact size match — and sha256 digest match when
     * the release ships one; anything else (missing, empty, partial, wrong
     * digest, or unknown size) is deleted and re-downloaded. Length > 0 alone
     * proves nothing after a kill.
     *
     * Blocking file IO (digest) — call on Dispatchers.IO (callers already are).
     */
    @WorkerThread
    override fun enqueue(info: UpdateInfo): Long {
        deleteStaleApks(keepName = info.apkName)
        val file = apkFile(info)
        if (isCompleteIO(file, info.size, info.sha256)) return -1L
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

    override fun cancel(downloadId: Long) {
        runCatching { dm.remove(downloadId) }
    }

    /**
     * Terminal-state flow for one [DownloadManager] id: emits [query]
     * until it leaves Running, then completes. The ViewModel used to own
     * this poll loop inline (delay + error mapping included); owning it
     * here keeps all DownloadManager knowledge in one place and leaves
     * the VM as a pure collector that maps states to dialog state.
     */
    override fun observe(downloadId: Long): Flow<DownloadStatus> = observe(downloadId, 500)

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
     * Call sites use pure [apkState] or IO wrapper [apkStateIO];
     * [isCompleteIO] is the strict boolean behind enqueue/already-have. */
    sealed interface ApkState {
        data object Missing : ApkState
        /** Present but shorter/longer than the release size, sha256 mismatch (or
         * an expected digest with no computed hash), or empty. */
        data object Partial : ApkState
        /** Byte-exact match, or non-empty after a fresh DM SUCCESS with unknown size. */
        data object Ready : ApkState
    }

    /** Gate-failure kind for dialog text via `Errors.friendly` (no exception alloc). */
    enum class ApkFailure { CHECKSUM_MISMATCH, INCOMPLETE }

    companion object {

        /**
         * IO: sha256 of [file] as lowercase hex, or null when unreadable.
         * Streaming digest — an APK is ~6 MB, never load it whole. Call on
         * Dispatchers.IO only (call sites already are); ~20ms for 6 MB.
         */
        @WorkerThread
        fun sha256Hex(file: File): String? = try {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            val digest = md.digest()
            val hex = "0123456789abcdef".toCharArray()
            val out = CharArray(digest.size * 2)
            for (i in digest.indices) {
                val v = digest[i].toInt() and 0xFF
                out[i * 2] = hex[v ushr 4]
                out[i * 2 + 1] = hex[v and 0x0F]
            }
            String(out)
        } catch (_: Exception) {
            null
        }

        /**
         * Truly pure decision table: no filesystem access. Callers stat once
         * and pass [exists]/[length] in (see [apkStateIO]). `dmSuccess=true`
         * only immediately after DownloadManager SUCCESS for this id;
         * everywhere else pass false so unknown size never counts as complete.
         *
         * Integrity: when the release ships a sha256 ([expectedSha256] from
         * the asset `digest`), the file must hash to it — same-size corrupt
         * is [ApkState.Partial]. Expected digest + null [computedSha256]
         * fails closed; no digest keeps size-only. Threat model: corruption /
         * wrong-stale content / post-publish swaps — NOT malicious GitHub
         * (see RELEASING.md § Updater contract).
         */
        fun apkState(
            exists: Boolean,
            length: Long,
            expectedSize: Long?,
            expectedSha256: String? = null,
            computedSha256: String? = null,
            dmSuccess: Boolean = false,
        ): ApkState {
            if (!exists || length <= 0) return ApkState.Missing
            val sizeOk = if (expectedSize != null) length == expectedSize else dmSuccess
            val hashOk = expectedSha256 == null ||
                (computedSha256 != null && computedSha256.equals(expectedSha256, ignoreCase = true))
            return if (sizeOk && hashOk) ApkState.Ready else ApkState.Partial
        }

        /**
         * Pure gate-failure classifier: checksum message only when a digest
         * is on record and the length is sane (sizeless, or == recorded size)
         * so only a hash mismatch is possible; everything else is incomplete.
         */
        fun apkGateFailure(
            state: ApkState,
            expectedSha256: String?,
            expectedSize: Long?,
            fileLength: Long,
        ): ApkFailure = when {
            state == ApkState.Partial && expectedSha256 != null &&
                (expectedSize == null || fileLength == expectedSize) -> ApkFailure.CHECKSUM_MISMATCH
            else -> ApkFailure.INCOMPLETE
        }

        /**
         * IO wrapper around [apkState]: stats once, hashes lazily on
         * Dispatchers.IO. Size mismatch short-circuits to Partial without
         * hashing. Double-hash alreadyHave→enqueue on same-size-corrupt is
         * intentional (~20ms): enqueue stays safe standalone so the single
         * table keeps one owner (see ARCHITECTURE.md).
         */
        @WorkerThread
        fun apkStateIO(
            file: File,
            expectedSize: Long?,
            expectedSha256: String? = null,
            dmSuccess: Boolean = false,
        ): ApkState {
            val exists = file.exists()
            val length = if (exists) file.length() else 0L
            if (!exists || length <= 0) return ApkState.Missing
            if (expectedSize != null && length != expectedSize) return ApkState.Partial
            val computed = if (expectedSha256 == null) null else sha256Hex(file)
            return apkState(exists, length, expectedSize, expectedSha256, computed, dmSuccess)
        }

        /** IO: strict completeness without DM receipt (alreadyHave/enqueue). */
        @WorkerThread
        fun isCompleteIO(file: File, expectedSize: Long?, expectedSha256: String? = null): Boolean =
            apkStateIO(file, expectedSize, expectedSha256, dmSuccess = false) == ApkState.Ready

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
