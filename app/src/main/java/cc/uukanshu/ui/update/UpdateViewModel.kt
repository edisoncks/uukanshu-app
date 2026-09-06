package cc.uukanshu.ui.update

import cc.uukanshu.core.Errors
import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.uukanshu.di.PrefsApi
import cc.uukanshu.data.update.ActivityLauncher
import cc.uukanshu.data.update.ApkDownloader
import cc.uukanshu.data.update.DownloadStatus
import cc.uukanshu.data.update.ReleaseFetcher
import cc.uukanshu.data.update.UpdateApi
import cc.uukanshu.data.update.UpdateDownloader
import cc.uukanshu.data.update.UpdateInfo
import cc.uukanshu.data.update.VersionCompare
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
         * Fresh DownloadManager SUCCESS receipt for the current [info].
         * Gates the sizeless install path (see `isInstallable`): set only on
         * `DownloadStatus.Success`, cleared whenever [info] changes, so a
         * killed-process partial with unknown size can never ride an old
         * receipt (or a user tap alone) into the installer.
         */
        val downloadSucceeded: Boolean = false,
        val needsUnknownSources: Boolean = false,
        val error: String? = null,
    )

    companion object {
        /** Auto-check at most once per launch-window of this long. */
        const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

        /**
         * Pure throttle decision (JVM-testable): auto-check only when the
         * last check is older than [AUTO_CHECK_INTERVAL_MS]. Extracted so
         * the timing rule has a unit test instead of living inline in a
         * coroutine that needs Android + DataStore.
         */
        fun shouldAutoCheck(lastCheckMs: Long, nowMs: Long): Boolean =
            nowMs - lastCheckMs >= AUTO_CHECK_INTERVAL_MS

        /**
         * Pure offer decision (JVM-testable): newer-than-current and not
         * skipped (manual checks ignore skip). Returns false for
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

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui
    private var pollJob: Job? = null

    /** Foreground launch check: throttled to once per [AUTO_CHECK_INTERVAL_MS]. */
    fun autoCheck() {
        viewModelScope.launch {
            val last = prefs.lastUpdateCheck.first()
            if (!shouldAutoCheck(last, System.currentTimeMillis())) return@launch
            if (!markChecking(manual = false)) return@launch
            checkBody(manual = false)
        }
    }

    /** User-tapped check: always hits the network, reports "latest" too. */
    fun manualCheck() {
        // Synchronous test-and-set on Main: two rapid taps must not launch
        // two network checks (the old guard read async, so both passed).
        if (!markChecking(manual = true)) return
        viewModelScope.launch { checkBody(manual = true) }
    }

    /** Atomic false->true flip of `checking`; false when already in flight. */
    private fun markChecking(manual: Boolean): Boolean {
        val prev = _ui.getAndUpdate { cur ->
            if (cur.checking) cur
            else cur.copy(checking = true, manual = manual, error = null, upToDate = false)
        }
        return !prev.checking
    }

    private suspend fun checkBody(manual: Boolean) {
        try {
            val info = withContext(Dispatchers.IO) { api.fetchLatest() }
            prefs.setLastUpdateCheck(System.currentTimeMillis())
            val current = withContext(Dispatchers.IO) {
                UpdateDownloader.currentVersion(app)
            }
            val skipped = prefs.skippedVersion.first()
            if (!shouldOfferUpdate(info.version, current, skipped, manual)) {
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
                    )
                }
                return
            }
            // Same-version APK already downloaded (e.g. process died mid-flow):
            // skip straight to the install prompt. Byte-exact size match only;
            // a partial file must re-download, never install.
            val alreadyHave = withContext(Dispatchers.IO) {
                UpdateDownloader.isComplete(downloader.apkFile(info), info.size)
            }
            _ui.update {
                it.copy(checking = false, visible = true, info = info,
                    fileReady = alreadyHave, downloadSucceeded = false)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Throttle attempts, not just successes: a failed auto-check
            // stays silent for 24h instead of retrying on every launch.
            // Manual checks always hit the network (markChecking gate).
            prefs.setLastUpdateCheck(System.currentTimeMillis())
            if (manual) {
                _ui.update {
                    it.copy(checking = false, visible = true,
                        error = Errors.friendly(e))
                }
            } else {
                // Auto-check is best-effort: stay silent offline / rate-limited.
                _ui.update { it.copy(checking = false) }
            }
        }
    }

    fun dismiss() {
        // Keep an in-flight DownloadManager job running: the system download
        // survives the dialog, and reopen() picks it up via fileReady/progress.
        _ui.update { it.copy(visible = false, upToDate = false, error = null) }
    }

    /** Reopen the dialog from the "new version" banner after dismissing. */
    fun reopen() {
        _ui.update { it.copy(visible = true) }
    }

    fun skipVersion() {
        val v = _ui.value.info?.version ?: return
        viewModelScope.launch { prefs.setSkippedVersion(v) }
        // Skipping means go away: clear the pending update so the Settings
        // banner and dialog don't come straight back. Next manual check
        // re-fetches (manual ignores skipped); auto stays suppressed.
        _ui.update { it.copy(visible = false, upToDate = false, error = null, info = null, downloadSucceeded = false) }
    }

    fun startDownload() {
        val info = _ui.value.info ?: return
        if (_ui.value.downloading) return
        _ui.update {
            it.copy(downloading = true, progress = null, error = null,
                needsUnknownSources = false)
        }
        viewModelScope.launch(Dispatchers.IO) {
            // Already-have check first: a complete APK on disk skips
            // straight to install even when the unknown-sources permission
            // was revoked since (install() needs no gate of its own).
            if (UpdateDownloader.isComplete(downloader.apkFile(info), info.size)) {
                withContext(Dispatchers.Main) {
                    _ui.update { it.copy(downloading = false, fileReady = true) }
                }
                return@launch
            }
            if (!UpdateDownloader.canInstall(app)) {
                withContext(Dispatchers.Main) {
                    _ui.update { it.copy(downloading = false, needsUnknownSources = true) }
                }
                return@launch
            }
            val id = try {
                downloader.enqueue(info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    // Never leave the dialog wedged in "downloading" with no job.
                    _ui.update {
                        it.copy(
                            downloading = false,
                            error = Errors.friendly(e),
                            downloadId = null,
                        )
                    }
                }
                return@launch
            }
            if (id == -1L) {
                withContext(Dispatchers.Main) {
                    _ui.update { it.copy(downloading = false, fileReady = true) }
                }
                return@launch
            }
            // Publish from Main: cancelDownload() reads/writes the same state
            // on Main. Publishing from IO let a fast cancel slip between
            // enqueue and publication, leaking a DM download the UI forgot.
            withContext(Dispatchers.Main) {
                if (!_ui.value.downloading) {
                    // Cancelled while enqueueing: drop the just-created download.
                    viewModelScope.launch(Dispatchers.IO) { downloader.cancel(id) }
                    return@withContext
                }
                _ui.update { it.copy(downloadId = id) }
                pollJob?.cancel()
                // Progress comes from UpdateDownloader.observe (completes on
                // terminal states); the VM only maps states to dialog state.
                // Query failures (not download failures) surface as errors.
                pollJob = viewModelScope.launch {
                    try {
                        downloader.observe(id).collect { s ->
                            when (s) {
                                is DownloadStatus.Running -> _ui.update {
                                    it.copy(progress = s.progress)
                                }
                                is DownloadStatus.Success -> _ui.update {
                                    it.copy(downloading = false, fileReady = true,
                                        downloadId = null, downloadSucceeded = true)
                                }
                                is DownloadStatus.Failed -> _ui.update {
                                    it.copy(downloading = false, error = Errors.friendlyText(s.reason),
                                        downloadId = null, downloadSucceeded = false)
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _ui.update {
                            it.copy(
                                downloading = false,
                                error = Errors.friendly(e),
                                downloadId = null,
                            )
                        }
                    }
                }
            }
        }
    }

    fun cancelDownload() {
        pollJob?.cancel()
        pollJob = null
        val id = _ui.value.downloadId
        if (id != null) {
            viewModelScope.launch(Dispatchers.IO) { downloader.cancel(id) }
        }
        _ui.update { it.copy(downloading = false, progress = null, downloadId = null) }
    }

    /** Fire the system package installer for the downloaded APK. */
    fun install() {
        val info = _ui.value.info ?: return
        val file = downloader.apkFile(info)
        if (!UpdateDownloader.isInstallable(file, info.size, _ui.value.downloadSucceeded)) {
            _ui.update { it.copy(fileReady = false, error = "APK file missing or incomplete, please re-download") }
            return
        }
        // Firing the installer can throw (no handler, FileProvider
        // misconfiguration, install blocked): surface it in the dialog,
        // never crash the app out of an update tap.
        try {
            launcher.start(UpdateDownloader.installIntent(app, file))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _ui.update { it.copy(error = Errors.friendly(e)) }
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
