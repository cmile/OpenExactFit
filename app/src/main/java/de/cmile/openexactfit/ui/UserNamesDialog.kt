package de.cmile.openexactfit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.cmile.openexactfit.R
import de.cmile.openexactfit.ble.Protocol

/** names for the monitor's users A and B; an empty field falls back to the letter. */
@Composable
fun UserNamesDialog(names: Map<String, String>, onSave: (Map<String, String>) -> Unit, onDismiss: () -> Unit) {
    var a by rememberSaveable { mutableStateOf(names["A"] ?: "") }
    var b by rememberSaveable { mutableStateOf(names["B"] ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.name_users_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.name_users_text))
                OutlinedTextField(
                    value = a, onValueChange = { a = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.name_user_hint, Protocol.USERS[0])) },
                )
                OutlinedTextField(
                    value = b, onValueChange = { b = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.name_user_hint, Protocol.USERS[1])) },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(mapOf("A" to a, "B" to b)) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
