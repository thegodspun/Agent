package io.github.thegodspun.sentinel.work

import android.content.Context
import io.github.thegodspun.sentinel.app
import io.github.thegodspun.sentinel.core.Conversation
import io.github.thegodspun.sentinel.core.Prompts
import io.github.thegodspun.sentinel.core.TextFilter
import io.github.thegodspun.sentinel.core.TextMessage
import io.github.thegodspun.sentinel.core.coreTools
import io.github.thegodspun.sentinel.core.describeNow
import io.github.thegodspun.sentinel.core.habitTools
import io.github.thegodspun.sentinel.texts.TextReader
import java.time.LocalDateTime

/**
 * Once a day, reads new texts (SMS history, messages that arrived in messaging-app
 * notifications, and Google Messages chats read off the screen) and has Claude save what it
 * learns about the user: people, routines, plans and dates. The texts themselves are not kept.
 */
object ContextLearner {
    private const val HOUR = 3_600_000L
    private const val FIRST_RUN_DAYS = 30

    /** Call while holding [sentinelLock]. */
    suspend fun runIfDue(context: Context) {
        val app = context.app
        val settings = app.settings
        if (!settings.learnFromTexts || settings.apiKey.isBlank()) return
        val now = System.currentTimeMillis()
        val chatMessages = app.messages.all()
        val dueByTime = now - settings.lastTextsLearnedAt >= 20 * HOUR
        if (!dueByTime && chatMessages.size < 60) return

        val since = settings.lastTextsLearnedAt.takeIf { it > 0 } ?: (now - FIRST_RUN_DAYS * 24 * HOUR)
        val sms = TextReader.since(context, since)
        val fromNotifications = chatMessages.map {
            TextMessage(time = it.postedAt, person = it.title, outgoing = it.outgoing, body = it.text, source = it.app)
        }
        // A plain SMS shows up both in history and as a Messages notification; keep one copy.
        val batch = TextFilter.prepare((sms + fromNotifications).distinctBy { it.body.trim() }.sortedBy { it.time })
        if (batch.isNotEmpty()) {
            Conversation(
                config = settings.agentConfig(effortOverride = "medium"),
                systemPrompt = Prompts.background(settings.aboutMe, app.notes.all()),
                tools = coreTools(app.tracker, app.notes) { LocalDateTime.now() } +
                    habitTools(app.habits) { LocalDateTime.now() },
            ).send(Prompts.learnFromTexts(TextFilter.format(batch), describeNow(LocalDateTime.now())))
        }
        app.messages.drop(chatMessages.size)
        settings.lastTextsLearnedAt = now
    }
}
