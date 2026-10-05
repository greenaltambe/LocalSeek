package com.augt.localseek.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import com.augt.localseek.MainActivity
import com.augt.localseek.R
import com.augt.localseek.tools.IndexNotificationMode
import java.util.UUID

/**
 * Notifications for background indexing. Wording is always neutral (the mascot lives inside the app only).
 * Everything is silent: LOW importance channels, no vibration, only-alert-once. If POST_NOTIFICATIONS was denied
 * nothing is shown and the in-app banner is the fallback.
 */
object IndexNotifier {

    const val CHANNEL_PROGRESS = "indexing_channel"
    const val CHANNEL_DONE = "indexing_done_channel"
    const val ID_PROGRESS = 1001
    const val ID_DONE = 1002

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, context.getString(R.string.notif_channel_indexing), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.notif_channel_indexing_desc)
                enableVibration(false)
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, context.getString(R.string.notif_channel_done), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.notif_channel_done_desc)
                enableVibration(false)
                setShowBadge(false)
            }
        )
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /**
     * The ongoing foreground-service notification. Determinate when [progress] has a total; [IndexNotificationMode.MINIMAL]
     * drops the numbers and the bar. The "Stop" action cancels the work; the periodic schedule still runs later.
     */
    fun progressNotification(context: Context, workId: UUID, progress: IndexProgress?, mode: IndexNotificationMode) =
        NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setContentTitle(context.getString(R.string.notif_indexing_title))
            .setSmallIcon(R.drawable.ic_stat_acorn)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp(context))
            .addAction(
                0, context.getString(R.string.notif_stop),
                WorkManager.getInstance(context).createCancelPendingIntent(workId)
            )
            .also { builder ->
                if (mode == IndexNotificationMode.MINIMAL || progress == null) {
                    builder.setContentText(context.getString(R.string.notif_indexing_minimal))
                    if (mode != IndexNotificationMode.MINIMAL) builder.setProgress(0, 0, true)
                } else {
                    val (done, total) = IndexProgressText.counts(progress)
                    builder.setContentText(
                        if (total != null) context.getString(R.string.notif_indexing_counts, done, total)
                        else context.getString(R.string.notif_indexing_so_far, done)
                    )
                    if (progress.isDeterminate) builder.setProgress(progress.total, progress.shownDone, false)
                    else builder.setProgress(0, 0, true)
                }
            }
            .build()

    /** "Index up to date": separate, low importance, auto-cancel, opens the app. Skipped without notification permission. */
    fun postDone(context: Context, indexedFiles: Int) {
        if (!canPost(context)) return
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setContentTitle(context.getString(R.string.notif_done_title))
            .setContentText(context.getString(R.string.notif_done_text, IndexProgressText.count(indexedFiles)))
            .setSmallIcon(R.drawable.ic_stat_acorn)
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp(context))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID_DONE, notification)
        } catch (_: SecurityException) {
            // permission revoked between the check and the call: the in-app banner is the fallback
        }
    }

    fun canPost(context: Context): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}
