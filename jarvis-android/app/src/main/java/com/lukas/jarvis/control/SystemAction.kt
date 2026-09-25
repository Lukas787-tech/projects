package com.lukas.jarvis.control

import java.util.Locale

/**
 * The phone's own buttons — back, home, the notification shade, the lock —
 * pressed by voice through the screen access the user already switched on for
 * reading. Each says which Android first offered it, and what to say once it
 * is done.
 */
enum class SystemAction(
    val id: String,
    val minSdk: Int,
    val done: String,
    private val phrases: List<String>
) {
    Back("back", 16, "Back.", listOf("go back", "back", "zurück", "geh zurück")),
    Home("home", 16, "Home screen.", listOf("go home", "home screen", "go to the home screen", "startbildschirm")),
    Recents(
        "recents", 16, "Your recent apps.",
        listOf("recent apps", "recents", "app switcher", "show recent apps", "letzte apps")
    ),
    Notifications(
        "notifications", 16, "Notifications are open.",
        listOf(
            "open notifications", "open my notifications", "show notifications", "notification shade",
            "pull down notifications", "benachrichtigungen öffnen", "zeig benachrichtigungen"
        )
    ),
    QuickSettings(
        "quick_settings", 17, "Quick settings are open.",
        listOf("quick settings", "open quick settings", "schnelleinstellungen")
    ),
    PowerMenu("power_menu", 21, "The power menu is open.", listOf("power menu", "open the power menu")),
    SplitScreen("split_screen", 24, "Split screen.", listOf("split screen", "geteilter bildschirm")),
    Lock(
        "lock", 28, "Locked.",
        listOf(
            "lock the phone", "lock my phone", "lock phone", "lock the screen", "lock screen",
            "lock it", "handy sperren", "bildschirm sperren", "sperr das handy", "sperre das handy"
        )
    ),
    Screenshot(
        "screenshot", 28, "Screenshot taken.",
        listOf(
            "screenshot", "take a screenshot", "take screenshot", "screen shot", "take a screen shot",
            "grab the screen", "bildschirmfoto", "mach einen screenshot", "mach ein bildschirmfoto"
        )
    );

    companion object {

        val ids: List<String> = entries.map { it.id }

        fun byId(raw: String?): SystemAction? {
            val key = raw?.trim()?.lowercase(Locale.ROOT)?.replace(' ', '_').orEmpty()
            return entries.firstOrNull { it.id == key }
                ?: entries.firstOrNull { action -> action.phrases.any { it.replace(' ', '_') == key } }
        }

        /**
         * A sentence that is plainly one of these and nothing more, for when
         * no model can be reached. "Go back to what we said about Rome" is not
         * the back button, so the whole sentence has to be the command.
         */
        fun heard(raw: String): SystemAction? {
            var text = raw.trim().lowercase(Locale.ROOT).trimEnd('?', '.', '!')
            FILLER.forEach { text = text.removePrefix(it).trim() }
            text = text.removeSuffix(" please").removeSuffix(" bitte").trim()
            return entries.firstOrNull { action -> action.phrases.any { it == text } }
        }

        private val FILLER = listOf(
            "hey jarvis", "jarvis,", "jarvis", "please", "can you", "could you", "would you", "bitte", "kannst du"
        )
    }
}
