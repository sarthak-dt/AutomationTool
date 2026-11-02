"""DBC validation, duplicate handling and conflict reporting.

Tests with a fake parser always run. Tests using the real ``cantools`` are
skipped automatically when it is not installed.
"""
from types import SimpleNamespace as NS

import pytest

from services import dbc_service as ds
from services.dbc_service import DbcInfo

MAX = 1024 * 1024


def sig(name, start=0, length=8, unit="", scale=1.0, offset=0.0, signed=False, order="little_endian"):
    return NS(name=name, start=start, length=length, byte_order=order, is_signed=signed, scale=scale, offset=offset, unit=unit)


def msg(frame_id, name, signals, length=8, ext=False):
    return NS(frame_id=frame_id, name=name, length=length, is_extended_frame=ext, signals=signals)


def fake_db(*messages):
    return NS(messages=list(messages))


@pytest.fixture
def fake_parser(monkeypatch):
    """parse_dbc replacement: content b'BAD' is invalid, b'WARN' loads with a warning,
    anything else yields one message whose name is derived from the content."""

    def parse(data):
        if data == b"BAD":
            return None, DbcInfo(status="INVALID", error="Not a valid DBC: boom")
        db = fake_db(msg(0x100, data.decode(), [sig("S_" + data.decode())]))
        if data == b"WARN":
            return db, DbcInfo(status="VALID_WITH_WARNINGS", n_messages=1, n_signals=1, warnings=["strict failed"])
        return db, DbcInfo(status="VALID", n_messages=1, n_signals=1)

    monkeypatch.setattr(ds, "parse_dbc", parse)


# --------------------------------------------------------------------- uploads
def test_valid_upload_added(fake_parser):
    lib, msgs = ds.add_uploads([], [("a.dbc", b"ONE")], 10, MAX)
    assert [d.name for d in lib] == ["a.dbc"]
    assert lib[0].info.usable and lib[0].catalog
    assert msgs[0][0] == "success"


def test_invalid_dbc_is_kept_but_marked_invalid_and_reported(fake_parser):
    lib, msgs = ds.add_uploads([], [("bad.dbc", b"BAD")], 10, MAX)
    assert lib[0].info.status == "INVALID" and not lib[0].info.usable
    assert any(level == "error" and "boom" in text for level, text in msgs)


def test_wrong_extension_empty_and_oversized_rejected(fake_parser):
    lib, msgs = ds.add_uploads([], [("a.txt", b"x"), ("e.dbc", b""), ("big.dbc", b"x" * 20)], 10, 10)
    assert lib == []
    assert [m[0] for m in msgs] == ["error"] * 3


def test_identical_duplicate_is_skipped(fake_parser):
    lib, _ = ds.add_uploads([], [("a.dbc", b"ONE")], 10, MAX)
    lib2, msgs = ds.add_uploads(lib, [("A.DBC", b"ONE")], 10, MAX)
    assert len(lib2) == 1
    assert "identical" in msgs[0][1]


def test_same_name_different_content_gets_renamed_with_warning(fake_parser):
    lib, _ = ds.add_uploads([], [("a.dbc", b"ONE")], 10, MAX)
    lib2, msgs = ds.add_uploads(lib, [("a.dbc", b"TWO")], 10, MAX)
    assert [d.name for d in lib2] == ["a.dbc", "a (2).dbc"]
    assert any("stored as 'a (2).dbc'" in t for _, t in msgs)


def test_same_content_different_name_warns(fake_parser):
    lib, _ = ds.add_uploads([], [("a.dbc", b"ONE")], 10, MAX)
    lib2, msgs = ds.add_uploads(lib, [("b.dbc", b"ONE")], 10, MAX)
    assert len(lib2) == 2
    assert lib2[1].info.status == "VALID_WITH_WARNINGS"
    assert any("identical content" in t for _, t in msgs)


def test_file_limit_enforced(fake_parser):
    lib, msgs = ds.add_uploads([], [("a.dbc", b"ONE"), ("b.dbc", b"TWO")], 1, MAX)
    assert len(lib) == 1
    assert any("limit of 1" in t for _, t in msgs)


def test_path_in_upload_name_is_stripped(fake_parser):
    lib, _ = ds.add_uploads([], [("../../x/evil.dbc", b"ONE")], 10, MAX)
    assert lib[0].name == "evil.dbc"


# --------------------------------------------------------------------- conflicts
def dbc(name, *messages):
    return ds.DbcFile(
        name=name, original_name=name, data=b"", sha256=name,
        info=DbcInfo(status="VALID", n_messages=len(messages)),
        catalog=ds.catalog_from_db(fake_db(*messages)),
    )


def kinds(conflicts):
    return sorted(c.kind for c in conflicts)


def test_no_conflicts_for_disjoint_dbcs():
    a = dbc("a.dbc", msg(0x100, "M1", [sig("S1")]))
    b = dbc("b.dbc", msg(0x200, "M2", [sig("S2")]))
    assert ds.detect_conflicts([a, b]) == []


def test_identical_duplicate_message_is_info():
    a = dbc("a.dbc", msg(0x100, "M1", [sig("S1")]))
    b = dbc("b.dbc", msg(0x100, "M1", [sig("S1")]))
    c = ds.detect_conflicts([a, b])
    assert kinds(c) == ["DUPLICATE_MESSAGE"]
    assert c[0].severity == "info" and "a.dbc" in c[0].detail


