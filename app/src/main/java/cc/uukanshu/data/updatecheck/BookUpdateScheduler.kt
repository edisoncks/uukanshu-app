package cc.uukanshu.data.updatecheck

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Daily 追更 schedule: one unique periodic work, network-only constraint.
 * TOC fetches are ~10KB/book so metered is allowed; battery/storage gates
 * are deliberately absent (see plan Rev.3). Foreground library-open check
 * (6h throttle) covers Doze-deferred runs.
 */
object BookUpdateScheduler {
    const val UNIQUE = "book-update-check"
    const val MAX_BOOKS_PER_RUN = 20
    const val FOREGROUND_THROTTLE_MS = 6L * 60 * 60 * 1000

    fun constraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun periodicRequest() = PeriodicWorkRequestBuilder<BookUpdateWorker>(24, TimeUnit.HOURS)
        .setConstraints(constraints())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
        .addTag(UNIQUE)
        .build()

    /** Boot / app-start: KEEP so we never churn the OS schedule. No DataStore read. */
    fun scheduleKeep(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            UNIQUE, ExistingPeriodicWorkPolicy.KEEP, periodicRequest(),
        )
    }

    /** Settings toggle ON: replace schedule. OFF is cancel() instead. */
    fun scheduleUpdate(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, periodicRequest(),
        )
    }

    fun cancel(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE)
    }
}
