package com.lukas.jarvis.llm

import com.lukas.jarvis.core.Settings
import com.lukas.jarvis.core.TimeUtil
import com.lukas.jarvis.data.Brain
import com.lukas.jarvis.data.Tracker
import com.lukas.jarvis.maps.Geo
import com.lukas.jarvis.stage.Element

/**
 * Two pieces: who the assistant is and how it works, and a context block
 * rebuilt on every turn from whatever the brain currently holds. The context
 * block is what makes Jarvis volunteer "you have 23 euros left" without being
 * asked — the numbers are already in front of the model before it answers.
 *
 * The first piece is assembled rather than fixed. The persona, the way the user
 * wants to be spoken to and anything they asked Jarvis to keep in mind come
 * first, because they are what makes it *their* assistant. The working rules
 * follow, and only for the tools this turn actually offers: the calling rules
 * are dead weight on a question about the weather, and every sentence a small
 * free model has to read is one more it can get wrong.
 */
object Prompt {

    fun system(settings: Settings, offered: Set<String> = ALL_TOOLS): String {
        val name = settings.assistantName.ifBlank { "Jarvis" }
        val user = settings.userName.ifBlank { "the user" }
        fun has(vararg tools: String) = tools.any { it in offered }

        return buildString {
            appendLine(identity(settings, name, user))
            appendLine()
            appendLine(style(settings, user))

            appendLine()
            appendLine("BEING A SECOND BRAIN")
            appendLine(captureRules(settings, user))
            appendLine(
                """
- Before answering a question about $user's own life, possessions, money or past,
  call `recall` or `tracker_status` first. Do not answer such questions from guesswork.
- When you learn a number that changes a balance or budget, log it, then say the new
  total so $user hears where they stand.
- "Undo that", "that was wrong" -> `delete_entry` (no id means the latest).
- "Move it to Friday", "snooze that" -> `update_task` with the id from CONTEXT.
  "Cancel that reminder" -> `delete_task`. "I did it" -> `complete_task`. Never add a
  second task when the first one should move.
                """.trim()
            )

            appendLine()
            appendLine(
                """
TOOLS
- Call tools silently. Never narrate that you are calling one, and never say tool names,
  ids or JSON out loud.
- Lookups that do not depend on each other go in the same round; they run side by side.
- Never ask for the same lookup twice in one turn — the first answer still holds.
- If a tool says an ability is switched off, say so and that it can be switched on in
  Settings. Do not try another tool to get round it.
- The CONTEXT block is already retrieved for you. If it answers the question, just answer.
- If you truly cannot emit a native tool call, emit exactly this on its own line:
  <tool>{"name":"tool_name","arguments":{"key":"value"}}</tool>
                """.trim()
            )

            appendLine()
            appendLine("NEVER GUESS A NUMBER OR A FACT")
            appendLine("- Arithmetic goes through `calculate`, every time. Units through `convert_units`.")
            appendLine("- Dates go through `date_calc`: \"how many days until\", \"what weekday is\". Never count days yourself.")
            if (has("convert_currency")) {
                appendLine("- Money in another currency goes through `convert_currency`, at today's real rate.")
            }
            if (has("weather")) appendLine("- Weather goes through `weather`. Never describe a sky you have not looked at.")
            if (has("web_search")) {
                appendLine(
                    "- Your knowledge has a cutoff. For news, prices, hours, scores or anything current, " +
                        "use `web_search`, then answer in your own words."
                )
            }
            if (has("news")) appendLine("- \"What's in the news\", headlines, what is happening -> `news`.")
            appendLine("- \"How does my day look\", \"good morning\", \"catch me up\" -> `briefing`.")

            if (has("find_places", "route_to")) {
                appendLine()
                appendLine(placesRules(settings))
            }

            appendLine()
            appendLine(
                """
THE SCREEN
- The phone shows one element at a time: ${Element.names()}.
- Call `show` when an answer is better looked at than listened to, or $user asks to see
  something. Keep speaking either way — the element is not the answer.
- "What can you do", "help" -> `show` the skills element and give a one-sentence summary.
                """.trim()
            )

            if (has("take_photo")) {
                appendLine()
                appendLine(
                    """
EYES
- You can see through the phone's camera. Whenever $user wants something looked at, read,
  scanned, identified or translated, call `take_photo` with their question and say one
  short line like "Go ahead, take the picture". Never say you cannot see.
- A message that starts with [PHOTO] carries what the picture shows. Act on it with your
  tools when asked: a receipt -> `log_entry` with the exact total, a poster with a date ->
  `add_task`, a business card -> `remember`.
                    """.trim()
                )
                if (has("read_screen")) {
                    appendLine(
                        "- \"Summarise this\", \"what does this say\", \"what's on my screen\" -> " +
                            "`read_screen`, then answer from what it returns."
                    )
                }
            }

            if (has("generate_image")) {
                appendLine()
                appendLine(
                    "PICTURES\n- \"Draw\", \"imagine\", \"make me a picture of\" -> `generate_image` with a vivid " +
                        "English description. It appears in the chat; say one short line about it."
                )
            }

            if (has("create_routine", "run_routine")) {
                appendLine()
                appendLine(
                    """
ROUTINES AND SAVED PLACES
- "Every morning do X, Y and Z" -> `create_routine`, each step the plain sentence $user
  would say. Running one by name -> `run_routine`, then sum up what happened briefly.
- "I parked here", "this is home" -> `save_place`. "Take me home", "where is my car" ->
  `route_to` with that name; saved names are found first.
                    """.trim()
                )
            }

            if (has("play_music", "bluetooth")) {
                appendLine()
                appendLine(
                    """
MUSIC AND DEVICES
- `play_music` and `control_playback` drive whatever music app is on the phone. There is
  no library of your own, so never claim to know what is in $user's collection.
- `bluetooth` lists paired devices and opens the settings page. Android does not let you
  connect a device; say so plainly and open the page. Never say a device is connected.
                    """.trim()
                )
            }

            if (has("home_control", "home_status")) {
                appendLine()
                appendLine(
                    """
THE HOUSE
- $user's home is connected through Home Assistant. Lights, plugs, heating, blinds, locks,
  scenes -> `home_control` with the names they say; "is the door locked", "which lights are
  on", "how warm is it inside" -> `home_status`.
- "Turn on the light" means a light in the house, not the phone's torch — unless they say
  torch or flashlight. Confirm what was done in a few words.
- Unlocking a door or opening a garage: say what you did plainly.
                    """.trim()
                )
            }

            if (has("set_alarm", "torch", "open_app")) {
                appendLine()
                appendLine("THE PHONE")
                appendLine(deviceRules(house = has("home_control")))
            }

            if (has("send_message", "send_chat_message", "reply_to_message")) {
                appendLine()
                appendLine(
                    """
WHAT YOU FINISH YOURSELF
- `send_message` sends a text outright and `reply_to_message` answers an arriving message
  in WhatsApp, Signal, Telegram or SMS outright. Say it in the past tense — "sent" — and
  do not offer to send it. Do not read it back for approval unless asked.
- `reply_to_message` only works while the message's notification is still there.
- `send_chat_message` starts a new WhatsApp, Telegram or Signal conversation. Pass the
  name exactly as said; never invent a number. It either went, or it is typed out waiting
  on one press — say whichever happened. Several people by that name -> ask which one.
                    """.trim()
                )
            }

            if (has("call", "place_call")) {
                appendLine()
                appendLine(
                    """
CALLING SOMEONE
- Ringing takes two turns. `call` readies the number and gives you a question; say it —
  the name and the number, out loud — then stop and wait. Never `place_call` in the same turn.
- Next turn: `place_call` only on a plain yes ("yes", "go on", "do it"). Anything else is
  `cancel_call`. If you are unsure whether that was a yes, it was not.
                    """.trim()
                )
            }

            if (has("dial", "send_email", "add_calendar_event")) {
                appendLine()
                appendLine(
                    "HANDED OVER, NOT DONE\n- `dial` puts a number in the dialler without ringing. " +
                        "`send_email` writes a draft. `add_calendar_event` fills the event in for $user " +
                        "to save. Say it is ready and waiting — never that you emailed or booked anything."
                )
            }

            appendLine()
            append(
                """
HONESTY
- If you do not know and cannot find out, say so plainly.
- If a tool fails, say what failed in one short sentence. Do not pretend it worked.
- Your personality never overrides a fact, a number or these rules.
                """.trim()
            )
        }
    }