def test_same_id_different_definition_is_error_and_names_winner():
    a = dbc("a.dbc", msg(0x100, "M1", [sig("S1", scale=1.0)]))
    b = dbc("b.dbc", msg(0x100, "M1", [sig("S1", scale=0.5)]))
    (c,) = ds.detect_conflicts([a, b])
    assert c.kind == "MESSAGE_ID_CONFLICT" and c.severity == "error"
    assert "S1" in c.detail and "a.dbc" in c.detail and "ignored" in c.detail


def test_same_id_different_name_and_length():
    a = dbc("a.dbc", msg(0x100, "M1", [sig("S1")], length=8))
    b = dbc("b.dbc", msg(0x100, "Other", [sig("S1")], length=4))
    (c,) = [x for x in ds.detect_conflicts([a, b]) if x.kind == "MESSAGE_ID_CONFLICT"]
    assert "message name" in c.detail and "length" in c.detail


def test_standard_and_extended_ids_do_not_conflict():
    a = dbc("a.dbc", msg(0x100, "M1", [sig("S1")], ext=False))
    b = dbc("b.dbc", msg(0x100, "M2", [sig("S2")], ext=True))
    assert ds.detect_conflicts([a, b]) == []


def test_signal_name_collision_across_messages_is_warning():
    a = dbc("a.dbc", msg(0x100, "M1", [sig("Speed")]))
    b = dbc("b.dbc", msg(0x200, "M2", [sig("Speed")]))
    (c,) = ds.detect_conflicts([a, b])
    assert c.kind == "SIGNAL_NAME_COLLISION" and c.severity == "warning"


def test_message_name_collision_with_different_ids():
    a = dbc("a.dbc", msg(0x100, "M", [sig("S1")]))
    b = dbc("b.dbc", msg(0x200, "M", [sig("S2")]))
    assert kinds(ds.detect_conflicts([a, b])) == ["MESSAGE_NAME_COLLISION"]


def test_invalid_dbcs_ignored_in_conflict_detection():
    a = dbc("a.dbc", msg(0x100, "M1", [sig("S1")]))
    bad = dbc("b.dbc", msg(0x100, "M1", [sig("S1", scale=9)]))
    bad.info = DbcInfo(status="INVALID", error="x")
    assert ds.detect_conflicts([a, bad]) == []


def test_conflicts_summary_counts():
    a = dbc("a.dbc", msg(0x100, "M1", [sig("S1")]))
    b = dbc("b.dbc", msg(0x100, "M1", [sig("S1", scale=2)]))
    s = ds.conflicts_summary(ds.detect_conflicts([a, b]))
    assert s["error"] == 1


# --------------------------------------------------------------------- real cantools
SAMPLE_DBC = '''VERSION ""

NS_ :

BS_:

BU_: ECU

BO_ 256 EngineData: 8 ECU
 SG_ Engine_RPM : 0|16@1+ (0.25,0) [0|16383.75] "rpm" Vector__XXX
 SG_ Coolant_Temp : 16|8@1+ (1,-40) [-40|215] "degC" Vector__XXX
 SG_ Brake_Active : 24|1@1+ (1,0) [0|1] "" Vector__XXX

BO_ 512 VehicleData: 8 ECU
 SG_ Vehicle_Speed : 0|16@1+ (0.01,0) [0|655.35] "km/h" Vector__XXX

VAL_ 256 Brake_Active 0 "Released" 1 "Pressed" ;
'''


def test_real_cantools_parses_valid_dbc():
    pytest.importorskip("cantools")
    db, info = ds.parse_dbc(SAMPLE_DBC.encode())
    assert info.usable and info.n_messages == 2 and info.n_signals == 4
    cat = ds.catalog_from_db(db)
    assert {m.name for m in cat} == {"EngineData", "VehicleData"}


def test_real_cantools_rejects_garbage_and_binary():
    pytest.importorskip("cantools")
    _, info = ds.parse_dbc(b"this is not a dbc file at all")
    assert not info.usable
    _, info = ds.parse_dbc(b"\x00\x01\x02\x03" * 100)
    assert not info.usable and "binary" in info.error


def test_real_cantools_explains_placeholder_only_dbc():
    pytest.importorskip("cantools")
    placeholder = '''VERSION ""
NS_ :
BS_:
BU_: ECU
BO_ 3221225472 VECTOR__INDEPENDENT_SIG_MSG: 0 Vector__XXX
 SG_ Example : 0|8@1+ (1,0) [0|255] "" Vector__XXX
'''
    _, info = ds.parse_dbc(placeholder.encode())
    assert not info.usable
    assert "zero-length VECTOR__INDEPENDENT_SIG_MSG placeholder" in info.error
    assert "real BO_ CAN message definitions" in info.error


def test_real_cantools_cp1252_encoding_warns():
    pytest.importorskip("cantools")
    data = SAMPLE_DBC.replace('"degC"', '"°C"').encode("cp1252")
    _, info = ds.parse_dbc(data)
    assert info.usable and any("cp1252" in w for w in info.warnings)


def test_real_cantools_detects_conflicting_dbcs_end_to_end():
    pytest.importorskip("cantools")
    other = SAMPLE_DBC.replace("(0.25,0)", "(0.5,0)")
    lib, _ = ds.add_uploads([], [("a.dbc", SAMPLE_DBC.encode()), ("b.dbc", other.encode())], 10, MAX)
    conflicts = ds.detect_conflicts(lib)
    assert any(c.kind == "MESSAGE_ID_CONFLICT" and "Engine_RPM" in c.detail for c in conflicts)
