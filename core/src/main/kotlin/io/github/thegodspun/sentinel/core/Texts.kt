package io.github.thegodspun.sentinel.core

/** A text message, from the phone's SMS history or a messaging app's notification. */
data class TextMessage(
    val time: String,
    /** Contact name when known, otherwise the number or chat title. */
    val person: String,
    val outgoing: Boolean,
    val body: String,
    /** "SMS" or the messaging app's name. */
    val source: String,
)

object TextFilter {
    private val codeWords = Regex(
        "\\b(code|verification|verify|otp|passcode|password|pin|one[- ]time|2fa|login|sign[- ]in|security)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val shortNumber = Regex("\\b\\d{4,8}\\b|\\b[A-Z0-9]{3}-[A-Z0-9]{3}\\b")

    /** Login and verification codes are dropped on the phone and never sent anywhere. */
    fun isSecretCode(body: String): Boolean = codeWords.containsMatchIn(body) && shortNumber.containsMatchIn(body)

    /** Keeps messages worth learning from, newest last, within a character budget. */
    fun prepare(messages: List<TextMessage>, maxChars: Int = 24_000): List<TextMessage> {
        val kept = messages.filter { it.body.isNotBlank() && !isSecretCode(it.body) }
            .map { it.copy(body = it.body.replace(Regex("\\s+"), " ").take(600)) }
        // Prefer the most recent messages when there are too many.
        var budget = maxChars
        return kept.asReversed().takeWhile { m ->
            budget -= m.body.length + m.person.length + 30
            budget >= 0
        }.asReversed()
    }

    fun format(messages: List<TextMessage>): String = messages.joinToString("\n") { m ->
        val direction = if (m.outgoing) "me → ${m.person}" else "${m.person} → me"
        "[${m.time}] ($direction, ${m.source}) ${m.body}"
    }
}

/** Packages of messaging apps whose notifications count as texts. */
val MESSAGING_APPS = mapOf(
    "com.google.android.apps.messaging" to "Messages",
    "com.whatsapp" to "WhatsApp",
    "org.thoughtcrime.securesms" to "Signal",
    "org.telegram.messenger" to "Telegram",
    "com.facebook.orca" to "Messenger",
    "com.discord" to "Discord",
    "com.google.android.apps.googlevoice" to "Google Voice",
)
