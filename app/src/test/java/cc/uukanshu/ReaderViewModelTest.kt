package cc.uukanshu

import cc.uukanshu.data.repo.BookRepo
import cc.uukanshu.di.RepoApi
import cc.uukanshu.ui.reader.ReaderViewModel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReaderViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private fun idle() {
        main.dispatcher.scheduler.advanceUntilIdle()
    }

    @Test fun cacheFirstRendersWithoutNetwork() = runTest {
        val repo = MutableFakeRepo(
            cached = testDetail(101L, 102L),
            fresh = testDetail(101L, 102L),
            chaptersText = mutableMapOf(101L to "cached-text"),
        )
        val vm = ReaderViewModel(repo, TestConvert(), MutableFakePrefs(), "1", 1, 101L)
        idle()
        val ui = vm.ui.value
        assertTrue(ui is ReaderViewModel.Ui.Content)
        assertEquals("cached-text", (ui as ReaderViewModel.Ui.Content).text)
        assertEquals(1, repo.savedProgress.size)
    }

    @Test fun outOfRangeShowsError() = runTest {
        val repo = MutableFakeRepo(fresh = testDetail(101L))
        val vm = ReaderViewModel(repo, TestConvert(), MutableFakePrefs(), "1", 99, 0L)
        idle()
        val ui = vm.ui.value
        assertTrue(ui is ReaderViewModel.Ui.Error)
    }

    @Test fun newLoadCancelsInFlightRevalidate() = runTest {
        // load(1) paints cached TOC and starts a hanging revalidate; load(2)
        // must kill it (structured child of the load) and render on its own.
        // A superseded revalidate must never commit totals after a newer load.
        var revalidateCancelled = false
        var detailCalls = 0
        val base = MutableFakeRepo(
            cached = testDetail(101L, 102L),
            chaptersText = mutableMapOf(101L to "t1", 102L to "t2"),
        )
        val repo = object : RepoApi by base {
            override suspend fun detail(bookId: String): BookRepo.Detail {
                detailCalls++
                if (detailCalls == 1) {
                    try {
                        awaitCancellation()
                    } finally {
                        revalidateCancelled = true
                    }
                }
                return testDetail(101L, 102L)
            }
        }
        val vm = ReaderViewModel(repo, TestConvert(), MutableFakePrefs(), "1", 1, 101L)
        main.dispatcher.scheduler.runCurrent()
        main.dispatcher.scheduler.runCurrent()
        assertEquals(1, detailCalls)
        vm.load(2)
        idle()
        assertTrue("superseded revalidate must be cancelled", revalidateCancelled)
        val ui = vm.ui.value
        assertTrue(ui is ReaderViewModel.Ui.Content)
        assertEquals(2, (ui as ReaderViewModel.Ui.Content).position)
    }

    @Test fun pageIdWinsOverShiftedPosition() = runTest {
        // Detail tapped position 1 (pageId 102); TOC shifted so 102 is now position 2.
        val repo = MutableFakeRepo(
            fresh = testDetail(101L, 102L),
            chaptersText = mutableMapOf(101L to "t1", 102L to "t2"),
        )
        val vm = ReaderViewModel(repo, TestConvert(), MutableFakePrefs(), "1", 1, 102L)
        idle()
        val ui = vm.ui.value
        assertTrue(ui is ReaderViewModel.Ui.Content)
        assertEquals(2, (ui as ReaderViewModel.Ui.Content).position)
    }
}
