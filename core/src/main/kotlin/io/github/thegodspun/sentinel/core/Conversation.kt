package io.github.thegodspun.sentinel.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive
import com.anthropic.models.beta.messages.BetaToolResultBlockParam
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

data class AgentConfig(
    val apiKey: String,
    val model: String = DEFAULT_MODEL,
    /** low | medium | high | xhigh | max */
    val effort: String = "medium",
    val webSearch: Boolean = true,
) {
    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
    }
}

/** Hooks the UI or a background job supplies to observe and gate a conversation. */
interface AgentListener {
    /** Called before each tool runs, so the UI can show "Checking your tracker…". */
    fun onToolStarted(toolName: String) {}

    /** Ask the person to approve an action. Background jobs should answer false. */
    suspend fun confirm(question: String): Boolean = false
}

private val clients = ConcurrentHashMap<String, AnthropicClient>()

/** One shared HTTP client per API key, so each conversation doesn't open its own connection pool. */
fun clientFor(apiKey: String): AnthropicClient = clients.getOrPut(apiKey) {
    AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(Duration.ofMinutes(5))
        .maxRetries(3)
        .build()
}

/** Abstraction over the Messages API call so the loop can be tested without a network. */
fun interface MessageSender {
    fun send(params: MessageCreateParams): BetaMessage
}

/**
 * One conversation with Claude. The system prompt and tool list are frozen when the
 * conversation starts and the message history is only ever appended to, which keeps
 * the prompt cache warm and keeps Claude's earlier reasoning valid between turns.
 */
class Conversation(
    private val config: AgentConfig,
    systemPrompt: String,
    tools: List<AgentTool>,
    private val sender: MessageSender = clientFor(config.apiKey).let { client ->
        MessageSender { client.beta().messages().create(it) }
    },
    private val maxSteps: Int = 25,
) {
    private val toolsByName = tools.associateBy { it.name }
    private val messages = mutableListOf<BetaMessageParam>()

    private val baseParams: MessageCreateParams.Builder = MessageCreateParams.builder()
        .model(config.model)
        .maxTokens(16_000L)
        .systemOfBetaTextBlockParams(
            listOf(
                BetaTextBlockParam.builder()
                    .text(systemPrompt)
                    .cacheControl(BetaCacheControlEphemeral.builder().build())
                    .build()
            )
        )
        .thinking(BetaThinkingConfigAdaptive.builder().build())
        .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.of(config.effort)).build())
        // If a safety classifier declines a request, retry it on Anthropic's recommended
        // fallback model instead of failing.
        .addBeta("server-side-fallback-2026-07-01")
        .fallbacksDefault()
        .apply {
            tools.forEach { addTool(it.toBetaTool()) }
            if (config.webSearch) addTool(BetaWebSearchTool20260209.builder().maxUses(5L).build())
        }

    val turnCount: Int get() = messages.size

    /**
     * Sends [userText], runs any tools Claude asks for, and returns Claude's final reply.
     * On failure the history is rolled back so the conversation stays usable.
     */
    suspend fun send(userText: String, listener: AgentListener = object : AgentListener {}): String =
        withContext(Dispatchers.IO) {
            val checkpoint = messages.size
            try {
                messages += userMessage(listOf(BetaContentBlockParam.ofText(userText)))
                runLoop(listener) ?: run {
                    rollback(checkpoint)
                    "Sorry, I can't help with that request."
                }
            } catch (e: Exception) {
                rollback(checkpoint)
                throw e
            }
        }

    /** Returns the final text, or null if the request was declined. */
    private suspend fun runLoop(listener: AgentListener): String? {
        val replyParts = mutableListOf<String>()
        repeat(maxSteps) {
            val response = sender.send(baseParams.messages(messages.toList()).build())
            val stopReason = response.stopReason().orElse(null)
            if (stopReason == BetaStopReason.REFUSAL) return null

            messages += response.toParam()
            response.content().forEach { block -> block.text().ifPresent { replyParts += it.text() } }

            val toolUses = response.content().mapNotNull { it.toolUse().orElse(null) }
            when {
                toolUses.isNotEmpty() -> {
                    val results = toolUses.map { use ->
                        listener.onToolStarted(use.name())
                        val outcome = runTool(use.name(), use._input().convert(Map::class.java), listener)
                        BetaContentBlockParam.ofToolResult(
                            BetaToolResultBlockParam.builder()
                                .toolUseId(use.id())
                                .content(outcome.text)
                                .isError(outcome.isError)
                                .build()
                        )
                    }
                    // Every result goes back in a single message.
                    messages += userMessage(results)
                    // Text written before a tool call is a working note, not the answer.
                    replyParts.clear()
                }
                // A server tool (web search) paused a long turn; sending again resumes it.
                stopReason == BetaStopReason.PAUSE_TURN -> Unit
                stopReason == BetaStopReason.MAX_TOKENS ->
                    return replyParts.joinToString("\n\n") + "\n\n(My answer was cut off — ask me to continue.)"
                else -> return replyParts.joinToString("\n\n").trim()
            }
        }
        return replyParts.joinToString("\n\n").trim() +
            "\n\n(I stopped after $maxSteps steps. Ask me to continue if needed.)"
    }

    private data class ToolOutcome(val text: String, val isError: Boolean)

    private suspend fun runTool(name: String, rawInput: Map<*, *>?, listener: AgentListener): ToolOutcome {
        val tool = toolsByName[name] ?: return ToolOutcome("Unknown tool: $name", true)
        val input = ToolInput(rawInput.orEmpty().entries.associate { (k, v) -> k.toString() to v })
        return try {
            if (tool.requiresConfirmation && !listener.confirm(tool.confirmationText(input))) {
                ToolOutcome("The user declined this action. Do not retry it unless they ask.", false)
            } else {
                ToolOutcome(tool.run(input), false)
            }
        } catch (e: Exception) {
            ToolOutcome("Error: ${e.message ?: e.javaClass.simpleName}", true)
        }
    }

    private fun rollback(size: Int) {
        while (messages.size > size) messages.removeAt(messages.lastIndex)
    }

    private fun userMessage(blocks: List<BetaContentBlockParam>): BetaMessageParam =
        BetaMessageParam.builder()
            .role(BetaMessageParam.Role.USER)
            .contentOfBetaContentBlockParams(blocks)
            .build()
}
