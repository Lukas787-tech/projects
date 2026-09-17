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

/** Roughly what an account costs, which is what decides ordering in the pool. */
enum class Tier {
    /** A standing free allowance that refills. First choice. */
    Free,

    /** Free only until signup credit runs out. Used after the free tier. */
    Trial,

    /** Your own hardware. No quota at all, but only reachable sometimes. */
    Local,

    /** Real money per token. Only used if you deliberately enable it. */
    Paid,

    /** Unknown — a hand-entered endpoint. */
    Custom
}

/**
 * Published free-tier ceilings, used as a *client-side* throttle so Jarvis
 * rotates away from an endpoint before the provider starts refusing it.
 *
 * These are deliberately conservative and will drift as providers change their
 * plans — they are a floor to stay under, not a contract. Whenever a response
 * carries real `x-ratelimit-*` headers those win, because they describe the
 * actual key rather than the published tier. Zero means "not known", which
 * disables that particular check rather than blocking everything.
 */
data class RateHint(
    val requestsPerMinute: Int = 0,
    val requestsPerDay: Int = 0
) {
    companion object {
        val Unlimited = RateHint()
    }
}

/** How the provider answers "what models do you have?". */
enum class CatalogStyle {
    /** `GET /models` returning `{"data":[{"id":...}]}`. Almost everyone. */
    OpenAi,

    /** Google's native list endpoint; its OpenAI shim has no `/models`. */
    Gemini,

    /** GitHub Models publishes a bare JSON array at a separate catalog host. */
    GitHub
}

data class ProviderPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val fallbackModels: List<String>,
    val needsKey: Boolean,
    val keyUrl: String?,
    val note: String,
    val tier: Tier = Tier.Free,
    val rate: RateHint = RateHint.Unlimited,
    /**
     * Whether the provider's chat models generally accept a `tools` array. A
     * false here only changes where the payload ladder starts; the real answer
     * is learned per endpoint on the first call and remembered after that.
     */
    val toolCalling: Boolean = true,
    val catalogStyle: CatalogStyle = CatalogStyle.OpenAi,
    /** Only model ids ending in this are free, if the provider mixes both. */
    val freeSuffix: String? = null,
    /** Extra headers this provider wants (attribution, API versions). */
    val extraHeaders: Map<String, String> = emptyMap(),
    /** How many models of this provider a single "Add" enrols. */
    val poolLimit: Int = 4,
    /**
     * True when the heaviest limit is charged to the account rather than the
     * model. Rotating models under an account-wide daily cap buys nothing, so
     * the pool rests the whole account instead of walking its models one by one.
     */
    val accountWideDailyCap: Boolean = true
) {
    val defaultModel: String get() = fallbackModels.first()

    val isLocal: Boolean get() = tier == Tier.Local
}

object Providers {

    // Hosted, standing free allowance.
    const val GROQ = "groq"
    const val GEMINI = "gemini"
    const val OPENROUTER = "openrouter"
    const val CEREBRAS = "cerebras"
    const val MISTRAL = "mistral"
    const val GITHUB = "github"
    const val CLOUDFLARE = "cloudflare"
    const val SAMBANOVA = "sambanova"
    const val COHERE = "cohere"
    const val ZHIPU = "zhipu"
    const val CHUTES = "chutes"
    const val SCALEWAY = "scaleway"
    const val GOOGLE_VERTEX_EXPRESS = "vertex_express"

    // Hosted, free while signup credit lasts.
    const val NVIDIA = "nvidia"
    const val HUGGINGFACE = "huggingface"
    const val NEBIUS = "nebius"
    const val TOGETHER = "together"
    const val HYPERBOLIC = "hyperbolic"
    const val NOVITA = "novita"
    const val MOONSHOT = "moonshot"
    const val QWEN = "qwen"
    const val DEEPINFRA = "deepinfra"

    // Hosted, metered.
    const val DEEPSEEK = "deepseek"
    const val XAI = "xai"
    const val FIREWORKS = "fireworks"
    const val PERPLEXITY = "perplexity"
    const val OPENAI = "openai"

    // Your own machines.
    const val OLLAMA = "ollama"
    const val LMSTUDIO = "lmstudio"
    const val LLAMACPP = "llamacpp"
    const val VLLM = "vllm"

    const val CUSTOM = "custom"

    /** The address a self-hosted server usually sits on, for the local presets. */
    private const val LAN = "192.168.1.10"

