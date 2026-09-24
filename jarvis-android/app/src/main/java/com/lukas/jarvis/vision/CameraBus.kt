package com.lukas.jarvis.vision

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How a tool asks for the camera.
 *
 * A tool runs in the middle of a turn, far from any activity, and taking a
 * picture needs one. So the tool leaves a request here and ends its turn; the
 * activity sees it, opens the camera, and the photo comes back as the next
 * message with the question still attached. "What is this?" is then one
 * sentence and one shutter press.
 */
class CameraBus {

    data class Request(
        val id: Long,
        /** What to ask about the picture once it is taken. */
        val question: String,
        /** True when the gallery is wanted instead of the camera. */
        val fromGallery: Boolean = false
    )

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    fun ask(question: String, fromGallery: Boolean = false) {
        _pending.value = Request(System.currentTimeMillis(), question, fromGallery)
    }

    /** The request has been acted on (or abandoned); forget it. */
    fun done() {
        _pending.value = null
    }
}
