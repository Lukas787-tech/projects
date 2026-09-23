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
notification. Repeating tasks roll themselves forward. "Move that to Friday",
"snooze it ten minutes" and "cancel the dentist" move or remove the task and its
alarm rather than adding a second one.

**Searches the web.** For anything current or outside the model's knowledge.

**Does the arithmetic itself.** Percentages, splitting a bill, unit prices and
conversions go through a parser rather than through the model, which is the
difference between a number that is right and a number that looks right. The
same goes for dates — "how many days until Christmas", "what date is three weeks
from now" are counted by `java.time`, not guessed — and for money in another
currency, which is converted at the day's European Central Bank rate
(Frankfurter, with open.er-api.com as the fallback; no key either way).

**Takes things back.** "Undo that" removes the last entry from its tracker and
reads out the corrected balance, so a misheard amount is one sentence away from
fixed.

**Tells you the weather.** Real forecasts for where you are or anywhere you
name, from Open-Meteo — no key, no account.

**Runs the phone.** Alarms and timers in your own clock app, the torch, the
ringer, the clipboard, battery and network and storage readouts, any installed
app by name, and any page of Android settings.

**Reaches people, and finishes the job.** Texts are sent, WhatsApp, Signal and
Telegram messages are answered straight from their notification, and new chats
are typed out and sent where accessibility allows. A call is read back — name
and number — and only rings after you say yes. Emails and calendar events are
still filled in for you to send or save, and it says so rather than claiming
the job is done.

**Reads your day.** With calendar and contacts switched on it knows what is on
today and who is in your address book. Ask "how does my day look" and it
gathers the weather, what is due, the next appointment and your budgets in one
pass — the same day the **Today** screen draws.

**Looks back.** It searches not only its memory but the conversations
themselves: "what did I tell you about the landlord" finds the turn.

**Finds places and draws the way there.** "I'm hungry, what's around here" pins
real restaurants on a map with distances; "how do I get to the second one" draws
the route and tells you the distance, the time and the first turns. Tap
**Navigate** on any pin to hand it to Google Maps for turn-by-turn. The data is
OpenStreetMap — Overpass for what is nearby, Nominatim for names and addresses,
OSRM for routing, and the standard OSM tiles for the map itself, which is why
there is no API key and no bill here either. Location stays on the phone; it is
only ever sent as the coordinates of a lookup.

**Speaks.** Replies are read aloud, and hands-free mode hands the microphone
straight back so you can keep talking.

**Shows its work.** While a turn runs, the tools it reaches for appear one by
one under the dot — *Memory*, *Weather*, *Calendar* — with the newest one
pulsing, so a slow answer says what it is waiting on. Once it has answered, the
same chips stay under the reply as a receipt for where each number came from.

**Says what it can do.** The **Skills** screen (the sparkle on the assistant
screen, or just ask "what can you do") lists every ability with the sentences
that reach it. Tap one and it is asked for real.

Each group of abilities is a switch, on the Skills screen and in **Settings ->
Abilities**. Turning one off takes its tools away from the model entirely, which
keeps a small free-tier model's choices short — and calendar and contacts only
ask for their Android permission at the moment you switch them on. A switched-off
tool is never run behind your back, even if a model asks for it by name.

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
  brief/      the day, gathered once for the screen and the spoken answer
  control/    the phone: media, device switches, app and intent handovers,
              contacts, calendar
  core/       settings, time parsing, the calculator, unit conversion, dates
  data/       SQLite schema, models, BM25 retrieval, tracker maths
  llm/        OpenAI-compatible client, tool definitions and catalog, agent
              loop, prompt
  voice/      speech recognition, text to speech, wake word service
  maps/       places, routing, location, map tiles and state
  web/        DuckDuckGo search, page reader, weather, exchange rates
  notify/     alarms and notifications
  ui/         theme and design tokens, shared parts, screens, the map canvas
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
- **A small model's mistakes are caught, not paid for.** `get_weather` is taken
  to mean `weather`, a lookup asked for twice in one turn is answered from the
  first result, and a turn that starts asking for what it already has is told
  to answer instead of spending more rounds. Lookups that do not depend on each
  other run side by side, so weather, calendar and a web search cost one wait.
