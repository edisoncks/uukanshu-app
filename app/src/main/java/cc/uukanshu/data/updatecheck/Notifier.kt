package cc.uukanshu.data.updatecheck

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import cc.uukanshu.MainActivity
import cc.uukanshu.R
import android.util.Log

/**
 * One summary notification for 追更, never per-book spam. Tap opens the app;
 * the 書架 badge is the source of truth (no deep-link Nav surgery in v1).
 * Denied permission = silent badges, never a crash.
 */
object Notifier {
    const val CHANNEL_ID = "book_updates"
    const val NOTIFICATION_ID = 1001

    fun formatSummary(newBooks: Int, newChapters: Int): String =
        "有 $newBooks 本書更新，共 $newChapters 章 · 點開查看"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "追更通知", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun show(ctx: Context, newBooks: Int, newChapters: Int) {
        if (newBooks <= 0) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(ctx)
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("uukanshu")
            .setContentText(formatSummary(newBooks, newChapters))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, notif)
        } catch (e: SecurityException) {
            Log.w("Notifier", "notify failed (perm revoked), badges already in Room", e)
        }
    }
}
