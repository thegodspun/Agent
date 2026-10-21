package io.github.thegodspun.sentinel.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.thegodspun.sentinel.Notifications
import io.github.thegodspun.sentinel.R
import io.github.thegodspun.sentinel.app
import io.github.thegodspun.sentinel.work.WakeWorker

/**
 * Keeps Sentinel's process alive and shows a persistent notification with a Talk button,
 * so the assistant is one tap away from the lock screen or notification shade. It also
 * watches the screen turning off and on to catch the moment the user wakes up.
 * Scheduled checks run through WorkManager and keep working even if this service is off.
 */
class AgentService : Service() {

    /** Notes when the screen goes dark and checks each unlock for "the user just woke up". */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> context.app.settings.lastScreenOff = System.currentTimeMillis()
                Intent.ACTION_USER_PRESENT -> WakeWorker.onUnlock(context)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // These broadcasts only reach receivers registered at runtime, which is why the service exists.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, Notifications.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_sentinel)
            .setContentTitle("Sentinel is keeping watch")
            .setContentText("Tap Talk to ask for anything")
            .setOngoing(true)
            .setContentIntent(Notifications.openAppIntent(this, requestCode = 1))
            .addAction(0, "Talk", Notifications.openAppIntent(this, voice = true, requestCode = 2))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, AgentService::class.java))
            } catch (e: Exception) {
                // Android blocks starting it from the background unless battery optimization
                // is off for Sentinel; it will start next time the app or phone starts.
                Log.w("AgentService", "Could not start always-on service", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentService::class.java))
        }
    }
}
