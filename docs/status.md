# status and open work

as of 2026-11-06. the protocol details are in `protocol-notes.md`, usage in
the `README.md`; this file is the to-do list.

## what works

verified on the real monitor (firmware 1.0.14 / A01.003, no encryption):

- **python tool** (`exactfit`): `sync`, `listen`, `history`, `explore`,
  `list`, `export`. live readings, stored readings and the combined sync in
  one connection, over bluez without pairing. sqlite store with one row per
  reading, 23 tests.
- **android app** (`app/`, package `de.cmile.openexactfit`): one screen.
  sync button does what `exactfit sync` does, session log in the app and in
  logcat (`adb logcat -s OpenExactFit`). tested on a moto g05 with android
  15 on 2026-11-05, both pending readings arrived. 7 protocol tests.
  since 2026-11-06, tested on the phone with a live reading: readings
  grouped by day with the monitor's warnings in words, user A/B filter
  chips, names for users A and B, tap a reading for details and to delete
  it from the app's database, csv export as a file through a fileprovider, a scan on app start
  (menu toggle), messages for bluetooth off / no permission / permission
  refused for good, ui strings in resources with a german translation. 3
  more unit tests.
  minimum android version raised from 7 to 13 (api 33) on 2026-11-06, so
  the gatt code has one path (the pre-33 callbacks, `characteristic.value`
  and the desugaring for java.time are gone) and no version ever asks for
  location.
  added later on 2026-11-06, **built and unit-tested but not yet tried on
  the phone**: a chart view (list/chart switch) with systolic, diastolic
  and pulse over a week, month, quarter or everything, a dashed
  least-squares trend per series with its change in the legend, the 135/85
  and 140/90 guideline lines, one chart per user when "all" is selected;
  a foreground service ("sync in the background" in the menu) that keeps a
  filtered low-power scan running, syncs whenever the monitor is switched
  on, posts a notification per new reading and comes back after a reboot;
  release signing from a git-ignored `keystore.properties`. 6 more unit
  tests (16 in all). also from 2026-11-06: a new launcher icon (heart with a
  pulse trace on teal, adaptive plus monochrome), and the vendor app's
  colour bands as a dot next to each value and in words in the details
  (`ui/Category.kt`: normal below 135/85, elevated from 135/85, high from
  160/100, crisis from 180/110, the higher value decides; 2 tests), and
  an averages view like the vendor app's: 7/14/30-day chips, a month
  calendar with a ring per day in the band colour of the day's mean, and
  the means for all, day (before 18:00) and evening readings of the chosen
  day or span (`ui/Averages.kt`, `ui/AveragesView.kt`, 4 tests). the
  session log card is hidden by default since 2026-11-06 evening (menu
  switch "show sync log"); logcat has the log either way. since
  2026-11-07 a "print" menu item renders the filtered readings as an html
  report (`ui/Report.kt`, one section per user with the mean, a table by
  day with band and notes) and hands it to android's print dialog, whose
  "save as pdf" doubles as the export for the doctor; 1 test.
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
- only one client can be connected to the monitor at a time. in the app the
  background service and the screen's own sync therefore never run together:
  while the service is on, the sync button is replaced by a note.

## open work

### android app

- [ ] try the 2026-11-06 additions on the phone: the chart with real data,
      the background service through a full cycle (switch on, lock the phone,
      measure, notification, list updated), the stop action in the
      notification, a reboot with the service switched on. the installed
      debug build must be replaced; the release apk has a different
      signature and cannot be installed over it without losing the database
      (export the csv or pull the database first).
- [ ] the permission-denied card is untested (the phone had already granted
      the permission); revoke it in the app settings and try. the same for
      the notification permission on android 13+.
- [x] a proper reading list: per-day grouping, user A/B filter, the status
      flags spelled out (body movement, irregular pulse, cuff loose).
- [x] csv export as a file (FileProvider) instead of shared text.
- [x] automatic sync: scanning when the app is opened (menu toggle "sync when
      opened", on by default) and a foreground service of the
      connected-device kind that scans in 25-minute rounds (android
      downgrades longer scans) with the service-uuid and name filters in
      low-power mode, then syncs and scans again. open: how much battery
      the filtered scan really costs on the moto g05 over a day; if too
      much, the pending-intent scan api (android 8+) would let the system
      wake the app instead.
- [x] user mapping: name the monitor's users A and B (menu, stored in
      shared preferences, shown in the list, the filter and the details).
- [x] delete single readings from the app's own database (not from the
      monitor), after a confirmation that says so.
- [x] chart with trend in the app: `ui/Trend.kt` (ranges, least-squares
      line, axis bounds, tested) and `ui/TrendChart.kt` (compose canvas, no
      chart library). two panels so pulse does not share the mmHg axis.
- [ ] the same chart in the python tool as `exactfit plot` (matplotlib,
      optional dependency) for the same data.
- [ ] averages: the vendor app's "evening" boundary is unknown; 18:00 is
      a guess, change `EVENING_HOUR` if it turns out otherwise. the vendor
      app may also use a different rule for mixed systolic/diastolic bands.
- [ ] print: the report prints everything the current filter shows; a
      range picker (last 30 days, a month) in front of the print dialog
      would help once the database is long.
- [ ] the colour bands in the chart as faint background bands, if the
      guideline lines turn out to be too little; and in the python `list`
      output as a column.
- [x] handle bluetooth being off and permissions being denied with a message
      instead of a silent "no monitor found" (turn-on request, ask again, or
      a jump to the app settings when refused for good).
- [x] replace the deprecated api calls: done by raising minSdk to 33 and
      deleting the old paths. android 7 to 12 are out; revisit only if
      someone with such a phone asks, the pre-33 code is in the git history
      (commit before 2026-11-06 evening).
- [x] release build: signing from `keystore.properties` (see the readme),
      `versionCode`/`versionName` in `app/build.gradle.kts`, r8 on.
- [ ] f-droid: needs a public remote first, then a metadata file; the app
      has no proprietary dependencies, so it should qualify. a reproducible
      build would let f-droid ship the apk under this project's own key.
- [x] a german translation of the ui strings (`values-de`); keep it in step
      when strings change.

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
- [ ] back up `~/.android/openexactfit-release.jks` and `keystore.properties`
      somewhere safe; without them no update can be installed over a
      release build.
