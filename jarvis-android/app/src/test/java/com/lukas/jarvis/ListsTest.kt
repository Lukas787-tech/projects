package com.lukas.jarvis

/*
 * The rules of the user's lists: one of each thing per list, the same list
 * however it is named, ticking off by a word, and the offline sentences that
 * reach them.
 */
import com.lukas.jarvis.data.ListBook
import com.lukas.jarvis.llm.Reflexes
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListsTest {

    @Test fun namesMeanTheSameList() {
        assertEquals("shopping", ListBook.canonical("my shopping list"))
        assertEquals("shopping", ListBook.canonical("Einkaufsliste"))
        assertEquals("shopping", ListBook.canonical("groceries"))
        assertEquals("packing", ListBook.canonical("the packing list"))
        assertEquals("films", ListBook.canonical("films"))
    }

    @Test fun addingTwiceKeepsOneAndUnticks() {
        var book = ListBook().add("shopping", listOf("Oat milk", "eggs"))
        book = book.check("shopping", listOf("eggs"), true).first
        book = book.add("Einkaufsliste", listOf("oat milk", "Eggs", "bread"))
        val list = book.find("shopping")!!
        assertEquals(listOf("Oat milk", "eggs", "bread"), list.items.map { it.text })
        assertFalse(list.items.first { it.text == "eggs" }.done)
    }

    @Test fun singularAndPluralAreOneThing() {
        val book = ListBook().add("shopping", listOf("Eggs", "tomato")).add("shopping", listOf("egg", "Tomatoes", "grass"))
        assertEquals(listOf("Eggs", "tomato", "grass"), book.find("shopping")!!.items.map { it.text })
    }

    @Test fun tickingAndRemovingFindWords() {
        var book = ListBook().add("shopping", listOf("free-range eggs", "bread"))
        val (checked, hit) = book.check("shopping", listOf("eggs"), true)
        assertEquals(listOf("free-range eggs"), hit)
        assertTrue(checked.find("shopping")!!.items.first().done)
        book = checked.clear("shopping", onlyDone = true)
        assertEquals(listOf("bread"), book.find("shopping")!!.items.map { it.text })
        val (after, gone) = book.remove("shopping", listOf("cheese"))
        assertTrue(gone.isEmpty())
        assertEquals(1, after.find("shopping")!!.items.size)
    }

    @Test fun storedAndReadBack() {
        val book = ListBook().add("packing", listOf("passport", "charger")).check("packing", listOf("passport"), true).first
        val back = ListBook.fromJson(book.toJson())
        assertEquals(book.lists.map { it.name to it.items.map { i -> i.text to i.done } },
            back.lists.map { it.name to it.items.map { i -> i.text to i.done } })
        assertTrue(ListBook.fromJson("not json").lists.isEmpty())
    }

    @Test fun offlineSentences() {
        fun parsed(text: String) = Reflexes.parse(text)?.let { it.name to JSONObject(it.argumentsJson) }
        val add = parsed("Add oat milk and eggs to my shopping list")!!
        assertEquals("list", add.first)
        assertEquals("add", add.second.getString("action"))
        assertEquals("shopping", add.second.getString("list"))
        assertEquals("oat milk and eggs", add.second.getJSONArray("items").getString(0))
        val de = parsed("Setz Milch auf die Einkaufsliste")!!
        assertEquals("milch", de.second.getJSONArray("items").getString(0))
        assertEquals("shopping", ListBook.canonical(de.second.getString("list")))
        assertEquals("show", parsed("what's on my packing list")!!.second.getString("action"))
    }
}