    val ALL: List<ProviderPreset> = listOf(

        // ------------------------------------------------------------ free tier

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
            note = "Free tier and the fastest option. Good default.",
            tier = Tier.Free,
            rate = RateHint(requestsPerMinute = 28, requestsPerDay = 900),
            poolLimit = 5
        ),
        ProviderPreset(
            id = GEMINI,
            label = "Google Gemini",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            // Google renames Gemini versions often, so the "-latest" aliases are
            // the default: they keep working when a version number moves.
            fallbackModels = listOf(
                "gemini-flash-latest",
                "gemini-flash-lite-latest",
                "gemini-pro-latest"
            ),
            needsKey = true,
            keyUrl = "https://aistudio.google.com/apikey",
            note = "Generous free tier from AI Studio. Refresh models to see " +
                "exactly what your key can call.",
            tier = Tier.Free,
            rate = RateHint(requestsPerMinute = 9, requestsPerDay = 200),
            catalogStyle = CatalogStyle.Gemini,
            poolLimit = 4
        ),
        ProviderPreset(
            id = OPENROUTER,
            label = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            fallbackModels = listOf("openai/gpt-oss-20b:free"),
            needsKey = true,
            keyUrl = "https://openrouter.ai/keys",
            note = "Refresh models and pick any ending in ':free'. Tool support " +
                "varies by model, so try another if replies ignore your data.",
            tier = Tier.Free,
            // The free pool is capped per account per day, not per model.
            rate = RateHint(requestsPerMinute = 18, requestsPerDay = 45),
            freeSuffix = ":free",
            extraHeaders = mapOf(
                // OpenRouter uses these purely for attribution on its dashboard.
                "HTTP-Referer" to "https://github.com/Lukas787-tech/projects",
                "X-Title" to "Jarvis"
            ),
            poolLimit = 6
        ),
        ProviderPreset(
            id = CEREBRAS,
            label = "Cerebras",
            baseUrl = "https://api.cerebras.ai/v1",
            fallbackModels = listOf("gpt-oss-120b", "llama-3.3-70b"),
            needsKey = true,
            keyUrl = "https://cloud.cerebras.ai",
            note = "Free tier, extremely fast inference.",
            tier = Tier.Free,
            rate = RateHint(requestsPerMinute = 28, requestsPerDay = 800),
            poolLimit = 4
        ),
        ProviderPreset(
            id = MISTRAL,
            label = "Mistral",
            baseUrl = "https://api.mistral.ai/v1",
            fallbackModels = listOf("mistral-small-latest", "open-mistral-nemo"),
            needsKey = true,
            keyUrl = "https://console.mistral.ai/api-keys",
            note = "Free experiment tier. Solid tool calling for its size.",
            tier = Tier.Free,
            // The free plan is roughly one request a second; stay well under.
            rate = RateHint(requestsPerMinute = 30, requestsPerDay = 500),
            poolLimit = 4
        ),
        ProviderPreset(
            id = GITHUB,
            label = "GitHub Models",
            baseUrl = "https://models.github.ai/inference",
            fallbackModels = listOf("openai/gpt-4o-mini", "meta/Llama-3.3-70B-Instruct"),
            needsKey = true,
            keyUrl = "https://github.com/settings/tokens",
            note = "Free with any GitHub token that has the models:read scope. " +
                "Small daily allowance, so pair it with others.",
            tier = Tier.Free,
            rate = RateHint(requestsPerMinute = 12, requestsPerDay = 140),
            catalogStyle = CatalogStyle.GitHub,
            poolLimit = 4
        ),
        ProviderPreset(
            id = CLOUDFLARE,
            label = "Cloudflare Workers AI",
            // The account id goes where ACCOUNT_ID is; the app cannot guess it.
            baseUrl = "https://api.cloudflare.com/client/v4/accounts/ACCOUNT_ID/ai/v1",
            fallbackModels = listOf("@cf/meta/llama-3.3-70b-instruct-fp8-fast"),
            needsKey = true,
            keyUrl = "https://dash.cloudflare.com/profile/api-tokens",
            note = "Replace ACCOUNT_ID in the URL with your Cloudflare account id, " +
                "then paste a Workers AI token. Free daily neuron allowance.",
            tier = Tier.Free,
            rate = RateHint(requestsPerMinute = 20, requestsPerDay = 300),
            poolLimit = 3
        ),
        ProviderPreset(
            id = SAMBANOVA,
            label = "SambaNova",
            baseUrl = "https://api.sambanova.ai/v1",
            fallbackModels = listOf("Meta-Llama-3.3-70B-Instruct"),
            needsKey = true,
            keyUrl = "https://cloud.sambanova.ai/apis",
            note = "Free developer tier, very fast on large Llama models.",
            tier = Tier.Free,
            rate = RateHint(requestsPerMinute = 18, requestsPerDay = 400),
            poolLimit = 4
        ),
        ProviderPreset(
            id = COHERE,
            label = "Cohere",
            baseUrl = "https://api.cohere.ai/compatibility/v1",
            fallbackModels = listOf("command-r7b-12-2024", "command-r-plus"),
            needsKey = true,
            keyUrl = "https://dashboard.cohere.com/api-keys",
            note = "Free trial keys allow a steady low rate — good as a backstop " +
                "rather than a first choice.",
            tier = Tier.Free,
            rate = RateHint(requestsPerMinute = 18, requestsPerDay = 900),
            poolLimit = 3
        ),
        ProviderPreset(
            id = ZHIPU,
            label = "Z.ai (GLM)",
            baseUrl = "https://api.z.ai/api/paas/v4",
            fallbackModels = listOf("glm-4-flash", "glm-4.5-air"),
            needsKey = true,
            keyUrl = "https://z.ai/manage-apikey/apikey-list",
            note = "The GLM flash models are free. Good quality for the price of " +
                "nothing; occasionally slow.",
            tier = Tier.Free,
            rate = RateHint(requestsPerMinute = 18, requestsPerDay = 400),
            poolLimit = 3
        ),
        ProviderPreset(
            id = CHUTES,
            label = "Chutes",
            baseUrl = "https://llm.chutes.ai/v1",
            fallbackModels = listOf("deepseek-ai/DeepSeek-V3-0324"),
            needsKey = true,
            keyUrl = "https://chutes.ai",
            note = "Free daily allowance across a large open-model catalogue.",
            tier = Tier.Free,
            rate = RateHint(requestsPerMinute = 15, requestsPerDay = 180),
            poolLimit = 4
        ),
        ProviderPreset(
            id = SCALEWAY,
            label = "Scaleway",
            baseUrl = "https://api.scaleway.ai/v1",
            fallbackModels = listOf("llama-3.3-70b-instruct"),
            needsKey = true,
            keyUrl = "https://console.scaleway.com/generative-api/models",
            note = "European hosting with a free allowance while their " +
                "generative API is in beta.",
            tier = Tier.Free,
            rate = RateHint(requestsPerMinute = 18, requestsPerDay = 300),
            poolLimit = 3
        ),
        ProviderPreset(
            id = GOOGLE_VERTEX_EXPRESS,
            label = "Vertex AI (express)",
            baseUrl = "https://aiplatform.googleapis.com/v1/publishers/google/models",
            fallbackModels = listOf("google/gemini-2.0-flash-001"),
            needsKey = true,
            keyUrl = "https://console.cloud.google.com/vertex-ai",
            note = "Express-mode Vertex key, separate quota from AI Studio — " +
                "useful as a second Google account once Gemini rests.",
            tier = Tier.Free,
            rate = RateHint(requestsPerMinute = 9, requestsPerDay = 180),
            poolLimit = 2
        ),

