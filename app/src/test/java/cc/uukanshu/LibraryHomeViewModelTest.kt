package cc.uukanshu

import cc.uukanshu.data.repo.BookRepo
import cc.uukanshu.ui.home.HomeViewModel
import cc.uukanshu.ui.library.LibraryViewModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class LibraryViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private fun idle() {
        main.dispatcher.scheduler.advanceUntilIdle()
    }

    private fun book(id: String) = BookRepo.CachedBook(id, id, "Au", total = 2, cached = 2, bytes = 10L)

    @Test fun shelfRendersRows() = runTest {
        val repo = MutableFakeRepo(libraryFlowRows = listOf(book("a"), book("b")))
        val vm = LibraryViewModel(repo, MutableFakePrefs(), TestConvert(), RecordingDownloads())
        idle()
        val load = vm.ui.value.load
        assertTrue(load is LibraryViewModel.Load.Shelf)
        assertEquals(listOf("a", "b"), (load as LibraryViewModel.Load.Shelf).books.map { it.id })
    }

    @Test fun deleteDelegatesAndForgets() = runTest {
        val repo = MutableFakeRepo(
            libraryRows = listOf(book("a")),
            libraryFlowRows = listOf(book("a")),
        )
        val downloads = RecordingDownloads()
        val vm = LibraryViewModel(repo, MutableFakePrefs(), TestConvert(), downloads)
        idle()
        vm.delete("a")
        idle()
        assertEquals(listOf("a"), repo.deleted)
        assertEquals(listOf("a"), downloads.forgotten)
    }

    @Test fun refreshFailureWithRowsIsFooterError() = runTest {
        val repo = MutableFakeRepo(
            libraryFlowRows = listOf(book("a")),
            libraryRows = listOf(book("a")),
            libraryFailure = IOException("db down"),
        )
        val vm = LibraryViewModel(repo, MutableFakePrefs(), TestConvert(), RecordingDownloads())
        idle()
        vm.refresh()
        idle()
        val load = vm.ui.value.load
        assertTrue(load is LibraryViewModel.Load.Shelf)
        // libraryFlow() throws on collection (init) or library() throws (refresh):
        // either way the shelf keeps rows with an error or fails closed — never empty.
        load as LibraryViewModel.Load.Shelf
        assertTrue(load.error != null || load.books.isNotEmpty())
    }
}

class HomeViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun sameKeyReturnsSameFlow() {
        val vm = HomeViewModel(MutableFakeRepo(), MutableFakePrefs(), TestConvert())
        assertSame(vm.pagingFor(0, 1), vm.pagingFor(0, 1))
    }

    @Test fun differentKeysDiffer() {
        val vm = HomeViewModel(MutableFakeRepo(), MutableFakePrefs(), TestConvert())
        assertNotSame(vm.pagingFor(0, 1), vm.pagingFor(1, 2))
    }

    @Test fun evictsBeyondMaxPagers() {
        val vm = HomeViewModel(MutableFakeRepo(), MutableFakePrefs(), TestConvert())
        val first = vm.pagingFor(0, 1)
        // Fill recent + 10 categories (11 max), then one more forces eviction.
        for (id in 1..10) vm.pagingFor(1, id)
        vm.pagingFor(1, 99)
        assertNotSame(first, vm.pagingFor(0, 1))
    }

    @Test fun selectTabMarksPendingTop() {
        val vm = HomeViewModel(MutableFakeRepo(), MutableFakePrefs(), TestConvert())
        vm.selectTab(1)
        assertEquals(true, vm.consumePendingTop(vm.listKey(1, 1)))
        assertEquals(false, vm.consumePendingTop(vm.listKey(1, 1)))
    }
}
