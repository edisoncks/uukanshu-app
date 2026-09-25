package cc.uukanshu.ui.update

import cc.uukanshu.core.Errors
import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.uukanshu.di.PrefsApi
import cc.uukanshu.data.update.ActivityLauncher
import cc.uukanshu.data.update.ApkDownloader
import cc.uukanshu.data.update.DownloadStatus
import cc.uukanshu.data.update.ReleaseFetcher
import cc.uukanshu.data.update.UpdateApi
import cc.uukanshu.data.update.UpdateDownloader
import cc.uukanshu.data.update.UpdateDownloadRecord
import cc.uukanshu.data.update.UpdateInfo
import cc.uukanshu.data.update.VersionCompare
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "UpdateVM"

/**
 * In-app update state machine (Tier B: DownloadManager + installer intent).
 *
 * Depends on [ReleaseFetcher]/[ApkDownloader] interfaces (not concretes)
 * so JVM tests inject fakes. Production wires singletons from [cc.uukanshu.App]
 * via `RealAppContainer` — never `UpdateApi()`/`UpdateDownloader(app)` inline.
 */
class UpdateViewModel(
    private val app: Application,
    private val prefs: PrefsApi,
    private val api: ReleaseFetcher,
    private val downloader: ApkDownloader,
    private val launcher: ActivityLauncher = ActivityLauncher { app.startActivity(it) },
    // Injected so tests run on the test scheduler instead of real threads.
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    data class Ui(
        /** Whether any update dialog is on screen. */
        val visible: Boolean = false,
        /** Network check in flight (auto or manual). */
        val checking: Boolean = false,
        /** True only for a manual check, to show the "already latest" note. */
        val manual: Boolean = false,
        val upToDate: Boolean = false,
        val info: UpdateInfo? = null,
        val downloading: Boolean = false,
        /** 0..1, null = indeterminate. */
        val progress: Float? = null,
        val downloadId: Long? = null,
        val fileReady: Boolean = false,
        /**
         * DownloadManager SUCCESS receipt for the current [info]. It is minted
         * only after observing that request's terminal success, and the pinned
         * request identity is persisted so a recreated VM can re-observe it.
         * A partial file or user tap alone never grants the sizeless install path.
         */
        val downloadSucceeded: Boolean = false,
        val needsUnknownSources: Boolean = false,
        /** Install-gate verification in flight (hashing on IO); blocks double-tap. */
        val installing: Boolean = false,
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui
    private var pollJob: Job? = null
    private var observedDownloadId: Long? = null
    private var observedDownloadInfo: UpdateInfo? = null
    private val recoveryComplete = CompletableDeferred<Unit>()

    init {
        viewModelScope.launch {
            try {
                recoverDownload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Download recovery failed", e)
            } finally {
                recoveryComplete.complete(Unit)
            }
        }
    }

    private suspend fun recoverDownload() {
        val record = prefs.updateDownloadRecord.first() ?: return
        val currentVersion = withContext(ioDispatcher) { UpdateDownloader.currentVersion(app) }
        if (!VersionCompare.isNewer(record.info.version, currentVersion)) {
            // Already installed: resolve the request only to cancel it (an
            // id-less record still needs a lookup), never to reattach.
            val staleId = record.downloadId ?: withContext(ioDispatcher) {
                downloader.findDownload(record.info)
            }
            if (staleId != null) withContext(ioDispatcher) { downloader.cancel(staleId) }
            clearDownloadRecord(record)
            return
        }
        val id = record.downloadId ?: withContext(ioDispatcher) {
            downloader.findDownload(record.info)
        }
        if (id == null) {
            clearDownloadRecord(record)
            return
        }
        val recovered = record.copy(downloadId = id)
        if (record.downloadId == null) {
            try {
                prefs.setUpdateDownloadRecord(recovered)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The pending (id-less) record still lets the next launch rediscover the request.
                Log.w(TAG, "Could not persist recovered download id", e)
            }
        }
        _ui.update {
            it.copy(
                // Recovery restores state but does not force the dialog back:
                // a dismissed prompt stays dismissed (the Settings banner
                // offers reopen), matching the dismiss contract below.
                visible = false,
                info = recovered.info,
                downloading = true,
                progress = null,
                downloadId = id,
                fileReady = false,
                downloadSucceeded = false,
                error = null,
            )
        }
        observeDownload(id, recovered.info)
    }

    private suspend fun clearDownloadRecord(expected: UpdateDownloadRecord) {
        try {
            if (prefs.updateDownloadRecord.first()?.sameRequestAs(expected) == true) {
                prefs.setUpdateDownloadRecord(null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not clear updater download record", e)
        }
    }

    private suspend fun stampUpdateCheckSafely() {
        try {
            prefs.setLastUpdateCheck(System.currentTimeMillis())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Throttle persistence must never turn a successful check into an uncaught failure.
            Log.w(TAG, "Could not persist update-check timestamp", e)
        }
    }

    /** Foreground launch check: throttled to once per [AUTO_CHECK_INTERVAL_MS]. */
    fun autoCheck() {
        viewModelScope.launch {
            try {
                recoveryComplete.await()
                if (_ui.value.downloading) return@launch
                val last = try {
                    prefs.lastUpdateCheck.first()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Could not read update-check timestamp; skipping auto-check", e)
                    return@launch
                }
                if (!UpdatePolicy.shouldAutoCheck(last, System.currentTimeMillis())) return@launch
                if (!markChecking(manual = false)) return@launch
                checkBody(manual = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Automatic update check failed", e)
            }
        }
    }

    /** User-tapped check: always hits the network, reports "latest" too. */
    fun manualCheck() {
        // Synchronous test-and-set on Main: two rapid taps must not launch
        // two network checks.
        if (!markChecking(manual = true)) return
        viewModelScope.launch { checkBody(manual = true) }
    }

    /** Atomic false->true flip of `checking`; false when already in flight. */
    private fun markChecking(manual: Boolean): Boolean {
        val prev = _ui.getAndUpdate { cur ->
            if (canStartCheck(cur.checking)) {
                cur.copy(checking = true, manual = manual, error = null, upToDate = false)
            } else {
                cur
            }
        }
        return canStartCheck(prev.checking)
    }

    private suspend fun checkBody(manual: Boolean) {
        try {
            recoveryComplete.await()
            val info = withContext(ioDispatcher) { api.fetchLatest() }
            stampUpdateCheckSafely()
            val current = withContext(ioDispatcher) {
                UpdateDownloader.currentVersion(app)
            }
            val skipped = prefs.skippedVersion.first()
            if (!UpdatePolicy.shouldOfferUpdate(info.version, current, skipped, manual)) {
                // Distinguish up-to-date (manual shows a note) from
                // skipped-auto (silent) without duplicating the version
                // comparison at the call site.
                val upToDate = !VersionCompare.isNewer(info.version, current)
                _ui.update {
                    it.copy(
                        checking = false,
                        visible = manual && upToDate,
                        upToDate = manual && upToDate,
                        info = null,
                        downloadSucceeded = false,
                        installing = false,
                    )
                }
                return
            }
            val existingId = withContext(ioDispatcher) { downloader.findDownload(info) }
            if (existingId != null) {
                val record = UpdateDownloadRecord(info, existingId)
                try {
                    prefs.setUpdateDownloadRecord(record)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Could not persist recovered download id", e)
                }
                _ui.update {
                    it.copy(
                        checking = false,
                        visible = true,
                        info = info,
                        downloading = true,
                        progress = null,
                        downloadId = existingId,
                        fileReady = false,
                        downloadSucceeded = false,
                        installing = false,
                    )
                }
                observeDownload(existingId, info)
                return
            }
            // A complete file is safe without a live DownloadManager receipt
            // only when its known size/digest passes the shared integrity gate.
            val alreadyHave = withContext(ioDispatcher) {
                UpdateDownloader.isCompleteIO(downloader.apkFile(info), info.size, info.sha256)
            }
            _ui.update {
                it.copy(checking = false, visible = true, info = info,
                    fileReady = alreadyHave, downloadSucceeded = false, installing = false)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Throttle attempts, not just successes: a failed auto-check
            // stays silent for 24h instead of retrying on every launch.
            // Manual checks always hit the network (markChecking gate).
            stampUpdateCheckSafely()
            if (manual) {
                _ui.update {
                    it.copy(checking = false, visible = true,
                        error = Errors.friendly(e))
                }
            } else {
                // Auto-check is best-effort: stay silent offline / rate-limited.
                _ui.update { it.copy(checking = false) }
            }
        } finally {
            _ui.update { it.copy(checking = false) }
        }
    }

    fun dismiss() {
        // Keep the durable DownloadManager request registered so a new VM can reattach.
        _ui.update { it.copy(visible = false, upToDate = false, error = null) }
    }

    /** Reopen the dialog from the "new version" banner after dismissing. */
    fun reopen() {
        _ui.update { it.copy(visible = true) }
    }

    fun skipVersion() {
        val info = _ui.value.info ?: return
        val v = info.version
        viewModelScope.launch {
            prefs.setSkippedVersion(v)
            prefs.updateDownloadRecord.first()
                ?.takeIf { it.sameRequestAs(UpdateDownloadRecord(info)) }
                ?.let { clearDownloadRecord(it) }
        }
        // Skipping means go away: clear the pending update so the Settings
        // banner and dialog don't come straight back. Next manual check
        // re-fetches (manual ignores skipped); auto stays suppressed.
        _ui.update { it.copy(visible = false, upToDate = false, error = null, info = null, downloadSucceeded = false, installing = false) }
    }

    private fun observeDownload(id: Long, info: UpdateInfo) {
        pollJob?.cancel()
        observedDownloadId = id
        observedDownloadInfo = info
        pollJob = viewModelScope.launch {
            try {
                downloader.observe(id).collect { status ->
                    when (status) {
                        is DownloadStatus.Running -> _ui.update {
                            it.copy(downloading = true, downloadId = id, progress = status.progress)
                        }
                        is DownloadStatus.Success -> {
                            val currentInfo = _ui.value.info
                            val (file, state, length) = withContext(ioDispatcher) {
                                val output = downloader.apkFile(info)
                                Triple(
                                    output,
                                    UpdateDownloader.apkStateIO(
                                        output, info.size, info.sha256, dmSuccess = true,
                                    ),
                                    if (output.exists()) output.length() else 0L,
                                )
                            }
                            val outcome = classifyDownloadSuccess(currentInfo, info, state, length)
                            if (outcome is DownloadSuccess.ChecksumFailed) {
                                withContext(ioDispatcher) { runCatching { file.delete() } }
                            }
                            _ui.update { applyDownloadSuccess(it, outcome) }
                            if (outcome !is DownloadSuccess.Ready) {
                                clearDownloadRecord(UpdateDownloadRecord(info, id))
                            }
                            if (observedDownloadId == id) {
                                observedDownloadId = null
                                observedDownloadInfo = null
                            }
                        }
                        is DownloadStatus.Failed -> {
                            _ui.update {
                                it.copy(
                                    downloading = false,
                                    error = Errors.friendlyText(status.reason),
                                    downloadId = null,
                                    downloadSucceeded = false,
                                )
                            }
                            clearDownloadRecord(UpdateDownloadRecord(info, id))
                            if (observedDownloadId == id) {
                                observedDownloadId = null
                                observedDownloadInfo = null
                            }
                        }
                        is DownloadStatus.Missing -> {
                            // The request vanished from DownloadManager: drop the
                            // stale record silently and leave the update offer
                            // up (Settings banner) so the user can retry.
                            _ui.update {
                                it.copy(
                                    downloading = false,
                                    downloadId = null,
                                    downloadSucceeded = false,
                                    error = null,
                                    visible = false,
                                )
                            }
                            clearDownloadRecord(UpdateDownloadRecord(info, id))
                            if (observedDownloadId == id) {
                                observedDownloadId = null
                                observedDownloadInfo = null
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update {
                    it.copy(downloading = false, error = Errors.friendly(e), downloadId = null)
                }
                if (observedDownloadId == id) {
                    observedDownloadId = null
                    observedDownloadInfo = null
                }
            }
        }
    }

    fun startDownload() {
        val info = _ui.value.info ?: return
        if (_ui.value.downloading) return
        _ui.update {
            it.copy(downloading = true, progress = null, error = null,
                needsUnknownSources = false)
        }
        viewModelScope.launch(ioDispatcher) {
            // Already-have check first: a complete APK on disk skips
            // straight to install even when the unknown-sources permission
            // was revoked since (install() needs no gate of its own).
            val apkFile = downloader.apkFile(info)
            if (UpdateDownloader.isCompleteIO(apkFile, info.size, info.sha256)) {
                withContext(Dispatchers.Main) {
                    _ui.update { it.copy(downloading = false, fileReady = true, downloadSucceeded = false) }
                }
                return@launch
            }
            if (!UpdateDownloader.canInstall(app)) {
                withContext(Dispatchers.Main) {
                    _ui.update { it.copy(downloading = false, needsUnknownSources = true) }
                }
                return@launch
            }
            val pending = UpdateDownloadRecord(info)
            try {
                // Write before enqueue so a process death in the enqueue/ID window
                // can rediscover the matching DownloadManager request.
                prefs.setUpdateDownloadRecord(pending)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _ui.update { it.copy(downloading = false, error = Errors.friendly(e)) }
                }
                return@launch
            }
            val id = try {
                downloader.enqueue(info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _ui.update {
                        it.copy(downloading = false, error = Errors.friendly(e), downloadId = null)
                    }
                }
                return@launch
            }
            if (id == -1L) {
                clearDownloadRecord(pending)
                withContext(Dispatchers.Main) {
                    _ui.update { it.copy(downloading = false, fileReady = true, downloadSucceeded = false) }
                }
                return@launch
            }
            val record = pending.copy(downloadId = id)
            try {
                prefs.setUpdateDownloadRecord(record)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The pending record is still sufficient to rediscover this request.
                Log.w(TAG, "Could not persist enqueued download id", e)
            }
            withContext(Dispatchers.Main) {
                if (!_ui.value.downloading) {
                    viewModelScope.launch(ioDispatcher) {
                        downloader.cancel(id)
                        clearDownloadRecord(record)
                    }
                    return@withContext
                }
                _ui.update { it.copy(downloadId = id) }
                observeDownload(id, info)
            }
        }
    }

    fun cancelDownload() {
        pollJob?.cancel()
        pollJob = null
        val current = _ui.value
        val id = current.downloadId ?: observedDownloadId
        val pinnedInfo = if (observedDownloadId == id) observedDownloadInfo else null
        val expected = (pinnedInfo ?: current.info)?.let { UpdateDownloadRecord(it, id) }
        observedDownloadId = null
        observedDownloadInfo = null
        viewModelScope.launch(ioDispatcher) {
            if (id != null) downloader.cancel(id)
            if (expected != null) clearDownloadRecord(expected)
        }
        _ui.update { it.copy(downloading = false, progress = null, downloadId = null) }
    }

    /** Fire the system package installer for the downloaded APK. */
    fun install() {
        val info = _ui.value.info ?: return
        // Synchronous Main test-and-set like markChecking: rapid taps must not
        // launch duplicate verifications or installer intents.
        val prev = _ui.getAndUpdate { cur ->
            if (canStartInstall(cur.installing)) cur.copy(installing = true, error = null) else cur
        }
        if (!canStartInstall(prev.installing)) return
        viewModelScope.launch {
            // Last integrity gate before the installer: size + DM receipt as
            // before, plus the release sha256 when shipped. Hashing reads the
            // whole APK — ioDispatcher, never Main; `downloadSucceeded` is
            // snapshotted on Main so the gate sees one consistent state.
            // `apkFile()` runs on IO (getExternalFilesDir does disk I/O).
            // Pre-fire re-stat narrows the snapshot→install race window;
            // it does not close it (installer fd race remains).
            val receipt = _ui.value.downloadSucceeded
            val gate = withContext(ioDispatcher) {
                val file = downloader.apkFile(info)
                val state = UpdateDownloader.apkStateIO(file, info.size, info.sha256, receipt)
                Triple(file, state, if (file.exists()) file.length() else 0L)
            }
            if (gate.second != UpdateDownloader.ApkState.Ready) {
                val failure = UpdateDownloader.apkGateFailure(gate.second, info.sha256, info.size, gate.third)
                _ui.update {
                    it.copy(
                        fileReady = false,
                        installing = false,
                        error = Errors.friendly(failure),
                    )
                }
                return@launch
            }
            val nowLen = withContext(ioDispatcher) {
                val f = gate.first
                if (f.exists()) f.length() else 0L
            }
            if (nowLen != gate.third) {
                _ui.update {
                    it.copy(
                        fileReady = false,
                        installing = false,
                        error = Errors.friendly(UpdateDownloader.ApkFailure.INCOMPLETE),
                    )
                }
                return@launch
            }
            // Firing the installer can throw (no handler, FileProvider
            // misconfiguration, install blocked): surface it in the dialog,
            // never crash the app out of an update tap.
            try {
                launcher.start(UpdateDownloader.installIntent(app, gate.first))
                _ui.update { it.copy(installing = false) }
            } catch (e: Exception) {
                if (e is CancellationException) {
                    _ui.update { it.copy(installing = false) }
                    throw e
                }
                _ui.update { it.copy(installing = false, error = Errors.friendly(e)) }
            }
        }
    }

    /** Direct the user to the "allow unknown apps" toggle, then continue. */
    fun openUnknownSources() {
        try {
            launcher.start(UpdateDownloader.unknownSourcesIntent(app))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _ui.update { it.copy(error = Errors.friendly(e)) }
            return
        }
        // Don't download yet: the user returns via back navigation, and the
        // dialog's update button retries with permission granted.
        _ui.update { it.copy(needsUnknownSources = true) }
    }

    /** Fallback when DownloadManager fails: let the browser fetch the APK. */
    fun openInBrowser() {
        val url = _ui.value.info?.apkUrl
            ?: _ui.value.info?.htmlUrl
            ?: "https://github.com/${UpdateApi.REPO}/releases/latest"
        try {
            launcher.start(UpdateDownloader.browserIntent(url))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _ui.update { it.copy(error = Errors.friendly(e)) }
        }
    }
}
