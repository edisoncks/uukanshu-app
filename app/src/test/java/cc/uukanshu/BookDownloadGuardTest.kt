package cc.uukanshu

import cc.uukanshu.data.download.BookDownloadManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards for the app-scoped download state machine.
 *
 * A deleted book must never replay stale done/total, an old job must never
 * evict a new job, and progress from a dead job must not resurrect
 * downloading=true after cancel. Pure JVM, no Android needed.
 */
class BookDownloadGuardTest {
    @Test fun forgetDropsTerminalState() = runBlocking {
        val m = BookDownloadManager(downloadFn = { _, _ -> }, scope = this)
        m.start("b1")
        // Let the empty downloadFn complete.
        withTimeout(5000) {
            // Poll until terminal (downloading=false).
            var guard = 0
            while ((m.states.value["b1"]?.downloading == true) && guard++ < 500) delay(10)
        }
        m.forget("b1")
        assertNull(m.states.value["b1"])
    }

    @Test fun cancelKeepsDoneTotalButClearsDownloading() = runBlocking {
        val m = BookDownloadManager(
            downloadFn = { _, onProgress ->
                onProgress(3, 10)
                delay(5000)
            },
            scope = this,
        )
        m.start("b2")
        // Wait until progress published.
        withTimeout(5000) {
            var guard = 0
            while ((m.states.value["b2"]?.done ?: 0) == 0 && guard++ < 500) delay(10)
        }
        assertEquals(3, m.states.value["b2"]?.done)
        m.cancel("b2")
        assertEquals(false, m.states.value["b2"]?.downloading)
        assertEquals(3, m.states.value["b2"]?.done)
        assertEquals(10, m.states.value["b2"]?.total)
    }

    @Test fun terminalPublishAfterForgetNeverResurrectsState() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val m = BookDownloadManager(
            // Non-cancellable tail: forget() cannot stop the eventual publish,
            // only the publish's identity check can drop it.
            downloadFn = { _, _ ->
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
            },
            scope = this,
        )
        m.start("b4")
        withTimeout(5000) { entered.await() }
        m.forget("b4")
        assertNull(m.states.value["b4"])
        release.complete(Unit)
        delay(200)
        assertNull("terminal publish after forget must stay dropped", m.states.value["b4"])
    }

    @Test fun staleJobFinallyNeverEvictsReplacement() = runBlocking {
        val release1 = CompletableDeferred<Unit>()
        val release2 = CompletableDeferred<Unit>()
        val entered1 = CompletableDeferred<Unit>()
        var run = 0
        val m = BookDownloadManager(
            downloadFn = { _, _ ->
                run++
                if (run == 1) {
                    entered1.complete(Unit)
                    // Park past cancellation so the stale job reaches its
                    // cleanup late (it holds the queue slot until released).
                    withContext(NonCancellable) { release1.await() }
                } else {
                    release2.await()
                }
            },
            scope = this,
        )
        m.start("b5")
        withTimeout(5000) { entered1.await() }
        // Cancel the first job mid-park, then register its replacement (the
        // replacement queues behind the stale job's slot and stays registered).
        m.forget("b5")
        m.start("b5")
        assertTrue(m.isDownloading("b5"))
        // Let the stale job run its cleanup.
        release1.complete(Unit)
        delay(200)
        assertTrue(
            "stale job cleanup must not evict the replacement",
            m.isDownloading("b5"),
        )
        release2.complete(Unit)
        withTimeout(5000) {
            var guard = 0
            while (m.states.value["b5"]?.downloading != false && guard++ < 500) delay(10)
        }
        assertFalse(m.isDownloading("b5"))
    }

    @Test fun restartAfterForgetStartsFresh() = runBlocking {
        var runs = 0
        val m = BookDownloadManager(
            downloadFn = { _, onProgress ->
                runs++
                onProgress(runs, 5)
            },
            scope = this,
        )
        m.start("b3")
        withTimeout(5000) {
            var guard = 0
            while ((m.states.value["b3"]?.downloading != false || m.states.value["b3"]?.done == 0) && guard++ < 500) delay(10)
        }
        m.forget("b3")
        assertNull(m.states.value["b3"])
        m.start("b3")
        withTimeout(5000) {
            var guard = 0
            while (m.states.value["b3"] == null && guard++ < 500) delay(10)
        }
        // Second start re-creates state instead of replaying stale progress.
        assertFalse(m.states.value["b3"] == null)
    }
}
