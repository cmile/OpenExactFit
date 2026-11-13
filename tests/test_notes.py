"""the owner's note on a reading: stored, migrated into older files, carried by csv."""

import io
import sqlite3

from exactfit import protocol
from exactfit.store import Store
from tests.test_protocol import CAPTURE_A, OWN_1


def test_set_and_clear_a_note(tmp_path):
    store = Store(tmp_path / "t.sqlite")
    store.add(protocol.parse_measurement(OWN_1), "aa:bb")
    rid = store.all()[0]["id"]
    assert store.set_note(rid, "  after coffee ") is True
    assert store.all()[0]["note"] == "after coffee"
    assert store.set_note(rid, "") is True
    assert store.all()[0]["note"] is None
    assert store.set_note(rid + 99, "x") is False


def test_version_2_file_gets_the_note_column(tmp_path):
    path = tmp_path / "v2.sqlite"
    conn = sqlite3.connect(path)
    conn.executescript("""
        create table measurements (
            id integer primary key, device text, user text, measured_at text,
            received_at text not null, systolic integer not null, diastolic integer not null,
            map_field integer not null, pulse integer, flags text, raw_hex text not null);
        insert into measurements values (1, 'x', 'A', '2026-01-15 21:10:00', 'r', 120, 80, 0, 70, '', '00');
        pragma user_version = 2;
    """)
    conn.commit(); conn.close()
    store = Store(path)
    assert store.all()[0]["note"] is None
    assert store.conn.execute("pragma user_version").fetchone()[0] == 3
    assert store.set_note(1, "kept") and Store(path).all()[0]["note"] == "kept"


def test_csv_carries_notes_and_fills_missing_ones(tmp_path):
    source = Store(tmp_path / "a.sqlite")
    source.add(protocol.parse_measurement(CAPTURE_A), "aa:bb", note='walked, then "rested"')
    source.add(protocol.parse_measurement(OWN_1), "aa:bb", note="morning")
    out = io.StringIO()
    source.export_csv(source.all(), out)

    target = Store(tmp_path / "b.sqlite")
    target.add(protocol.parse_measurement(OWN_1), "aa:bb")          # already there, no note
    assert target.import_csv(io.StringIO(out.getvalue())) == (1, 1, 0)
    assert sorted(r["note"] for r in target.all()) == ["morning", 'walked, then "rested"']
    # a note the owner already wrote is not overwritten by an import
    target.set_note(target.all()[0]["id"], "mine")
    target.import_csv(io.StringIO(out.getvalue()))
    assert "mine" in [r["note"] for r in target.all()]
