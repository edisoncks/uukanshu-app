package cc.uukanshu

import androidx.test.core.app.ApplicationProvider
import cc.uukanshu.data.update.ActivityLauncher
import cc.uukanshu.data.update.ApkDownloader
import cc.uukanshu.data.update.DownloadStatus
import cc.uukanshu.data.update.ReleaseFetcher
import cc.uukanshu.data.update.UpdateInfo
import cc.uukanshu.di.PrefsApi
import cc.uukanshu.ui.update.UpdateViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun rapidDoubleManualCheckLaunchesSingleFetch() = runTest(main.dispatcher) {
        val calls = AtomicInteger(0)
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = UpdateViewModel(
            app,
            MutableFakePrefs(),
            CountingFetcher(calls, info()),
            FakeApkDownloader(),
            ioDispatcher = main.dispatcher,
        )
        // Synchronous Main test-and-set: the second tap is a no-op before it
        // can launch a second fetch.
        vm.manualCheck()
        vm.manualCheck()
        main.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, calls.get())
        assertFalse(vm.ui.value.checking)
    }

    @Test fun installerFailureSurfacesAsDialogError() = runTest(main.dispatcher) {
        // Firing the installer can throw (no handler, FileProvider
        // misconfiguration): the dialog must show an error, never crash.
        // Byte-exact file so the install gate (strict, no receipt needed)
        // lets the tap reach the launcher.
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val sizedInfo = info().copy(size = 3L)
        val apk = java.io.File.createTempFile("uukanshu-test", ".apk")
            .also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val downloader = object : ApkDownloader {
            override fun apkFile(info: UpdateInfo): java.io.File = apk
            override fun findDownload(info: UpdateInfo): Long? = null
            override fun enqueue(info: UpdateInfo): Long = -1L
            override fun cancel(downloadId: Long) = Unit
            override fun observe(downloadId: Long) = kotlinx.coroutines.flow.flowOf(DownloadStatus.Success)
        }
        val vm = UpdateViewModel(
            app,
            MutableFakePrefs(),
            CountingFetcher(AtomicInteger(0), sizedInfo),
            downloader,
            ActivityLauncher { throw android.content.ActivityNotFoundException("no handler") },
            ioDispatcher = main.dispatcher,
        )
        vm.manualCheck()
        main.dispatcher.scheduler.advanceUntilIdle()
        assertEquals("9.9.9", vm.ui.value.info?.version)
        vm.install()
        main.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(true, vm.ui.value.error?.isNotEmpty())
    }

    @Test fun sizelessPartialWithoutReceiptRefusesInstall() = runTest(main.dispatcher) {
        // Unknown size + non-empty file + no fresh DM Success: a
        // killed-process partial must re-download, never reach the installer.
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val apk = java.io.File.createTempFile("uukanshu-test", ".apk")
            .also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        var launched = 0
        val downloader = object : ApkDownloader {
            override fun apkFile(info: UpdateInfo): java.io.File = apk
            override fun findDownload(info: UpdateInfo): Long? = null
            override fun enqueue(info: UpdateInfo): Long = -1L
            override fun cancel(downloadId: Long) = Unit
            override fun observe(downloadId: Long) = kotlinx.coroutines.flow.flowOf(DownloadStatus.Success)
        }
        val vm = UpdateViewModel(
            app,
            MutableFakePrefs(),
            CountingFetcher(AtomicInteger(0), info()),
            downloader,
            ActivityLauncher { launched++ },
            ioDispatcher = main.dispatcher,
        )
        vm.manualCheck()
        main.dispatcher.scheduler.advanceUntilIdle()
        vm.install()
        main.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, launched)
        assertEquals(true, vm.ui.value.error?.isNotEmpty())
        assertFalse(vm.ui.value.fileReady)
    }

    @Test fun unknownSourcesFailureSurfacesAsDialogError() = runTest(main.dispatcher) {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = UpdateViewModel(
            app,
            MutableFakePrefs(),
            CountingFetcher(AtomicInteger(0), info()),
            FakeApkDownloader(),
            ActivityLauncher { throw RuntimeException("no settings") },
            ioDispatcher = main.dispatcher,
        )
        // No coroutine hop: the launcher throw is mapped synchronously.
        vm.openUnknownSources()
        assertFalse(vm.ui.value.needsUnknownSources)
        assertEquals(true, vm.ui.value.error?.isNotEmpty())
    }

    @Test fun browserFallbackFailureSurfacesAsDialogError() = runTest(main.dispatcher) {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = UpdateViewModel(
            app,
            MutableFakePrefs(),
            CountingFetcher(AtomicInteger(0), info()),
            FakeApkDownloader(),
            ActivityLauncher { throw RuntimeException("no browser") },
            ioDispatcher = main.dispatcher,
        )
        vm.openInBrowser()
        assertEquals(true, vm.ui.value.error?.isNotEmpty())
    }

    @Test fun timestampWriteFailureDoesNotMaskSuccessOrWedgeRetry() = runTest(main.dispatcher) {
        val calls = AtomicInteger(0)
        val delegate = MutableFakePrefs()
        val prefs = object : PrefsApi by delegate {
            override suspend fun setLastUpdateCheck(now: Long) {
                throw java.io.IOException("disk full")
            }
        }
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = UpdateViewModel(
            app,
            prefs,
            CountingFetcher(calls, info()),
            FakeApkDownloader(),
            ioDispatcher = main.dispatcher,
        )
        vm.manualCheck()
        main.dispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.ui.value.checking)
        assertEquals("9.9.9", vm.ui.value.info?.version)
        assertNull(vm.ui.value.error)

        vm.manualCheck()
        main.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, calls.get())
        assertFalse(vm.ui.value.checking)
    }

    @Test fun timestampWriteFailureDoesNotMaskFetchFailure() = runTest(main.dispatcher) {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val prefs = object : PrefsApi by MutableFakePrefs() {
            override suspend fun setLastUpdateCheck(now: Long) {
                throw java.io.IOException("disk full")
            }
        }
        val failing = object : ReleaseFetcher {
            override fun fetchLatest(): UpdateInfo = throw java.io.IOException("offline")
        }
        val vm = UpdateViewModel(app, prefs, failing, FakeApkDownloader(), ioDispatcher = main.dispatcher)
        vm.manualCheck()
        main.dispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.ui.value.checking)
        assertTrue(vm.ui.value.error?.isNotEmpty() == true)
    }

    @Test fun autoCheckTimestampReadFailureIsHandled() = runTest(main.dispatcher) {
        val calls = AtomicInteger(0)
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val prefs = object : PrefsApi by MutableFakePrefs() {
            override val lastUpdateCheck: Flow<Long> = flow { throw java.io.IOException("corrupt prefs") }
        }
        val vm = UpdateViewModel(
            app,
            prefs,
            CountingFetcher(calls, info()),
            FakeApkDownloader(),
            ioDispatcher = main.dispatcher,
        )
        vm.autoCheck()
        main.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, calls.get())
        assertFalse(vm.ui.value.checking)
    }

    @Test fun failedAutoCheckStillThrottles() = runTest(main.dispatcher) {
        // A failed attempt stamps lastUpdateCheck too: silence for 24h
        // instead of retrying on every launch.
        val prefs = MutableFakePrefs()
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val failing = object : ReleaseFetcher {
            override fun fetchLatest(): UpdateInfo = throw java.io.IOException("offline")
        }
        val vm = UpdateViewModel(app, prefs, failing, FakeApkDownloader(), ioDispatcher = main.dispatcher)
        vm.autoCheck()
        main.dispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.ui.value.checking)
        assertFalse(vm.ui.value.visible)
        assertEquals(true, (prefs.lastCheckSet ?: 0L) > 0L)
    }

    @Test fun autoCheckThrottledWhenRecent() = runTest(main.dispatcher) {
        val calls = AtomicInteger(0)
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = UpdateViewModel(
            app,
            MutableFakePrefs(lastCheck = System.currentTimeMillis()),
            CountingFetcher(calls, info()),
            FakeApkDownloader(),
            ioDispatcher = main.dispatcher,
        )
        vm.autoCheck()
        main.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, calls.get())
    }
}
