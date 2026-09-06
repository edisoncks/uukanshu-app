package cc.uukanshu

import cc.uukanshu.data.net.UukanshuGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class UukanshuGateTest {
    @Test fun queuedRequestsSerializeFIFO() = runTest {
        // Plain mutex: whoever queued first runs first. Interleaving comes
        // from bulk releasing the permit during crawlDelay/backoff.
        val gate = UukanshuGate()
        val order = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()
        val holder = async { gate.withPermit { release.await() } }
        runCurrent()
        val bulk = async {
            gate.withPermit { order += "bulk" }
        }
        runCurrent()
        val tap = async {
            gate.withPermit { order += "tap" }
        }
        runCurrent()
        release.complete(Unit)
        listOf(holder, bulk, tap).awaitAll()
        assertEquals(listOf("bulk", "tap"), order)
    }

    @Test fun bulkWaitIsCancellable() = runTest {
        // A cancelled waiter must never wedge the gate.
        val gate = UukanshuGate()
        val release = CompletableDeferred<Unit>()
        val holder = async { gate.withPermit { release.await() } }
        runCurrent()
        val bulk = async {
            gate.withPermit { error("must not run") }
        }
        runCurrent()
        bulk.cancel()
        release.complete(Unit)
        holder.join()
        bulk.join()
    }

    @Test fun permitsSerializeConcurrentRequests() = runBlocking {
        val gate = UukanshuGate()
        val active = AtomicInteger(0)
        val max = AtomicInteger(0)
        (1..10).map {
            async {
                gate.withPermit {
                    val cur = active.incrementAndGet()
                    max.updateAndGet { m -> maxOf(m, cur) }
                    delay(10)
                    active.decrementAndGet()
                }
            }
        }.awaitAll()
        assertEquals(1, max.get())
        assertEquals(0, active.get())
    }
}
