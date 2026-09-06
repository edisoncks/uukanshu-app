package cc.uukanshu

import cc.uukanshu.core.Errors
import cc.uukanshu.data.parse.Parser
import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.data.net.UukanshuGate
import cc.uukanshu.data.update.UpdateApi
import cc.uukanshu.data.update.UpdateDownloader
import cc.uukanshu.ui.reader.ReaderViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

class HardeningTest {
    // -- Errors: cancellation-safe helpers ---------------------------------

    @Test fun runCatchingExceptCancelCapturesOrdinaryFailure() = runBlocking {
        val r = Errors.runCatchingExceptCancel { throw IOException("boom") }
        assertTrue(r.isFailure)
        assertEquals("boom", r.exceptionOrNull()?.message)
    }

    @Test fun runCatchingExceptCancelRethrowsCancellation() = runBlocking {
        try {
            Errors.runCatchingExceptCancel { throw CancellationException("stop") }
            fail("must rethrow CancellationException")
        } catch (e: CancellationException) {
            assertEquals("stop", e.message)
        }
    }

    @Test fun suppressExceptCancelReturnsNullOnFailure() = runBlocking {
        assertNull(Errors.suppressExceptCancel { throw IOException("boom") })
    }

    @Test fun suppressExceptCancelRethrowsCancellation() = runBlocking {
        try {
            Errors.suppressExceptCancel<Int> { throw CancellationException("stop") }
            fail("must rethrow CancellationException")
        } catch (e: CancellationException) {
            assertEquals("stop", e.message)
        }
    }

    // -- Parser: single id normalization ------------------------------------

    @Test fun normalizeBookIdStripsLeadingZeros() {
        assertEquals("18957", Parser.normalizeBookId("018957"))
        assertEquals("1", Parser.normalizeBookId("  1  "))
    }

    @Test fun normalizeBookIdRejectsBadInput() {
        assertNull(Parser.normalizeBookId(null))
        assertNull(Parser.normalizeBookId(""))
        assertNull(Parser.normalizeBookId("  "))
        assertNull(Parser.normalizeBookId("abc"))
        assertNull(Parser.normalizeBookId("-1"))
        assertNull(Parser.normalizeBookId("99999999999999999999"))
    }

    @Test fun canonicalChapterUrlStripsTrackingParams() {
        assertEquals(
            "https://uukanshu.cc/book/100/101.html",
            Parser.canonicalChapterUrl("/book/100/101.html?from=hot", "https://uukanshu.cc/book/100/"),
        )
        assertEquals(
            "https://uukanshu.cc/book/100/102.html",
            Parser.canonicalChapterUrl("https://uukanshu.cc/book/100/102.html#toc", "https://uukanshu.cc/book/100/"),
        )
    }

    @Test fun canonicalChapterUrlRejectsNonChapter() {
        assertNull(Parser.canonicalChapterUrl("/book/100/", "https://uukanshu.cc/book/100/"))
        assertNull(Parser.canonicalChapterUrl("lastchapter.php", "https://uukanshu.cc/book/100/1.html"))
    }

    // -- Reader: pageId-first resolution -------------------------------------

    private fun ref(pos: Int, pageId: Long) =
        Parser.ChapterRef(pos, pageId, "t-$pos", "https://uukanshu.cc/book/1/$pageId.html")

    @Test fun resolvePrefersPageIdAcrossShift() {
        // TOC insert at front shifts positions: pageId still finds the chapter.
        val shifted = listOf(ref(1, 100L), ref(2, 101L), ref(3, 102L))
        assertEquals(2, ReaderViewModel.resolveEffectivePosition(shifted, 1, 101L))
    }

    @Test fun resolveFallsBackToPositionForPreV4Only() {
        val chapters = listOf(ref(1, 101L), ref(2, 102L))
        assertEquals(1, ReaderViewModel.resolveEffectivePosition(chapters, 1, 0L))
        // Deleted chapter (pageId unknown, position live): -1 routes into the
        // out-of-range Error path instead of aliasing to the neighbor.
        assertEquals(-1, ReaderViewModel.resolveEffectivePosition(chapters, 1, 999L))
    }

    // -- Prefs: single bounds + theme normalization --------------------------

    @Test fun fontBoundsAreSingleSource() {
        assertEquals(0.8f, Prefs.FONT_MIN)
        assertEquals(1.6f, Prefs.FONT_MAX)
        assertEquals(0.8f, Prefs.coerceFontScale(0.1f))
        assertEquals(1.6f, Prefs.coerceFontScale(99f))
        assertEquals(1.2f, Prefs.coerceFontScale(1.2f))
    }

    @Test fun themeNormalizationFailsSafeToSystem() {
        assertEquals(Prefs.SYSTEM, Prefs.normalizeTheme(null))
        assertEquals(Prefs.SYSTEM, Prefs.normalizeTheme(""))
        assertEquals(Prefs.SYSTEM, Prefs.normalizeTheme("dark-mode"))
        assertEquals(Prefs.DARK, Prefs.normalizeTheme(Prefs.DARK))
        // Unknown toggle input normalizes to SYSTEM first, then cycles to LIGHT.
        assertEquals(Prefs.LIGHT, Prefs.next("bogus"))
    }

    // -- Gate: nesting fails fast instead of deadlocking ---------------------

    @Test fun nestedGateThrowsInsteadOfDeadlocking() = runBlocking {
        val gate = UukanshuGate()
        try {
            gate.withPermit {
                gate.withPermit { }
            }
            fail("nested withPermit must throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("deadlock"))
        }
    }

    @Test fun sequentialGatePermitsSucceed() = runBlocking {
        val gate = UukanshuGate()
        gate.withPermit { }
        gate.withPermit { }
    }

    // -- JsonMini: trailing data fails closed --------------------------------

    @Test fun trailingGarbageYieldsNoUpdate() {
        assertNull(UpdateApi.parse("""{"tag_name":"v1.0.15","assets":[]} trailing"""))
    }

    // -- ApkState: single decision table -------------------------------------

    @Test fun apkStateDecisionTable() {
        val f = File.createTempFile("apk", ".apk").apply { writeBytes(ByteArray(10)) }
        try {
            assertEquals(UpdateDownloader.ApkState.Ready, UpdateDownloader.apkState(f, 10L, false))
            assertEquals(UpdateDownloader.ApkState.Partial, UpdateDownloader.apkState(f, 11L, false))
            // Unknown size without DM receipt never counts as complete.
            assertEquals(UpdateDownloader.ApkState.Partial, UpdateDownloader.apkState(f, null, false))
            // Unknown size with DM SUCCESS receipt is installable.
            assertEquals(UpdateDownloader.ApkState.Ready, UpdateDownloader.apkState(f, null, true))
        } finally {
            f.delete()
        }
        val missing = File("/tmp/uukanshu-test-missing-${System.nanoTime()}.apk")
        assertEquals(UpdateDownloader.ApkState.Missing, UpdateDownloader.apkState(missing, 10L, false))
    }

    @Test fun backgroundRevalidateCancellationPropagates() = runBlocking {
        // Documents the contract fixed in Reader/Library: a cancelled child
        // must stay cancelled (rethrow), not complete normally via runCatching.
        val job = launch {
            try {
                Errors.suppressExceptCancel { throw CancellationException("stop") }
                fail("must rethrow")
            } catch (e: CancellationException) {
                throw e
            }
        }
        job.join()
        assertTrue(job.isCancelled)
    }
}
