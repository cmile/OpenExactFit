package de.cmile.openexactfit.ui

import de.cmile.openexactfit.data.StoredReading
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingsListTest {
    private fun reading(id: Long, measured: String?, received: String = "2026-11-05 09:00:00") = StoredReading(
        id = id, device = "AA:BB", user = "A", measuredAt = measured, receivedAt = received,
        systolic = 120, diastolic = 80, pulse = 70, flags = "", rawHex = "1e",
    )

    @Test fun dayComesFromTheMonitorClockWhenSet() {
        assertEquals("2026-11-04", reading(1, "2026-11-04 22:30:00").day())
        assertEquals("22:30", reading(1, "2026-11-04 22:30:00").timeOfDay())
    }

    @Test fun dayFallsBackToReceiptWhenTheClockWasUnset() {
        assertEquals("2026-11-05", reading(1, null).day())
        assertEquals("09:00", reading(1, null).timeOfDay())
    }

    @Test fun groupingKeepsStoreOrderWithinAndAcrossDays() {
        val rows = listOf(
            reading(3, "2026-11-05 08:10:00"), reading(2, "2026-11-04 22:35:00"), reading(1, "2026-11-04 22:30:00"),
        )
        val groups = groupByDay(rows)
        assertEquals(listOf("2026-11-05", "2026-11-04"), groups.map { it.first })
        assertEquals(listOf(2L, 1L), groups[1].second.map { it.id })
    }
}
