# exactfit-ble

read blood pressure measurements from a **braun exactfit 5 connect (bua6350)**
over bluetooth low energy, locally, without the healthy heart app and without a
google account.

## status

- live measurements: **working**, verified on linux / bluez on 2026-11-04. the
  monitor needs no pairing and no encryption; a finished measurement arrives as a
  notification in the standard bluetooth "blood pressure measurement" layout
  (0x2a35), minus a real mean arterial pressure (see `docs/protocol-notes.md`).
- stored history: **working**, verified 2026-11-04. `exactfit history` does the
  download the vendor app does, taken from the app's own code (see
  `docs/protocol-notes.md`), and unlike the app deletes nothing on the monitor.
  readings already delivered live are not offered again by the monitor.
- setting the clock: write format known from the app, not implemented yet.
- encryption: newer firmware than 1.0.14 wraps commands and records in aes-128
  under a fixed key; implemented, auto-detected, unverified against such firmware.

protocol knowledge comes from [cedhuf/ha-braun-ble](https://github.com/cedhuf/ha-braun-ble)
(`docs/bua6350-support.md`), mit licensed.

## install

```bash
python3 -m venv .venv && . .venv/bin/activate
pip install -e '.[dev]'
pytest
```

linux needs bluez; the user running it needs access to the adapter (usually
membership in the `bluetooth` group, or run via `sudo -E`).

## use

```bash
exactfit sync              # the everyday command: fetch stored readings, then stay for live ones
exactfit sync --forever    # daemon style: scan again after every session
exactfit listen            # live readings only
exactfit history           # stored readings only (deletes nothing on the monitor)
exactfit list              # stored measurements
exactfit list --user A
exactfit export > bp.csv   # csv on stdout
exactfit explore           # dump gatt table and readable characteristics (no writes)
```

the database lives in `~/.local/share/exactfit/measurements.sqlite` unless
`--db` says otherwise. duplicate notifications (same device, same raw bytes) are
stored once.

workflow: start `exactfit sync`, switch the monitor on and measure. the monitor
advertises as soon as it is on; `sync` first collects what it stored while
nothing was listening, then prints each new reading as it happens. the monitor
drops the connection about a minute after a measurement when it switches
itself off, which ends the session. readings the monitor has delivered live
are not offered as stored readings again, so nothing is ever fetched twice.

## android app

`app/` is the phone app, kotlin and jetpack compose, package `de.cmile.openexactfit`,
minimum android 7 (api 24). first draft: one screen with a sync button, the
readings list and a csv export through the share sheet. it does what
`exactfit sync` does, over the android gatt api, and keeps its own sqlite file
with the same columns as the python tool. `app/src/main/java/de/cmile/openexactfit/`:

| file | what |
| --- | --- |
| `ble/Protocol.kt` | port of `exactfit/protocol.py`, aes through `javax.crypto` |
| `ble/ExactFitClient.kt` | scan, connect, stored readings, then live readings; gatt calls serialized over coroutines |
| `data/MeasurementStore.kt` | sqlite, deduplicated on monitor address plus raw bytes |
| `SyncViewModel.kt`, `MainActivity.kt` | state and the compose screen |

build with android studio, or `./gradlew :app:assembleDebug` with `JAVA_HOME`
pointing at a jdk 17 or newer. `./gradlew :app:testDebugUnitTest` runs the
protocol tests against the same captured bytes as the python tests.

permissions: android 12 and newer ask for "nearby devices" (bluetooth scan and
connect, declared `neverForLocation`); older versions need fine location, as
every ble scan does there.

## protocol

`docs/protocol-notes.md` holds everything known: captures from the monitor and
the command formats read from the vendor app. three characteristics are never
written by this tool: factory reset, delete reading, and the texas instruments
firmware-update service.
