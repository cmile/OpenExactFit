package de.cmile.openexactfit.ui

import de.cmile.openexactfit.data.StoredReading
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionsTest {
    private fun reading(id: Long, measured: String, systolic: Int = 120, diastolic: Int = 80, pulse: Int? = 70, user: String = "A") =
        StoredReading(id, "AA:BB", user, measured, "2026-11-13 12:00:00", systolic, diastolic, pulse, "", "1e")

    @Test fun readingsCloseTogetherChainIntoOneSession() {
        // newest first, as the store hands them out; 07:44, 07:47, 07:56 chain (each within 10 min of the next)
        val rows = listOf(
            reading(5, "2026-11-13 21:40:00"),
            reading(4, "2026-11-13 07:56:00"), reading(3, "2026-11-13 07:47:00"), reading(2, "2026-11-13 07:44:00"),
            reading(1, "2026-11-13 07:33:00"),
        )
        val sessions = groupSessions(rows)
        assertEquals(listOf(listOf(5L), listOf(4L, 3L, 2L), listOf(1L)), sessions.map { s -> s.readings.map { it.id } })
        assertEquals("07:44–07:56", sessions[1].timeRange())
        assertEquals("21:40", sessions[0].timeRange())
    }

    @Test fun theMeanCountsEveryReadingTheFirstIncluded() {
        val s = groupSessions(listOf(
            reading(3, "2026-11-13 07:50:00", 118, 83, 75), reading(2, "2026-11-13 07:47:00", 118, 84, 71),
            reading(1, "2026-11-13 07:44:00", 127, 88, 72),
        )).single()
        assertEquals(Triple(121, 85, 73), Triple(s.average.systolic, s.average.diastolic, s.average.pulse))
        val shown = s.asReading()
        assertEquals("2026-11-13 07:44:00", shown.measuredAt)
        assertEquals(121, shown.systolic)
    }

    @Test fun usersAreKeptApartEvenWhenTheyAlternate() {
        val rows = listOf(
            reading(4, "2026-11-13 07:48:00", user = "A"), reading(3, "2026-11-13 07:46:00", user = "B"),
            reading(2, "2026-11-13 07:44:00", user = "A"), reading(1, "2026-11-13 07:42:00", user = "B"),
        )
        assertEquals(listOf(listOf(4L, 2L), listOf(3L, 1L)), groupSessions(rows).map { s -> s.readings.map { it.id } })
    }

    @Test fun elevenMinutesApartAreTwoSessions() {
        val rows = listOf(reading(2, "2026-11-13 07:55:00"), reading(1, "2026-11-13 07:44:00"))
        assertEquals(2, groupSessions(rows).size)
    }
}
