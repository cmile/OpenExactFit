package de.cmile.openexactfit.ui

import de.cmile.openexactfit.data.MeasurementStore
import de.cmile.openexactfit.data.StoredReading
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/** from this hour a reading counts as "evening" in the averages, by the monitor's clock (or receipt when unset). */
const val EVENING_HOUR = 18

/** rounded means over a set of readings; pulse over the readings that have one. */
data class Average(val count: Int, val systolic: Int, val diastolic: Int, val pulse: Int?) {
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
fun daysWithReadings(readings: List<StoredReading>, month: YearMonth): Map<LocalDate, Category> =
    readings.filter { YearMonth.from(it.date()) == month }
        .groupBy { it.date() }
        .mapValues { (_, rows) -> average(rows)!!.category }
