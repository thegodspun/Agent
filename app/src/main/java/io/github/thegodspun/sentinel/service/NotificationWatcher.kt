package io.github.thegodspun.sentinel.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.thegodspun.sentinel.app
import io.github.thegodspun.sentinel.core.CapturedNotification
import io.github.thegodspun.sentinel.core.MESSAGING_APPS
import io.github.thegodspun.sentinel.core.NotificationFilter
import io.github.thegodspun.sentinel.core.TextFilter
import io.github.thegodspun.sentinel.work.SentinelWorker
import java.time.LocalDateTime

/**
 * Watches incoming notifications (email, texts, patient portals, pharmacy, bank) for
 * deadlines and benefit news. Matching ones are queued for the sentinel, which asks Claude
 * to pull out dates and flag anything urgent. Messages from chat apps are also kept briefly
 * so Sentinel can learn about the user's life from them.
 */
class NotificationWatcher : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.isOngoing) return
        val extras = sbn.notification.extras
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        val settings = app.settings
        val messagingApp = MESSAGING_APPS[sbn.packageName]
        if (messagingApp != null && settings.learnFromTexts && !TextFilter.isSecretCode(text)) {
            // RCS and chat-app messages can't be read from history, so learn from them as they arrive.
            app.messages.add(
                CapturedNotification(
                    app = messagingApp,
                    title = title.take(100),
                    text = text.take(1000),
                    postedAt = LocalDateTime.now().withNano(0).toString(),
                )
            )
        }
        if (!settings.reviewAllNotifications && !NotificationFilter.isRelevant(title, text)) return

        app.inbox.add(
            CapturedNotification(
                app = appLabel(sbn.packageName),
                title = title.take(200),
                text = text.take(1500),
                postedAt = LocalDateTime.now().withNano(0).toString(),
            )
        )
        SentinelWorker.runSoon(this)
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    companion object {
        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            val me = ComponentName(context, NotificationWatcher::class.java).flattenToString()
            return flat.split(':').any { it == me }
        }
    }
}
