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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * The watch, run every hour and shortly after an important notification arrives:
 * 1. Deadline reminders, worked out on the phone with no network or AI cost.
 * 2. Review of queued notifications by Claude, which adds any dates it finds to the tracker.
 * 3. The morning briefing, once a day at the chosen hour.
 */
class SentinelWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = lock.withLock {
        val app = applicationContext.app
        sendReminders()
        if (app.settings.apiKey.isBlank()) return@withLock Result.success()
        try {
            reviewInbox()
            maybeBrief()
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
        val reply = backgroundConversation(effort = "low")
            .send(Prompts.triage(pending, describeNow(LocalDateTime.now())))
        app.inbox.drop(pending.size)
        if (!reply.trim().startsWith(Prompts.NOTHING)) {
            Notifications.post(applicationContext, Notifications.CHANNEL_REMINDERS, TRIAGE_ID, "Sentinel alert", reply)
        }
        // Items the review just added may already be close to due.
        sendReminders()
    }

    private suspend fun maybeBrief() {
        val settings = applicationContext.app.settings
        val now = LocalDateTime.now()
        val today = now.toLocalDate().toString()
        if (settings.briefingHour < 0 || now.hour < settings.briefingHour || settings.lastBriefingDate == today) return
        val briefing = backgroundConversation(effort = "medium").send(Prompts.briefing(describeNow(now)))
        settings.lastBriefingDate = today
        settings.lastBriefing = briefing
        Notifications.post(applicationContext, Notifications.CHANNEL_BRIEFING, BRIEFING_ID, "Your briefing", briefing)
    }

    /** Background runs only get the tracker tools: no calls, texts or screen control. */
    private fun backgroundConversation(effort: String): Conversation {
        val app = applicationContext.app
        return Conversation(
            config = app.settings.agentConfig(effortOverride = effort),
            systemPrompt = Prompts.background(app.settings.aboutMe, app.notes.all()),
            tools = coreTools(app.tracker, app.notes) { LocalDateTime.now() },
        )
    }

    companion object {
        private const val TAG = "SentinelWorker"
        private const val PERIODIC = "sentinel-periodic"
        private const val SOON = "sentinel-soon"
        private const val TRIAGE_ID = 1001
        private const val BRIEFING_ID = 1002

        /** The hourly run and a notification-triggered run must not review the same inbox twice. */
        private val lock = Mutex()

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
