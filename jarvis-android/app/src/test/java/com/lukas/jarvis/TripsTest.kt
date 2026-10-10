package com.lukas.jarvis

import com.lukas.jarvis.data.Trip
import com.lukas.jarvis.data.TripWeather
import com.lukas.jarvis.data.Trips
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TripsTest {

    private val oct14 = LocalDate.parse("2026-10-14")

    @Test fun aTripFromItsDaysOrItsNights() {
        assertEquals(Trip("Lisbon", oct14, LocalDate.parse("2026-10-19")), Trips.of("lisbon", oct14, null, 5))
        assertEquals(5, Trips.of("Lisbon", oct14, LocalDate.parse("2026-10-19"), null)?.nights)
        assertEquals(0, Trips.of("Bruges", oct14, null, null)?.nights)
        assertNull(Trips.of("Lisbon", oct14, LocalDate.parse("2026-10-10"), null))
        assertNull(Trips.of("", oct14, null, 3))
        assertNull(Trips.of("Lisbon", null, null, 3))
    }

    @Test fun theDatesReadNaturally() {
        assertEquals("14 to 19 Oct", Trip("Lisbon", oct14, LocalDate.parse("2026-10-19")).dates)
        assertEquals("30 Oct to 2 Nov", Trip("Rome", LocalDate.parse("2026-10-30"), LocalDate.parse("2026-11-02")).dates)
        assertEquals("14 Oct", Trip("Bruges", oct14, oct14).dates)
        assertEquals("packing for lisbon", Trip("Lisbon", oct14, oct14).listName)
    }

    @Test fun packingFollowsTheLengthAndTheWeather() {
        val week = Trip("Lisbon", oct14, oct14.plusDays(9))
        val warm = Trips.packing(week, TripWeather(high = 28.0, low = 18.0, rainyDays = 0))
        assertTrue("Underwear ×7" in warm)
        assertTrue("A bag for laundry" in warm)
        assertTrue("Sunscreen" in warm && "Swimwear" in warm)
        assertFalse("A warm jacket" in warm)
        assertFalse(warm.any { it.contains("rain", ignoreCase = true) })

        val cold = Trips.packing(Trip("Oslo", oct14, oct14.plusDays(2)), TripWeather(high = 8.0, low = 1.0, rainyDays = 2))
        assertTrue("A warm jacket" in cold && "Umbrella or a rain jacket" in cold)
        assertTrue("Underwear ×3" in cold)

        // Too far off to know: a little for rain, just in case.
        assertTrue("Something for rain, just in case" in Trips.packing(week, null))
        // A day trip needs no change of clothes.
        assertFalse(Trips.packing(Trip("Bruges", oct14, oct14), null).any { it.startsWith("Socks") })
    }
}
