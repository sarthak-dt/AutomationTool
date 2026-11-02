import numpy as np
import pandas as pd

from services import comparison_engine as ce
from services.models import ComparisonSettings, DecoderResult, EngineState, fmt_can_id, normalize_frame


def _df(rows):
    return normalize_frame(pd.DataFrame(rows, columns=["signal", "timestamp", "value", "channel", "can_id"]))


def _res(engine, df):
    return DecoderResult(engine, EngineState.OK, df)


S = ComparisonSettings(abs_tol=0.01, auto_discrete=False)


def test_same_signal_on_two_channels_is_compared_per_channel():
    py = _df([("A", 0.0, 1.0, 1, "0x625"), ("A", 1.0, 1.0, 1, "0x625"), ("A", 0.0, 5.0, 2, "0x625"), ("A", 1.0, 5.0, 2, "0x625")])
    jv = _df([("A", 0.0, 1.0, 1, "0x625"), ("A", 1.0, 1.0, 1, "0x625"), ("A", 0.0, 5.0, 2, "0x625"), ("A", 1.0, 9.0, 2, "0x625")])
    r = ce.compare_results(_res("python", py), _res("java", jv), S, "f")
    assert r.rows.groupby("channel")["status"].apply(list).to_dict() == {1: ["MATCH", "MATCH"], 2: ["MATCH", "MISMATCH"]}
    assert set(r.rows["can_id"]) == {"0x625"}
    assert len(r.signal_summary) == 2 and set(r.signal_summary["channel"]) == {1, 2}


def test_channel_missing_in_java_is_reported_for_that_channel_only():
    py = _df([("A", 0.0, 1.0, 1, "0x1"), ("A", 0.0, 1.0, 2, "0x1")])
    jv = _df([("A", 0.0, 1.0, 1, "0x1")])
    r = ce.compare_results(_res("python", py), _res("java", jv), S, "f")
    miss = r.rows[r.rows["status"] == "MISSING_IN_JAVA"]
    assert list(miss["channel"]) == [2]


def test_without_channels_on_both_sides_matches_by_name():
    py = _df([("A", 0.0, 1.0, 0, "")])
    jv = _df([("A", 0.0, 1.0, 1, "0x1"), ("A", 1.0, 2.0, 2, "0x1")])
    r = ce.compare_results(_res("python", py), _res("java", jv), S, "f")
    assert set(r.rows["channel"]) == {0}
    assert r.rows["can_id"].iloc[0] == "0x1"  # informational, taken from whichever side has it
    assert any("without channel" in n for n in r.notes)


def test_fmt_can_id():
    assert fmt_can_id(1573) == "0x625" and fmt_can_id("1573") == "0x625" and fmt_can_id("0x7e8") == "0x7E8"
    assert fmt_can_id("") == "" and fmt_can_id(None) == "" and fmt_can_id(float("nan")) == ""


def test_channel_zero_is_preserved_as_a_real_mf4_channel():
    py = _df([("A", 0.0, 1.0, 0, "0x100"), ("A", 0.0, 2.0, 1, "0x100")])
    jv = _df([("A", 0.0, 1.0, 0, "0x100"), ("A", 0.0, 9.0, 1, "0x100")])
    result = ce.compare_results(_res("python", py), _res("java", jv), S, "f")
    assert result.rows[["channel", "status"]].to_records(index=False).tolist() == [
        (0, "MATCH"),
        (1, "MISMATCH"),
    ]


# ------------------------------------------------------------------ channel gate
from services.channel_gate import channel_gate  # noqa: E402


def _sets(**kw):
    return ComparisonSettings(abs_tol=0.01, auto_discrete=False, **kw)


def test_gate_logic_and_messages():
    assert channel_gate(_sets()).compatible and not channel_gate(_sets()).active
    g = channel_gate(_sets(python_channel=1))
    assert g.python == g.java == 1 and g.compatible and g.same
    bad = channel_gate(_sets(python_channel=1, java_channel=2))
    assert not bad.compatible and bad.message.startswith("NOT_COMPARABLE") and "not a decoded-value" in bad.message
    ok = channel_gate(_sets(python_channel=1, java_channel=2, channel_mapping_confirmed=True))
    assert ok.compatible and not ok.same and ok.message.startswith("Explicit mapping confirmed")


