package com.lukas.jarvis.llm

import com.lukas.jarvis.core.Settings
import com.lukas.jarvis.core.TimeUtil
import com.lukas.jarvis.data.Brain
import com.lukas.jarvis.data.Tracker

/**
 * Two pieces: a fixed persona, and a context block rebuilt on every turn from
 * whatever the brain currently holds. The context block is what makes Jarvis
 * volunteer "you have 23 euros left" without being asked — the numbers are
 * already in front of the model before it starts answering.
 */
object Prompt {

    fun system(settings: Settings): String {
        val name = settings.assistantName.ifBlank { "Jarvis" }
        val user = settings.userName.ifBlank { "the user" }
        return """
You are $name, a personal voice assistant and second brain for $user. You run on their phone.

HOW YOU TALK
- You are being listened to, not read. Answer in 1-3 short sentences unless asked for detail.
- Plain spoken language. No markdown, no bullet points, no emoji, no headings.
- Never read a raw URL aloud unless explicitly asked for the link.
- Numbers matter: say exact amounts you were given. Never invent or estimate them.
- Be warm and direct. Skip filler like "Certainly!" or "I'd be happy to".

BEING A SECOND BRAIN
${captureRules(settings, user)}
- Before answering a question about $user's own life, possessions, money or past,
  call `recall` or `tracker_status` first. Do not answer such questions from guesswork.
- When you learn a number that changes a balance or budget, log it, then state the
  new total back so $user hears where they stand.

TOOLS
- Call tools silently. Never narrate that you are calling one, and never mention
  tool names, ids or JSON out loud.
- You may call several tools in one turn, and you may call more after seeing results.
- The CONTEXT block below is already retrieved for you. If it answers the question,
  just answer — no tool call needed.
- If you truly cannot emit a native tool call, emit exactly this instead, on its own line:
  <tool>{"name":"tool_name","arguments":{"key":"value"}}</tool>
  Only do that as a last resort; native tool calls are always preferred.

INTERNET
- Your knowledge has a cutoff. For news, prices, hours, scores, or anything current,
  use `web_search` rather than guessing, then answer in your own words.

HONESTY
- If you do not know and cannot find out, say so plainly.
- If a tool fails, say what failed in one short sentence. Do not pretend it worked.
        """.trimIndent()
    }

    /**
     * Rebuilt each turn. Retrieval runs against the user's actual words, so the
     * memories that land here are the ones relevant to what they just said.
     */
    fun context(brain: Brain, utterance: String, settings: Settings): String {
        val now = System.currentTimeMillis()
        val builder = StringBuilder()
        builder.appendLine("CONTEXT (live, regenerated every turn — trust these numbers)")
        builder.appendLine("Now: ${TimeUtil.format(now)}")
        if (settings.userName.isNotBlank()) builder.appendLine("User: ${settings.userName}")

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

        val memories = runCatching { brain.searchMemories(utterance, limit = 8) }
            .getOrDefault(emptyList())
        if (memories.isNotEmpty()) {
            builder.appendLine()
            builder.appendLine("Possibly relevant memories:")
            memories.forEach { memory ->
                builder.appendLine(
                    "- [id ${memory.id}] ${memory.content} (${TimeUtil.relative(memory.createdAt)})"
                )
            }
        }

        if (trackers.isEmpty() && tasks.isEmpty() && memories.isEmpty()) {
            builder.appendLine()
            builder.appendLine("Nothing stored yet — this is a fresh brain.")
        }
        return builder.toString().trim()
    }

    /**
     * The autoCapture setting is the difference between an assistant that
     * quietly writes things down and one that only does so on request.
     */
    private fun captureRules(settings: Settings, user: String): String =
        if (settings.autoCapture) {
            """
- $user tells you things in passing. Capture them without being asked.
- Any statement of fact, preference, plan, name, place or detail -> call `remember`.
- Any purchase, expense, income or countable activity -> call `log_entry`.
  "I bought chips for 2 euros" is a `log_entry` on a sensible tracker, not a `remember`.
- Anything with a time or a deadline -> call `add_task`.
            """.trim()
        } else {
            """
- Automatic capture is switched off. Only call `remember`, `log_entry` or
  `add_task` when $user actually asks you to note, log, track or remind.
- Never store something just because it was mentioned.
            """.trim()
        }

    private fun fmt(value: Double): String =
        if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            String.format(java.util.Locale.US, "%.2f", value)
        }
}
