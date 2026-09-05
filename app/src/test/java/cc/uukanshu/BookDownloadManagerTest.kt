package cc.uukanshu

import cc.uukanshu.data.download.BookDownloadManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class BookDownloadManagerTest {
    @Test fun secondDownloadWaitsForFirst() = runBlocking {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val aEntered = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val bEntered = CompletableDeferred<Unit>()
        val manager = BookDownloadManager(
            downloadFn = { id, _ ->
                if (id == "a") {
                    order.add("a-enter")
                    aEntered.complete(Unit)
                    releaseA.await()
                    order.add("a-exit")
                } else {
                    order.add("b-enter")
                    bEntered.complete(Unit)
                }
            },
            scope = this,
        )
        manager.start("a")
        withTimeout(5000) { aEntered.await() }
        manager.start("b")
        // Give B a chance to (incorrectly) enter if not serialized.
        delay(100)
        assertFalse("b must queue behind a, order=$order", order.contains("b-enter"))
        releaseA.complete(Unit)
        withTimeout(5000) { bEntered.await() }
        assertEquals(listOf("a-enter", "a-exit", "b-enter"), order)
    }

    @Test fun cancelledQueuedDownloadNeverRuns() = runBlocking {
        val aEntered = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val bRan = AtomicBoolean(false)
        val manager = BookDownloadManager(
            downloadFn = { id, _ ->
                if (id == "a") {
                    aEntered.complete(Unit)
                    releaseA.await()
                } else {
                    bRan.set(true)
                }
            },
            scope = this,
        )
        manager.start("a")
        withTimeout(5000) { aEntered.await() }
        manager.start("b")
        manager.cancel("b")
        releaseA.complete(Unit)
        // Let A finish and give B a chance to (incorrectly) run.
        delay(100)
        assertFalse("cancelled queued download must not run", bRan.get())
    }
}
