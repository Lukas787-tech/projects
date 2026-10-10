# Changelog

What changed in each release of the Android app, newest first. Releases
before 6.0 were called **Jarvis**; the app, its data and its settings are
the same app, so 6.0 installs over 5.5 and keeps everything.

## 6.0 — Mochi

A new look and a new name, and the same assistant inside. The heads-up
display is gone, replaced by a cosy café: warm paper and foam colours by
day, a roasted brown by night, and **Mochi**, a small steamed-bun character
drawn in pixels, who shows what the assistant is really doing.

**The look**

- **Mochi, the character.** Mochi listens when the microphone is open, thinks
  while a model is working, holds up the tool it is using (an umbrella for the
  weather, a map for directions, a notepad for memory), talks in time with the
  voice, looks worried when something needs your answer, and celebrates when
  a job is done. Mochi also rings along with a timer, looks puzzled when it
  isn't sure what you meant, and dozes off when left alone, sooner at night.
  None of it is for show: each pose comes from what the agent is really
  doing.
- **One canvas instead of tabs.** Talking happens on a single page built from
  *moments*: resting, listening, thinking, working, showing an answer,
  making something, asking you, ringing, finding the way, and getting back on
  track after a hiccup. Each moment
  decides where Mochi sits, which cards show and what the composer offers.
  Answers arrive as cards that suit them: a forecast, a route, a list, a
  plan, a message ready to send. Older cards fold into a shelf.
- **Rooms for the rest.** *Today*, the *Library* (memory, notes, lists, money
  and tasks on one page with shelves), *You* (all the settings), *Powers*
  (every ability), the *Map*, *Music*, *This phone* and *The conversation*.
  Every room has a way back, and every empty room says what to do next.
- **Café colours that you choose.** Ten accents (caramel, honey, apricot,
  berry, rose, sage, matcha, lavender, sky, stone) and a hue slider for any
  other colour. Light, dark or automatic. Every pairing is checked so text
  keeps a contrast of at least 4.5:1. 5.5's accents become their nearest
  café colour.
  Fonts are bundled and open-licensed: Newsreader for headings, DM Sans for
  text and Pixelify Sans for Mochi's speech.
- **Mochi on the home screen.** The launcher icon, the notification icon,
  the widget and the floating bubble are all Mochi now.

**Asks before anything outward**

- Anything that leaves the phone in your name waits on a confirmation card
  that says exactly what will happen, and you can correct it before it goes.
  That covers a text, a chat message or a reply, a call, an email, sharing
  something, and sharing where you are. The same goes for anything hard to
  take back: forgetting a memory, deleting an entry, a task, a routine or a
  place, changing a calendar event, pressing the phone's own buttons, and
  unlocking or opening anything at home. Nothing happens until you say yes,
  and cancelling sends nothing. A test fails the build if any such tool can
  skip the card.

**Local only**

- A new switch under *You → Data*, also offered during the introduction.
  When the pool has a model you added yourself (a free key, or your own
  Ollama or LM Studio), Local only uses only those and never falls back to
  the keyless public models. When it fails, it fails; nothing is quietly
  sent somewhere else.
- When only the keyless models are there, a turn is sealed. What you wrote
  about yourself, your memories, where the phone is, and your contacts,
  calendar, places and weather stay off the conversation. Your name, your
  totals and your open tasks still go along, so it can still count and
  remind. Mochi says plainly when that means it can't answer.

**New things to ask for**

- **Plans.** "Plan my Saturday", "help me get ready for the move": Mochi
  lays out the steps on one card, then offers to start, to put the steps in
  your calendar, or to set a reminder for each one. Nothing is added until
  you say so.
- **Habits.** Any tracker that isn't money ("yoga", "pages read", "gym")
  counts the days in a row. It gets a *Did it* button on Today and in the
  Library, the week as dots and your best run, and every Monday's brief says
  how last week went.
- **Routines you can edit.** Make a routine on Today, rename it and change
  its steps, its time and its days, or delete it.
