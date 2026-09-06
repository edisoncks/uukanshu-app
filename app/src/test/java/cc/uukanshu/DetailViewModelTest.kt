package cc.uukanshu

import cc.uukanshu.ui.detail.DetailViewModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class DetailViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private fun vm(
        repo: MutableFakeRepo,
        downloads: RecordingDownloads = RecordingDownloads(),
    ) = DetailViewModel(repo, MutableFakePrefs(), TestConvert(), "1", downloads)

    private fun idle() {
        main.dispatcher.scheduler.advanceUntilIdle()
    }

    @Test fun freshPaintsReady() = runTest {
        val repo = MutableFakeRepo(fresh = testDetail(101L, 102L))
        val vm = vm(repo)
        idle()
        val load = vm.ui.value.load
        assertTrue(load is DetailViewModel.Load.Ready)
        assertEquals(2, (load as DetailViewModel.Load.Ready).chapters.size)
        assertEquals(false, load.refreshing)
    }

    @Test fun emptyFreshKeepsStaleWithOfflineFlag() = runTest {
        val cached = testDetail(101L)
        val repo = MutableFakeRepo(cached = cached, fresh = testDetail())
        val vm = vm(repo)
        idle()
        val load = vm.ui.value.load
        assertTrue(load is DetailViewModel.Load.Ready)
        load as DetailViewModel.Load.Ready
        assertEquals(1, load.chapters.size)
        assertEquals(true, load.offline)
    }

    @Test fun failureWithoutCacheShowsFailed() = runTest {
        val repo = MutableFakeRepo(failure = IOException("network down"))
        val vm = vm(repo)
        idle()
        val load = vm.ui.value.load
        assertTrue(load is DetailViewModel.Load.Failed)
    }

    @Test fun restartSeedsRetainedManagerProgress() = runTest {
        // A failed 500/1000 must show 500/1000 on restart, never flash 0/0.
        val repo = MutableFakeRepo(fresh = testDetail(101L))
        val downloads = RecordingDownloads()
        downloads.publish("1", cc.uukanshu.data.download.BookDownloadManager.State(
            downloading = false, done = 500, total = 1000, error = "boom",
        ))
        val vm = vm(repo, downloads)
        idle()
        vm.downloadAll()
        assertEquals(listOf("1"), downloads.started)
        assertEquals(true, vm.ui.value.downloading)
        assertEquals(500, vm.ui.value.done)
        assertEquals(1000, vm.ui.value.downloadTotal)
        assertEquals(null, vm.ui.value.downloadError)
    }

    @Test fun downloadDelegatesToManager() = runTest {
        val repo = MutableFakeRepo(fresh = testDetail(101L))
        val downloads = RecordingDownloads()
        val vm = vm(repo, downloads)
        idle()
        vm.downloadAll()
        assertEquals(listOf("1"), downloads.started)
        vm.cancelDownload()
        assertEquals(listOf("1"), downloads.cancelled)
    }
}
