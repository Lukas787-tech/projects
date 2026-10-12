# Providers: what's free, what's verified

Last checked: **2026-10-11**. Machine-readable presets live in
[`data/providers.toml`](../data/providers.toml) and
[`data/asset-sources.toml`](../data/asset-sources.toml); edit those files (or a
user override file) when something below changes. No code change is needed.

## How this was verified

The build environment's network policy blocked most official docs sites
(`ai.google.dev`, `console.groq.com`, `openrouter.ai`, `docs.mistral.ai`,
`docs.fish.audio`, `docs.tavily.com`, `api.polyhaven.com`, …), so each entry
says where its facts came from:

| Confidence | Meaning |
|---|---|
| **high** | Read directly from official docs (`docs.claude.com`, `code.claude.com`) or from the provider's **official SDK / API source on GitHub** |
| **medium** | Consistent across several recent (2026) secondary sources |
| **low** | Secondary sources conflict or are stale; treat as a guess |

Because free tiers and model names change monthly, Kickoff never trusts this
file alone:

- **Models are listed live** from each provider (`GET /models`, Ollama `GET /api/tags`) and picked with `model_preference` patterns. `default_model` is only a fallback.
- **The setup wizard runs a real test call** for every key (a 1-token chat, a 1-word synthesis, a 1-result search) before recommending a provider.
- **Limits drive throttling only when known.** Unknown limits fall back to honoring `429` / `Retry-After`.

## LLM providers

| Provider | Access | Endpoint | Free-tier limits | Recommended model | Confidence |
|---|---|---|---|---|---|
| **Google Gemini** | Free, no card | OpenAI-compatible: `https://generativelanguage.googleapis.com/v1beta/openai/` (`/chat/completions`, `/models`) | Flash models only; ~10 RPM, ~250 RPD (sources range 250–1,500 RPD) | newest `gemini-*-flash` from the live list; fallback `gemini-2.5-flash` | medium |
| **Groq** | Free, no card | `https://api.groq.com/openai/v1` | `openai/gpt-oss-120b`: 30 RPM, 1,000 RPD, 8K TPM, 200K TPD | `openai/gpt-oss-120b` | medium |
| **OpenRouter** | Free models, no card | `https://openrouter.ai/api/v1` | 20 RPM; 50 RPD (1,000 RPD after a one-time $10 purchase) | any `*:free` id from the live list | medium |
| **Mistral** | Free "Experiment" plan, phone verification | `https://api.mistral.ai/v1` | Low, unpublished per-minute limits | `mistral-small-latest` | low |
| **Cerebras** | **Trial only**: one-time credits, card required, 30 days | `https://api.cerebras.ai/v1` | ~5 RPM during trial | `gpt-oss-120b` | low |
| **Ollama** | Local, no key, no limits | `http://localhost:11434` (`/api/tags`, `/api/chat`, `/api/version`) | none | best installed model | high |
| **Anthropic** | Paid (optional upgrade) | `https://api.anthropic.com/v1/messages`, `x-api-key`, `anthropic-version: 2023-06-01` | n/a | `claude-opus-5-5` (also `claude-sonnet-5-5`, `claude-haiku-5-5` for cheaper tasks) | high |

What changed and how Kickoff handles it:

- **Gemini.** Pro models left the free tier in April 2026, and free-tier prompts may be used to improve Google's products. The wizard shows that privacy note before you choose Gemini.
- **Groq.** Several August 2026 sources report that Llama models left the free tier on 2026-08-16. The `gpt-oss` models remain free, so they are preferred.
- **OpenRouter.** The `:free` list churns: five free variants were withdrawn between September and October 2026. Kickoff filters the live list to `:free` ids and re-picks one when the saved model disappears.
- **Cerebras.** The old "1M free tokens/day" offer is gone. Cerebras is kept as a preset but is **not** in the default fallback chain, because it needs a card.
- **Mistral.** Limits are visible only in each workspace's Admin Console, so the preset has no throttle and relies on `429` handling.
- **JSON output.**
  - Gemini's compatibility layer accepts `response_format` with a JSON schema.
  - Groq, OpenRouter, Mistral and Cerebras get `json_object` mode.
  - Ollama gets a JSON schema in `format`.
  - Anthropic gets native structured outputs (`output_config.format`). The schema is sent without the keywords that native modes reject (`minLength`, `maxItems`, …), and Kickoff checks those client-side.
- **Anthropic specifics.** Current Claude models reject `temperature` and explicit thinking settings, so Kickoff sends neither. On Opus 5.5 and Sonnet 5.5, Kickoff requests server-side refusal fallback (`fallbacks: "default"`, beta `server-side-fallback-2026-07-01`), so a safety decline is retried on the model Anthropic recommends instead of failing.

  Every structured result is then validated and repaired by Kickoff regardless, because free and local models often break format.

