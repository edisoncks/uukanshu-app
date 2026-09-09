package cc.uukanshu.data.updatecheck

import cc.uukanshu.data.repo.BookRepo
import cc.uukanshu.di.PrefsApi
import cc.uukanshu.di.RepoApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Thin orchestration over [RepoApi.checkAllUpdates]: oldest-first, bounded
 * (see BookRepo, limit 20/run) so a 100-book shelf converges over runs
 * instead of blowing the 10-min Worker limit. Pure + JVM-tested via fakes.
 *
 * Single write path for [PrefsApi.lastBookCheck]: stamped on success only,
 * never on whole-run failure (else 6h throttle would hide retry).
 * Per-book failures are skips inside the repo (success, failed=false).
 */
object UpdateChecker {
    data class Result(val checked: Int, val newBooks: Int, val newChapters: Int, val failed: Boolean = false)

    suspend fun checkAll(
        repo: RepoApi,
        prefs: PrefsApi,
        limit: Int = BookUpdateScheduler.MAX_BOOKS_PER_RUN,
    ): Result {
        val r: BookRepo.CheckAllResult = try {
            repo.checkAllUpdates(limit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result(0, 0, 0, failed = true)
        }
        if (r.failed) return Result(r.checked, r.newBooks, r.newChapters, failed = true)
        try {
            prefs.setLastBookCheck(System.currentTimeMillis())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Prefs write failure must not fail the run; badges are in Room.
        }
        return Result(r.checked, r.newBooks, r.newChapters, failed = false)
    }

    /** Foreground throttle: library-open auto-check at most once per interval. */
    fun shouldForegroundCheck(lastCheck: Long, now: Long = System.currentTimeMillis()): Boolean =
        now - lastCheck >= BookUpdateScheduler.FOREGROUND_THROTTLE_MS

    fun formatLastCheck(lastCheck: Long, now: Long = System.currentTimeMillis()): String {
        if (lastCheck <= 0L) return "從未檢查"
        val d = now - lastCheck
        if (d < 0) return "剛剛"
        val m = d / 60_000
        if (m < 1) return "剛剛"
        if (m < 60) return "${m}分鐘前"
        val h = m / 60
        if (h < 24) return "${h}小時前"
        val days = h / 24
        return if (days == 1L) "昨天" else "${days}天前"
    }
}
