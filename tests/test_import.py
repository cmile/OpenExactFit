"""csv import: an export of either client goes back in, deduplicated, with its receipt times."""

import io

from exactfit import protocol
from exactfit.store import Store
from tests.test_protocol import CAPTURE_A, OWN_1, STORED_1


def test_export_then_import_is_a_round_trip(tmp_path):
    source = Store(tmp_path / "a.sqlite")
    source.add(protocol.parse_measurement(CAPTURE_A), "aa:bb", received_at="2026-01-20 10:00:00")
    source.add(protocol.parse_measurement(OWN_1), "aa:bb", received_at="2026-01-20 10:00:01")
    out = io.StringIO()
    source.export_csv(source.all(), out)

    target = Store(tmp_path / "b.sqlite")
    assert target.import_csv(io.StringIO(out.getvalue())) == (2, 0, 0)
    rows = target.all()  # ordered by measurement time: the january record first
    assert [(r["systolic"], r["diastolic"], r["received_at"], r["flags"]) for r in rows] == [
        (122, 78, "2026-01-20 10:00:01", ""),
        (116, 73, "2026-01-20 10:00:00", "body_movement"),
    ]
    # a second import changes nothing
    assert target.import_csv(io.StringIO(out.getvalue())) == (0, 2, 0)


def test_android_layout_and_bad_rows(tmp_path):
    # the android export has no map_field column; the reading is re-parsed from raw_hex anyway
    text = (
        "id,device,user,measured_at,received_at,systolic,diastolic,pulse,flags,raw_hex\n"
        f"7,AA:BB,A,2026-01-14 07:35:00,2026-01-15 08:00:00,118,76,68,,{STORED_1.hex()}\n"
        "8,AA:BB,A,,,0,0,,,zz\n"
    )
    store = Store(tmp_path / "c.sqlite")
    assert store.import_csv(io.StringIO(text)) == (1, 0, 1)
    row = store.all()[0]
    assert (row["systolic"], row["pulse"], row["device"], row["received_at"]) == (118, 68, "AA:BB", "2026-01-15 08:00:00")
