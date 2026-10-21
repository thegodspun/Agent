package io.github.thegodspun.sentinel

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifications {
    const val CHANNEL_SERVICE = "service"
    const val CHANNEL_REMINDERS = "reminders"
    const val CHANNEL_BRIEFING = "wake_briefing"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_SERVICE, "Always-on status", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Keeps Sentinel running with a Talk button"
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_REMINDERS, "Deadlines & alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Deadline reminders and urgent items found in your notifications"
                },
                NotificationChannel(CHANNEL_BRIEFING, "Wake-up briefing", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Pressing matters and a habit note, sent when you start your day"
                },
            )
        )
        // Replaced by the wake-up briefing channel, which pops up on screen.
        manager.deleteNotificationChannel("briefing")
    }

    /** Opens the app; with [voice] it starts listening right away. */
    fun openAppIntent(context: Context, voice: Boolean = false, requestCode: Int = 0): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_VOICE, voice),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun post(context: Context, channel: String, id: Int, title: String, text: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_sentinel)
            .setColor(ContextCompat.getColor(context, R.color.sentinel_blue))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent(context, requestCode = id))
            .setAutoCancel(true)
            .setPriority(
                if (channel == CHANNEL_SERVICE) NotificationCompat.PRIORITY_MIN else NotificationCompat.PRIORITY_HIGH
            )
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