- **Routines that start by themselves.** "When I plug in at night, turn on
  Do Not Disturb", "when my car connects, tell me the traffic", "when a
  meeting starts, silence the phone". A routine can wait on charging, a
  Bluetooth device connecting or going (named, like "car" or "AirPods"), or
  a calendar event starting. It then runs in the background and sends its
  answer as a notification. Only what some routine waits on is watched.
- **Money that logs itself.** "My rent is 800 every month on the 1st",
  "Netflix 12.99 monthly", "I get paid on the 25th". Each one is logged on
  its day, even if the phone was off, and the Library lists it under its
  tracker with a stop that asks once more.
- **Budgets that speak up.** Once, as it happens: "Heads up: that's 83% of
  this month's budget, with 10 days to go", or by how much it went over, or
  where the month is heading at this pace.
- **This month against last.** "How is this month going?" sets this month
  so far against the same days of last month.
- **Money cards with real charts.** Meters against each budget, bars with a
  mark for last month, and a habit's week as dots, in the café's colours.
  Every row also says its numbers in words.
- **The shopping list at the shop.** "Show my shopping list when I get to
  Rewe" posts what is still to get when you arrive. The shopping list comes
  in the order a shop is walked: fruit and veg, bread, dairy, through to the
  freezer and the household aisle.
- **Trips.** "I'm going to Lisbon on the 14th for five days" sets up a
  countdown, the trip's own packing list for that many nights and the
  weather there, and remembers the trip.
- **Nudges** (off unless you switch them on, under *You → Voice*). Now and
  then, one useful thing unasked: an appointment somewhere in the next hour
  and a half, rain on the way, a budget at nine tenths, a habit's run about
  to end, or what is still open in the evening. Never at night, never the
  same thing twice, three a day at most, a switch for each kind, and worked
  out on the phone without any model.
- **Memory upkeep.** Overnight on the charger, things said twice and plans
  for days long gone are put away, never deleted. The Library lists them,
  each with a way to bring it back. Notes, the journal and anything pinned
  are never touched. The switch is under *You → Data*.
- **A quick model for quick things.** Commands lean towards a small, fast
  model in the pool, and plans and explanations towards a bigger one. A key
  of your own still comes first.
- **Countdowns** can be removed from Today.
- **A *New tracker* button** in the Library, and deleting one asks first.

**Getting started**

- **A new introduction** in six short steps: hello, your names, what Mochi is
  like, the look, whether you mostly talk or type, and *what leaves the
  phone*, which says plainly what a turn sends and lets you switch Local only
  on before the first question.
- **The wake phrase is "hey mochi".** Upgrading keeps your own phrase. Only
  the old defaults ("jarvis", "hey jarvis") become the new one.

**Accessibility**

- Every control has a TalkBack label and a touch target of at least 48 dp.
  CI checks this on every moment, on Today, the Library, You, Powers, Music
  and the conversation, and on each step of the introduction.
- Large text, landscape and *Reduce motion* are supported. With Reduce
  motion, Mochi holds still and the screen changes without animating.

**Under the hood**

- Settings from 5.5 move over by themselves. An assistant still called
  "Jarvis", the old default, becomes "Mochi" with Mochi's own character. A
  name you chose yourself is kept, and so is its personality.
- The pieces of the old interface (the HUD, the arc reactor, the globe flight,
  its fonts) are gone from the app.
- Keys you add are stored encrypted, under a key held by the Android
  Keystore.
- The database gains one table, for money that logs itself. As with every
  step, it only adds; nothing in a 5.5 database is changed.
- A backup can be locked with a passphrase. It carries your keys and every
  memory, so it is worth locking.
- **Not changed:** the app's package (`com.lukas.jarvis`), so 6.0 updates the
  installed app instead of sitting beside it. All of your data stays.

## 5.5

- **Reminders at a place** — "remind me to buy milk when I get home", "when
  I leave work, remind me to call Mum", "every time I get to the gym…". The
  phone's own location service watches the spot, so nothing runs in between
  and no Google services are needed. They sit on the Tasks tab under *At
  places*, and one set while you are already there waits for the next arrival.
- **The phone's own buttons by voice** — "lock the phone", "take a
  screenshot", "go back", "open notifications", "quick settings", through the
  screen access you already switched on for reading.
