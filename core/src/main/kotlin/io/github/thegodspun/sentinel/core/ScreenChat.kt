package io.github.thegodspun.sentinel.core

import kotlinx.serialization.encodeToString
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
)

data class ScreenMessage(val outgoing: Boolean, val text: String)

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

    fun parse(nodes: List<ScreenNode>, width: Int, height: Int): ScreenConversation? {
        if (width <= 0 || height <= 0) return null
        // A conversation has a compose box in the lower part of the screen; the chat list doesn't.
        val compose = nodes.filter { it.editable }.maxByOrNull { it.bottom } ?: return null
        if (compose.top < height * 0.35) return null

        val topBar = height * 0.14
        val person = nodes.firstOrNull {
            !it.editable && !it.text.isNullOrBlank() && it.bottom <= topBar * 1.4 && !isChrome(it.text)
        }?.text?.trim() ?: return null

        val messages = nodes
            .filter { n ->
                !n.editable && !n.text.isNullOrBlank() && n.top >= topBar && n.bottom <= compose.top + 4 &&
                    !isChrome(n.text) && n.text.trim() != person
            }
            .sortedWith(compareBy({ it.top }, { it.left }))
            .map { ScreenMessage(outgoing = isOutgoing(it, width), text = it.text!!.trim()) }
            .filter { it.text.length >= 2 && !TextFilter.isSecretCode(it.text) }
            .distinct()
        return if (messages.isEmpty()) null else ScreenConversation(person, messages)
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
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(set.toList()))
        true
    }

    private fun load(): LinkedHashSet<Int> = cache ?: LinkedHashSet<Int>().also { set ->
        if (file.exists()) set.addAll(json.decodeFromString<List<Int>>(file.readText()))
        cache = set
    }
}
