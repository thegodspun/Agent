package io.github.thegodspun.sentinel.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.thegodspun.sentinel.Notifications
import io.github.thegodspun.sentinel.app
import io.github.thegodspun.sentinel.core.Conversation
import io.github.thegodspun.sentinel.core.Prompts
import io.github.thegodspun.sentinel.core.Reminders
import io.github.thegodspun.sentinel.core.coreTools
import io.github.thegodspun.sentinel.core.describeNow
import io.github.thegodspun.sentinel.habits.UsageCollector
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * The watch, run every hour and shortly after an important notification arrives:
 * 1. Updates the habit summaries from usage history (on the phone, no AI cost).
 * 2. Deadline reminders, worked out on the phone with no network or AI cost.
 * 3. Review of queued notifications by Claude, which adds any dates it finds to the tracker.
 * 4. The daily briefing, if the wake-up check didn't already send it.
 * 5. Once a day, learning about the user from their recent texts.
 * While the user is probably asleep, alerts wait for the wake-up briefing.
 */
class SentinelWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = sentinelLock.withLock {
        val app = applicationContext.app
        try {
            UsageCollector.refresh(applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read usage history", e)
        }
        if (Briefing.userProbablyAsleep(applicationContext)) return@withLock Result.success()
        sendReminders()
        if (app.settings.apiKey.isBlank()) return@withLock Result.success()
        try {
            reviewInbox()
            Briefing.deliverIfDue(applicationContext)
            ContextLearner.runIfDue(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Sentinel check failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    private fun sendReminders() {
        val tracker = applicationContext.app.tracker
        Reminders.due(tracker.all(), LocalDate.now()).forEach { reminder ->
            Notifications.post(
                applicationContext,
                Notifications.CHANNEL_REMINDERS,
                reminder.item.id.hashCode(),
                reminder.message,
                reminder.item.notes.ifBlank { "Open Sentinel for details." },
            )
            Reminders.markSent(tracker, reminder)
        }
    }

    private suspend fun reviewInbox() {
        val app = applicationContext.app
        val pending = app.inbox.all().take(40)
        if (pending.isEmpty()) return
        val conversation = Conversation(
            config = app.settings.agentConfig(effortOverride = "low"),
            systemPrompt = Prompts.background(app.settings.aboutMe, app.notes.all()),
            tools = coreTools(app.tracker, app.notes) { LocalDateTime.now() },
        )
        val reply = conversation.send(Prompts.triage(pending, describeNow(LocalDateTime.now())))
        app.inbox.drop(pending.size)
        if (!reply.trim().startsWith(Prompts.NOTHING)) {
            Notifications.post(applicationContext, Notifications.CHANNEL_REMINDERS, TRIAGE_ID, "Sentinel alert", reply)
        }
        // Items the review just added may already be close to due.
        sendReminders()
    }

    companion object {
        private const val TAG = "SentinelWorker"
        private const val PERIODIC = "sentinel-periodic"
        private const val SOON = "sentinel-soon"
        private const val TRIAGE_ID = 1001

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SentinelWorker>(1, TimeUnit.HOURS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Review new notifications in a couple of minutes, batching any that arrive meanwhile. */
        fun runSoon(context: Context) {
            val request = OneTimeWorkRequestBuilder<SentinelWorker>()
                .setInitialDelay(2, TimeUnit.MINUTES)
                .setConstraints(network)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.KEEP, request)
        }

        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SentinelWorker>().setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