**Default fallback chain:** Gemini → Groq → OpenRouter (free) → Ollama (if detected).
**Wizard:** if Ollama is running with a model of at least 7B parameters, it is recommended first. Otherwise Gemini.

## Speech to text

| Engine | Access | Details | Confidence |
|---|---|---|---|
| **Whisper, local** (whisper.cpp via `whisper-rs`) | Free, offline | ggml models from `https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-{model}.bin`. tiny 75 MiB, base 142 MiB, small 466 MiB, medium 1.5 GiB. SHA-1 values from whisper.cpp `models/README.md` are checked after download. | high |
| **Groq Whisper** | Free tier | OpenAI-compatible `POST /openai/v1/audio/transcriptions`, model `whisper-large-v3-turbo`. 20 RPM, 2,000 RPD, 28,800 audio-seconds/day, 25 MB upload on free. Shares the Groq key. | medium |

## Text to speech

| Engine | Access | Details | Confidence |
|---|---|---|---|
| **Fish Audio** | Uncertain free access (see below) | Base `https://api.fish.audio`, `Authorization: Bearer`. `POST /v1/tts` (msgpack body, model in `model` header). **Streaming:** WebSocket `wss://api.fish.audio/v1/tts/live` with msgpack events `start{request}` → `text{text}`… → `flush` → `stop`; server sends `audio{audio}` then `finish{reason}`. Voices: `GET /model?page_size&page_number&title&tag&language&sort_by`. Credits: `GET /wallet/self/api-credit`. Formats: pcm, wav, mp3, opus. | high (API), low (free tier) |
| **Piper** | Free, offline | MIT-licensed Windows build `piper_windows_amd64.zip` from `rhasspy/piper` release `2023.11.14-2`. CLI: `piper --model <voice>.onnx --output_raw`, text on stdin, 16-bit mono PCM out at the voice's `audio.sample_rate`. Voices from `huggingface.co/rhasspy/piper-voices`, each with its own license. (Active development moved to the GPL-licensed `OHF-Voice/piper1-gpl`; Kickoff uses the MIT build as a separate process.) | high |
| **Windows voices** | Free, built in | WinRT `Windows.Media.SpeechSynthesis`. Zero download. | high |

**Fish Audio free tier.** The official SDK names the models `s1`, `s2-pro` and `s2.1-pro`. Fish Audio's blog announced a free `s2.1-pro-free` API model under a fair-use policy, with no SLA and requests possibly retained for training. That offer was last reported as extended to **2026-08-31**, and its current status could not be confirmed. Reviews also say the free web plan (about 8,000 credits a month) does **not** include API access.

Per the agreed plan, the wizard handles this as follows:

1. Calls `/wallet/self/api-credit`.
2. Tries a one-word synthesis with `s2.1-pro-free`, then `s2.1-pro`.
3. Recommends Fish Audio only if one of those succeeds without consuming paid credit you don't have.
4. Otherwise falls back automatically to Piper, then Windows voices, and says why.

Because no numeric free cap is published, usage is metered in UTF-8 bytes against a monthly budget you set yourself, with warnings at 80% and 95%.

## Web search

| Provider | Access | Details | Confidence |
|---|---|---|---|
| **Tavily** (default) | **1,000 credits/month, no card** | `POST https://api.tavily.com/search`, `Authorization: Bearer`. Basic search = 1 credit, advanced = 2. Kickoff uses basic. | high (API, from official SDK), medium (quota) |
| **Brave Search** | **$5 monthly credit (~1,000 searches), card required, attribution required** | `GET https://api.search.brave.com/res/v1/web/search`, header `X-Subscription-Token`. The old 2,000-query free plan no longer exists, and overages are billed. | medium |

Without any search key, research still runs. It uses the asset APIs below and the model's own knowledge of comparable games, and every such record is stored with `origin = llm_knowledge` and labeled **Unverified** in the UI.

## Asset and reference sources

| Source | Access | License handling | Confidence |
|---|---|---|---|
| **Poly Haven** | API, no key | All assets CC0. Every call needs a **unique User-Agent** naming the app (ToS 2.4). Content shown from the live API needs a visible **"Powered by Poly Haven"** credit (ToS 2.5). `GET /search?q=&t=` (ranked slugs), `GET /assets?t=` (metadata + `thumbnail_url`), `GET /info/{id}`. `429` carries `Retry-After`. | high (from the official `Poly-Haven/Public-API` repo, swagger + ToS) |
| **Sketchfab** | API (public search) | `GET https://api.sketchfab.com/v3/search?type=models&downloadable=true&q=`. Each result carries its own license, classified with the allowlist. Downloads require the user's OAuth login (later milestone). | medium |
| **Kenney** | Link-only (site search) | Kenney's asset pages state CC0, recorded as a source-wide policy with its URL. | medium |
| **OpenGameArt** | Link-only (site search) | Licenses vary per entry (CC0, CC-BY, CC-BY-SA, GPL, OGA-BY), so results stay `unknown` until verified. | medium |
| **itch.io** | Link-only (site search) | Licenses are set per creator, so results are always `unknown` until you verify them. | medium |

