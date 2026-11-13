package de.cmile.openexactfit.ui

import de.cmile.openexactfit.data.StoredReading
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** the words the report needs, resolved from resources by the caller so this file stays testable. */
data class ReportLabels(
    val title: String,
    /** with one %s for the date */
    val printed: String,
    /** with one %s for the user's name */
    val user: String,
    val time: String,
    val systolic: String,
    val diastolic: String,
    val pulse: String,
    val band: String,
    val notes: String,
    val average: String,
    val received: String,
    val readings: (Int) -> String,
    val category: (Category) -> String,
    val flag: (String) -> String,
)

/**
 * a printable html page of the readings, one section per user with the mean on top and a
 * table grouped by day. android's print framework renders it through a webview, and its
 * dialog offers "save as pdf", so this doubles as the export for the doctor's visit.
 */
object Report {
    private val DAY: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

    fun html(
        readings: List<StoredReading>,
        userName: (String?) -> String,
        labels: ReportLabels,
        now: LocalDateTime = LocalDateTime.now(),
    ): String = buildString {
        append("<!doctype html><html><head><meta charset=\"utf-8\"><title>").append(esc(labels.title)).append("</title><style>")
        append(
            """
            @page { margin: 15mm; }
            body { font-family: sans-serif; font-size: 11pt; color: #222; }
            h1 { font-size: 16pt; margin: 0 0 2pt; }
            h2 { font-size: 13pt; margin: 14pt 0 4pt; }
            .meta { color: #666; font-size: 9pt; margin-bottom: 8pt; }
            table { border-collapse: collapse; width: 100%; }
            th, td { border-bottom: 1px solid #ccc; padding: 3pt 6pt; text-align: left; vertical-align: top; }
            th { font-weight: 600; border-bottom: 2px solid #999; }
            td.n { text-align: right; font-variant-numeric: tabular-nums; }
            tr.day td { border: 0; padding-top: 8pt; font-weight: 600; }
            .band { display: inline-block; padding: 0 5pt; border-radius: 3pt; }
            .b0 { background: #dff0d8; } .b1 { background: #fff3c4; } .b2 { background: #ffe0c2; } .b3 { background: #f8cdd6; }
            .muted { color: #666; }
            """.trimIndent()
        )
        append("</style></head><body>")
        append("<h1>").append(esc(labels.title)).append("</h1>")
        append("<div class=\"meta\">").append(esc(labels.printed.format(now.format(STAMP)))).append("</div>")

        val users = readings.groupBy { it.user }.toSortedMap(compareBy<String?> { it == null }.thenBy { it })
        for ((user, rows) in users) {
            append("<h2>").append(esc(labels.user.format(userName(user)))).append("</h2>")
            average(rows)?.let { a ->
                append("<div class=\"meta\">").append(esc(labels.readings(a.count))).append(", ").append(esc(labels.average)).append(' ')
                append(a.systolic).append('/').append(a.diastolic)
                a.pulse?.let { append(", ").append(esc(labels.pulse).lowercase()).append(' ').append(it) }
                append(' ').append(band(a.category, labels)).append("</div>")
            }
            append("<table><tr><th>").append(esc(labels.time)).append("</th><th>").append(esc(labels.systolic)).append("</th><th>")
            append(esc(labels.diastolic)).append("</th><th>").append(esc(labels.pulse)).append("</th><th>").append(esc(labels.band))
            append("</th><th>").append(esc(labels.notes)).append("</th></tr>")
            for ((day, dayRows) in groupByDay(rows)) {
                val dayText = runCatching { LocalDate.parse(day).format(DAY) }.getOrDefault(day)
                append("<tr class=\"day\"><td colspan=\"6\">").append(esc(dayText)).append("</td></tr>")
                for (r in dayRows) {
                    val notes = r.flags.split(',').filter { it.isNotBlank() }.map { labels.flag(it) }.toMutableList()
                    if (r.measuredAt == null) notes += labels.received
                    r.note?.takeIf { it.isNotBlank() }?.let { notes += it }
                    append("<tr><td>").append(esc(r.timeOfDay())).append("</td>")
                    append("<td class=\"n\">").append(r.systolic).append("</td>")
                    append("<td class=\"n\">").append(r.diastolic).append("</td>")
                    append("<td class=\"n\">").append(r.pulse?.toString() ?: "").append("</td>")
                    append("<td>").append(band(Category.of(r.systolic, r.diastolic), labels)).append("</td>")
                    append("<td class=\"muted\">").append(esc(notes.joinToString(", "))).append("</td></tr>")
                }
            }
            append("</table>")
        }
        append("</body></html>")
    }

    private fun band(c: Category, labels: ReportLabels): String =
        "<span class=\"band b${c.ordinal}\">" + esc(labels.category(c)) + "</span>"

    fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
