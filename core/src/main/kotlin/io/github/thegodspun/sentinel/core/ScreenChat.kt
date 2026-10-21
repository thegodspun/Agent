package io.github.thegodspun.sentinel.core

import kotlinx.serialization.encodeToString
import java.time.LocalDate
import kotlinx.serialization.json.Json
import java.io.File

/** A piece of on-screen text and where it is, copied from the accessibility tree. */
data class ScreenNode(
    val text: String?,
    val description: String?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val editable: Boolean = false,
    val clickable: Boolean = false,
)

/** A message read off the screen. [date] comes from the date header above it, when there is one. */
data class ScreenMessage(val outgoing: Boolean, val text: String, val date: LocalDate? = null)

data class ScreenConversation(val person: String, val messages: List<ScreenMessage>)

/**
 * Reads an open chat in Google Messages from what is on screen. That is the only way to see
 * RCS messages, because Android doesn't let other apps read RCS history. The layout rules are
 * best-effort: the chat's name is the first text in the top bar, messages sit between the top
 * bar and the compose box, and the user's own messages hug the right edge.
 */
object ScreenChatParser {
    private val time = Regex("""\b\d{1,2}:\d{2}\s?([AaPp]\.?\s?[Mm]\.?)?""")
    private val chromeWords = setOf(
        "today", "yesterday", "now", "just now", "sent", "delivered", "read", "seen", "sending", "sending…",
        "failed", "not sent", "rcs message", "text message", "chat message", "message", "type a message",
        "sms", "mms", "rcs", "end-to-end encrypted", "encrypted", "tap to retry", "tap to download",
        "active now", "typing", "typing…", "edited", "reply", "react", "more", "send", "back",
    )
    private val dayOrMonth = Regex(
        """\b(mon(day)?|tue(s|sday)?|wed(nesday)?|thu(rs|rsday)?|fri(day)?|sat(urday)?|sun(day)?|""" +
            """jan(uary)?|feb(ruary)?|mar(ch)?|apr(il)?|may|june?|july?|aug(ust)?|sept?(ember)?|oct(ober)?|""" +
            """nov(ember)?|dec(ember)?)\b"""
    )
    private val separators = Regex("""[\d·•,.:/\-]+""")
    private val chromeStarts = listOf(
        "chatting with", "texting with", "messages and calls are", "you're now chatting", "tap to",
        "this chat is", "today ·", "yesterday ·",
    )

    fun parse(nodes: List<ScreenNode>, width: Int, height: Int, today: LocalDate = LocalDate.now()): ScreenConversation? {
        if (width <= 0 || height <= 0) return null
        // A conversation has a compose box in the lower part of the screen; the chat list doesn't.
        val compose = nodes.filter { it.editable }.maxByOrNull { it.bottom } ?: return null
        if (compose.top < height * 0.35) return null

        val topBar = height * 0.14
        val title = nodes.firstOrNull {
            !it.editable && !it.text.isNullOrBlank() && it.bottom <= topBar * 1.4 && !isChrome(it.text)
        } ?: return null
        val person = title.text!!.trim()

        // Everything between the chat's title and the compose box.
        val body = nodes
            .filter { n ->
                n !== title && !n.editable && !n.text.isNullOrBlank() && n.top >= title.bottom - 4 &&
                    n.bottom <= compose.top + 4 && n.text.trim() != person
            }
            .sortedWith(compareBy({ it.top }, { it.left }))
        val chips = suggestionChips(body, compose)

        val messages = mutableListOf<ScreenMessage>()
        var date: LocalDate? = null
        for (n in body) {
            if (n in chips) continue
            val text = n.text!!.trim()
            if (isCentered(n, width)) {
                // Centered lines are date headers ("Today · 10:42 AM") and banners, not messages.
                parseDateHeader(text, today)?.let { date = it }
                if (isChrome(text) || parseDateHeader(text, today) != null) continue
            } else if (isStatusLine(text)) {
                continue // "Delivered", "10:45 AM" under a bubble
            }
            if (text.length < 2 || TextFilter.isSecretCode(text)) continue
            messages += ScreenMessage(outgoing = isOutgoing(n, width), text = text, date = date)
        }
        val unique = messages.distinctBy { Triple(it.outgoing, it.text, it.date) }
        return if (unique.isEmpty()) null else ScreenConversation(person, unique)
    }

    /** Smart Reply and suggestion chips: a row of short tappable items just above the compose box. */
    private fun suggestionChips(body: List<ScreenNode>, compose: ScreenNode): Set<ScreenNode> {
        val band = body.filter { it.clickable && it.bottom <= compose.top + 4 && it.top >= compose.top - (compose.bottom - compose.top) * 2.5 }
        return band.groupBy { it.top / 24 }.values.filter { it.size >= 2 }.flatten().toSet()
    }

