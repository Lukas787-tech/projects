package com.lukas.jarvis.llm

/**
 * Every backend Jarvis talks to speaks the OpenAI chat-completions dialect, so
 * one client covers all of them. Swapping provider is just a base URL, a key
 * and a model name — which is also how the PythonAnywhere relay will slot in
 * later without touching the client at all.
 */
data class ProviderPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val models: List<String>,
    val needsKey: Boolean,
    val keyUrl: String?,
    val note: String
) {
    val defaultModel: String get() = models.first()
}

object Providers {

    const val GROQ = "groq"
    const val GEMINI = "gemini"
    const val OPENROUTER = "openrouter"
    const val CEREBRAS = "cerebras"
    const val OLLAMA = "ollama"
    const val CUSTOM = "custom"

    val ALL: List<ProviderPreset> = listOf(
        ProviderPreset(
            id = GROQ,
            label = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            models = listOf(
                "llama-3.3-70b-versatile",
                "llama-3.1-8b-instant",
                "openai/gpt-oss-120b",
                "openai/gpt-oss-20b",
                "qwen/qwen3-32b"
            ),
            needsKey = true,
            keyUrl = "https://console.groq.com/keys",
            note = "Free tier, fastest option, solid tool calling. Best default."
        ),
        ProviderPreset(
            id = GEMINI,
            label = "Google Gemini",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            models = listOf(
                "gemini-2.0-flash",
                "gemini-2.0-flash-lite",
                "gemini-2.5-flash"
            ),
            needsKey = true,
            keyUrl = "https://aistudio.google.com/apikey",
            note = "Generous free tier from AI Studio. Very good general quality."
        ),
        ProviderPreset(
            id = OPENROUTER,
            label = "OpenRouter (free models)",
            baseUrl = "https://openrouter.ai/api/v1",
            models = listOf(
                "meta-llama/llama-3.3-70b-instruct:free",
                "google/gemma-3-27b-it:free",
                "qwen/qwen3-14b:free",
                "mistralai/mistral-small-3.2-24b-instruct:free"
            ),
            needsKey = true,
            keyUrl = "https://openrouter.ai/keys",
            note = "Models ending in :free cost nothing. Tool support varies by model."
        ),
        ProviderPreset(
            id = CEREBRAS,
            label = "Cerebras",
            baseUrl = "https://api.cerebras.ai/v1",
            models = listOf(
                "llama-3.3-70b",
                "llama3.1-8b",
                "qwen-3-32b"
            ),
            needsKey = true,
            keyUrl = "https://cloud.cerebras.ai",
            note = "Free tier, extremely fast inference."
        ),
        ProviderPreset(
            id = OLLAMA,
            label = "Ollama (your own PC)",
            baseUrl = "http://192.168.1.10:11434/v1",
            models = listOf(
                "llama3.1",
                "qwen2.5",
                "mistral",
                "gemma3"
            ),
            needsKey = false,
            keyUrl = null,
            note = "Point this at your home machine. Works on the same Wi-Fi today; " +
                "the PythonAnywhere relay will make it work from anywhere later."
        ),
        ProviderPreset(
            id = CUSTOM,
            label = "Custom endpoint",
            baseUrl = "https://example.com/v1",
            models = listOf("your-model"),
            needsKey = false,
            keyUrl = null,
            note = "Any OpenAI-compatible URL. This is where the PythonAnywhere " +
                "relay goes once it exists."
        )
    )

    fun byId(id: String): ProviderPreset = ALL.firstOrNull { it.id == id } ?: ALL.first()
}