License classification is deterministic. A result is `usable` only when the source supplies a license on the allowlist in `asset-sources.toml` together with a license URL. Everything else is `unknown` and treated as reference-only. The LLM never assigns or upgrades a license.

## Claude Code facts used by the starter kit

These were read from `code.claude.com`, so they are high confidence:

- **Environment variables in `.mcp.json`:**
  - `.mcp.json` expands `${VAR}` and `${VAR:-default}` in `command`, `args`, `env`, `url` and `headers`.
  - In a remote server's `url` and `headers`, credential names such as `ANTHROPIC_API_KEY` deliberately read as empty. Generated kits therefore use their own variable names (for example `KICKOFF_ANTHROPIC_KEY`).
  - `headersHelper` can produce headers from a command at connect time.
- **Skills and commands:** skills live at `.claude/skills/<name>/SKILL.md` with YAML frontmatter (`name`, `description`). `.claude/commands/<name>.md` still works, but skills are preferred.

## Sources

- [Gemini OpenAI compatibility](https://ai.google.dev/gemini-api/docs/openai)
- Gemini free-tier limits: [tinkerllm](https://tinkerllm.com/blog/gemini-api-free-tier-limits-rate-quotas/), [aipromptshub](https://aipromptshub.co/blog/gemini-api-free-tier-rate-limits), [pecollective](https://pecollective.com/tools/gemini-free-tier-guide/)
- Groq limits: [allaiask](https://allaiask.com/llm-providers/groq/rate-limits), [ampm-aiops (Llama removal)](https://ampm-aiops.com/en/guides/groq-free-tier-llama-removed-gpt-oss-2026/), [Groq speech-to-text docs](https://console.groq.com/docs/speech-to-text)
- OpenRouter: [free models collection](https://openrouter.ai/collections/free-models), [klymentiev](https://klymentiev.com/blog/openrouter-free-tier), [TrueFoundry](https://www.truefoundry.com/de/blog/openrouter-rate-limits-your-free-tier-depends-on-what-youve-already-paid)
- Mistral: [costbench](https://costbench.com/software/llm-api-providers/mistral-ai/free-plan/), [free-llm-apis](https://free-llm-apis.pages.dev/providers/mistral/)
- Cerebras: [toolfreebie](https://toolfreebie.com/cerebras-free-api/), [morphllm](https://www.morphllm.com/cerebras-pricing)
- Ollama: [`docs/api.md`](https://github.com/ollama/ollama/blob/main/docs/api.md)
- Anthropic: [models overview](https://docs.claude.com/en/docs/about-claude/models/overview), [Messages API](https://docs.claude.com/en/api/messages)
- whisper.cpp: [models README](https://github.com/ggml-org/whisper.cpp/blob/master/models/README.md)
- Fish Audio: [official Python SDK](https://github.com/fishaudio/fish-audio-python), [free API blog post](https://fish.audio/blog/s2-1-pro-free-api/), [AlphaSignal](https://alphasignal.ai/news/fish-audio-opens-s2-1-pro-to-developers-with-free-unlimited-api-access), [saascrmreview](https://saascrmreview.com/fish-audio-review/)
- Piper: [rhasspy/piper (MIT, archived)](https://github.com/rhasspy/piper), [OHF-Voice/piper1-gpl](https://github.com/OHF-Voice/piper1-gpl)
- Tavily: [official Python SDK](https://github.com/tavily-ai/tavily-python), [costbench](https://costbench.com/software/web-scraping/tavily/free-plan/)
- Brave: [API docs](https://api.search.brave.com/app/documentation), [agentdeals](https://agentdeals.dev/vendor/brave-search-api), [apicostcalc](https://apicostcalc.com/brave-search.html)
- Poly Haven: [Public-API repo + ToS](https://github.com/Poly-Haven/Public-API)
- Sketchfab: [Download API](https://sketchfab.com/developers/download-api), [license filters](https://sketchfab.com/blogs/community/refine-downloadable-model-searches-with-new-license-filters/)
- OpenGameArt / Kenney / itch.io: [OpenGameArt API forum thread](https://opengameart.org/forumtopic/api), [game-asset-library](https://github.com/hakyong-tae/game-asset-library)
- Claude Code: [MCP](https://code.claude.com/docs/en/mcp), [Skills](https://code.claude.com/docs/en/skills)
