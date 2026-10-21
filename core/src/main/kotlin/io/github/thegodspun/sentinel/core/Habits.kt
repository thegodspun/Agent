package io.github.thegodspun.sentinel.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** A phone-usage event, translated from Android's UsageStatsManager. */
enum class UsageKind { APP_FOREGROUND, APP_BACKGROUND, SCREEN_ON, SCREEN_OFF, UNLOCK }

data class UsageEvent(val time: Long, val kind: UsageKind, val app: String? = null)

@Serializable
data class AppTime(val app: String, val minutes: Int)

/** One day of habits, worked out on the phone. Only these summaries are ever sent to Claude. */
@Serializable
data class DaySummary(
    val date: String,
    val screenMinutes: Int,
    val unlocks: Int,
    /** Screen-on time between midnight and 5 a.m. */
    val lateNightMinutes: Int,
    val topApps: List<AppTime> = emptyList(),
    /** When the screen went dark for the night that ended this morning (an estimate of falling asleep). */
    val bedtime: String? = null,
    /** When the screen first came on after that night. */
    val wakeTime: String? = null,
    val sleepMinutes: Int? = null,
    /** Times the phone was checked during that night. */
    val nightPickups: Int = 0,
    val firstAppAfterWaking: String? = null,
)

@Serializable
data class HabitInsight(val date: String, val kind: String, val text: String)

@Serializable
data class HabitData(
    val days: List<DaySummary> = emptyList(),
    val goals: List<String> = emptyList(),
    val insights: List<HabitInsight> = emptyList(),
)

val INSIGHT_KINDS = listOf("bad_habit", "good_habit", "pattern", "suggestion")

/** Daily summaries, the user's habit goals, and what the coach has noticed. */
class HabitStore(private val file: File, private val keepDays: Int = 90, private val keepInsights: Int = 40) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val lock = Any()

    fun data(): HabitData = synchronized(lock) { read() }

    /** Saves summaries, replacing older versions of the same day unless the new one has no data. */
    fun upsertDays(summaries: List<DaySummary>) = synchronized(lock) {
        val byDate = read().days.associateBy { it.date }.toMutableMap()
        summaries.forEach { new ->
            val old = byDate[new.date]
            val hasData = new.screenMinutes > 0 || new.unlocks > 0 || new.wakeTime != null
            // Days before Sentinel (or the phone) was in use have no events; don't record them as idle days.
            if (old == null && !hasData) return@forEach
            if (old == null || new.screenMinutes > 0 || new.wakeTime != null) {
                // Keep a sleep estimate the new data can no longer see (events expire after a few days).
                byDate[new.date] = if (new.wakeTime == null && old?.wakeTime != null) {
                    new.copy(
                        bedtime = old.bedtime, wakeTime = old.wakeTime, sleepMinutes = old.sleepMinutes,
                        nightPickups = old.nightPickups, firstAppAfterWaking = old.firstAppAfterWaking,
                    )
                } else new
            }
        }
        update { it.copy(days = byDate.values.sortedBy { d -> d.date }.takeLast(keepDays)) }
    }

    fun addGoal(goal: String) = update { it.copy(goals = (it.goals + goal.trim()).distinct()) }

    fun removeGoal(text: String): Int {
        var removed = 0
        update { data ->
            val kept = data.goals.filterNot { it.contains(text, ignoreCase = true) }
            removed = data.goals.size - kept.size
            data.copy(goals = kept)
        }
        return removed
    }

    fun addInsight(insight: HabitInsight) = update { data ->
        val others = data.insights.filterNot { it.text.equals(insight.text, ignoreCase = true) }
        data.copy(insights = (others + insight).takeLast(keepInsights))
    }

    private fun update(transform: (HabitData) -> HabitData) = synchronized(lock) { write(transform(read())) }

    private fun read(): HabitData = readOrQuarantine(file, HabitData()) { json.decodeFromString(it) }

    private fun write(data: HabitData) = writeAtomically(file, json.encodeToString(data))
}

data class Interval(val start: Long, val end: Long) {
    fun overlap(from: Long, to: Long): Long = (minOf(end, to) - maxOf(start, from)).coerceAtLeast(0)
}

/** A stretch with the screen off, allowing for brief checks of the phone in the middle. */
data class SleepBlock(val start: Long, val end: Long, val pickups: Int, val asleepMillis: Long)

object UsageAnalyzer {
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE

    /** Off-screen gaps shorter than this don't count toward sleep. */
    private const val MIN_GAP = 20 * MINUTE

    /** Phone checks shorter than this don't end a night's sleep. */
    private const val MAX_INTERRUPTION = 10 * MINUTE

    /** Screen-on intervals. An open session at the end runs until [now]. */
    fun screenSessions(events: List<UsageEvent>, now: Long): List<Interval> {
        val sessions = mutableListOf<Interval>()
        var onSince: Long? = null
        for (e in events.sortedBy { it.time }) {
            when (e.kind) {
                UsageKind.SCREEN_OFF -> onSince?.let { sessions += Interval(it, e.time); onSince = null }
                // An unlock or app launch while we think the screen is off means we missed the "on" event.
                else -> if (onSince == null && e.kind != UsageKind.APP_BACKGROUND) onSince = e.time
            }
        }
        onSince?.let { sessions += Interval(it, now) }
        return sessions
    }

    /**
     * Sleep-like blocks between [from] and the last screen session. A block is a run of
     * screen-off gaps of at least 20 minutes separated by phone checks of at most 10 minutes.
     * A gap still in progress (the screen is off right now) is not included.
     */
    fun sleepBlocks(sessions: List<Interval>, from: Long, to: Long, minAsleepMillis: Long): List<SleepBlock> {
        val inRange = sessions.filter { it.end > from && it.start < to }
        val gaps = mutableListOf<Interval>()
        var cursor = from
        for (s in inRange) {
            if (s.start > cursor) gaps += Interval(cursor, s.start)
            cursor = maxOf(cursor, s.end)
        }
        val candidates = gaps.filter { it.end - it.start >= MIN_GAP }
        val blocks = mutableListOf<SleepBlock>()
        var current: SleepBlock? = null
        for (gap in candidates) {
            val c = current
            current = if (c != null && gap.start - c.end <= MAX_INTERRUPTION) {
                c.copy(end = gap.end, pickups = c.pickups + 1, asleepMillis = c.asleepMillis + (gap.end - gap.start))
            } else {
                c?.let { blocks += it }
                SleepBlock(gap.start, gap.end, 0, gap.end - gap.start)
            }
        }
        current?.let { blocks += it }
        return blocks.filter { it.asleepMillis >= minAsleepMillis }
    }

    /** Total time the screen has been on between [from] and [now]. */
    fun screenOnMillis(events: List<UsageEvent>, from: Long, now: Long): Long =
        screenSessions(events, now).sumOf { it.overlap(from, now) }

    /** The most recent finished sleep block in the last day, if any. */
    fun latestSleep(events: List<UsageEvent>, now: Long, minAsleepMillis: Long = 3 * HOUR): SleepBlock? =
        sleepBlocks(screenSessions(events, now), now - 24 * HOUR, now, minAsleepMillis).lastOrNull()

    fun summarize(
        events: List<UsageEvent>,
        date: LocalDate,
        zone: ZoneId,
        now: Long,
        label: (String) -> String = { it },
        ignoredApps: Set<String> = emptySet(),
    ): DaySummary {
        val sorted = events.sortedBy { it.time }
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = minOf(date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), now)
        val sessions = screenSessions(sorted, now)

        val screen = sessions.sumOf { it.overlap(dayStart, dayEnd) }
        val lateNight = sessions.sumOf { it.overlap(dayStart, minOf(dayStart + 5 * HOUR, dayEnd)) }
        val unlocks = sorted.count { it.kind == UsageKind.UNLOCK && it.time in dayStart until dayEnd }

        val appMillis = mutableMapOf<String, Long>()
        for (interval in appIntervals(sorted, now)) {
            val ms = interval.second.overlap(dayStart, dayEnd)
            if (ms > 0 && interval.first !in ignoredApps) appMillis.merge(interval.first, ms, Long::plus)
        }
        val topApps = appMillis.entries
            .map { AppTime(label(it.key), (it.value / MINUTE).toInt()) }
            .filter { it.minutes >= 1 }
            .groupBy { it.app }.map { (app, times) -> AppTime(app, times.sumOf { it.minutes }) }
            .sortedByDescending { it.minutes }
            .take(8)

