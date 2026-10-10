package com.lukas.jarvis.llm

import com.lukas.jarvis.auto.Routine
import com.lukas.jarvis.auto.Routines
import com.lukas.jarvis.brief.Briefer
import com.lukas.jarvis.control.Agenda
import com.lukas.jarvis.control.Caller
import com.lukas.jarvis.control.Chat
import com.lukas.jarvis.control.Chats
import com.lukas.jarvis.control.Device
import com.lukas.jarvis.control.Launcher
import com.lukas.jarvis.control.Messenger
import com.lukas.jarvis.control.People
import com.lukas.jarvis.control.Phone
import com.lukas.jarvis.control.ScreenReader
import com.lukas.jarvis.core.Calculator
import com.lukas.jarvis.core.DateMath
import com.lukas.jarvis.core.Settings
import com.lukas.jarvis.core.TimeUtil
import com.lukas.jarvis.core.Units
import com.lukas.jarvis.data.Brain
import com.lukas.jarvis.data.ChatMessage
import com.lukas.jarvis.data.Entry
import com.lukas.jarvis.data.Memory
import com.lukas.jarvis.data.Task
import com.lukas.jarvis.data.Tracker
import com.lukas.jarvis.data.TrackerStatus
import com.lukas.jarvis.data.Money
import com.lukas.jarvis.auto.Trigger
import com.lukas.jarvis.data.Recurring
import com.lukas.jarvis.moment.Bar
import com.lukas.jarvis.moment.Chart
import com.lukas.jarvis.maps.Geo
import com.lukas.jarvis.maps.Locator
import com.lukas.jarvis.maps.Navigator
import com.lukas.jarvis.maps.PlacesClient
import com.lukas.jarvis.stage.Element
import com.lukas.jarvis.stage.StageStore
import com.lukas.jarvis.notify.ReplyListener
import com.lukas.jarvis.notify.Reminders
import com.lukas.jarvis.web.Currency
import com.lukas.jarvis.web.Home
import com.lukas.jarvis.web.Imagine
import com.lukas.jarvis.web.Knowledge
import com.lukas.jarvis.web.Weather
import com.lukas.jarvis.vision.CameraBus
import com.lukas.jarvis.web.WebTools
import kotlinx.coroutines.async
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// Messages and calls. Whatever leaves the phone waits on a yes first.

/**
 * A text, sent rather than drafted.
 *
 * The point of asking an assistant to send something is not having to pick
 * the phone up, so a draft waiting on a screen is a job half done. The
 * sentence that comes back is in the past tense only when it really went.
 */
internal fun Tools.sendMessage(args: JSONObject): String {
    val text = args.optString("text").trim()
    if (text.isBlank()) return "There was no message to send."
    val number = args.optString("number")
    // This used to hand the message to the phone's own messaging app when
    // the permission was missing, which pushed Jarvis into the background
    // and looked exactly like the app closing. Asking for the permission
    // keeps everything where the user left it.
    if (!messenger.maySend) {
        messenger.requestPermission()
        return "I need permission to send texts — it is asking you now. " +
            "Say it again once you have allowed it."
    }
    return messenger.sendSms(number, text)
}

/**
 * A new conversation in WhatsApp, Telegram or Signal.
 *
 * The name is resolved against contacts here rather than by the model,
 * because a model guessing a phone number is how the right message reaches
 * the wrong person.
 */
internal fun Tools.sendChatMessage(args: JSONObject): String {
    val chat = Chat.match(args.optString("app"))
        ?: return "I can start a conversation in WhatsApp, Telegram or Signal."
    val who = args.optString("who").trim()
    val text = args.optString("text").trim()
    if (who.isBlank()) return "Who should it go to?"
    if (text.isBlank()) return "There was no message to send."
    return chats.send(chat, who, text)
}

/**
 * An answer to whatever just came in, through the notification it arrived on.
 *
 * This is the only route into WhatsApp and the like that Android supports,
 * and it only exists while the notification does — once the message has been
 * read on the phone, its notification is gone and so is the way back in.
 * Saying that is more use than a generic failure.
 */
internal fun Tools.replyToMessage(args: JSONObject): String {
    val text = args.optString("text").trim()
    if (text.isBlank()) return "There was no reply to send."

    val target = ReplyListener.match(args.optString("who").takeIf { it.isNotBlank() })
        ?: return if (ReplyListener.waiting().isEmpty()) {
            "Nothing is waiting that I can answer. A message can only be answered " +
                "while its notification is still there."
        } else {
            "I cannot find that conversation among the ones waiting."
        }

    return if (ReplyListener.reply(target, text)) {
        "Replied to ${target.from.ifBlank { target.appLabel }}."
    } else {
        "${target.appLabel} would not take the reply."
    }
}

internal fun Tools.unreadMessages(): String {
    val waiting = ReplyListener.waiting()
    if (waiting.isEmpty()) return "Nothing is waiting to be answered."
    return waiting.take(8).joinToString("\n") { item ->
        val who = item.from.ifBlank { "Someone" }
        "$who on ${item.appLabel} (${TimeUtil.relative(item.postedAt)}): " +
            item.text.take(200)
    }
}
