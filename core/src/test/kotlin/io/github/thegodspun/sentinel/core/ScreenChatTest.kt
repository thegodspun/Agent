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

    private fun node(text: String?, left: Int, top: Int, right: Int, bottom: Int, desc: String? = null, editable: Boolean = false) =
        ScreenNode(text, desc, left, top, right, bottom, editable)

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
        node("RCS message", 120, 2200, 900, 2300, editable = true),
    )

    @Test
    fun `reads an open chat with direction`() {
        val chat = ScreenChatParser.parse(conversation, w, h)!!
        assertEquals("Mom", chat.person)
        assertEquals(
            listOf(
                ScreenMessage(false, "Did you get the letter from Social Security?"),
                ScreenMessage(true, "Yes! The appeal is due Nov 20, can you drive me Tuesday at 3:30?"),
                ScreenMessage(false, "Of course ❤️"),
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
