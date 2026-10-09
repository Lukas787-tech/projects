# Jarvis 6.0 — design, feature and tool update

Status: draft for review. Nothing here is built yet.

## 1. What was decided

| Question | Answer |
|---|---|
| Design | **Full redesign.** New visual language and navigation, not a polish of the HUD. |
| Features | **Smarter assistant, phone control, daily-life tools.** |
| "Tools" | **The assistant's tools**: the functions the model can call (`llm/Tools.kt`, `ToolCatalog.kt`). |
| Release shape | **One big 6.0.** |

One thing was *not* selected but the plan cannot skip it: **safety and trust**
(confirmation before outward actions, encrypted keys). Phone control and
daily-life tools both add more things the assistant can do on its own, so
Phase 0 below puts a confirmation gate under all of it. If you disagree, say so
and I will cut it back to the minimum (the SMS/call gate only).

## 2. What we are building on (verified in the code)

- **Tools:** about 100 tools in 22 families (`ToolGroup`). The largest are Phone (20),
  Places (11) and Messages (10). `ToolCatalog` already holds name, family, status
  line, chip and a `readOnly` flag. The *handlers* are all in one 2,795-line
  `Tools` class, and the *schemas* are elsewhere (`Prompt.kt`, `ToolRouter.kt`).
- **Agent loop:** `llm/Agent.kt` runs rounds of tool calls, runs read-only tools
  in parallel, and has a claim check ("said it did X but did nothing").
  `ModelPool` spreads load over providers; two keyless providers are the default.
- **Memory:** SQLite (`data/Db.kt`) with a hand-rolled token index
  (`memory_tokens`). Lexical search only, with importance and recency. No embeddings.
- **Automation:** routines (`auto/`) with time and place triggers, timers,
  stopwatch, place reminders, a morning brief, an evening journal prompt.
- **UI:** about 33 Compose files. `ui/theme/Theme.kt` has user-chosen accents;
  screens are Voice (the HUD), Today, Tasks, Hub (memory, lists, trackers),
  Skills, Settings, Map, History, Interpreter.
- **Tests:** 123 unit tests, plus Robolectric screenshot tests and an
  emulator smoke test in CI.

## 3. Principles

1. **The assistant may read freely, may change your own data with an undo, and
   must ask before anything that leaves the phone or can't be undone.**
2. **Every tool is declared once**: schema, handler, family, risk level and
   tests live together. Nothing is half-known in two places.
3. **Redesign by tokens, not by screen.** The new look is a token set and a
   small component library first; screens are migrated onto it afterwards.
4. **Everything new works offline or degrades clearly.** The app promises "no
   account, no key, no cost"; keep that promise.
5. **Nothing merges to the 6.0 branch without its tests and screenshots.**

## 4. Phases

A single release does not mean a single step. Work in this order on one
long-lived branch (`release/6.0`), merging `main` into it weekly.

### Phase 0 — Foundations (must come first)

| # | Work | Size |
|---|---|---|
| 0.1 | **Risk levels on tools.** Add `risk` to `ToolInfo`: `Read`, `Local` (changes your own data, undoable), `Outward` (sends or spends: SMS, call, email, share, calendar invite), `Sensitive` (deletes, device settings, screen control). | S |
| 0.2 | **Confirmation gate** in `Agent.runOne`. `Outward` and `Sensitive` tools return a pending action; the UI shows a card ("Text Anna: '…' — Send / Edit / Cancel"). Voice mode accepts "yes / send it / cancel". Optional per-contact "trusted" skip. | M |
| 0.3 | **Tool registry refactor.** Split `Tools.kt` into one handler class per `ToolGroup`. Each registers `{name, schema, risk, group, handler}` in one registry; `ToolCatalog`, `Prompt` and `ToolRouter` read from it. Behaviour unchanged; existing tests must pass untouched. | L |
| 0.4 | **Encrypted keys.** Keystore-backed storage for API keys; one-time migration from the plain preference files; passphrase-optional vault export with a clear warning on the plain option. | M |
| 0.5 | **Schema migrations.** `Db.onUpgrade` gets a numbered, tested migration path (new tables for the features below). | S |
| 0.6 | **Stop committing screenshots to `main`.** A redesign means hundreds of `[screens]` runs; today each adds ~100 PNGs and the repo is already ~570 MB. Publish them as workflow artifacts or to a throwaway branch. | S |
| 0.7 | **Release signing.** Real key from CI secrets for release; R8 on. | S |

Exit: all existing behaviour intact, SMS/call blocked until confirmed (with
tests that prove the tool does nothing before confirmation), one registry.

### Phase 1 — Design system (the redesign, part 1)

Inputs I need from you: references you like (apps, screenshots, a mood), and
what to keep from the HUD, if anything (the arc-reactor core? the accent colour
choice? the character/persona system?).

| # | Work | Size |
|---|---|---|
| 1.1 | **Design brief**: audit the 80 existing screenshots in `docs/screens`, name what is wrong (density, navigation depth, two parallel ways to do things), write 5-7 design principles. | S |
| 1.2 | **Information architecture.** Today there are ~10 destinations. Proposal: **Talk** (assistant), **Today** (day at a glance), **Library** (memory, lists, trackers, notes) and **Settings/Powers**. Tasks and Map become views inside Today and Talk. | M |
| 1.3 | **Tokens**: colour (light + dark + user accent, replacing the single-dark-glass assumption in `Theme.kt`), type scale, spacing, radius, elevation, motion. Large-text and landscape/tablet from day one. | M |
| 1.4 | **Component library**: card, list row, chip, input, sheet, confirmation card (from 0.2), tool-trail, empty state, loading skeleton. Each has a preview and a screenshot test. | L |
| 1.5 | **Voice/Talk surface**: the new "core", transcripts, live tool trail, interruption and barge-in. | L |

