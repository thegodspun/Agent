package io.github.thegodspun.sentinel.work

import android.content.Context
import android.util.Log
import com.anthropic.errors.BadRequestException
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
 * Learns about the user from their texts: SMS history, messages that arrived in messaging-app
 * notifications, and Google Messages chats read off the screen. Claude saves what it learns
 * (people, routines, plans, dates); the texts themselves are not kept.
 *
 * Messages go in oldest-first batches. After each batch, the checkpoint moves to the newest
 * message that was actually sent, so nothing is skipped when there's a backlog.
 */
object ContextLearner {
    private const val HOUR = 3_600_000L
    private const val FIRST_RUN_DAYS = 30
    private const val MAX_BATCHES_PER_RUN = 4

    /** Captured messages waiting before a run is started early. */
    const val BATCH_SIZE = 60

    /** Call while holding [sentinelLock]. */
    suspend fun runIfDue(context: Context) {
        val app = context.app
        val settings = app.settings
        if (!settings.learnFromTexts || settings.apiKey.isBlank()) return
        val now = System.currentTimeMillis()
        val captured = app.messages.all()
        val dueByTime = now - settings.lastTextsLearnedAt >= 20 * HOUR
        if (!dueByTime && captured.size < BATCH_SIZE && !settings.textsBacklog) return

        val since = settings.lastTextsLearnedAt.takeIf { it > 0 } ?: (now - FIRST_RUN_DAYS * 24 * HOUR)
        val sms = TextReader.since(context, since)
        val fromCapture = captured.map {
            TextMessage(time = it.postedAt, person = it.title, outgoing = it.outgoing, body = it.text, source = it.app, captureId = it.id)
        }
        // A plain SMS shows up both in history and as a Messages notification; keep one copy.
        val all = (sms + fromCapture).distinctBy { it.body.trim() }.sortedBy { it.time }
        val batches = TextFilter.chunks(all)

        for (batch in batches.take(MAX_BATCHES_PER_RUN)) {
            try {
                Conversation(
                    config = settings.agentConfig(effortOverride = "medium").copy(webSearch = false),
                    systemPrompt = Prompts.background(settings.aboutMe, app.notes.all()),
                    tools = coreTools(app.tracker, app.notes, provenance = "texts") { LocalDateTime.now() } +
                        habitTools(app.habits, background = true) { LocalDateTime.now() },
                ).send(Prompts.learnFromTexts(TextFilter.format(batch), describeNow(LocalDateTime.now())), refusalReply = "")
            } catch (e: BadRequestException) {
                // Something in this batch can't be sent; skip it rather than retrying it forever.
                Log.w("ContextLearner", "Skipping a batch the API rejected", e)
            }
            markLearned(context, batch)
        }
        // Everything that was filtered out (codes, blanks) is done too.
        val leftover = batches.size > MAX_BATCHES_PER_RUN
        if (!leftover) {
            app.messages.remove(captured.map { it.id }.toSet())
            settings.lastTextsLearnedAt = now
        }
        settings.textsBacklog = leftover
    }

    private fun markLearned(context: Context, batch: List<TextMessage>) {
        val app = context.app
        app.messages.remove(batch.mapNotNull { it.captureId }.toSet())
        batch.maxOfOrNull { it.epochMillis }?.takeIf { it > 0 }?.let {
            if (it > app.settings.lastTextsLearnedAt) app.settings.lastTextsLearnedAt = it
        }
    }
}
