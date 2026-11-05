package de.cmile.openexactfit.ble

import de.cmile.openexactfit.ble.Protocol.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** the same captured bytes as tests/test_protocol.py. */
class ProtocolTest {
    @Test fun liveRecordFromOwnDevice() {
        val m = Protocol.parseMeasurement(Protocol.hex("1e800050005a00ea07010e071e004800000000"))
        assertEquals(128, m.systolic); assertEquals(80, m.diastolic); assertEquals(72, m.pulse)
        assertEquals("A", m.user); assertEquals(LocalDateTime.of(2026, 1, 14, 7, 30), m.timestamp)
        assertTrue(m.flags.isEmpty()); assertEquals(90, m.mapField)
    }

    @Test fun storedRecordFromOwnDevice() {
        val m = Protocol.parseMeasurement(Protocol.hex("1e76004c000000ea07010e0723004400000000"))
        assertEquals(118, m.systolic); assertEquals(76, m.diastolic); assertEquals(68, m.pulse)
        assertEquals(LocalDateTime.of(2026, 1, 14, 7, 35), m.timestamp); assertEquals(0, m.mapField)
    }

    @Test fun upstreamCaptureWithBodyMovement() {
        val m = Protocol.parseMeasurement(Protocol.hex("1e740049005a00ea070a0b0f0f005b00000100"))
        assertEquals(116, m.systolic); assertEquals(73, m.diastolic); assertEquals(91, m.pulse)
        assertEquals(listOf("body_movement"), m.flags)
    }

    @Test fun numReadings() {
        val n = Protocol.parseNumReadings(Protocol.hex("023c0100"))
        assertEquals(2, n.users); assertEquals(60, n.slotsPerUser); assertEquals(listOf(1, 0), n.pending)
    }

    @Test fun aesFips197Vector() {
        val enc = Protocol.encryptCommand(Protocol.hex("3243f6a8885a308d313198a2e0370734"))
        assertEquals("3925841d02dc09fbdc118597196a0b32", enc.toHex())
    }

    @Test fun readingRequest() {
        assertArrayEquals(byteArrayOf(1, 7), Protocol.buildReadingRequest(1, 7, encrypted = false))
        val enc = Protocol.buildReadingRequest(1, 7, encrypted = true)
        assertEquals(16, enc.size)
        assertEquals("0107" + "ff".repeat(14), Protocol.decryptBlockAt(enc, 0).toHex())
    }

    @Test fun firmwareThreshold() {
        assertFalse(Protocol.firmwareRequiresAes("1.0.14/A01.003"))
        assertTrue(Protocol.firmwareRequiresAes("1.0.15/A01.004"))
        assertFalse(Protocol.firmwareRequiresAes(""))
    }
}
