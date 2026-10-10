package com.lukas.jarvis

import com.lukas.jarvis.data.Aisle
import com.lukas.jarvis.data.ListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AislesTest {

    private fun aisles(vararg items: String) = items.associateWith { Aisle.of(it) }

    @Test fun everydayThingsFindTheirAisle() {
        val expected = mapOf(
            "apples" to Aisle.Produce, "Tomatoes" to Aisle.Produce, "free-range eggs" to Aisle.Dairy,
            "oat milk" to Aisle.Dairy, "sourdough bread" to Aisle.Bakery, "chicken breast" to Aisle.Meat,
            "spaghetti" to Aisle.Cupboard, "dark chocolate" to Aisle.Snacks, "sparkling water" to Aisle.Drinks,
            "toilet paper" to Aisle.Household, "toothpaste" to Aisle.Care
        )
        assertEquals(expected, aisles(*expected.keys.toTypedArray()))
    }

    @Test fun theWordThatNamesTheThingWins() {
        assertEquals(Aisle.Drinks, Aisle.of("orange juice"))
        assertEquals(Aisle.Cupboard, Aisle.of("tomato sauce"))
        assertEquals(Aisle.Cupboard, Aisle.of("peanut butter"))
        assertEquals(Aisle.Frozen, Aisle.of("ice cream"))
        assertEquals(Aisle.Care, Aisle.of("sun cream"))
        assertEquals(Aisle.Household, Aisle.of("kitchen roll"))
        assertEquals(Aisle.Frozen, Aisle.of("frozen spinach"))
    }

    @Test fun germanCompoundsAndShortWordsInsideOthers() {
        assertEquals(Aisle.Dairy, Aisle.of("Vollmilch"))
        assertEquals(Aisle.Drinks, Aisle.of("Orangensaft"))
        assertEquals(Aisle.Cupboard, Aisle.of("Reis"))
        assertEquals(Aisle.Frozen, Aisle.of("Eis"))
        assertEquals(Aisle.Bakery, Aisle.of("Vollkornbrot"))
        assertEquals(Aisle.Meat, Aisle.of("Rinderhack"))
        // "ham" is not in "shampoo", "egg" is not in "eggplant"'s whole word.
        assertEquals(Aisle.Care, Aisle.of("shampoo"))
        assertEquals(Aisle.Other, Aisle.of("birthday card"))
    }

    @Test fun aListComesBackInWalkingOrderKeepingItsOwnOrderInside() {
        val items = listOf("toothpaste", "milk", "bananas", "cheese", "bread", "something odd").map { ListItem(it) }
        val order = Aisle.inShopOrder(items)
        assertEquals(listOf(Aisle.Produce, Aisle.Bakery, Aisle.Dairy, Aisle.Care, Aisle.Other), order.map { it.first })
        assertEquals(listOf("milk", "cheese"), order.first { it.first == Aisle.Dairy }.second.map { it.text })
    }

    @Test fun atTheShopTheListIsReadAisleByAisle() {
        val list = com.lukas.jarvis.data.NamedList(
            "shopping",
            listOf(ListItem("milk"), ListItem("apples"), ListItem("bread", done = true), ListItem("bananas"))
        )
        assertEquals("Fruit and veg: apples, bananas\nDairy and eggs: milk", Aisle.walk(list))
        assertEquals("tent, torch", Aisle.walk(com.lukas.jarvis.data.NamedList("packing", listOf(ListItem("tent"), ListItem("torch")))))
        assertEquals(null, Aisle.walk(com.lukas.jarvis.data.NamedList("shopping", listOf(ListItem("milk", done = true)))))
    }

    @Test fun aPlaceReminderKeepsItsList() {
        val watch = com.lukas.jarvis.notify.PlaceWatch.create(
            7, "your shopping list", "Rewe", com.lukas.jarvis.maps.GeoPoint(52.5, 13.4),
            leaving = false, every = true, here = null, list = "shopping"
        )
        val back = com.lukas.jarvis.notify.PlaceWatch.fromJson(watch.toJson())
        assertEquals("shopping", back?.list)
        assertEquals(watch, back?.copy(createdAt = watch.createdAt))
    }

    @Test fun onlyTheShoppingListIsSorted() {
        assertTrue(Aisle.suits("the shopping list"))
        assertTrue(Aisle.suits("Einkaufsliste"))
        assertTrue(Aisle.suits("groceries"))
        assertFalse(Aisle.suits("packing"))
    }
}
