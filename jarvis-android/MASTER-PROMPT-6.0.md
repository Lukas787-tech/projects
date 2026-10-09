# Master prompt — Mochi 6.0 "Café"

Paste this into a fresh coding session at the repo root. It supersedes the design
direction in `PLAN-6.0.md` (Phase 1); the phases, risks and test strategy there
still apply.

---

## 1. Mission

You are rebuilding the front of **Mochi** (formerly Jarvis), an Android assistant
app (`jarvis-android/`, Kotlin, Jetpack Compose, minSdk 26), into **6.0**. The
brain stays: agent loop, tools, memory, routines, Fish Audio voice, free keyless
models. Everything the user sees and touches is redone.

The pitch: **a cozy, café-warm app where a small pixel friend with real
personality does the work in front of you, and the screen rearranges itself
around whatever is happening right now — creating, controlling, or showing.**

The feeling: your friend who happens to have unlimited powers. Calm, warm,
quietly capable, never corporate, never a dashboard.

## 2. Name

- The product and the default assistant are both called **Mochi**.
  The user can still rename the assistant (`assistantName`); only the default changes.
- Default wake phrase: "hey mochi" (configurable).
- Rename everything the user can see: launcher label, notifications, quick-settings
  tile, widget, shortcuts, onboarding, prompts, default persona, README, screenshots.
  Retire the Stark / arc-reactor wording and the "Stark gold" style names.
- **Do not change the `applicationId` (`com.lukas.jarvis`), the Kotlin package, or
  the `jarvis-android/` folder in this release.** A new `applicationId` is a
  different app to Android: 5.5 users could not update in place and would lose
  their keys and memories, and the CI workflows are path-bound. A package/folder
  rename is a separate, later change.
- Migrate stored settings: if `assistantName` is still the untouched default
  "Jarvis", set it to "Mochi"; if the user changed it, leave it alone. Same for
  the wake phrase.

## 3. Non-negotiables

1. **Zero dead ends.** Every screen, state, error and empty state offers a next
   step. No blank screens, no "nothing here", no modal without an exit, no
   error without a retry or an alternative. (Section 7.)
2. **One canvas.** Create, control and see all happen in the same UI, not in
   separate apps-within-the-app. The assistant never sends the user elsewhere to
   finish something it started.
3. **Dynamic, not static.** The UI composes itself from the current *moment*
   (Section 6). There is no fixed home screen layout.
4. **The character is the face of the AI.** It is visible whenever the AI is
   doing anything, and its animation is driven by real agent state, never faked
   or looped regardless of what is happening.
5. **Ask before anything outward or irreversible.** Texts, calls, emails,
   shares, deletes and device changes show a confirmation card first (see
   `PLAN-6.0.md` Phase 0.1–0.2). Do not ship new tools that skip this.
6. **Nothing existing silently breaks.** The 123 existing unit tests keep
   passing; behaviour that is intentionally replaced gets its test replaced.
7. **No APK size limit.** Use that freedom (on-device models, bundled fonts,
   richer assets) but keep cold start under ~2 s on a mid-range phone.

## 4. Visual language: "latte café"

**Mood:** warm white, soft paper, steamed milk, a little caramel. The light theme
is the primary design; a dark roast ("night café") theme is secondary and must be
equally polished.

**Palette** (starting values — tune by eye, keep contrast ≥ 4.5:1 for text):
- Foam (background) `#FBF7F1`, Paper (cards) `#FFFDF9`, Latte (surfaces) `#EFE4D6`
- Espresso (primary text) `#3A2E28`, Cocoa (secondary text) `#7B6A5E`
- Caramel (default accent) `#C58A5B`, Sage `#8FA68E`, Berry `#B8605F` (warnings), Honey `#E3B65C`
- Night café (dark): Roast `#1F1815`, Mocha `#2B221E`, text `#F3E9DD`
- The user-chosen accent system stays, with warm presets first.

