package com.lukas.jarvis.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lukas.jarvis.AppContainer
import com.lukas.jarvis.JarvisApp
import android.graphics.Bitmap
import com.lukas.jarvis.auto.Routine
import com.lukas.jarvis.brief.DayBrief
import com.lukas.jarvis.llm.AgentResult
import com.lukas.jarvis.maps.SavedPlace
import com.lukas.jarvis.vision.CameraBus
import com.lukas.jarvis.vision.Photo
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    val micAvailable: Boolean = true,
    /**
     * The tools the turn in progress has reached for so far, in order. Drawn as
     * a trail of chips while Jarvis works, so a slow answer shows what it is
     * waiting on instead of a spinner that could mean anything.
     */
    val activity: List<String> = emptyList(),
    /**
     * Thumbnails of the photos in this session's thread, keyed by the
     * message's creation time. Pictures are not stored with the history:
     * the words of what was seen are, and that is what a later turn needs.
     */
    val photos: Map<Long, Bitmap> = emptyMap(),
    /** The reply as it is being written, shown live before it is final. */
    val draft: String = ""
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

    private val earcon = com.lukas.jarvis.voice.Earcon()

    /** The turn in flight, so tapping the dot while it works can stop it. */
    private var turnJob: Job? = null

    private fun configureVoice(settings: Settings = settingsStore.current) {
        speaker.configure(settings.speechRate, settings.speechPitch, settings.voiceName, settings.speechLanguage)
        speech.language = settings.speechLanguage
    }

    init {
        configureVoice()
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
                if (_ui.value.stage != Stage.Speaking) _ui.value = _ui.value.copy(level = level)
            }
        }
        // While speaking, the reactor pulses with the words instead of the mic.
        viewModelScope.launch {
            speaker.level.collect { level ->
                if (_ui.value.stage == Stage.Speaking) _ui.value = _ui.value.copy(level = level)
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
            // A turn that is taking too long, or was the wrong question: stop it.
            Stage.Thinking -> cancelTurn()
            Stage.Idle -> startListening()
        }
    }

    /** Abandons the turn in flight. Whatever it already did stays done. */
    fun cancelTurn() {
        val job = turnJob ?: return
        job.cancel()
        // The first sentences may already be being spoken.
        speaker.stop()
        turnJob = null
        busy = false
        _ui.value = _ui.value.copy(stage = Stage.Idle, stageLabel = "", activity = emptyList(), draft = "")
    }

    fun startListening() {
        if (busy) return
        speaker.stop()
        _ui.value = _ui.value.copy(stage = Stage.Listening, stageLabel = "listening", error = null)
        if (settingsStore.current.earcons) earcon.listening()
        speech.start(
            onResult = { text ->
                lastTurnWasVoice = true
                if (settingsStore.current.earcons) earcon.heard()
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
        if (text.isBlank()) return
        turn(display = text) { onStage, onTool, onDraft ->
            container.agent.respond(
                utterance = text,
                settings = settingsStore.current,
                history = historyBefore(),
                onStage = onStage,
                onTool = onTool,
                onDraft = onDraft
            )
        }
    }

    /**
     * A photo and the question that goes with it.
     *
     * The picture is looked at first, by whichever model can see, and what it
     * shows is written out in words. The turn itself then runs as usual on
     * those words — so the text-only models, which are most of the pool, can
     * still log the receipt, file the date off the poster, or remember the card.
     */
    fun sendPhoto(photo: Photo, question: String? = null) {
        val asked = question?.trim().orEmpty().ifBlank {
            container.camera.pending.value?.question ?: "What is this?"
        }
        container.camera.done()
        lastTurnWasVoice = settingsStore.current.voiceMode
        speaker.stop()
        viewModelScope.launch {
            // The turn that opened the camera may still be finishing its sentence;
            // a quick shutter must not find the assistant busy and be dropped.
            var waited = 0
            while (busy && waited < 30_000) {
                kotlinx.coroutines.delay(200)
                waited += 200
            }
            photoTurn(photo, asked)
        }
    }

    private fun photoTurn(photo: Photo, asked: String) {
        val stamp = System.currentTimeMillis()
        _ui.update { it.copy(photos = it.photos + (stamp to photo.preview)) }

        turn(display = "\uD83D\uDCF7 $asked", createdAt = stamp, rewriteDisplay = true) { onStage, onTool, onDraft ->
            onStage("looking at the photo")
            onTool("take_photo")
            // The phone reads the text, codes and objects itself, offline, while
            // a vision model (if the pool has one) describes the scene. Either
            // alone is enough to answer; together the digits are exact.
            val seen = coroutineScope {
                val local = async { runCatching { container.eyes.read(photo.full) }.getOrNull() }
                val remote = async {
                    runCatching { container.agent.look(settingsStore.current, asked, photo.dataUrl) }
                }
                val reading = local.await()
                val described = remote.await()
                val words = buildString {
                    described.getOrNull()?.let { append(it.trim()) }
                    reading?.takeUnless { it.isEmpty }?.let {
                        if (isNotEmpty()) append("\n\n")
                        append(it.describe())
                    }
                }
                if (words.isBlank()) {
                    throw described.exceptionOrNull()
                        ?: LlmException("Nothing in that picture could be made out. Try again closer or in better light.")
                }
                if (described.isFailure) {
                    "(No vision model answered, so this is what the phone itself read and recognised.)\n$words"
                } else {
                    words
                }
            }
            pendingDisplay = "\uD83D\uDCF7 $asked\n\n${seen.take(900)}"
            val result = container.agent.respond(
                utterance = "[PHOTO] What the picture I just took shows:\n$seen\n\nMy question: $asked",
                settings = settingsStore.current,
                history = historyBefore(),
                onStage = onStage,
                onTool = onTool,
                onDraft = onDraft
            )
            result.copy(toolsUsed = (listOf("take_photo") + result.toolsUsed).distinct())
        }
    }

    /** The user closed the camera without taking anything. */
    fun cancelPhoto() = container.camera.done()

    val cameraRequests: StateFlow<CameraBus.Request?> = container.camera.pending

    /** Asks for the camera from a button rather than a sentence. */
    fun askCamera(question: String, fromGallery: Boolean = false) =
        container.camera.ask(question, fromGallery)

    /** Runs a routine from a tap or its notification: every step, then one spoken summary. */
    fun runRoutine(name: String) {
        val routine = container.routines.find(name) ?: run {
            fail("There is no routine called '$name' any more.")
            return
        }
        lastTurnWasVoice = false
        container.routines.markRun(routine.name)
        turn(display = "Run my ${routine.name} routine") { onStage, onTool, _ ->
            AgentResult(
                reply = container.agent.runRoutine(routine, settingsStore.current, onStage, onTool),
                effects = com.lukas.jarvis.llm.ToolEffects(true, true, true),
                toolsUsed = listOf("run_routine")
            )
        }
    }

    val routines: StateFlow<List<Routine>> = container.routines.all

    fun saveRoutine(routine: Routine) {
        container.routines.save(routine)
    }

    fun deleteRoutine(name: String) {
        container.routines.remove(name)
    }

    private fun historyBefore(): List<ChatMessage> =
        _ui.value.messages.dropLast(1).takeLast(HISTORY_TURNS)

    /** Set by a photo turn once it knows what it saw, so the thread keeps the words. */
    @Volatile
    private var pendingDisplay: String? = null

    /**
     * One exchange, whatever started it: the user's line goes up at once, the
     * work runs off the main thread with its stages and tools shown, and the
     * answer is stored, shown and spoken.
     */
    private fun turn(
        display: String,
        createdAt: Long = System.currentTimeMillis(),
        rewriteDisplay: Boolean = false,
        work: suspend (
            onStage: (String) -> Unit,
            onTool: (String) -> Unit,
            onDraft: (String) -> Unit
        ) -> AgentResult
    ) {
        if (busy) {
            // Dropping a typed message without a word looked like a broken send button.
            _ui.value = _ui.value.copy(error = "Still on the last one — tap the dot to stop it.")
            return
        }
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
        pendingDisplay = null
        val userMessage = ChatMessage(role = ChatMessage.ROLE_USER, content = display, createdAt = createdAt)
        _ui.value = _ui.value.copy(
            stage = Stage.Thinking,
            stageLabel = "thinking",
            partial = "",
            error = null,
            activity = emptyList(),
            draft = "",
            messages = _ui.value.messages + userMessage
        )

        turnJob = viewModelScope.launch {
            val self = coroutineContext[Job]
            try {
                if (!rewriteDisplay) storeUserMessage(userMessage, userMessage)

                // The agent hits SQLite and the network throughout, so the whole
                // loop runs off the main thread. Stage updates are safe from here
                // because StateFlow assignment is thread-safe.
                // Speaking starts with the first finished sentence rather than
                // after the whole reply, when the reply is going to be spoken.
                val voice = if (current.speakReplies) SpokenDraft() else null
                val result = withContext(Dispatchers.IO) {
                    work(
                        { label -> _ui.update { it.copy(stage = Stage.Thinking, stageLabel = label) } },
                        { name -> _ui.update { it.copy(activity = it.activity + name) } },
                        { words ->
                            _ui.update { it.copy(draft = words) }
                            voice?.onDraft(words)
                        }
                    )
                }

                if (rewriteDisplay) {
                    storeUserMessage(userMessage, userMessage.copy(content = pendingDisplay ?: display))
                }

                val reply = ChatMessage(
                    role = ChatMessage.ROLE_ASSISTANT,
                    content = result.reply,
                    tools = result.toolsUsed,
                    image = result.effects.images.lastOrNull()
                )
                val id = withContext(Dispatchers.IO) { brain.addMessage(reply) }
                // The stored id, not the default 0: the thread keys its rows on
                // it, and two replies both keyed 0 is a crash in a lazy list.
                _ui.value = _ui.value.copy(
                    messages = _ui.value.messages + reply.copy(id = id),
                    activity = emptyList(),
                    draft = ""
                )

                if (result.effects.any) refreshAll()

                if (current.speakReplies) {
                    _ui.value = _ui.value.copy(stage = Stage.Speaking, stageLabel = "speaking")
                    if (speaker.isStreaming && voice != null) {
                        speaker.endStream(voice.rest(result.reply))
                    } else {
                        speaker.speak(result.reply)
                    }
                } else {
                    _ui.value = _ui.value.copy(stage = Stage.Idle, stageLabel = "")
                    if (lastTurnWasVoice && current.handsFree) startListening()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Stopped on purpose; cancelTurn already put the screen back.
                throw e
            } catch (e: LlmException) {
                if (rewriteDisplay) storeUserMessage(userMessage, userMessage)
                fail(e.message ?: "The model call failed.")
            } catch (e: Exception) {
                if (rewriteDisplay) storeUserMessage(userMessage, userMessage)
                fail(e.message ?: "Something went wrong.")
            } finally {
                // A turn that was stopped may only finish unwinding after the
                // next one has started; it must not release the next one's hold.
                if (turnJob === self || turnJob == null) {
                    busy = false
                    turnJob = null
                }
            }
        }
    }

    /**
     * Feeds a reply to the speaker a sentence at a time while it is still
     * being written, and works out at the end what is left to say.
     *
     * A draft that starts over — another endpoint answering, or a round that
     * turned out to be a tool call — starts the count again; whatever was
     * already said stays said, which for "let me check the weather" is exactly
     * what a person would have heard anyway.
     */
    private inner class SpokenDraft {
        private var current = ""
        private var fed = 0

        fun onDraft(words: String) {
            if (words.isEmpty()) {
                current = ""
                fed = 0
                return
            }
            current = words
            val cut = boundary(words, fed)
            if (cut > fed) {
                speaker.feed(words.substring(fed, cut))
                fed = cut
            }
        }

        /** The part of the final reply not yet spoken. */
        fun rest(reply: String): String {
            val said = current.take(fed).trim()
            if (said.isEmpty()) return reply
            return when {
                reply.startsWith(said) -> reply.substring(said.length)
                squash(reply).startsWith(squash(said)) -> reply.drop(said.length.coerceAtMost(reply.length))
                // The final wording drifted from the draft; better to finish
                // with the whole answer than to leave half of it unsaid.
                else -> reply
            }
        }

        /** The end of the last whole sentence after [from], or [from] if there is none yet. */
        private fun boundary(text: String, from: Int): Int {
            var end = from
            var i = from
            while (i < text.length - 1) {
                val c = text[i]
                if ((c == '.' || c == '!' || c == '?' || c == '…' || c == '\n') && text[i + 1].isWhitespace()) {
                    // Short fragments ("Hi.") wait for company, so speech is not choppy.
                    if (i + 1 - from >= MIN_SPOKEN_CHUNK || end > from) end = i + 1
                }
                i++
            }
            return end
        }

        private fun squash(text: String) = text.replace(Regex("\\s+"), " ").trim()
    }

    /** Saves the user's line and swaps the on-screen copy for the stored one. */
    private suspend fun storeUserMessage(shown: ChatMessage, stored: ChatMessage) {
        val id = withContext(Dispatchers.IO) { brain.addMessage(stored) }
        _ui.update { state ->
            state.copy(messages = state.messages.map { if (it === shown) stored.copy(id = id) else it })
        }
    }

    private fun fail(message: String) {
        _ui.value = _ui.value.copy(
            stage = Stage.Idle,
            stageLabel = "",
            error = message,
            activity = emptyList(),
            draft = ""
        )
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

    fun setMapStyle(style: String) = updateSettings { it.copy(mapStyle = style) }

    val savedPlaces: StateFlow<List<SavedPlace>> = container.savedPlaces.places

    private var following: Job? = null

    /**
     * Keeps the dot live while the map is on screen. Started and stopped by
     * the map itself, so the GPS is only on while someone is looking at it.
     */
    fun followLocation(on: Boolean) {
        following?.cancel()
        following = null
        if (!on) return
        following = viewModelScope.launch {
            container.locator.updates().collect { fix ->
                container.mapStore.setFix(fix)
                nameTheStreet(fix.point)
            }
        }
    }

    private val _hereLabel = MutableStateFlow<String?>(null)

    /** The street you are on, for the card at the top of the map. */
    val hereLabel: StateFlow<String?> = _hereLabel.asStateFlow()
    private var labelledAt: com.lukas.jarvis.maps.GeoPoint? = null

    /**
     * Asks Nominatim for the street only after moving a real distance: its
     * policy is one request a second at most, and walking pace would ask ten
     * times a minute for the same road.
     */
    private fun nameTheStreet(point: com.lukas.jarvis.maps.GeoPoint) {
        val last = labelledAt
        if (last != null && com.lukas.jarvis.maps.Geo.distance(last, point) < 120) return
        labelledAt = point
        viewModelScope.launch {
            val full = withContext(Dispatchers.IO) {
                runCatching { container.places.describe(point) }.getOrNull()
            } ?: return@launch
            // "12, Bahnhofstraße, Mitte, Berlin, …" -> "Bahnhofstraße 12, Mitte"
            val parts = full.split(",").map { it.trim() }.filter { it.isNotBlank() }
            val label = if (parts.size >= 2 && parts[0].any { it.isDigit() } && parts[0].length <= 6) {
                "${parts[1]} ${parts[0]}" + (parts.getOrNull(2)?.let { ", $it" } ?: "")
            } else {
                parts.take(2).joinToString(", ")
            }
            _hereLabel.value = label
        }
    }

    /** "I parked here", from a button: the spot is saved and said back. */
    fun saveHere(name: String) {
        viewModelScope.launch {
            val message = withContext(Dispatchers.IO) {
                container.navigator.savePlace(name, note = null, useSelectedPin = false)
            }
            report(message)
        }
    }

    /** Draws the way to a saved place, from a button on the map or the day. */
    fun routeToSaved(name: String) {
        if (_routing.value) return
        if (container.savedPlaces.find(name) == null) {
            fail(
                if (name == "car") "No parked car saved yet. Tap Park when you leave it."
                else "No place called $name yet. Say \"save this as $name\" when you are there."
            )
            return
        }
        _routing.value = true
        viewModelScope.launch {
            val message = withContext(Dispatchers.IO) {
                container.navigator.routeTo(name, null, settingsStore.current)
            }
            _routing.value = false
            if (container.mapStore.current.route == null) fail(message)
        }
    }

    fun forgetSavedPlace(name: String) {
        container.savedPlaces.remove(name)
    }

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
        configureVoice()
    }

    /** The voices the phone's speech engine offers in the current language. */
    fun voices(): List<com.lukas.jarvis.voice.VoiceOption> = speaker.voices()

    /** Puts the free keyless endpoints back in the pool. */
    fun restoreFreeBrain() {
        val added = container.pool.restoreBuiltIns()
        _poolMessage.value = if (added == 0) "The free built-in AI is already in the pool."
        else "Free built-in AI restored — Jarvis works without any key again."
    }

    /** Removes one message from the thread and from history. */
    fun deleteMessage(message: ChatMessage) {
        _ui.update { state -> state.copy(messages = state.messages.filterNot { it.id == message.id && it.createdAt == message.createdAt }) }
        if (message.id > 0) {
            viewModelScope.launch { withContext(Dispatchers.IO) { brain.deleteMessage(message.id) } }
        }
    }

    /** Says a message out loud again. */
    fun speakMessage(message: ChatMessage) {
        if (busy) return
        _ui.value = _ui.value.copy(stage = Stage.Speaking, stageLabel = "speaking")
        speaker.speak(message.content) {
            viewModelScope.launch {
                if (_ui.value.stage == Stage.Speaking) _ui.value = _ui.value.copy(stage = Stage.Idle, stageLabel = "")
            }
        }
    }

    /** Sends the user's last line again, for an answer that went wrong. */
    fun retryLast() {
        val last = _ui.value.messages.lastOrNull { it.role == ChatMessage.ROLE_USER } ?: return
        if (last.content.startsWith("\uD83D\uDCF7")) return
        sendTyped(last.content)
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

    /**
     * A sentence tapped on the Skills screen: sent as if typed, and answered on
     * the assistant's own screen, where the reply and its tools can be seen.
     */
    fun trySkill(phrase: String) {
        container.stage.show(Element.Globe)
        sendTyped(phrase)
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
                configureVoice()
                // The old provider's model list and test result describe
                // settings that no longer exist.
                _availableModels.value = emptyList()
                _modelsState.value = ModelsState.Idle
                _testState.value = TestState.Idle
                "Restored ${result.keys} settings, including every saved API key."
            }
        }

    fun previewVoice() {
        configureVoice()
        val persona = com.lukas.jarvis.llm.Personas.byId(settingsStore.current.personality)
        // A preview must not look like the end of a spoken turn, which would
        // hand the microphone back in hands-free mode.
        speaker.speak(
            persona.sample.takeIf { persona.id != com.lukas.jarvis.llm.Personas.CUSTOM }
                ?: "This is how I sound. Ready when you are."
        ) { }
    }

    override fun onCleared() {
        // These are application-scoped, so quiet them down rather than
        // destroying resources the next activity will need.
        speech.cancel()
        speaker.stop()
        earcon.release()
        super.onCleared()
    }

    companion object {
        private const val HISTORY_TURNS = 20

        /** The shortest piece of a reply worth speaking on its own while the rest is written. */
        private const val MIN_SPOKEN_CHUNK = 24

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as JarvisApp
                AssistantViewModel(app.container)
            }
        }
    }
}
