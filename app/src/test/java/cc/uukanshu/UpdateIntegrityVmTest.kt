package cc.uukanshu

import androidx.test.core.app.ApplicationProvider
import cc.uukanshu.data.update.ActivityLauncher
import cc.uukanshu.data.update.ApkDownloader
import cc.uukanshu.data.update.DownloadStatus
import cc.uukanshu.data.update.ReleaseFetcher
import cc.uukanshu.data.update.UpdateDownloader
import cc.uukanshu.data.update.UpdateInfo
import cc.uukanshu.core.ApkChecksumMismatchException
import cc.uukanshu.core.ApkIncompleteException
import cc.uukanshu.core.Errors
import cc.uukanshu.ui.update.UpdateViewModel
import kotlinx.coroutines.CompletableDeferred
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
        private val gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null,
    ) : ApkDownloader {
        override fun apkFile(info: UpdateInfo): File = file
        override fun enqueue(info: UpdateInfo): Long = onEnqueue()
        override fun cancel(downloadId: Long) = Unit
        override fun observe(downloadId: Long) = flow {
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

    private fun await(cond: () -> Boolean) {
        var tries = 0
        while (!cond() && tries < 100) {
            Thread.sleep(50)
            main.dispatcher.scheduler.advanceUntilIdle()
            tries++
        }
    }

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
        )

    private fun fetchThenStart(info: UpdateInfo, dl: FakeDl): UpdateViewModel {
        val vm = vmFor(info, dl)
        vm.manualCheck()
        await { vm.ui.value.info != null }
        vm.startDownload()
        return vm
    }

    @Test fun `dm success with matching digest readies file`() = runTest {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        grantCanInstall(app)
        val file = File.createTempFile("uukanshu-integ", ".apk").also { it.delete() }
        val dl = FakeDl(file, onSuccess = { file.writeBytes(good) })
        val vm = fetchThenStart(info(5L, sha(good)), dl)
        await { vm.ui.value.fileReady }
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

    @Test fun `dm success with mismatched digest deletes file and errors`() = runTest {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        grantCanInstall(app)
        val file = File.createTempFile("uukanshu-integ", ".apk").also { it.delete() }
        val dl = FakeDl(file, onSuccess = { file.writeBytes(bad) })
        val vm = fetchThenStart(info(5L, sha(good)), dl)
        await { vm.ui.value.error != null }
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

    @Test fun `legacy release without digest keeps size-only success`() = runTest {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        grantCanInstall(app)
        val file = File.createTempFile("uukanshu-integ", ".apk").also { it.delete() }
        val dl = FakeDl(file, onSuccess = { file.writeBytes(good) })
        val vm = fetchThenStart(info(5L, null), dl)
        await { vm.ui.value.fileReady }
        val ui = vm.ui.value
        assertTrue(
            "fileReady=${ui.fileReady} error=${ui.error} needs=${ui.needsUnknownSources}",
            ui.fileReady,
        )
        assertNull(ui.error)
        file.delete()
    }

    @Test fun `install gate accepts digest-verified file`() = runTest {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        // Under the app's external Download dir: FileProvider's configured root
        // (external-files-path Download/) must cover the APK, as in production.
        val file = File(app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)!!, "uukanshu-integ.apk")
            .also { it.writeBytes(good) }
        val launched = AtomicInteger(0)
        val vm = vmFor(info(5L, sha(good)), FakeDl(file), launched)
        vm.manualCheck()
        await { vm.ui.value.fileReady }
        vm.install()
        await { launched.get() == 1 || vm.ui.value.error != null }
        val ui = vm.ui.value
        assertEquals("launched=${launched.get()} error=${ui.error} fileReady=${ui.fileReady}", 1, launched.get())
        assertNull(ui.error)
        file.delete()
    }

    @Test fun `install gate rejects corrupt file with digest`() = runTest {
        // fileReady minted while the file was good, disk corrupted after:
        // the install gate re-verifies (IO) and refuses — last line of defense.
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val file = File(app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)!!, "uukanshu-integ.apk")
            .also { it.writeBytes(good) }
        val launched = AtomicInteger(0)
        val vm = vmFor(info(5L, sha(good)), FakeDl(file), launched)
        vm.manualCheck()
        await { vm.ui.value.fileReady }
        file.writeBytes(bad)
        vm.install()
        await { vm.ui.value.error != null }
        assertEquals("launched=${launched.get()} error=${vm.ui.value.error}", 0, launched.get())
        assertFalse(vm.ui.value.fileReady)
        file.delete()
    }

    @Test fun `rapid install taps fire installer once`() = runTest {
        // Repro for async-gate double-fire: two back-to-back taps must share
        // one Main-guarded verification (see markChecking pattern).
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val file = File(app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)!!, "uukanshu-integ.apk")
            .also { it.writeBytes(good) }
        val launched = AtomicInteger(0)
        val vm = vmFor(info(5L, sha(good)), FakeDl(file), launched)
        vm.manualCheck()
        await { vm.ui.value.fileReady }
        vm.install()
        vm.install()
        await { launched.get() == 1 || vm.ui.value.error != null }
        // Give the second tap a chance to misfire, then assert single fire.
        Thread.sleep(200)
        main.dispatcher.scheduler.advanceUntilIdle()
        assertEquals("launched=${launched.get()} error=${vm.ui.value.error}", 1, launched.get())
        assertNull(vm.ui.value.error)
        assertFalse(vm.ui.value.installing)
        file.delete()
    }

    @Test fun `install gate failure clears installing and maps via Errors`() = runTest {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val file = File(app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)!!, "uukanshu-integ.apk")
            .also { it.writeBytes(good) }
        val launched = AtomicInteger(0)
        val vm = vmFor(info(5L, sha(good)), FakeDl(file), launched)
        vm.manualCheck()
        await { vm.ui.value.fileReady }
        file.writeBytes(bad)
        vm.install()
        await { vm.ui.value.error != null }
        assertEquals(0, launched.get())
        assertFalse(vm.ui.value.installing)
        assertEquals(Errors.friendly(ApkIncompleteException()), vm.ui.value.error)
    }

    @Test fun `dm mismatch error maps via Errors`() = runTest {
        // Typed mapping, not substring sniffing: Traditional source for display().
        assertEquals(
            "APK 校驗失敗，請重新下載",
            Errors.friendly(ApkChecksumMismatchException()),
        )
    }

    /** A stateful fetcher: first call returns [first], then [second] — the
     *  mid-flight re-check a user can trigger while a download is in flight. */
    private fun swapFetcher(a: UpdateInfo, b: UpdateInfo) = object : ReleaseFetcher {
        val fetched = AtomicInteger(0)
        override fun fetchLatest(): UpdateInfo = if (fetched.incrementAndGet() == 1) a else b
    }

    @Test fun `mid-flight recheck then success never mints or errors for the wrong version`() = runTest {
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
        )
        vm.manualCheck()
        await { vm.ui.value.info?.version == "9.9.9" }
        vm.startDownload()
        await { vm.ui.value.downloading && vm.ui.value.downloadId == 42L }
        // Mid-flight: dialog swaps to the newer release.
        vm.manualCheck()
        await { vm.ui.value.info?.version == "9.9.10" }
        file.writeBytes(good) // DM lands v9.9.9's bytes, which match release A's digest
        gate.complete(Unit)
        await { !vm.ui.value.downloading }
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

    @Test fun `skip during flight then success mints nothing while hidden`() = runTest {
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
        await { vm.ui.value.info != null }
        vm.startDownload()
        await { vm.ui.value.downloading && vm.ui.value.downloadId == 42L }
        vm.skipVersion()
        await { vm.ui.value.info == null && !vm.ui.value.visible }
        gate.complete(Unit)
        await { !vm.ui.value.downloading }
        val ui = vm.ui.value
        assertFalse("receipt minted with no update info", ui.fileReady)
        assertFalse(ui.downloadSucceeded)
        assertFalse(ui.visible)
        vm.reopen()
        await { vm.ui.value.visible }
        assertFalse(
            "reopened dialog must not offer install for a skipped version",
            vm.ui.value.fileReady,
        )
        file.delete()
    }
}

