package de.cmile.openexactfit

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.cmile.openexactfit.ble.ExactFitClient
import de.cmile.openexactfit.data.MeasurementStore
import de.cmile.openexactfit.data.Settings
import de.cmile.openexactfit.data.StoredReading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

data class UiState(
    /** every stored reading, newest first; the screen filters by user. */
    val readings: List<StoredReading> = emptyList(),
    val log: List<String> = emptyList(),
    /** the screen's own sync is running. */
    val syncing: Boolean = false,
    /** "A", "B" or null for all. */
    val userFilter: String? = null,
    val userNames: Map<String, String> = emptyMap(),
    val autoSync: Boolean = true,
    /** the menu switch for the foreground service. */
    val backgroundSync: Boolean = false,
    /** the service's scan loop is alive right now. */
    val backgroundRunning: Boolean = false,
) {
    val visibleReadings: List<StoredReading>
        get() = if (userFilter == null) readings else readings.filter { it.user == userFilter }

    /** the name the owner gave a user, or the monitor's letter. */
    fun userName(user: String?): String = user?.let { userNames[it] ?: it } ?: "?"
}

class SyncViewModel(app: Application) : AndroidViewModel(app) {
    private val store = MeasurementStore(app)
    private val settings = Settings(app)
    private val client = ExactFitClient(app)
    private val _state = MutableStateFlow(
        UiState(userNames = settings.userNames(), autoSync = settings.autoSync, backgroundSync = settings.backgroundSync)
    )
    val state: StateFlow<UiState> = _state
    private var job: Job? = null

    init {
        // the store signals every change, the background service's included; the first value loads the list
        viewModelScope.launch { MeasurementStore.changes.collect { reload() } }
        viewModelScope.launch { SyncLog.lines.collect { lines -> _state.update { it.copy(log = lines) } } }
        viewModelScope.launch { SyncService.running.collect { on -> _state.update { it.copy(backgroundRunning = on) } } }
    }

    private suspend fun reload() {
        val rows = withContext(Dispatchers.IO) { store.all() }
        _state.update { it.copy(readings = rows) }
    }

    /** the screen's own sync; not while the background service holds the monitor. */
    fun startSync() {
        if (job?.isActive == true || SyncService.running.value) return
        SyncLog.clear()
        _state.update { it.copy(syncing = true) }
        job = viewModelScope.launch {
            try {
                runSync(client, store)
            } finally {
                _state.update { it.copy(syncing = false) }
            }
        }
    }

    fun stopSync() {
        job?.cancel()
        job = null
    }

    fun setUserFilter(user: String?) = _state.update { it.copy(userFilter = user) }

    fun setUserNames(names: Map<String, String>) {
        settings.setUserNames(names)
        _state.update { it.copy(userNames = settings.userNames()) }
    }

    fun setAutoSync(on: Boolean) {
        settings.autoSync = on
        _state.update { it.copy(autoSync = on) }
    }

    /** switches the foreground service on or off; the screen's own sync stops first so only one client talks to the monitor. */
    fun setBackgroundSync(on: Boolean) {
        settings.backgroundSync = on
        _state.update { it.copy(backgroundSync = on) }
        if (on) {
            stopSync()
            SyncService.start(getApplication())
        } else {
            SyncService.stop(getApplication())
        }
    }

    /** restarts the service when it is wanted but not alive, e.g. after the process was killed. */
    fun ensureBackgroundSync() {
        if (settings.backgroundSync && !SyncService.running.value) SyncService.start(getApplication())
    }

    /** deletes from the app's database only; the store's change signal reloads the list. */
    fun delete(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(id) }
            Log.i(SyncLog.TAG, "deleted reading $id from the app database")
        }
    }

    /** writes the csv to the cache and returns a uri other apps may read once. */
    suspend fun exportCsv(context: Context): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        val file = File(dir, "exactfit-readings-${LocalDate.now()}.csv")
        file.writeText(store.csv())
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}
