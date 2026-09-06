package cc.uukanshu

import cc.uukanshu.data.parse.Parser
import cc.uukanshu.di.RepoApi
import cc.uukanshu.ui.search.SearchViewModel
import kotlinx.coroutines.CompletableDeferred
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

    @Test fun retryAfterErrorRefiresSearch() = runTest {
        // Identical-text retry must refire, not conflate away in the query flow.
        val books = listOf(Parser.BookItem(id = "1", title = "A"))
        val base = MutableFakeRepo(searchResult = Parser.SearchResult(1, books))
        var calls = 0
        var failNext = true
        val repo = object : RepoApi by base {
            override suspend fun search(keyword: String): Parser.SearchResult {
                calls++
                if (failNext) {
                    failNext = false
                    throw IOException("offline")
                }
                return base.search(keyword)
            }
        }
        val vm = SearchViewModel(repo, MutableFakePrefs(), TestConvert())
        main.dispatcher.scheduler.advanceUntilIdle()
        vm.query("key")
        advanceSearch()
        assertTrue(vm.ui.value is SearchViewModel.Ui.Error)
        assertEquals(1, calls)
        vm.query("key")
        advanceSearch()
        assertEquals(2, calls)
        assertTrue(vm.ui.value is SearchViewModel.Ui.Success)
    }

    @Test fun failureShowsError() = runTest {
        val repo = MutableFakeRepo(searchFailure = IOException("offline"))
        val vm = SearchViewModel(repo, MutableFakePrefs(), TestConvert())
        main.dispatcher.scheduler.advanceUntilIdle()
        vm.query("key")
        advanceSearch()
        assertTrue(vm.ui.value is SearchViewModel.Ui.Error)
    }

    @Test fun loadingKeepsStaleResults() = runTest {
        // New query with results on screen: Loading carries the old books
        // (screen keeps them under a bar) instead of blanking the list.
        val books = listOf(Parser.BookItem(id = "1", title = "A"))
        val base = MutableFakeRepo(searchResult = Parser.SearchResult(1, books))
        val gate = CompletableDeferred<Unit>()
        var searchCalls = 0
        val repo = object : RepoApi by base {
            override suspend fun search(keyword: String): Parser.SearchResult {
                if (++searchCalls == 2) gate.await()
                return base.search(keyword)
            }
        }
        val vm = SearchViewModel(repo, MutableFakePrefs(), TestConvert())
        main.dispatcher.scheduler.advanceUntilIdle()
        vm.query("first")
        advanceSearch()
        assertTrue(vm.ui.value is SearchViewModel.Ui.Success)
        vm.query("second")
        // Past debounce, second search hanging: Loading with stale books.
        main.dispatcher.scheduler.advanceTimeBy(500)
        main.dispatcher.scheduler.runCurrent()
        val loading = vm.ui.value
        assertTrue("expected Loading, got $loading", loading is SearchViewModel.Ui.Loading)
        assertEquals(listOf("1"), (loading as SearchViewModel.Ui.Loading).books.map { it.id })
        gate.complete(Unit)
        advanceSearch()
        assertTrue(vm.ui.value is SearchViewModel.Ui.Success)
    }

    @Test fun initialLoadingHasNoStaleResults() = runTest {
        val books = listOf(Parser.BookItem(id = "1", title = "A"))
        val base = MutableFakeRepo(searchResult = Parser.SearchResult(1, books))
        val gate = CompletableDeferred<Unit>()
        val repo = object : RepoApi by base {
            override suspend fun search(keyword: String): Parser.SearchResult {
                gate.await()
                return base.search(keyword)
            }
        }
        val vm = SearchViewModel(repo, MutableFakePrefs(), TestConvert())
        main.dispatcher.scheduler.advanceUntilIdle()
        vm.query("key")
        main.dispatcher.scheduler.advanceTimeBy(500)
        main.dispatcher.scheduler.runCurrent()
        val loading = vm.ui.value
        assertTrue("expected Loading, got $loading", loading is SearchViewModel.Ui.Loading)
        assertTrue((loading as SearchViewModel.Ui.Loading).books.isEmpty())
        gate.complete(Unit)
        advanceSearch()
        assertTrue(vm.ui.value is SearchViewModel.Ui.Success)
    }
}
