"""wire protocol of the braun exactfit 5 connect (bua6350).

everything here is taken from captures documented in
https://github.com/cedhuf/ha-braun-ble/blob/main/docs/bua6350-support.md
(mit licensed). a finished measurement is notified on MEASUREMENT_UUID in the
layout of the bluetooth sig "blood pressure measurement" characteristic
(0x2a35), little-endian, unencrypted, no pairing required.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime

# advertised service, also the one carrying the measurement characteristic
MEASUREMENT_SERVICE_UUID = "56484aae-a8eb-4a97-ac19-a8ea6373e05a"
MEASUREMENT_UUID = "2db34480-bce5-4bb7-9f56-55bd202317c5"
# notify, never seen firing; suspected cuff pressure during inflation
CUFF_PRESSURE_UUID = "77ab1c51-2f6b-4a7b-81af-5e301984bf13"

# read-only, reads 0000
FEATURE_UUID = "c3a2ed78-b3f1-4086-ab3b-bf3c5685a745"

# second vendor service ("kaz bpm service" in the vendor app). names are the
# app's own constant names, found in the decompiled healthy heart apk 2.4.4.
CONFIG_SERVICE_UUID = "bb647f01-d352-48de-9015-d055b1355d7b"
CLOCK_UUID = "6114ac81-2a71-4acc-ada5-555b63e8c5e1"            # BPM_SET_TIME_CHAR, read+write
NUM_READINGS_UUID = "25775d68-eb3f-4ee5-961a-5312516cb0ca"     # BPM_NUM_READINGS_CHAR, read
READING_REQUEST_UUID = "d4a0c8c3-6387-4aca-bfab-21ac412e5be8"  # BPM_READING_REQUEST_CHAR, write
REQUESTED_READING_UUID = "991dee0f-75c1-4c7c-93ed-78fef7f8e6db"  # BPM_REQUESTED_READING_CHAR, notify
ERROR_UUID = "bc305904-03cd-4858-8ae0-b184f2a52898"            # BPM_ERROR_CHAR, notify
USER_NAME_UUID = "fce1be76-a487-4331-96b0-53c8097e306c"        # BPM_USER_NAME_CHAR, write+notify
MEASUREMENT_CONTROL_UUID = "53ebc2ce-344c-4c5d-9d65-4740aa4660cd"  # BPM_MEASUREMENT_CONTROL_CHAR, write
PAIRING_UUID = "deffe5de-90b2-4d5c-9888-76bdaa950c78"          # BPM_PAIRING_CHAR, write
# destructive. this tool never writes to these two.
DELETE_READING_UUID = "2df251d7-b858-450c-9779-32745e5e4727"   # BPM_DELETE_READING_CHAR
FACTORY_RESET_UUID = "598040af-4de1-45f2-853f-9aba929fcfd2"    # BPM_FACTORY_RESET_CHAR

# the vendor app wraps command payloads and the body of records in one
# aes-128 block under this fixed key once the firmware (the part before the
# "/") is at least AES_MIN_FIRMWARE. the key is the example key of fips-197.
AES_BASE_KEY = bytes.fromhex("2b7e151628aed2a6abf7158809cf4f3c")
AES_MIN_FIRMWARE = "1.0.14"
PAD_BYTE = 0xFF

# texas instruments over-the-air firmware update. never write to this.
OAD_SERVICE_UUID = "f000ffc0-0451-4000-b000-000000000000"

LOCAL_NAME = "BUA6350"
MANUFACTURER_ID = 0x0203

USERS = ("A", "B")
KPA_TO_MMHG = 7.50062

FLAG_KPA = 0x01
FLAG_TIMESTAMP = 0x02
FLAG_PULSE = 0x04
FLAG_USER = 0x08
FLAG_STATUS = 0x10

STATUS_BODY_MOVEMENT = 0x0001
STATUS_CUFF_TOO_LOOSE = 0x0002
STATUS_IRREGULAR_PULSE = 0x0004
STATUS_PULSE_RANGE_MASK = 0x0018  # 0: ok, 1: too high, 2: too low
STATUS_IMPROPER_POSITION = 0x0020


def sfloat(raw: bytes) -> float:
    """decode an ieee-11073 16-bit sfloat (12-bit mantissa, 4-bit exponent, both signed)."""
    value = int.from_bytes(raw, "little")
    mantissa = value & 0x0FFF
    exponent = value >> 12
    if mantissa >= 0x0800:
        mantissa -= 0x1000
    if exponent >= 0x08:
        exponent -= 0x10
    return mantissa * 10**exponent


@dataclass
class Measurement:
    systolic: int
    diastolic: int
    # bytes 5..6 of the record. the 0x2a35 layout puts the mean arterial
    # pressure here, but every record seen so far (two devices, four readings
    # with different pressures) carries exactly 90 there, so the monitor does
    # not report a map. kept raw, not shown, see docs/protocol-notes.md.
    map_field: int
    pulse: int | None = None
    user: str | None = None
    # device local time as the monitor stamped it; no zone information on the
    # wire, and the seconds byte has always been 0, so minute precision.
    timestamp: datetime | None = None
    # bit 7 of the month byte: the monitor's clock had not been set when this
    # reading was taken, so the timestamp counts from the default date
    clock_was_unset: bool = False
    body_movement: bool = False
    cuff_too_loose: bool = False
    irregular_pulse: bool = False
    pulse_range: int = 0
    improper_position: bool = False
    raw: bytes = field(default=b"", repr=False)

    @property
    def flags(self) -> list[str]:
        names = []
        if self.body_movement:
            names.append("body_movement")
        if self.cuff_too_loose:
            names.append("cuff_too_loose")
        if self.irregular_pulse:
            names.append("irregular_pulse")
        if self.pulse_range == 1:
            names.append("pulse_too_high")
        elif self.pulse_range == 2:
            names.append("pulse_too_low")
        if self.improper_position:
            names.append("improper_position")
        return names

    def __str__(self) -> str:
        when = self.timestamp.strftime("%Y-%m-%d %H:%M:%S") if self.timestamp else "no time"
        pulse = f"pulse {self.pulse}" if self.pulse is not None else "no pulse"
        flags = f" [{', '.join(self.flags)}]" if self.flags else ""
        return f"{when} user {self.user or '?'}: {self.systolic}/{self.diastolic} mmHg, {pulse}{flags}"


class ProtocolError(ValueError):
    pass


def parse_measurement(payload: bytes) -> Measurement:
    """parse one blood-pressure-measurement notification."""
    if len(payload) < 7:
        raise ProtocolError(f"payload too short: {payload.hex()}")
    flags = payload[0]
    scale = KPA_TO_MMHG if flags & FLAG_KPA else 1
    m = Measurement(
        systolic=round(sfloat(payload[1:3]) * scale),
        diastolic=round(sfloat(payload[3:5]) * scale),
        map_field=round(sfloat(payload[5:7]) * scale),
        raw=bytes(payload),
    )
    offset = 7
    try:
        if flags & FLAG_TIMESTAMP:
            year = int.from_bytes(payload[offset : offset + 2], "little")
            month, day, hour, minute, second = payload[offset + 2 : offset + 7]
            m.clock_was_unset = bool(month & 0x80)
            m.timestamp = datetime(year, month & 0x7F, day, hour, minute, second)
            offset += 7
        if flags & FLAG_PULSE:
            m.pulse = round(sfloat(payload[offset : offset + 2]))
            offset += 2
        if flags & FLAG_USER:
            user = payload[offset]
            m.user = USERS[user] if user < len(USERS) else str(user)
            offset += 1
        if flags & FLAG_STATUS:
            status = int.from_bytes(payload[offset : offset + 2], "little")
            m.body_movement = bool(status & STATUS_BODY_MOVEMENT)
            m.cuff_too_loose = bool(status & STATUS_CUFF_TOO_LOOSE)
            m.irregular_pulse = bool(status & STATUS_IRREGULAR_PULSE)
            m.pulse_range = (status & STATUS_PULSE_RANGE_MASK) >> 3
            m.improper_position = bool(status & STATUS_IMPROPER_POSITION)
            offset += 2
    except (ValueError, IndexError) as exc:
        raise ProtocolError(f"malformed payload {payload.hex()}: {exc}") from exc
    return m


def parse_clock(payload: bytes) -> tuple[datetime, int]:
    """parse the clock characteristic.

    layout: month, day, year (2 bytes le), hour, constant 0x30, minute, second.
    the upstream document reads bytes 5..6 as minute and second, which happened
    to look right on its capture (``0f 30 06 2f`` -> "15:48:06"); on a second
    device the same byte was 0x30 again while the display showed 22:25, which
    only fits if 0x30 is a constant and the minute follows it. meaning of 0x30
    unknown (format setting?). returned with the time as ``extra``.
    """
    if len(payload) < 8:
        raise ProtocolError(f"clock payload too short: {payload.hex()}")
    month, day = payload[0], payload[1]
    year = int.from_bytes(payload[2:4], "little")
    hour, extra, minute, second = payload[4], payload[5], payload[6], payload[7]
    return datetime(year, month, day, hour, minute, second), extra


# --- commands and encryption, as the vendor app does them ------------------

def pad_payload(data: bytes, size: int = 16) -> bytes:
    """pad a command to the aes block size with 0xff, as the app's createPaddedPayload."""
    if len(data) > size:
        raise ValueError("payload longer than block")
    return bytes(data) + bytes([PAD_BYTE]) * (size - len(data))


