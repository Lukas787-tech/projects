# Jarvis — voice assistant and second brain

An Android app you talk to. It remembers what you tell it, keeps running totals
of anything you count, reminds you about things, and can search the live web.
Everything it knows lives in a SQLite file on your phone.

> "I bought chips for two euros" → logged.
> Later: "how much have I got left?" → it knows, because it did the arithmetic,
> not because a language model guessed.

## Getting the APK

There is no Android SDK in this repo, so the APK is built by GitHub Actions.

1. Go to the **Actions** tab → **Build Jarvis APK**.
2. Open the newest run and download the `jarvis-apk` artifact.
3. Unzip, copy the `.apk` to your phone, tap it, allow "install unknown apps".

To get a permanent download link instead, run the workflow manually
(**Run workflow** → tick *Also publish the APK as a GitHub Release*) and the APK
appears under **Releases**, which you can open directly on your phone.

Every build is signed with the throwaway key in `app/keystore/`, so new versions
install over old ones without an uninstall. That key is not a secret and is not
for Play Store use — it exists so upgrades work.

## First run

1. Open **Settings**.
2. Pick a provider and paste an API key. All of these have a free tier:

   | Provider | Free key | Notes |
   |---|---|---|
   | **Groq** | console.groq.com/keys | Fastest. Best default. |
   | **Google Gemini** | aistudio.google.com/apikey | Generous limits, strong quality. |
   | **OpenRouter** | openrouter.ai/keys | Models ending `:free` cost nothing. |
   | **Cerebras** | cloud.cerebras.ai | Very fast. |
   | **Ollama** | — | Your own PC. See below. |

3. Set your name so it addresses you properly.
4. Go back to **Voice**, tap the orb, talk.

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
