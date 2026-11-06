package de.cmile.openexactfit.ui

import androidx.compose.ui.graphics.Color
import de.cmile.openexactfit.data.MeasurementStore
import de.cmile.openexactfit.data.StoredReading
import java.time.LocalDateTime
import java.time.ZoneId

/** how far back the chart looks; null is everything. */
enum class ChartRange(val days: Long?) { WEEK(7), MONTH(30), QUARTER(91), ALL(null) }

/** one reading on the time axis, in seconds since the epoch. */
data class ChartPoint(val time: Long, val systolic: Int, val diastolic: Int, val pulse: Int?)

private const val DAY = 86_400L

/** the monitor's clock when it was set, otherwise the time of receipt, as epoch seconds in [zone]. */
fun StoredReading.epochSeconds(zone: ZoneId = ZoneId.systemDefault()): Long =
    LocalDateTime.parse(measuredAt ?: receivedAt, MeasurementStore.FORMAT).atZone(zone).toEpochSecond()

/** the readings inside [range], counted back from [now], oldest first. unparsable timestamps are skipped. */
fun chartPoints(
    readings: List<StoredReading>,
    range: ChartRange,
    now: LocalDateTime = LocalDateTime.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): List<ChartPoint> {
    val from = range.days?.let { now.minusDays(it).atZone(zone).toEpochSecond() } ?: Long.MIN_VALUE
    return readings.mapNotNull { r ->
        val t = runCatching { r.epochSeconds(zone) }.getOrNull() ?: return@mapNotNull null
        if (t < from) null else ChartPoint(t, r.systolic, r.diastolic, r.pulse)
    }.sortedBy { it.time }
}

/** what the x axis spans: the whole window for a fixed range, the data for ALL, and at least a day. */
fun timeWindow(
    points: List<ChartPoint>,
    range: ChartRange,
    now: LocalDateTime = LocalDateTime.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): LongRange {
    val end = now.atZone(zone).toEpochSecond()
    val start = range.days?.let { end - it * DAY } ?: (points.firstOrNull()?.time ?: end)
    return minOf(start, end - DAY)..end
}

/** a least-squares line through (time, value). */
class Trend(private val origin: Long, private val slopePerSecond: Double, private val valueAtOrigin: Double) {
    fun at(time: Long): Double = valueAtOrigin + slopePerSecond * (time - origin)
    val slopePerDay: Double get() = slopePerSecond * DAY
}

/** null below two distinct times; then the line has no direction. */
fun linearTrend(points: List<Pair<Long, Double>>): Trend? {
    if (points.size < 2) return null
    val origin = points.first().first
    val xs = points.map { (it.first - origin).toDouble() }
    val ys = points.map { it.second }
    val mx = xs.average()
    val my = ys.average()
    val sxx = xs.sumOf { (it - mx) * (it - mx) }
    if (sxx == 0.0) return null
    val sxy = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) }
    val slope = sxy / sxx
    return Trend(origin, slope, my - slope * mx)
}

/** axis limits in steps of ten that leave room around the data and always show [floor]..[ceiling]. */
fun axisBounds(values: List<Int>, floor: Int, ceiling: Int): IntRange {
    val lo = minOf(floor, values.minOrNull()?.minus(5) ?: floor)
    val hi = maxOf(ceiling, values.maxOrNull()?.plus(5) ?: ceiling)
    return (Math.floorDiv(lo, 10) * 10)..(-Math.floorDiv(-hi, 10) * 10)
}

/** one line of the chart with its trend. */
class Series(val points: List<Pair<Long, Double>>, val color: Color) {
    val trend: Trend? = linearTrend(points)

    /** change of the trend line from the first to the last reading, rounded; null without a trend. */
    val change: Int? = trend?.let { Math.round(it.at(points.last().first) - it.at(points.first().first)).toInt() }
}
