package de.cmile.openexactfit

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.Settings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import de.cmile.openexactfit.ui.AveragesView
import de.cmile.openexactfit.ui.ChartRange
import de.cmile.openexactfit.ui.FLAG_LABELS
import de.cmile.openexactfit.ui.ReadingsList
import de.cmile.openexactfit.ui.Report
import de.cmile.openexactfit.ui.ReportLabels
import de.cmile.openexactfit.ui.TrendChart
import de.cmile.openexactfit.ui.UserNamesDialog
import de.cmile.openexactfit.ui.groupSessions
import de.cmile.openexactfit.ui.theme.OpenExactFitTheme
import de.cmile.openexactfit.data.MeasurementStore
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

/** what scanning and connecting need: the "nearby devices" pair, no location (minSdk 33). */
fun blePermissions(): Array<String> = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

fun missingPermissions(context: Context): List<String> =
    blePermissions().filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }

private fun bluetoothAdapter(context: Context): BluetoothAdapter? =
    (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

/** the report's words from resources; plain context calls, because the report is built outside composition. */
fun reportLabels(context: Context) = ReportLabels(
    title = context.getString(R.string.report_title),
    printed = context.getString(R.string.report_printed),
    user = context.getString(R.string.user_label),
    time = context.getString(R.string.report_time),
    systolic = context.getString(R.string.series_systolic),
    diastolic = context.getString(R.string.series_diastolic),
    pulse = context.getString(R.string.series_pulse),
    band = context.getString(R.string.report_band),
    notes = context.getString(R.string.report_notes),
    average = context.getString(R.string.report_average),
    received = context.getString(R.string.report_received),
    readings = { n -> context.resources.getQuantityString(R.plurals.readings_count, n, n) },
    category = { c -> context.getString(c.label) },
    flag = { f -> FLAG_LABELS[f]?.let(context::getString) ?: f.replace('_', ' ') },
)

/** kept alive while the print framework renders from it; it does so asynchronously after the dialog opens. */
private var printWebView: WebView? = null

/** hands an html page to android's print dialog, which also offers "save as pdf". needs an activity context. */
fun printHtml(context: Context, html: String, jobName: String) {
    val webView = WebView(context)
    printWebView = webView
    webView.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String?) {
            val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
            manager.print(jobName, view.createPrintDocumentAdapter(jobName), PrintAttributes.Builder().build())
        }
    }
    webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
}

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
    /** 0 list, 1 chart, 2 averages */
    var view by rememberSaveable { mutableIntStateOf(0) }
    var range by rememberSaveable { mutableStateOf(ChartRange.MONTH) }

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
    // what to do once the bluetooth permissions are granted: a sync, or switching the service on
    var afterPermissions by remember { mutableStateOf<() -> Unit>({}) }
    val requestPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) {
            afterPermissions()
        } else {
            val activity = context as? Activity
            // after a refusal, "do not ask again" shows as rationale == false
            val forever = activity != null && granted.filterValues { !it }.keys.any {
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
            }
            problem = Problem.PermissionDenied(forever)
        }
    }

    fun withPermissions(action: () -> Unit) {
        val missing = missingPermissions(context)
        if (missing.isEmpty()) {
            action()
        } else {
            afterPermissions = action
            requestPermissions.launch(missing.toTypedArray())
        }
    }

    fun syncWithPermissions() = withPermissions { startIfPossible() }

    // the service works without the notification permission too, it just stays quiet about new readings
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.setBackgroundSync(true) }
    fun enableBackgroundSync() = withPermissions {
        val needsAsking = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsAsking) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.setBackgroundSync(true)
    }

    // on opening: bring the background service back if it is wanted, otherwise scan once if nothing needs asking.
    // once per activity, not again on rotation.
    var autoStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (autoStarted || missingPermissions(context).isNotEmpty()) return@LaunchedEffect
        autoStarted = true
        when {
            state.backgroundSync -> vm.ensureBackgroundSync()
            state.autoSync && !state.syncing && bluetoothAdapter(context)?.isEnabled == true -> vm.startSync()
        }
    }

    val exportSubject = stringResource(R.string.export_subject)
    val exportChooser = stringResource(R.string.export_chooser)
    // csv import: the system's document picker, then a short result dialog. mail apps hand
    // csv out under odd mime types, so the picker is not filtered.
    var importResult by remember { mutableStateOf<MeasurementStore.ImportResult?>(null) }
    var importFailed by remember { mutableStateOf(false) }
    val pickCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val result = vm.importCsv(context, uri)
            if (result == null) importFailed = true else importResult = result
        }
    }

    val reportTitle = stringResource(R.string.report_title)
    fun print() {
        val html = Report.html(state.visibleReadings, { state.userName(it) }, reportLabels(context),
            sessions = state.groupSessions, sessionMeans = state.averageSessions)
        printHtml(context, html, reportTitle)
    }
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
                            text = { Text(stringResource(R.string.print)) },
                            enabled = state.visibleReadings.isNotEmpty(),
                            onClick = { menuOpen = false; print() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.export_csv)) },
                            enabled = state.readings.isNotEmpty(),
                            onClick = { menuOpen = false; export() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.import_csv)) },
                            onClick = { menuOpen = false; pickCsv.launch(arrayOf("*/*")) },
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
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.background_sync)) },
                            trailingIcon = { Checkbox(checked = state.backgroundSync, onCheckedChange = null) },
                            onClick = {
                                menuOpen = false
                                if (state.backgroundSync) vm.setBackgroundSync(false) else enableBackgroundSync()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.show_log)) },
                            trailingIcon = { Checkbox(checked = state.showLog, onCheckedChange = null) },
                            onClick = { vm.setShowLog(!state.showLog) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.group_sessions)) },
                            trailingIcon = { Checkbox(checked = state.groupSessions, onCheckedChange = null) },
                            onClick = { vm.setGroupSessions(!state.groupSessions) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.average_sessions)) },
                            trailingIcon = { Checkbox(checked = state.averageSessions, onCheckedChange = null) },
                            onClick = { vm.setAverageSessions(!state.averageSessions) },
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).padding(horizontal = 16.dp).fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    state.backgroundRunning -> Text(stringResource(R.string.background_running), style = MaterialTheme.typography.bodyMedium)
                    state.syncing -> OutlinedButton(onClick = { vm.stopSync() }) { Text(stringResource(R.string.stop)) }
                    else -> Button(onClick = { syncWithPermissions() }) { Text(stringResource(R.string.sync)) }
                }
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
            if (state.showLog && state.log.isNotEmpty()) {
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
                Spacer(Modifier.height(4.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val labels = listOf(R.string.view_list, R.string.view_chart, R.string.view_averages)
                    labels.forEachIndexed { i, label ->
                        SegmentedButton(selected = view == i, onClick = { view = i }, shape = SegmentedButtonDefaults.itemShape(i, labels.size)) {
                            Text(stringResource(label), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            when {
                state.readings.isEmpty() ->
                    Text(stringResource(R.string.no_readings), style = MaterialTheme.typography.bodyMedium)
                state.visibleReadings.isEmpty() ->
                    Text(stringResource(R.string.no_readings_for_user, state.userName(state.userFilter)), style = MaterialTheme.typography.bodyMedium)
                view == 1 -> ChartView(state, range, onRange = { range = it })
                view == 2 -> AveragesPane(state)
                else -> ReadingsList(
                    readings = state.visibleReadings,
                    showUser = state.userFilter == null,
                    sessions = state.groupSessions,
                    userName = { state.userName(it) },
                    onDelete = { vm.delete(it) },
                    onNote = { id, note -> vm.setNote(id, note) },
                )
            }
        }
    }

    if (namesOpen) UserNamesDialog(
        names = state.userNames,
        onSave = { vm.setUserNames(it); namesOpen = false },
        onDismiss = { namesOpen = false },
    )

    importResult?.let { r ->
        AlertDialog(
            onDismissRequest = { importResult = null },
            title = { Text(stringResource(R.string.import_title)) },
            text = { Text(stringResource(R.string.import_result, r.fresh, r.duplicates, r.unreadable)) },
            confirmButton = { TextButton(onClick = { importResult = null }) { Text(stringResource(R.string.close)) } },
        )
    }
    if (importFailed) AlertDialog(
        onDismissRequest = { importFailed = false },
        title = { Text(stringResource(R.string.import_title)) },
        text = { Text(stringResource(R.string.import_failed)) },
        confirmButton = { TextButton(onClick = { importFailed = false }) { Text(stringResource(R.string.close)) } },
    )
}

/** range chips and one chart per user: the filtered user, or every user with readings when "all" is selected. */
@Composable
private fun ChartView(state: UiState, range: ChartRange, onRange: (ChartRange) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (r in ChartRange.entries) FilterChip(selected = range == r, onClick = { onRange(r) }, label = { Text(rangeLabel(r)) })
        }
        val groups = if (state.userFilter == null) state.readings.groupBy { it.user }.toList().sortedBy { it.first ?: "~" }
        else listOf(state.userFilter to state.visibleReadings)
        for ((user, rows) in groups) {
            Spacer(Modifier.height(8.dp))
            if (groups.size > 1) Text(state.userName(user), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            // with sessions on, the chart draws one point per sitting (its mean) instead of every reading
            val points = remember(rows, state.groupSessions) {
                if (state.groupSessions) groupSessions(rows).map { it.asReading() } else rows
            }
            TrendChart(readings = points, range = range)
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** averages for the filtered user; with "all" selected and two users with readings, asks for a choice instead of mixing people. */
@Composable
private fun AveragesPane(state: UiState) {
    val users = state.readings.map { it.user }.distinct()
    if (state.userFilter == null && users.size > 1) {
        Text(stringResource(R.string.averages_pick_user), style = MaterialTheme.typography.bodyMedium)
    } else {
        AveragesView(readings = state.visibleReadings, modifier = Modifier.fillMaxWidth(), bySession = state.averageSessions)
    }
}

@Composable
private fun rangeLabel(range: ChartRange): String = stringResource(
    when (range) {
        ChartRange.WEEK -> R.string.range_week
        ChartRange.MONTH -> R.string.range_month
        ChartRange.QUARTER -> R.string.range_quarter
        ChartRange.ALL -> R.string.range_all
    }
)

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
                is Problem.PermissionDenied ->
                    if (problem.forever) stringResource(R.string.permission_denied_forever) else stringResource(R.string.permission_denied)
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
