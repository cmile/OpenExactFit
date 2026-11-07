package de.cmile.openexactfit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.cmile.openexactfit.R
import de.cmile.openexactfit.data.StoredReading
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields

/**
 * the vendor app's "averages" tab: 7/14/30-day chips, a month calendar whose days with
 * readings wear a ring in the band colour of the day's mean, and the means (all, day,
 * evening) for the chosen day or the chosen span. one user's readings.
 */
@Composable
fun AveragesView(readings: List<StoredReading>, modifier: Modifier = Modifier) {
    val today = remember { LocalDate.now() }
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    val month = YearMonth.parse(monthText)
    /** iso date of the selected day, empty when a span is selected instead */
    var selectedDay by rememberSaveable { mutableStateOf("") }
    var span by rememberSaveable { mutableIntStateOf(7) }
    val marked = remember(readings, month) { daysWithReadings(readings, month) }
    val locale = LocalLocale.current.platformLocale
    val firstDayOfWeek = remember(locale) { WeekFields.of(locale).firstDayOfWeek }

    Column(modifier.verticalScroll(rememberScrollState())) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (n in listOf(7, 14, 30)) FilterChip(
                selected = selectedDay.isEmpty() && span == n,
                onClick = { span = n; selectedDay = "" },
                label = { Text(stringResource(R.string.range_days, n)) },
            )
        }
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { monthText = month.minusMonths(1).toString() }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
            }
            Text(
                month.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { monthText = month.plusMonths(1).toString() }, enabled = month < YearMonth.from(today)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        }
        Row {
            for (i in 0 until 7) Text(
                firstDayOfWeek.plus(i.toLong()).getDisplayName(TextStyle.SHORT, locale),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
        val lead = (month.atDay(1).dayOfWeek.value - firstDayOfWeek.value + 7) % 7
        val weeks = (lead + month.lengthOfMonth() + 6) / 7
        for (week in 0 until weeks) Row {
            for (column in 0 until 7) {
                val dayOfMonth = week * 7 + column - lead + 1
                Box(Modifier.weight(1f).height(44.dp), contentAlignment = Alignment.Center) {
                    if (dayOfMonth in 1..month.lengthOfMonth()) {
                        val date = month.atDay(dayOfMonth)
                        DayCell(
                            date = date,
                            category = marked[date],
                            selected = selectedDay == date.toString(),
                            future = date > today,
                            onClick = { selectedDay = date.toString() },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        val chosenDay = selectedDay.takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) }
        val title = chosenDay?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)) ?: stringResource(R.string.averages_last_days, span)
        val rows = if (chosenDay != null) readingsOn(readings, chosenDay) else readingsInLastDays(readings, span, today)
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        val all = average(rows)
        if (all == null) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.no_readings_in_range), style = MaterialTheme.typography.bodyMedium)
        } else {
            AverageRow(stringResource(R.string.averages_all), all)
            average(rows.filter { !it.isEvening() })?.let { AverageRow(stringResource(R.string.averages_day), it) }
            average(rows.filter { it.isEvening() })?.let { AverageRow(stringResource(R.string.averages_evening), it) }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.averages_hint, EVENING_HOUR),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun DayCell(date: LocalDate, category: Category?, selected: Boolean, future: Boolean, onClick: () -> Unit) {
    var shape = Modifier.size(36.dp).clip(CircleShape)
    if (category != null) {
        shape = shape
            .then(if (selected) Modifier.background(category.color) else Modifier.border(2.dp, category.color, CircleShape))
            .clickable(onClick = onClick)
    }
    val color = when {
        selected && category != null -> Color.White
        category != null -> MaterialTheme.colorScheme.onSurface
        future -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(shape, contentAlignment = Alignment.Center) {
        Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun AverageRow(label: String, a: Average) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                pluralStringResource(R.plurals.readings_count, a.count, a.count),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        CategoryDot(a.category)
        Spacer(Modifier.width(8.dp))
        Text("${a.systolic}/${a.diastolic}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(12.dp))
        Text(a.pulse?.let { "♥ $it" } ?: "", style = MaterialTheme.typography.bodyMedium, softWrap = false, modifier = Modifier.width(64.dp))
    }
    HorizontalDivider()
}
