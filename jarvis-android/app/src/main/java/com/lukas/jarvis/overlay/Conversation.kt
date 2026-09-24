package com.lukas.jarvis.overlay

import com.lukas.jarvis.AppContainer
import com.lukas.jarvis.data.ChatMessage
import com.lukas.jarvis.voice.Earcon
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
 * The floating dot and the wake word both need this, because both live in a
 * service outside any activity and the point of each is that the app never has
 * to come to the front. It writes to the same memory the app reads, so a
 * question answered from the home screen is in the transcript next time the
 * app is opened.
 *
 * It keeps no conversation state of its own beyond what is stored: history
 * comes from the brain on each turn, so the app and the services can never
 * hold two different ideas of what was said.
 */
class Conversation(private val container: AppContainer) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val earcon = Earcon()

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
            Stage.Idle -> listen(onProblem)
        }
    }

    /**
     * Listens for one request and answers it. [onDone] runs once the turn is
     * over however it ended — answered, silent or failed — which is what lets
     * the wake word go back to listening for its name.
     */
    fun listen(onProblem: (String) -> Unit, onDone: () -> Unit = {}) {
        if (busy) return
        val settings = container.settings.current
        if (!settings.isConfigured && container.pool.isEmpty) {
            onProblem("Open Jarvis and restore the free AI in Settings first.")
            onDone()
            return
        }

        container.speaker.stop()
        container.speech.language = settings.speechLanguage
        container.speaker.configure(settings.speechRate, settings.speechPitch, settings.voiceName, settings.speechLanguage)
        _stage.value = Stage.Listening
        if (settings.earcons) earcon.listening()
        container.speech.start(
            onResult = { text ->
                if (settings.earcons) earcon.heard()
                ask(text, onProblem, onDone)
            },
            onFailure = { message ->
                _stage.value = Stage.Idle
                // A blank message is silence, which is not worth interrupting
                // someone's home screen over.
                if (message.isNotBlank()) onProblem(message)
                onDone()
            }
        )
    }

    /** Answers a request that was already heard — "Jarvis, what's the time" in one breath. */
    fun ask(utterance: String, onProblem: (String) -> Unit, onDone: () -> Unit = {}) {
        val text = utterance.trim()
        if (text.isBlank() || busy) {
            _stage.value = Stage.Idle
            onDone()
            return
        }
        busy = true
        _stage.value = Stage.Thinking

        scope.launch {
            var spoken = false
            try {
                val settings = container.settings.current
                container.speaker.configure(settings.speechRate, settings.speechPitch, settings.voiceName, settings.speechLanguage)
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
                    content = result.reply,
                    tools = result.toolsUsed,
                    image = result.effects.images.lastOrNull()
                )
                withContext(Dispatchers.IO) { container.brain.addMessage(reply) }
                _lastReply.value = result.reply

                // Lit until the words have been said, and its own callback ends
                // the turn — never the app's hands-free hook.
                _stage.value = Stage.Speaking
                spoken = true
                container.speaker.speak(result.reply) {
                    scope.launch {
                        if (_stage.value == Stage.Speaking) _stage.value = Stage.Idle
                        onDone()
                    }
                }
            } catch (e: Exception) {
                _stage.value = Stage.Idle
                if (container.settings.current.earcons) earcon.failed()
                onProblem(e.message?.lineSequence()?.firstOrNull() ?: "That did not work.")
            } finally {
                busy = false
                if (!spoken) onDone()
            }
        }
    }

    fun cancel() {
        container.speech.cancel()
        container.speaker.stop()
        _stage.value = Stage.Idle
    }

    fun release() {
        cancel()
        earcon.release()
    }

    private companion object {
        const val HISTORY_TURNS = 12
    }
}
