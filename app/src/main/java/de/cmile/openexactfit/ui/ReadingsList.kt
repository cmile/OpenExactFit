package de.cmile.openexactfit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.cmile.openexactfit.R
import de.cmile.openexactfit.data.StoredReading
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** the day a reading belongs to: the monitor's clock when it had one, otherwise when the app received it. */
fun StoredReading.day(): String = (measuredAt ?: receivedAt).take(10)

fun StoredReading.timeOfDay(): String = (measuredAt ?: receivedAt).drop(11).take(5)

/** readings grouped by day, in the order given (newest first from the store). */
fun groupByDay(readings: List<StoredReading>): List<Pair<String, List<StoredReading>>> =
    readings.groupBy { it.day() }.toList()

/** the monitor's status bits as the python tool names them, in words. */
val FLAG_LABELS: Map<String, Int> = mapOf(
    "body_movement" to R.string.flag_body_movement,
    "cuff_too_loose" to R.string.flag_cuff_too_loose,
    "irregular_pulse" to R.string.flag_irregular_pulse,
    "pulse_too_high" to R.string.flag_pulse_too_high,
    "pulse_too_low" to R.string.flag_pulse_too_low,
    "improper_position" to R.string.flag_improper_position,
)

@Composable
fun flagText(flags: String): String {
    val words = ArrayList<String>()
    for (f in flags.split(',')) {
        if (f.isBlank()) continue
        val id = FLAG_LABELS[f]
        words += if (id != null) stringResource(id) else f.replace('_', ' ')
    }
    return words.joinToString(", ")
}

@Composable
fun dayLabel(day: String): String {
    val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: return day
    val today = LocalDate.now()
    return when (date) {
        today -> stringResource(R.string.today)
        today.minusDays(1) -> stringResource(R.string.yesterday)
        else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))
    }
}

@Composable
fun ReadingsList(
    readings: List<StoredReading>,
    showUser: Boolean,
    userName: (String?) -> String,
    onDelete: (Long) -> Unit,
    onNote: (Long, String) -> Unit,
) {
    var selected by remember { mutableStateOf<StoredReading?>(null) }
    LazyColumn {
        for ((day, rows) in groupByDay(readings)) {
            item(key = "day-$day") { DayHeader(day, rows.size) }
            items(rows, key = { it.id }) { r ->
                ReadingRow(r, if (showUser) userName(r.user) else null, onClick = { selected = r })
                HorizontalDivider()
            }
        }
    }
    selected?.let { r ->
        ReadingDialog(
            reading = r,
            userName = userName(r.user),
            onDelete = { onDelete(r.id); selected = null },
            onNote = { onNote(r.id, it); selected = null },
            onDismiss = { selected = null },
        )
    }
}

@Composable
private fun DayHeader(day: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(dayLabel(day), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            pluralStringResource(R.plurals.readings_count, count, count),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReadingRow(r: StoredReading, userName: String?, onClick: () -> Unit) {
    val flags = flagText(r.flags)
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(r.timeOfDay(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(52.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (userName != null) Text(userName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (flags.isNotEmpty()) Text(flags, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (!r.note.isNullOrBlank()) Text(
                r.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        CategoryDot(Category.of(r.systolic, r.diastolic))
        Spacer(Modifier.width(8.dp))
        Text("${r.systolic}/${r.diastolic}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(12.dp))
        Text(r.pulse?.let { "♥ $it" } ?: "", style = MaterialTheme.typography.bodyMedium, softWrap = false, modifier = Modifier.width(64.dp))
    }
}

/** the band's colour as a small dot; the band's name sits next to it wherever there is room. */
@Composable
fun CategoryDot(category: Category) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(category.color))
}

@Composable
private fun ReadingDialog(
    reading: StoredReading,
    userName: String,
    onDelete: () -> Unit,
    onNote: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val r = reading
    var note by remember(r.id) { mutableStateOf(r.note.orEmpty()) }
    val noteChanged = note.trim() != r.note.orEmpty()
    // while the note is being typed the keyboard takes half the screen: the technical lines
    // make way so the save button stays above it
    var editing by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detail_title, r.systolic, r.diastolic)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val category = Category.of(r.systolic, r.diastolic)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryDot(category)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(category.label))
                }
                r.pulse?.let { Text(stringResource(R.string.detail_pulse, it)) }
                Text(stringResource(R.string.user_label, userName))
                Text(r.measuredAt?.let { stringResource(R.string.detail_measured, it) } ?: stringResource(R.string.detail_measured_unknown))
                Text(stringResource(R.string.detail_received, r.receivedAt))
                val flags = flagText(r.flags)
                Text(
                    if (flags.isEmpty()) stringResource(R.string.detail_no_flags) else flags,
                    color = if (flags.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.note_label)) },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).onFocusChanged { editing = it.isFocused },
                )
                if (!editing) {
                    Text(stringResource(R.string.detail_monitor, r.device), style = MaterialTheme.typography.bodySmall)
                    Text(r.rawHex, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Text(
                        stringResource(R.string.category_scale),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        // one button: it saves when the note was edited, otherwise it just closes
        confirmButton = {
            if (noteChanged) TextButton(onClick = { onNote(note) }) { Text(stringResource(R.string.save)) }
            else TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
        dismissButton = {
            TextButton(onClick = { confirmDelete = true }) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
        },
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.delete_title)) },
        text = { Text(stringResource(R.string.delete_text)) },
        confirmButton = {
            TextButton(onClick = { confirmDelete = false; onDelete() }) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
    )
}
