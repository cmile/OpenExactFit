package de.cmile.openexactfit

import android.bluetooth.BluetoothDevice
import de.cmile.openexactfit.ble.ExactFitClient
import de.cmile.openexactfit.ble.Protocol
import de.cmile.openexactfit.data.MeasurementStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * one sync, shared by the screen and the background service: runs the client, stores
 * what arrives, writes the log. [device] skips the scan when the caller already found
 * the monitor. returns the number of readings that were new to the database.
 */
suspend fun runSync(
    client: ExactFitClient,
    store: MeasurementStore,
    device: BluetoothDevice? = null,
    onFresh: suspend (Protocol.Measurement) -> Unit = {},
): Int {
    var fresh = 0
    val handler: suspend (ExactFitClient.Event) -> Unit = { event ->
        when (event) {
            is ExactFitClient.Event.Log -> SyncLog.add(event.text)
            is ExactFitClient.Event.Reading -> {
                val m = event.measurement
                val isNew = withContext(Dispatchers.IO) { store.add(m, event.deviceAddress) }
                SyncLog.add("${m.systolic}/${m.diastolic} pulse ${m.pulse ?: "?"} user ${m.user ?: "?"} (${event.source}${if (isNew) "" else ", already stored"})")
                if (isNew) {
                    fresh++
                    onFresh(m)
                }
            }
        }
    }
    if (device == null) client.sync(onEvent = handler) else client.sync(device, onEvent = handler)
    return fresh
}
