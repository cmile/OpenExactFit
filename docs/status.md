# status and open work

as of 2026-11-05. the protocol details are in `protocol-notes.md`, usage in
the `README.md`; this file is the to-do list.

## what works

verified on the real monitor (firmware 1.0.14 / A01.003, no encryption):

- **python tool** (`exactfit`): `sync`, `listen`, `history`, `explore`,
  `list`, `export`. live readings, stored readings and the combined sync in
  one connection, over bluez without pairing. sqlite store with one row per
  reading, 23 tests.
- **android app** (`app/`, package `de.cmile.openexactfit`): first draft, one
  screen. sync button does what `exactfit sync` does, readings list, csv
  export through the share sheet, session log in the app and in logcat
  (`adb logcat -s OpenExactFit`). tested on a moto g05 with android 15, both
  pending readings arrived. 7 protocol tests.
- **deduplication** on monitor, user, timestamp and the three values, in
  both stores, with migration of older database files.
- **documentation** of the protocol, including corrections to the upstream
  ha-braun-ble notes and what was learned from the vendor app's code.

## facts that shape the design

- the monitor hands out only readings it still counts as "pending". the
  vendor app's delete command clears that counter, and the readings are then
  unreachable over bluetooth although the monitor's display still shows them.
  **sync with this project first, then with braun's app**, never the other
  way round. there is no way to fetch them back.
- a reading delivered live may or may not turn up as pending later; the
  database keeps one copy either way.
- nothing here ever writes to the delete, factory-reset or firmware-update
  characteristics, and that stays so.

## open work

### android app

- [ ] a proper reading list: per-day grouping, user A/B filter, the status
      flags spelled out (body movement, irregular pulse, cuff loose).
- [ ] csv export as a file (FileProvider) instead of shared text.
- [ ] automatic sync: start scanning when the app is opened, and a foreground
      service that catches readings while the app is in the background.
      the monitor advertises only while it is switched on, so background
      scanning with a service-uuid filter is the realistic route.
- [ ] user mapping: name the monitor's users A and B.
- [ ] delete single readings from the app's own database (not from the monitor).
- [ ] chart with trend: systolic, diastolic and pulse over time, selectable
      range (week, month, quarter, all), a moving average or regression line
      per series so the direction is visible at a glance, the 135/85 and
      140/90 guideline lines for reference, and user A/B separately. also
      wanted in the python tool as `exactfit plot` (matplotlib, optional
      dependency) for the same data.
- [ ] handle bluetooth being off and permissions being denied with a message
      instead of a silent "no monitor found".
- [ ] replace the deprecated api calls (`connectGatt` without transport,
      `characteristic.value`) once minSdk allows, or keep the version split
      and silence the warnings.
- [ ] release build: signing, versioning, maybe f-droid.
- [ ] a german translation of the ui strings (they are english today).

### python tool

- [ ] `exactfit set-time`: the write format is known from the vendor app
      (`docs/protocol-notes.md`), the flag byte's meaning is not; try it on
      the monitor and watch the clock characteristic.
- [ ] a `purge` command that sends the vendor app's delete, off by default and
      clearly named, for people who want the monitor emptied after a sync.
      decide first whether this project wants that at all.
- [ ] home assistant: either a small mqtt publisher, or findings fed back
      into ha-braun-ble.

### protocol questions

- [ ] what decides whether a live-delivered reading becomes pending again
      (observed both ways on 2026-11-04/05).
- [ ] clock byte 5 (`0x30`) and the set-time flag byte.
- [ ] what the monitor does at 60 pending readings per user (slot count from
      the num-readings read); probably a ring buffer, untested.
- [ ] whether the pairing-hash write is ever needed; it was not so far.
- [ ] the aes path is implemented from the vendor app's code and the fips-197
      test vector, but never run against a monitor with firmware newer than
      1.0.14. anyone with such a unit: `exactfit history -v` prints what the
      monitor answers.
- [ ] user B has never delivered a reading in testing.

### housekeeping

- [ ] report the clock-layout and map-field corrections upstream to
      cedhuf/ha-braun-ble, and offer the history protocol as a pull request.
- [ ] `~/Projects/healthyheart-apk` (the decompiled vendor app, ~350 mb) is
      scratch material outside the repo; delete it once nothing else is
      needed from it, or keep only the helper scripts.
- [ ] a remote for the repository; it is local only today.