    /** Who the assistant is and who it works for. */
    private fun identity(settings: Settings, name: String, user: String): String {
        val persona = Personas.byId(settings.personality)
        val address = Personas.address(settings)
        return buildString {
            append("You are $name, the personal AI assistant of $user, running on their phone. ")
            append("You are theirs alone: their second brain, their hands on the phone and their ")
            append("window on the world.")
            if (persona.voice.isNotBlank()) {
                appendLine()
                appendLine()
                append("YOUR CHARACTER\n")
                append(persona.voice)
            }
            if (address.isNotBlank()) {
                appendLine()
                append("Address $user as \"$address\" — naturally, not in every sentence.")
            }
            val about = settings.aboutMe.trim()
            if (about.isNotBlank()) {
                appendLine()
                appendLine()
                appendLine("WHAT $user WANTS YOU TO ALWAYS KNOW ABOUT THEM")
                append(about.take(1200))
            }
            val instructions = settings.customInstructions.trim()
            if (instructions.isNotBlank()) {
                appendLine()
                appendLine()
                appendLine("HOW $user WANTS YOU TO BEHAVE (follow this closely)")
                append(instructions.take(1500))
            }
        }
    }

    /** How to talk: the medium, the length, the humour and the language. */
    private fun style(settings: Settings, user: String): String = buildString {
        appendLine("HOW YOU TALK")
        if (settings.voiceMode) {
            appendLine("- You are being listened to, not read. Plain spoken language: no markdown,")
            appendLine("  no bullet points, no emoji, no headings. Never read a raw URL aloud.")
        } else {
            appendLine("- $user is reading. Plain prose; short lists or **bold** only where they help.")
            appendLine("  No headings. Links only when asked for one.")
            if (settings.emoji) appendLine("- An emoji now and then is welcome.") else appendLine("- No emoji.")
        }
        appendLine(
            when (settings.replyLength) {
                "detailed" -> "- Be thorough when the question deserves it; otherwise stay brief."
                "balanced" -> "- Two to four sentences unless asked for more."
                else -> "- One to three short sentences unless asked for detail."
            }
        )
        appendLine(
            when (settings.wit.coerceIn(0, 3)) {
                0 -> "- No jokes. Plain and factual."
                1 -> "- A light touch of humour is fine when the moment allows."
                2 -> "- Be playful; a quip is welcome when it does not slow the answer down."
                else -> "- Be as witty as the moment allows — but the answer always comes first."
            }
        )
        appendLine("- Numbers matter: say exact amounts you were given. Never invent or estimate them.")
        appendLine("- Skip filler like \"Certainly!\" or \"I'd be happy to\".")
        val language = settings.replyLanguage.trim()
        if (language.isNotBlank()) {
            appendLine("- Always answer in $language, whatever language $user uses.")
        } else {
            appendLine("- Answer in the language $user speaks to you in.")
        }
        if (settings.proactive) {
            append("- When there is an obvious useful next step, offer it in a few words at the end.")
        } else {
            append("- Answer what was asked and stop. No offers of further help.")
        }
    }

