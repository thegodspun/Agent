package io.github.thegodspun.sentinel.core

import com.anthropic.core.jsonMapper
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConversationTest {
    private val config = AgentConfig(apiKey = "test-key")
    private val dir: File = Files.createTempDirectory("conv").toFile()
    private val tracker = TrackerStore(File(dir, "tracker.json"))
    private val notes = NotesStore(File(dir, "notes.txt"))
    private val tools = coreTools(tracker, notes) { LocalDateTime.of(2026, 10, 21, 9, 0) }

    private fun message(stopReason: String, vararg content: String): BetaMessage =
        jsonMapper().readValue(
            """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
             "content":[${content.joinToString(",")}],
             "stop_reason":"$stopReason","stop_sequence":null,
             "usage":{"input_tokens":10,"output_tokens":5}}
            """.trimIndent(),
            BetaMessage::class.java,
        )

    private fun text(t: String) = """{"type":"text","text":"$t"}"""
    private fun toolUse(id: String, name: String, input: String) =
        """{"type":"tool_use","id":"$id","name":"$name","input":$input}"""

    /** Replies with canned responses (or throws them) and records each request. */
    private class FakeSender(private val replies: List<Any>) : MessageSender {
        val requests = mutableListOf<MessageCreateParams>()
        override suspend fun send(params: MessageCreateParams): BetaMessage {
            requests += params
            return when (val r = replies[requests.size - 1]) {
                is Exception -> throw r
                else -> r as BetaMessage
            }
        }
    }

    @Test
    fun `runs tools and returns all results in one message`() = runTest {
        val sender = FakeSender(
            listOf(
                message(
                    "tool_use",
                    text("Next Tuesday is Dec 1."),
                    toolUse("t1", "tracker_add", """{"title":"SSDI appeal","category":"deadline","due":"2026-12-01"}"""),
                    toolUse("t2", "get_current_time", "{}"),
                ),
                message("end_turn", text("Saved your appeal deadline.")),
            )
        )
        val conversation = Conversation(config, "system", tools, sender)

        assertEquals("Saved your appeal deadline.", conversation.send("My appeal is due Dec 1"))
        assertEquals("SSDI appeal", tracker.all().single().title)

        val second = sender.requests[1]
        val history = second.messages()
        assertEquals(3, history.size) // user, assistant tool_use, user tool_results
        val results = history[2].content().asBetaContentBlockParams()
        assertEquals(2, results.size)
        assertTrue(results.all { it.isToolResult() })
        assertEquals(4, conversation.turnCount)
    }

    @Test
    fun `confirmation gate blocks declined actions`() = runTest {
        var ran = false
        val risky = object : AgentTool("send_text", "Text someone") {
            override val properties = mapOf("to" to stringProp("who"))
            override val requiresConfirmation = true
            override suspend fun run(input: ToolInput): String { ran = true; return "sent" }
        }
        val sender = FakeSender(
            listOf(
                message("tool_use", toolUse("t1", "send_text", """{"to":"Mom"}""")),
                message("end_turn", text("Okay, I won't send it.")),
            )
        )
        val asked = mutableListOf<String>()
        val listener = object : AgentListener {
            override suspend fun confirm(question: String): Boolean { asked += question; return false }
        }
        Conversation(config, "system", listOf(risky), sender).send("Text mom", listener)

        assertEquals(1, asked.size)
        assertTrue(!ran)
        val result = sender.requests[1].messages()[2].content().asBetaContentBlockParams().single().asToolResult()
        assertTrue(result.content().get().asString().contains("declined"))
    }

    @Test
    fun `tool errors are reported back instead of crashing`() = runTest {
        val sender = FakeSender(
            listOf(
                message("tool_use", toolUse("t1", "tracker_add", """{"title":"x","category":"task","due":"soon"}""")),
                message("end_turn", text("That date didn't work.")),
            )
        )
        Conversation(config, "system", tools, sender).send("add x due soon")
        val result = sender.requests[1].messages()[2].content().asBetaContentBlockParams().single().asToolResult()
        assertTrue(result.isError().get())
    }

    @Test
    fun `refusal and api failure roll history back`() = runTest {
        val sender = FakeSender(listOf(message("refusal"), message("end_turn", text("Hi!"))))
        val conversation = Conversation(config, "system", tools, sender)
        assertTrue(conversation.send("first").startsWith("Sorry"))
        assertEquals(0, conversation.turnCount)
        assertEquals("Hi!", conversation.send("hello"))
        assertEquals(1, sender.requests[1].messages().size)

        val failing = Conversation(config, "system", tools, { throw IllegalStateException("offline") })
        assertFailsWith<IllegalStateException> { failing.send("hello") }
        assertEquals(0, failing.turnCount)
    }

    @Test
    fun `a tool call cut off by the length limit is not run`() = runTest {
        val sender = FakeSender(
            listOf(
                message("max_tokens", toolUse("t1", "tracker_add", """{"title":"Call Dana at 555-01","category":"task"}""")),
                message("end_turn", text("Saved.")),
            )
        )
        Conversation(config, "system", tools, sender).send("remind me to call Dana")
        assertTrue(tracker.all().isEmpty())
        val result = sender.requests[1].messages()[2].content().asBetaContentBlockParams().single().asToolResult()
        assertTrue(result.isError().get())
    }

    @Test
    fun `completed actions survive a later failure so they aren't repeated`() = runTest {
        var sent = 0
        val text = object : AgentTool("send_text", "Text someone") {
            override val properties = mapOf("to" to stringProp("who"))
            override val requiresConfirmation = true
            override suspend fun run(input: ToolInput): String { sent++; return "sent" }
        }
        val allow = object : AgentListener { override suspend fun confirm(question: String) = true }
        val sender = FakeSender(
            listOf(
                message("tool_use", toolUse("t1", "send_text", """{"to":"Dana"}""")),
                IllegalStateException("connection lost"),
            )
        )
        val conversation = Conversation(config, "system", listOf(text), sender)
        val e = assertFailsWith<ActionsCompletedException> { conversation.send("text Dana I'm late", allow) }
        assertEquals(listOf("send_text"), e.actions)
        assertEquals(1, sent)
        assertEquals(3, conversation.turnCount) // the request, the call and its result are kept
    }

    @Test
    fun `background runs can choose what a refusal returns`() = runTest {
        val sender = FakeSender(listOf(message("refusal")))
        assertEquals("", Conversation(config, "system", tools, sender).send("x", refusalReply = ""))
    }
}
