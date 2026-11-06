package de.cmile.openexactfit.ui

import de.cmile.openexactfit.data.StoredReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class AveragesTest {
    private fun reading(measured: String, systolic: Int = 120, diastolic: Int = 80, pulse: Int? = 70) = StoredReading(
        id = measured.hashCode().toLong(), device = "AA:BB", user = "A", measuredAt = measured, receivedAt = "2026-11-06 12:00:00",
        systolic = systolic, diastolic = diastolic, pulse = pulse, flags = "", rawHex = "1e",
    )

    @Test fun meansAreRoundedAndPulseSkipsReadingsWithoutOne() {
        val a = average(listOf(reading("2026-11-06 08:00:00", 121, 81, 70), reading("2026-11-06 08:05:00", 124, 84, null)))!!
        assertEquals(2, a.count)
        assertEquals(123, a.systolic)   // 122.5 rounds up
        assertEquals(83, a.diastolic)   // 82.5 rounds up
        assertEquals(70, a.pulse)
        assertNull(average(emptyList()))
    }

    @Test fun eveningStartsAtEighteen() {
        assertEquals(false, reading("2026-11-06 17:59:00").isEvening())
        assertEquals(true, reading("2026-11-06 18:00:00").isEvening())
    }

    @Test fun lastDaysIncludeTodayAndTheDaysBefore() {
        val today = LocalDate.of(2026, 11, 6)
        val rows = listOf(reading("2026-11-06 08:00:00"), reading("2026-10-31 08:00:00"), reading("2026-10-30 23:00:00"), reading("2026-11-07 08:00:00"))
        assertEquals(2, readingsInLastDays(rows, 7, today).size)
        assertEquals(3, readingsInLastDays(rows, 14, today).size)
        assertEquals(1, readingsOn(rows, today).size)
    }

    @Test fun calendarMarksDaysWithTheBandOfTheDayMean() {
        val rows = listOf(
            reading("2026-11-04 08:00:00", 130, 84), reading("2026-11-04 20:00:00", 142, 88),   // mean 136/86: elevated
            reading("2026-11-05 08:00:00", 118, 76),
            reading("2026-10-05 08:00:00", 190, 120),
        )
        val marked = daysWithReadings(rows, YearMonth.of(2026, 11))
        assertEquals(mapOf(LocalDate.of(2026, 11, 4) to Category.ELEVATED, LocalDate.of(2026, 11, 5) to Category.NORMAL), marked)
    }
}
