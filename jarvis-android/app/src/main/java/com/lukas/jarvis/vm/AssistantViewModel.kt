package com.lukas.jarvis.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lukas.jarvis.AppContainer
import com.lukas.jarvis.JarvisApp
import com.lukas.jarvis.core.Settings
import com.lukas.jarvis.core.SettingsStore
import com.lukas.jarvis.data.ChatMessage
import com.lukas.jarvis.data.Entry
import com.lukas.jarvis.data.Memory
import com.lukas.jarvis.data.Task
import com.lukas.jarvis.data.Tracker
import com.lukas.jarvis.data.TrackerStatus
import com.lukas.jarvis.llm.LlmException
import com.lukas.jarvis.voice.SpeechInput
import com.lukas.jarvis.voice.Speaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Stage { Idle, Listening, Thinking, Speaking }

data class AssistantUiState(
    val stage: Stage = Stage.Idle,
    val stageLabel: String = "",
    val partial: String = "",
    val level: Float = 0f,
    val messages: List<ChatMessage> = emptyList(),
    val error: String? = null,
    val micAvailable: Boolean = true
)

class AssistantViewModel(
    private val container: AppContainer
) : ViewModel() {

    private val speech: SpeechInput = container.speech
    private val speaker: Speaker = container.speaker
    private val brain = container.brain
    private val settingsStore: SettingsStore = container.settings

    private val _ui = MutableStateFlow(AssistantUiState())
    val ui: StateFlow<AssistantUiState> = _ui.asStateFlow()

    val settings: StateFlow<Settings> = settingsStore.state

    private val _memories = MutableStateFlow<List<Memory>>(emptyList())
    val memories: StateFlow<List<Memory>> = _memories.asStateFlow()

    private val _trackers = MutableStateFlow<List<TrackerStatus>>(emptyList())
    val trackers: StateFlow<List<TrackerStatus>> = _trackers.asStateFlow()

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** Only a turn that started with the mic should hand the mic back afterwards. */
    private var lastTurnWasVoice = false
    private var busy = false

    init {
        speaker.configure(settingsStore.current.speechRate, settingsStore.current.speechPitch)
        speaker.onFinished = {
            // This fires on a binder thread. SpeechRecognizer may only be touched
            // from the main thread, so hop back before restarting the mic.
            viewModelScope.launch {
                if (_ui.value.stage == Stage.Speaking) {
                    _ui.value = _ui.value.copy(stage = Stage.Idle, stageLabel = "")
                }
                if (lastTurnWasVoice && settingsStore.current.handsFree) {
                    startListening()
                }
            }
        }

        viewModelScope.launch {
            speech.partial.collect { text ->
                _ui.value = _ui.value.copy(partial = text)
            }
        }
        viewModelScope.launch {
            speech.level.collect { level ->
                _ui.value = _ui.value.copy(level = level)
            }
        }

        _ui.value = _ui.value.copy(micAvailable = speech.available)
        viewModelScope.launch {
            val history = withContext(Dispatchers.IO) { brain.recentMessages(40) }
            _ui.value = _ui.value.copy(messages = history)
            refreshAll()
        }
    }

    // ------------------------------------------------------------------- voice

    fun toggleListening() {
        when (_ui.value.stage) {
            Stage.Listening -> stopListening()
            Stage.Speaking -> {
                speaker.stop()
                _ui.value = _ui.value.copy(stage = Stage.Idle, stageLabel = "")
            }
            Stage.Thinking -> Unit
            Stage.Idle -> startListening()
        }
    }

    fun startListening() {
        if (busy) return
        speaker.stop()
        _ui.value = _ui.value.copy(stage = Stage.Listening, stageLabel = "listening", error = null)
        speech.start(
            onResult = { text ->
                lastTurnWasVoice = true
                send(text)
            },
            onFailure = { message ->
                _ui.value = _ui.value.copy(
                    stage = Stage.Idle,
                    stageLabel = "",
                    partial = "",
                    // An empty message means plain silence, which is not an error.
                    error = message.takeIf { it.isNotBlank() }
                )
            }
        )
    }

    fun stopListening() {
        speech.stop()
        _ui.value = _ui.value.copy(stage = Stage.Idle, stageLabel = "")
    }

    fun stopSpeaking() {
        speaker.stop()
        _ui.value = _ui.value.copy(stage = Stage.Idle, stageLabel = "")
    }

    // -------------------------------------------------------------------- turn

    fun sendTyped(text: String) {
        lastTurnWasVoice = false
        send(text)
    }

    private fun send(rawText: String) {
        val text = rawText.trim()
        if (text.isBlank() || busy) return
        val current = settingsStore.current
        if (!current.isConfigured) {
            _ui.value = _ui.value.copy(
                stage = Stage.Idle,
                error = "Add a provider and API key in Settings first."
            )
            return
        }

        busy = true
        val userMessage = ChatMessage(role = ChatMessage.ROLE_USER, content = text)
        _ui.value = _ui.value.copy(
            stage = Stage.Thinking,
            stageLabel = "thinking",
            partial = "",
            error = null,
            messages = _ui.value.messages + userMessage
        )

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { brain.addMessage(userMessage) }
                val history = _ui.value.messages.dropLast(1).takeLast(HISTORY_TURNS)

                // The agent hits SQLite and the network throughout, so the whole
                // loop runs off the main thread. Stage updates are safe from here
                // because StateFlow assignment is thread-safe.
                val result = withContext(Dispatchers.IO) {
                    container.agent.respond(
                        utterance = text,
                        settings = current,
                        history = history,
                        onStage = { label ->
                            _ui.value = _ui.value.copy(stage = Stage.Thinking, stageLabel = label)
                        }
                    )
                }

                val reply = ChatMessage(role = ChatMessage.ROLE_ASSISTANT, content = result.reply)
                withContext(Dispatchers.IO) { brain.addMessage(reply) }
                _ui.value = _ui.value.copy(messages = _ui.value.messages + reply)

                if (result.effects.any) refreshAll()

                if (current.speakReplies) {
                    _ui.value = _ui.value.copy(stage = Stage.Speaking, stageLabel = "speaking")
                    speaker.speak(result.reply)
                } else {
                    _ui.value = _ui.value.copy(stage = Stage.Idle, stageLabel = "")
                    if (lastTurnWasVoice && current.handsFree) startListening()
                }
            } catch (e: LlmException) {
                fail(e.message ?: "The model call failed.")
            } catch (e: Exception) {
                fail(e.message ?: "Something went wrong.")
            } finally {
                busy = false
            }
        }
    }

    private fun fail(message: String) {
        _ui.value = _ui.value.copy(stage = Stage.Idle, stageLabel = "", error = message)
    }

    fun dismissError() {
        _ui.value = _ui.value.copy(error = null)
    }

    fun clearConversation() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { brain.clearMessages() }
            _ui.value = _ui.value.copy(messages = emptyList())
        }
    }

    // -------------------------------------------------------------------- data

    fun refreshAll() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                Snapshot(
                    memories = brain.recentMemories(200),
                    trackers = brain.allTrackerStatus(),
                    tasks = brain.tasks(includeDone = true, limit = 200),
                    entries = brain.recentEntries(null, 100)
                )
            }
            _memories.value = snapshot.memories
            _trackers.value = snapshot.trackers
            _tasks.value = snapshot.tasks
            _entries.value = snapshot.entries
        }
    }

    private data class Snapshot(
        val memories: List<Memory>,
        val trackers: List<TrackerStatus>,
        val tasks: List<Task>,
        val entries: List<Entry>
    )

    fun addMemory(content: String, kind: String, tags: List<String>, importance: Int) {
        if (content.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                brain.addMemory(
                    Memory(
                        kind = kind,
                        content = content.trim(),
                        tags = tags,
                        importance = importance,
                        source = "manual"
                    )
                )
            }
            refreshAll()
        }
    }

    fun deleteMemory(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { brain.deleteMemory(id) }
            refreshAll()
        }
    }

    fun togglePin(memory: Memory) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { brain.updateMemory(memory.copy(pinned = !memory.pinned)) }
            refreshAll()
        }
    }

    fun saveTracker(tracker: Tracker) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { brain.upsertTracker(tracker) }
            refreshAll()
        }
    }

    fun deleteTracker(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { brain.deleteTracker(id) }
            refreshAll()
        }
    }

    fun addEntry(trackerId: Long, amount: Double, direction: String, note: String?) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                brain.addEntry(
                    Entry(
                        trackerId = trackerId,
                        amount = amount,
                        direction = direction,
                        note = note?.takeIf { it.isNotBlank() }
                    )
                )
            }
            refreshAll()
        }
    }

    fun deleteEntry(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { brain.deleteEntry(id) }
            refreshAll()
        }
    }

    fun addTask(title: String, dueAt: Long?, repeatRule: String, notes: String?) {
        if (title.isBlank()) return
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val task = Task(
                    title = title.trim(),
                    notes = notes?.takeIf { it.isNotBlank() },
                    dueAt = dueAt,
                    repeatRule = repeatRule
                )
                task.copy(id = brain.addTask(task))
            }
            if (saved.dueAt != null) container.reminders.schedule(saved)
            refreshAll()
        }
    }

    fun toggleTask(task: Task) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val flipped = task.copy(
                    done = !task.done,
                    completedAt = if (!task.done) System.currentTimeMillis() else null
                )
                brain.updateTask(flipped)
                if (flipped.done) {
                    container.reminders.cancel(flipped.id)
                } else if (flipped.dueAt != null) {
                    container.reminders.schedule(flipped)
                }
            }
            refreshAll()
        }
    }

    fun deleteTask(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { brain.deleteTask(id) }
            container.reminders.cancel(id)
            refreshAll()
        }
    }

    // ---------------------------------------------------------------- settings

    fun updateSettings(transform: (Settings) -> Settings) {
        settingsStore.update(transform)
        val next = settingsStore.current
        speaker.configure(next.speechRate, next.speechPitch)
    }

    fun switchProvider(providerId: String) {
        settingsStore.switchProvider(providerId)
    }

    fun previewVoice() {
        speaker.configure(settingsStore.current.speechRate, settingsStore.current.speechPitch)
        speaker.speak("This is how I sound. Ready when you are.")
    }

    override fun onCleared() {
        // These are application-scoped, so quiet them down rather than
        // destroying resources the next activity will need.
        speech.cancel()
        speaker.stop()
        super.onCleared()
    }

    companion object {
        private const val HISTORY_TURNS = 20

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as JarvisApp
                AssistantViewModel(app.container)
            }
        }
    }
}
