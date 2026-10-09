package com.lukas.jarvis.vm

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

/** What a restore said, and whether it is waiting on the backup's passphrase. */
data class RestoreOutcome(val said: String, val needsPassphrase: Boolean = false)