        // ------------------------------------------------------- signup credit

        ProviderPreset(
            id = NVIDIA,
            label = "NVIDIA NIM",
            baseUrl = "https://integrate.api.nvidia.com/v1",
            fallbackModels = listOf("meta/llama-3.3-70b-instruct"),
            needsKey = true,
            keyUrl = "https://build.nvidia.com",
            note = "Free credits on signup, enormous model catalogue.",
            tier = Tier.Trial,
            rate = RateHint(requestsPerMinute = 35),
            poolLimit = 4
        ),
        ProviderPreset(
            id = HUGGINGFACE,
            label = "Hugging Face",
            baseUrl = "https://router.huggingface.co/v1",
            fallbackModels = listOf("meta-llama/Llama-3.3-70B-Instruct"),
            needsKey = true,
            keyUrl = "https://huggingface.co/settings/tokens",
            note = "Routes to whichever inference partner is up. Monthly credit " +
                "on a free account.",
            tier = Tier.Trial,
            rate = RateHint(requestsPerMinute = 18),
            poolLimit = 4
        ),
        ProviderPreset(
            id = NEBIUS,
            label = "Nebius AI Studio",
            baseUrl = "https://api.studio.nebius.com/v1",
            fallbackModels = listOf("meta-llama/Llama-3.3-70B-Instruct"),
            needsKey = true,
            keyUrl = "https://studio.nebius.com",
            note = "Free credit on signup, cheap after that.",
            tier = Tier.Trial,
            rate = RateHint(requestsPerMinute = 28),
            poolLimit = 4
        ),
        ProviderPreset(
            id = TOGETHER,
            label = "Together AI",
            baseUrl = "https://api.together.xyz/v1",
            fallbackModels = listOf("meta-llama/Llama-3.3-70B-Instruct-Turbo-Free"),
            needsKey = true,
            keyUrl = "https://api.together.xyz/settings/api-keys",
            note = "A few models are free outright — their ids end in '-Free'. " +
                "Signup credit covers the rest.",
            tier = Tier.Trial,
            rate = RateHint(requestsPerMinute = 28),
            poolLimit = 4
        ),
        ProviderPreset(
            id = HYPERBOLIC,
            label = "Hyperbolic",
            baseUrl = "https://api.hyperbolic.xyz/v1",
            fallbackModels = listOf("meta-llama/Meta-Llama-3.1-70B-Instruct"),
            needsKey = true,
            keyUrl = "https://app.hyperbolic.xyz/settings",
            note = "Free credit on signup, open models only.",
            tier = Tier.Trial,
            rate = RateHint(requestsPerMinute = 28),
            poolLimit = 3
        ),
        ProviderPreset(
            id = NOVITA,
            label = "Novita",
            baseUrl = "https://api.novita.ai/v3/openai",
            fallbackModels = listOf("meta-llama/llama-3.3-70b-instruct"),
            needsKey = true,
            keyUrl = "https://novita.ai/settings/key-management",
            note = "Free credit on signup, broad open-model catalogue.",
            tier = Tier.Trial,
            rate = RateHint(requestsPerMinute = 28),
            poolLimit = 3
        ),
        ProviderPreset(
            id = DEEPINFRA,
            label = "DeepInfra",
            baseUrl = "https://api.deepinfra.com/v1/openai",
            fallbackModels = listOf("meta-llama/Llama-3.3-70B-Instruct"),
            needsKey = true,
            keyUrl = "https://deepinfra.com/dash/api_keys",
            note = "Free credit on signup, then pay per token.",
            tier = Tier.Trial,
            rate = RateHint(requestsPerMinute = 28),
            poolLimit = 3
        ),
        ProviderPreset(
            id = MOONSHOT,
            label = "Moonshot (Kimi)",
            baseUrl = "https://api.moonshot.ai/v1",
            fallbackModels = listOf("kimi-k2-0905-preview", "moonshot-v1-8k"),
            needsKey = true,
            keyUrl = "https://platform.moonshot.ai/console/api-keys",
            note = "Signup credit. Very long context, strong tool calling.",
            tier = Tier.Trial,
            rate = RateHint(requestsPerMinute = 18),
            poolLimit = 3
        ),
        ProviderPreset(
            id = QWEN,
            label = "Qwen (DashScope)",
            baseUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
            fallbackModels = listOf("qwen-plus", "qwen-turbo"),
            needsKey = true,
            keyUrl = "https://bailian.console.alibabacloud.com",
            note = "Free token allowance per model for the first months of an " +
                "account. Use the international endpoint outside China.",
            tier = Tier.Trial,
            rate = RateHint(requestsPerMinute = 28),
            poolLimit = 4
        ),

