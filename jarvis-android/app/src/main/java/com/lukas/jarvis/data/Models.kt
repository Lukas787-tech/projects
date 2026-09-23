package com.lukas.jarvis.data

/** A single thing Jarvis remembers about you. */
data class Memory(
    val id: Long = 0,
    val kind: String = KIND_FACT,
    val content: String,
    val detail: String? = null,
    val tags: List<String> = emptyList(),
    val importance: Int = 3,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val occurredAt: Long? = null,
    val source: String = "voice"
) {
    companion object {
        const val KIND_FACT = "fact"
        const val KIND_EVENT = "event"
        const val KIND_NOTE = "note"
        const val KIND_IDEA = "idea"
        const val KIND_PREFERENCE = "preference"
        const val KIND_PERSON = "person"
        const val KIND_PLACE = "place"

        val ALL_KINDS = listOf(
            KIND_FACT, KIND_EVENT, KIND_NOTE, KIND_IDEA,
            KIND_PREFERENCE, KIND_PERSON, KIND_PLACE
        )
    }
}

/**
 * A named thing you keep a running number on: a spending category, a card
 * balance, calories, gym sessions, hours worked. Expenses are just the most
 * common shape of this.
 */
data class Tracker(
    val id: Long = 0,
    val name: String,
    val label: String,
    val kind: String = KIND_MONEY,
    val unit: String = "EUR",
    val budget: Double? = null,
    val period: String = PERIOD_MONTHLY,
    val startingBalance: Double? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val archived: Boolean = false
) {
    companion object {
        const val KIND_MONEY = "money"
        const val KIND_COUNT = "count"
        const val KIND_DURATION = "duration"
        const val KIND_QUANTITY = "quantity"

        const val PERIOD_NONE = "none"
        const val PERIOD_DAILY = "daily"
        const val PERIOD_WEEKLY = "weekly"
        const val PERIOD_MONTHLY = "monthly"

        val ALL_KINDS = listOf(KIND_MONEY, KIND_COUNT, KIND_DURATION, KIND_QUANTITY)
        val ALL_PERIODS = listOf(PERIOD_NONE, PERIOD_DAILY, PERIOD_WEEKLY, PERIOD_MONTHLY)

        /** "week", not "weekly" — this reads inside a sentence. */
        fun periodWord(period: String): String = when (period) {
            PERIOD_DAILY -> "day"
            PERIOD_WEEKLY -> "week"
            PERIOD_MONTHLY -> "month"
            else -> "period"
        }
    }
}

/** One movement on a tracker: "chips, 2.00, out". */
data class Entry(
    val id: Long = 0,
    val trackerId: Long,
    val amount: Double,
    val direction: String = DIR_OUT,
    val note: String? = null,
    val category: String? = null,
    val occurredAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val DIR_OUT = "out"
        const val DIR_IN = "in"
    }
}

/** A tracker plus its computed numbers for the current period. */
data class TrackerStatus(
    val tracker: Tracker,
    val periodSpent: Double,
    val periodReceived: Double,
    val periodStart: Long,
    val entryCount: Int,
    val allTimeSpent: Double,
    val allTimeReceived: Double
) {
    /** Money left on the card/wallet, if a starting balance was set. */
    val balance: Double?
        get() = tracker.startingBalance?.let { it - allTimeSpent + allTimeReceived }

    /** Budget left in the current period, if a budget was set. */
    val budgetLeft: Double?
        get() = tracker.budget?.let { it - periodSpent }
}

data class Task(
    val id: Long = 0,
    val title: String,
    val notes: String? = null,
    val dueAt: Long? = null,
    val repeatRule: String = REPEAT_NONE,
    val done: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val notify: Boolean = true
) {
    companion object {
        const val REPEAT_NONE = "none"
        const val REPEAT_DAILY = "daily"
        const val REPEAT_WEEKLY = "weekly"
        const val REPEAT_MONTHLY = "monthly"

        val ALL_REPEATS = listOf(REPEAT_NONE, REPEAT_DAILY, REPEAT_WEEKLY, REPEAT_MONTHLY)
    }
}

data class ChatMessage(
    val id: Long = 0,
    val role: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** The tools a reply used, by name, so the thread can show how it was answered. */
    val tools: List<String> = emptyList()
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}
