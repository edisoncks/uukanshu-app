package cc.uukanshu

import androidx.test.core.app.ApplicationProvider
import cc.uukanshu.data.update.ActivityLauncher
import cc.uukanshu.data.update.ApkDownloader
import cc.uukanshu.data.update.DownloadStatus
import cc.uukanshu.data.update.ReleaseFetcher
import cc.uukanshu.data.update.UpdateInfo
import cc.uukanshu.ui.update.UpdateViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class UpdateViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private fun idle() {
        main.dispatcher.scheduler.advanceUntilIdle()
    }

    private class CountingFetcher(val calls: AtomicInteger, val info: UpdateInfo) : ReleaseFetcher {
        override fun fetchLatest(): UpdateInfo {
            calls.incrementAndGet()
            return info
        }
    }

    private fun info(version: String = "9.9.9") = UpdateInfo(
        tag = "v$version",
        version = version,
        changelog = "notes",
        apkUrl = "https://example.com/u.apk",
        apkName = "uukanshu-$version.apk",
        htmlUrl = "https://example.com/rel",
        size = null,
    )

    @Test fun rapidDoubleManualCheckLaunchesSingleFetch() = runTest {
        val calls = AtomicInteger(0)
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = UpdateViewModel(
            app,
            MutableFakePrefs(),
            CountingFetcher(calls, info()),
            FakeApkDownloader(),
        )
        vm.manualCheck()
        vm.manualCheck()
        // checkBody hops to Dispatchers.IO (real thread): wait for completion.
        var tries = 0
        while (vm.ui.value.checking && tries < 100) {
            Thread.sleep(50)
            main.dispatcher.scheduler.advanceUntilIdle()
            tries++
        }
        assertEquals(1, calls.get())
        assertFalse(vm.ui.value.checking)
    }

    @Test fun installerFailureSurfacesAsDialogError() = runTest {
        // Firing the installer can throw (no handler, FileProvider
        // misconfiguration): the dialog must show an error, never crash.
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val apk = java.io.File.createTempFile("uukanshu-test", ".apk")
            .also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val downloader = object : ApkDownloader {
            override fun apkFile(info: UpdateInfo): java.io.File = apk
            override fun enqueue(info: UpdateInfo): Long = -1L
            override fun cancel(downloadId: Long) = Unit
            override fun observe(downloadId: Long) = kotlinx.coroutines.flow.flowOf(DownloadStatus.Success)
        }
        val vm = UpdateViewModel(
            app,
            MutableFakePrefs(),
            CountingFetcher(AtomicInteger(0), info()),
            downloader,
            ActivityLauncher { throw android.content.ActivityNotFoundException("no handler") },
        )
        vm.manualCheck()
        var tries = 0
        while (vm.ui.value.info == null && tries < 100) {
            Thread.sleep(50)
            main.dispatcher.scheduler.advanceUntilIdle()
            tries++
        }
        assertEquals("9.9.9", vm.ui.value.info?.version)
        vm.install()
        assertEquals(true, vm.ui.value.error?.isNotEmpty())
    }

    @Test fun unknownSourcesFailureSurfacesAsDialogError() = runTest {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = UpdateViewModel(
            app,
            MutableFakePrefs(),
            CountingFetcher(AtomicInteger(0), info()),
            FakeApkDownloader(),
            ActivityLauncher { throw RuntimeException("no settings") },
        )
        vm.openUnknownSources()
        assertFalse(vm.ui.value.needsUnknownSources)
        assertEquals(true, vm.ui.value.error?.isNotEmpty())
    }

    @Test fun browserFallbackFailureSurfacesAsDialogError() = runTest {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = UpdateViewModel(
            app,
            MutableFakePrefs(),
            CountingFetcher(AtomicInteger(0), info()),
            FakeApkDownloader(),
            ActivityLauncher { throw RuntimeException("no browser") },
        )
        vm.openInBrowser()
        assertEquals(true, vm.ui.value.error?.isNotEmpty())
    }

    @Test fun autoCheckThrottledWhenRecent() = runTest {
        val calls = AtomicInteger(0)
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = UpdateViewModel(
            app,
            MutableFakePrefs(lastCheck = System.currentTimeMillis()),
            CountingFetcher(calls, info()),
            FakeApkDownloader(),
        )
        vm.autoCheck()
        idle()
        assertEquals(0, calls.get())
    }
}
