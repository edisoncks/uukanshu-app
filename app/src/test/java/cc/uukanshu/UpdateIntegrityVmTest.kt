package cc.uukanshu

import androidx.test.core.app.ApplicationProvider
import cc.uukanshu.data.update.ActivityLauncher
import cc.uukanshu.data.update.ApkDownloader
import cc.uukanshu.data.update.DownloadStatus
import cc.uukanshu.data.update.ReleaseFetcher
import cc.uukanshu.data.update.UpdateDownloader
import cc.uukanshu.data.update.UpdateDownloadRecord
import cc.uukanshu.data.update.UpdateInfo
import cc.uukanshu.core.Errors
import cc.uukanshu.ui.update.UpdateViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
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
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Digest wiring through the real VM state machine:
 * DM-Success verification (mint/delete), legacy no-digest path, and the
 * install gate as the last integrity check before the installer fires.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class UpdateIntegrityVmTest {
    @get:Rule val main = MainDispatcherRule()

    @org.junit.Before fun clearFileProviderCache() {
        // FileProvider caches PathStrategy per authority in a static map: a
        // successful install in one test poisons the next test's sandbox dir
        // (each Robolectric test gets its own /tmp/.../external-files). Clear
        // it so every install resolves against the current sandbox.
        runCatching {
            val clazz = Class.forName("androidx.core.content.FileProvider")
            for (field in clazz.declaredFields) {
                if (java.util.Map::class.java.isAssignableFrom(field.type)) {
                    field.isAccessible = true
                    (field.get(null) as? MutableMap<*, *>)?.clear()
                }
            }
        }
    }

    private val good = byteArrayOf(1, 2, 3, 4, 5)
    private val bad = byteArrayOf(9, 9, 9, 9, 9) // same length — the old size-only gate passed this

    private fun sha(bytes: ByteArray): String {
        val f = File.createTempFile("uukanshu-sha", ".bin").apply { writeBytes(bytes) }
        try {
            return UpdateDownloader.sha256Hex(f)!!
        } finally {
            f.delete()
        }
    }

    /** Fake DM: shared temp file; hooks simulate the download's side effects.
     *  [gate] non-null holds the download in flight after Running until the
     *  test releases it (mid-flight state swaps become deterministic; null
     *  keeps the original immediate-Success behavior for existing tests). */
    private class FakeDl(
        val file: File,
        var onEnqueue: () -> Long = { 42L },
        var onRunning: () -> Unit = {},
        var onSuccess: () -> Unit = {},
        var existingId: Long? = null,
        var missing: Boolean = false,
        private val gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null,
    ) : ApkDownloader {
        var enqueueCalls = 0
        var findDownloadCalls = 0
        val observedIds = mutableListOf<Long>()
        val cancelledIds = mutableListOf<Long>()
        override fun apkFile(info: UpdateInfo): File = file
        override fun findDownload(info: UpdateInfo): Long? {
            findDownloadCalls++
            return existingId
        }
        override fun enqueue(info: UpdateInfo): Long {
            enqueueCalls++
            return onEnqueue()
        }
        override fun cancel(downloadId: Long) {
            cancelledIds += downloadId
        }
        override fun observe(downloadId: Long) = flow {
            observedIds += downloadId
            if (missing) {
                emit(DownloadStatus.Missing)
                return@flow
            }
            onRunning()
            emit(DownloadStatus.Running(0.5f))
            gate?.await()
            onSuccess()
            emit(DownloadStatus.Success)
        }
    }

    private fun info(size: Long?, sha256: String?, version: String = "9.9.9") = UpdateInfo(
        tag = "v$version",
        version = version,
        changelog = "notes",
        apkUrl = "https://example.com/u.apk",
        apkName = "uukanshu-$version.apk",
        htmlUrl = "https://example.com/rel",
        size = size,
        sha256 = sha256,
    )

    /** Drive every pending coroutine on the shared test scheduler to quiescence. */
    private fun await() = main.dispatcher.scheduler.advanceUntilIdle()

    /** Robolectric's PackageManager shadow defaults the install grant to
     * false; flip it so startDownload proceeds past the unknown-sources
     * branch (see CanInstallProbeTest). */
    private fun grantCanInstall(app: android.app.Application) {
        org.robolectric.Shadows.shadowOf(app.packageManager)
            .setCanRequestPackageInstalls(true)
    }

    private fun vmFor(info: UpdateInfo, dl: ApkDownloader, launched: AtomicInteger? = null): UpdateViewModel =
        UpdateViewModel(
            ApplicationProvider.getApplicationContext(),
            MutableFakePrefs(),
            object : ReleaseFetcher {
                override fun fetchLatest(): UpdateInfo = info
            },
            dl,
            ActivityLauncher { launched?.incrementAndGet() },
            ioDispatcher = main.dispatcher,
        )

    private fun fetchThenStart(info: UpdateInfo, dl: FakeDl): UpdateViewModel {
        val vm = vmFor(info, dl)
        vm.manualCheck()
        await()
        vm.startDownload()
        return vm
    }

    @Test fun `dm success with matching digest readies file`() = runTest(main.dispatcher) {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        grantCanInstall(app)
        val file = File.createTempFile("uukanshu-integ", ".apk").also { it.delete() }
        val dl = FakeDl(file, onSuccess = { file.writeBytes(good) })
        val vm = fetchThenStart(info(5L, sha(good)), dl)
        await()
        val ui = vm.ui.value
        assertTrue(
            "fileReady=${ui.fileReady} error=${ui.error} needs=${ui.needsUnknownSources} downloading=${ui.downloading}",
            ui.fileReady,
        )
        assertTrue(ui.downloadSucceeded)
        assertFalse(ui.downloading)
        assertNull(ui.error)
        file.delete()
    }

    @Test fun `new ViewModel reattaches to an in-flight download without enqueueing again`() = runTest(main.dispatcher) {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        grantCanInstall(app)
        val payload = info(5L, sha(good))
        val file = File.createTempFile("uukanshu-restart", ".apk").also { it.delete() }
        val gate = CompletableDeferred<Unit>()
        val dl = FakeDl(file, onSuccess = { file.writeBytes(good) }, gate = gate)
        val prefs = MutableFakePrefs()
        val fetcher = object : ReleaseFetcher {
            override fun fetchLatest(): UpdateInfo = payload
        }
        val firstVm = UpdateViewModel(app, prefs, fetcher, dl, ActivityLauncher { }, main.dispatcher)
        firstVm.manualCheck()
        await()
        firstVm.startDownload()
        await()
        assertTrue(firstVm.ui.value.downloading)
        assertEquals(UpdateDownloadRecord(payload, 42L), prefs.updateDownloadRecord.first())

        // A second VM represents a process recreation with the same durable store and DM job.
        val restoredVm = UpdateViewModel(app, prefs, fetcher, dl, ActivityLauncher { }, main.dispatcher)
        await()
        assertEquals(1, dl.enqueueCalls)
        assertEquals(42L, restoredVm.ui.value.downloadId)
        assertTrue(restoredVm.ui.value.downloading)
        // Recovery must not force the dismissed prompt back on screen.
        assertFalse(restoredVm.ui.value.visible)
        assertEquals(listOf(42L, 42L), dl.observedIds)

        gate.complete(Unit)
        await()
        assertTrue(restoredVm.ui.value.fileReady)
        assertTrue(restoredVm.ui.value.downloadSucceeded)
        assertFalse(restoredVm.ui.value.downloading)
        file.delete()
    }

    @Test fun `recovered id purged from DownloadManager clears the record silently`() = runTest(main.dispatcher) {
        // A stored id whose row was removed outside the app (user cleared the
        // Downloads list) must not surface "download not found" on launch.
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val payload = info(5L, sha(good))
        val file = File.createTempFile("uukanshu-missing", ".apk").also { it.delete() }
        val dl = FakeDl(file, existingId = 55L, missing = true)
        val prefs = MutableFakePrefs().also {
            it.setUpdateDownloadRecord(UpdateDownloadRecord(payload, downloadId = 55L))
        }
        val vm = UpdateViewModel(
            app, prefs, object : ReleaseFetcher {
                override fun fetchLatest(): UpdateInfo = payload
            }, dl, ActivityLauncher { }, main.dispatcher,
        )
        await()
        assertNull(prefs.updateDownloadRecord.first())
        assertFalse(vm.ui.value.downloading)
        assertFalse(vm.ui.value.visible)
        assertNull(vm.ui.value.error)
        file.delete()
    }

    @Test fun `id-less pending record rediscovers the matching DownloadManager request`() = runTest(main.dispatcher) {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val payload = info(5L, sha(good))
        val file = File.createTempFile("uukanshu-pending", ".apk").also { it.delete() }
        val gate = CompletableDeferred<Unit>()
        val dl = FakeDl(file, existingId = 42L, onSuccess = { file.writeBytes(good) }, gate = gate)
        val prefs = MutableFakePrefs().also {
            it.setUpdateDownloadRecord(UpdateDownloadRecord(payload))
        }
        val fetcher = object : ReleaseFetcher {
            override fun fetchLatest(): UpdateInfo = payload
        }
        val vm = UpdateViewModel(
            app, prefs, fetcher, dl, ActivityLauncher { }, main.dispatcher,
        )
        await()
        assertEquals(0, dl.enqueueCalls)
        assertEquals(42L, prefs.updateDownloadRecord.first()?.downloadId)
        assertEquals(42L, vm.ui.value.downloadId)
        gate.complete(Unit)
        await()
        assertTrue(vm.ui.value.fileReady)
        file.delete()
    }

    @Test fun `dm success with mismatched digest deletes file and errors`() = runTest(main.dispatcher) {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        grantCanInstall(app)
        val file = File.createTempFile("uukanshu-integ", ".apk").also { it.delete() }
        val dl = FakeDl(file, onSuccess = { file.writeBytes(bad) })
        val vm = fetchThenStart(info(5L, sha(good)), dl)
        await()
        // Fail closed: no install path, file gone so a retry re-downloads.
        val ui = vm.ui.value
        assertFalse("fileReady=${ui.fileReady} error=${ui.error}", ui.fileReady)
        assertFalse(ui.downloadSucceeded)
        assertFalse(ui.downloading)
        assertFalse(file.exists())
        assertTrue("error=${ui.error}", ui.error!!.contains("重新下載"))
        assertTrue("error=${ui.error}", ui.error!!.contains("校驗"))
        file.delete()
    }

    @Test fun `legacy release without digest keeps size-only success`() = runTest(main.dispatcher) {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        grantCanInstall(app)
        val file = File.createTempFile("uukanshu-integ", ".apk").also { it.delete() }
        val dl = FakeDl(file, onSuccess = { file.writeBytes(good) })
        val vm = fetchThenStart(info(5L, null), dl)
        await()
        val ui = vm.ui.value
        assertTrue(
            "fileReady=${ui.fileReady} error=${ui.error} needs=${ui.needsUnknownSources}",
            ui.fileReady,
        )
        assertNull(ui.error)
        file.delete()
    }

    @Test fun `install gate accepts digest-verified file`() = runTest(main.dispatcher) {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        // Under the app's external Download dir: FileProvider's configured root
        // (external-files-path Download/) must cover the APK, as in production.
        val file = File(app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)!!, "uukanshu-integ.apk")
            .also { it.writeBytes(good) }
        val launched = AtomicInteger(0)
        val vm = vmFor(info(5L, sha(good)), FakeDl(file), launched)
        vm.manualCheck()
        await()
        vm.install()
        await()
        val ui = vm.ui.value
        assertEquals("launched=${launched.get()} error=${ui.error} fileReady=${ui.fileReady}", 1, launched.get())
        assertNull(ui.error)
        file.delete()
    }

    @Test fun `install gate rejects corrupt file with digest`() = runTest(main.dispatcher) {
        // fileReady minted while the file was good, disk corrupted after:
        // the install gate re-verifies (IO) and refuses — last line of defense.
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val file = File(app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)!!, "uukanshu-integ.apk")
            .also { it.writeBytes(good) }
        val launched = AtomicInteger(0)
        val vm = vmFor(info(5L, sha(good)), FakeDl(file), launched)
        vm.manualCheck()
        await()
        file.writeBytes(bad)
        vm.install()
        await()
        assertEquals("launched=${launched.get()} error=${vm.ui.value.error}", 0, launched.get())
        assertFalse(vm.ui.value.fileReady)
        // Corruption with a digest on record and a matching size is a checksum
        // failure — the gate must say so, not "incomplete".
        assertEquals(Errors.friendly(UpdateDownloader.ApkFailure.CHECKSUM_MISMATCH), vm.ui.value.error)
        file.delete()
    }

    @Test fun `rapid install taps fire installer once`() = runTest(main.dispatcher) {
        // Repro for async-gate double-fire: two back-to-back taps must share
        // one Main-guarded verification (see markChecking pattern).
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val file = File(app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)!!, "uukanshu-integ.apk")
            .also { it.writeBytes(good) }
        val launched = AtomicInteger(0)
        val vm = vmFor(info(5L, sha(good)), FakeDl(file), launched)
        vm.manualCheck()
        await()
        vm.install()
        vm.install()
        await()
        // The sync Main gate already made the second tap a no-op; add a settle
        // pass to prove nothing fires late.
        await()
        assertEquals("launched=${launched.get()} error=${vm.ui.value.error}", 1, launched.get())
        assertNull(vm.ui.value.error)
        assertFalse(vm.ui.value.installing)
        file.delete()
    }

    @Test fun `install gate failure clears installing and maps via Errors`() = runTest(main.dispatcher) {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val file = File(app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)!!, "uukanshu-integ.apk")
            .also { it.writeBytes(good) }
        val launched = AtomicInteger(0)
        val vm = vmFor(info(5L, sha(good)), FakeDl(file), launched)
        vm.manualCheck()
        await()
        file.writeBytes(bad)
        vm.install()
        await()
        assertEquals(0, launched.get())
        assertFalse(vm.ui.value.installing)
        assertEquals(Errors.friendly(UpdateDownloader.ApkFailure.CHECKSUM_MISMATCH), vm.ui.value.error)
    }

    @Test fun `dm success with wrong size errors as incomplete not checksum`() = runTest(main.dispatcher) {
        // DM SUCCESS is not proof of a complete artifact: if the landed length
        // disagrees with the release size, the gate failed on size — the honest
        // message is "incomplete, re-download", not the checksum one.
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        grantCanInstall(app)
        val file = File.createTempFile("uukanshu-integ", ".apk").also { it.delete() }
        val dl = FakeDl(file, onSuccess = { file.writeBytes(ByteArray(9)) })
        val vm = fetchThenStart(info(5L, sha(good)), dl)
        await()
        val ui = vm.ui.value
        assertFalse(ui.fileReady)
        assertFalse(ui.downloadSucceeded)
        assertFalse("file must be deleted so a retry re-downloads", file.exists())
        assertEquals(Errors.friendly(UpdateDownloader.ApkFailure.INCOMPLETE), ui.error)
        file.delete()
    }

    @Test fun `missing file at install gate stays incomplete`() = runTest(main.dispatcher) {
        // Disk loss after fileReady: the gate sees Missing, not a digest
        // mismatch — the message must stay "incomplete" (pins the classifier's
        // else-branch; passes before and after the typed-error fix).
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val file = File(app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)!!, "uukanshu-integ.apk")
            .also { it.writeBytes(good) }
        val launched = AtomicInteger(0)
        val vm = vmFor(info(5L, sha(good)), FakeDl(file), launched)
        vm.manualCheck()
        await()
        file.delete()
        vm.install()
        await()
        assertEquals(0, launched.get())
        assertFalse(vm.ui.value.installing)
        assertEquals(Errors.friendly(UpdateDownloader.ApkFailure.INCOMPLETE), vm.ui.value.error)
    }

    @Test fun `dm mismatch error maps via Errors`() = runTest(main.dispatcher) {
        // Typed mapping, not substring sniffing: Traditional source for display().
        assertEquals(
            "APK 校驗失敗，請重新下載",
            Errors.friendly(UpdateDownloader.ApkFailure.CHECKSUM_MISMATCH),
        )
    }

    /** A stateful fetcher: first call returns [first], then [second] — the
     *  mid-flight re-check a user can trigger while a download is in flight. */
    private fun swapFetcher(a: UpdateInfo, b: UpdateInfo) = object : ReleaseFetcher {
        val fetched = AtomicInteger(0)
        override fun fetchLatest(): UpdateInfo = if (fetched.incrementAndGet() == 1) a else b
    }

    @Test fun `mid-flight recheck then success never mints or errors for the wrong version`() = runTest(main.dispatcher) {
        // DM-Success must pin the release the download was enqueued for: a
        // re-check swapping the dialog's info mid-flight must not verify
        // against (or report errors for) a version whose file was never
        // downloaded, and must not mint a receipt for it.
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        grantCanInstall(app)
        val file = File.createTempFile("uukanshu-swap", ".apk").also { it.delete() }
        val gate = CompletableDeferred<Unit>()
        val dl = FakeDl(file, gate = gate, onSuccess = { file.writeBytes(good) })
        val vm = UpdateViewModel(
            ApplicationProvider.getApplicationContext(),
            MutableFakePrefs(),
            swapFetcher(info(5L, sha(good)), info(999_999L, "b".repeat(64), version = "9.9.10")),
            dl,
            ActivityLauncher { },
            ioDispatcher = main.dispatcher,
        )
        vm.manualCheck()
        await()
        vm.startDownload()
        await()
        // Mid-flight: dialog swaps to the newer release.
        vm.manualCheck()
        await()
        file.writeBytes(good) // DM lands v9.9.9's bytes, which match release A's digest
        gate.complete(Unit)
        await()
        val ui = vm.ui.value
        assertNull("error=${ui.error}", ui.error)
        assertTrue("artifact must survive a version swap", file.exists())
        assertEquals(
            "artifact must still match release A's digest",
            sha(good),
            UpdateDownloader.sha256Hex(file),
        )
        assertFalse("no fileReady for a version this download never produced", ui.fileReady)
        assertFalse(ui.downloadSucceeded)
        assertFalse(ui.downloading)
        file.delete()
    }

    @Test fun `skip during flight then success mints nothing while hidden`() = runTest(main.dispatcher) {
        // Receipts attach only to a dialog that still describes the enqueued
        // release: skip nulls info, so the Success must not mint fileReady or
        // downloadSucceeded — reopening must show the offer, not an install
        // prompt for a version the user skipped.
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        grantCanInstall(app)
        val file = File.createTempFile("uukanshu-skip", ".apk").also { it.delete() }
        val gate = CompletableDeferred<Unit>()
        val dl = FakeDl(file, gate = gate, onSuccess = { file.writeBytes(good) })
        val vm = vmFor(info(5L, sha(good)), dl)
        vm.manualCheck()
        await()
        vm.startDownload()
        await()
        vm.skipVersion()
        await()
        gate.complete(Unit)
        await()
        val ui = vm.ui.value
        assertFalse("receipt minted with no update info", ui.fileReady)
        assertFalse(ui.downloadSucceeded)
        assertFalse(ui.visible)
        vm.reopen()
        await()
        assertFalse(
            "reopened dialog must not offer install for a skipped version",
            vm.ui.value.fileReady,
        )
        file.delete()
    }

    @Test fun `same version different digest swap never mints`() = runTest(main.dispatcher) {
        // Full-info pin (not version-string): same version, different digest
        // mid-flight must still clear terminal state without mint or error.
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        grantCanInstall(app)
        val file = File.createTempFile("uukanshu-swap-samever", ".apk").also { it.delete() }
        val gate = CompletableDeferred<Unit>()
        val dl = FakeDl(file, gate = gate, onSuccess = { file.writeBytes(good) })
        val vm = UpdateViewModel(
            ApplicationProvider.getApplicationContext(),
            MutableFakePrefs(),
            swapFetcher(info(5L, sha(good)), info(5L, "b".repeat(64))),
            dl,
            ActivityLauncher { },
            ioDispatcher = main.dispatcher,
        )
        vm.manualCheck()
        await()
        vm.startDownload()
        await()
        vm.manualCheck()
        await()
        gate.complete(Unit)
        await()
        val ui = vm.ui.value
        assertNull("error=${ui.error}", ui.error)
        assertFalse(ui.fileReady)
        assertFalse(ui.downloadSucceeded)
        file.delete()
    }

    @Test fun `stale record for the installed version is cancelled and cleared`() = runTest(main.dispatcher) {
        // Version check must run before reattach: a record for a release that is
        // no longer newer than the installed app is a leftover request, not an
        // update in flight.
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val file = File.createTempFile("uukanshu-stale", ".apk").also { it.delete() }
        val payload = info(5L, sha(good), version = UpdateDownloader.currentVersion(app))
        val dl = FakeDl(file, existingId = 77L)
        val prefs = MutableFakePrefs().also {
            it.setUpdateDownloadRecord(UpdateDownloadRecord(payload))
        }
        val vm = UpdateViewModel(
            app, prefs, object : ReleaseFetcher {
                override fun fetchLatest(): UpdateInfo = payload
            }, dl, ActivityLauncher { }, main.dispatcher,
        )
        await()
        assertEquals(listOf(77L), dl.cancelledIds)
        assertNull(prefs.updateDownloadRecord.first())
        assertFalse(vm.ui.value.downloading)
        assertFalse(vm.ui.value.visible)
        file.delete()
    }

    @Test fun `stale record with a stored id cancels without rescanning DownloadManager`() = runTest(main.dispatcher) {
        // The version check comes first, so a stale record that already pins its
        // DownloadManager id never pays for a findDownload scan/hash.
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val file = File.createTempFile("uukanshu-stale-id", ".apk").also { it.delete() }
        val payload = info(5L, sha(good), version = UpdateDownloader.currentVersion(app))
        val dl = FakeDl(file, existingId = 77L)
        val prefs = MutableFakePrefs().also {
            it.setUpdateDownloadRecord(UpdateDownloadRecord(payload, downloadId = 55L))
        }
        val vm = UpdateViewModel(
            app, prefs, object : ReleaseFetcher {
                override fun fetchLatest(): UpdateInfo = payload
            }, dl, ActivityLauncher { }, main.dispatcher,
        )
        await()
        assertEquals(0, dl.findDownloadCalls)
        assertEquals(listOf(55L), dl.cancelledIds)
        assertNull(prefs.updateDownloadRecord.first())
        assertFalse(vm.ui.value.visible)
        file.delete()
    }

    @Test fun `cancel download clears the durable record`() = runTest(main.dispatcher) {
        // Regression: after success the dialog nulls downloadId, so cancel rebuilt
        // a record with a null id. Full structural equality then never matched the
        // stored id and the record leaked; sameRequestAs fixes that.
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        grantCanInstall(app)
        val payload = info(5L, sha(good))
        val file = File.createTempFile("uukanshu-cancel", ".apk").also { it.delete() }
        val gate = CompletableDeferred<Unit>()
        val dl = FakeDl(file, gate = gate, onSuccess = { file.writeBytes(good) })
        val prefs = MutableFakePrefs()
        val vm = UpdateViewModel(
            app, prefs, object : ReleaseFetcher {
                override fun fetchLatest(): UpdateInfo = payload
            }, dl, ActivityLauncher { }, main.dispatcher,
        )
        vm.manualCheck()
        await()
        vm.startDownload()
        await()
        assertEquals(UpdateDownloadRecord(payload, 42L), prefs.updateDownloadRecord.first())
        vm.cancelDownload()
        await()
        assertEquals(listOf(42L), dl.cancelledIds)
        assertNull(prefs.updateDownloadRecord.first())
        assertFalse(vm.ui.value.downloading)
        file.delete()
    }
}