### Phase 2 — Smarter assistant

| # | Work | Size |
|---|---|---|
| 2.1 | **Better memory retrieval.** Keep the offline lexical index; add optional on-device embeddings (small model via ML Kit/MediaPipe — *to evaluate for size and speed*). Rank by relevance × importance × recency; show "why I remembered this". | L |
| 2.2 | **Memory upkeep.** Nightly consolidation: merge duplicates, summarise old conversations into memories, expire stale facts (with a review screen so you can undo). | M |
| 2.3 | **Multi-step plans.** A `plan` mode in the agent: for requests like "plan my Saturday" it writes a short plan, runs the steps, and reports one result. Reuses the existing claim check. | M |
| 2.4 | **Proactive suggestions.** WorkManager job that, a few times a day, looks at calendar, tasks, weather and places and offers at most one useful nudge ("rain at 5 — you cycle home"). Rate-limited, off by default, per-kind switches. | M |
| 2.5 | **Model routing.** Cheap/fast model for simple tool calls, stronger one for planning, on-device fallback offline. Builds on `ModelPool`/`RateGuard`. | M |
| 2.6 | **Privacy mode.** "Local only" switch: memory, contacts, calendar and location never go to the keyless providers; the app says so before the first message. | S |

### Phase 3 — Phone control

| # | Work | Size |
|---|---|---|
| 3.1 | **Triggers for routines.** Extend `auto/Routines` beyond time and place: charging, Wi-Fi/Bluetooth connect, headphones, calendar event start/end, notification received. | M |
| 3.2 | **Screen-aware help.** Build on `read_screen`/`ScreenReader`: "reply to this", "add this to my calendar", "what does this error mean". Always user-invoked, never background. | M |
| 3.3 | **Notification triage.** Using `ReplyListener`: summarise unread, draft replies (confirmed via 0.2), quiet-hours digest. | M |
| 3.4 | **Powers screen.** One place listing every permission/service (SMS, calls, accessibility, notification access, overlay, background location), whether it is on, what it was last used for, and a revoke button. | M |
| 3.5 | **Action log.** Every outward or sensitive action is recorded (what, when, confirmed how) and viewable/undoable where possible. | S |

### Phase 4 — Daily-life tools

Each is built as a new tool family on the Phase 0 registry, using the existing tables where possible.

| # | Work | Size |
|---|---|---|
| 4.1 | **Day planner.** Time-blocking from tasks + calendar + travel time (`Navigator`); "move my gym to tomorrow". | L |
| 4.2 | **Money, next level.** Budgets with alerts, recurring entries, monthly report with charts (the `spending_report` tool already exists). | M |
| 4.3 | **Habits and goals.** Streaks on top of trackers; weekly review in the morning brief. | M |
| 4.4 | **Notes and journal.** First-class notes (the evening journal line is currently just a notification action), searchable by the memory index. | M |
| 4.5 | **Shopping + errands.** Lists tied to places ("remind me at the supermarket", already possible with place reminders) and a store-order hint. | S |
| 4.6 | **Travel helper.** Trip object: tickets, notes, route, weather, packing list. | M |
| 4.7 | **Email digest** (read-only). *Depends on the connector story; evaluate before committing.* | ? |

### Phase 5 — Screens migration and release

| # | Work | Size |
|---|---|---|
| 5.1 | Migrate every remaining screen onto the new components; delete old theme code. | L |
| 5.2 | Onboarding rewrite: explain the free providers and privacy mode, then ask for only the permissions needed now. | M |
| 5.3 | Accessibility pass (TalkBack labels, contrast, large text, reduced motion) and landscape/tablet pass. | M |
| 5.4 | Update README and screenshots, write the 6.0 changelog, upgrade-from-5.5 test (migrations, key migration, settings kept). | M |

## 5. Testing

- **Unit:** every new handler gets tests; every `Outward`/`Sensitive` tool gets a "does nothing before confirmation" test.
- **Migration:** a test that opens a 5.5-format database and preference set and checks 6.0 reads it.
- **Screenshots:** extend `ScreensTest` for the new screens, plus large-text and landscape variants.
- **Device smoke:** extend `.github/scripts/device-smoke.sh` for the confirmation card, a trigger-based routine and a plan run.
- **Manual before release:** real-phone check of SMS/call confirmation, accessibility services, background location.

## 6. Risks

- **The redesign erases recent work.** The last ~15 commits were HUD iteration. Decide what is kept before Phase 1.
- **One big release means a long branch.** Mitigation: weekly merges from `main`, feature flags for Phases 2-4, and a dated internal checkpoint at the end of each phase (installable APK).
- **Scope.** Phases 2-4 together are larger than Phases 0-1. If time runs short, cut 4.6, 4.7 and 2.1 (embeddings) first. Do not cut Phase 0.
- **Model quality.** Planning, proactive suggestions and screen help depend on model ability; the keyless models are weak. Each feature needs a "works with the weakest model" test, or a clear "needs a key" label.
- **On-device models** add APK size and device-specific behaviour. Treat as an experiment with a size budget.

## 7. Open questions for you

1. **Design direction:** any reference apps or a mood (calm, playful, technical, minimal)? What, if anything, of the HUD stays?
2. **Email digest (4.7):** is reading email in scope at all? It brings OAuth and a privacy burden.
3. **On-device model (2.1, 2.5):** how much extra APK size is acceptable?
4. **Proactive suggestions (2.4):** welcome, or too intrusive for your taste?
5. **Timeline:** is there a target date? That decides what is cut.
6. **Repo history (0.6):** are you willing to rewrite history to shrink `.git`? It needs a force-push.
