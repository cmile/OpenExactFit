package de.cmile.openexactfit

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * the session log shown on the screen and written to logcat. one per process, so the
 * background service and the screen's own sync write to the same place.
 */
object SyncLog {
    /** `adb logcat -s OpenExactFit` follows a sync from the shell */
    const val TAG = "OpenExactFit"

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun add(text: String) {
        Log.i(TAG, text)
        val stamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        _lines.update { (it + "$stamp  $text").takeLast(50) }
    }

    fun clear() = _lines.update { emptyList() }
}
