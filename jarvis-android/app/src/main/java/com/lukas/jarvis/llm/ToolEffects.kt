package com.lukas.jarvis.llm

/** What changed as a result of a turn, so the UI knows which tabs to refresh. */
data class ToolEffects(
    var memoriesChanged: Boolean = false,
    var trackersChanged: Boolean = false,
    var tasksChanged: Boolean = false,
    /** Pictures drawn this turn, as file paths, to be shown with the reply. */
    val images: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf()),
    /** Every tool that ran or is waiting, with what it was asked and what it said, for the canvas. */
    val outputs: MutableList<ToolOutput> = java.util.Collections.synchronizedList(mutableListOf())
) {
    /** Actions this turn left waiting on the person's yes. */
    val waiting: List<PendingAction> get() = synchronized(outputs) { outputs.mapNotNull { it.waiting } }

    val any: Boolean get() = memoriesChanged || trackersChanged || tasksChanged
}
