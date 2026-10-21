package io.github.thegodspun.sentinel.core

/** Cuts to at most [n] characters without splitting an emoji or other surrogate pair. */
fun String.safeTake(n: Int): String {
    if (length <= n) return this
    val end = if (n > 0 && this[n - 1].isHighSurrogate()) n - 1 else n
    return substring(0, end)
}

/** Replaces broken halves of surrogate pairs, which the Claude API rejects, with U+FFFD. */
fun String.sanitizeUnicode(): String {
    if (none { it.isSurrogate() }) return this
    val out = StringBuilder(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        when {
            c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate() -> { out.append(c).append(this[i + 1]); i += 2; continue }
            c.isSurrogate() -> out.append('\uFFFD')
            else -> out.append(c)
        }
        i++
    }
    return out.toString()
}

/** A text message, from the phone's SMS history, a messaging app's notification, or the screen. */
data class TextMessage(
    /** When it was sent: ISO date-time or date, or "unknown" for messages read off the screen with no date. */
    val time: String,
    /** Contact name when known, otherwise the number or chat title. */
    val person: String,
    val outgoing: Boolean,
    val body: String,
    /** "SMS" or the messaging app's name. */
    val source: String,
    /** For SMS: when it was stored on the phone, used as the learning checkpoint. */
    val epochMillis: Long = 0,
    /** For captured messages: the stored item's id, so exactly the learned ones are removed. */
    val captureId: String? = null,
)

object TextFilter {
    private val codeWords = Regex(
        "\\b(code|codes|verification|verify|otp|passcode|password|pin|one[- ]time|2fa|mfa|login|log[- ]in|sign[- ]in|" +
            "security code|access code|confirm your identity|authenticat\\w*)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val shortNumber = Regex(
        "\\b\\d{4,8}\\b|\\b\\d{3}[ -]\\d{3}\\b|\\b[A-Z0-9]{3}-[A-Z0-9]{3}\\b|\\b(?=[A-Z0-9]*\\d)(?=[A-Z0-9]*[A-Z])[A-Z0-9]{6,8}\\b"
    )

    /** Login and verification codes are dropped on the phone and never sent anywhere. */
    fun isSecretCode(body: String): Boolean = codeWords.containsMatchIn(body) && shortNumber.containsMatchIn(body)

    /**
     * Blanks out the code in a notification that carries one, keeping the rest. A notice like
     * "MyChart: your code is 512334" becomes "MyChart: your code is [code removed]".
     */
    fun redactCodes(text: String): String =
        if (isSecretCode(text)) shortNumber.replace(text, "[code removed]") else text

    private fun clean(messages: List<TextMessage>): List<TextMessage> =
        messages.filter { it.body.isNotBlank() && !isSecretCode(it.body) }
            .map { it.copy(body = it.body.replace(Regex("\\s+"), " ").safeTake(600).sanitizeUnicode(), person = it.person.sanitizeUnicode()) }

    private fun cost(m: TextMessage) = m.body.length + m.person.length + 40

    /**
     * Splits messages into batches that each fit the character budget, oldest first, so a
     * backlog is learned a batch at a time instead of being cut off.
     */
    fun chunks(messages: List<TextMessage>, maxChars: Int = 24_000): List<List<TextMessage>> {
        val out = mutableListOf<MutableList<TextMessage>>()
        var used = maxChars
        clean(messages).forEach { m ->
            if (used + cost(m) > maxChars) {
                out += mutableListOf<TextMessage>()
                used = 0
            }
            out.last() += m
            used += cost(m)
        }
        return out
    }

    /** Keeps messages worth learning from, newest last, within a character budget. */
    fun prepare(messages: List<TextMessage>, maxChars: Int = 24_000): List<TextMessage> {
        val kept = clean(messages)
        // Prefer the most recent messages when there are too many.
        var budget = maxChars
        return kept.asReversed().takeWhile { m ->
            budget -= cost(m)
            budget >= 0
        }.asReversed()
    }

    fun format(messages: List<TextMessage>): String = messages.joinToString("\n") { m ->
        val direction = if (m.outgoing) "me → ${m.person}" else "${m.person} → me"
        val time = if (m.time == "unknown") "date unknown" else m.time
        "[$time] ($direction, ${m.source}) ${m.body}"
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
