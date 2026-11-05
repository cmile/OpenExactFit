package de.cmile.openexactfit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import de.cmile.openexactfit.data.StoredReading
import de.cmile.openexactfit.ui.theme.OpenExactFitTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenExactFitTheme {
                App()
            }
        }
    }
}

/** what scanning and connecting need on this android version. */
fun blePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: SyncViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) vm.startSync()
    }

    fun syncWithPermissions() {
        val missing = blePermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) vm.startSync() else launcher.launch(missing.toTypedArray())
    }

    fun share() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, "exactfit readings")
            putExtra(Intent.EXTRA_TEXT, vm.csv())
        }
        context.startActivity(Intent.createChooser(intent, "export csv"))
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("OpenExactFit") }) },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).padding(horizontal = 16.dp).fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.syncing) OutlinedButton(onClick = { vm.stopSync() }) { Text("stop") }
                else Button(onClick = { syncWithPermissions() }) { Text("sync with monitor") }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = { share() }, enabled = state.readings.isNotEmpty()) { Text("export csv") }
            }
            if (state.log.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        for (line in state.log.takeLast(8)) Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (state.readings.isEmpty()) {
                Text("no readings yet. switch the monitor on, press sync, measure.", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    items(state.readings, key = { it.id }) { r ->
                        ReadingRow(r)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
fun ReadingRow(r: StoredReading) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(r.measuredAt ?: r.receivedAt, style = MaterialTheme.typography.bodyMedium)
            val extra = listOfNotNull(r.user?.let { "user $it" }, r.flags.takeIf { it.isNotEmpty() }?.replace('_', ' ')).joinToString("  ")
            if (extra.isNotEmpty()) Text(extra, style = MaterialTheme.typography.bodySmall)
        }
        Text("${r.systolic}/${r.diastolic}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(12.dp))
        Text(r.pulse?.let { "♥ $it" } ?: "", style = MaterialTheme.typography.bodyMedium)
    }
}
