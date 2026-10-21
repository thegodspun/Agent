package io.github.thegodspun.sentinel.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import java.util.UUID

val TRACKER_CATEGORIES = listOf(
    "deadline", "appointment", "benefit", "claim", "medication", "equipment", "task", "contact",
)

@Serializable
data class TrackerItem(
    val id: String,
    val title: String,
    val category: String,
    /** ISO date (2026-11-03) or date-time (2026-11-03T14:30), or null when undated. */
    val due: String? = null,
    val notes: String = "",
    val done: Boolean = false,
    val createdAt: String,
    /** Days-before-due reminders already sent, so each one fires once. */
    val remindersSent: List<Int> = emptyList(),
) {
    fun dueDate(): LocalDate? = parseDue(due)?.toLocalDate()
    fun dueDateTime(): LocalDateTime? = parseDue(due)

    fun describe(): String = buildString {
        append("[$id] ")
        if (done) append("(done) ")
        append("$category: $title")
        if (due != null) append(" — due $due")
        if (notes.isNotBlank()) append(" — $notes")
    }

    companion object {
        fun parseDue(value: String?): LocalDateTime? {
            if (value.isNullOrBlank()) return null
            return try {
                LocalDateTime.parse(value)
            } catch (_: DateTimeParseException) {
                try {
                    LocalDate.parse(value).atStartOfDay()
                } catch (_: DateTimeParseException) {
                    null
                }
            }
        }
    }
}

/** Deadlines, appointments, claims and benefits, saved as a JSON file in app storage. */
class TrackerStore(private val file: File) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val lock = Any()

    fun all(): List<TrackerItem> = synchronized(lock) { read() }

    fun get(id: String): TrackerItem? = all().firstOrNull { it.id == id }

    fun add(title: String, category: String, due: String?, notes: String, now: LocalDateTime): TrackerItem {
        validateDue(due)
        val item = TrackerItem(
            id = UUID.randomUUID().toString().take(8),
            title = title,
            category = category.lowercase().takeIf { it in TRACKER_CATEGORIES } ?: "task",
            due = due,
            notes = notes,
            createdAt = now.withNano(0).toString(),
        )
        synchronized(lock) { write(read() + item) }
        return item
    }

    fun update(id: String, transform: (TrackerItem) -> TrackerItem): TrackerItem? = synchronized(lock) {
        val items = read()
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return null
        val updated = transform(items[index])
        validateDue(updated.due)
        write(items.toMutableList().also { it[index] = updated })
        updated
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        val items = read()
        val remaining = items.filterNot { it.id == id }
        if (remaining.size == items.size) return false
        write(remaining)
        true
    }

    private fun validateDue(due: String?) {
        require(due.isNullOrBlank() || TrackerItem.parseDue(due) != null) {
            "Due date must look like 2026-11-03 or 2026-11-03T14:30, got \"$due\""
        }
    }

    private fun read(): List<TrackerItem> = readOrQuarantine(file, emptyList()) { json.decodeFromString(it) }

    private fun write(items: List<TrackerItem>) = writeAtomically(file, json.encodeToString(items))
}

/** A reminder that is due to be shown now. */
data class Reminder(val item: TrackerItem, val daysBefore: Int, val message: String)

object Reminders {
    /** How many days ahead of a due date each reminder fires. */
    val SCHEDULE = listOf(30, 14, 7, 3, 1, 0)

    /**
     * Finds reminders that should fire at [today]: the closest threshold reached that has
     * not been sent yet. Missed earlier thresholds are skipped so the person isn't flooded
     * with stale reminders. Overdue items remind once per day, keyed by the negated date.
     */
    fun due(items: List<TrackerItem>, today: LocalDate): List<Reminder> = items
        .filter { !it.done }
        .mapNotNull { item ->
            val dueDate = item.dueDate() ?: return@mapNotNull null
            val daysLeft = java.time.temporal.ChronoUnit.DAYS.between(today, dueDate).toInt()
            if (daysLeft < 0) {
                val key = -(today.toEpochDay().toInt())
                if (key in item.remindersSent) return@mapNotNull null
                return@mapNotNull Reminder(item, key, "OVERDUE by ${-daysLeft} day(s): ${item.title}")
            }
            val threshold = SCHEDULE.filter { daysLeft <= it }.minOrNull() ?: return@mapNotNull null
            if (threshold in item.remindersSent) return@mapNotNull null
            val whenText = when (daysLeft) {
                0 -> "TODAY"
                1 -> "tomorrow"
                else -> "in $daysLeft days (${dueDate})"
            }
            Reminder(item, threshold, "${item.category.replaceFirstChar { it.uppercase() }} $whenText: ${item.title}")
        }

    fun markSent(store: TrackerStore, reminder: Reminder) {
        store.update(reminder.item.id) { it.copy(remindersSent = (it.remindersSent + reminder.daysBefore).distinct()) }
    }
}
