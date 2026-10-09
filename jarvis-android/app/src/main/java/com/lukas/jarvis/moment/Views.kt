package com.lukas.jarvis.moment

/**
 * What a card is for, from the person's side.
 *
 * [Show] answers or displays something, [Create] is a thing Mochi made that you
 * can edit, keep or throw away, [Control] is a live tile for something on the
 * phone, and [Ask] waits for your yes.
 */
enum class CardMode { Show, Create, Control, Ask }

/**
 * The card a tool's result is shown on.
 *
 * Every tool declares one in the registry ([com.lukas.jarvis.llm.ToolCatalog]),
 * so a new tool gets a place on the canvas without the composer learning its
 * name. The composer only knows kinds.
 */
enum class CardKind(val mode: CardMode, val label: String) {
    /** Words, and nothing better to show them on. */
    Answer(CardMode.Show, "Answer"),

    /** Sums, units and dates the phone worked out itself. */
    Exact(CardMode.Show, "Worked out"),
    Brief(CardMode.Show, "Your day"),
    Weather(CardMode.Show, "Weather"),
    Places(CardMode.Show, "Places"),
    Route(CardMode.Show, "Route"),
    Map(CardMode.Show, "Map"),
    Chart(CardMode.Show, "Numbers"),
    Web(CardMode.Show, "From the web"),
    Calendar(CardMode.Show, "Calendar"),
    Contact(CardMode.Show, "Contact"),
    Camera(CardMode.Show, "What I saw"),
    Screen(CardMode.Show, "Your screen"),
    Translation(CardMode.Show, "Translation"),
    Inbox(CardMode.Show, "Messages"),
    Memory(CardMode.Create, "Memory"),
    Note(CardMode.Create, "Note"),
    Task(CardMode.Create, "Reminder"),
    List(CardMode.Create, "List"),
    Entry(CardMode.Create, "Tracker"),
    Routine(CardMode.Create, "Routine"),
    Event(CardMode.Create, "Event"),
    Picture(CardMode.Create, "Picture"),
    Plan(CardMode.Create, "Plan"),
    Place(CardMode.Create, "Place"),
    Message(CardMode.Create, "Message"),
    Call(CardMode.Create, "Call"),
    Timer(CardMode.Control, "Timer"),
    Stopwatch(CardMode.Control, "Stopwatch"),
    Device(CardMode.Control, "Phone"),
    Media(CardMode.Control, "Music"),
    Home(CardMode.Control, "Home"),
    Settings(CardMode.Control, "Settings"),

    /** A confirmation waiting on your yes. */
    Confirm(CardMode.Ask, "Needs your OK"),

    /** Something went wrong, with what to do about it. */
    Problem(CardMode.Show, "Hiccup");
}

/**
 * Something to do next, offered under a result: "Route there", "Remind me".
 *
 * [say] is a sentence handed back to Mochi as if you had said it, so a follow-up
 * needs no code of its own. `{place}`-style slots are filled from the card;
 * a follow-up whose slots the card cannot fill is simply not offered.
 */
data class FollowUp(
    val label: String,
    val say: String
) {
    /** This follow-up with its slots filled, or null when one of them is unknown. */
    fun fill(slots: kotlin.collections.Map<String, String>): FollowUp? {
        var label = this.label
        var say = this.say
        SLOT.findAll(this.label + " " + this.say).map { it.groupValues[1] }.toSet().forEach { key ->
            val value = slots[key]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            label = label.replace("{$key}", value)
            say = say.replace("{$key}", value)
        }
        return FollowUp(label, say)
    }

    companion object {
        private val SLOT = Regex("\\{([a-z_]+)\\}")
    }
}
