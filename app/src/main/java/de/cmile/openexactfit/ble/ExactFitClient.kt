package de.cmile.openexactfit.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import de.cmile.openexactfit.ble.Protocol.toHex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

/**
 * one sync with the monitor, the way `exactfit sync` does it: connect, fetch the
 * stored readings the monitor still holds, then stay for live readings until the
 * monitor switches itself off. nothing is ever deleted on the monitor.
 *
 * the caller holds the bluetooth permissions; this class does not check them.
 */
@SuppressLint("MissingPermission")
class ExactFitClient(private val context: Context) {

    sealed class Event {
        data class Log(val text: String) : Event()
        data class Reading(val measurement: Protocol.Measurement, val source: String, val deviceAddress: String) : Event()
    }

    private val adapter get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    suspend fun scan(timeoutMs: Long = 60_000, address: String? = null): BluetoothDevice? {
        val scanner = adapter?.bluetoothLeScanner ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val callback = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        val name = result.scanRecord?.deviceName ?: result.device.name ?: ""
                        val uuids = result.scanRecord?.serviceUuids ?: emptyList()
                        val matches = when {
                            address != null -> result.device.address.equals(address, ignoreCase = true)
                            uuids.any { it.uuid == Protocol.MEASUREMENT_SERVICE } -> true
                            else -> name.uppercase().startsWith(Protocol.LOCAL_NAME_PREFIX)
                        }
                        if (matches && cont.isActive) {
                            scanner.stopScan(this)
                            cont.resume(result.device)
                        }
                    }

                    override fun onScanFailed(errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                val filters = if (address == null)
                    listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(Protocol.MEASUREMENT_SERVICE)).build(),
                           ScanFilter.Builder().setDeviceName("BUA6350").build())
                else listOf(ScanFilter.Builder().setDeviceAddress(address.uppercase()).build())
                val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
                scanner.startScan(filters, settings, callback)
                cont.invokeOnCancellation { scanner.stopScan(callback) }
            }
        }
    }

    /** scan, connect, download stored readings, stay for live ones. returns the number of readings seen. */
    suspend fun sync(liveIdleMs: Long = 300_000, onEvent: suspend (Event) -> Unit): Int {
        onEvent(Event.Log("scanning, switch the monitor on now"))
        val device = scan() ?: run { onEvent(Event.Log("no monitor found")); return 0 }
        onEvent(Event.Log("found ${device.address}"))
        val gatt = GattSession(context, device)
        var count = 0
        try {
            gatt.connect()
            onEvent(Event.Log("connected"))
            // subscribe to live readings first so nothing taken meanwhile is lost
            gatt.enableNotifications(Protocol.MEASUREMENT_SERVICE, Protocol.MEASUREMENT)
            gatt.enableNotifications(Protocol.CONFIG_SERVICE, Protocol.REQUESTED_READING)

            val firmware = runCatching { String(gatt.read(Protocol.DEVICE_INFO_SERVICE, Protocol.FIRMWARE_REVISION)) }.getOrDefault("")
            val rawCounts = gatt.read(Protocol.CONFIG_SERVICE, Protocol.NUM_READINGS)
            val encrypted = rawCounts.size >= 16
            onEvent(Event.Log("firmware $firmware, encryption ${if (encrypted) "on" else "off"}"))
            val counts = Protocol.parseNumReadings(if (encrypted) Protocol.decryptBlockAt(rawCounts, 0) else rawCounts)
            onEvent(Event.Log("pending readings: A=${counts.pending[0]}, B=${counts.pending[1]}"))

            for (user in 0..1) {
                for (index in 0 until counts.pending[user]) {
                    gatt.write(Protocol.CONFIG_SERVICE, Protocol.READING_REQUEST, Protocol.buildReadingRequest(user, index, encrypted))
                    val answer = gatt.awaitNotification(Protocol.REQUESTED_READING, 5_000)
                        ?: run { onEvent(Event.Log("no answer for user $user index $index")); break }
                    val payload = if (encrypted) Protocol.decryptBlockAt(answer, 1) else answer
                    handle(payload, "stored", device.address, onEvent)?.let { count++ }
                }
            }
            onEvent(Event.Log("waiting for live readings, measure now"))
            while (true) {
                val payload = gatt.awaitNotification(Protocol.MEASUREMENT, liveIdleMs) ?: break
                handle(payload, "live", device.address, onEvent)?.let { count++ }
            }
            onEvent(Event.Log(if (gatt.isDisconnected) "monitor switched off" else "no reading for a while, done"))
        } catch (e: Exception) {
            onEvent(Event.Log("error: ${e.message ?: e.javaClass.simpleName}"))
        } finally {
            gatt.close()
        }
        return count
    }

    private suspend fun handle(payload: ByteArray, source: String, address: String, onEvent: suspend (Event) -> Unit): Protocol.Measurement? =
        try {
            val m = Protocol.parseMeasurement(payload)
            onEvent(Event.Reading(m, source, address))
            m
        } catch (e: Protocol.ProtocolException) {
            onEvent(Event.Log("unparsable record ${payload.toHex()}"))
            null
        }
}

