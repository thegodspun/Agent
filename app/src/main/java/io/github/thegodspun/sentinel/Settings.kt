package io.github.thegodspun.sentinel

import android.content.Context
import io.github.thegodspun.sentinel.core.AgentConfig

/**
 * User settings. Stored in app-private storage, which other apps cannot read; the API
 * key is not encrypted beyond Android's own file-based encryption of app data.
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString("api_key", "").orEmpty()
        set(value) = prefs.edit().putString("api_key", value.trim()).apply()

    var model: String
        get() = prefs.getString("model", AgentConfig.DEFAULT_MODEL).orEmpty().ifBlank { AgentConfig.DEFAULT_MODEL }
        set(value) = prefs.edit().putString("model", value.trim()).apply()

    var effort: String
        get() = prefs.getString("effort", "medium").orEmpty()
        set(value) = prefs.edit().putString("effort", value).apply()

    var aboutMe: String
        get() = prefs.getString("about_me", "").orEmpty()
        set(value) = prefs.edit().putString("about_me", value).apply()

    var speakReplies: Boolean
        get() = prefs.getBoolean("speak_replies", false)
        set(value) = prefs.edit().putBoolean("speak_replies", value).apply()

    var alwaysOn: Boolean
        get() = prefs.getBoolean("always_on", true)
        set(value) = prefs.edit().putBoolean("always_on", value).apply()

    /** Brief the user when they first pick up the phone after sleeping, instead of at a fixed hour. */
    var wakeBriefing: Boolean
        get() = prefs.getBoolean("wake_briefing", true)
        set(value) = prefs.edit().putBoolean("wake_briefing", value).apply()

    /**
     * With wake briefings on: brief anyway by this hour if no wake-up was noticed (until the usual
     * wake time is learned). With them off: the fixed briefing hour. -1 turns briefings off.
     */
    var briefingHour: Int
        get() = prefs.getInt("briefing_hour", 10)
        set(value) = prefs.edit().putInt("briefing_hour", value).apply()

    /** Hours the screen must be off before an unlock counts as waking up. */
    var minSleepHours: Int
        get() = prefs.getInt("min_sleep_hours", 4)
        set(value) = prefs.edit().putInt("min_sleep_hours", value).apply()

    var lastBriefingAt: Long
        get() = prefs.getLong("last_briefing_at", 0)
        set(value) = prefs.edit().putLong("last_briefing_at", value).apply()

    var lastBriefing: String
        get() = prefs.getString("last_briefing_text", "").orEmpty()
        set(value) = prefs.edit().putString("last_briefing_text", value).apply()

    var lastWeeklyReview: String
        get() = prefs.getString("last_weekly_review", "").orEmpty()
        set(value) = prefs.edit().putString("last_weekly_review", value).apply()

    /** When the screen last turned off, recorded by the always-on service. */
    var lastScreenOff: Long
        get() = prefs.getLong("last_screen_off", 0)
        set(value) = prefs.edit().putLong("last_screen_off", value).apply()

    /** Learn habits from app usage, screen time and sleep patterns. */
    var habitLearning: Boolean
        get() = prefs.getBoolean("habit_learning", true)
        set(value) = prefs.edit().putBoolean("habit_learning", value).apply()

    /** Learn about the user from their texts (SMS history and incoming messaging-app notifications). */
    var learnFromTexts: Boolean
        get() = prefs.getBoolean("learn_from_texts", true)
        set(value) = prefs.edit().putBoolean("learn_from_texts", value).apply()

    var lastTextsLearnedAt: Long
        get() = prefs.getLong("last_texts_learned_at", 0)
        set(value) = prefs.edit().putLong("last_texts_learned_at", value).apply()

    /** Send every notification to the sentinel, not only ones matching benefit/health keywords. */
    var reviewAllNotifications: Boolean
        get() = prefs.getBoolean("review_all", false)
        set(value) = prefs.edit().putBoolean("review_all", value).apply()

    fun agentConfig(effortOverride: String? = null) =
        AgentConfig(apiKey = apiKey, model = model, effort = effortOverride ?: effort)
}
