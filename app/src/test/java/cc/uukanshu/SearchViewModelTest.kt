package cc.uukanshu

import cc.uukanshu.data.parse.Parser
import cc.uukanshu.ui.search.SearchViewModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class SearchViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private fun advanceSearch() {
        // debounce(400) + flatMapLatest collection, all on Main.
        main.dispatcher.scheduler.advanceTimeBy(600)
        main.dispatcher.scheduler.advanceUntilIdle()
    }

    @Test fun blankQueryStaysIdle() = runTest {
        val vm = SearchViewModel(MutableFakeRepo(), MutableFakePrefs(), TestConvert())
        main.dispatcher.scheduler.advanceUntilIdle()
        vm.query("   ")
        main.dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.ui.value is SearchViewModel.Ui.Idle)
    }

    @Test fun successDedupsById() = runTest {
        val books = listOf(
            Parser.BookItem(id = "1", title = "A"),
            Parser.BookItem(id = "1", title = "A-dup"),
            Parser.BookItem(id = "2", title = "B"),
        )
        val repo = MutableFakeRepo(searchResult = Parser.SearchResult(3, books))
        val vm = SearchViewModel(repo, MutableFakePrefs(), TestConvert())
        main.dispatcher.scheduler.advanceUntilIdle()
        vm.query("key")
        advanceSearch()
        val ui = vm.ui.value
        assertTrue(ui is SearchViewModel.Ui.Success)
        assertEquals(listOf("1", "2"), (ui as SearchViewModel.Ui.Success).books.map { it.id })
    }

    @Test fun failureShowsError() = runTest {
        val repo = MutableFakeRepo(searchFailure = IOException("offline"))
        val vm = SearchViewModel(repo, MutableFakePrefs(), TestConvert())
        main.dispatcher.scheduler.advanceUntilIdle()
        vm.query("key")
        advanceSearch()
        assertTrue(vm.ui.value is SearchViewModel.Ui.Error)
    }
}
