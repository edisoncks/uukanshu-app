package cc.uukanshu.data.updatecheck

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import cc.uukanshu.App
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Thin Worker shell: all logic in [UpdateChecker] (faked in JVM tests).
 * Uses App singletons directly — no custom WorkerFactory, no Hilt/Koin
 * (see ARCHITECTURE.md manual DI). Disabled flag = early success so boot
 * scheduling never needs a DataStore read.
 */
class BookUpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? App ?: return Result.success()
        try {
            val enabled = try {
                app.prefs.bgCheckEnabled.first()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                true
            }
            if (!enabled) return Result.success()
            val r = UpdateChecker.checkAll(app.repo, app.prefs)
            if (r.newBooks > 0) {
                try {
                    Notifier.show(applicationContext, r.newBooks, r.newChapters)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Denied permission / notification failure must not fail the run;
                    // badges are already in Room.
                }
            }
            return Result.success(
                workDataOf("checked" to r.checked, "newBooks" to r.newBooks, "newChapters" to r.newChapters),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Transport-wide failure: one exponential retry; per-book skips
            // never reach here (see BookRepo.checkAllUpdates).
            return Result.retry()
        }
    }
}
