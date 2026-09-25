package com.lukas.jarvis.control

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder

/** A messaging app this can start a conversation in. */
enum class Chat(val label: String, val packageName: String, val spoken: List<String>) {
    WhatsApp("WhatsApp", "com.whatsapp", listOf("whatsapp", "whats app", "wa")),
    Telegram("Telegram", "org.telegram.messenger", listOf("telegram")),
    Signal("Signal", "org.thoughtcrime.securesms", listOf("signal"));

    companion object {
        fun match(raw: String?): Chat? {
            val text = raw?.trim()?.lowercase().orEmpty()
            if (text.isBlank()) return null
            return entries.firstOrNull { chat -> chat.spoken.any { text.contains(it) } }
        }
    }
}

/**
 * Starting a conversation in someone else's messaging app.
 *
 * Answering a message that arrived is easy — its notification carries a reply
 * box. Starting one is the hard direction, because none of these apps offers a
 * way in. What they do offer is a link that opens a chat with the text already
 * written, and from there the only thing missing is the press.
 *
 * So this is two halves that have to both work: the link opens the chat, and
 * [Tapper] presses send. Where the second half is not switched on, the message
 * is still sitting there typed out — which is worth saying, because it is one
 * tap from done rather than lost.
 */
class Chats(context: Context, private val people: People) {

    private val app = context.applicationContext

    fun installed(chat: Chat): Boolean = runCatching {
        app.packageManager.getPackageInfo(chat.packageName, 0)
        true
    }.getOrDefault(false)

    /**
     * Sends [text] to [who] in [chat].
     *
     * A name is looked up in contacts, because these apps are addressed by phone
     * number and nobody says a phone number out loud. An ambiguous name is
     * refused rather than guessed: sending the right message to the wrong Ralf
     * is not a small mistake.
     */
    fun send(chat: Chat, who: String, text: String): String {
        if (text.isBlank()) return "There was no message to send."
        if (!installed(chat)) return "${chat.label} is not on this phone."

        val target = resolve(who)
            ?: return "I could not find a number for $who in your contacts."
        if (target.ambiguous) {
            return "There is more than one $who in your contacts — " +
                "${target.candidates.joinToString(" and ")}. Which one?"
        }

        val opened = open(chat, target.number, text)
        if (!opened) return "${chat.label} would not open that chat."

        return if (Tapper.isEnabled(app)) {
            // Armed after the link has gone out, so the window covers the app
            // starting up and drawing the chat rather than starting before it.
            Tapper.armFor(chat.packageName)
            "Sent to ${target.name} on ${chat.label}."
        } else {
            "The message to ${target.name} is typed out in ${chat.label}, waiting on " +
                "one press. Switch on Jarvis in accessibility settings and I will " +
                "press it myself next time."
        }
    }

    /**
     * The number as WhatsApp's link wants it: country code and all, digits
     * only. A saved "0151 …" means nothing to wa.me without the country, so
     * it is completed from the phone's own network or SIM country.
     */
    private fun international(number: String): String? {
        val trimmed = number.trim()
        if (trimmed.startsWith("+")) return trimmed.filter { it.isDigit() }
        if (trimmed.startsWith("00")) return trimmed.filter { it.isDigit() }.removePrefix("00")
        val telephony = app.getSystemService(android.telephony.TelephonyManager::class.java)
        val country = listOfNotNull(telephony?.networkCountryIso, telephony?.simCountryIso, java.util.Locale.getDefault().country)
            .map { it.uppercase(java.util.Locale.ROOT) }
            .firstOrNull { it.length == 2 }
            ?: return null
        return android.telephony.PhoneNumberUtils.formatNumberToE164(trimmed, country)
            ?.filter { it.isDigit() }
    }

    private fun open(chat: Chat, number: String, text: String): Boolean {
        val digits = if (chat == Chat.WhatsApp) international(number) ?: return false else number.filter { it.isDigit() }
        val body = URLEncoder.encode(text, "UTF-8")
        // wa.me is WhatsApp's own documented link and the most reliable of the
        // three; the others take a plain sendto with their package named.
        val uri = when (chat) {
            Chat.WhatsApp -> Uri.parse("https://wa.me/$digits?text=$body")
            else -> Uri.parse("smsto:$number")
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            if (chat != Chat.WhatsApp) {
                action = Intent.ACTION_SENDTO
                putExtra("sms_body", text)
                setPackage(chat.packageName)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            app.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private data class Target(
        val name: String,
        val number: String,
        val ambiguous: Boolean = false,
        val candidates: List<String> = emptyList()
    )

    private fun resolve(who: String): Target? {
        val typed = who.filter { it.isDigit() || it == '+' }
        // Already a number: nothing to look up and nothing to get wrong.
        if (typed.length >= 5 && who.count { it.isLetter() } == 0) {
            return Target(who.trim(), typed)
        }
        val hits = people.find(who, limit = 4).filter { !it.number.isNullOrBlank() }
        if (hits.isEmpty()) return null
        if (hits.size > 1) {
            return Target("", "", ambiguous = true, candidates = hits.map { it.name })
        }
        val only = hits.first()
        return Target(only.name, only.number.orEmpty())
    }
}
