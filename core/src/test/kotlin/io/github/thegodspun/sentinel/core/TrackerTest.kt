package io.github.thegodspun.sentinel.core

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackerTest {
    private val dir: File = Files.createTempDirectory("tracker").toFile()
    private val store = TrackerStore(File(dir, "tracker.json"))
    private val now = LocalDateTime.of(2026, 10, 21, 9, 0)

    @Test
    fun `items persist across store instances`() {
        val item = store.add("SSDI appeal", "deadline", "2026-11-20", "Form SSA-561", now)
        val reopened = TrackerStore(File(dir, "tracker.json"))
        assertEquals(listOf(item), reopened.all())
    }

    @Test
    fun `unknown category falls back to task`() {
        assertEquals("task", store.add("Call PT", "whatever", null, "", now).category)
    }

    @Test
    fun `rejects unparseable due dates`() {
        assertFailsWith<IllegalArgumentException> { store.add("x", "task", "next Tuesday", "", now) }
    }

    @Test
    fun `update and delete`() {
        val item = store.add("Wheelchair fitting", "appointment", "2026-10-30T14:30", "", now)
        store.update(item.id) { it.copy(done = true) }
        assertTrue(store.get(item.id)!!.done)
        assertTrue(store.delete(item.id))
        assertFalse(store.delete(item.id))
        assertNull(store.update("missing") { it })
    }

    @Test
    fun `reminders fire once per threshold and skip stale ones`() {
        val today = LocalDate.of(2026, 10, 21)
        val item = store.add("Medicaid renewal", "benefit", "2026-10-26", "", now) // 5 days out

        val first = Reminders.due(store.all(), today)
        assertEquals(1, first.size)
        assertEquals(7, first.single().daysBefore) // 30 and 14 were skipped, not both sent
        Reminders.markSent(store, first.single())
        assertTrue(Reminders.due(store.all(), today).isEmpty())

        val threeDaysOut = Reminders.due(store.all(), LocalDate.of(2026, 10, 23))
        assertEquals(3, threeDaysOut.single().daysBefore)
        Reminders.markSent(store, threeDaysOut.single())

        val dueDay = Reminders.due(store.all(), LocalDate.of(2026, 10, 26)).single()
        assertTrue("TODAY" in dueDay.message, dueDay.message)

        store.update(item.id) { it.copy(done = true) }
        assertTrue(Reminders.due(store.all(), LocalDate.of(2026, 10, 26)).isEmpty())
    }

    @Test
    fun `overdue items remind once per day`() {
        store.add("Return DME form", "task", "2026-10-01", "", now)
        val day1 = LocalDate.of(2026, 10, 21)
        val r = Reminders.due(store.all(), day1).single()
        assertTrue(r.message.startsWith("OVERDUE by 20"), r.message)
        Reminders.markSent(store, r)
        assertTrue(Reminders.due(store.all(), day1).isEmpty())
        assertEquals(1, Reminders.due(store.all(), day1.plusDays(1)).size)
    }

    @Test
    fun `undated items never remind`() {
        store.add("Look into ABLE account", "benefit", null, "", now)
        assertTrue(Reminders.due(store.all(), LocalDate.of(2026, 10, 21)).isEmpty())
    }

    @Test
    fun `notes add and remove`() {
        val notes = NotesStore(File(dir, "notes.txt"))
        notes.add("Lives in Ohio")
        notes.add("Has Medicaid through\nCareSource")
        assertEquals(listOf("Lives in Ohio", "Has Medicaid through CareSource"), notes.all())
        assertEquals(1, notes.remove("ohio"))
        assertEquals(listOf("Has Medicaid through CareSource"), notes.all())
    }

    @Test
    fun `notification filter keeps benefit mail and drops chatter`() {
        assertTrue(NotificationFilter.isRelevant("Social Security", "A new letter is in your account"))
        assertTrue(NotificationFilter.isRelevant("CVS", "Your prescription is ready"))
        assertFalse(NotificationFilter.isRelevant("Alex", "lol see you tonight"))
    }

    @Test
    fun `inbox deduplicates and drops reviewed items`() {
        val inbox = InboxStore(File(dir, "inbox.json"), maxItems = 3)
        val n = CapturedNotification("Gmail", "SSA", "Your hearing is scheduled", "2026-10-21T09:00")
        inbox.add(n)
        inbox.add(n)
        assertEquals(1, inbox.all().size)
        repeat(5) { inbox.add(n.copy(text = "message $it")) }
        assertEquals(3, inbox.all().size)
        inbox.drop(2)
        assertEquals(listOf("message 4"), inbox.all().map { it.text })
    }
}
