"""sqlite storage for measurements."""

from __future__ import annotations

import csv
import sqlite3
import sys
from datetime import datetime
from pathlib import Path
from typing import Iterable

from .protocol import Measurement, ProtocolError, parse_measurement

SCHEMA = """
create table if not exists measurements (
    id integer primary key,
    device text,
    user text,
    measured_at text,
    received_at text not null,
    systolic integer not null,
    diastolic integer not null,
    map_field integer not null,
    pulse integer,
    flags text,
    raw_hex text not null,
    note text
);
-- one row per reading. the monitor sends the same reading twice with different
-- raw bytes (live and from storage, bytes 5..6 differ), so raw_hex cannot be the key.
create unique index if not exists measurements_reading on measurements (
    device, coalesce(user, ''), coalesce(measured_at, ''),
    systolic, diastolic, coalesce(pulse, -1)
);
pragma user_version = 3;
"""

COLUMNS = ("id", "device", "user", "measured_at", "received_at",
           "systolic", "diastolic", "map_field", "pulse", "flags", "raw_hex", "note")

# the dedupe key as a where clause, for finding the stored copy of a reading
_SAME_READING = ("device = ? and coalesce(user, '') = ? and coalesce(measured_at, '') = ? "
                 "and systolic = ? and diastolic = ? and coalesce(pulse, -1) = ?")


class Store:
    def __init__(self, path: Path) -> None:
        self.conn = sqlite3.connect(path)
        self.conn.row_factory = sqlite3.Row
        self._migrate()
        self.conn.executescript(SCHEMA)

    def _migrate(self) -> None:
        cols = {row[1] for row in self.conn.execute("pragma table_info(measurements)")}
        if not cols:
            return  # fresh file, SCHEMA creates everything
        if "map" in cols:  # schema before 2026-11-04, when the field was believed to be the map
            with self.conn:
                self.conn.execute("alter table measurements rename column map to map_field")
        version = self.conn.execute("pragma user_version").fetchone()[0]
        if version < 2:
            # schema before 2026-11-05 keyed rows on (device, raw_hex) as a table
            # constraint. sqlite cannot drop that, so rebuild the table; the new
            # index then also collapses readings that were stored twice.
            self.conn.executescript(
                "begin;"
                "alter table measurements rename to measurements_old;"
                + SCHEMA.replace("pragma user_version = 3;", "")
                + "insert or ignore into measurements (device, user, measured_at, received_at,"
                " systolic, diastolic, map_field, pulse, flags, raw_hex)"
                " select device, user, measured_at, received_at, systolic, diastolic,"
                " map_field, pulse, flags, raw_hex from measurements_old order by id;"
                "drop table measurements_old;"
                "commit;"
            )
        cols = {row[1] for row in self.conn.execute("pragma table_info(measurements)")}
        if "note" not in cols:  # schema before 2026-11-13 (version 2) had no owner's note
            with self.conn:
                self.conn.execute("alter table measurements add column note text")

    def add(self, m: Measurement, device: str, received_at: str | None = None,
            note: str | None = None) -> bool:
        """store a measurement; returns False if this reading was already stored.

        ``received_at`` defaults to now; an import passes the original time and
        the owner's note along.
        """
        try:
            with self.conn:
                self.conn.execute(
                    "insert into measurements (device, user, measured_at, received_at, "
                    "systolic, diastolic, map_field, pulse, flags, raw_hex, note) "
                    "values (?,?,?,?,?,?,?,?,?,?,?)",
                    (
                        device,
                        m.user,
                        m.timestamp.isoformat(sep=" ") if m.timestamp else None,
                        received_at or datetime.now().isoformat(sep=" ", timespec="seconds"),
                        m.systolic,
                        m.diastolic,
                        m.map_field,
                        m.pulse,
                        ",".join(m.flags),
                        m.raw.hex(),
                        note or None,
                    ),
                )
        except sqlite3.IntegrityError:
            return False
        return True

    def set_note(self, reading_id: int, note: str | None) -> bool:
        """the owner's note on a reading; empty or None clears it. False if there is no such id."""
        with self.conn:
            cur = self.conn.execute("update measurements set note = ? where id = ?",
                                    ((note or "").strip() or None, reading_id))
        return cur.rowcount == 1

    def _fill_note(self, m: Measurement, device: str, note: str) -> None:
        """give the stored copy of ``m`` this note, unless it already has one."""
        with self.conn:
            self.conn.execute(
                f"update measurements set note = ? where {_SAME_READING} and coalesce(note, '') = ''",
                (note, device, m.user or "", m.timestamp.isoformat(sep=" ") if m.timestamp else "",
                 m.systolic, m.diastolic, m.pulse if m.pulse is not None else -1),
            )

    def all(self, user: str | None = None) -> list[sqlite3.Row]:
        sql = "select * from measurements"
        args: tuple = ()
        if user:
            sql += " where user = ?"
            args = (user,)
        sql += " order by coalesce(measured_at, received_at), id"
        return list(self.conn.execute(sql, args))

    def export_csv(self, rows: Iterable[sqlite3.Row], out=sys.stdout) -> None:
        writer = csv.writer(out)
        writer.writerow(COLUMNS)
        for row in rows:
            writer.writerow([row[c] for c in COLUMNS])

    def import_csv(self, lines: Iterable[str]) -> tuple[int, int, int]:
        """read an export of either client back in.

        every reading is re-parsed from its ``raw_hex``, so the android export
        (which lacks ``map_field``) and this tool's own both work, and the usual
        deduplication applies. ``received_at``, ``device`` and ``note`` are taken
        from the file; a reading that is already stored without a note gets the
        file's. returns (new, already stored, unreadable rows).
        """
        new = dup = bad = 0
        for row in csv.DictReader(lines):
            try:
                m = parse_measurement(bytes.fromhex(row["raw_hex"]))
            except (KeyError, TypeError, ValueError, ProtocolError):
                bad += 1
                continue
            device = row.get("device") or "?"
            note = (row.get("note") or "").strip()
            if self.add(m, device, row.get("received_at") or None, note):
                new += 1
            else:
                dup += 1
                if note:
                    self._fill_note(m, device, note)
        return new, dup, bad
