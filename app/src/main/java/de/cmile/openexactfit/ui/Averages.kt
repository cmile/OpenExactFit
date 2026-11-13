package de.cmile.openexactfit.ui

import de.cmile.openexactfit.data.MeasurementStore
import de.cmile.openexactfit.data.StoredReading
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/** from this hour a reading counts as "evening" in the averages, by the monitor's clock (or receipt when unset). */
const val EVENING_HOUR = 18

/** rounded means over a set of readings; pulse over the readings that have one. */
data class Average(
    val count: Int,
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int?,
    /** how many sessions the mean was taken over; null when every reading counted on its own. */
    val sessions: Int? = null,
) {
    val category: Category get() = Category.of(systolic, diastolic)
}

fun average(readings: List<StoredReading>): Average? {
    if (readings.isEmpty()) return null
    val pulses = readings.mapNotNull { it.pulse }
    return Average(
        count = readings.size,
        systolic = Math.round(readings.map { it.systolic }.average()).toInt(),
        diastolic = Math.round(readings.map { it.diastolic }.average()).toInt(),
        pulse = if (pulses.isEmpty()) null else Math.round(pulses.average()).toInt(),
    )
}

/**
 * the mean of the session means: a sitting with five readings then weighs as much as one
 * with a single reading, instead of five times as much. [readings] newest first.
 */
fun averageBySession(readings: List<StoredReading>): Average? {
    if (readings.isEmpty()) return null
    val groups = groupSessions(readings)
    val pulses = groups.mapNotNull { g -> g.readings.mapNotNull { it.pulse }.takeIf { it.isNotEmpty() }?.average() }
    return Average(
        count = readings.size,
        systolic = Math.round(groups.map { g -> g.readings.map { it.systolic }.average() }.average()).toInt(),
        diastolic = Math.round(groups.map { g -> g.readings.map { it.diastolic }.average() }.average()).toInt(),
        pulse = if (pulses.isEmpty()) null else Math.round(pulses.average()).toInt(),
        sessions = groups.size,
    )
}

fun average(readings: List<StoredReading>, bySession: Boolean): Average? =
    if (bySession) averageBySession(readings) else average(readings)

fun StoredReading.dateTime(): LocalDateTime = LocalDateTime.parse(measuredAt ?: receivedAt, MeasurementStore.FORMAT)

fun StoredReading.date(): LocalDate = LocalDate.parse(day())

fun StoredReading.isEvening(): Boolean = dateTime().hour >= EVENING_HOUR

fun readingsOn(readings: List<StoredReading>, date: LocalDate): List<StoredReading> = readings.filter { it.date() == date }

/** [today] and the [days] - 1 days before it. */
fun readingsInLastDays(readings: List<StoredReading>, days: Int, today: LocalDate): List<StoredReading> {
    val from = today.minusDays(days.toLong() - 1)
    return readings.filter { val d = it.date(); d >= from && d <= today }
}

/** for the calendar: every day of [month] that has readings, with the band of that day's mean. */
fun daysWithReadings(readings: List<StoredReading>, month: YearMonth, bySession: Boolean = false): Map<LocalDate, Category> =
    readings.filter { YearMonth.from(it.date()) == month }
        .groupBy { it.date() }
        .mapValues { (_, rows) -> average(rows, bySession)!!.category }
