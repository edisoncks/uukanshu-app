package cc.uukanshu

import androidx.test.core.app.ApplicationProvider
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