        // Last night: the longest sleep block that ended this day, looking back to 6 p.m.
        val night = sleepBlocks(sessions, dayStart - 6 * HOUR, dayEnd, 3 * HOUR)
            .filter { it.end in dayStart until dayEnd && WakePolicy.isNightSleep(it.start, it.end, zone) }
            .maxByOrNull { it.asleepMillis }
        val firstApp = night?.let { n ->
            sorted.firstOrNull {
                it.kind == UsageKind.APP_FOREGROUND && it.time >= n.end && it.time < n.end + 2 * HOUR &&
                    it.app != null && it.app !in ignoredApps
            }?.app?.let(label)
        }

        return DaySummary(
            date = date.toString(),
            screenMinutes = (screen / MINUTE).toInt(),
            unlocks = unlocks,
            lateNightMinutes = (lateNight / MINUTE).toInt(),
            topApps = topApps,
            bedtime = night?.let { isoMinute(it.start, zone) },
            wakeTime = night?.let { isoMinute(it.end, zone) },
            sleepMinutes = night?.let { (it.asleepMillis / MINUTE).toInt() },
            nightPickups = night?.pickups ?: 0,
            firstAppAfterWaking = firstApp,
        )
    }

    /** (package, interval) for each stretch an app was in the foreground. */
    private fun appIntervals(sorted: List<UsageEvent>, now: Long): List<Pair<String, Interval>> {
        val out = mutableListOf<Pair<String, Interval>>()
        var app: String? = null
        var since = 0L
        fun close(at: Long) {
            app?.let { out += it to Interval(since, at) }
            app = null
        }
        for (e in sorted) {
            when (e.kind) {
                UsageKind.APP_FOREGROUND -> {
                    if (e.app != app) close(e.time)
                    if (app == null) { app = e.app; since = e.time }
                }
                UsageKind.APP_BACKGROUND -> if (e.app == app) close(e.time)
                UsageKind.SCREEN_OFF -> close(e.time)
                else -> Unit
            }
        }
        close(now)
        return out
    }

    private fun isoMinute(millis: Long, zone: ZoneId): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone).withSecond(0).withNano(0).toString()
}

/** Turns stored summaries into a short plain-text profile for Claude and the Habits screen. */
object HabitProfile {
    private val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    /** Typical wake time as minutes after midnight, from the last two weeks. */
    fun typicalWakeMinutes(data: HabitData, today: LocalDate): Int? = median(
        recent(data, today, 14).mapNotNull { d -> d.wakeTime?.let { LocalDateTime.parse(it).toLocalTime() } }
            .map { it.hour * 60 + it.minute }
    )

    /** Typical bedtime as minutes after midnight (may be after midnight, e.g. 60 = 1:00 a.m.). */
    fun typicalBedtimeMinutes(data: HabitData, today: LocalDate): Int? {
        // Measure from noon so that 11 p.m. and 1 a.m. average to midnight, not noon.
        val fromNoon = recent(data, today, 14).mapNotNull { d -> d.bedtime?.let { LocalDateTime.parse(it).toLocalTime() } }
            .map { ((it.hour * 60 + it.minute) - 12 * 60 + 24 * 60) % (24 * 60) }
        return median(fromNoon)?.let { (it + 12 * 60) % (24 * 60) }
    }

