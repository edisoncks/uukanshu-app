package cc.uukanshu.data.updatecheck

import android.util.Log

private const val TAG = "AppInit"

/**
 * Boot init for 追更: channel + daily schedule.
 * Each step guarded separately so a faulty NotificationManager can't
 * kill boot and can't skip the WorkManager schedule (badges work via
 * manual check even when scheduling fails). Why-only, no history.
 */
object AppInit {
    fun init(channel: () -> Unit, schedule: () -> Unit) {
        try {
            channel()
        } catch (e: Exception) {
            Log.w(TAG, "ensureChannel failed, badges still work via manual check", e)
        }
        try {
            schedule()
        } catch (e: Exception) {
            Log.w(TAG, "scheduleKeep failed, badges still work via manual check", e)
        }
    }
}
