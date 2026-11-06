package de.cmile.openexactfit.ui

import androidx.compose.ui.graphics.Color
import de.cmile.openexactfit.data.MeasurementStore
import de.cmile.openexactfit.data.StoredReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TrendTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val now = LocalDateTime.of(2026, 11, 6, 12, 0)
    private val day = 86_400L

    private fun reading(id: Long, measured: String, systolic: Int = 120, diastolic: Int = 80, pulse: Int? = 70) = StoredReading(
        id = id, device = "AA:BB", user = "A", measuredAt = measured, receivedAt = "2026-11-06 12:00:00",
        systolic = systolic, diastolic = diastolic, pulse = pulse, flags = "", rawHex = "1e",
    )

    private fun at(s: String) = LocalDateTime.parse(s, MeasurementStore.FORMAT).atZone(zone).toEpochSecond()

    @Test fun rangeKeepsOnlyRecentReadingsOldestFirst() {
        val rows = listOf(reading(3, "2026-11-05 08:00:00"), reading(2, "2026-10-01 08:00:00"), reading(1, "2026-11-01 08:00:00"))
        assertEquals(listOf(at("2026-11-01 08:00:00"), at("2026-11-05 08:00:00")), chartPoints(rows, ChartRange.WEEK, now, zone).map { it.time })
        assertEquals(2, chartPoints(rows, ChartRange.MONTH, now, zone).size)
        assertEquals(3, chartPoints(rows, ChartRange.ALL, now, zone).size)
    }

    @Test fun unsetClockFallsBackToReceipt() {
        val r = reading(1, "2026-11-05 08:00:00").copy(measuredAt = null)
        assertEquals(at("2026-11-06 12:00:00"), chartPoints(listOf(r), ChartRange.WEEK, now, zone).single().time)
    }

    @Test fun trendFollowsAStraightLine() {
        val t0 = 1_000_000L
        val trend = linearTrend(listOf(t0 to 120.0, t0 + day to 122.0, t0 + 2 * day to 124.0))!!
        assertEquals(2.0, trend.slopePerDay, 1e-9)
        assertEquals(124.0, trend.at(t0 + 2 * day), 1e-9)
        assertNull(linearTrend(listOf(t0 to 120.0)))
        assertNull(linearTrend(listOf(t0 to 120.0, t0 to 130.0)))
    }

    @Test fun seriesChangeIsTheTrendOverTheReadings() {
        val s = Series(listOf(0L to 130.0, day to 126.0, 2 * day to 122.0), Color.Red)
        assertEquals(-8, s.change)
        assertNull(Series(listOf(0L to 130.0), Color.Red).change)
    }

    @Test fun axisBoundsCoverDataAndGuidelines() {
        assertEquals(60..160, axisBounds(listOf(80, 120), 60, 160))
        assertEquals(50..190, axisBounds(listOf(58, 182), 60, 160))
        assertEquals(50..100, axisBounds(emptyList(), 50, 100))
    }

    @Test fun windowIsTheRangeOrTheDataButNeverUnderADay() {
        val end = now.atZone(zone).toEpochSecond()
        assertEquals((end - 7 * day)..end, timeWindow(emptyList(), ChartRange.WEEK, now, zone))
        val fresh = ChartPoint(end - 600, 120, 80, 70)
        assertEquals((end - day)..end, timeWindow(listOf(fresh), ChartRange.ALL, now, zone))
        val old = ChartPoint(end - 100 * day, 120, 80, 70)
        assertEquals(old.time..end, timeWindow(listOf(old, fresh), ChartRange.ALL, now, zone))
    }
}
