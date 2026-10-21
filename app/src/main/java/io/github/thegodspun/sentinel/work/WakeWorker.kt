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
import java.time.ZoneId
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
        val darkSince = inputData.getLong(KEY_DARK_SINCE, 0)
        val zone = ZoneId.systemDefault()
        val hour = 3_600_000L
        if (WakePolicy.briefedThisWakeDay(settings.lastBriefingAt, System.currentTimeMillis(), zone)) {
            return@withLock Result.success()
        }

        val stillUsingPhone = applicationContext.getSystemService(PowerManager::class.java).isInteractive &&
            settings.lastScreenOff < unlockAt
        if (!stillUsingPhone) {
            // Maybe the screen just timed out. Remember this unlock so the next one can pick it up.
            settings.wakeCandidateAt = unlockAt
            settings.wakeCandidateDarkSince = darkSince
            return@withLock Result.success()
        }

        // Usage history can see through brief phone checks during the night; without it, use the raw gap.
        val minSleep = settings.minSleepHours * hour
        val sleep = UsageCollector.latestSleep(applicationContext, minSleep)
        val slept = if (sleep != null && sleep.end >= unlockAt - 15 * 60_000L) {
            WakePolicy.isNightSleep(sleep.start, sleep.end, zone)
        } else {
            unlockAt - darkSince >= minSleep && WakePolicy.isNightSleep(darkSince, unlockAt, zone)
        }
        if (!slept) return@withLock Result.success()

        try {
            Briefing.deliver(applicationContext, justWokeUp = true)
            settings.wakeCandidateAt = 0
            Result.success()
        } catch (e: Exception) {
            Log.w("WakeWorker", "Wake-up briefing failed", e)
            if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val KEY_UNLOCK_AT = "unlock_at"
        private const val KEY_DARK_SINCE = "dark_since"
        private const val NAME = "wake-briefing"

        /** Called by the always-on service on every unlock. Cheap unless the unlock could be a wake-up. */
        fun onUnlock(context: Context) {
            val settings = context.app.settings
            if (!settings.wakeBriefing || settings.apiKey.isBlank()) return
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            // An earlier unlock this morning that timed out before it was confirmed still counts.
            val candidate = settings.wakeCandidateAt.takeIf { now - it < WakePolicy.CANDIDATE_TTL_MILLIS && it > 0 }
            val darkSince = if (candidate != null) settings.wakeCandidateDarkSince else settings.lastScreenOff
            if (candidate == null && !WakePolicy.worthChecking(now, settings.lastScreenOff, settings.lastBriefingAt, zone)) return
            if (WakePolicy.briefedThisWakeDay(settings.lastBriefingAt, now, zone)) return

            val typicalWake = HabitProfile.typicalWakeMinutes(context.app.habits.data(), LocalDate.now())
            val delay = WakePolicy.confirmDelayMillis(UsageCollector.minutesOfDay(now), typicalWake)
            val request = OneTimeWorkRequestBuilder<WakeWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_UNLOCK_AT to now, KEY_DARK_SINCE to darkSince))
                .build()
            // The newest unlock wins: an earlier 3 a.m. check is replaced by the real morning one.
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
