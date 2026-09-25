package com.lukas.jarvis

/*
 * Reminders at a place: which crossings of the circle set one off, how one
 * set while already there waits for the next arrival, and finding one again
 * from a spoken description.
 */
import com.lukas.jarvis.maps.GeoPoint
import com.lukas.jarvis.notify.PlaceWatch
import com.lukas.jarvis.notify.PlaceWatch.Crossing
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceTest {

    private val home = GeoPoint(52.5200, 13.4050)
    private val away = GeoPoint(52.5400, 13.4050) // about 2 km north

    private fun arrival(here: GeoPoint?, every: Boolean = false) =
        PlaceWatch.create(1, "buy milk", "home", home, leaving = false, every = every, here = here)

    @Test fun anArrivalSetFromElsewhereFiresOnTheWayIn() {
        val watch = arrival(here = away)
        assertTrue(watch.armed)
        assertEquals(Crossing.Fire, watch.onCrossing(entering = true))
        assertEquals(Crossing.Ignore, watch.onCrossing(entering = false))
    }

    @Test fun anArrivalSetWhileThereWaitsForTheNextOne() {
        val watch = arrival(here = home)
        assertFalse(watch.armed)
        // The system's first report is the phone already being inside.
        assertEquals(Crossing.Ignore, watch.onCrossing(entering = true))
        assertEquals(Crossing.Arm, watch.onCrossing(entering = false))
        assertEquals(Crossing.Fire, watch.copy(armed = true).onCrossing(entering = true))
    }

    @Test fun anUnknownLocationCountsAsAway() {
        assertTrue(arrival(here = null).armed)
    }

    @Test fun aDepartureFiresOnlyOnTheWayOut() {
        val watch = PlaceWatch.create(2, "lock the door", "home", home, leaving = true, every = false, here = home)
        assertEquals(Crossing.Fire, watch.onCrossing(entering = false))
        assertEquals(Crossing.Ignore, watch.onCrossing(entering = true))
    }

    @Test fun onceIsUsedUpAndEveryTimeRearms() {
        assertNull(arrival(here = away).afterFiring())
        val again = arrival(here = away, every = true).afterFiring()!!
        // Inside now, so the next arrival needs a departure first.
        assertFalse(again.armed)
        val leaving = PlaceWatch.create(3, "keys", "work", home, leaving = true, every = true, here = away)
        assertTrue(leaving.afterFiring()!!.armed)
    }

    @Test fun triggersReadAsSpoken() {
        assertEquals("when you get home", arrival(here = away).trigger)
        assertEquals("when you get back here", arrival(away).copy(place = "here").trigger)
        assertEquals("when you get to Aldi", arrival(away).copy(place = "Aldi").trigger)
        assertEquals("when you leave work", arrival(away).copy(place = "work", leaving = true).trigger)
    }

    @Test fun survivesTheStore() {
        val watch = arrival(here = home, every = true)
        val back = PlaceWatch.listFromJson(PlaceWatch.listToJson(listOf(watch)))
        assertEquals(listOf(watch), back)
        assertTrue(PlaceWatch.listFromJson("not json").isEmpty())
        assertNull(PlaceWatch.fromJson(JSONObject().put("text", "no place")))
    }

    @Test fun foundByItsWordsOrItsPlace() {
        val milk = arrival(here = away)
        val gym = PlaceWatch.create(9, "stretch", "gym", away, leaving = false, every = true, here = null)
        val all = listOf(milk, gym)
        assertEquals(milk, PlaceWatch.match(all, "the milk one"))
        assertEquals(gym, PlaceWatch.match(all, "the gym reminder"))
        assertEquals(gym, PlaceWatch.match(all, "9"))
        assertNull(PlaceWatch.match(all, "the reminder"))
    }
}
