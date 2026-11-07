"""parser tests against the bytes captured in cedhuf/ha-braun-ble docs/bua6350-support.md."""

from datetime import datetime

import pytest

from exactfit import protocol
from exactfit.store import Store

CAPTURE_A = bytes.fromhex("1e 7400 4900 5a00 ea07 0a 0b 0f 0f 00 5b00 00 0100".replace(" ", ""))
CAPTURE_B = bytes.fromhex("1e 8500 4600 5a00 ea07 0a 0b 0f 13 00 5a00 01 0000".replace(" ", ""))


def test_capture_user_a_with_body_movement():
    m = protocol.parse_measurement(CAPTURE_A)
    assert (m.systolic, m.diastolic, m.pulse) == (116, 73, 91)
    assert m.user == "A"
    assert m.timestamp == datetime(2026, 10, 11, 15, 15, 0)
    assert m.flags == ["body_movement"]


def test_capture_user_b_clean():
    m = protocol.parse_measurement(CAPTURE_B)
    assert (m.systolic, m.diastolic, m.pulse) == (133, 70, 90)
    assert m.user == "B"
    assert m.timestamp == datetime(2026, 10, 11, 15, 19, 0)
    assert m.flags == []
    assert "133/70 mmHg" in str(m)


# records in the exact layout our own monitor (firmware 1.0.14) sends, with the pressure,
# pulse and time bytes changed so that nobody's real readings sit in a public repository
OWN_1 = bytes.fromhex("1e7a004e005a00ea07010e0717004600000000")
OWN_2 = bytes.fromhex("1e800050005a00ea07010e071e004800000000")


def test_own_device_captures():
    a = protocol.parse_measurement(OWN_1)
    b = protocol.parse_measurement(OWN_2)
    assert (a.systolic, a.diastolic, a.pulse, a.user) == (122, 78, 70, "A")
    assert a.timestamp == datetime(2026, 1, 14, 7, 23, 0)
    assert (b.systolic, b.diastolic, b.pulse, b.user) == (128, 80, 72, "A")
    assert b.timestamp == datetime(2026, 1, 14, 7, 30, 0)
    assert a.flags == b.flags == []


STORED_1 = bytes.fromhex("1e76004c000000ea07010e0723004400000000")


def test_map_field_is_90_live_and_0_from_memory():
    for raw in (CAPTURE_A, CAPTURE_B, OWN_1, OWN_2):
        assert protocol.parse_measurement(raw).map_field == 90
    assert protocol.parse_measurement(STORED_1).map_field == 0


def test_stored_record_from_own_device():
    m = protocol.parse_measurement(STORED_1)
    assert (m.systolic, m.diastolic, m.pulse, m.user) == (118, 76, 68, "A")
    assert m.timestamp == datetime(2026, 1, 14, 7, 35, 0)


def test_sfloat_exponent_and_sign():
    assert protocol.sfloat(bytes.fromhex("7400")) == 116
    assert protocol.sfloat((0xF000 | 0x0C8).to_bytes(2, "little")) == pytest.approx(20.0)  # 200 * 10^-1
    assert protocol.sfloat((0x0FFF).to_bytes(2, "little")) == -1


def test_minimal_payload_without_optional_fields():
    m = protocol.parse_measurement(bytes.fromhex("00 7400 4900 5a00".replace(" ", "")))
    assert m.pulse is None and m.user is None and m.timestamp is None


def test_kpa_is_converted():
    # 15.5 kpa = 116 mmhg; sfloat 155 * 10^-1
    kpa = (0xF000 | 155).to_bytes(2, "little")
    m = protocol.parse_measurement(b"\x01" + kpa + kpa + kpa)
    assert m.systolic == 116


def test_too_short_payload_raises():
    with pytest.raises(protocol.ProtocolError):
        protocol.parse_measurement(b"\x1e\x74")


def test_clock_upstream_capture():
    # upstream doc read this as 15:48:06; with the constant 0x30 skipped it is 15:06:47
    when, extra = protocol.parse_clock(bytes.fromhex("0a0bea070f30062f"))
    assert when == datetime(2026, 10, 11, 15, 6, 47)
    assert extra == 0x30


def test_clock_second_device_matches_display():
    # read on 2026-11-04 while the display showed 22:25 and the host clock said 22:25:33+
    when, extra = protocol.parse_clock(bytes.fromhex("0b04ea071630190a"))
    assert when == datetime(2026, 11, 4, 22, 25, 10)
    assert extra == 0x30


def test_store_migrates_old_map_column(tmp_path):
    import sqlite3
    path = tmp_path / "old.sqlite"
    conn = sqlite3.connect(path)
    conn.executescript(Store.__init__.__globals__["SCHEMA"].replace("map_field integer", "map integer"))
    conn.execute("insert into measurements (device, received_at, systolic, diastolic, map, raw_hex) "
                 "values ('x', 'now', 1, 2, 90, 'ff')")
    conn.commit(); conn.close()
    rows = Store(path).all()
    assert rows[0]["map_field"] == 90


