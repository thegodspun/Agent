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
import com.anthropic.core.Timeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
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

/**
 * One shared HTTP client per API key, so each conversation doesn't open its own connection pool.
 * A stalled request gives up after 4 minutes and is retried once, rather than freezing the chat.
 */
fun clientFor(apiKey: String): AnthropicClient = clients.getOrPut(apiKey) {
    AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(
            Timeout.builder()
                .connect(Duration.ofSeconds(20))
                .write(Duration.ofSeconds(60))
                .read(Duration.ofMinutes(4))
                .request(Duration.ofMinutes(4))
                .build()
        )
        .maxRetries(1)
        .build()
}

/** Abstraction over the Messages API call so the loop can be tested without a network. */
fun interface MessageSender {
    suspend fun send(params: MessageCreateParams): BetaMessage
}

/**
 * Thrown when a request fails after some actions (calls, texts, taps) already happened. Those
 * steps stay in the conversation so Claude knows they're done and doesn't repeat them.
 */
class ActionsCompletedException(val actions: List<String>, cause: Exception) :
    Exception("Failed after completing: ${actions.joinToString()}", cause)

/**
 * One conversation with Claude. The system prompt and tool list are frozen when the
 * conversation starts and the message history is only ever appended to, which keeps
 * the prompt cache warm and keeps Claude's earlier reasoning valid between turns.
 */
class Conversation(
    private val config: AgentConfig,
    systemPrompt: String,
    tools: List<AgentTool>,
    // The async client makes the request cancellable: cancelling the coroutine cancels the HTTP call.
    private val sender: MessageSender = clientFor(config.apiKey).let { client ->
        MessageSender { client.async().beta().messages().create(it).await() }
    },
    private val maxSteps: Int = 25,
) {
    private val toolsByName = tools.associateBy { it.name }
    private val messages = mutableListOf<BetaMessageParam>()

    val agentConfig: AgentConfig get() = config

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
        // Also cache the growing history, so each step of a tool loop doesn't pay for it again.
        .cacheControl(BetaCacheControlEphemeral.builder().build())
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
     * Sends [userText], runs any tools Claude asks for, and returns Claude's final reply, or
     * [refusalReply] if the request is declined. On failure the history is rolled back to the
     * last completed step so the conversation stays usable.
     */
    suspend fun send(
        userText: String,
        listener: AgentListener = object : AgentListener {},
        refusalReply: String = "Sorry, I can't help with that request.",
    ): String = withContext(Dispatchers.IO) {
        val state = LoopState(checkpoint = messages.size)
        try {
            messages += userMessage(listOf(BetaContentBlockParam.ofText(userText.sanitizeUnicode())))
            runLoop(listener, state) ?: run {
                rollback(state.safePoint)
                refusalReply
            }
        } catch (e: CancellationException) {
            rollback(state.safePoint)
            throw e
        } catch (e: Exception) {
            rollback(state.safePoint)
            if (state.actions.isNotEmpty()) throw ActionsCompletedException(state.actions.toList(), e)
            throw e
        }
    }

    private class LoopState(checkpoint: Int) {
        /** History up to here is complete and valid: either the start, or right after a set of tool results. */
        var safePoint = checkpoint
        /** Approved actions (calls, texts, taps) that already happened in this request. */
        val actions = mutableListOf<String>()
    }

    /** Returns the final text, or null if the request was declined. */
    private suspend fun runLoop(listener: AgentListener, state: LoopState): String? {
        val replyParts = mutableListOf<String>()
        for (step in 0 until maxSteps) {
            currentCoroutineContext().ensureActive()
            val response = sender.send(baseParams.messages(messages.toList()).build())
            val stopReason = response.stopReason().orElse(null)
            if (stopReason == BetaStopReason.REFUSAL) return null

            messages += response.toParam()
            response.content().forEach { block -> block.text().ifPresent { replyParts += it.text() } }

            val toolUses = response.content().mapNotNull { it.toolUse().orElse(null) }
            when {
                // Cut off mid-call: the arguments may be incomplete, so don't run anything.
                stopReason == BetaStopReason.MAX_TOKENS && toolUses.isNotEmpty() -> {
                    messages += userMessage(toolUses.map { use ->
                        toolResult(use.id(), "Not run: your output was cut off by the length limit. Make the call again with shorter input.", true)
                    })
                    state.safePoint = messages.size
                    replyParts.clear()
                }
                toolUses.isNotEmpty() -> {
                    val results = toolUses.map { use ->
                        currentCoroutineContext().ensureActive()
                        listener.onToolStarted(use.name())
                        val outcome = runTool(use.name(), use._input().convert(Map::class.java), listener)
                        if (outcome.action) state.actions += use.name()
                        toolResult(use.id(), outcome.text, outcome.isError)
                    }
                    // Every result goes back in a single message.
                    messages += userMessage(results)
                    state.safePoint = messages.size
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

    private fun toolResult(id: String, text: String, isError: Boolean): BetaContentBlockParam =
        BetaContentBlockParam.ofToolResult(
            BetaToolResultBlockParam.builder()
                .toolUseId(id)
                .content(text.sanitizeUnicode())
                .isError(isError)
                .build()
        )

    /** [action] is true when an approved real-world action (call, text, tap…) ran. */
    private data class ToolOutcome(val text: String, val isError: Boolean, val action: Boolean = false)

    private suspend fun runTool(name: String, rawInput: Map<*, *>?, listener: AgentListener): ToolOutcome {
        val tool = toolsByName[name] ?: return ToolOutcome("Unknown tool: $name", true)
        val input = ToolInput(rawInput.orEmpty().entries.associate { (k, v) -> k.toString() to v })
        return try {
            if (tool.requiresConfirmation && !listener.confirm(tool.confirmationText(input))) {
                ToolOutcome("The user declined this action. Do not retry it unless they ask.", false)
            } else {
                ToolOutcome(tool.run(input), false, action = tool.requiresConfirmation)
            }
        } catch (e: CancellationException) {
            throw e
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
