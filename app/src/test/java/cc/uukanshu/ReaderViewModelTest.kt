package cc.uukanshu

import cc.uukanshu.data.convert.T2S

import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.data.repo.BookRepo
import cc.uukanshu.di.RepoApi
import cc.uukanshu.ui.reader.ReaderViewModel
import kotlinx.coroutines.CompletableDeferred
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

    @Test fun lateFreshTocUpdatesTotalOnly() = runTest {
        // One producer per screen (I1): there is nothing to supersede. A fresh
        // TOC that lands AFTER a content load may only touch total (I4); the
        // load-owned snapshot (position/text) stays put, and the next load
        // resolves against the new generation.
        val gate = CompletableDeferred<Unit>()
        val base = MutableFakeRepo(
            cached = testDetail(101L, 102L),
            chaptersText = mutableMapOf(101L to "t1", 102L to "t2", 103L to "t3"),
        )
        val repo = object : RepoApi by base {
            override suspend fun detail(bookId: String): BookRepo.Detail {
                gate.await()
                return testDetail(101L, 102L, 103L)
            }
        }
        val vm = ReaderViewModel(repo, T2S(), MutableFakePrefs(), "1", 1, 101L)
        idle()
        val ui = vm.ui.value as ReaderViewModel.Ui.Content
        assertEquals(1, ui.position)
        assertEquals("Syncing must not bump total", 2, ui.total)
        assertEquals("t1", ui.text)
        gate.complete(Unit)
        idle()
        val after = vm.ui.value as ReaderViewModel.Ui.Content
        assertEquals("total follows the accepted Fresh generation", 3, after.total)
        assertEquals("position is load-owned", 1, after.position)
        assertEquals("text is load-owned", "t1", after.text)
        vm.load(2)
        idle()
        val next = vm.ui.value as ReaderViewModel.Ui.Content
        assertEquals("next load resolves against the new generation", 2, next.position)
        assertEquals(3, next.total)
    }

    @Test fun freshLandingMidChapterFetchKeepsGrownTotal() = runTest {
        // A Fresh that lands while the chapter fetch is in flight already bumped
        // the Loading total via setTotal; the load's final paint must not clobber
        // it back to the stale snapshot size (which false-AtEnds next()).
        val detailGate = CompletableDeferred<Unit>()
        val chapterGate = CompletableDeferred<Unit>()
        val base = MutableFakeRepo(
            cached = testDetail(101L, 102L),
            chaptersText = mutableMapOf(), // nothing cached -> network chapter path
        )
        val repo = object : RepoApi by base {
            override suspend fun detail(bookId: String): BookRepo.Detail {
                detailGate.await()
                return testDetail(101L, 102L, 103L)
            }
            override suspend fun chapter(url: String): cc.uukanshu.data.parse.Parser.ChapterContent {
                chapterGate.await()
                return cc.uukanshu.data.parse.Parser.ChapterContent(
                    book = "T", title = "c", text = "t1",
                    prevUrl = null, tocUrl = null, nextUrl = null,
                )
            }
        }
        val vm = ReaderViewModel(repo, T2S(), MutableFakePrefs(), "1", 1, 101L)
        main.dispatcher.scheduler.runCurrent()
        main.dispatcher.scheduler.runCurrent() // load owns Syncing(2), suspends on chapter()
        detailGate.complete(Unit)
        main.dispatcher.scheduler.runCurrent()
        main.dispatcher.scheduler.runCurrent() // Fresh(3) lands, bumps Loading total 0 -> 3
        chapterGate.complete(Unit)
        idle()
        val ui = vm.ui.value
        assertTrue("expected Content, got $ui", ui is ReaderViewModel.Ui.Content)
        ui as ReaderViewModel.Ui.Content
        assertEquals("total must follow the accepted Fresh generation", 3, ui.total)
        assertEquals("position is load-owned", 1, ui.position)
        assertEquals("t1", ui.text)
    }

    @Test fun outOfRangeRescuedByInFlightFresh() = runTest {
        // Target pageId exists only in the fresh TOC (shift between Detail tap
        // and Reader open). The producer's fetch is in flight (Syncing): the
        // load must wait for its terminal and re-resolve by stable pageId.
        val gate = CompletableDeferred<Unit>()
        val base = MutableFakeRepo(
            cached = testDetail(101L),
            chaptersText = mutableMapOf(101L to "t1", 102L to "t2"),
        )
        val repo = object : RepoApi by base {
            override suspend fun detail(bookId: String): BookRepo.Detail {
                gate.await()
                return testDetail(101L, 102L)
            }
        }
        val vm = ReaderViewModel(repo, T2S(), MutableFakePrefs(), "1", 1, 102L)
        idle()   // Syncing painted; load waits on the in-flight fetch
        gate.complete(Unit)
        idle()   // Fresh terminal lands; load re-resolves by pageId
        val ui = vm.ui.value
        assertTrue("expected Content after rescue, got $ui", ui is ReaderViewModel.Ui.Content)
        assertEquals(2, (ui as ReaderViewModel.Ui.Content).position)
        assertEquals("t2", ui.text)
    }

    @Test fun failedTocRetryRestartsProducer() = runTest {
        // No cache; the first TWO fetches fail. awaitGeneration restarts the
        // producer once per load (bounded): the first load paints the Failed
        // error; the retry button's restart succeeds and renders.
        var detailCalls = 0
        val base = MutableFakeRepo(chaptersText = mutableMapOf(101L to "t1"))
        val repo = object : RepoApi by base {
            override suspend fun detail(bookId: String): BookRepo.Detail {
                detailCalls++
                if (detailCalls <= 2) throw IOException("network down")
                return testDetail(101L)
            }
        }
        val vm = ReaderViewModel(repo, T2S(), MutableFakePrefs(), "1", 1, 101L)
        idle()
        assertTrue(vm.ui.value is ReaderViewModel.Ui.Error)
        assertEquals(2, detailCalls)
        vm.load(1, 101L)
        idle()
        val ui = vm.ui.value
        assertTrue("retry must restart the producer and render, got $ui", ui is ReaderViewModel.Ui.Content)
        assertEquals(3, detailCalls)
    }

    @Test fun outOfRangeStaleRetriesOncePerLoad() = runTest {
        // Stale-final + target missing from both generations: exactly ONE
        // bounded restart per load(), then the bounds verdict. Repeated
        // retries each do one more attempt — never a loop.
        var detailCalls = 0
        val base = MutableFakeRepo(cached = testDetail(101L))
        val repo = object : RepoApi by base {
            override suspend fun detail(bookId: String): BookRepo.Detail {
                detailCalls++
                throw IOException("network down")
            }
        }
        val vm = ReaderViewModel(repo, T2S(), MutableFakePrefs(), "1", 99, 0L)
        idle()
        // Producer run 1 (init) + the single bounded rescue attempt.
        assertEquals(2, detailCalls)
        assertTrue(vm.ui.value is ReaderViewModel.Ui.Error)
        vm.load(99)
        idle()
        assertEquals("bounded per load, never a loop", 3, detailCalls)
        assertTrue(vm.ui.value is ReaderViewModel.Ui.Error)
    }

    @Test fun staleRescueWaitsForFreshTerminal() = runTest {
        // Stale-final + target only in fresh: the bounded restart must wait for
        // the new terminal (Fresh), not return the Syncing paint. Old code woke
        // on Syncing([101]), re-resolved miss, painted Deleted; fixed code waits
        // for Fresh([101,102]) and rescues by pageId.
        var detailCalls = 0
        val secondGate = CompletableDeferred<Unit>()
        val base = MutableFakeRepo(
            cached = testDetail(101L),
            chaptersText = mutableMapOf(101L to "t1", 102L to "t2"),
        )
        val repo = object : RepoApi by base {
            override suspend fun detail(bookId: String): BookRepo.Detail {
                detailCalls++
                if (detailCalls == 1) throw IOException("network down")
                secondGate.await()
                return testDetail(101L, 102L)
            }
        }
        val vm = ReaderViewModel(repo, T2S(), MutableFakePrefs(), "1", 1, 102L)
        idle() // run1 Stale + restart issued; run2 suspended at secondGate
        assertEquals(2, detailCalls)
        // Must still be Loading (waiting terminal), never early bounds Error.
        assertTrue("must wait for Fresh terminal, got ${vm.ui.value}", vm.ui.value is ReaderViewModel.Ui.Loading)
        secondGate.complete(Unit)
        idle()
        val ui = vm.ui.value
        assertTrue("expected Content after Stale->Fresh rescue, got $ui", ui is ReaderViewModel.Ui.Content)
        assertEquals(2, (ui as ReaderViewModel.Ui.Content).position)
        assertEquals("t2", ui.text)
    }

    @Test fun rapidNextDoesNotStealPendingId() = runTest {
        // pendingPageId consumed synchronously on load() entry (Main): a rapid
        // second load before the first TOC resolves must not steal the first
        // load's id. Old code cleared inside the coroutine, so load(2) aliased
        // to pageId 101 (pos 1); fixed code loads pos 2 positionally.
        val detailGate = CompletableDeferred<Unit>()
        val base = MutableFakeRepo(chaptersText = mutableMapOf(101L to "t1", 102L to "t2"))
        val repo = object : RepoApi by base {
            override suspend fun detail(bookId: String): BookRepo.Detail {
                detailGate.await()
                return testDetail(101L, 102L)
            }
        }
        val vm = ReaderViewModel(repo, T2S(), MutableFakePrefs(), "1", 1, 101L)
        main.dispatcher.scheduler.runCurrent() // init starts load(1), suspends on gate
        vm.load(2) // rapid next before TOC resolves; must be positional
        detailGate.complete(Unit)
        idle()
        val ui = vm.ui.value
        assertTrue("expected Content, got $ui", ui is ReaderViewModel.Ui.Content)
        ui as ReaderViewModel.Ui.Content
        assertEquals("second load must not steal pending id", 2, ui.position)
        assertEquals("t2", ui.text)
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

    @Test fun chapterFailureRetryKeepsPageIdNotNeighbor() = runTest {
        // TOC shift: pageId 102 moved from position 1 to 2. First chapter fetch
        // fails; Error must carry effective position + pageId so retry re-opens
        // the same chapter instead of aliasing to the neighbor at the stale arg.
        // Why: old catch emitted Loading.position (tap arg), retry lost the id.
        val shifted = testDetail(101L, 102L)
        val base = MutableFakeRepo(
            fresh = shifted,
            chaptersText = mutableMapOf(101L to "t1"),
        )
        var chapterCalls = 0
        val repo = object : RepoApi by base {
            override suspend fun cachedChapterContent(bookId: String, pageId: Long): String? =
                base.chaptersText[pageId]
            override suspend fun chapter(url: String): cc.uukanshu.data.parse.Parser.ChapterContent {
                chapterCalls++
                if (chapterCalls == 1) throw IOException("network down")
                return base.chapter(url)
            }
            override suspend fun cachedDetail(bookId: String): BookRepo.Detail? = null
            override suspend fun detail(bookId: String): BookRepo.Detail = shifted
            override suspend fun saveChapterContent(bookId: String, pageId: Long, content: String) {
                base.chaptersText[pageId] = content
            }
        }
        // Ensure target text exists for retry (second call succeeds).
        base.chaptersText[102L] = "t2-retry"
        // Remove it for the first attempt to force network path, then restore via chapter().
        base.chaptersText.remove(102L)
        val vm = ReaderViewModel(repo, T2S(), MutableFakePrefs(), "1", 1, 102L)
        idle()
        val err = vm.ui.value
        assertTrue("expected Error after fetch failure, got $err", err is ReaderViewModel.Ui.Error)
        err as ReaderViewModel.Ui.Error
        assertEquals("Error must carry resolved position", 2, err.position)
        assertEquals("Error must carry pageId for retry", 102L, err.pageId)
        vm.load(err.position, err.pageId)
        idle()
        val ui = vm.ui.value
        assertTrue("retry must render Content, got $ui", ui is ReaderViewModel.Ui.Content)
        ui as ReaderViewModel.Ui.Content
        assertEquals(2, ui.position)
        // Bookmark must target the resolved chapter, never the stale neighbor.
        assertEquals(1, base.savedProgress.size)
        assertEquals(2, base.savedProgress[0].second)
        assertEquals(102L, base.savedProgress[0].third)
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

    @Test fun setFontScaleClampsAndIdempotent() = runTest {
        val repo = MutableFakeRepo(
            fresh = testDetail(101L),
            chaptersText = mutableMapOf(101L to "t1"),
        )
        val prefs = MutableFakePrefs()
        val vm = ReaderViewModel(repo, T2S(), prefs, "1", 1, 101L)
        idle()
        // Absolute set for the Slider: out-of-range coerces to bounds.
        vm.setFontScale(2f)
        assertEquals(Prefs.FONT_MAX, vm.fontScale.value)
        vm.setFontScale(0f)
        assertEquals(Prefs.FONT_MIN, vm.fontScale.value)
        // Idempotent: same value is a no-op, stays put.
        vm.setFontScale(Prefs.FONT_MIN)
        assertEquals(Prefs.FONT_MIN, vm.fontScale.value)
        vm.setFontScale(1.3f)
        assertEquals(1.3f, vm.fontScale.value!!)
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
