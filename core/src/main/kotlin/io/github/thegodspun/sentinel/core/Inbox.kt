package io.github.thegodspun.sentinel.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** A phone notification captured for the sentinel to review. */
@Serializable
data class CapturedNotification(
    val app: String,
    val title: String,
    val text: String,
    val postedAt: String,
)

/** Notifications waiting for the next sentinel review. Capped so it can't grow forever. */
class InboxStore(private val file: File, private val maxItems: Int = 200) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    fun add(n: CapturedNotification) = synchronized(lock) {
        val items = read()
        // Apps often repost the same notification; keep one copy.
        if (items.any { it.app == n.app && it.title == n.title && it.text == n.text }) return
        write((items + n).takeLast(maxItems))
    }

    fun all(): List<CapturedNotification> = synchronized(lock) { read() }

    /** Removes the first [count] items (the ones that were just reviewed). */
    fun drop(count: Int) = synchronized(lock) { write(read().drop(count)) }

    private fun read(): List<CapturedNotification> =
        if (file.exists()) json.decodeFromString(file.readText()) else emptyList()

    private fun write(items: List<CapturedNotification>) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(items))
    }
}

/**
 * Decides which notifications are worth sending to Claude. Only notifications that
 * mention benefits, health care, money or deadlines are kept unless the user opts in
 * to reviewing everything, which limits both cost and how much personal data leaves
 * the phone.
 */
object NotificationFilter {
    val KEYWORDS = listOf(
        "appeal", "deadline", "due", "overdue", "expire", "renew", "recertif", "deny", "denied",
        "approved", "approval", "appointment", "reschedul", "cancel", "confirm", "claim", "benefit",
        "social security", "ssa", "ssdi", "ssi", "medicaid", "medicare", "insurance", "eob",
        "explanation of benefits", "prior auth", "authorization", "prescription", "refill", "pharmacy",
        "payment", "bill", "invoice", "balance", "hearing", "disability", "veteran", "va ", "mychart",
        "doctor", "clinic", "hospital", "therapy", "wheelchair", "dme", "supplies", "caseworker",
        "notice", "letter", "document", "verification", "eligib", "snap", "housing", "paratransit",
    )

    fun isRelevant(title: String, text: String): Boolean {
        val haystack = "$title $text".lowercase()
        return KEYWORDS.any { it in haystack }
    }
}
