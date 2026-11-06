# CLAUDE.md

open-source client for the braun exactfit 5 connect (bua6350) blood pressure
monitor: a python cli (`exactfit/`) and an android app (`app/`). it exists
because the vendor app forces a google login. owner: smile, who writes
german; code, comments, commit messages and docs are english, lowercase prose.

## read first

- `README.md` — usage, install, the sync order next to the vendor app.
- `docs/status.md` — what works, what is open. update it when something changes.
- `docs/protocol-notes.md` — every protocol fact, each tagged with where it
  came from (`device.` for our own captures, the vendor app section for what
  its code does). add new observations there with a date.

do not redo the apk analysis: the decompiled vendor app (healthy heart 2.4.4)
sits outside the repo in `~/Projects/healthyheart-apk` (dexdump output,
helper scripts), and everything learned from it is in the protocol notes.

## hard rules

- **never write** to the delete-reading characteristic (`2df251d7-…`), the
  factory-reset characteristic (`598040af-…`) or the ti oad firmware service
  (`f000ffc0-…`). the reading request (`d4a0c8c3-…`) and, once implemented,
  set-time are the only writes this project makes.
- commit only when smile asks. ide and build output (`.idea/`, `.gradle/`,
  `build/`, `local.properties`, `build.log`) stay out of the repo.
- both stores (python `exactfit/store.py`, android `data/MeasurementStore.kt`)
  share one schema and one dedupe key; change them together and migrate.

## commands

```bash
# python
. .venv/bin/activate            # venv exists; pip install -e '.[dev]' if not
pytest                          # 23 tests, no hardware needed
exactfit sync -v                # the real thing; switch the monitor on when asked
exactfit explore                # gatt dump, read-only

# android (jdk 17+, sdk in ~/Android/Sdk via local.properties)
./gradlew --console=plain assembleDebug testDebugUnitTest
~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
~/Android/Sdk/platform-tools/adb logcat -s OpenExactFit     # the app's session log
```

the phone (moto g05, android 15) is usually reachable over wifi adb. the
app's database can be pulled with
`adb exec-out run-as de.cmile.openexactfit cat databases/measurements.sqlite`.

## the monitor in one paragraph

ble, no pairing, no bonding. it advertises only while switched on and drops
the connection about a minute after a measurement. live readings arrive as
notifications in the standard 0x2a35 layout; stored readings are requested
one by one by `[user, index]` for `index < pending`, where `pending` comes
from the num-readings read. only readings still counted as pending are
obtainable; the vendor app's delete clears that counter for good. firmware
newer than 1.0.14 wraps commands and records in aes-128 under a fixed key,
detected by the length of the num-readings answer; smile's unit (1.0.14)
speaks plaintext, so the aes path is untested on hardware. only one client
can be connected at a time, so close the phone apps before testing from linux.