    fun describe(data: HabitData, today: LocalDate): String {
        val week = recent(data, today, 8).filter { it.date != today.toString() }
        val lastNight = data.days.firstOrNull { it.date == today.toString() && it.wakeTime != null }
        if (week.isEmpty() && lastNight == null && data.goals.isEmpty() && data.insights.isEmpty()) {
            return "No habit data yet (it builds up over the first few days, and needs Usage access turned on)."
        }
        return buildString {
            if (lastNight?.bedtime != null && lastNight.wakeTime != null) {
                append("Last night (ended this morning): screen off ${shortTime(lastNight.bedtime)} → first use ${shortTime(lastNight.wakeTime)}")
                lastNight.sleepMinutes?.let { append(", about ${hours(it.toDouble())} with the screen off") }
                if (lastNight.nightPickups > 0) append(", ${lastNight.nightPickups} phone checks during the night")
                if (lastNight.lateNightMinutes > 0) append(", ${lastNight.lateNightMinutes} min of use between midnight and 5 a.m.")
                lastNight.firstAppAfterWaking?.let { append(", first app after waking: $it") }
                append(".\n")
            }
            val wake = typicalWakeMinutes(data, today)
            val bed = typicalBedtimeMinutes(data, today)
            val sleeps = recent(data, today, 14).mapNotNull { it.sleepMinutes }
            if (wake != null || bed != null) {
                append("Typical schedule (last 2 weeks): ")
                bed?.let { append("screen off for the night around ${formatMinutes(it)}") }
                if (wake != null && bed != null) append(", ")
                wake?.let { append("first use in the morning around ${formatMinutes(it)}") }
                if (sleeps.isNotEmpty()) append("; about ${hours(sleeps.average())} with the screen off")
                append(".\n")
            }
            if (week.isNotEmpty()) {
                append(
                    "Last ${week.size} full days, average per day: screen time ${hours(week.map { it.screenMinutes }.average())}, " +
                        "${week.map { it.unlocks }.average().roundToInt()} unlocks, " +
                        "${week.map { it.lateNightMinutes }.average().roundToInt()} min of use between midnight and 5 a.m.\n"
                )
                val apps = week.flatMap { it.topApps }.groupBy { it.app }
                    .mapValues { (_, v) -> v.sumOf { it.minutes } / week.size }
                    .entries.sortedByDescending { it.value }.take(6)
                if (apps.isNotEmpty()) {
                    append("Most-used apps (avg min/day): ")
                    append(apps.joinToString { "${it.key} ${it.value}" }).append(".\n")
                }
                append("Day by day (date: the night ending that morning, screen off → first use; then that day's screen time, late-night min, unlocks, top app):\n")
                week.forEach { d ->
                    append("- ${d.date}: ")
                    if (d.bedtime != null && d.wakeTime != null) {
                        append("${shortTime(d.bedtime)} → ${shortTime(d.wakeTime)}")
                        if (d.nightPickups > 0) append(" (${d.nightPickups} night checks)")
                    } else append("sleep unknown")
                    append(", ${hours(d.screenMinutes.toDouble())}, ${d.lateNightMinutes} late, ${d.unlocks} unlocks")
                    d.topApps.firstOrNull()?.let { append(", ${it.app} ${it.minutes} min") }
                    d.firstAppAfterWaking?.let { append(", first app after waking: $it") }
                    append('\n')
                }
            }
            if (data.goals.isNotEmpty()) {
                append("The user's habit goals:\n")
                data.goals.forEach { append("- ").append(it).append('\n') }
            }
            if (data.insights.isNotEmpty()) {
                append("Observations you saved earlier (most recent last):\n")
                data.insights.takeLast(12).forEach { append("- ${it.date} [${it.kind}] ${it.text}\n") }
            }
        }.trim()
    }

    fun formatMinutes(minutes: Int): String =
        java.time.LocalTime.of(minutes / 60 % 24, minutes % 60).format(clock)

    fun hours(minutes: Double): String {
        val m = minutes.roundToInt()
        return if (m < 60) "$m min" else "${m / 60}h ${m % 60}m"
    }

    private fun shortTime(iso: String): String = LocalDateTime.parse(iso).toLocalTime().format(clock)

    private fun recent(data: HabitData, today: LocalDate, days: Int): List<DaySummary> {
        val cutoff = today.minusDays(days.toLong()).toString()
        return data.days.filter { it.date > cutoff && it.date <= today.toString() }
    }

    private fun median(values: List<Int>): Int? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}

/**
 * Tools for the habit coach. With [background] set, goals can't be changed: they're the
 * user's own words, so only the user (in chat) sets or removes them.
 */
fun habitTools(habits: HabitStore, background: Boolean = false, clock: () -> LocalDateTime): List<AgentTool> =
    allHabitTools(habits, clock).filter { !background || it.name !in setOf("set_habit_goal", "remove_habit_goal") }

