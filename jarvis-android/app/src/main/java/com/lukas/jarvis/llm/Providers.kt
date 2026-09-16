package com.lukas.jarvis.llm

/**
 * Every backend Jarvis talks to speaks the OpenAI chat-completions dialect, so
 * one client covers all of them. Swapping provider is just a base URL, a key
 * and a model name — which is also how the PythonAnywhere relay will slot in
 * later without touching the client at all.
 *
 * The model lists here are only a starting guess for the picker. Providers
 * retire model ids without warning (Groq dropped the Llama 3.x ids in mid-2026),
 * so the app asks the provider for its real list via [ModelCatalog] and these
 * are just what it shows before the first refresh.
 */
data class ProviderPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val fallbackModels: List<String>,
    val needsKey: Boolean,
    val keyUrl: String?,
    val note: String
) {
    val defaultModel: String get() = fallbackModels.first()
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
            // The Llama 3.x ids were retired in June 2026; these are the
            // replacements Groq itself points migrating users at.
            fallbackModels = listOf(
                "openai/gpt-oss-120b",
                "openai/gpt-oss-20b"
            ),
            needsKey = true,
            keyUrl = "https://console.groq.com/keys",
            note = "Free tier and the fastest option. Good default."
        ),
        ProviderPreset(
            id = GEMINI,
            label = "Google Gemini",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            // Google renames Gemini versions often, so the "-latest" aliases are
            // the default: they keep working when a version number moves.
            fallbackModels = listOf(
                "gemini-flash-latest",
                "gemini-pro-latest"
            ),
            needsKey = true,
            keyUrl = "https://aistudio.google.com/apikey",
            note = "Generous free tier from AI Studio. Refresh models to see " +
                "exactly what your key can call."
        ),
        ProviderPreset(
            id = OPENROUTER,
            label = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            fallbackModels = listOf("openai/gpt-oss-20b:free"),
            needsKey = true,
            keyUrl = "https://openrouter.ai/keys",
            note = "Refresh models and pick any ending in ':free'. Tool support " +
                "varies by model, so try another if replies ignore your data."
        ),
        ProviderPreset(
            id = CEREBRAS,
            label = "Cerebras",
            baseUrl = "https://api.cerebras.ai/v1",
            fallbackModels = listOf("gpt-oss-120b"),
            needsKey = true,
            keyUrl = "https://cloud.cerebras.ai",
            note = "Free tier, extremely fast inference."
        ),
        ProviderPreset(
            id = OLLAMA,
            label = "Ollama (your own PC)",
            baseUrl = "http://192.168.1.10:11434/v1",
            fallbackModels = listOf("llama3.1"),
            needsKey = false,
            keyUrl = null,
            note = "Point this at your home machine and refresh models to see " +
                "what you have pulled. Works on the same Wi-Fi today; the " +
                "PythonAnywhere relay will make it work from anywhere later."
        ),
        ProviderPreset(
            id = CUSTOM,
            label = "Custom endpoint",
            baseUrl = "https://example.com/v1",
            fallbackModels = listOf("your-model"),
            needsKey = false,
            keyUrl = null,
            note = "Any OpenAI-compatible URL. This is where the PythonAnywhere " +
                "relay goes once it exists."
        )
    )

    fun byId(id: String): ProviderPreset = ALL.firstOrNull { it.id == id } ?: ALL.first()
}