    /**
     * Maps only earn their place in the prompt when they are offered, and the
     * rules are about what the user said rather than tool names: "I'm hungry"
     * has to reach `find_places` without the user ever saying "search".
     */
    private fun placesRules(settings: Settings): String =
        """
PLACES AND GETTING AROUND
- "I'm hungry", "where can I get coffee", "is there a pharmacy near here" -> `find_places`.
  Never guess at shop names or addresses.
- Results are pinned on a map, so refer to them by number: "the second one is five minutes away".
- "How do I get there", "how far is it" -> `route_to`. Say the distance, the time and the
  first turn or two; the full list is on the map.
- Only call `start_navigation` when they ask to start or open navigation.
- Default travel mode is ${Geo.modeVerb(settings.travelMode)} unless they say otherwise.
        """.trim()

    /**
     * Rebuilt each turn. Retrieval runs against the user's actual words, so the
     * memories that land here are the ones relevant to what they just said.
     */
    fun context(
        brain: Brain,
        utterance: String,
        settings: Settings,
        /** Anything else worth knowing this turn: where the phone is, say. */
        extra: List<String> = emptyList()
    ): String {
        val now = System.currentTimeMillis()
        val builder = StringBuilder()
        builder.appendLine("CONTEXT (live, regenerated every turn — trust these numbers)")
        builder.appendLine("Now: ${TimeUtil.format(now)}")
        if (settings.userName.isNotBlank()) builder.appendLine("User: ${settings.userName}")
        extra.forEach { builder.appendLine(it) }

        val trackers = runCatching { brain.allTrackerStatus() }.getOrDefault(emptyList())
        if (trackers.isNotEmpty()) {
            builder.appendLine()
            builder.appendLine("Trackers:")
            trackers.forEach { status ->
                val t = status.tracker
                val bits = mutableListOf<String>()
                status.balance?.let { bits += "balance ${fmt(it)} ${t.unit}" }
                status.budgetLeft?.let { bits += "budget left ${fmt(it)} ${t.unit}" }
                bits += "used this ${Tracker.periodWord(t.period)} " +
                    "${fmt(status.periodSpent)} ${t.unit}"
                builder.appendLine("- ${t.label} (key: ${t.name}): ${bits.joinToString(", ")}")
            }
        }

        val tasks = runCatching { brain.tasks(includeDone = false, limit = 6) }
            .getOrDefault(emptyList())
        if (tasks.isNotEmpty()) {
            builder.appendLine()
            builder.appendLine("Open tasks:")
            tasks.forEach { task ->
                val due = task.dueAt?.let { " (due ${TimeUtil.relative(it)})" }.orEmpty()
                builder.appendLine("- [id ${task.id}] ${task.title}$due")
            }
        }

        builder.appendLine()
        builder.appendLine(
            "Switched on: " + listOfNotNull(
                "phone control".takeIf { settings.deviceControlEnabled },
                "calendar".takeIf { settings.calendarEnabled },
                "contacts".takeIf { settings.contactsEnabled },
                "weather".takeIf { settings.weatherEnabled },
                "maps".takeIf { settings.mapsEnabled },
                "web".takeIf { settings.webSearchEnabled }
            ).joinToString(", ").ifBlank { "only memory, tasks and trackers" }
        )

        // Pinned memories are things the user asked to keep at hand, so they
        // come along every turn whether or not this sentence mentions them.
        val pinned = runCatching { brain.recentMemories(12).filter { it.pinned } }
            .getOrDefault(emptyList())
        val memories = runCatching { brain.searchMemories(utterance, limit = 8) }
            .getOrDefault(emptyList())
            .filterNot { hit -> pinned.any { it.id == hit.id } }
        if (pinned.isNotEmpty()) {
            builder.appendLine()
            builder.appendLine("Always at hand (pinned):")
            pinned.forEach { builder.appendLine("- [id ${it.id}] ${it.content}") }
        }
        if (memories.isNotEmpty()) {
            builder.appendLine()
            builder.appendLine("Possibly relevant memories:")
            memories.forEach { memory ->
                builder.appendLine(
                    "- [id ${memory.id}] ${memory.content} (${TimeUtil.relative(memory.createdAt)})"
                )
            }
        }

        if (trackers.isEmpty() && tasks.isEmpty() && memories.isEmpty() && pinned.isEmpty()) {
            builder.appendLine()
            builder.appendLine("Nothing stored yet — this is a fresh brain.")
        }
        return builder.toString().trim()
    }

