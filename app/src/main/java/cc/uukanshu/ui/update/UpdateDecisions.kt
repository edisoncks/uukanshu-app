package cc.uukanshu.ui.update

import cc.uukanshu.core.Errors
import cc.uukanshu.data.update.UpdateDownloader
import cc.uukanshu.data.update.UpdateInfo
import cc.uukanshu.data.update.VersionCompare

/**
 * Pure decision logic for the in-app updater.
 *
 * [UpdateViewModel] owns the Android/IO sequencing; every non-trivial decision
 * it makes lives here so it is testable on plain JVM (no Robolectric, no
 * threads). See ARCHITECTURE.md § In-app update.
 */

/**
 * Throttle + offer policy. Single source of truth for whether a check runs and
 * whether its result is offered.
 */
object UpdatePolicy {
    /** Auto-check at most once per launch-window of this long. */
    const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    /** Auto-check only when the last check is older than [AUTO_CHECK_INTERVAL_MS]. */
    fun shouldAutoCheck(lastCheckMs: Long, nowMs: Long): Boolean =
        nowMs - lastCheckMs >= AUTO_CHECK_INTERVAL_MS

    /**
     * Newer-than-current and not skipped (manual checks ignore skip). False for
     * up-to-date / skipped-auto so callers stay a thin `when`.
     */
    fun shouldOfferUpdate(
        remoteVersion: String,
        currentVersion: String,
        skippedVersion: String?,
        manual: Boolean,
    ): Boolean {
        if (!VersionCompare.isNewer(remoteVersion, currentVersion)) return false
        if (!manual && remoteVersion == skippedVersion) return false
        return true
    }
}

/** Backs the Main-thread `getAndUpdate` test-and-set in [UpdateViewModel]. */
fun canStartCheck(checking: Boolean): Boolean = !checking

/** Backs the Main-thread `getAndUpdate` test-and-set in [UpdateViewModel]. */
fun canStartInstall(installing: Boolean): Boolean = !installing

/** Pure terminal verdict for a `DownloadStatus.Success`. */
sealed interface DownloadSuccess {
    /** The dialog moved on mid-flight; clear terminal state, mint nothing. */
    data object StaleInfo : DownloadSuccess

    /** Verified (digest matched, or the release shipped no digest): mint receipt. */
    data object Ready : DownloadSuccess

    /** Digest mismatch / size mismatch: fail closed. */
    data class ChecksumFailed(val failure: UpdateDownloader.ApkFailure) : DownloadSuccess
}

/**
 * The single terminal verdict for a `DownloadStatus.Success`.
 *
 * Security rule: a receipt is pinned to the release the download was enqueued
 * for. [currentInfo] is the dialog's info when the terminal status arrived, so
 * a mid-flight re-check or skip that moved the dialog on yields
 * [DownloadSuccess.StaleInfo] and mints nothing.
 *
 * A release without a digest is accepted on DownloadManager's success:
 * [apkState] is not consulted on that branch (a size probe still runs, but it
 * skips hashing when there is no digest).
 */
fun classifyDownloadSuccess(
    currentInfo: UpdateInfo?,
    enqueued: UpdateInfo,
    apkState: UpdateDownloader.ApkState,
    fileLength: Long,
): DownloadSuccess = when {
    currentInfo != enqueued -> DownloadSuccess.StaleInfo
    enqueued.sha256 == null -> DownloadSuccess.Ready
    apkState == UpdateDownloader.ApkState.Ready -> DownloadSuccess.Ready
    else -> DownloadSuccess.ChecksumFailed(
        UpdateDownloader.apkGateFailure(apkState, enqueued.sha256, enqueued.size, fileLength),
    )
}

/** The terminal `Ui` transition for each [DownloadSuccess] outcome. */
fun applyDownloadSuccess(ui: UpdateViewModel.Ui, outcome: DownloadSuccess): UpdateViewModel.Ui =
    when (outcome) {
        DownloadSuccess.StaleInfo -> ui.copy(downloading = false, downloadId = null)
        DownloadSuccess.Ready -> ui.copy(
            downloading = false,
            fileReady = true,
            downloadId = null,
            downloadSucceeded = true,
        )
        is DownloadSuccess.ChecksumFailed -> ui.copy(
            downloading = false,
            fileReady = false,
            downloadId = null,
            downloadSucceeded = false,
            error = Errors.friendly(outcome.failure),
        )
    }