/** serialized gatt operations over the callback api, one in flight at a time. */
@SuppressLint("MissingPermission")
private class GattSession(private val context: Context, private val device: BluetoothDevice) {
    private var gatt: BluetoothGatt? = null
    private val opLock = Mutex()
    private var pending: CompletableDeferred<ByteArray?>? = null
    private val connected = CompletableDeferred<Unit>()
    private val disconnected = CompletableDeferred<Unit>()
    private val notifications = Channel<Pair<UUID, ByteArray>>(Channel.UNLIMITED)
    val isDisconnected get() = disconnected.isCompleted

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                disconnected.complete(Unit)
                if (!connected.isCompleted) connected.completeExceptionally(IllegalStateException("disconnected (status $status)"))
                pending?.complete(null)
                notifications.close()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) connected.complete(Unit)
            else connected.completeExceptionally(IllegalStateException("service discovery failed ($status)"))
        }

        @Deprecated("pre-33 callback")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) finish(if (status == BluetoothGatt.GATT_SUCCESS) c.value else null)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            finish(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            finish(if (status == BluetoothGatt.GATT_SUCCESS) ByteArray(0) else null)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            finish(if (status == BluetoothGatt.GATT_SUCCESS) ByteArray(0) else null)
        }

        @Deprecated("pre-33 callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) notifications.trySend(c.uuid to (c.value ?: ByteArray(0)))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            notifications.trySend(c.uuid to value)
        }

        private fun finish(value: ByteArray?) {
            pending?.complete(value)
        }
    }

    suspend fun connect(timeoutMs: Long = 20_000) {
        gatt = if (Build.VERSION.SDK_INT >= 23)
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        else device.connectGatt(context, false, callback)
        withTimeout(timeoutMs) { connected.await() }
    }

    private fun characteristic(service: UUID, uuid: UUID): BluetoothGattCharacteristic =
        gatt?.getService(service)?.getCharacteristic(uuid)
            ?: throw IllegalStateException("characteristic $uuid not found")

    private suspend fun op(timeoutMs: Long = 5_000, start: () -> Boolean): ByteArray? = opLock.withLock {
        if (isDisconnected) throw IllegalStateException("disconnected")
        val d = CompletableDeferred<ByteArray?>()
        pending = d
        if (!start()) throw IllegalStateException("gatt operation refused")
        try {
            withTimeout(timeoutMs) { d.await() }
        } finally {
            pending = null
        }
    }

    suspend fun read(service: UUID, uuid: UUID): ByteArray {
        val c = characteristic(service, uuid)
        return op { gatt!!.readCharacteristic(c) } ?: throw IllegalStateException("read of $uuid failed")
    }

    suspend fun write(service: UUID, uuid: UUID, value: ByteArray) {
        val c = characteristic(service, uuid)
        op {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt!!.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { c.value = value; c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; gatt!!.writeCharacteristic(c) }
            }
        } ?: throw IllegalStateException("write to $uuid failed")
    }

    suspend fun enableNotifications(service: UUID, uuid: UUID) {
        val c = characteristic(service, uuid)
        gatt!!.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(Protocol.CCCD) ?: throw IllegalStateException("no cccd on $uuid")
        op {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt!!.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; gatt!!.writeDescriptor(d) }
            }
        } ?: throw IllegalStateException("enabling notifications on $uuid failed")
    }

    /** next notification on [uuid]; null on timeout or disconnect. other characteristics' notifications are dropped. */
    suspend fun awaitNotification(uuid: UUID, timeoutMs: Long): ByteArray? = withTimeoutOrNull(timeoutMs) {
        while (true) {
            val (u, v) = notifications.receiveCatching().getOrNull() ?: return@withTimeoutOrNull null
            if (u == uuid) return@withTimeoutOrNull v
        }
        @Suppress("UNREACHABLE_CODE") null
    }

    fun close() {
        gatt?.close()
        gatt = null
    }
}
