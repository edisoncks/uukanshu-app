package cc.uukanshu

import androidx.test.core.app.ApplicationProvider
import cc.uukanshu.data.update.ApkDownloader
import cc.uukanshu.data.update.DownloadStatus
import cc.uukanshu.data.update.ReleaseFetcher
import cc.uukanshu.data.update.UpdateInfo
import cc.uukanshu.ui.update.UpdateViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The one updater test that does **not** inject `ioDispatcher`: it proves the
 * production default ([kotlinx.coroutines.Dispatchers.IO]) still drives a check
 * to the offer state. The virtual-time tests cover ordering deterministically;
 * this one covers "the real path is wired". No sleeps — it awaits the `ui`
 * StateFlow under a timeout.
 *
 * `Dispatchers.Main` is an unconfined test dispatcher so `viewModelScope`
 * runs eagerly on the caller thread while the VM's IO hops stay on the real
 * production dispatcher.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class UpdateRealIoSmokeTest {
    @get:Rule val main = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test fun `default io dispatcher drives a check to offer`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val info = UpdateInfo(
            tag = "v9.9.9",
            version = "9.9.9",
            changelog = "notes",
            apkUrl = "https://example.com/u.apk",
            apkName = "uukanshu-9.9.9.apk",
            htmlUrl = "https://example.com/rel",
            size = null,
        )
        val vm = UpdateViewModel(
            app,
            MutableFakePrefs(),
            object : ReleaseFetcher {
                override fun fetchLatest() = info
            },
            object : ApkDownloader {
                // Reached by checkBody's already-have probe: a stable path that
                // does not exist, so the check proceeds past it to the offer.
                override fun apkFile(info: UpdateInfo): File =
                    File(app.cacheDir, "uukanshu-smoke.apk")
                override fun findDownload(info: UpdateInfo): Long? = null
                // This test only drives a check; a download must never start.
                override fun enqueue(info: UpdateInfo): Long =
                    error("enqueue not exercised by the smoke test")
                override fun cancel(downloadId: Long) = Unit
                override fun observe(downloadId: Long): Flow<DownloadStatus> =
                    error("observe not exercised by the smoke test")
            },
        )
        vm.manualCheck()
        val ui = withTimeout(10_000) { vm.ui.first { it.info != null } }
        assertEquals("9.9.9", ui.info?.version)
    }
}
