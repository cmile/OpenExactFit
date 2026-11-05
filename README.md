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
- android app: **first draft working**, verified 2026-11-05 on android 15. see
  below.
- setting the clock: write format known from the app, not implemented yet.
- encryption: newer firmware than 1.0.14 wraps commands and records in aes-128
  under a fixed key; implemented, auto-detected, unverified against such firmware.

`docs/status.md` lists what is done and what is still open.

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
`--db` says otherwise. a reading is stored once per monitor, keyed on user,
timestamp, systolic, diastolic and pulse: the monitor can deliver the same
reading live and later again from storage, with slightly different raw bytes.

workflow: start `exactfit sync`, switch the monitor on and measure. the monitor
advertises as soon as it is on; `sync` first collects what it stored while
nothing was listening, then prints each new reading as it happens. the monitor
drops the connection about a minute after a measurement when it switches
itself off, which ends the session. readings the monitor has delivered live
may or may not be offered as stored readings again; the database keeps one
copy either way, and nothing is ever deleted on the monitor.

using braun's app as well: let this tool (or the android app below) sync
first, then the vendor app. the vendor app deletes what it fetched from the
monitor's bluetooth side, and those readings cannot be requested again by
anyone, although the monitor's own display still shows them.

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
| `data/MeasurementStore.kt` | sqlite, same schema and deduplication as the python tool |
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
