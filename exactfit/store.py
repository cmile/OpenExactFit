"""sqlite storage for measurements."""

from __future__ import annotations

import csv
import sqlite3
import sys
from datetime import datetime
from pathlib import Path
from typing import Iterable

from .protocol import Measurement

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
    unique (device, raw_hex)
);
"""

COLUMNS = ("id", "device", "user", "measured_at", "received_at",
           "systolic", "diastolic", "map_field", "pulse", "flags", "raw_hex")


class Store:
    def __init__(self, path: Path) -> None:
        self.conn = sqlite3.connect(path)
        self.conn.row_factory = sqlite3.Row
        self.conn.executescript(SCHEMA)
        self._migrate()

    def _migrate(self) -> None:
        cols = {row[1] for row in self.conn.execute("pragma table_info(measurements)")}
        if "map" in cols:  # schema before 2026-11-04, when the field was believed to be the map
            with self.conn:
                self.conn.execute("alter table measurements rename column map to map_field")

    def add(self, m: Measurement, device: str) -> bool:
        """store a measurement; returns False if the same raw record was already stored."""
        try:
            with self.conn:
                self.conn.execute(
                    "insert into measurements (device, user, measured_at, received_at, "
                    "systolic, diastolic, map_field, pulse, flags, raw_hex) "
                    "values (?,?,?,?,?,?,?,?,?,?)",
                    (
                        device,
                        m.user,
                        m.timestamp.isoformat(sep=" ") if m.timestamp else None,
                        datetime.now().isoformat(sep=" ", timespec="seconds"),
                        m.systolic,
                        m.diastolic,
                        m.map_field,
                        m.pulse,
                        ",".join(m.flags),
                        m.raw.hex(),
                    ),
                )
        except sqlite3.IntegrityError:
            return False
        return True

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
