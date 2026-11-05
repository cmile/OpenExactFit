# protocol notes

observations from this project's own device, on top of the upstream document
([cedhuf/ha-braun-ble, docs/bua6350-support.md](https://github.com/cedhuf/ha-braun-ble/blob/main/docs/bua6350-support.md)).
evidence levels as there: **device** (observed), **inferred**, **unconfirmed**.

## device under test

firmware `1.0.14/A01.003`, hardware `1.0.0/A01.000`, same as upstream.
pnp id `01 0d00 0000 1001`: vendor id source bluetooth sig, vendor `0x000d`
= texas instruments. the ieee 11073 regulatory field reads `fe00` + the ascii
string `experimental`, the placeholder from ti's ble-stack sample projects.
**inferred**: firmware built on the ti ble stack (the oad service points the
same way).

## connection

**device.** `bleak` on linux / bluez connects without any pairing or bonding.
`bluetoothctl pair` is not needed and fails, which is harmless.

## measurement record `2db34480-…`

**device.** two live readings on 2026-11-04, user a, printed as 122/78 and 128/80
on the display:

```text
1e 7a00 4e00 5a00 ea07 01 0e 07 17 00 4600 00 0000   122/78, pulse 70, 07:23, user a
1e 8000 5000 5a00 ea07 01 0e 07 1e 00 4800 00 0000   128/80, pulse 72, 07:30, user a
```

the 0x2a35 layout holds as upstream describes it, with two corrections:

- **bytes 5..6 are not the mean arterial pressure.** all four live records
  (upstream's 116/73 and 133/70, these 122/78 and 128/80) carry `5a00` = 90
  there, and a record fetched from memory carries `0000`. a real map would be
  87, 91, 93 and 96. the parser keeps the field as `map_field` and nothing
  displays it.
- **the seconds byte is always `00`**: timestamps have minute precision.

both readings arrived while connected, about 40 s after the measurement
finished on the display. nothing was sent on connect for the earlier reading.
**unconfirmed**: whether a reading taken while no client is connected is pushed
on the next connection. to test: measure with nothing listening, then run
`exactfit listen`.

## clock characteristic `6114ac81-…`

**device.** read `0b 04 ea07 16 30 19 0a` on 2026-11-04 at about 22:25:40 host
time while the display showed the correct time, 22:25.

layout, corrected against upstream:

| byte | content |
| --- | --- |
| 0 | month |
| 1 | day |
| 2..3 | year, little-endian |
| 4 | hour |
| 5 | constant `0x30` on both devices seen; meaning unknown |
| 6 | minute |
| 7 | second |

upstream read bytes 5..6 as minute and second. its capture `0f 30 06 2f` was
then "15:48:06"; with the constant skipped it is 15:06:47, which still fits its
session (measurements at 15:15 and 15:19 came later). the second device decides
it: `16 30 19 0a` can only be 22:25:10, not 22:48:25.

**unconfirmed**: whether writing the same 8 bytes sets the clock, and whether
byte 5 has to be echoed back as `0x30`. not tried yet.

## `25775d68-…`

**device.** `02 3c 01 00` here, `02 3c 00 06` upstream. `02` users, `3c` = 60 =
the advertised memory per user. bytes 2..3 differ between the two devices.

**rejected**: stored-measurement count per user. the display showed 9 readings
for user a and 0 for user b while the characteristic read `01 00`.
upstream's `00 06` came from a device used by both users.
still open: selected user, unsynced count, settings.

## stored history

**device.** a reading taken while no client is connected is **not** pushed on
the next connection (tested 2026-11-04: measure, let the monitor switch off,
switch on again with `exactfit listen` running, nothing arrives). it has to be
requested; the request protocol is in the vendor-app section below and
**verified on the device on 2026-11-04 23:08**:

```text
read  25775d68  -> 02 3c 01 00            2 users, 60 slots, 1 pending for a, 0 for b
write d4a0c8c3  <- 00 00                  user a, index 0 (plain: firmware 1.0.14)
notif 991dee0f  -> 1e 7600 4c00 0000 ea07 01 0e 07 23 00 4400 00 0000
                                          118/76, pulse 68, 07:35, user a
```

the answer arrived within the 5 s window, in the live-record layout, with
`0000` where live records carry `5a00`. no pairing write was needed first.

**device.** a requested reading stays pending: the same `02 3c 01 00` and the
same record came back on the next connection (2026-11-05 00:07). only the
app's delete command clears it, so this tool relies on its own deduplication.

**device.** two readings taken earlier the same evening *while a client was
subscribed to the live characteristic* were not pending, which suggested the
monitor marks a reading as delivered once it has notified it live. that does
not hold in general: the reading of 2026-11-05 00:08 was notified live to
`sync` and came back as pending (`02 3c 02 00`) on the next connection, seven
hours later, with bytes 5..6 now `0000`. so the same reading can arrive twice
with different raw bytes, and both tools key their deduplication on monitor,
user, timestamp and the three values rather than on the raw record. what
decides whether a live reading becomes pending is still unknown.

## battery `2a19`

**device.** reads `00` here too; upstream saw notifications of `82` later.
not a percentage.

## the vendor app's view (healthy heart 2.4.4 for android, `kaz.bpmandroid`)

read from the app's own code (`kaz.bpmandroid.utils.BLEUtils`,
`kaz.bpmandroid.ble.BluetoothManager`, `kaz.bpmandroid.ble.aes.BUA6350.*`),
disassembled with the android sdk's `dexdump`. evidence level for everything in
this section: **app**, meaning this is what the app sends and expects, verified
on the monitor only where a later section says so.

### characteristic names

| uuid | app constant | props | notes |
| --- | --- | --- | --- |
| `56484aae-…` | `UUID_BLOOD_PRESSURE_SERVICE` | | |
| `2db34480-…` | `PRESSURE_MEASUREMENT_CHAR` | notify | live reading |
| `77ab1c51-…` | `PRESSURE_INTERMEDIATE_PRESSURE_CHAR` | notify | cuff pressure during inflation |
| `c3a2ed78-…` | `PRESSURE_FEATURE_CHAR` | read | |
| `bb647f01-…` | `UUID_KAZ_BPM_SERVICE` | | |
| `6114ac81-…` | `BPM_SET_TIME_CHAR` | read, write | |
| `25775d68-…` | `BPM_NUM_READINGS_CHAR` | read | |
| `d4a0c8c3-…` | `BPM_READING_REQUEST_CHAR` | write | |
| `991dee0f-…` | `BPM_REQUESTED_READING_CHAR` | notify | answer to a request |
| `bc305904-…` | `BPM_ERROR_CHAR` | notify | byte 0 is an error code; the app reacts to its `ERROR_TIME_OUT` |
| `fce1be76-…` | `BPM_USER_NAME_CHAR` | write, notify | |
| `53ebc2ce-…` | `BPM_MEASUREMENT_CONTROL_CHAR` | write | one byte |
| `deffe5de-…` | `BPM_PAIRING_CHAR` | write | |
| `2df251d7-…` | `BPM_DELETE_READING_CHAR` | write | **destructive** |
| `598040af-…` | `BPM_FACTORY_RESET_CHAR` | write | **destructive** |
| `f000ffc0-…` | `UUID_UPDATE_SERVICE` | | ti oad, firmware update |

the app also knows `220acbcf-…` (`BPM_STORED_RECORD_INDEX`), an authentication
service `abe0f4c5-…` with challenge/response and public-key characteristics,
and `01766d60-…` / `6be10cf5-…`. none of these exist on the bua6350; they
belong to the "4075" model (exactfit 2 connect), which has a real key exchange.

### encryption

- aes-128, one block, ecb, no iv, no derivation. key `2b7e151628aed2a6abf7158809cf4f3c`,
  which is the example key of fips-197 appendix b.
- commands shorter than 16 bytes are padded with `0xff` to 16 and encrypted whole.
- a record keeps byte 0 (the flags) in the clear; bytes 1..16 are one encrypted block.
- the `BPM_NUM_READINGS_CHAR` answer is decrypted from byte 0.
- switched on when the device-info firmware string, cut at the `/`, compares
  *greater* than `1.0.14` component by component (the app's
  `isFirmwareVersionAtLeast` returns false on equality). before that, the scan
  callback sets the same flag from byte 51 of the raw advertisement record being
  `0x01`; device info overrides it after connecting.
- firmware `1.0.14/A01.003`, the only one seen, therefore talks in the clear,
  which matches every byte captured so far. `exactfit history` detects the
  mode from the length of the num-readings answer anyway.

### stored readings

`BPM_NUM_READINGS_CHAR` read: `[users, slots per user, pending user a, pending user b]`.
"pending" because after downloading the app deletes what it fetched, so the
count is "not yet collected by the app", not the display history.
`02 3c 01 00` on 2026-11-04 was one uncollected reading for user a.

download, per user, index from 0 to pending-1:

1. write `[user, index]` to `BPM_READING_REQUEST_CHAR` (padded and encrypted when
   encryption is on).
2. the monitor notifies `BPM_REQUESTED_READING_CHAR` with a record in the same
   layout as a live reading. the app parses systolic/diastolic/map as plain
   little-endian u16 (equivalent to sfloat for these values) and treats bit 7 of
   the month byte as "the clock was not set when this was measured".
3. the app requests the next index from inside the notification handler.
4. when the last one is in, the app writes `[user, 0, count]` to
   `BPM_DELETE_READING_CHAR`; the monitor then notifies on that characteristic
   and the app commits the batch. **this tool never sends the delete.**

### other commands

- set time, 8 bytes to `BPM_SET_TIME_CHAR`: `month, day, year lo, year hi, hour,
  minute, second, flag`, `flag` being the inverse of a boolean the caller passes.
  this is the *write* layout; the read layout seen on two devices has a constant
  `0x30` between hour and minute. the app's own reader takes byte 5 as the minute
  and byte 0 bit 7 as "clock is set", so either the app misreads it or the
  monitor answers in a different layout than it accepts. untested.
- pairing, 5 bytes to `BPM_PAIRING_CHAR`: `user, hash >> 24, hash >> 16, hash >> 8, hash`,
  the hash being a per-user id the app keeps. the write triggers the app's
  num-readings read; whether the monitor needs it before answering requests is
  unknown. un-pairing writes the same.
- user name, `max(len, 17) + 3` bytes to `BPM_USER_NAME_CHAR`: `user, write flag,
  name bytes…, 0`; padded to 18 and encrypted when encryption is on.
- measurement control, 1 byte to `BPM_MEASUREMENT_CONTROL_CHAR`; the app writes
  `1` after an error-char timeout.
