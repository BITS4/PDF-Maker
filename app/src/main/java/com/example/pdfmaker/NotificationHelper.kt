package com.tajapps.pdfmaker

import com.tajapps.pdfmaker.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object NotificationHelper {

    private const val CHANNEL_ID   = "pdfmaker_progress"
    private const val CHANNEL_NAME = "PDF Operations"
    private const val NOTIF_ID     = 1001

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW   // silent — no sound
            ).apply { description = "Progress updates for PDF operations" }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    /** Show or update a progress notification. Call repeatedly with increasing [progress]. */
    fun showProgress(
        context    : Context,
        title      : String,
        message    : String,
        progress   : Int,        // 0–100, or -1 for indeterminate
        maxProgress: Int = 100
    ) {
        val tapIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(tapIntent)
            .setAutoCancel(false)

        if (progress < 0) {
            builder.setProgress(0, 0, true)   // indeterminate
        } else {
            builder.setProgress(maxProgress, progress, false)
        }

        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID, builder.build())
        } catch (_: SecurityException) { /* POST_NOTIFICATIONS not granted on API 33+ */ }
    }

    /** Replace progress notification with a "Done" one that auto-dismisses. */
    fun showDone(context: Context, title: String, message: String) {
        val tapIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(tapIntent)

        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID, builder.build())
        } catch (_: SecurityException) { }
    }

    fun dismiss(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIF_ID)
    }
}
