package io.github.thegodspun.sentinel.core

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HabitsTest {
    private val zone = ZoneId.of("America/Chicago")
    private val day = LocalDate.of(2026, 10, 21)

    private fun t(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun session(start: Long, end: Long, app: String? = null) = buildList {
        add(UsageEvent(start, UsageKind.SCREEN_ON))
        add(UsageEvent(start + 1_000, UsageKind.UNLOCK))
        if (app != null) add(UsageEvent(start + 2_000, UsageKind.APP_FOREGROUND, app))
        add(UsageEvent(end, UsageKind.SCREEN_OFF))
    }

    /** Evening scrolling until 12:40 a.m., a 3 a.m. phone check, then up at 7:30. */
    private val night = buildList {
        val prev = day.minusDays(1)
        addAll(session(t(prev, 21), t(prev, 23, 0), "youtube"))
        addAll(session(t(prev, 23, 30), t(day, 0, 40), "tiktok"))
        addAll(session(t(day, 3, 0), t(day, 3, 4)))
        addAll(session(t(day, 7, 30), t(day, 8, 15), "gmail"))
        addAll(session(t(day, 12), t(day, 13), "youtube"))
    }

    @Test
    fun `infers bedtime, wake time and night checks`() {
        val s = UsageAnalyzer.summarize(night, day, zone, now = t(day, 20))
        assertEquals("2026-10-21T00:40", s.bedtime)
        assertEquals("2026-10-21T07:30", s.wakeTime)
        assertEquals(1, s.nightPickups)
        assertEquals(6 * 60 + 46, s.sleepMinutes) // 0:40-7:30 minus the 4-minute check
        assertEquals("gmail", s.firstAppAfterWaking)
    }

    @Test
    fun `counts screen time, late-night use, unlocks and apps within the day`() {
        val s = UsageAnalyzer.summarize(night, day, zone, now = t(day, 20))
        // 40 min after midnight + 4 + 45 + 60
        assertEquals(149, s.screenMinutes)
        assertEquals(44, s.lateNightMinutes)
        assertEquals(3, s.unlocks) // the 11:30 p.m. unlock belongs to the day before
        assertEquals("youtube", s.topApps.first().app)
        assertEquals(59, s.topApps.first().minutes)
        assertEquals(40, s.topApps.first { it.app == "tiktok" }.minutes)
    }

    @Test
    fun `latest sleep ignores a gap still in progress`() {
        val asleepNow = UsageAnalyzer.latestSleep(night.filter { it.time < t(day, 7) }, now = t(day, 7))
        assertTrue(asleepNow == null || asleepNow.end < t(day, 0), "the night isn't over until the screen comes back on")
        val awake = UsageAnalyzer.latestSleep(night.filter { it.time < t(day, 7, 35) }, now = t(day, 7, 35))
        assertNotNull(awake)
        assertEquals(t(day, 7, 30), awake.end)
    }

    @Test
    fun `profile summarizes typical schedule and averages`() {
        val dir = Files.createTempDirectory("habits").toFile()
        val store = HabitStore(File(dir, "habits.json"))
        val days = (1..7).map { offset ->
            val d = day.minusDays(offset.toLong())
            DaySummary(
                date = d.toString(), screenMinutes = 300, unlocks = 80, lateNightMinutes = 30,
                topApps = listOf(AppTime("YouTube", 100)),
                bedtime = d.minusDays(1).atTime(23, 30).toString(), wakeTime = d.atTime(7, 15).toString(),
                sleepMinutes = 465,
            )
        }
        store.upsertDays(days)
        store.addGoal("Phone down by midnight")
        store.addInsight(HabitInsight("2026-10-20", "bad_habit", "Late-night YouTube"))

        val profile = HabitProfile.describe(store.data(), day)
        assertTrue("11:30 PM" in profile, profile)
        assertTrue("7:15 AM" in profile, profile)
        assertTrue("screen time 5h 0m" in profile, profile)
        assertTrue("YouTube 100" in profile, profile)
        assertTrue("Phone down by midnight" in profile, profile)
        assertTrue("Late-night YouTube" in profile, profile)
        assertEquals(7 * 60 + 15, HabitProfile.typicalWakeMinutes(store.data(), day))
    }

    @Test
    fun `bedtimes either side of midnight average to around midnight`() {
        val data = HabitData(
            days = listOf(
                DaySummary("2026-10-19", 0, 0, 0, bedtime = "2026-10-18T23:00"),
                DaySummary("2026-10-20", 0, 0, 0, bedtime = "2026-10-20T01:00"),
                DaySummary("2026-10-21", 0, 0, 0, bedtime = "2026-10-21T00:00"),
            )
        )
        assertEquals(0, HabitProfile.typicalBedtimeMinutes(data, day))
    }

    @Test
    fun `upsert keeps an old sleep estimate the new data can't see`() {
        val store = HabitStore(File(Files.createTempDirectory("habits").toFile(), "h.json"))
        store.upsertDays(listOf(DaySummary("2026-10-15", 200, 50, 10, wakeTime = "2026-10-15T07:00", bedtime = "2026-10-14T23:00")))
        store.upsertDays(listOf(DaySummary("2026-10-15", 210, 52, 10)))
        val saved = store.data().days.single()
        assertEquals(210, saved.screenMinutes)
        assertEquals("2026-10-15T07:00", saved.wakeTime)
    }

    @Test
    fun `wake policy`() {
        val now = LocalDateTime.of(2026, 10, 21, 7, 30).atZone(zone).toInstant().toEpochMilli()
        val hour = 3_600_000L
        assertTrue(WakePolicy.worthChecking(now, lastScreenOff = now - 6 * hour, lastBriefingAt = now - 24 * hour))
        assertTrue(!WakePolicy.worthChecking(now, lastScreenOff = now - 5 * 60_000, lastBriefingAt = 0))
        assertTrue(!WakePolicy.worthChecking(now, lastScreenOff = now - 6 * hour, lastBriefingAt = now - 2 * hour))

        assertEquals(10 * 60_000L, WakePolicy.confirmDelayMillis(3 * 60, typicalWakeMinutes = 7 * 60 + 30))
        assertEquals(90_000L, WakePolicy.confirmDelayMillis(7 * 60, typicalWakeMinutes = 7 * 60 + 30))
        assertEquals(10 * 60_000L, WakePolicy.confirmDelayMillis(2 * 60, typicalWakeMinutes = null))
    }
}
