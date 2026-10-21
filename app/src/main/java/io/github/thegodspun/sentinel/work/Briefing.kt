package io.github.thegodspun.sentinel.work

import android.content.Context
import android.os.PowerManager
import io.github.thegodspun.sentinel.Notifications
import io.github.thegodspun.sentinel.app
import io.github.thegodspun.sentinel.core.Conversation
import io.github.thegodspun.sentinel.core.HabitProfile
import io.github.thegodspun.sentinel.core.Prompts
import io.github.thegodspun.sentinel.core.coreTools
import io.github.thegodspun.sentinel.core.describeNow
import io.github.thegodspun.sentinel.core.habitTools
import io.github.thegodspun.sentinel.habits.UsageCollector
import io.github.thegodspun.sentinel.tools.readCalendar
import kotlinx.coroutines.sync.Mutex
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Shared by the hourly check and the wake-up check so they never brief or review twice. */
internal val sentinelLock = Mutex()

object Briefing {
    private const val BRIEFING_ID = 1002
    private const val HOUR = 3_600_000L

    /**
     * Writes and posts the briefing, folding in notifications that arrived since the last
     * one. Call while holding [sentinelLock].
     */
    suspend fun deliver(context: Context, justWokeUp: Boolean) {
        val app = context.app
        val settings = app.settings
        if (settings.apiKey.isBlank()) return
        UsageCollector.refresh(context)

        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val pending = app.inbox.all().take(40)
        val startOfDay = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val calendar = readCalendar(context, startOfDay, startOfDay + 24 * HOUR, max = 20)
        val habits = app.habits.data()
        val weekly = settings.habitLearning && habits.days.size >= 5 &&
            (settings.lastWeeklyReview.isBlank() ||
                ChronoUnit.DAYS.between(LocalDate.parse(settings.lastWeeklyReview), today) >= 7)

        val conversation = Conversation(
            config = settings.agentConfig(effortOverride = "medium"),
            systemPrompt = Prompts.background(settings.aboutMe, app.notes.all()),
            tools = coreTools(app.tracker, app.notes) { LocalDateTime.now() } +
                habitTools(app.habits) { LocalDateTime.now() },
        )
        val text = conversation.send(
            Prompts.briefing(
                now = describeNow(now),
                justWokeUp = justWokeUp,
                calendar = calendar,
                notifications = pending,
                habitProfile = if (settings.habitLearning) HabitProfile.describe(habits, today) else "(habit learning is off)",
                weeklyReview = weekly,
            )
        )

        app.inbox.drop(pending.size)
        settings.lastBriefingAt = System.currentTimeMillis()
        settings.lastBriefing = text
        if (weekly) settings.lastWeeklyReview = today.toString()

        val title = when (now.hour) {
            in 3..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            else -> "Welcome back"
        }
        Notifications.post(context, Notifications.CHANNEL_BRIEFING, BRIEFING_ID, title, text)
    }

    /** True when the screen has been dark for an hour at night, so alerts should wait for the briefing. */
    fun userProbablyAsleep(context: Context): Boolean {
        val settings = context.app.settings
        val screenOn = context.getSystemService(PowerManager::class.java).isInteractive
        if (screenOn) return false
        val darkFor = System.currentTimeMillis() - settings.lastScreenOff
        val hour = LocalDateTime.now().hour
        val nightHours = hour >= 22 || hour < 9
        return nightHours && (settings.lastScreenOff == 0L || darkFor >= HOUR)
    }

    /**
     * Used by the hourly check when no wake-up was caught as it happened (for example
     * because the always-on service was off): brief if usage history shows the user got up
     * since the last briefing, or once it's past the fallback hour.
     */
    suspend fun deliverIfDue(context: Context) {
        val settings = context.app.settings
        if (settings.briefingHour < 0 && !settings.wakeBriefing) return
        val nowMillis = System.currentTimeMillis()
        val now = LocalDateTime.now()
        val sinceLast = nowMillis - settings.lastBriefingAt

        if (!settings.wakeBriefing) {
            val briefedToday = settings.lastBriefingAt > 0 &&
                java.time.Instant.ofEpochMilli(settings.lastBriefingAt).atZone(ZoneId.systemDefault()).toLocalDate() == now.toLocalDate()
            if (!briefedToday && now.hour >= settings.briefingHour) deliver(context, justWokeUp = false)
            return
        }

        if (sinceLast < 16 * HOUR || userProbablyAsleep(context)) return
        val minSleep = settings.minSleepHours * HOUR
        val woke = UsageCollector.latestSleep(context, minSleep)
        if (woke != null && woke.end > settings.lastBriefingAt && nowMillis - woke.end < 3 * HOUR) {
            deliver(context, justWokeUp = true)
            return
        }
        // Fallback: three hours after the usual wake time, or the chosen hour until that's learned.
        val typicalWake = HabitProfile.typicalWakeMinutes(context.app.habits.data(), now.toLocalDate())
        val fallbackMinutes = typicalWake?.let { minOf(it + 180, 23 * 60) }
            ?: settings.briefingHour.takeIf { it >= 0 }?.times(60)
            ?: return
        if (now.hour * 60 + now.minute >= fallbackMinutes) deliver(context, justWokeUp = false)
    }
}
