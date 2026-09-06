package cc.uukanshu

import cc.uukanshu.data.download.BookDownloadManager
import cc.uukanshu.data.parse.Parser
import cc.uukanshu.data.repo.DownloadPlan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DownloadRobustnessTest {
    private fun ref(pos: Int, pageId: Long) =
        Parser.ChapterRef(pos, pageId, "t-$pos", "https://uukanshu.cc/book/1/$pageId.html")

    @Test fun missingFiltersCachedIdsInOrder() {
        val chapters = listOf(ref(1, 101L), ref(2, 102L), ref(3, 103L))
        val missing = DownloadPlan.missing(chapters, setOf(101L, 103L))
        assertEquals(listOf(102L), missing.map { it.pageId })
    }

    @Test fun isCompleteOnlyWhenAllCached() {
        val chapters = listOf(ref(1, 101L), ref(2, 102L))
        assertTrue(DownloadPlan.isComplete(chapters, setOf(101L, 102L)))
        assertFalse(DownloadPlan.isComplete(chapters, setOf(101L)))
        assertFalse(DownloadPlan.isComplete(emptyList(), emptySet()))
    }

    @Test fun concurrentStartSameIdRunsOnce() = runBlocking {
        val runs = AtomicInteger(0)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val manager = BookDownloadManager(
            downloadFn = { _, _ ->
                runs.incrementAndGet()
                entered.complete(Unit)
                release.await()
            },
            scope = this,
        )
        // 20 concurrent starters race the old check-then-act window.
        repeat(20) { launch { manager.start("same") } }
        withTimeout(5000) { entered.await() }
        // Give losers a chance to (incorrectly) launch a second job.
        delay(200)
        release.complete(Unit)
        // Let the winner finish.
        delay(200)
        assertEquals("concurrent start(id) must run exactly once, runs=${runs.get()}", 1, runs.get())
    }
}
