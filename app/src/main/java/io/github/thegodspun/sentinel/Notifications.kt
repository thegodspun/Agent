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
import io.github.thegodspun.sentinel.service.ApprovalCenter
import io.github.thegodspun.sentinel.service.ApprovalReceiver

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

    /** A pop-up asking to approve an action, with Allow / Not now buttons. */
    fun postApproval(context: Context, id: Int, question: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        fun answer(allow: Boolean, requestCode: Int) = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, ApprovalReceiver::class.java).putExtra(ApprovalCenter.EXTRA_ALLOW, allow),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_sentinel)
            .setColor(ContextCompat.getColor(context, R.color.sentinel_blue))
            .setContentTitle("Sentinel needs your OK")
            .setContentText(question)
            .setStyle(NotificationCompat.BigTextStyle().bigText(question))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setTimeoutAfter(120_000)
            .addAction(0, "Allow", answer(true, 41))
            .addAction(0, "Not now", answer(false, 42))
            .setDeleteIntent(answer(false, 43))
            .setContentIntent(openAppIntent(context, requestCode = 44))
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    /**
     * Android doesn't let a background app open other apps. This posts a notification that
     * opens [intent] when tapped, which Android does allow.
     */
    fun postTapToFinish(context: Context, what: String, intent: Intent) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val id = 2000 + (what.hashCode() and 0xfff)
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_sentinel)
            .setColor(ContextCompat.getColor(context, R.color.sentinel_blue))
            .setContentTitle("Tap to finish")
            .setContentText(what)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

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
