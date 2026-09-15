package cc.uukanshu.data.updatecheck

import cc.uukanshu.data.repo.BookRepo
import cc.uukanshu.di.PrefsApi
import cc.uukanshu.di.RepoApi
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

private const val TAG = "UpdateChecker"

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
    data class Result(
        val checked: Int,
        val newBooks: Int,
        val newChapters: Int,
        val failed: Boolean = false,
        // Whole-run cause (DB down, init-query throw). Preserved so the
        // footer can show the real error instead of a generic string.
        val cause: Exception? = null,
    )

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
            return Result(0, 0, 0, failed = true, cause = e)
        }
        if (r.failed) return Result(r.checked, r.newBooks, r.newChapters, failed = true)
        try {
            prefs.setLastBookCheck(System.currentTimeMillis())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "setLastBookCheck failed, badges already in Room", e)
        }
        return Result(r.checked, r.newBooks, r.newChapters, failed = false)
    }

    /**
     * Single gate for every *automatic* 追更 path: the daily Worker and the
     * library-open foreground fallback. Manual 檢查更新 deliberately bypasses
     * this (see LibraryViewModel.checkUpdates) because the switch's copy
     * promises manual checks always work.
     *
     * Fails closed and logs: a read failure must not put the app on the
     * network against a switch the user turned off, and a silent skip would
     * leave the decision path invisible.
     */
    suspend fun isAutoBookCheckEnabled(prefs: PrefsApi): Boolean = try {
        prefs.autoBookCheckEnabled.first()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "autoBookCheckEnabled read failed; skipping automatic check", e)
        false
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
