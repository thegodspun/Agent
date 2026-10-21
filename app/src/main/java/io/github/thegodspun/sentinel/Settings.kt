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

    /** Hour of day (0-23) for the morning briefing, or -1 to turn it off. */
    var briefingHour: Int
        get() = prefs.getInt("briefing_hour", 8)
        set(value) = prefs.edit().putInt("briefing_hour", value).apply()

    var lastBriefingDate: String
        get() = prefs.getString("last_briefing", "").orEmpty()
        set(value) = prefs.edit().putString("last_briefing", value).apply()

    var lastBriefing: String
        get() = prefs.getString("last_briefing_text", "").orEmpty()
        set(value) = prefs.edit().putString("last_briefing_text", value).apply()

    /** Send every notification to the sentinel, not only ones matching benefit/health keywords. */
    var reviewAllNotifications: Boolean
        get() = prefs.getBoolean("review_all", false)
        set(value) = prefs.edit().putBoolean("review_all", value).apply()

    fun agentConfig(effortOverride: String? = null) =
        AgentConfig(apiKey = apiKey, model = model, effort = effortOverride ?: effort)
}
