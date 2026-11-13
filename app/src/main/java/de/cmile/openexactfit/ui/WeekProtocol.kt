package de.cmile.openexactfit.ui

import de.cmile.openexactfit.data.StoredReading
import java.time.LocalDate

/** the protocol's length in days; the first one is measured but left out of the means. */
const val PROTOCOL_DAYS = 7

/** readings before this hour are the day's morning value. */
const val PROTOCOL_MORNING_END_HOUR = 12

/** readings from this hour on are the day's evening value; what lies between is not counted. */
const val PROTOCOL_EVENING_HOUR = 17

/** one day of the protocol: everything measured in the morning and in the evening, each one value. */
data class ProtocolDay(
    val date: LocalDate,
    val morningReadings: List<StoredReading>,
    val eveningReadings: List<StoredReading>,
    /** readings of that day between the two windows; shown as a count, not used. */
    val ignored: Int,
) {
    val morning: Average? get() = average(morningReadings)
    val evening: Average? get() = average(eveningReadings)
}

/**
 * the home blood pressure protocol doctors ask for: seven days, a morning and an evening
 * value each, the first day left out, the mean of the remaining twelve values. every
 * reading inside a window counts towards that window's value; each value then weighs the same.
 */
data class WeekProtocol(
    val days: List<ProtocolDay>,
    /** means over days 2..7 */
    val morning: Average?,
    val evening: Average?,
    val overall: Average?,
    /** how many of the counted morning and evening values exist */
    val filled: Int,
    val expected: Int,
)

fun weekProtocol(readings: List<StoredReading>, start: LocalDate): WeekProtocol {
    val days = (0 until PROTOCOL_DAYS).map { i ->
        val date = start.plusDays(i.toLong())
        val rows = readingsOn(readings, date)
        val hours = rows.associateWith { r -> runCatching { r.dateTime().hour }.getOrNull() }
        val morning = rows.filter { hours[it]?.let { h -> h < PROTOCOL_MORNING_END_HOUR } == true }
        val evening = rows.filter { hours[it]?.let { h -> h >= PROTOCOL_EVENING_HOUR } == true }
        ProtocolDay(date, morning, evening, rows.size - morning.size - evening.size)
    }
    val counted = days.drop(1)
    val mornings = counted.map { it.morningReadings }.filter { it.isNotEmpty() }
    val evenings = counted.map { it.eveningReadings }.filter { it.isNotEmpty() }
    return WeekProtocol(
        days = days,
        morning = averageOfGroups(mornings),
        evening = averageOfGroups(evenings),
        overall = averageOfGroups(mornings + evenings),
        filled = mornings.size + evenings.size,
        expected = 2 * (PROTOCOL_DAYS - 1),
    )
}

/** the week that ends with the latest reading, or with [today] when there is none. */
fun defaultProtocolStart(readings: List<StoredReading>, today: LocalDate): LocalDate {
    val last = readings.mapNotNull { runCatching { it.date() }.getOrNull() }.maxOrNull() ?: today
    return last.minusDays(PROTOCOL_DAYS - 1L)
}
