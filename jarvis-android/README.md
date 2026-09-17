# Jarvis — voice assistant and second brain

An Android app you talk to. It remembers what you tell it, keeps running totals
of anything you count, reminds you about things, and can search the live web.
Everything it knows lives in a SQLite file on your phone.

> "I bought chips for two euros" → logged.
> Later: "how much have I got left?" → it knows, because it did the arithmetic,
> not because a language model guessed.

## Getting the APK

There is no Android SDK in this repo, so the APK is built by GitHub Actions.

**On your phone — easiest.** Open
[Releases](https://github.com/Lukas787-tech/projects/releases), tap the
`jarvis-*.apk` on the newest `jarvis-v*` release, and allow "install unknown
apps" when the browser asks. No login, no unzipping.

To refresh that link after changes: **Actions** -> **Build Jarvis APK** ->
**Run workflow** -> tick *Also publish the APK as a GitHub Release*.

**From a computer.** Every push also uploads a `jarvis-apk` artifact on the
workflow run, which is a zip you download and extract. Fine at a desk, painful
on a phone, which is why Releases is the default route above.

Every build is signed with the throwaway key in `app/keystore/`, so new versions
install over old ones without an uninstall. That key is not a secret and is not
for Play Store use — it exists so upgrades work.

## First run

1. Open **Settings**.
2. Pick a provider and paste an API key. The picker holds around thirty of
   them, labelled by what an account costs. The ones worth starting with:

   | Provider | Free key | Notes |
   |---|---|---|
   | **Groq** | console.groq.com/keys | Fastest. Best default. |
   | **Google Gemini** | aistudio.google.com/apikey | Generous limits, strong quality. |
   | **Cerebras** | cloud.cerebras.ai | Very fast. |
   | **OpenRouter** | openrouter.ai/keys | Models ending `:free` cost nothing. |
   | **Mistral** | console.mistral.ai/api-keys | Good tool calling for its size. |
   | **GitHub Models** | github.com/settings/tokens | Any token with `models:read`. |
   | **SambaNova** | cloud.sambanova.ai/apis | Free developer tier. |
   | **Z.ai (GLM)** | z.ai | The flash models are free. |
   | **Cohere**, **Chutes**, **Scaleway**, **Cloudflare** | — | More standing free tiers. |
   | **NVIDIA**, **Hugging Face**, **Nebius**, **Together**, **Novita**, **Hyperbolic**, **DeepInfra**, **Moonshot**, **Qwen** | — | Free credit on signup. |
   | **Ollama**, **LM Studio**, **llama.cpp**, **vLLM** | — | Your own PC. See below. |
   | **DeepSeek**, **xAI**, **Fireworks**, **Perplexity**, **OpenAI** | — | Paid. Only used if you add them. |

3. In **Model pool**, tap **Add** for that provider — it enrols several of its
   models at once. Repeat for a second and third provider, or once you have a
   few keys saved use **Add every provider I have a key for**.
4. Set your name so it addresses you properly.
5. Go back to **Voice**, tap the orb, talk.

No costs anywhere: the models are free tiers, speech-to-text and text-to-speech
are the ones built into Android, and web search goes through DuckDuckGo's
keyless endpoints.

## What it does

**Remembers.** Tell it anything — a door code, a preference, a plan, where you
put something — and it stores it. Ask later and it searches its memory before
answering. Retrieval is BM25 over a locally built index, nudged by importance
and recency.

**Counts things.** Any purchase or countable activity becomes an entry on a
tracker. A tracker is a named number with a unit, optionally a starting balance
and a budget that resets daily, weekly or monthly. Money is the obvious case;
calories, kilometres and gym sessions work identically. The totals are computed
in SQL, so they are exact.

**Reminds.** Anything with a time becomes a task with a real Android alarm and
notification. Repeating tasks roll themselves forward.

**Searches the web.** For anything current or outside the model's knowledge.

**Speaks.** Replies are read aloud, and hands-free mode hands the microphone
straight back so you can keep talking.

The key trick is that before every reply, the app injects your current tracker
balances, open tasks and the memories most relevant to what you just said. So
you get "you've got 23 euros left this week" without having to ask.

## Talking to your own PC (Ollama)

Choose the **Ollama** provider and set the base URL to your machine, e.g.
`http://192.168.1.50:11434/v1`. Start Ollama with
`OLLAMA_HOST=0.0.0.0 ollama serve` so it accepts connections from the phone.
This works while you are on the same Wi-Fi.

Making it work from outside the house is the PythonAnywhere relay, which is not
built yet — it will be a small OpenAI-compatible proxy that forwards to your home
machine, and the app will need no changes beyond pointing the **Custom endpoint**
provider at it.

## Wake word

Optional, off by default, and worth being honest about: Android has no free
always-on hotword engine, so this loops the normal speech recognizer and watches
for your phrase. It costs noticeably more battery than a real hotword chip, and
on Android 10+ background apps cannot reliably launch themselves, so it opens the
UI dependably only while the app is already running. Tapping the orb is better
almost all of the time.

## Building locally

Needs JDK 17 and the Android SDK (platform 35).

```bash
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

## Layout

```
app/src/main/java/com/lukas/jarvis/
  core/       settings, time parsing
  data/       SQLite schema, models, BM25 retrieval, tracker maths
  llm/        OpenAI-compatible client, tool definitions, agent loop, prompt
  voice/      speech recognition, text to speech, wake word service
  web/        DuckDuckGo search, page reader
  notify/     alarms and notifications
  ui/         theme, orb, screens
  vm/         view model
```

Every provider speaks the OpenAI chat-completions dialect, so switching between
a cloud model, your own Ollama box and a future relay is a base URL and a model
name — nothing in the client changes.

## Staying inside the free tiers

A pool of endpoints only helps if it is used carefully, so the rotation is built
around not hitting limits rather than recovering from them:

- **Every call is counted before it is sent**, against the provider's published
  free-tier rate and against whatever its `x-ratelimit-*` headers report. An
  endpoint near its ceiling loses its turn to one with room, so the limit is
  usually never reached.
- **Limits are tracked per account, not per model.** A daily cap is charged to
  the key, so when one is hit the whole account rests instead of each of its
  models spending a request to discover the same wall. Escaping a daily cap
  means a second account — which is why the pool spans providers.
- **The shape each endpoint accepts is remembered.** Providers disagree about
  `temperature`, `max_tokens` and `tools`; finding that out costs a rejected
  request, so it is learned once and reused. Tool calling is given up last
  rather than first, because it is what reaches your memory and trackers.
- **A daily cap rests until midnight UTC**, a stated `Retry-After` is obeyed
  exactly, and everything else backs off with jitter so a pool that failed
  together does not wake together.
- **One turn walks at most five endpoints.** The tenth failure costs the same as
  the second and says the same thing.
- **Prompts are kept small.** History is trimmed and tool output clamped, since
  tokens per minute are metered as strictly as requests.
