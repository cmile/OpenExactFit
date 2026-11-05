package de.cmile.openexactfit.ble

import java.time.LocalDateTime
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * wire protocol of the braun exactfit 5 connect (bua6350).
 *
 * port of `exactfit/protocol.py` in this repository; `docs/protocol-notes.md`
 * holds the evidence. a finished measurement arrives as a notification in the
 * layout of the bluetooth sig blood pressure measurement characteristic
 * (0x2a35), little-endian. firmware 1.0.14 talks in the clear; newer firmware
 * wraps commands and record bodies in one aes-128 block under a fixed key.
 */
object Protocol {
    val MEASUREMENT_SERVICE: UUID = UUID.fromString("56484aae-a8eb-4a97-ac19-a8ea6373e05a")
    val MEASUREMENT: UUID = UUID.fromString("2db34480-bce5-4bb7-9f56-55bd202317c5")
    val CUFF_PRESSURE: UUID = UUID.fromString("77ab1c51-2f6b-4a7b-81af-5e301984bf13")

    val CONFIG_SERVICE: UUID = UUID.fromString("bb647f01-d352-48de-9015-d055b1355d7b")
    val CLOCK: UUID = UUID.fromString("6114ac81-2a71-4acc-ada5-555b63e8c5e1")
    val NUM_READINGS: UUID = UUID.fromString("25775d68-eb3f-4ee5-961a-5312516cb0ca")
    val READING_REQUEST: UUID = UUID.fromString("d4a0c8c3-6387-4aca-bfab-21ac412e5be8")
    val REQUESTED_READING: UUID = UUID.fromString("991dee0f-75c1-4c7c-93ed-78fef7f8e6db")
    val ERROR: UUID = UUID.fromString("bc305904-03cd-4858-8ae0-b184f2a52898")
    // destructive, never written by this app:
    // delete reading 2df251d7-b858-450c-9779-32745e5e4727, factory reset 598040af-4de1-45f2-853f-9aba929fcfd2

    val DEVICE_INFO_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val FIRMWARE_REVISION: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val LOCAL_NAME_PREFIX = "BUA"
    val USERS = listOf("A", "B")
    const val KPA_TO_MMHG = 7.50062

    /** the example key of fips-197 appendix b, hard-coded in the vendor app. */
    val AES_BASE_KEY: ByteArray = hex("2b7e151628aed2a6abf7158809cf4f3c")
    const val AES_MIN_FIRMWARE = "1.0.14"
    private const val PAD: Byte = 0xFF.toByte()

    private const val FLAG_KPA = 0x01
    private const val FLAG_TIMESTAMP = 0x02
    private const val FLAG_PULSE = 0x04
    private const val FLAG_USER = 0x08
    private const val FLAG_STATUS = 0x10

    class ProtocolException(message: String) : Exception(message)

    data class Measurement(
        val systolic: Int,
        val diastolic: Int,
        /** bytes 5..6: constant 90 in live records, 0 in stored ones. not a map. */
        val mapField: Int,
        val pulse: Int?,
        val user: String?,
        /** the monitor's local time, minute precision (seconds are always 0). */
        val timestamp: LocalDateTime?,
        val clockWasUnset: Boolean,
        val bodyMovement: Boolean,
        val cuffTooLoose: Boolean,
        val irregularPulse: Boolean,
        val pulseRange: Int,
        val improperPosition: Boolean,
        val raw: ByteArray,
    ) {
        val flags: List<String>
            get() = buildList {
                if (bodyMovement) add("body_movement")
                if (cuffTooLoose) add("cuff_too_loose")
                if (irregularPulse) add("irregular_pulse")
                if (pulseRange == 1) add("pulse_too_high")
                if (pulseRange == 2) add("pulse_too_low")
                if (improperPosition) add("improper_position")
            }
    }

    /** ieee-11073 16-bit sfloat: 12-bit signed mantissa, 4-bit signed exponent. */
    fun sfloat(lo: Int, hi: Int): Double {
        val value = (hi shl 8) or lo
        var mantissa = value and 0x0FFF
        var exponent = value shr 12
        if (mantissa >= 0x0800) mantissa -= 0x1000
        if (exponent >= 0x08) exponent -= 0x10
        return mantissa * Math.pow(10.0, exponent.toDouble())
    }

