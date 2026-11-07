# OpenExactFit

read blood pressure measurements from a **braun exactfit 5 connect (bua6350)**
over bluetooth low energy, locally, without the healthy heart app and without a
google account. a python command-line tool (`exactfit`) and an android app,
both open source, mit licensed: https://github.com/cmile/OpenExactFit

## status

- live measurements: **working**, verified on linux / bluez on 2026-11-04. the
  monitor needs no pairing and no encryption; a finished measurement arrives as a
  notification in the standard bluetooth "blood pressure measurement" layout
  (0x2a35), minus a real mean arterial pressure (see `docs/protocol-notes.md`).
- stored history: **working**, verified 2026-11-04. `exactfit history` does the
  download the vendor app does, taken from the app's own code (see
  `docs/protocol-notes.md`), and unlike the app deletes nothing on the monitor.
- android app: **working**, verified 2026-11-05 and 2026-11-06 on android 15;
  the chart and the background service from 2026-11-06 are built but not yet
  tried on the phone. see below.
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
exactfit import bp.csv     # read a csv export back in (this tool's or the android app's), deduplicated
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
minimum android 13 (api 33). one screen: a sync button (and a scan on start,
switchable in the menu), the readings grouped by day with the monitor's
warnings in words and a coloured dot for the blood pressure band (the vendor
app's chart: normal below 135/85, elevated, high, crisis from 180/110), a user
A/B filter, names for the two users, details and
delete per reading (from the app's database only), a chart view with a trend
line per series and the 135/85 and 140/90 guideline lines over a week, month,
quarter or everything, an averages view with a month calendar and the means
for the last 7, 14 or 30 days or a tapped day (all, day and evening readings),
a printable report through android's print dialog (which also saves a pdf),
a csv import (an export of either client, through the system's file picker),
a csv export as a file through the share sheet, and a
background mode. english and german. it does what `exactfit sync` does, over
the android gatt api, and keeps its own sqlite file with the same columns as
the python tool. `app/src/main/java/de/cmile/openexactfit/`:

| file | what |
| --- | --- |
| `ble/Protocol.kt` | port of `exactfit/protocol.py`, aes through `javax.crypto` |
| `ble/ExactFitClient.kt` | scan, connect, stored readings, then live readings; gatt calls serialized over coroutines |
| `data/MeasurementStore.kt` | sqlite, same schema and deduplication as the python tool; signals changes to the screen |
| `data/Settings.kt` | user names, the scan-on-start switch, the background-sync switch |
| `Sync.kt`, `SyncLog.kt` | one sync run and the session log, shared by the screen and the service |
| `SyncService.kt`, `BootReceiver.kt` | the foreground service that waits for the monitor, and its restart after a reboot |
| `SyncViewModel.kt`, `MainActivity.kt` | state, permissions and bluetooth checks, the screen frame |
| `ui/ReadingsList.kt`, `ui/UserNamesDialog.kt` | the grouped list, the detail dialog, the names dialog |
| `ui/Trend.kt`, `ui/TrendChart.kt` | ranges, least-squares trend and axis maths (tested); the compose canvas chart |
| `ui/Category.kt`, `ui/Averages.kt`, `ui/AveragesView.kt` | the blood pressure bands, the means and the calendar view |
| `ui/Report.kt` | the html report for printing, one section per user |

**background sync** ("sync in the background" in the menu) starts a
foreground service with a persistent notification. it runs a filtered,
low-power bluetooth scan for the monitor, connects and syncs as soon as the
monitor is switched on, posts a notification for each new reading, and goes
back to scanning. the monitor advertises only while it is on, so the scan is
idle almost all the time. the service survives a reboot while the switch is
on, and stops from the menu or from the notification. while it runs, the
screen's own sync button is replaced by a note, because the monitor accepts
one connection at a time.

build with android studio, or `./gradlew :app:assembleDebug` with `JAVA_HOME`
pointing at a jdk 17 or newer. `./gradlew :app:testDebugUnitTest` runs the
protocol tests against the same captured bytes as the python tests, plus the
list and chart maths.

permissions: "nearby devices" (bluetooth scan and connect, declared
`neverForLocation`), and for background sync notifications; the
foreground-service and boot-completed permissions are granted on install. the
minimum of android 13 is deliberate: it is the first version with the
non-deprecated gatt api, so the code has one path instead of two, and no
version of the app ever needs the location permission that ble scanning
required before android 12.

### release build

the release build is signed with a key that is not in the repository. put a
`keystore.properties` next to this readme (it is git-ignored):

```properties
storeFile=~/.android/openexactfit-release.jks
storePassword=...
keyAlias=openexactfit
keyPassword=...
```

create the keystore once with
`keytool -genkeypair -keystore ~/.android/openexactfit-release.jks -alias openexactfit -keyalg RSA -keysize 4096 -validity 10000`
and keep both files safe: android installs an update only when it is signed
with the same key. then

```bash
./gradlew :app:assembleRelease     # -> app/build/outputs/apk/release/app-release.apk
```

bump `versionCode` and `versionName` in `app/build.gradle.kts` for every
release. without `keystore.properties` the apk is built unsigned. a release
build cannot be installed over a debug build of the app (different
signature): uninstalling the debug build deletes its database, so export the
csv or pull the database (`adb exec-out run-as de.cmile.openexactfit cat
databases/measurements.sqlite`) first.

## protocol

`docs/protocol-notes.md` holds everything known: captures from the monitor and
the command formats read from the vendor app. three characteristics are never
written by this tool: factory reset, delete reading, and the texas instruments
firmware-update service.
