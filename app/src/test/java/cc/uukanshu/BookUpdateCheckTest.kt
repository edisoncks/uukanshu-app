package cc.uukanshu

import cc.uukanshu.data.db.BookEntity
import cc.uukanshu.data.repo.BookRepo
import cc.uukanshu.data.repo.ShelfOrder
import cc.uukanshu.data.updatecheck.Notifier
import cc.uukanshu.data.updatecheck.UpdateChecker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 追更 contracts: preserve carries badge state, throttle/format are pure. */
class BookUpdateCheckTest {
    @Test fun preserveCarriesSeenState() {
        val existing = BookEntity("1", "T", seenTotal = 10, newCount = 3, lastCheckedAt = 99L, updatedAt = 5L)
        val fresh = BookEntity("1", "T2")
        val out = ShelfOrder.preserve(existing, fresh, 1000L)
        assertEquals(5L, out.updatedAt)
        assertEquals(10, out.seenTotal)
        assertEquals(3, out.newCount)
        assertEquals(99L, out.lastCheckedAt)
        assertEquals("T2", out.title)
    }

    @Test fun assembleCarriesBadge() {
        val rows = listOf(BookEntity("a", "A", newCount = 4))
        val stats = listOf(cc.uukanshu.data.db.ChapterStats("a", total = 12, cached = 8, bytes = 10L))
        val out = ShelfOrder.assemble(rows, stats, emptyMap())
        assertEquals(1, out.size)
        assertEquals(4, out[0].newChapters)
    }

    @Test fun foregroundThrottleIs6h() {
        val now = 1_700_000_000_000L
        assertTrue(UpdateChecker.shouldForegroundCheck(0L, now))
        assertFalse(UpdateChecker.shouldForegroundCheck(now - 1000L, now))
        assertTrue(UpdateChecker.shouldForegroundCheck(now - 6L * 60 * 60 * 1000, now))
        assertFalse(UpdateChecker.shouldForegroundCheck(now - 6L * 60 * 60 * 1000 + 1, now))
    }

    @Test fun formatLastCheckIsTraditional() {
        val now = 1_700_000_000_000L
        assertEquals("從未檢查", UpdateChecker.formatLastCheck(0L, now))
        assertEquals("剛剛", UpdateChecker.formatLastCheck(now - 1000L, now))
        assertEquals("5分鐘前", UpdateChecker.formatLastCheck(now - 5 * 60_000L, now))
        assertEquals("3小時前", UpdateChecker.formatLastCheck(now - 3 * 60 * 60_000L, now))
        assertEquals("昨天", UpdateChecker.formatLastCheck(now - 25 * 60 * 60_000L, now))
        assertEquals("2天前", UpdateChecker.formatLastCheck(now - 49 * 60 * 60_000L, now))
    }

    @Test fun notifierSummaryIsSingleLine() {
        assertEquals("有 2 本書更新，共 15 章 · 點開查看", Notifier.formatSummary(2, 15))
    }

    @Test fun checkerWritesLastCheckEvenOnEmpty() = runBlocking {
        val repo = MutableFakeRepo()
        var stamped: Long? = null
        val prefsSpy = object : cc.uukanshu.di.PrefsApi by MutableFakePrefs() {
            override suspend fun setLastBookCheck(now: Long) {
                stamped = now
            }
        }
        val r = UpdateChecker.checkAll(repo, prefsSpy, limit = 5)
        assertEquals(0, r.checked)
        assertEquals(0, r.newBooks)
        assertEquals(false, r.failed)
        assertTrue((stamped ?: 0L) > 0L)
    }

    @Test fun checkerInitThrowIsFailedWithoutStamp() = runBlocking {
        val throwing = object : cc.uukanshu.di.RepoApi by MutableFakeRepo() {
            override suspend fun checkAllUpdates(limit: Int): BookRepo.CheckAllResult =
                throw java.io.IOException("db down")
        }
        var stamped: Long? = null
        val prefsSpy = object : cc.uukanshu.di.PrefsApi by MutableFakePrefs() {
            override suspend fun setLastBookCheck(now: Long) {
                stamped = now
            }
        }
        val r = UpdateChecker.checkAll(throwing, prefsSpy)
        assertEquals(true, r.failed)
        assertEquals(null, stamped)
    }

    @Test fun checkerPassesThroughRepoFailedWithoutStamp() = runBlocking {
        val failed = object : cc.uukanshu.di.RepoApi by MutableFakeRepo() {
            override suspend fun checkAllUpdates(limit: Int) =
                BookRepo.CheckAllResult(0, 0, 0, failed = true)
        }
        var stamped: Long? = null
        val prefsSpy = object : cc.uukanshu.di.PrefsApi by MutableFakePrefs() {
            override suspend fun setLastBookCheck(now: Long) {
                stamped = now
            }
        }
        val r = UpdateChecker.checkAll(failed, prefsSpy)
        assertEquals(true, r.failed)
        assertEquals(null, stamped)
    }

    @Test fun checkerAggregatesRepoResult() = runBlocking {
        val repo = MutableFakeRepo()
        val counting = object : cc.uukanshu.di.RepoApi by repo {
            override suspend fun checkAllUpdates(limit: Int) =
                BookRepo.CheckAllResult(checked = 2, newBooks = 1, newChapters = 7, perBook = mapOf("a" to 7))
        }
        val r = UpdateChecker.checkAll(counting, MutableFakePrefs())
        assertEquals(2, r.checked)
        assertEquals(1, r.newBooks)
        assertEquals(7, r.newChapters)
    }

    @Test fun visibleIdsSkipsTocOnlyOldestFirstCaps() {
        val ordered = listOf(
            BookEntity("invisible-old", "X"),
            BookEntity("a", "A"),
            BookEntity("b", "B"),
            BookEntity("empty", "E"),
        )
        val stats = listOf(
            cc.uukanshu.data.db.ChapterStats("a", total = 10, cached = 5, bytes = 1L),
            cc.uukanshu.data.db.ChapterStats("b", total = 10, cached = 10, bytes = 1L),
            cc.uukanshu.data.db.ChapterStats("empty", total = 5, cached = 0, bytes = 0L),
        )
        assertEquals(listOf("a", "b"), BookRepo.visibleIds(ordered, stats, 20))
        assertEquals(listOf("a"), BookRepo.visibleIds(ordered, stats, 1))
        assertEquals(emptyList<String>(), BookRepo.visibleIds(listOf(BookEntity("x", "X")), emptyList(), 20))
    }

    @Test fun visibleIdsZeroIsEmptyNegativeFailsFast() {
        val ordered = listOf(BookEntity("a", "A"))
        val stats = listOf(cc.uukanshu.data.db.ChapterStats("a", total = 10, cached = 5, bytes = 1L))
        assertEquals(emptyList<String>(), BookRepo.visibleIds(ordered, stats, 0))
        var thrown = false
        try {
            BookRepo.visibleIds(ordered, stats, -1)
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }
}