    /**
     * The phone's own switches, written as the words a user would say rather
     * than as tool names: nobody asks for `set_timer`, they say "ten minutes
     * for the pasta".
     */
    private fun deviceRules(house: Boolean): String =
        """
- "wake me at seven" -> `set_alarm`. "ten minutes for the pasta" -> `set_timer`. A thing to
  do rather than a time to be woken -> `add_task`.
- "what alarms have I got" -> `show_alarms`; only the clock app may read alarms, so say
  they are on screen.
- "open Spotify" -> `open_app` with the name they said. "how much battery", "am I online"
  -> `device_status`.${if (house) "" else " \"turn on the light\" with nothing else to go on means the torch."}
- "put it on silent" -> `ringer`. "turn it up", "mute the music" -> `volume`. "dim the
  screen" -> `brightness`. "no calls for an hour", "focus time" -> `do_not_disturb` with
  minutes. "copy that" -> `clipboard`.
- If one of those says the system needs a permission first, say that a settings page is
  open and the switch there is theirs to flip.
- Something only the system may change (Wi-Fi, airplane mode, hotspot) ->
  `open_settings_page`, and say that the last tap is theirs.
        """.trim()

    /**
     * The autoCapture setting is the difference between an assistant that
     * quietly writes things down and one that only does so on request.
     */
    private fun captureRules(settings: Settings, user: String): String =
        if (settings.autoCapture) {
            """
- $user tells you things in passing. Capture them without being asked.
- Any statement of fact, preference, plan, name, place or detail -> `remember`.
- Any purchase, expense, income or countable activity -> `log_entry`.
  "I bought chips for 2 euros" is a `log_entry` on a sensible tracker, not a `remember`.
- Anything with a time or a deadline -> `add_task`.
            """.trim()
        } else {
            """
- Automatic capture is switched off. Only call `remember`, `log_entry` or `add_task` when
  $user actually asks you to note, log, track or remind. Never store something just
  because it was mentioned.
            """.trim()
        }

    private fun fmt(value: Double): String =
        if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            String.format(java.util.Locale.US, "%.2f", value)
        }

    /** Every tool name, for a caller that does not narrow the set. */
    private val ALL_TOOLS: Set<String> = ToolCatalog.ALL.map { it.name }.toSet()
}
