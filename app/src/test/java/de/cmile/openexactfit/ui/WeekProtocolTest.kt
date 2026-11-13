package de.cmile.openexactfit.ui

import de.cmile.openexactfit.data.StoredReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class WeekProtocolTest {
    private var nextId = 1L
    private fun reading(measured: String, systolic: Int, diastolic: Int, pulse: Int? = 70) =
        StoredReading(nextId++, "AA:BB", "A", measured, "r", systolic, diastolic, pulse, "", "1e")

    private val start = LocalDate.of(2026, 11, 2)

    @Test fun dayOneIsShownButNotCountedAndMiddayReadingsAreLeftOut() {
        val rows = listOf(
            reading("2026-11-02 07:00:00", 180, 110),                                   // day 1: shown, not counted
            reading("2026-11-03 07:00:00", 120, 80), reading("2026-11-03 07:03:00", 124, 84), // one morning value: 122/82
            reading("2026-11-03 13:00:00", 170, 100),                                   // midday: ignored
            reading("2026-11-03 21:00:00", 130, 86),
            reading("2026-11-04 08:00:00", 126, 82),
        )
        val p = weekProtocol(rows, start)
        assertEquals(7, p.days.size)
        assertEquals(180, p.days[0].morning!!.systolic)
        assertEquals(Pair(122, 82), p.days[1].morning!!.let { it.systolic to it.diastolic })
        assertEquals(1, p.days[1].ignored)
        assertNull(p.days[2].evening)
        // counted values: 122/82, 126/82 (mornings), 130/86 (evening)
        assertEquals(Pair(124, 82), p.morning!!.let { it.systolic to it.diastolic })
        assertEquals(Pair(130, 86), p.evening!!.let { it.systolic to it.diastolic })
        assertEquals(Pair(126, 83), p.overall!!.let { it.systolic to it.diastolic })
        assertEquals(3, p.filled)
        assertEquals(12, p.expected)
    }

    @Test fun anEmptyWeekHasNoMeans() {
        val p = weekProtocol(emptyList(), start)
        assertNull(p.overall)
        assertEquals(0, p.filled)
    }

    @Test fun theDefaultWeekEndsWithTheLatestReading() {
        val rows = listOf(reading("2026-11-13 07:50:00", 118, 83), reading("2026-11-04 22:35:00", 121, 84))
        assertEquals(LocalDate.of(2026, 11, 7), defaultProtocolStart(rows, LocalDate.of(2026, 11, 20)))
        assertEquals(LocalDate.of(2026, 11, 14), defaultProtocolStart(emptyList(), LocalDate.of(2026, 11, 20)))
    }

    @Test fun theSheetNamesTheWeekMarksDayOneAndPrintsTheMeans() {
        val rows = listOf(reading("2026-11-02 07:00:00", 180, 110), reading("2026-11-03 07:00:00", 120, 80), reading("2026-11-03 21:00:00", 130, 86, null))
        val labels = WeekLabels(
            title = "protocol", printed = "printed %s", user = "user %s", day = "day", morning = "morning", evening = "evening",
            pulse = "pulse", notCounted = "not counted", means = "means", overall = "overall", slots = "%d of %d", hint = "hint",
            category = { it.name.lowercase() },
        )
        val html = Report.weekHtml(weekProtocol(rows, start), "<smile>", labels, LocalDateTime.of(2026, 11, 9, 9, 0))
        assertTrue(html.contains("user &lt;smile&gt;"))
        assertTrue(html.contains("(not counted)</td><td>180/110"))
        assertTrue(html.contains("<td>120/80 <span class=\"muted\">pulse 70</span>"))
        assertTrue(html.contains("<td colspan=\"2\">125/83"))
        assertTrue(html.contains("2 of 12"))
    }
}
