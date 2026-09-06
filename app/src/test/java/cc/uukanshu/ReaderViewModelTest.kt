package cc.uukanshu

import cc.uukanshu.ui.reader.ReaderViewModel
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