**Type:** an editorial warm serif for headlines and the assistant's voice
(candidates: Newsreader, Source Serif 4, Fraunces), a friendly humanist sans for
UI and body (Inter or DM Sans), and a pixel face (Pixelify Sans, Silkscreen) used
sparingly for the character's speech tags, timers and tiny status labels. All
fonts open-licensed (OFL) and **bundled in the app** so it works offline. Do not
use proprietary fonts.

**Shape and motion:** big soft radii, paper-like shadows, generous spacing, no
hard borders or neon. Motion is slow and springy: things settle, they do not
snap. Honour the system's reduce-motion setting everywhere.

**Tokens first:** colour, type, spacing, radius, elevation and motion are defined
once in `ui/theme`; no hard-coded values in screens. Large text, landscape and
tablet work from day one.

## 5. The character

**Brief:** Mochi is a cozy, cute, **pixel-art** creature that visibly does the
work. Its form is **your design choice** — a soft, round body suggests itself, but
decide. Make it original and memorable, and do **not** copy any existing mascot
(including Claude's). It must read at 24 px in a status bar and at 160 px as the
hero.

**Technical shape:**
- Authored on a small grid (e.g. 32×32 or 48×48), limited palette from the latte
  set, rendered crisp (nearest-neighbour, integer scaling) in Compose Canvas or
  from a sprite sheet.
- A **state machine** driven by agent events (the `Agent` rounds and tool calls):
  idle, listening, thinking, working (one pose or prop per `ToolGroup`: writing
  for notes and tasks, searching for web, map-reading for places, tapping for
  phone control, counting coins for money…), speaking, success, confused, error
  (apologetic, never alarming), waiting-for-your-yes, alert (timer or reminder
  ringing), sleepy (night, long idle), delighted (small wins).
- **Speaking** syncs to the Fish voice: mouth and body follow audio amplitude if
  the player exposes it, otherwise a text-timed approximation. The voice itself
  does not change.
- It **shows** the work: it carries the card it is making, hands you what it
  found, points at the map or globe when it is looking something up.

**Soul (this is the point):**
- A consistent personality tied to the existing persona and `assistantName`
  settings; the pixel friend is that persona made visible. Add a warm "friend"
  persona as the new default.
- It reacts to context: weather, time of day, your streaks, a finished timer, a
  memory it just made ("I'll remember that" with a small nod). It has idle
  behaviours (stretches, sips something, looks at the globe) that never block or
  distract while you are doing something.
- It is honest on screen: if it is unsure it looks unsure; if a tool fails it
  shows what failed and what it will try next.
- Small, rare delights over constant gimmicks. Nothing loops obnoxiously.
  Everything is skippable and tied to a setting.

## 6. The dynamic UI engine

Define a **Moment** — one value derived from agent state, tool results, device
state and time — and a **composer** that turns it into a layout.

Moments (extend as needed): `Resting`, `Listening`, `Thinking`, `Working(group)`,
`Showing(kind)` (map, globe, list, chart, image, text, camera, screen),
`Creating(kind)` (note, task, list, routine, tracker entry, message, event, image,
plan), `Asking` (confirmation or clarification), `Alerting` (timer, reminder,
incoming message, place arrival), `Navigating`, `Recovering` (an error with
options).

For each moment the composer decides:
- the character's pose and size,
- which **cards** appear, in what order (max three visible, one clear primary
  action; everything else is one gesture away),
- which **controls** are offered right now (only ones that make sense),
- what the background does (the globe or map recedes or leads).

Rules:
- **Tools declare their view.** In the tool registry (`PLAN-6.0.md` 0.3), each
  tool states the card it produces and the follow-up actions it offers, so new
  tools get UI without touching the composer.
- Layout changes are animated and interruptible; the user can always grab a card,
  dismiss it, pin it, or ask about it.
- **Pin and shelve:** pinned cards stay; the rest settle into a quiet history
  strip. Nothing is lost when the moment changes.
- Typing, voice and touch are equal ways in at every moment.

## 7. Zero dead ends: concrete rules

1. Every card has at least one forward action (do it, change it, undo it, ask more).
2. Every result offers 1–3 contextual follow-ups ("route there", "remind me",
   "save this place"), generated from the tool that produced it.
3. Every empty state *does* something: a tappable prompt, or the character
   offering to start ("Want me to set up a shopping list?").
4. Every error names what happened in plain words and offers a retry and an
   alternative (another model, offline mode, a manual path).
5. Every permission denial leads to what still works and a one-tap way to grant it.
6. Every confirmation can be edited, not only accepted or cancelled.
7. Back, close and undo always exist, and always do what they say.
8. **Enforced by a test:** a Robolectric test walks every Moment and asserts a
   reachable primary action exists. A dead end fails CI.

## 8. Create · Control · See (one canvas)

**Create** — anything the AI makes appears live as an editable **draft card**
while it works: notes, tasks, lists, routines, tracker entries, calendar events,
messages, images, plans. The user can edit it as it forms, then accept, adjust or
discard. Creating is a first-class surface, not a chat reply.

**Control** — live control tiles that appear when relevant: timers and stopwatch,
media, volume, brightness, torch, ringer, Do Not Disturb, apps, home devices,
calls and messages (always via confirmation), routines and their triggers. Show
current state, not just buttons.

**See** — the showcase. The **globe** is the hero: an ambient, beautiful home
presence that shows where you are, flies to places when you ask, draws routes and
flights, and recedes when a card needs the room. The **map** is the working view
for places, routes, saved places and navigation. Plus charts for money and
habits, images, camera and vision results, and screen-reading results, all in the
same warm style (no neon, no default chart colours).

## 9. Engineering rules

- Reuse what exists: `Agent`, `ToolCatalog`, `Brain`, `Routines`, `ModelPool`,
  `ui/globe`, `ui/map`, the Fish voice integration. Do the Phase 0 foundations
  (risk levels, confirmation gate, tool registry, key encryption, migrations)
  before building UI on top of them.
- New UI lives under `ui/` on the new tokens. Migrate in this order: design
  system → character → composer + Talk surface → Today → Library (memory, lists,
  trackers, notes) → Map and Globe → Settings and Powers → onboarding. Delete old
  theme and screen code as each is replaced.
- Compose previews and screenshot tests for each component and each Moment, in
  light, dark, large-text and landscape.
- Performance: 60 fps animation on mid-range hardware, no per-frame allocation in
  the character renderer, background work limited to what the user switched on.
- Accessibility: TalkBack labels for every card and the character's state (as
  text), reduce-motion, contrast, touch targets ≥ 48 dp.