private fun allHabitTools(habits: HabitStore, clock: () -> LocalDateTime): List<AgentTool> = listOf(
    object : AgentTool(
        "habit_report",
        "Get the user's recent habit data: sleep and wake estimates, screen time, late-night use, " +
            "unlocks, most-used apps, their habit goals and your earlier observations.",
    ) {
        override val properties = emptyMap<String, Map<String, Any>>()
        override suspend fun run(input: ToolInput) = HabitProfile.describe(habits.data(), clock().toLocalDate())
    },

    object : AgentTool(
        "save_habit_insight",
        "Save an observation about the user's habits so you can follow up on it later: a bad habit to " +
            "work on, a good habit to reinforce, a pattern, or a suggestion you made.",
    ) {
        override val properties = mapOf(
            "kind" to stringProp("Type of observation", INSIGHT_KINDS),
            "text" to stringProp("One specific sentence, e.g. \"Scrolling YouTube past 1 a.m. on 5 of 7 nights\""),
        )
        override val required = listOf("kind", "text")
        override suspend fun run(input: ToolInput): String {
            val kind = input.string("kind").takeIf { it in INSIGHT_KINDS } ?: "pattern"
            habits.addInsight(HabitInsight(clock().toLocalDate().toString(), kind, input.string("text")))
            return "Saved."
        }
    },

    object : AgentTool("set_habit_goal", "Save a habit the user wants to build or break, in their words.") {
        override val properties = mapOf("goal" to stringProp("e.g. \"Phone down by midnight\""))
        override val required = listOf("goal")
        override suspend fun run(input: ToolInput): String {
            habits.addGoal(input.string("goal"))
            return "Goal saved."
        }
    },

    object : AgentTool("remove_habit_goal", "Remove habit goals containing the given text.") {
        override val properties = mapOf("text" to stringProp("Text the goal contains"))
        override val required = listOf("text")
        override suspend fun run(input: ToolInput) = "Removed ${habits.removeGoal(input.string("text"))} goal(s)."
    },
)

/** Decides whether an unlock looks like the start of the user's day. */
object WakePolicy {
    const val MIN_GAP_TO_CHECK_MILLIS = 20 * 60_000L

    /** A wake-up candidate stays valid this long if the screen times out before it's confirmed. */
    const val CANDIDATE_TTL_MILLIS = 30 * 60_000L

    /** Each "wake-day" runs from 4 a.m. to 4 a.m., and gets at most one briefing. */
    private const val DAY_STARTS_AT_HOUR = 4

    fun wakeDayStart(now: LocalDateTime): LocalDateTime {
        val today = now.toLocalDate().atTime(DAY_STARTS_AT_HOUR, 0)
        return if (now.hour < DAY_STARTS_AT_HOUR) today.minusDays(1) else today
    }

    fun briefedThisWakeDay(lastBriefingAt: Long, now: Long, zone: ZoneId): Boolean {
        if (lastBriefingAt <= 0) return false
        val start = wakeDayStart(LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone))
        return lastBriefingAt >= start.atZone(zone).toInstant().toEpochMilli()
    }

    /**
     * Whether a dark-screen stretch looks like a night's sleep rather than an afternoon or
     * evening away from the phone: it has to cover at least an hour between 1 and 7 a.m.
     */
    fun isNightSleep(start: Long, end: Long, zone: ZoneId): Boolean {
        if (end <= start) return false
        var day = LocalDateTime.ofInstant(Instant.ofEpochMilli(start), zone).toLocalDate()
        val lastDay = LocalDateTime.ofInstant(Instant.ofEpochMilli(end), zone).toLocalDate()
        while (!day.isAfter(lastDay)) {
            val windowStart = day.atTime(1, 0).atZone(zone).toInstant().toEpochMilli()
            val windowEnd = day.atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
            if (Interval(start, end).overlap(windowStart, windowEnd) >= 60 * 60_000L) return true
            day = day.plusDays(1)
        }
        return false
    }

    /** A cheap first test run on every unlock; the full check happens a little later. */
    fun worthChecking(now: Long, lastScreenOff: Long, lastBriefingAt: Long, zone: ZoneId): Boolean =
        lastScreenOff > 0 &&
            now - lastScreenOff >= MIN_GAP_TO_CHECK_MILLIS &&
            !briefedThisWakeDay(lastBriefingAt, now, zone)

    /**
     * How long to wait before confirming the user is really up. In the middle of the night
     * (more than 3 hours before their usual wake time, or midnight-5 a.m. before one is
     * learned) a quick look at the phone shouldn't trigger the morning briefing.
     */
    fun confirmDelayMillis(localMinutes: Int, typicalWakeMinutes: Int?): Long {
        val night = if (typicalWakeMinutes != null) {
            val minutesBeforeWake = (typicalWakeMinutes - localMinutes + 24 * 60) % (24 * 60)
            minutesBeforeWake in 181..(10 * 60)
        } else {
            localMinutes < 5 * 60
        }
        return if (night) 10 * 60_000L else 90_000L
    }

    /**
     * The fallback briefing (when no wake-up was caught) may go out from [fallbackMinutes]
     * for eight hours, never late in the evening.
     */
    fun inFallbackWindow(localMinutes: Int, fallbackMinutes: Int): Boolean =
        localMinutes >= fallbackMinutes && localMinutes < minOf(fallbackMinutes + 8 * 60, 21 * 60)
}
