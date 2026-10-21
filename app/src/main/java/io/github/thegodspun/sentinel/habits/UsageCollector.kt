package io.github.thegodspun.sentinel.habits

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import io.github.thegodspun.sentinel.app
import io.github.thegodspun.sentinel.core.SleepBlock
import io.github.thegodspun.sentinel.core.UsageAnalyzer
import io.github.thegodspun.sentinel.core.UsageEvent
import io.github.thegodspun.sentinel.core.UsageKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reads screen on/off, unlock and app-switch events from Android's usage history and turns
 * them into daily habit summaries. Needs "Usage access", which the user grants in Settings.
 * The raw events never leave the phone; only the daily summaries go to Claude.
 */
object UsageCollector {
    private const val DAY = 86_400_000L

    fun hasAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    /** Recomputes the last [days] daily summaries and saves them. Android keeps detailed events for about a week. */
    fun refresh(context: Context, days: Int = 8) {
        val app = context.app
        if (!app.settings.habitLearning || !hasAccess(context)) return
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val from = today.minusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli() - DAY / 4
        val events = events(context, from, now)
        if (events.isEmpty()) return
        val ignored = ignoredPackages(context)
        val labels = mutableMapOf<String, String>()
        val label = { pkg: String -> labels.getOrPut(pkg) { appLabel(context, pkg) } }
        val summaries = (0 until days).map { offset ->
            UsageAnalyzer.summarize(events, today.minusDays(offset.toLong()), zone, now, label, ignored)
        }
        app.habits.upsertDays(summaries)
    }

    /** The sleep that ended most recently, from the last day of usage history. */
    fun latestSleep(context: Context, minAsleepMillis: Long): SleepBlock? {
        if (!hasAccess(context)) return null
        val now = System.currentTimeMillis()
        return UsageAnalyzer.latestSleep(events(context, now - DAY - DAY / 4, now), now, minAsleepMillis)
    }

    /** Screen-on time since [from], from usage history. */
    fun screenOnMillisSince(context: Context, from: Long): Long {
        if (!hasAccess(context)) return 0
        val now = System.currentTimeMillis()
        return UsageAnalyzer.screenOnMillis(events(context, from - DAY / 4, now), from, now)
    }

    fun settingsIntent() = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)

    private fun events(context: Context, from: Long, to: Long): List<UsageEvent> {
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val raw = usm.queryEvents(from, to) ?: return emptyList()
        val out = ArrayList<UsageEvent>()
        val e = UsageEvents.Event()
        while (raw.hasNextEvent()) {
            raw.getNextEvent(e)
            val kind = when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> UsageKind.APP_FOREGROUND
                UsageEvents.Event.ACTIVITY_PAUSED -> UsageKind.APP_BACKGROUND
                UsageEvents.Event.SCREEN_INTERACTIVE -> UsageKind.SCREEN_ON
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> UsageKind.SCREEN_OFF
                UsageEvents.Event.KEYGUARD_HIDDEN -> UsageKind.UNLOCK
                else -> null
            } ?: continue
            out += UsageEvent(e.timeStamp, kind, e.packageName)
        }
        return out
    }

    /** Home screens and system UI aren't "apps" anyone chooses to spend time in. */
    private fun ignoredPackages(context: Context): Set<String> {
        val home = context.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }
        return (home + listOf("com.android.systemui", "com.google.android.permissioncontroller", "android")).toSet()
    }

    private fun appLabel(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg.substringAfterLast('.')
    }

    fun minutesOfDay(millis: Long): Int =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime().let { it.hour * 60 + it.minute }
}
