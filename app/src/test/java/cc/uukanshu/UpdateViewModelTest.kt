package cc.uukanshu

import androidx.test.core.app.ApplicationProvider
import cc.uukanshu.data.update.ActivityLauncher
import cc.uukanshu.data.update.ApkDownloader
import cc.uukanshu.data.update.DownloadStatus
import cc.uukanshu.data.update.ReleaseFetcher
import cc.uukanshu.data.update.UpdateInfo
import cc.uukanshu.di.PrefsApi
import cc.uukanshu.ui.update.UpdateViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    @Test fun skipSurvivesAFailedPreferenceWrite() = runTest(main.dispatcher) {
        // A DataStore write can fail (full disk, unwritable file). The skip must
        // not escape to the crash handler, and the offer must stay on screen —
        // hiding it would claim a skip that never reached the disk.
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val prefs = object : PrefsApi by MutableFakePrefs() {
            override suspend fun setSkippedVersion(v: String?) {
                throw java.io.IOException("disk full")
            }
        }
        val vm = UpdateViewModel(
            app,
            prefs,
            CountingFetcher(AtomicInteger(0), info()),
            FakeApkDownloader(),
            ioDispatcher = main.dispatcher,
        )
        vm.manualCheck()
        main.dispatcher.scheduler.advanceUntilIdle()
        assertNotNull("precondition: the offer is up", vm.ui.value.info)

        vm.skipVersion()
        main.dispatcher.scheduler.advanceUntilIdle()

        assertNotNull("a failed skip must be reported", vm.ui.value.error)
        assertTrue("the offer must stay up", vm.ui.value.visible)
        assertNotNull("the skipped version must not be dropped", vm.ui.value.info)
    }

    @Test fun skipHidesTheOfferOnlyOncePersisted() = runTest(main.dispatcher) {
        // The write is gated so the assertions can see the state *while* it is in
        // flight: hiding the offer before the preference lands would claim a skip
        // that never reached the disk.
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val gate = CompletableDeferred<Unit>()
        val backing = MutableFakePrefs()
        val prefs = object : PrefsApi by backing {
            override suspend fun setSkippedVersion(v: String?) {
                gate.await()
                backing.setSkippedVersion(v)
            }
        }
        val vm = UpdateViewModel(
            app,
            prefs,
            CountingFetcher(AtomicInteger(0), info()),
            FakeApkDownloader(),
            ioDispatcher = main.dispatcher,
        )
        vm.manualCheck()
        main.dispatcher.scheduler.advanceUntilIdle()

        vm.skipVersion()
        main.dispatcher.scheduler.advanceUntilIdle()
        assertTrue("the offer must stay up while the write is in flight", vm.ui.value.visible)
        assertNotNull("the release must not be dropped before the write lands", vm.ui.value.info)
        assertTrue("nothing may be persisted yet", backing.skipped.isEmpty())

        gate.complete(Unit)
        main.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("9.9.9"), backing.skipped)
        assertFalse(vm.ui.value.visible)
        assertNull(vm.ui.value.info)
        assertNull(vm.ui.value.error)
    }

    @Test fun downloadTapDuringSkipCannotStart() = runTest(main.dispatcher) {
        // The skip holds a suspended preference write while the dialog still shows
        // 立即更新. A download started in that window would be hidden when the skip
        // hides the dialog, and the skip's record clear would take its durable
        // record with it (UpdateDownloadRecord.sameRequestAs matches by release
        // identity when either side has no id).
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val gate = CompletableDeferred<Unit>()
        val prefs = object : PrefsApi by MutableFakePrefs() {
            override suspend fun setSkippedVersion(v: String?) {
                gate.await()
            }
        }
        val enqueued = AtomicInteger(0)
        val downloader = object : ApkDownloader {
            override fun apkFile(info: UpdateInfo) = java.io.File.createTempFile("uukanshu-skip", ".apk")
            override fun findDownload(info: UpdateInfo): Long? = null
            override fun enqueue(info: UpdateInfo): Long {
                enqueued.incrementAndGet()
                return 7L
            }
            override fun cancel(downloadId: Long) = Unit
            override fun observe(downloadId: Long): Flow<DownloadStatus> = flow { }
        }
        val vm = UpdateViewModel(
            app, prefs, CountingFetcher(AtomicInteger(0), info()), downloader,
            ioDispatcher = main.dispatcher,
        )
        // Without the unknown-sources grant startDownload() would bail before
        // enqueue() for a reason unrelated to the skip, hiding a real regression.
        org.robolectric.Shadows.shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        vm.manualCheck()
        main.dispatcher.scheduler.advanceUntilIdle()
        assertNotNull("precondition: the offer is up", vm.ui.value.info)

        vm.skipVersion()
        assertTrue("the skip must be claimed before its write suspends", vm.ui.value.skipping)

        vm.startDownload()
        main.dispatcher.scheduler.advanceUntilIdle()
        assertEquals("no download may start under a pending skip", 0, enqueued.get())

        gate.complete(Unit)
        main.dispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.ui.value.visible)
        assertEquals(0, enqueued.get())
    }
}
