package com.lukas.jarvis.llm

/**
 * Local only, as the agent applies it.
 *
 * With a key of your own, a turn simply never goes to the free keyless
 * models. With nothing but those, the turn still happens, sealed: no
 * memories, nothing you wrote about yourself, not where the phone is, and
 * none of the tools whose answers would say who you know, what you have
 * planned or where you are. Mochi is told, so it can say so instead of
 * pretending not to know.
 */
object Privacy {

    /** Families whose answers say who you know, what you have planned, or where you are. */
    private val PERSONAL = setOf(ToolGroup.People, ToolGroup.Calendar, ToolGroup.Places, ToolGroup.Weather)

    /** True for a tool that may not reach a keyless model in a sealed turn. */
    fun isPersonal(tool: String): Boolean {
        val info = ToolCatalog.info(tool) ?: return false
        // Reading memories out is personal; writing one down is the person's own words anyway.
        return info.group in PERSONAL || (info.group == ToolGroup.Memory && info.readOnly)
    }

    /** What the sealed context says in place of everything left out. */
    const val NOTE =
        "LOCAL ONLY is on and this answer comes from a free keyless model, so the user's memories, " +
            "what they wrote about themselves, their contacts, their calendar, the weather and places " +
            "for where they are, and where the phone is, are all kept off this conversation. If they " +
            "ask for one of those, say so plainly, and that adding a free key of their own in You -> " +
            "Brain lets you use them privately. Never guess at them."
}
