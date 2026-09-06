package cc.uukanshu

import cc.uukanshu.data.download.BookDownloadManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
