package io.github.thegodspun.sentinel

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.thegodspun.sentinel.core.AppTime
import io.github.thegodspun.sentinel.core.DaySummary
import io.github.thegodspun.sentinel.core.HabitInsight
import io.github.thegodspun.sentinel.ui.ChatLine
import io.github.thegodspun.sentinel.ui.ChatViewModel
import io.github.thegodspun.sentinel.ui.SentinelScreen
import io.github.thegodspun.sentinel.ui.SentinelTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Renders each screen with sample data, in light and dark mode, to PNGs under
 * app/build/screenshots. CI publishes them so the design can be reviewed without a phone.
 * Run with: ./gradlew :app:recordRoborazziDebug
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: SentinelApp get() = ApplicationProvider.getApplicationContext()

    private object NoActions : MainActivity.Actions {
        override fun listen() = Unit
        override fun requestPermissions() = Unit
        override fun openNotificationAccess() = Unit
        override fun openAccessibility() = Unit
        override fun openUsageAccess() = Unit
        override fun openAppInfo() = Unit
        override fun requestBatteryExemption() = Unit
        override fun openUrl(url: String) = Unit
    }

    @Before
    fun seed() {
        val now = LocalDateTime.now()
        val today = LocalDate.now()
        app.settings.apiKey = "sk-ant-sample"
        app.settings.lastBriefing = "Good morning! Your SSDI reconsideration form is due Friday, so let's send it today. " +
            "You have PT at 1:00 PM. You slept about 6 hours; last night's TikTok after 1 a.m. cut into that, " +
            "so try putting the phone on the charger across the room tonight."
        app.settings.lastBriefingAt = System.currentTimeMillis()
        app.tracker.add("SSDI reconsideration (SSA-561) due", "deadline", today.plusDays(2).toString(), "Mail certified, keep the receipt.", now)
        app.tracker.add("Outpatient PT", "appointment", today.atTime(13, 0).toString(), "OhioHealth Rehab, 2nd floor", now)
        app.tracker.add("Medicaid renewal packet", "benefit", today.minusDays(1).toString(), "", now)
        app.tracker.add("Cushion delivery", "equipment", today.plusDays(9).toString(), "Between 9 and 12", now)
        val done = app.tracker.add("Call vocational rehab", "task", null, "", now)
        app.tracker.update(done.id) { it.copy(done = true) }
        app.habits.upsertDays((1..7).map { i ->
            val d = today.minusDays(i.toLong())
            DaySummary(
                d.toString(), screenMinutes = 300 + i * 17, unlocks = 80 + i * 3, lateNightMinutes = if (i % 2 == 0) 70 else 15,
                topApps = listOf(AppTime("TikTok", 140 - i * 5), AppTime("YouTube", 80), AppTime("Messages", 35)),
                bedtime = d.minusDays(1).atTime(if (i % 2 == 0) 1 else 23, 40).toString(), wakeTime = d.atTime(7, 20 + i).toString(),
                sleepMinutes = if (i % 2 == 0) 340 else 450, nightPickups = 2,
            )
        })
        app.habits.addGoal("Phone down by midnight")
        app.habits.addGoal("Pressure relief every 30 minutes")
        app.habits.addInsight(HabitInsight(today.minusDays(1).toString(), "bad_habit", "TikTok past 1 a.m. on 4 of the last 7 nights, and those nights were 2 hours shorter."))
        app.habits.addInsight(HabitInsight(today.toString(), "good_habit", "You've been up by 7:30 every day this week."))
        app.notes.add("Lives in Columbus, Ohio")
        app.notes.add("Aunt Carol drives to PT on Thursdays")
    }

    private fun shoot(name: String, tab: Int, dark: Boolean, prepare: (ChatViewModel) -> Unit = {}) {
        if (dark) RuntimeEnvironment.setQualifiers("+night")
        val chat = ChatViewModel(app).also(prepare)
        compose.mainClock.autoAdvance = false
        compose.setContent { SentinelTheme { SentinelScreen(chat, refreshKey = 0, actions = NoActions, initialTab = tab) } }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    private val conversation: (ChatViewModel) -> Unit = {
        it.showPreview(
            listOf(
                ChatLine(true, "Did I get anything important from Social Security?"),
                ChatLine(false, "Yes. A letter arrived in your my Social Security account last night, and your reconsideration form (SSA-561) is due Friday. I added both to your tracker. Want me to walk you through the form?"),
                ChatLine(true, "Yes please, and remind me tomorrow at 9"),
            ),
            "Saving to your tracker…",
        )
    }

    @Test fun chatLight() = shoot("1-chat-light", 0, dark = false)
    @Test fun chatDark() = shoot("2-chat-dark", 0, dark = true)
    @Test fun conversationLight() = shoot("3-conversation-light", 0, dark = false, prepare = conversation)
    @Test fun conversationDark() = shoot("4-conversation-dark", 0, dark = true, prepare = conversation)
    @Test fun habitsLight() = shoot("5-habits-light", 1, dark = false)
    @Test fun habitsDark() = shoot("6-habits-dark", 1, dark = true)
    @Test fun trackerLight() = shoot("7-tracker-light", 2, dark = false)
    @Test fun trackerDark() = shoot("8-tracker-dark", 2, dark = true)
    @Test fun setupLight() = shoot("9-setup-light", 3, dark = false)
}