    fun parseMeasurement(payload: ByteArray): Measurement {
        if (payload.size < 7) throw ProtocolException("payload too short: ${payload.toHex()}")
        val b = payload.map { it.toInt() and 0xFF }
        val flags = b[0]
        val scale = if (flags and FLAG_KPA != 0) KPA_TO_MMHG else 1.0
        var offset = 7
        try {
            var timestamp: LocalDateTime? = null
            var clockUnset = false
            if (flags and FLAG_TIMESTAMP != 0) {
                val year = b[offset] or (b[offset + 1] shl 8)
                val month = b[offset + 2]
                clockUnset = month and 0x80 != 0
                timestamp = LocalDateTime.of(year, month and 0x7F, b[offset + 3], b[offset + 4], b[offset + 5], b[offset + 6])
                offset += 7
            }
            var pulse: Int? = null
            if (flags and FLAG_PULSE != 0) {
                pulse = Math.round(sfloat(b[offset], b[offset + 1])).toInt()
                offset += 2
            }
            var user: String? = null
            if (flags and FLAG_USER != 0) {
                user = USERS.getOrNull(b[offset]) ?: b[offset].toString()
                offset += 1
            }
            var status = 0
            if (flags and FLAG_STATUS != 0) status = b[offset] or (b[offset + 1] shl 8)
            return Measurement(
                systolic = Math.round(sfloat(b[1], b[2]) * scale).toInt(),
                diastolic = Math.round(sfloat(b[3], b[4]) * scale).toInt(),
                mapField = Math.round(sfloat(b[5], b[6]) * scale).toInt(),
                pulse = pulse,
                user = user,
                timestamp = timestamp,
                clockWasUnset = clockUnset,
                bodyMovement = status and 0x01 != 0,
                cuffTooLoose = status and 0x02 != 0,
                irregularPulse = status and 0x04 != 0,
                pulseRange = (status and 0x18) shr 3,
                improperPosition = status and 0x20 != 0,
                raw = payload.copyOf(),
            )
        } catch (e: IndexOutOfBoundsException) {
            throw ProtocolException("malformed payload ${payload.toHex()}")
        } catch (e: java.time.DateTimeException) {
            throw ProtocolException("malformed timestamp in ${payload.toHex()}: ${e.message}")
        }
    }

    data class NumReadings(val users: Int, val slotsPerUser: Int, val pending: List<Int>)

    /** [users, slots per user, pending user a, pending user b]. pending = not yet collected by the app. */
    fun parseNumReadings(payload: ByteArray): NumReadings {
        if (payload.size < 4) throw ProtocolException("num-readings payload too short: ${payload.toHex()}")
        val b = payload.map { it.toInt() and 0xFF }
        return NumReadings(b[0], b[1], listOf(b[2], b[3]))
    }

    fun padPayload(data: ByteArray, size: Int = 16): ByteArray {
        require(data.size <= size) { "payload longer than block" }
        return ByteArray(size) { i -> if (i < data.size) data[i] else PAD }
    }

    private fun cipher(mode: Int): Cipher =
        Cipher.getInstance("AES/ECB/NoPadding").apply { init(mode, SecretKeySpec(AES_BASE_KEY, "AES")) }

    fun encryptCommand(data: ByteArray): ByteArray = cipher(Cipher.ENCRYPT_MODE).doFinal(padPayload(data))

    /** decrypt the 16 bytes at [start] in place; a record keeps its flags byte in the clear. */
    fun decryptBlockAt(payload: ByteArray, start: Int): ByteArray {
        if (payload.size < start + 16) throw ProtocolException("payload too short for a block at $start: ${payload.toHex()}")
        val clear = cipher(Cipher.DECRYPT_MODE).doFinal(payload, start, 16)
        return payload.copyOfRange(0, start) + clear + payload.copyOfRange(start + 16, payload.size)
    }

    /** the app's threshold: strictly newer than 1.0.14 (its comparison returns false on equality). */
    fun firmwareRequiresAes(firmwareRevision: String): Boolean {
        val parts = firmwareRevision.substringBefore('/').trim().split('.').mapNotNull { it.toIntOrNull() }
        if (parts.isEmpty()) return false
        val want = AES_MIN_FIRMWARE.split('.').map { it.toInt() }
        for (i in want.indices) {
            val v = parts.getOrElse(i) { 0 }
            if (v > want[i]) return true
            if (v < want[i]) return false
        }
        return false
    }

    /** ask for stored reading [index] of [user] (0 = a, 1 = b); the answer comes on [REQUESTED_READING]. */
    fun buildReadingRequest(user: Int, index: Int, encrypted: Boolean): ByteArray {
        val plain = byteArrayOf((user and 0xFF).toByte(), (index and 0xFF).toByte())
        return if (encrypted) encryptCommand(plain) else plain
    }

    fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