        // ------------------------------------------------------------- metered

        ProviderPreset(
            id = DEEPSEEK,
            label = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            fallbackModels = listOf("deepseek-chat", "deepseek-reasoner"),
            needsKey = true,
            keyUrl = "https://platform.deepseek.com/api_keys",
            note = "Paid, but among the cheapest anywhere. A good last resort " +
                "when every free endpoint is resting.",
            tier = Tier.Paid,
            rate = RateHint(requestsPerMinute = 50),
            poolLimit = 2
        ),
        ProviderPreset(
            id = XAI,
            label = "xAI (Grok)",
            baseUrl = "https://api.x.ai/v1",
            fallbackModels = listOf("grok-4-fast", "grok-3-mini"),
            needsKey = true,
            keyUrl = "https://console.x.ai",
            note = "Paid, with promotional credit from time to time.",
            tier = Tier.Paid,
            rate = RateHint(requestsPerMinute = 50),
            poolLimit = 2
        ),
        ProviderPreset(
            id = FIREWORKS,
            label = "Fireworks",
            baseUrl = "https://api.fireworks.ai/inference/v1",
            fallbackModels = listOf("accounts/fireworks/models/llama-v3p3-70b-instruct"),
            needsKey = true,
            keyUrl = "https://fireworks.ai/account/api-keys",
            note = "Paid and fast, with signup credit.",
            tier = Tier.Paid,
            rate = RateHint(requestsPerMinute = 50),
            poolLimit = 2
        ),
        ProviderPreset(
            id = PERPLEXITY,
            label = "Perplexity",
            baseUrl = "https://api.perplexity.ai",
            fallbackModels = listOf("sonar", "sonar-pro"),
            needsKey = true,
            keyUrl = "https://www.perplexity.ai/settings/api",
            note = "Paid, and answers with live web results built in. No tool " +
                "calling, so Jarvis falls back to plain replies here.",
            tier = Tier.Paid,
            rate = RateHint(requestsPerMinute = 40),
            toolCalling = false,
            poolLimit = 2
        ),
        ProviderPreset(
            id = OPENAI,
            label = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            fallbackModels = listOf("gpt-4o-mini", "gpt-4.1-mini"),
            needsKey = true,
            keyUrl = "https://platform.openai.com/api-keys",
            note = "Paid. Included because it is the dialect everything else " +
                "imitates, so it always works.",
            tier = Tier.Paid,
            rate = RateHint(requestsPerMinute = 50),
            poolLimit = 2
        ),