    private fun isCentered(n: ScreenNode, width: Int): Boolean {
        val center = (n.left + n.right) / 2.0
        return kotlin.math.abs(center - width / 2.0) < width * 0.1 && (n.right - n.left) < width * 0.75
    }

    private val statusOnly = Regex(
        """^((sent|delivered|read|seen|edited|sending…?|failed|not sent)\s*[·•]?\s*)?(\d{1,2}:\d{2}\s?([ap]\.?\s?m\.?)?)?$""",
        RegexOption.IGNORE_CASE,
    )

    /** A delivery status or bare timestamp shown under a bubble. */
    fun isStatusLine(text: String): Boolean {
        val t = text.trim()
        return t.isNotEmpty() && statusOnly.matches(t) && (time.containsMatchIn(t.lowercase()) || t.lowercase() in chromeWords)
    }

    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val weekdays = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

    /** Reads a date header such as "Today", "Yesterday · 9:15 PM", "Tuesday", "Oct 21" or "Mon, Jun 3, 2025". */
    fun parseDateHeader(text: String, today: LocalDate): LocalDate? {
        val t = text.trim().lowercase()
        if (t.length > 40) return null
        if (t.startsWith("today")) return today
        if (t.startsWith("yesterday")) return today.minusDays(1)
        val words = t.split(Regex("[\\s,·•]+")).filter { it.isNotBlank() }
        val month = words.firstNotNullOfOrNull { w -> months.indexOfFirst { w.startsWith(it) }.takeIf { it >= 0 && w.length <= 9 } }
        val numbers = words.mapNotNull { it.trimEnd('.').toIntOrNull() }
        if (month != null) {
            val day = numbers.firstOrNull { it in 1..31 } ?: return null
            val year = numbers.firstOrNull { it in 2000..2100 }
            return runCatching {
                val candidate = LocalDate.of(year ?: today.year, month + 1, day)
                if (year == null && candidate.isAfter(today)) candidate.minusYears(1) else candidate
            }.getOrNull()
        }
        val weekday = words.firstNotNullOfOrNull { w -> weekdays.indexOfFirst { w.startsWith(it) }.takeIf { it >= 0 && w.length <= 9 } }
        if (weekday != null && words.size <= 4) {
            val back = (today.dayOfWeek.value - (weekday + 1) + 7) % 7
            return today.minusDays(if (back == 0) 7L else back.toLong())
        }
        return null
    }

    /** Timestamps, date separators, delivery status and banners rather than message text. */
    fun isChrome(raw: String): Boolean {
        val t = raw.trim().lowercase()
        if (t.isEmpty() || t in chromeWords) return true
        if (chromeStarts.any { t.startsWith(it) }) return true
        if (t.length > 40) return false
        // Strip times, day and month names, digits and separators; chrome has nothing else left.
        val rest = t.replace(time, " ").replace(dayOrMonth, " ").replace(separators, " ")
        return rest.split(Regex("\\s+")).filter { it.isNotBlank() }.all { it in chromeWords }
    }

    private fun isOutgoing(n: ScreenNode, width: Int): Boolean {
        val description = n.description?.lowercase().orEmpty()
        if (description.startsWith("you said") || description.startsWith("you:") || description.startsWith("sent by you")) return true
        if (description.contains(" said") && !description.startsWith("you")) return false
        val nearRight = n.right >= width * 0.88
        val nearLeft = n.left <= width * 0.25
        return when {
            nearRight && !nearLeft -> true
            nearLeft && !nearRight -> false
            else -> (n.left + n.right) / 2 > width / 2
        }
    }
}

/** Remembers which on-screen messages were already captured, so scrolling back doesn't re-learn them. */
class SeenStore(private val file: File, private val max: Int = 5_000) {
    private val json = Json
    private val lock = Any()
    private var cache: LinkedHashSet<Int>? = null

    /** Returns true the first time [key] is offered, false after that. */
    fun firstTime(key: String): Boolean = synchronized(lock) {
        val set = load()
        if (!set.add(key.hashCode())) return false
        while (set.size > max) set.remove(set.first())
        writeAtomically(file, json.encodeToString(set.toList()))
        true
    }

    private fun load(): LinkedHashSet<Int> = cache ?: LinkedHashSet<Int>().also { set ->
        set.addAll(readOrQuarantine(file, emptyList<Int>()) { json.decodeFromString<List<Int>>(it) })
        cache = set
    }
}
