package com.lukas.jarvis.overlay

import com.lukas.jarvis.AppContainer
import com.lukas.jarvis.data.ChatMessage
import com.lukas.jarvis.vm.Stage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A whole spoken turn with no screen attached: listen, answer, speak.
 *
 * The floating dot needs this because it lives in a service, outside any
 * activity, and the point of it is that tapping it does not have to open the
 * app first. It writes to the same memory the app reads, so a question answered
 * from the home screen is in the transcript next time the app is opened.
 *
 * It deliberately keeps no conversation state of its own beyond what is stored:
 * history comes from the brain on each turn, so the app and the dot can never
 * hold two different ideas of what was said.
 */
class Conversation(private val container: AppContainer) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _stage = MutableStateFlow(Stage.Idle)
    val stage: StateFlow<Stage> = _stage.asStateFlow()

    /** Straight from the recognizer, which already measures it. */
    val level: StateFlow<Float> get() = container.speech.level

    /** The last thing said, for whoever wants to show it. */
    private val _lastReply = MutableStateFlow("")
    val lastReply: StateFlow<String> = _lastReply.asStateFlow()

    private var busy = false

    /** Tap behaviour: start a turn, or abandon the one in progress. */
    fun toggle(onProblem: (String) -> Unit) {
        when (_stage.value) {
            Stage.Listening -> {
                container.speech.stop()
                _stage.value = Stage.Idle
            }
            Stage.Speaking -> {
                container.speaker.stop()
                _stage.value = Stage.Idle
            }
            Stage.Thinking -> Unit
            Stage.Idle -> start(onProblem)
        }
    }

    private fun start(onProblem: (String) -> Unit) {
        if (busy) return
        val settings = container.settings.current
        if (!settings.isConfigured && container.pool.isEmpty) {
            onProblem("Open Jarvis and add a provider first.")
            return
        }

        container.speaker.stop()
        _stage.value = Stage.Listening
        container.speech.start(
            onResult = { text -> answer(text, onProblem) },
            onFailure = { message ->
                _stage.value = Stage.Idle
                // A blank message is silence, which is not worth interrupting
                // someone's home screen over.
                if (message.isNotBlank()) onProblem(message)
            }
        )
    }

    private fun answer(utterance: String, onProblem: (String) -> Unit) {
        val text = utterance.trim()
        if (text.isBlank() || busy) {
            _stage.value = Stage.Idle
            return
        }
        busy = true
        _stage.value = Stage.Thinking

        scope.launch {
            try {
                val settings = container.settings.current
                val user = ChatMessage(role = ChatMessage.ROLE_USER, content = text)
                val history = withContext(Dispatchers.IO) {
                    container.brain.addMessage(user)
                    container.brain.recentMessages(HISTORY_TURNS)
                }

                val result = withContext(Dispatchers.IO) {
                    container.agent.respond(
                        utterance = text,
                        settings = settings,
                        // The turn just stored is dropped: the agent is handed
                        // the utterance separately and would otherwise see it
                        // twice.
                        history = history.dropLast(1),
                        onStage = { }
                    )
                }

                val reply = ChatMessage(
                    role = ChatMessage.ROLE_ASSISTANT,
                    content = result.reply
                )
                withContext(Dispatchers.IO) { container.brain.addMessage(reply) }
                _lastReply.value = result.reply

                _stage.value = Stage.Speaking
                container.speaker.speak(result.reply)
                _stage.value = Stage.Idle
            } catch (e: Exception) {
                _stage.value = Stage.Idle
                onProblem(e.message ?: "That did not work.")
            } finally {
                busy = false
            }
        }
    }

    fun cancel() {
        container.speech.cancel()
        container.speaker.stop()
        _stage.value = Stage.Idle
    }

    private companion object {
        const val HISTORY_TURNS = 12
    }
}
