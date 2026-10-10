package com.lukas.jarvis

import com.lukas.jarvis.data.Memory
import com.lukas.jarvis.data.Tidied
import com.lukas.jarvis.data.Upkeep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpkeepTest {

    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun m(id: Long, content: String, kind: String = Memory.KIND_FACT, pinned: Boolean = false, importance: Int = 3, updated: Long = now - id * day, occurred: Long? = null) =
        Memory(id = id, kind = kind, content = content, pinned = pinned, importance = importance, createdAt = updated, updatedAt = updated, occurredAt = occurred)

    @Test fun theSameThingSaidTwiceKeepsItsBestTelling() {
        val plan = Upkeep.plan(listOf(m(1, "My locker code is 3917"), m(2, "Locker code: 3917"), m(3, "Anna likes tea")), now)
        assertEquals(listOf(Tidied(2, Upkeep.Reason.Repeat, keptId = 1, at = now)), plan)
    }

    @Test fun differentThingsStay() {
        assertFalse(Upkeep.same("Anna likes tea", "Anna likes coffee"))
        assertFalse(Upkeep.same("Locker code 3917", "Locker code 4821"))
        assertTrue(Upkeep.same("Mum's birthday is on the 3rd of May", "mum's birthday is on the 3rd of may!"))
    }

    @Test fun aPinnedOrMoreImportantTellingIsTheOneKept() {
        val plan = Upkeep.plan(listOf(m(1, "Gate code is 1234"), m(2, "gate code is 1234", pinned = true)), now)
        assertEquals(listOf(Tidied(1, Upkeep.Reason.Repeat, keptId = 2, at = now)), plan)
        val important = Upkeep.plan(listOf(m(1, "Gate code is 1234"), m(5, "gate code: 1234", importance = 5)), now)
        assertEquals(1L, important.single().memoryId)
    }

    @Test fun aPlanForADayLongGoneIsPutAwayAMonthAfter() {
        val old = m(7, "Dentist on Tuesday at 3", kind = Memory.KIND_EVENT, occurred = now - 40 * day)
        val recent = m(8, "Concert on Friday", kind = Memory.KIND_EVENT, occurred = now - 5 * day)
        val mattering = m(9, "Wedding anniversary dinner", kind = Memory.KIND_EVENT, occurred = now - 90 * day, importance = 5)
        assertEquals(listOf(Tidied(7, Upkeep.Reason.Past, at = now)), Upkeep.plan(listOf(old, recent, mattering), now))
    }

    @Test fun notesAndTheJournalAreNeverTouched() {
        val plan = Upkeep.plan(
            listOf(m(1, "Felt good after the run", kind = Memory.KIND_JOURNAL), m(2, "Felt good after the run", kind = Memory.KIND_JOURNAL), m(3, "idea", kind = Memory.KIND_NOTE), m(4, "idea", kind = Memory.KIND_NOTE)),
            now
        )
        assertTrue(plan.isEmpty())
    }

    @Test fun aNightIsASmallTidy() {
        val many = (1L..60L).map { m(it, "The same sentence said again") }
        assertEquals(Upkeep.MOST_A_NIGHT, Upkeep.plan(many, now).size)
    }

    @Test fun theReviewListSurvivesBeingStored() {
        val list = listOf(Tidied(2, Upkeep.Reason.Repeat, 1, now), Tidied(7, Upkeep.Reason.Past, null, now))
        assertEquals(list, Tidied.listFromJson(Tidied.listToJson(list)))
    }
}
