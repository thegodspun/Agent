package io.github.thegodspun.sentinel.work

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.thegodspun.sentinel.app
import io.github.thegodspun.sentinel.core.HabitProfile
import io.github.thegodspun.sentinel.core.WakePolicy
import io.github.thegodspun.sentinel.habits.UsageCollector
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Runs shortly after an unlock that might be the user waking up. It confirms they are still
 * using the phone (not just checking the time at 3 a.m.) and that they really slept, then
 * sends the wake-up briefing.
 */
class WakeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = sentinelLock.withLock {
        val app = applicationContext.app
        val settings = app.settings
        val unlockAt = inputData.getLong(KEY_UNLOCK_AT, 0)
        val gap = inputData.getLong(KEY_GAP, 0)
        val hour = 3_600_000L

        val stillUsingPhone = applicationContext.getSystemService(PowerManager::class.java).isInteractive &&
            settings.lastScreenOff < unlockAt
        if (!stillUsingPhone) return@withLock Result.success()
        if (System.currentTimeMillis() - settings.lastBriefingAt < WakePolicy.MIN_HOURS_BETWEEN_BRIEFINGS * hour) {
            return@withLock Result.success()
        }

        // Usage history can see through brief phone checks during the night; without it, use the raw gap.
        val minSleep = settings.minSleepHours * hour
        val slept = if (UsageCollector.hasAccess(applicationContext)) {
            val sleep = UsageCollector.latestSleep(applicationContext, minSleep)
            sleep != null && sleep.end >= unlockAt - 15 * 60_000L || gap >= minSleep
        } else {
            gap >= minSleep
        }
        if (!slept) return@withLock Result.success()

        try {
            Briefing.deliver(applicationContext, justWokeUp = true)
            Result.success()
        } catch (e: Exception) {
            Log.w("WakeWorker", "Wake-up briefing failed", e)
            if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val KEY_UNLOCK_AT = "unlock_at"
        private const val KEY_GAP = "gap"
        private const val NAME = "wake-briefing"

        /** Called by the always-on service on every unlock. Cheap unless the unlock could be a wake-up. */
        fun onUnlock(context: Context) {
            val settings = context.app.settings
            if (!settings.wakeBriefing || settings.apiKey.isBlank()) return
            val now = System.currentTimeMillis()
            if (!WakePolicy.worthChecking(now, settings.lastScreenOff, settings.lastBriefingAt)) return

            val typicalWake = HabitProfile.typicalWakeMinutes(context.app.habits.data(), LocalDate.now())
            val delay = WakePolicy.confirmDelayMillis(UsageCollector.minutesOfDay(now), typicalWake)
            val request = OneTimeWorkRequestBuilder<WakeWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_UNLOCK_AT to now, KEY_GAP to now - settings.lastScreenOff))
                .build()
            // The newest unlock wins: an earlier 3 a.m. check is replaced by the real morning one.
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
