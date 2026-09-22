package com.lukas.jarvis.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lukas.jarvis.AppContainer
import com.lukas.jarvis.JarvisApp
import com.lukas.jarvis.brief.DayBrief
import com.lukas.jarvis.core.Settings
import com.lukas.jarvis.core.Vault
import com.lukas.jarvis.stage.Element
import com.lukas.jarvis.stage.StageStore
import com.lukas.jarvis.core.SettingsStore
import com.lukas.jarvis.data.ChatMessage
import com.lukas.jarvis.data.Entry
import com.lukas.jarvis.data.Memory
import com.lukas.jarvis.data.Task
import com.lukas.jarvis.data.Tracker
import com.lukas.jarvis.data.TrackerStatus
import com.lukas.jarvis.llm.ConnectionTest
import com.lukas.jarvis.llm.LlmException
import com.lukas.jarvis.llm.PoolEntry
import com.lukas.jarvis.llm.Providers
import com.lukas.jarvis.maps.MapState
import com.lukas.jarvis.maps.TileCache
import com.lukas.jarvis.voice.SpeechInput
import com.lukas.jarvis.voice.Speaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Stage { Idle, Listening, Thinking, Speaking }

/** State of "ask the provider which models it really has". */
sealed interface ModelsState {
    data object Idle : ModelsState
    data object Loading : ModelsState
    data class Loaded(val count: Int) : ModelsState
    data class Failed(val message: String) : ModelsState
}

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Passed(
        val reply: String,
        val toolsWork: Boolean,
        val diagnostics: String
    ) : TestState

    /** [diagnostics] is the raw exchange, so a failure can be reported verbatim. */
    data class Failed(val message: String, val diagnostics: String) : TestState
}

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

    private val _availableModels = MutableStateFlow<List<String>>(emptyList())
    val availableModels: StateFlow<List<String>> = _availableModels.asStateFlow()

    private val _modelsState = MutableStateFlow<ModelsState>(ModelsState.Idle)
    val modelsState: StateFlow<ModelsState> = _modelsState.asStateFlow()

    private val _testState = MutableStateFlow<TestState>(TestState.Idle)
    val testState: StateFlow<TestState> = _testState.asStateFlow()

    val poolEntries: StateFlow<List<PoolEntry>> = container.pool.entries
    val lastUsedEndpoint: StateFlow<String?> = container.pool.lastUsed

    val map: StateFlow<MapState> = container.mapStore.state
    val tiles: TileCache get() = container.tiles

    /** The day, as both the dashboard and the spoken brief see it. */
    private val _brief = MutableStateFlow<DayBrief?>(null)
    val brief: StateFlow<DayBrief?> = _brief.asStateFlow()

    private val _briefLoading = MutableStateFlow(false)
    val briefLoading: StateFlow<Boolean> = _briefLoading.asStateFlow()

    /** True while a pin tapped by hand is being routed to. */
    private val _routing = MutableStateFlow(false)
    val routing: StateFlow<Boolean> = _routing.asStateFlow()

    private val _poolBusy = MutableStateFlow(false)
    val poolBusy: StateFlow<Boolean> = _poolBusy.asStateFlow()

    private val _poolMessage = MutableStateFlow<String?>(null)
    val poolMessage: StateFlow<String?> = _poolMessage.asStateFlow()

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
        // A configured pool is enough on its own; the single-provider settings
        // are only the fallback when no pool exists.
        if (!current.isConfigured && container.pool.isEmpty) {
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

    /**
     * Gathers the day.
     *
     * Two of the four sources are network calls, so this is never on the path of
     * anything else: the dashboard asks for it when it opens, and a turn that
     * changed a task refreshes it only if it was already on screen.
     */
    fun refreshBrief() {
        if (_briefLoading.value) return
        _briefLoading.value = true
        viewModelScope.launch {
            val day = runCatching {
                withContext(Dispatchers.IO) { container.briefer.build(settingsStore.current) }
            }.getOrNull()
            if (day != null) _brief.value = day
            _briefLoading.value = false
        }
    }

    fun refreshAll() {
        // A brief already on screen is stale the moment a task or a balance
        // moves, and nothing else would tell it.
        if (_brief.value != null) refreshBrief()
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

    // -------------------------------------------------------------------- maps

    fun selectPlace(index: Int) = container.mapStore.select(index)

    /**
     * The same routing the model does, from a tap instead of a sentence. The
     * navigator's prose only surfaces when nothing was drawn, because otherwise
     * the map itself is the answer.
     */
    fun routeToPlace(index: Int) {
        if (_routing.value) return
        val place = container.mapStore.current.places.getOrNull(index) ?: return
        container.mapStore.select(index)
        _routing.value = true
        viewModelScope.launch {
            val message = withContext(Dispatchers.IO) {
                container.navigator.routeTo(
                    destination = place.name,
                    mode = null,
                    settings = settingsStore.current
                )
            }
            _routing.value = false
            if (container.mapStore.current.route == null) fail(message)
        }
    }

    fun navigateToPlace(index: Int) {
        val place = container.mapStore.current.places.getOrNull(index) ?: return
        container.mapStore.select(index)
        val opened = container.mapStore.openExternalNavigation(
            destination = place.point,
            label = place.name,
            mode = settingsStore.current.travelMode
        )
        if (!opened) fail("No maps app on this phone would take the directions.")
    }

    fun setTravelMode(mode: String) = updateSettings { it.copy(travelMode = mode) }

    fun clearMap() = container.mapStore.clear()

    // ---------------------------------------------------------------- settings

    /**
     * Pulls the live model list. Hardcoded ids go stale — this is how the app
     * recovers without shipping an update.
     */
    fun refreshModels() {
        if (_modelsState.value == ModelsState.Loading) return
        _modelsState.value = ModelsState.Loading
        viewModelScope.launch {
            try {
                val models = container.models.fetch(settingsStore.current)
                _availableModels.value = models
                _modelsState.value = ModelsState.Loaded(models.size)
                // A model that no longer exists would fail on the next message,
                // so move to a real one now rather than at the worst moment.
                if (settingsStore.current.model !in models && models.isNotEmpty()) {
                    settingsStore.update { it.copy(model = models.first()) }
                }
            } catch (e: Exception) {
                _modelsState.value = ModelsState.Failed(e.message ?: "Could not list models.")
            }
        }
    }

    fun testConnection() {
        if (_testState.value == TestState.Running) return
        _testState.value = TestState.Running
        viewModelScope.launch {
            _testState.value = when (val result = container.connectionTest.run(settingsStore.current)) {
                is ConnectionTest.Result.Ok ->
                    TestState.Passed(result.reply, result.toolsWork, result.diagnostics)
                is ConnectionTest.Result.Failed ->
                    TestState.Failed(result.message, result.diagnostics)
            }
        }
    }

    // -------------------------------------------------------------- model pool

    /**
     * Enrols several of the current provider's models at once. Free tiers cap
     * per model as well as per account, so holding a spread of models on one key
     * is what keeps the assistant answering after the first one runs dry.
     */
    fun addCurrentProviderToPool() {
        if (_poolBusy.value) return
        val current = settingsStore.current
        _poolBusy.value = true
        _poolMessage.value = null
        viewModelScope.launch {
            val result = container.poolBuilder.expand(
                providerId = current.providerId,
                baseUrl = current.baseUrl,
                apiKey = current.apiKey,
                pool = container.pool
            )
            _poolMessage.value = result.message
            _poolBusy.value = false
        }
    }

    /**
     * Enrols every provider that already has a key saved.
     *
     * Spreading across accounts is the only thing a daily cap cannot follow you
     * to, so this is the button that actually makes the pool reliable — one tap
     * instead of a dozen trips through the provider picker.
     */
    fun addEverySavedProviderToPool() {
        if (_poolBusy.value) return
        _poolBusy.value = true
        _poolMessage.value = null
        viewModelScope.launch {
            val result = container.poolBuilder.expandAll(
                saved = settingsStore.savedProviders(),
                pool = container.pool,
                onProgress = { _poolMessage.value = it }
            )
            _poolMessage.value = result.message
            _poolBusy.value = false
        }
    }

    /** One line of "how much of the pool can actually answer right now". */
    fun poolSummary(): String = container.pool.summary()

    fun removeFromPool(id: String) = container.pool.remove(id)

    fun setPoolEntryEnabled(id: String, enabled: Boolean) =
        container.pool.setEnabled(id, enabled)

    fun clearPool() {
        container.pool.clear()
        _poolMessage.value = null
    }

    /** Clears every cooldown, for when limits have reset or a key was fixed. */
    fun wakePool() {
        container.pool.wakeAll()
        _poolMessage.value = "All endpoints woken."
    }

    fun dismissPoolMessage() {
        _poolMessage.value = null
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        settingsStore.update(transform)
        val next = settingsStore.current
        speaker.configure(next.speechRate, next.speechPitch)
    }

    fun switchProvider(providerId: String) {
        settingsStore.switchProvider(providerId)
        // The old provider's models mean nothing here.
        _availableModels.value = emptyList()
        _modelsState.value = ModelsState.Idle
        _testState.value = TestState.Idle
        if (settingsStore.current.let { Providers.byId(it.providerId).needsKey.not() || it.apiKey.isNotBlank() }) {
            refreshModels()
        }
    }

    // -------------------------------------------------------------- elements

    /** Which element is on the stage, written by the user and by the assistant. */
    val element: StateFlow<StageStore.State> = container.stage.state

    fun showElement(element: Element) {
        container.stage.show(element)
    }

    // ----------------------------------------------------------- the phone

    fun playMusic() = report(container.phone.play(null))
    fun pauseMusic() = report(container.phone.pause())
    fun nextTrack() = report(container.phone.next())
    fun previousTrack() = report(container.phone.previous())
    fun setMediaVolume(percent: Int) = report(container.phone.setVolume(percent))

    private val _bluetooth = MutableStateFlow("")
    val bluetooth: StateFlow<String> = _bluetooth.asStateFlow()

    fun refreshBluetooth() {
        _bluetooth.value = container.phone.bluetoothDevices()
    }

    /** Battery, network, storage and ringer, as one line the screen can show. */
    private val _deviceStatus = MutableStateFlow("")
    val deviceStatus: StateFlow<String> = _deviceStatus.asStateFlow()

    fun refreshDeviceStatus() {
        _deviceStatus.value = container.device.status()
    }

    fun setTorch(on: Boolean) = report(container.device.torch(on))

    fun openBluetoothSettings() = report(container.phone.openBluetoothSettings())

    /**
     * Phone actions answer in a sentence whether or not they worked, and a
     * control that silently does nothing is the thing worth avoiding here, so
     * the sentence is shown rather than dropped.
     */
    private fun report(message: String) {
        _poolMessage.value = message
    }

    // --------------------------------------------------------------- backup

    /** The backup file's contents, for the caller to write wherever it likes. */
    fun exportBackup(): String = Vault.export(container.app)

    /**
     * Puts a backup back, then rebuilds everything that had read the old values.
     *
     * Both stores cache their contents in memory, so a restore that only wrote
     * to disk would appear to do nothing until the next launch.
     */
    fun restoreBackup(text: String): String =
        when (val result = Vault.import(container.app, text)) {
            is Vault.Result.Failed -> result.reason
            is Vault.Result.Restored -> {
                settingsStore.reload()
                container.pool.reload()
                val next = settingsStore.current
                speaker.configure(next.speechRate, next.speechPitch)
                // The old provider's model list and test result describe
                // settings that no longer exist.
                _availableModels.value = emptyList()
                _modelsState.value = ModelsState.Idle
                _testState.value = TestState.Idle
                "Restored ${result.keys} settings, including every saved API key."
            }
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