        // -------------------------------------------------------------- local

        ProviderPreset(
            id = OLLAMA,
            label = "Ollama (your own PC)",
            baseUrl = "http://$LAN:11434/v1",
            fallbackModels = listOf("llama3.1"),
            needsKey = false,
            keyUrl = null,
            note = "Point this at your home machine and refresh models to see " +
                "what you have pulled. Works on the same Wi-Fi today; the " +
                "PythonAnywhere relay will make it work from anywhere later.",
            tier = Tier.Local,
            rate = RateHint.Unlimited,
            accountWideDailyCap = false,
            poolLimit = 3
        ),
        ProviderPreset(
            id = LMSTUDIO,
            label = "LM Studio (your own PC)",
            baseUrl = "http://$LAN:1234/v1",
            fallbackModels = listOf("local-model"),
            needsKey = false,
            keyUrl = null,
            note = "Start the LM Studio server, enable network access, then " +
                "refresh models.",
            tier = Tier.Local,
            rate = RateHint.Unlimited,
            accountWideDailyCap = false,
            poolLimit = 3
        ),
        ProviderPreset(
            id = LLAMACPP,
            label = "llama.cpp server",
            baseUrl = "http://$LAN:8080/v1",
            fallbackModels = listOf("local-model"),
            needsKey = false,
            keyUrl = null,
            note = "Whatever `llama-server` is currently holding. No quota, so " +
                "it is worth keeping in the pool as a permanent backstop.",
            tier = Tier.Local,
            rate = RateHint.Unlimited,
            accountWideDailyCap = false,
            poolLimit = 2
        ),
        ProviderPreset(
            id = VLLM,
            label = "vLLM server",
            baseUrl = "http://$LAN:8000/v1",
            fallbackModels = listOf("local-model"),
            needsKey = false,
            keyUrl = null,
            note = "A vLLM OpenAI-compatible server on your own hardware.",
            tier = Tier.Local,
            rate = RateHint.Unlimited,
            accountWideDailyCap = false,
            poolLimit = 2
        ),

        ProviderPreset(
            id = CUSTOM,
            label = "Custom endpoint",
            baseUrl = "https://example.com/v1",
            fallbackModels = listOf("your-model"),
            needsKey = false,
            keyUrl = null,
            note = "Any OpenAI-compatible URL. This is where the PythonAnywhere " +
                "relay goes once it exists.",
            tier = Tier.Custom,
            rate = RateHint.Unlimited,
            accountWideDailyCap = false,
            poolLimit = 2
        )
    )

    private val index: Map<String, ProviderPreset> = ALL.associateBy { it.id }

    fun byId(id: String): ProviderPreset = index[id] ?: ALL.first()

    /** Providers grouped for the picker, cheapest-and-most-generous first. */
    fun grouped(): List<Pair<String, List<ProviderPreset>>> = listOf(
        "Free tier" to ALL.filter { it.tier == Tier.Free },
        "Free signup credit" to ALL.filter { it.tier == Tier.Trial },
        "Your own machine" to ALL.filter { it.tier == Tier.Local },
        "Paid" to ALL.filter { it.tier == Tier.Paid },
        "Other" to ALL.filter { it.tier == Tier.Custom }
    ).filter { it.second.isNotEmpty() }
}
