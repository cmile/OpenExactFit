package de.cmile.openexactfit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.cmile.openexactfit.R
import de.cmile.openexactfit.data.StoredReading
import de.cmile.openexactfit.ui.theme.ChartDiastolic
import de.cmile.openexactfit.ui.theme.ChartPulse
import de.cmile.openexactfit.ui.theme.ChartSystolic
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * one user's readings over time: a blood pressure panel with the 135/85 and 140/90
 * guideline lines, a pulse panel below it on its own axis, and a dashed least-squares
 * trend per series so the direction shows at a glance.
 */
@Composable
fun TrendChart(readings: List<StoredReading>, range: ChartRange, modifier: Modifier = Modifier) {
    val now = remember(readings, range) { LocalDateTime.now() }
    val points = remember(readings, range) { chartPoints(readings, range, now) }
    if (points.isEmpty()) {
        Text(stringResource(R.string.no_readings_in_range), style = MaterialTheme.typography.bodyMedium, modifier = modifier)
        return
    }
    val window = remember(points, range) { timeWindow(points, range, now) }
    val systolic = remember(points) { Series(points.map { it.time to it.systolic.toDouble() }, ChartSystolic) }
    val diastolic = remember(points) { Series(points.map { it.time to it.diastolic.toDouble() }, ChartDiastolic) }
    val pulse = remember(points) { Series(points.mapNotNull { p -> p.pulse?.let { p.time to it.toDouble() } }, ChartPulse) }
    val hasPulse = pulse.points.isNotEmpty()

    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendItem(systolic, stringResource(R.string.series_systolic))
            LegendItem(diastolic, stringResource(R.string.series_diastolic))
            if (hasPulse) LegendItem(pulse, stringResource(R.string.series_pulse))
        }
        Spacer(Modifier.height(4.dp))
        Panel(
            height = 220.dp,
            window = window,
            bounds = axisBounds(points.flatMap { listOf(it.systolic, it.diastolic) }, 60, 160),
            series = listOf(systolic, diastolic),
            guides = listOf(140, 90, 135, 85),
            timeLabels = !hasPulse,
        )
        if (hasPulse) {
            Spacer(Modifier.height(4.dp))
            Panel(
                height = 120.dp,
                window = window,
                bounds = axisBounds(pulse.points.map { it.second.toInt() }, 50, 100),
                series = listOf(pulse),
                guides = emptyList(),
                timeLabels = true,
            )
        }
        Text(
            stringResource(R.string.chart_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LegendItem(series: Series, name: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(series.color))
        Spacer(Modifier.width(4.dp))
        val change = series.change
        Text(if (change == null) name else "$name ${"%+d".format(change)}", style = MaterialTheme.typography.labelMedium)
    }
}

/** one plot area: grid with value labels, guideline lines, the series with points, lines and trends. */
@Composable
private fun Panel(height: Dp, window: LongRange, bounds: IntRange, series: List<Series>, guides: List<Int>, timeLabels: Boolean) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val guideColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val surface = MaterialTheme.colorScheme.surface
    val zone = remember { ZoneId.systemDefault() }
    val dateFormat = remember { DateTimeFormatter.ofPattern("d MMM") }

    Canvas(Modifier.fillMaxWidth().height(height)) {
        val left = 36.dp.toPx()
        val right = 8.dp.toPx()
        val top = 6.dp.toPx()
        val bottom = if (timeLabels) 18.dp.toPx() else 4.dp.toPx()
        val plotW = size.width - left - right
        val plotH = size.height - top - bottom
        val span = (window.last - window.first).coerceAtLeast(1).toFloat()
        fun x(t: Long): Float = left + (t - window.first) / span * plotW
        fun y(v: Double): Float = top + ((bounds.last - v) / (bounds.last - bounds.first)).toFloat() * plotH
        val dash = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx()))
        val thin = 1.dp.toPx()

        // grid and value labels every 20
        var v = Math.floorDiv(bounds.first + 19, 20) * 20
        while (v <= bounds.last) {
            val yy = y(v.toDouble())
            drawLine(gridColor, Offset(left, yy), Offset(left + plotW, yy), thin)
            val label = measurer.measure(v.toString(), labelStyle)
            drawText(label, topLeft = Offset(left - label.size.width - 4.dp.toPx(), yy - label.size.height / 2))
            v += 20
        }
        for (g in guides) if (g in bounds) {
            val yy = y(g.toDouble())
            drawLine(guideColor, Offset(left, yy), Offset(left + plotW, yy), thin, pathEffect = dash)
        }
        if (timeLabels) for (k in 0..3) {
            val t = window.first + (window.last - window.first) * k / 3
            val label = measurer.measure(Instant.ofEpochSecond(t).atZone(zone).format(dateFormat), labelStyle)
            val tx = (x(t) - label.size.width / 2).coerceIn(0f, size.width - label.size.width)
            drawText(label, topLeft = Offset(tx, size.height - label.size.height))
        }

        val pad = 4.dp.toPx()
        clipRect(left - pad, top - pad, left + plotW + pad, top + plotH + pad) {
            for (s in series) {
                s.trend?.let { trend ->
                    val t0 = s.points.first().first
                    val t1 = s.points.last().first
                    drawLine(
                        s.color.copy(alpha = 0.7f), Offset(x(t0), y(trend.at(t0))), Offset(x(t1), y(trend.at(t1))),
                        strokeWidth = 2.dp.toPx(), pathEffect = dash,
                    )
                }
                if (s.points.size > 1) {
                    val path = Path()
                    s.points.forEachIndexed { i, (t, value) -> if (i == 0) path.moveTo(x(t), y(value)) else path.lineTo(x(t), y(value)) }
                    drawPath(path, s.color, style = Stroke(1.5.dp.toPx()))
                }
                for ((t, value) in s.points) {
                    drawCircle(surface, 4.dp.toPx(), Offset(x(t), y(value)))
                    drawCircle(s.color, 3.dp.toPx(), Offset(x(t), y(value)))
                }
            }
        }
    }
}
