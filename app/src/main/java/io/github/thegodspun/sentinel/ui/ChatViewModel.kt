package io.github.thegodspun.sentinel.ui

import android.app.Application
import android.speech.tts.TextToSpeech
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import io.github.thegodspun.sentinel.app
import io.github.thegodspun.sentinel.core.ActionsCompletedException
import io.github.thegodspun.sentinel.core.AgentListener
import io.github.thegodspun.sentinel.core.Conversation
import io.github.thegodspun.sentinel.core.HabitProfile
import io.github.thegodspun.sentinel.core.Prompts
import io.github.thegodspun.sentinel.core.coreTools
import io.github.thegodspun.sentinel.core.describeNow
import io.github.thegodspun.sentinel.core.habitTools
import io.github.thegodspun.sentinel.service.ApprovalCenter
import io.github.thegodspun.sentinel.tools.phoneTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

data class ChatLine(val fromUser: Boolean, val text: String, val id: Long = nextLineId++)

private var nextLineId = 0L

/** A pending "Allow this?" question from a tool that acts on the outside world. */
data class Confirmation(val question: String, val answer: CompletableDeferred<Boolean>)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.app

    private val _lines = MutableStateFlow<List<ChatLine>>(emptyList())
    val lines: StateFlow<List<ChatLine>> = _lines.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _confirmation = MutableStateFlow<Confirmation?>(null)
    val confirmation: StateFlow<Confirmation?> = _confirmation.asStateFlow()

    private var conversation: Conversation? = null
    private var job: Job? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        tts = TextToSpeech(application) { ttsReady = it == TextToSpeech.SUCCESS }
    }

    private val listener = object : AgentListener {
        override fun onToolStarted(toolName: String) {
            _status.value = TOOL_STATUS[toolName] ?: "Working ($toolName)…"
        }

        override suspend fun confirm(question: String): Boolean {
            val pending = Confirmation(question, CompletableDeferred())
            _confirmation.value = pending
            return try {
                ApprovalCenter.ask(getApplication(), question, pending.answer)
            } finally {
                _confirmation.value = null
            }
        }
    }

    fun send(text: String) {
        if (text.isBlank()) return
        if (_status.value != null) {
            // Don't drop a request silently (e.g. a voice request from the Talk button).
            val waiting = if (_confirmation.value != null) {
                "I'm waiting for you to allow or decline the action I asked about. Answer that first, then ask again."
            } else {
                "I'm still working on your last request. Ask again in a moment."
            }
            _lines.update { it + ChatLine(true, text) + ChatLine(false, waiting) }
            return
        }
        if (app.settings.apiKey.isBlank()) {
            _lines.update { it + ChatLine(false, "Add your Claude API key in the Setup tab first.") }
            return
        }
        _lines.update { it + ChatLine(true, text) }
        _status.value = "Thinking…"
        job = viewModelScope.launch {
            val reply = try {
                // A changed API key, model or effort in Setup takes effect right away.
                val config = app.settings.agentConfig()
                val convo = conversation?.takeIf { it.agentConfig == config } ?: newConversation().also { conversation = it }
                convo.send("[${describeNow(LocalDateTime.now())}]\n$text", listener)
            } catch (e: CancellationException) {
                "Stopped."
            } catch (e: ActionsCompletedException) {
                "I lost the connection partway through, but some steps already happened: " +
                    e.actions.joinToString { it.replace('_', ' ') } +
                    ". Check those before asking me to do them again."
            } catch (e: UnauthorizedException) {
                conversation = null
                "Your API key was rejected. Check it in the Setup tab."
            } catch (e: RateLimitException) {
                "Claude is rate-limiting this API key right now. Wait a minute and try again."
            } catch (e: AnthropicServiceException) {
                "The Claude service returned an error (${e.statusCode()}). Try again in a moment."
            } catch (e: AnthropicIoException) {
                "I couldn't reach the internet. Check your connection and try again."
            } catch (e: Exception) {
                "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
            }
            _status.value = null
            job = null
            _lines.update { it + ChatLine(false, reply.ifBlank { "Done." }) }
            if (app.settings.speakReplies) speak(reply)
        }
    }

    /** Stops the request in progress, including any open network call. */
    fun cancel() {
        _confirmation.value?.answer?.complete(false)
        job?.cancel()
    }

    /** Fills the chat with sample lines, for screenshot tests. */
    @VisibleForTesting
    internal fun showPreview(sample: List<ChatLine>, status: String?) {
        _lines.value = sample
        _status.value = status
    }

    fun answerConfirmation(allow: Boolean) {
        _confirmation.value?.answer?.complete(allow)
    }

    /** Starts over with a fresh conversation; tracker items and saved facts are kept. */
    fun newChat() {
        if (_status.value != null) return
        conversation = null
        _lines.value = emptyList()
        tts?.stop()
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        tts?.speak(text.replace(Regex("[*#_`]"), ""), TextToSpeech.QUEUE_FLUSH, null, "reply")
    }

    private fun newConversation(): Conversation {
        val settings = app.settings
        return Conversation(
            config = settings.agentConfig(),
            systemPrompt = Prompts.chat(
                aboutMe = settings.aboutMe,
                facts = app.notes.all(),
                habitProfile = if (settings.habitLearning) {
                    HabitProfile.describe(app.habits.data(), LocalDate.now())
                } else {
                    "(habit learning is off)"
                },
                now = describeNow(LocalDateTime.now()),
            ),
            tools = coreTools(app.tracker, app.notes) { LocalDateTime.now() } +
                habitTools(app.habits) { LocalDateTime.now() } +
                phoneTools(getApplication<Application>()),
        )
    }

    override fun onCleared() {
        tts?.shutdown()
        super.onCleared()
    }

    companion object {
        private val TOOL_STATUS = mapOf(
            "tracker_add" to "Saving to your tracker…",
            "tracker_list" to "Checking your tracker…",
            "tracker_update" to "Updating your tracker…",
            "remember" to "Remembering that…",
            "read_screen" to "Reading the screen…",
            "open_app" to "Opening the app…",
            "calendar_upcoming" to "Checking your calendar…",
            "habit_report" to "Looking at your habits…",
            "save_habit_insight" to "Noting that…",
        )
    }
}