def encrypt_command(data: bytes) -> bytes:
    from . import aes

    return aes.encrypt_block(pad_payload(data), AES_BASE_KEY)


def decrypt_block_at(payload: bytes, start: int) -> bytes:
    """decrypt the 16 bytes at ``start`` in place and return the whole payload.

    the app's decryptIfNeeded(data, startIndex, returnFullPacket=true): a record
    keeps its flags byte in the clear and carries the rest as one aes block.
    """
    from . import aes

    if len(payload) < start + 16:
        raise ProtocolError(f"payload too short to hold a block at {start}: {payload.hex()}")
    clear = aes.decrypt_block(payload[start : start + 16], AES_BASE_KEY)
    return bytes(payload[:start]) + clear + bytes(payload[start + 16 :])


def firmware_requires_aes(firmware_revision: str) -> bool:
    """the app's isFirmwareVersionAtLeast(firmware, "1.0.14") on the part before the slash.

    despite its name the app's function returns false when every component is
    equal, so encryption starts with the first firmware *newer* than 1.0.14.
    1.0.14 itself, the only version seen so far, talks in the clear.
    """
    head = firmware_revision.split("/")[0].strip()
    parts = [int(p) for p in head.split(".") if p.isdigit()]
    want = [int(p) for p in AES_MIN_FIRMWARE.split(".")]
    if not parts:
        return False
    parts += [0] * (len(want) - len(parts))
    return parts[: len(want)] > want


