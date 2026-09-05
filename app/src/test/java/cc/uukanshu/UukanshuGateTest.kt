package cc.uukanshu

import cc.uukanshu.data.net.UukanshuGate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class UukanshuGateTest {
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
