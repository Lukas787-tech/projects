# Kickoff architecture

Kickoff is a Tauri 2 desktop app: a Rust backend and a React frontend in a
WebView. The backend does all real work (AI calls, audio, files, secrets). The
frontend renders state and sends commands. The webview never sees a stored
secret and has no network, filesystem or shell access of its own.

```
┌──────────────────────── WebView (React + TS) ────────────────────────┐
│ pages · components · TanStack Query · typed IPC (src/lib/ipc.ts)     │
└──────────────┬───────────────────────────────────▲───────────────────┘
               │ invoke(command)                   │ results / Channel streams
┌──────────────▼───────────── src-tauri (shell) ───┴───────────────────┐
│ commands.rs · state.rs · logging.rs · error.rs                        │
└──────┬──────────────┬──────────────┬──────────────┬──────────────────┘
       │              │              │              │
  kickoff-core   kickoff-providers  kickoff-store  kickoff-platform
  domain logic   presets, clients   SQLite          OS-specific code
  (no I/O deps)  (HTTP, retries)    (no secrets)    (paths, keyring, voices)
```

## Crates

| Crate | Responsibility | Must not |
|---|---|---|
| `crates/core` | Branding, `Secret`, redaction, settings types. Later: brief model, interview, research and kit logic. | Use Tauri, OS APIs, or concrete providers (traits only) |
| `crates/providers` | Provider presets (`data/providers.toml`) and, from M1, the LLM, search, voice and asset clients. | Store anything; it gets keys passed in as `Secret` |
| `crates/store` | SQLite schema, migrations and repositories | Store raw secrets |
| `crates/platform` | Data paths, Windows Credential Manager, system voices, process launch | Contain domain logic |
| `src-tauri` | Wiring: state, commands, logging, Tauri config | Contain domain logic |

Rule of thumb: only `crates/platform` may contain `cfg(windows)`.

## Renaming the app

The display name is `APP_NAME` in `crates/core/src/branding.rs`. Also change
`productName` and the window `title` in `src-tauri/tauri.conf.json`; a unit
test fails until both match. `APP_ID` is the stable identifier for the
credential store, the data folder and the database. Changing it orphans stored
keys, so treat it as permanent.

## Secrets

- **Where secrets are stored.** Raw values live only in Windows Credential Manager (`keyring-core` + `windows-native-keyring-store`), one entry per key, service = `APP_ID`, with *Local* persistence so keys stay on this PC and never roam with a domain profile. SQLite holds metadata only.
- **In memory.** Values are held as `kickoff_core::Secret`. It prints as `[redacted]` and has no `Serialize` impl, so it can't be sent over IPC or written to the database by accident.
- **In logs.** Every log line goes through `redact::RedactingMakeWriter`. It scrubs registered key values and common key shapes (`sk-…`, `gsk_…`, `AIza…`, `Bearer …`, `api_key=…`). Command errors are redacted before reaching the UI.
- **In generated projects.** Projects reference keys by env-var name only. A launcher injects the values at run time (M5), and the kit writer refuses to write any file where `redact::contains_secret` matches.
- **Off Windows** (development only), the app uses an in-memory store that never persists, and the UI says keys can't be saved.

## AI layer

- **Provider abstraction.** `kickoff_core::llm::LlmProvider` (a trait with `list_models`, `chat_stream` and `chat`). There are three implementations in `kickoff-providers`:
  - `OpenAiCompat` covers Gemini, Groq, OpenRouter, Mistral and Cerebras.
  - `Ollama` uses Ollama's native `/api/chat` and `/api/tags`.
  - `Anthropic` uses the Messages API over raw HTTP.
- **Router.** `kickoff_core::llm::Router` takes an ordered list of `Route`s (provider + model + known RPM) and handles rate limits:
  - It holds requests back so each provider stays under its per-minute limit, using the last 60 seconds of requests.
  - On a per-minute 429 it waits for `Retry-After`, or backs off with jitter, then retries.
  - Daily quota, a rejected key, a missing model or an unreachable provider moves it straight to the next route.
  - Once text has been streamed, a failure is reported, not retried.
  - Every wait and fallback is emitted as a `RouterEvent`.
- **Structured output.** `structured_call` makes small models produce valid JSON:
  - It uses the provider's native JSON mode, with unsupported schema keywords stripped.
  - It always puts the schema in the prompt.
  - It extracts the reply leniently (code fences, `<think>` blocks, trailing commas) and validates it with `jsonschema`.
  - On failure it sends up to two repair prompts quoting the validation errors, then moves to the next route.
- **App wiring.** `src-tauri/src/ai.rs` builds routes for a task:
  - The order is the task's own choice first, then the fallback chain.
  - Each route uses the provider's key from the key library and a model picked from the provider's live model list.
  - `AppObserver` records usage per provider per day in `usage_daily` and forwards router events to the UI as `ai-status` events, which appear as toasts.
- **Key library flow.** `src-tauri/src/keys.rs` writes the secret to Credential Manager first and the metadata to SQLite second, deleting the secret again if the second step fails. Every loaded secret is registered with the log redactor. A test scans the database files on disk to prove no secret is written there.

## Data

- **App data folder:** `%APPDATA%\<APP_ID>` contains `<APP_ID>.sqlite3` (WAL), `logs/` (daily rolling, 7 kept), `models/` (Whisper and Piper downloads) and `cache/` (thumbnails).
- **Schema:** `crates/store/src/migrations/*.sql`, applied in order at startup by `rusqlite_migration`. Migrations are forward-only: never edit a released migration, add a new one.
- **Provider presets:** bundled from `data/providers.toml`. `providers.override.toml` in the data folder can replace fields or add entries, and an invalid override is logged and ignored.

## IPC

- **Commands.** Every command is `async` and moves blocking work (SQLite, credential store) to `spawn_blocking`, so the UI thread never stalls.
- **Errors.** Commands return `Result<T, CommandError>`, where `CommandError` is `{ code, message }`.
- **Shared types.** Request and response types derive `ts_rs::TS`. `cargo test` regenerates `src/bindings/*.ts`, and CI fails if the committed bindings are stale.
- **Browser preview.** `src/lib/devMocks.ts` serves commands in a plain browser (`npm run dev`) and in Vitest. It is never loaded inside the Tauri app.

## Frontend

React 19, Vite, Tailwind v4, TanStack Query for command data, react-router (hash routing, because the Tauri asset server has no SPA fallback), lucide icons.

**Why Tailwind.** Theme colors are semantic tokens (`bg-surface`, `text-muted`, `bg-accent`…) defined once in `src/styles.css` and swapped per theme with `data-theme`. Utility classes let the many dense screens (interview, live brief, triage, settings) iterate quickly with no CSS-module bookkeeping, and with no runtime styling cost.

## Testing

| Layer | How |
|---|---|
| Rust domain and storage | `cargo test --workspace`; fake providers, in-memory SQLite |
| HTTP clients (M1+) | `wiremock` tests built from the request and response shapes in official docs |
| Frontend | `vitest` + Testing Library against the mock IPC |
| Windows | GitHub Actions `windows-latest`: `cargo test` (includes a real Credential Manager round trip) and a debug NSIS installer, uploaded as an artifact |
| Manual | A short smoke checklist per milestone on Windows 11 |