def test_store_deduplicates(tmp_path):
    store = Store(tmp_path / "t.sqlite")
    m = protocol.parse_measurement(CAPTURE_A)
    assert store.add(m, "aa:bb") is True
    assert store.add(m, "aa:bb") is False
    rows = store.all()
    assert len(rows) == 1 and rows[0]["systolic"] == 116 and rows[0]["flags"] == "body_movement"
    assert store.all(user="B") == []


def test_store_treats_live_and_stored_copy_as_one(tmp_path):
    # the same reading as the monitor notified it live (bytes 5..6 = 90) and as
    # it later came out of storage (bytes 5..6 = 0)
    live = protocol.parse_measurement(bytes.fromhex("1e840056005a00ea07010f1509004e00000000"))
    stored = protocol.parse_measurement(bytes.fromhex("1e840056000000ea07010f1509004e00000000"))
    store = Store(tmp_path / "t.sqlite")
    assert store.add(live, "aa:bb") is True
    assert store.add(stored, "aa:bb") is False
    assert store.add(stored, "cc:dd") is True  # another monitor is another reading


def test_store_migrates_raw_hex_key(tmp_path):
    import sqlite3
    path = tmp_path / "old.sqlite"
    conn = sqlite3.connect(path)
    conn.executescript("""
        create table measurements (
            id integer primary key, device text, user text, measured_at text,
            received_at text not null, systolic integer not null, diastolic integer not null,
            map_field integer not null, pulse integer, flags text, raw_hex text not null,
            unique (device, raw_hex));
        insert into measurements values
            (1, 'x', 'A', '2026-01-15 21:09:00', 'r1', 132, 86, 90, 78, '', '1e840056005a00'),
            (2, 'x', 'A', '2026-01-15 21:09:00', 'r2', 132, 86, 0, 78, '', '1e840056000000'),
            (3, 'x', 'A', '2026-01-15 21:10:00', 'r3', 120, 80, 0, 70, '', '00');
    """)
    conn.commit(); conn.close()
    store = Store(path)
    rows = store.all()
    assert [r["received_at"] for r in rows] == ["r1", "r3"]  # first copy wins
    assert store.conn.execute("pragma user_version").fetchone()[0] == 2
    assert store.add(protocol.parse_measurement(bytes.fromhex("1e840056000000ea07010f1509004e00000000")), "x") is False
    Store(path)  # migrating twice is a no-op


# --- aes and commands --------------------------------------------------------

from exactfit import aes


def test_aes_fips197_vector():
    key = bytes.fromhex("2b7e151628aed2a6abf7158809cf4f3c")
    plain = bytes.fromhex("3243f6a8885a308d313198a2e0370734")
    cipher = bytes.fromhex("3925841d02dc09fbdc118597196a0b32")
    assert aes.encrypt_block(plain, key) == cipher
    assert aes.decrypt_block(cipher, key) == plain


def test_base_key_is_the_fips_example_key():
    assert protocol.AES_BASE_KEY == bytes.fromhex("2b7e151628aed2a6abf7158809cf4f3c")


def test_reading_request_plain_and_encrypted():
    assert protocol.build_reading_request(1, 7, encrypted=False) == b"\x01\x07"
    enc = protocol.build_reading_request(1, 7, encrypted=True)
    assert len(enc) == 16
    assert aes.decrypt_block(enc, protocol.AES_BASE_KEY) == b"\x01\x07" + b"\xff" * 14


def test_decrypt_block_at_keeps_flags_byte():
    body = bytes(range(1, 17))
    packet = b"\x1e" + aes.encrypt_block(body, protocol.AES_BASE_KEY) + b"\x00\x00"
    assert protocol.decrypt_block_at(packet, 1) == b"\x1e" + body + b"\x00\x00"


def test_num_readings_from_own_device():
    assert protocol.parse_num_readings(bytes.fromhex("023c0100")) == (2, 60, [1, 0])


def test_firmware_threshold():
    assert not protocol.firmware_requires_aes("1.0.14/A01.003")  # equal: the app says no
    assert protocol.firmware_requires_aes("1.0.15/A01.004")
    assert protocol.firmware_requires_aes("1.1.0")
    assert not protocol.firmware_requires_aes("1.0.13/A01.002")
    assert not protocol.firmware_requires_aes("")


def test_set_time_layout():
    when = datetime(2026, 11, 4, 22, 25, 10)
    assert protocol.build_set_time(when, encrypted=False) == bytes.fromhex("0b04ea0716190a00")
    assert protocol.build_set_time(when, encrypted=False, flag=False)[-1] == 1


def test_month_high_bit_means_clock_unset():
    raw = bytearray(OWN_2)
    raw[9] |= 0x80
    m = protocol.parse_measurement(bytes(raw))
    assert m.clock_was_unset and m.timestamp.month == 1
