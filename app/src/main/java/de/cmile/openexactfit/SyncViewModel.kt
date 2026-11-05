package de.cmile.openexactfit

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.cmile.openexactfit.ble.ExactFitClient
import de.cmile.openexactfit.data.MeasurementStore
import de.cmile.openexactfit.data.StoredReading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class UiState(
    val readings: List<StoredReading> = emptyList(),
    val log: List<String> = emptyList(),
    val syncing: Boolean = false,
)

class SyncViewModel(app: Application) : AndroidViewModel(app) {
    private val store = MeasurementStore(app)
    private val client = ExactFitClient(app)
    private val _state = MutableStateFlow(UiState())
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

    fun csv(): String = store.csv()

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