- **Routines that run themselves** — "every weekday at 7:30, tell me if I
  need an umbrella": a quiet routine runs at its time with the app closed and
  sends the answer as a notification. Routines can keep to weekdays,
  weekends or any days you name.
- **An interpreter** — "be my interpreter for Spanish" opens a two-sided
  screen: each person taps their own button and speaks, and every line is
  translated and read aloud in the other language (hands-free, it then
  listens for the answer). Typing works too, for a loud room.
- **Says foreign words in their own voice** — in "thank you in Japanese is
  ありがとう", the Japanese is read by a Japanese voice. Chinese, Korean,
  Russian, Greek, Arabic, Hebrew, Hindi, Thai and more work the same way, using
  whichever voices the phone has installed.
- **Remembers with no connection** — "remember that my locker code is 3917"
  is kept even when no model can be reached, and "what's my locker code?" is
  answered from memory the same way.
- **Profiles** — save a whole look, character and voice under a name
  ("Night", "Work", "Mark III") and switch with one tap in Settings → Look (now You → Look),
  or by saying "switch to night mode".
- **Routines that start at a place** — "when I get home, run my evening
  routine": the routine runs in the background on arrival and its answer
  arrives as a notification.
- **Focus sessions** — "focus for 25 minutes" or "start a pomodoro" turns on
  Do Not Disturb for that long and puts a Focus timer on screen that rings at
  the end. Works offline too.
- **Sleep timer** — "stop the music in 30 minutes": when it runs out the
  music pauses and nothing rings. Works offline too.
- **A journal** — "dear diary, …" or "journal: …" keeps an entry in your own
  words (even offline), the evening wrap-up notification has a *Write in
  journal* box, and "what did I write last week?" reads them back.
- **"Remember this"** — long-press any line of the conversation to keep it
  in memory.
- **Three more backdrops** — Aurora, Ember and Abyss, for eight in all.
- **Rides out a busy moment** — a model that fails with a passing error (a
  502 from a busy gateway, a dropped connection) gets one more try before the
  turn gives up.
- **Fixes** — "take me home" before home was saved used to search the map
  for a place called "Home"; a saved "car" matched "Carrefour"; the Today
  greeting could say "sir" after the name was set, or "Good morning" all
  afternoon; a timer whose alarm was held back sat at 0:00; the translation
  service's quota notice could be read out as a translation.

## 5.4

- **Lists** — shopping, packing, anything without a time, by voice or on the
  new Lists tab; "Einkaufsliste" and "groceries" are the same list.
- **Timers Jarvis runs itself** — named, on screen and in the shade, ringing
  until stopped (a tap, or just "stop"), and "how long is left on the pasta"
  has an answer.
- **The phone's controls** — volume, brightness, Do Not Disturb with an end
  time, and the Devices screen as a control centre.
- **Calendar that finishes the job** — events are added, moved and cancelled
  in your calendar, not just filled in, and one with a place gets a "time to
  leave" reminder from the real travel time.
- **What's playing** — the song, artist and app, and controls that reach the
  app that is playing.
- **Weather with more in it** — when the rain starts, sunrise and sunset, UV,
  air quality and pollen; the town's name and the weekday.
- **Morning brief and evening wrap-up** as notifications at times you choose.
- **Works offline** — timers, alarms, reminders, the torch, volume, music,
  lists, apps, sums and more are understood with no connection at all, and
  the header says when you are offline.
- **Backup that keeps everything** — memories, tasks, trackers, lists and the
  conversation, not only settings and keys.
- **Your own colour** — any accent from a rainbow slider, and each
  personality speaks at its own pace and pitch.
- **Routines to start from** — Morning, Bedtime and Heading out, one tap on
  Today.
- **The day on the home screen** — the widget shows the weather, what is
  due and the next appointment.
- **Fixes** — a Today screen that crashed on phones that do not report their
  battery, a clock squeezed into one column, a core that stayed open whenever
  the location was known, repeating tasks that ended when ticked off, numbers
  like "2,50" or "50%" misread, and more — most of them found by rendering
  the app in CI.
