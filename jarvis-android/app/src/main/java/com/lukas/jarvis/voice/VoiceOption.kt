package com.lukas.jarvis.voice

/** One voice the engine offers, as the settings screen lists it. */
data class VoiceOption(
    val name: String,
    val label: String,
    val language: String,
    val network: Boolean,
    val quality: Int
)
