package de.cmile.openexactfit.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.cmile.openexactfit.R
import de.cmile.openexactfit.data.StoredReading
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * the 7-day protocol for one user: pick the week with the arrows, see each day's morning
 * and evening value and the means over days 2..7, print it as one sheet.
 */
@Composable
fun WeekProtocolDialog(
    readings: List<StoredReading>,
    userName: String,
    onPrint: (WeekProtocol) -> Unit,
    onDismiss: () -> Unit,
) {
    val today = remember { LocalDate.now() }
    var startText by rememberSaveable { mutableStateOf(defaultProtocolStart(readings, today).toString()) }
    val start = LocalDate.parse(startText)
    val protocol = remember(readings, start) { weekProtocol(readings, start) }
    val locale = LocalLocale.current.platformLocale
    val dayFormat = remember(locale) { DateTimeFormatter.ofPattern("EEE d MMM", locale) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.week_protocol)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.user_label, userName), style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { startText = start.minusDays(1).toString() }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
                    }
                    Text(
                        "${start.format(dayFormat)} – ${start.plusDays(PROTOCOL_DAYS - 1L).format(dayFormat)}",
                        style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { startText = start.plusDays(1).toString() }, enabled = start < today) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                }
                Row(modifier = Modifier.padding(bottom = 4.dp)) {
                    Spacer(Modifier.weight(1.3f))
                    HeaderCell(stringResource(R.string.protocol_morning))
                    HeaderCell(stringResource(R.string.protocol_evening))
                }
                for ((i, day) in protocol.days.withIndex()) {
                    // the first day is measured but not counted: shown, dimmed
                    Row(modifier = Modifier.padding(vertical = 4.dp).alpha(if (i == 0) 0.5f else 1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(day.date.format(dayFormat), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1.3f))
                        ValueCell(day.morning)
                        ValueCell(day.evening)
                    }
                }
                Spacer(Modifier.height(6.dp))
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))
                SummaryRow(stringResource(R.string.protocol_overall), protocol.overall, strong = true)
                SummaryRow(stringResource(R.string.protocol_morning), protocol.morning)
                SummaryRow(stringResource(R.string.protocol_evening), protocol.evening)
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.protocol_slots, protocol.filled, protocol.expected),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (protocol.filled < protocol.expected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.protocol_hint, PROTOCOL_MORNING_END_HOUR, PROTOCOL_EVENING_HOUR),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPrint(protocol) }, enabled = protocol.overall != null) { Text(stringResource(R.string.print)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ValueCell(a: Average?) {
    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        if (a == null) {
            Text("–", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            CategoryDot(a.category)
            Spacer(Modifier.width(4.dp))
            Text("${a.systolic}/${a.diastolic}", style = MaterialTheme.typography.bodyMedium, softWrap = false)
        }
    }
}

@Composable
private fun SummaryRow(label: String, a: Average?, strong: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (a == null) {
            Text("–", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            CategoryDot(a.category)
            Spacer(Modifier.width(6.dp))
            Text(
                "${a.systolic}/${a.diastolic}",
                style = if (strong) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
            )
            Spacer(Modifier.width(8.dp))
            Text(a.pulse?.let { "♥ $it" } ?: "", style = MaterialTheme.typography.bodyMedium, softWrap = false, modifier = Modifier.width(56.dp))
        }
    }
}
