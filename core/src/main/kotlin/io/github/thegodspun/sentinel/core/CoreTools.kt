package io.github.thegodspun.sentinel.core

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Tools that only touch the agent's own data, so they are safe in background jobs. */
fun coreTools(tracker: TrackerStore, notes: NotesStore, clock: () -> LocalDateTime): List<AgentTool> = listOf(
    object : AgentTool(
        "get_current_time",
        "Returns the current local date, weekday and time on the phone.",
    ) {
        override val properties = emptyMap<String, Map<String, Any>>()
        override suspend fun run(input: ToolInput) = describeNow(clock())
    },

    object : AgentTool(
        "tracker_add",
        "Save a deadline, appointment, benefit application, insurance claim, medication, " +
            "equipment order, task or important contact so the phone reminds the user about it. " +
            "Check tracker_list first to avoid duplicates.",
    ) {
        override val properties = mapOf(
            "title" to stringProp("Short, specific title, e.g. \"SSDI appeal (Request for Reconsideration) due\""),
            "category" to stringProp("Kind of item", TRACKER_CATEGORIES),
            "due" to stringProp("Due date as 2026-11-03 or date-time as 2026-11-03T14:30. Omit if undated."),
            "notes" to stringProp("Details: phone numbers, claim numbers, what to bring, source of the deadline."),
        )
        override val required = listOf("title", "category")
        override suspend fun run(input: ToolInput): String {
            val item = tracker.add(
                title = input.string("title"),
                category = input.string("category"),
                due = input.stringOrNull("due"),
                notes = input.stringOrNull("notes").orEmpty(),
                now = clock(),
            )
            return "Saved: ${item.describe()}"
        }
    },

    object : AgentTool(
        "tracker_list",
        "List the user's tracked deadlines, appointments, benefits, claims and tasks, soonest first.",
    ) {
        override val properties = mapOf(
            "include_done" to boolProp("Also list completed items. Default false."),
            "category" to stringProp("Only this category", TRACKER_CATEGORIES),
        )
        override suspend fun run(input: ToolInput): String {
            val includeDone = input.boolOrNull("include_done") ?: false
            val category = input.stringOrNull("category")
            val items = tracker.all()
                .filter { includeDone || !it.done }
                .filter { category == null || it.category == category }
                .sortedWith(compareBy(nullsLast()) { it.dueDateTime() })
            return if (items.isEmpty()) "No matching items." else items.joinToString("\n") { it.describe() }
        }
    },

    object : AgentTool(
        "tracker_update",
        "Change a tracked item by id (from tracker_list). Only the fields you pass change.",
    ) {
        override val properties = mapOf(
            "id" to stringProp("Item id"),
            "title" to stringProp("New title"),
            "category" to stringProp("New category", TRACKER_CATEGORIES),
            "due" to stringProp("New due date (2026-11-03 or 2026-11-03T14:30)"),
            "notes" to stringProp("Replacement notes"),
            "done" to boolProp("Mark complete (true) or reopen (false)"),
        )
        override val required = listOf("id")
        override suspend fun run(input: ToolInput): String {
            val updated = tracker.update(input.string("id")) { item ->
                val newDue = input.stringOrNull("due") ?: item.due
                item.copy(
                    title = input.stringOrNull("title") ?: item.title,
                    category = input.stringOrNull("category") ?: item.category,
                    due = newDue,
                    notes = input.stringOrNull("notes") ?: item.notes,
                    done = input.boolOrNull("done") ?: item.done,
                    remindersSent = if (newDue != item.due) emptyList() else item.remindersSent,
                )
            } ?: return "No item with that id."
            return "Updated: ${updated.describe()}"
        }
    },

    object : AgentTool("tracker_delete", "Delete a tracked item by id. Prefer marking items done instead.") {
        override val properties = mapOf("id" to stringProp("Item id"))
        override val required = listOf("id")
        override suspend fun run(input: ToolInput) =
            if (tracker.delete(input.string("id"))) "Deleted." else "No item with that id."
    },

    object : AgentTool(
        "remember",
        "Save a lasting fact about the user (insurance plan, injury level, caseworker name, state, " +
            "preferences). Saved facts are shown to you at the start of every future conversation.",
    ) {
        override val properties = mapOf("fact" to stringProp("One self-contained fact"))
        override val required = listOf("fact")
        override suspend fun run(input: ToolInput): String {
            notes.add(input.string("fact"))
            return "Remembered."
        }
    },

    object : AgentTool("forget", "Remove saved facts that contain the given text.") {
        override val properties = mapOf("text" to stringProp("Text the fact contains"))
        override val required = listOf("text")
        override suspend fun run(input: ToolInput) = "Removed ${notes.remove(input.string("text"))} fact(s)."
    },
)

fun describeNow(now: LocalDateTime): String =
    "${now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)}, " +
        now.format(DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' h:mm a", Locale.US)) +
        " (ISO ${now.withNano(0).withSecond(0)})"

/** Long-term facts about the user, one per line. The oldest drop off past [maxFacts]. */
class NotesStore(private val file: File, private val maxFacts: Int = 200) {
    private val lock = Any()

    fun all(): List<String> = synchronized(lock) {
        if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()
    }

    fun add(fact: String) = synchronized(lock) {
        file.parentFile?.mkdirs()
        val clean = fact.replace('\n', ' ').trim()
        val facts = (all().filterNot { it.equals(clean, ignoreCase = true) } + clean).takeLast(maxFacts)
        file.writeText(facts.joinToString("\n", postfix = "\n"))
    }

    fun removeExact(fact: String) = synchronized(lock) {
        val kept = all().filterNot { it == fact }
        file.writeText(kept.joinToString("\n", postfix = if (kept.isEmpty()) "" else "\n"))
    }

    fun remove(text: String): Int = synchronized(lock) {
        val lines = all()
        val kept = lines.filterNot { it.contains(text, ignoreCase = true) }
        file.writeText(kept.joinToString("\n", postfix = if (kept.isEmpty()) "" else "\n"))
        lines.size - kept.size
    }
}
