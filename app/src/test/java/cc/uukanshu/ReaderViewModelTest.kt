package cc.uukanshu

import cc.uukanshu.data.convert.T2S

import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.data.repo.BookRepo
import cc.uukanshu.di.RepoApi
import cc.uukanshu.ui.reader.ReaderViewModel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

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
        val vm = ReaderViewModel(repo, T2S(), MutableFakePrefs(), "1", 1, 101L)
        idle()
        val ui = vm.ui.value
        assertTrue(ui is ReaderViewModel.Ui.Content)
        assertEquals("cached-text", (ui as ReaderViewModel.Ui.Content).text)
        assertEquals(1, repo.savedProgress.size)
    }

    @Test fun outOfRangeShowsError() = runTest {
        val repo = MutableFakeRepo(fresh = testDetail(101L))
        val vm = ReaderViewModel(repo, T2S(), MutableFakePrefs(), "1", 99, 0L)
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
        val vm = ReaderViewModel(repo, T2S(), MutableFakePrefs(), "1", 1, 101L)
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
        val vm = ReaderViewModel(repo, T2S(), MutableFakePrefs(), "1", 1, 102L)
        idle()
        val ui = vm.ui.value
        assertTrue(ui is ReaderViewModel.Ui.Content)
        assertEquals(2, (ui as ReaderViewModel.Ui.Content).position)
    }

    @Test fun toggleSimplifiedRerendersWithoutRefetch() = runTest {
        val trad = "生命不息，奮鬥不止"
        val simp = "生命不息，奋斗不止"
        val repo = MutableFakeRepo(
            cached = testDetail(101L),
            fresh = testDetail(101L),
            chaptersText = mutableMapOf(101L to trad),
        )
        val vm = ReaderViewModel(repo, T2S(), MutableFakePrefs(), "1", 1, 101L)
        idle()
        assertEquals(trad, (vm.ui.value as ReaderViewModel.Ui.Content).text)
        // Break the network: a refetch would now fail, so success proves re-render from currentRaw.
        repo.failure = IOException("network down")
        vm.toggleSimplified()
        idle()
        val ui = vm.ui.value
        assertTrue(ui is ReaderViewModel.Ui.Content)
        assertEquals(simp, (ui as ReaderViewModel.Ui.Content).text)
        assertEquals(true, vm.simplified.value)
    }

    @Test fun doubleToggleReturnsToStart() = runTest {
        val repo = MutableFakeRepo(
            fresh = testDetail(101L),
            chaptersText = mutableMapOf(101L to "t1"),
        )
        val vm = ReaderViewModel(repo, T2S(), MutableFakePrefs(), "1", 1, 101L)
        idle()
        vm.toggleSimplified()
        vm.toggleSimplified()
        idle()
        assertEquals(false, vm.simplified.value)
    }

    @Test fun fontStepClampsAtBounds() = runTest {
        val repo = MutableFakeRepo(
            fresh = testDetail(101L),
            chaptersText = mutableMapOf(101L to "t1"),
        )
        val vm = ReaderViewModel(repo, T2S(), MutableFakePrefs(), "1", 1, 101L)
        idle()
        vm.font(10f)
        assertEquals(Prefs.FONT_MAX, vm.fontScale.value)
        vm.font(-10f)
        assertEquals(Prefs.FONT_MIN, vm.fontScale.value)
    }

    @Test fun cycleThemeRotatesSystemLightDark() = runTest {
        val repo = MutableFakeRepo(
            fresh = testDetail(101L),
            chaptersText = mutableMapOf(101L to "t1"),
        )
        val vm = ReaderViewModel(repo, T2S(), MutableFakePrefs(), "1", 1, 101L)
        idle()
        assertEquals(Prefs.SYSTEM, vm.theme.value)
        vm.cycleTheme()
        assertEquals(Prefs.LIGHT, vm.theme.value)
        vm.cycleTheme()
        assertEquals(Prefs.DARK, vm.theme.value)
        vm.cycleTheme()
        assertEquals(Prefs.SYSTEM, vm.theme.value)
    }
}
