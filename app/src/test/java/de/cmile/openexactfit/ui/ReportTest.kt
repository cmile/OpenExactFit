package de.cmile.openexactfit.ui

import de.cmile.openexactfit.data.StoredReading
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ReportTest {
    private val labels = ReportLabels(
        title = "readings", printed = "printed %s", user = "user %s", time = "time", systolic = "sys", diastolic = "dia",
        pulse = "pulse", band = "band", notes = "notes", average = "average", received = "received",
        readings = { "$it readings" }, category = { it.name.lowercase() }, flag = { it.replace('_', ' ') },
    )

    @Test fun oneSectionPerUserWithMeanRowsAndEscaping() {
        val rows = listOf(
            StoredReading(1, "AA:BB", "A", "2026-11-06 07:40:00", "2026-11-06 07:41:00", 120, 85, 72, "", "1e", note = "after <coffee>"),
            StoredReading(2, "AA:BB", "B", null, "2026-11-05 22:00:00", 165, 100, null, "body_movement,irregular_pulse", "1e"),
        )
        val html = Report.html(rows, { if (it == "A") "<smile>" else it ?: "?" }, labels, LocalDateTime.of(2026, 11, 7, 9, 0))
        assertTrue(html.contains("<h2>user &lt;smile&gt;</h2>"))
        assertTrue(html.contains("<td class=\"n\">120</td><td class=\"n\">85</td><td class=\"n\">72</td>"))
        assertTrue(html.contains("1 readings, average 120/85, pulse 72"))
        assertTrue(html.contains("body movement, irregular pulse, received"))
        assertTrue(html.contains("<td class=\"muted\">after &lt;coffee&gt;</td>"))
        assertTrue(html.contains(">high<"))
        assertTrue(html.indexOf("user &lt;smile&gt;") < html.indexOf("user B"))
    }

    @Test fun sessionsPrintAMeanLineAboveTheirReadings() {
        val rows = listOf(
            StoredReading(2, "AA:BB", "A", "2026-11-06 07:44:00", "r", 120, 84, 72, "", "1e"),
            StoredReading(1, "AA:BB", "A", "2026-11-06 07:40:00", "r", 126, 88, 76, "", "1e"),
        )
        val html = Report.html(rows, { it ?: "?" }, labels, LocalDateTime.of(2026, 11, 7, 9, 0), sessions = true)
        assertTrue(html.contains("<tr class=\"mean\"><td>07:40–07:44</td><td class=\"n\">123</td><td class=\"n\">86</td><td class=\"n\">74</td>"))
        assertTrue(html.contains("average, 2 readings"))
        assertTrue(html.contains("<tr class=\"sub\"><td>07:44</td>"))
        val plain = Report.html(rows, { it ?: "?" }, labels, LocalDateTime.of(2026, 11, 7, 9, 0))
        assertTrue(!plain.contains("class=\"mean\""))
    }
}
