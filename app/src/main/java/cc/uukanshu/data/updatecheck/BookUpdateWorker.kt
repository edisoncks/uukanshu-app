package cc.uukanshu.data.updatecheck

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import cc.uukanshu.App
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

private const val TAG = "BookUpdateWorker"

/**
 * Thin Worker shell: all logic in [UpdateChecker] (faked in JVM tests).
 * Uses App singletons directly — no custom WorkerFactory, no Hilt/Koin
 * (see ARCHITECTURE.md manual DI). Disabled flag = early success so boot
 * scheduling never needs a DataStore read.
 */
class BookUpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? App ?: run {
            Log.e(TAG, "application is not App, failing loud")
            return Result.failure()
        }
        try {
            val enabled = try {
                app.prefs.bgCheckEnabled.first()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "bgCheckEnabled read failed, defaulting to true", e)
                true
            }
            if (!enabled) return Result.success()
            val r = UpdateChecker.checkAll(app.repo, app.prefs)
            // Whole-run init failure (DB down): transient → retry with backoff.
            // Checker never throws except cancel, so this is the only retry path.
            if (r.failed) return Result.retry()
            if (r.newBooks > 0) {
                try {
                    Notifier.show(applicationContext, r.newBooks, r.newChapters)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "notification failed, badges already in Room", e)
                }
            }
            return Result.success(
                workDataOf("checked" to r.checked, "newBooks" to r.newBooks, "newChapters" to r.newChapters),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Programming bug: fail loud, never infinite-retry on our own NPE.
            // No IOException branch: UpdateChecker converts transport/init
            // failures to Result(failed) so this is unreachable except for
            // unexpected throws from Notifier/workDataOf.
            return Result.failure()
        }
    }
}
