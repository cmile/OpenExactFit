package de.cmile.openexactfit

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import de.cmile.openexactfit.ui.ReadingsList
import de.cmile.openexactfit.ui.UserNamesDialog
import de.cmile.openexactfit.ui.theme.OpenExactFitTheme
import kotlinx.coroutines.launch

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

fun missingPermissions(context: Context): List<String> =
    blePermissions().filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }

private fun bluetoothAdapter(context: Context): BluetoothAdapter? =
    (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

/** why a sync cannot start right now; shown instead of a silent "no monitor found". */
sealed class Problem {
    object NoBluetooth : Problem()
    object BluetoothOff : Problem()
    data class PermissionDenied(val forever: Boolean) : Problem()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: SyncViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf<Problem?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var namesOpen by remember { mutableStateOf(false) }

    // permissions first, then the adapter: on android 12+ even asking to enable bluetooth needs connect.
    fun startIfPossible(): Boolean {
        val adapter = bluetoothAdapter(context)
        problem = when {
            adapter == null -> Problem.NoBluetooth
            !adapter.isEnabled -> Problem.BluetoothOff
            else -> null
        }
        if (problem == null) vm.startSync()
        return problem == null
    }

    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) startIfPossible()
    }
    val requestPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) {
            startIfPossible()
        } else {
            val activity = context as? Activity
            // after a refusal, "do not ask again" shows as rationale == false
            val forever = activity != null && granted.filterValues { !it }.keys.any {
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
            }
            problem = Problem.PermissionDenied(forever)
        }
    }

    fun syncWithPermissions() {
        val missing = missingPermissions(context)
        if (missing.isEmpty()) startIfPossible() else requestPermissions.launch(missing.toTypedArray())
    }

    // scan as soon as the app is opened, if nothing needs asking; once per activity, not again on rotation
    var autoStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!autoStarted && state.autoSync && !state.syncing && missingPermissions(context).isEmpty()) {
            autoStarted = true
            if (bluetoothAdapter(context)?.isEnabled == true) vm.startSync()
        }
    }

    val exportSubject = stringResource(R.string.export_subject)
    val exportChooser = stringResource(R.string.export_chooser)
    fun export() {
        scope.launch {
            val uri = vm.exportCsv(context)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_SUBJECT, exportSubject)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, exportChooser))
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = null) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.export_csv)) },
                            enabled = state.readings.isNotEmpty(),
                            onClick = { menuOpen = false; export() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.name_users)) },
                            onClick = { menuOpen = false; namesOpen = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.auto_sync)) },
                            trailingIcon = { Checkbox(checked = state.autoSync, onCheckedChange = null) },
                            onClick = { vm.setAutoSync(!state.autoSync) },
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).padding(horizontal = 16.dp).fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.syncing) OutlinedButton(onClick = { vm.stopSync() }) { Text(stringResource(R.string.stop)) }
                else Button(onClick = { syncWithPermissions() }) { Text(stringResource(R.string.sync)) }
            }
            problem?.let { p ->
                Spacer(Modifier.height(12.dp))
                ProblemCard(
                    problem = p,
                    onEnableBluetooth = { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                    onGrant = { syncWithPermissions() },
                    onOpenSettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        )
                    },
                )
            }
            if (state.log.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        for (line in state.log.takeLast(6)) Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            if (state.readings.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.userFilter == null,
                        onClick = { vm.setUserFilter(null) },
                        label = { Text(stringResource(R.string.filter_all)) },
                    )
                    for (u in listOf("A", "B")) FilterChip(
                        selected = state.userFilter == u,
                        onClick = { vm.setUserFilter(u) },
                        label = { Text(state.userName(u)) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (state.readings.isEmpty()) {
                Text(stringResource(R.string.no_readings), style = MaterialTheme.typography.bodyMedium)
            } else if (state.visibleReadings.isEmpty()) {
                Text(stringResource(R.string.no_readings_for_user, state.userName(state.userFilter)), style = MaterialTheme.typography.bodyMedium)
            } else {
                ReadingsList(
                    readings = state.visibleReadings,
                    showUser = state.userFilter == null,
                    userName = { state.userName(it) },
                    onDelete = { vm.delete(it) },
                )
            }
        }
    }

    if (namesOpen) UserNamesDialog(
        names = state.userNames,
        onSave = { vm.setUserNames(it); namesOpen = false },
        onDismiss = { namesOpen = false },
    )
}

@Composable
private fun ProblemCard(problem: Problem, onEnableBluetooth: () -> Unit, onGrant: () -> Unit, onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            val text = when (problem) {
                Problem.NoBluetooth -> stringResource(R.string.no_bluetooth)
                Problem.BluetoothOff -> stringResource(R.string.bluetooth_off)
                is Problem.PermissionDenied -> when {
                    problem.forever -> stringResource(R.string.permission_denied_forever)
                    Build.VERSION.SDK_INT >= 31 -> stringResource(R.string.permission_denied)
                    else -> stringResource(R.string.permission_denied_location)
                }
            }
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (problem) {
                    Problem.NoBluetooth -> Unit
                    Problem.BluetoothOff -> TextButton(onClick = onEnableBluetooth) { Text(stringResource(R.string.turn_on_bluetooth)) }
                    is Problem.PermissionDenied -> {
                        if (problem.forever) TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.open_settings)) }
                        else TextButton(onClick = onGrant) { Text(stringResource(R.string.grant_again)) }
                    }
                }
            }
        }
    }
}
