package cc.uukanshu.ui.update

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.data.update.DownloadStatus
import cc.uukanshu.data.update.UpdateApi
import cc.uukanshu.data.update.UpdateDownloader
import cc.uukanshu.data.update.UpdateInfo
import cc.uukanshu.data.update.VersionCompare
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** In-app update state machine (Tier B: DownloadManager + installer intent). */
class UpdateViewModel(
    private val app: Application,
    private val prefs: Prefs,
    private val api: UpdateApi = UpdateApi(),
    private val downloader: UpdateDownloader = UpdateDownloader(app),
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
        val needsUnknownSources: Boolean = false,
        val error: String? = null,
    )

    companion object {
        /** Auto-check at most once per launch-window of this long. */
        const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    }

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui
    private var pollJob: Job? = null

    /** Foreground launch check: throttled to once per [AUTO_CHECK_INTERVAL_MS]. */
    fun autoCheck() {
        viewModelScope.launch {
            val last = prefs.lastUpdateCheck.first()
            if (System.currentTimeMillis() - last < AUTO_CHECK_INTERVAL_MS) return@launch
            check(manual = false)
        }
    }

    /** User-tapped check: always hits the network, reports "latest" too. */
    fun manualCheck() {
        if (_ui.value.checking) return
        viewModelScope.launch { check(manual = true) }
    }

    private suspend fun check(manual: Boolean) {
        _ui.update {
            it.copy(checking = true, manual = manual, error = null, upToDate = false)
        }
        try {
            val info = withContext(Dispatchers.IO) { api.fetchLatest() }
            prefs.setLastUpdateCheck(System.currentTimeMillis())
            val current = withContext(Dispatchers.IO) {
                UpdateDownloader.currentVersion(app)
            }
            val skipped = prefs.skippedVersion.first()
            if (!VersionCompare.isNewer(info.version, current)) {
                _ui.update {
                    it.copy(checking = false, visible = manual, upToDate = manual,
                        info = null)
                }
                return
            }
            if (!manual && info.version == skipped) {
                _ui.update { it.copy(checking = false) }
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
                    fileReady = alreadyHave)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (manual) {
                _ui.update {
                    it.copy(checking = false, visible = true,
                        error = "${e.javaClass.simpleName}: ${e.message}")
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
        _ui.update { it.copy(visible = false, upToDate = false, error = null, info = null) }
    }

    fun startDownload() {
        val info = _ui.value.info ?: return
        if (_ui.value.downloading) return
        if (!UpdateDownloader.canInstall(app)) {
            _ui.update { it.copy(needsUnknownSources = true) }
            return
        }
        _ui.update {
            it.copy(downloading = true, progress = null, error = null,
                needsUnknownSources = false)
        }
        viewModelScope.launch(Dispatchers.IO) {
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
                            error = "${e.javaClass.simpleName}: ${e.message}",
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
                pollJob = viewModelScope.launch {
                    while (true) {
                        val s = try {
                            withContext(Dispatchers.IO) { downloader.query(id) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            _ui.update {
                                it.copy(
                                    downloading = false,
                                    error = "${e.javaClass.simpleName}: ${e.message}",
                                    downloadId = null,
                                )
                            }
                            return@launch
                        }
                        when (s) {
                        is DownloadStatus.Running -> _ui.update {
                            it.copy(progress = s.progress)
                        }
                        is DownloadStatus.Success -> {
                            _ui.update {
                                it.copy(downloading = false, fileReady = true,
                                    downloadId = null)
                            }
                            return@launch
                        }
                        is DownloadStatus.Failed -> {
                            _ui.update {
                                it.copy(downloading = false, error = s.reason,
                                    downloadId = null)
                            }
                            return@launch
                        }
                    }
                    delay(500)
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
        if (!UpdateDownloader.isComplete(file, info.size)) {
            _ui.update { it.copy(fileReady = false, error = "APK file missing or incomplete, please re-download") }
            return
        }
        app.startActivity(UpdateDownloader.installIntent(app, file))
    }

    /** Direct the user to the "allow unknown apps" toggle, then continue. */
    fun openUnknownSources() {
        app.startActivity(UpdateDownloader.unknownSourcesIntent(app))
        // Don't download yet: the user returns via back navigation, and the
        // dialog's update button retries with permission granted.
        _ui.update { it.copy(needsUnknownSources = true) }
    }

    /** Fallback when DownloadManager fails: let the browser fetch the APK. */
    fun openInBrowser() {
        val url = _ui.value.info?.apkUrl
            ?: _ui.value.info?.htmlUrl
            ?: "https://github.com/${UpdateApi.REPO}/releases/latest"
        app.startActivity(UpdateDownloader.browserIntent(url))
    }
}