def parse_num_readings(payload: bytes) -> tuple[int, int, list[int]]:
    """BPM_NUM_READINGS_CHAR: number of users, memory per user, pending readings per user.

    "pending" because the app deletes every reading from the monitor once it
    has downloaded it; the display's own history is a separate memory.
    """
    if len(payload) < 4:
        raise ProtocolError(f"num-readings payload too short: {payload.hex()}")
    users, per_user = payload[0], payload[1]
    return users, per_user, list(payload[2 : 2 + max(users, 2)])


def build_reading_request(user: int, index: int, encrypted: bool) -> bytes:
    """ask for stored reading ``index`` of ``user`` (0 = a, 1 = b); answer comes on REQUESTED_READING_UUID."""
    plain = bytes([user & 0xFF, index & 0xFF])
    return encrypt_command(plain) if encrypted else plain


def build_set_time(when: datetime, encrypted: bool, flag: bool = True) -> bytes:
    """BPM_SET_TIME_CHAR payload as the app's setTime builds it.

    month, day, year lo, year hi, hour, minute, second, then a byte the app
    derives as ``not flag``. what the flag means is not known; the app passes
    it from its caller. note that the *read* layout of the same characteristic
    puts a constant byte between hour and minute, see parse_clock.
    """
    plain = bytes([
        when.month, when.day, when.year & 0xFF, when.year >> 8,
        when.hour, when.minute, when.second, 0 if flag else 1,
    ])
    return encrypt_command(plain) if encrypted else plain
