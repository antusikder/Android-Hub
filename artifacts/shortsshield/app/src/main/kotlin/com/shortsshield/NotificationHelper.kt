package com.shortsshield

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService

object NotificationHelper {

    private const val CHANNEL_ID   = "shield_status"
    private const val CHANNEL_NAME = "Shield Status"
    private const val NOTIF_ID     = 1001

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW  // silent, no sound/vibration
        ).apply {
            description = "Shows when ShortsShield is actively blocking Shorts"
            setShowBadge(false)
        }
        context.getSystemService<NotificationManager>()?.createNotificationChannel(channel)
    }

    fun buildNotification(context: Context, blocked: Long): Notification {
        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield_notif)
            .setContentTitle("ShortsShield Active")
            .setContentText("$blocked Shorts blocked today")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun update(context: Context, blocked: Long) {
        if (!ShieldPrefs.get(context).showNotification) return
        context.getSystemService<NotificationManager>()
            ?.notify(NOTIF_ID, buildNotification(context, blocked))
    }

    fun cancel(context: Context) {
        context.getSystemService<NotificationManager>()?.cancel(NOTIF_ID)
    }

    const val NOTIFICATION_ID = NOTIF_ID
}
