package io.github.thegodspun.sentinel.core

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenChatTest {
    private val w = 1080
    private val h = 2400

    private val today = java.time.LocalDate.of(2026, 10, 21) // a Wednesday

    private fun node(
        text: String?, left: Int, top: Int, right: Int, bottom: Int,
        desc: String? = null, editable: Boolean = false, clickable: Boolean = false,
    ) = ScreenNode(text, desc, left, top, right, bottom, editable, clickable)

    /** Roughly what an open Google Messages conversation looks like to an accessibility service. */
    private val conversation = listOf(
        node(null, 20, 120, 120, 220, desc = "Back"),
        node("Mom", 150, 130, 400, 210),
        node(null, 900, 120, 1000, 220, desc = "Video call"),
        node("Today · 10:42 AM", 380, 300, 700, 340),
        node("Did you get the letter from Social Security?", 60, 380, 760, 520, desc = "Mom said Did you get the letter from Social Security?"),
        node("Yes! The appeal is due Nov 20, can you drive me Tuesday at 3:30?", 300, 560, 1020, 700),
        node("Delivered · 10:45 AM", 760, 710, 1020, 750),
        node("Your Chase verification code is 482913", 60, 780, 760, 850),
        node("Of course ❤️", 60, 880, 400, 940),
        node("Tuesday", 60, 960, 300, 1010), // a reply, not a date header: it isn't centered
        node("Sounds good", 60, 2080, 330, 2160, clickable = true), // Smart Reply chips
        node("Thanks!", 360, 2080, 600, 2160, clickable = true),
        node("RCS message", 120, 2200, 900, 2300, editable = true),
    )

    @Test
    fun `reads an open chat with direction`() {
        val chat = ScreenChatParser.parse(conversation, w, h, today)!!
        assertEquals("Mom", chat.person)
        assertEquals(
            listOf(
                ScreenMessage(false, "Did you get the letter from Social Security?", today),
                ScreenMessage(true, "Yes! The appeal is due Nov 20, can you drive me Tuesday at 3:30?", today),
                ScreenMessage(false, "Of course ❤️", today),
                ScreenMessage(false, "Tuesday", today),
            ),
            chat.messages,
        )
    }

    @Test
    fun `the chat list is not a conversation`() {
        val list = listOf(
            node("Messages", 150, 130, 500, 210),
            node("Mom", 200, 300, 600, 350),
            node("Of course ❤️", 200, 360, 800, 400),
            node("Start chat", 700, 2150, 1020, 2280),
        )
        assertNull(ScreenChatParser.parse(list, w, h))
    }

    @Test
    fun `chrome detection keeps real messages that mention times and dates`() {
        listOf("10:42 AM", "Today · 10:42 AM", "Yesterday 9:15 PM", "Delivered · 10:45 AM", "Tuesday", "Oct 21", "Sent").forEach {
            assertTrue(ScreenChatParser.isChrome(it), it)
        }
        listOf("See you at 3:30", "Maybe tomorrow", "Martin is coming", "Need money for rent", "Sunny today!").forEach {
            assertFalse(ScreenChatParser.isChrome(it), it)
        }
    }

    @Test
    fun `date headers give messages their dates`() {
        val p = ScreenChatParser
        assertEquals(today, p.parseDateHeader("Today · 10:42 AM", today))
        assertEquals(today.minusDays(1), p.parseDateHeader("Yesterday 9:15 PM", today))
        assertEquals(java.time.LocalDate.of(2026, 10, 20), p.parseDateHeader("Tuesday", today))
        assertEquals(java.time.LocalDate.of(2026, 10, 14), p.parseDateHeader("Wednesday", today)) // a week ago, not today
        assertEquals(java.time.LocalDate.of(2026, 6, 3), p.parseDateHeader("Wed, Jun 3", today))
        assertEquals(java.time.LocalDate.of(2025, 12, 24), p.parseDateHeader("Dec 24", today)) // last December
        assertEquals(java.time.LocalDate.of(2025, 6, 3), p.parseDateHeader("Jun 3, 2025", today))
        assertEquals(null, p.parseDateHeader("See you Tuesday at 3", today))

        val old = listOf(
            node("Nurse Dana", 150, 130, 500, 210),
            node("Wed, Jun 3", 400, 300, 680, 340),
            node("I'll come by tomorrow at 9", 60, 380, 760, 450),
            node("Today · 8:00 AM", 380, 500, 700, 540),
            node("Running 10 min late", 60, 580, 600, 640),
            node("RCS message", 120, 2200, 900, 2300, editable = true),
        )
        val chat = ScreenChatParser.parse(old, w, h, today)!!
        assertEquals(java.time.LocalDate.of(2026, 6, 3), chat.messages[0].date)
        assertEquals(today, chat.messages[1].date)
    }

    @Test
    fun `status lines under bubbles are not messages`() {
        assertTrue(ScreenChatParser.isStatusLine("Delivered"))
        assertTrue(ScreenChatParser.isStatusLine("Read · 10:45 AM"))
        assertTrue(ScreenChatParser.isStatusLine("10:45 AM"))
        assertFalse(ScreenChatParser.isStatusLine("Tuesday"))
        assertFalse(ScreenChatParser.isStatusLine("Read the letter yet?"))
    }

    @Test
    fun `seen store remembers captured messages across restarts`() {
        val file = File(Files.createTempDirectory("seen").toFile(), "seen.json")
        val store = SeenStore(file, max = 2)
        assertTrue(store.firstTime("a"))
        assertFalse(store.firstTime("a"))
        assertTrue(store.firstTime("b"))
        assertTrue(store.firstTime("c")) // "a" falls out
        val reopened = SeenStore(file, max = 2)
        assertFalse(reopened.firstTime("c"))
        assertTrue(reopened.firstTime("a"))
    }
}