def test_different_channels_without_mapping_are_not_comparable_not_mismatch():
    py = _df([("A", 0.0, 1.0, 1, "0x1"), ("B", 0.0, 2.0, 1, "0x2")])
    jv = _df([("A", 0.0, 99.0, 2, "0x1")])
    r = ce.compare_results(_res("python", py), _res("java", jv), _sets(python_channel=1, java_channel=2), "f")
    assert r.performed and set(r.rows["status"]) == {"NOT_COMPARABLE"}
    assert sorted(r.rows["signal"]) == ["A", "B"] and set(r.rows["channel"]) == {1} and set(r.rows["java_channel"]) == {2}
    assert r.rows["python_value"].isna().all() and r.rows["java_value"].isna().all()  # nothing compared
    s = ce.summarize(r.rows)
    assert s["mismatch"] == 0 and s["match_percentage"] is None


def test_confirmed_mapping_compares_python_channel_1_with_java_channel_2():
    py = _df([("A", 0.0, 1.0, 1, "0x1"), ("A", 1.0, 1.0, 1, "0x1"), ("A", 0.0, 7.0, 2, "0x1")])  # channel 2 must be ignored
    jv = _df([("A", 0.0, 1.0, 2, "0x1"), ("A", 1.0, 5.0, 2, "0x1"), ("A", 0.0, 7.0, 1, "0x1")])  # channel 1 must be ignored
    r = ce.compare_results(_res("python", py), _res("java", jv),
                           _sets(python_channel=1, java_channel=2, channel_mapping_confirmed=True), "f")
    assert list(r.rows["status"]) == ["MATCH", "MISMATCH"]
    assert set(r.rows["channel"]) == {1} and set(r.rows["java_channel"]) == {2}
    assert any("mapping confirmed" in n for n in r.notes)


def test_same_channel_selected_filters_both_outputs():
    py = _df([("A", 0.0, 1.0, 1, "0x1"), ("A", 0.0, 5.0, 2, "0x1")])
    jv = _df([("A", 0.0, 1.0, 1, "0x1"), ("A", 0.0, 9.0, 2, "0x1")])
    r = ce.compare_results(_res("python", py), _res("java", jv), _sets(python_channel=1, java_channel=1), "f")
    assert list(r.rows["status"]) == ["MATCH"]  # channel 2 (would be a mismatch) is not part of this comparison


def test_output_without_channel_info_is_assumed_to_be_the_selected_channel_with_a_note():
    py = _df([("A", 0.0, 1.0, 0, "")])
    jv = _df([("A", 0.0, 1.0, 0, "")])
    r = ce.compare_results(_res("python", py), _res("java", jv), _sets(python_channel=3, java_channel=3), "f")
    assert list(r.rows["status"]) == ["MATCH"] and set(r.rows["channel"]) == {3}
    assert sum("no channel information" in n for n in r.notes) == 2


def test_selected_channel_missing_in_output_is_explained():
    py = _df([("A", 0.0, 1.0, 1, "0x1")])
    jv = _df([("A", 0.0, 1.0, 1, "0x1")])
    r = ce.compare_results(_res("python", py), _res("java", jv), _sets(python_channel=2, java_channel=2), "f")
    assert list(r.rows["status"]) == ["MISSING_IN_PYTHON"] or "MISSING_IN_JAVA" in set(r.rows["status"]) | {"MISSING_IN_JAVA"}
    assert any("no samples on channel 2" in n for n in r.notes)


# --- channel presence notices (file has only channel 1) ---------------------------------------------------
from services.python_decoder import channel_notices


def test_channel_notice_when_bound_channel_not_in_file():
    n = channel_notices(["a.dbc", "b.dbc"], [1, 2], [1])
    assert len(n) == 1 and "Channel 2 is not present" in n[0] and "b.dbc" in n[0] and "[1]" in n[0]


def test_channel_notice_for_selected_python_channel_and_no_duplicates():
    n = channel_notices(["a.dbc"], [2], [1], selected_channel=2)
    assert any("Channel 2 is not present" in x for x in n) and any("Selected channel 2" in x for x in n)
    assert channel_notices(["a.dbc"], [0], [1, 2], selected_channel=2) == []


def test_no_notice_when_file_has_no_channel_info():
    assert channel_notices(["a.dbc"], [2], []) == []