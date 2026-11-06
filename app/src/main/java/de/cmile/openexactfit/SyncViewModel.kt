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
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class UiState(
    /** every stored reading, newest first; the screen filters by user. */
    val readings: List<StoredReading> = emptyList(),
    val log: List<String> = emptyList(),
    val syncing: Boolean = false,
    /** "A", "B" or null for all. */
    val userFilter: String? = null,
    val userNames: Map<String, String> = emptyMap(),
    val autoSync: Boolean = true,
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
    private val _state = MutableStateFlow(UiState(userNames = settings.userNames(), autoSync = settings.autoSync))
    val state: StateFlow<UiState> = _state
    private var job: Job? = null

    init { reload() }

    fun reload() {
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) { store.all() }
            _state.update { it.copy(readings = rows) }
        }
    }

    fun startSync() {
        if (job?.isActive == true) return
        _state.update { it.copy(syncing = true, log = emptyList()) }
        job = viewModelScope.launch {
            try {
                client.sync { event ->
                    when (event) {
                        is ExactFitClient.Event.Log -> log(event.text)
                        is ExactFitClient.Event.Reading -> {
                            val m = event.measurement
                            val fresh = withContext(Dispatchers.IO) { store.add(m, event.deviceAddress) }
                            log("${m.systolic}/${m.diastolic} pulse ${m.pulse ?: "?"} user ${m.user ?: "?"} (${event.source}${if (fresh) "" else ", already stored"})")
                            if (fresh) reload()
                        }
                    }
                }
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

    /** deletes from the app's database only. */
    fun delete(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(id) }
            Log.i(TAG, "deleted reading $id from the app database")
            reload()
        }
    }

    /** writes the csv to the cache and returns a uri other apps may read once. */
    suspend fun exportCsv(context: Context): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        val file = File(dir, "exactfit-readings-${LocalDate.now()}.csv")
        file.writeText(store.csv())
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private fun log(text: String) {
        Log.i(TAG, text)
        val stamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        _state.update { it.copy(log = (it.log + "$stamp  $text").takeLast(50)) }
    }

    companion object {
        /** `adb logcat -s OpenExactFit` follows a sync from the shell */
        const val TAG = "OpenExactFit"
    }
}