- Work on branch `release/6.0`; small commits; keep the build green; screenshots
  go to workflow artifacts, not into `main` (`PLAN-6.0.md` 0.6).

## 10. Deliverables, in order

1. Tokens, fonts and component library (with previews and screenshots).
2. Character: sprite set, state machine, renderer, wired to real agent events,
   voice-synced.
3. Moment model, composer, and the new Talk surface (create, control and see in one).
4. The dead-end test and the first end-to-end flows: ask → work → result → follow-up.
5. Remaining screens migrated; old UI deleted; rename to Mochi complete.
6. Smarter assistant, phone control and daily-life features from `PLAN-6.0.md`
   Phases 2–4, each shipping with its card, view and follow-ups.
7. Onboarding, accessibility pass, upgrade-from-5.5 test (keys, memories and
   settings survive), README and changelog.

## 11. Definition of done

- On a real phone: you can say or type anything, watch Mochi do it, and finish
  the task without leaving the canvas.
- No Moment fails the dead-end test; every outward action is confirmed first.
- Light and dark both look intentional; large text and landscape work.
- A 5.5 install updates in place to 6.0 and keeps everything.
- A new user sees the free, no-key path first and understands what leaves the phone.
- All tests pass, including the new Moment, migration and confirmation tests.

## 12. Ask me when

You are choosing the character's form, changing the palette beyond tuning, adding
a permission, or cutting a feature to hold the schedule. Otherwise decide, build,
and tell me what you chose.
