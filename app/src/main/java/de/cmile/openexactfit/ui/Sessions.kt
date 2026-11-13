package de.cmile.openexactfit.ui

import de.cmile.openexactfit.data.StoredReading
import java.time.Duration

/** readings of one user taken at most this many minutes after one another form a session. */
const val SESSION_GAP_MINUTES = 10L

/**
 * readings taken in one sitting, shown as one line with their mean. a view only: the
 * database keeps every reading as it is. [readings] are newest first, as the store hands
 * them out, and every one of them counts towards the mean, the first included.
 */
data class Session(val readings: List<StoredReading>) {
    val earliest: StoredReading get() = readings.last()
    val latest: StoredReading get() = readings.first()
    val isSingle: Boolean get() = readings.size == 1
    val average: Average get() = average(readings)!!

    /** "07:44" for one reading, "07:44–07:50" for several. */
    fun timeRange(): String = if (isSingle) earliest.timeOfDay() else "${earliest.timeOfDay()}–${latest.timeOfDay()}"

    /** the session as one reading with the mean values at the time of its first measurement, for the chart. */
    fun asReading(): StoredReading =
        if (isSingle) earliest
        else average.let { a -> earliest.copy(systolic = a.systolic, diastolic = a.diastolic, pulse = a.pulse, flags = "", note = null) }
}

/**
 * chains readings of the same user into sessions: a reading joins the session whose
 * oldest member so far was measured at most [gapMinutes] after it. the result keeps the
 * order of the input (newest first); other users' readings in between do not break a chain.
 */
fun groupSessions(readings: List<StoredReading>, gapMinutes: Long = SESSION_GAP_MINUTES): List<Session> {
    val sessions = mutableListOf<MutableList<StoredReading>>()
    val open = HashMap<String?, MutableList<StoredReading>>()
    for (r in readings) {
        val current = open[r.user]
        val joins = current != null && runCatching {
            val gap = Duration.between(r.dateTime(), current.last().dateTime())
            !gap.isNegative && gap.toMinutes() <= gapMinutes && gap.seconds <= gapMinutes * 60
        }.getOrDefault(false)
        if (joins) {
            current.add(r)
        } else {
            val fresh = mutableListOf(r)
            open[r.user] = fresh
            sessions += fresh
        }
    }
    return sessions.map { Session(it) }
}
