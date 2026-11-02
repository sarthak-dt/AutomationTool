"""Comparison logic: tolerance, missing signals, timestamps, discrete, resampling, units."""
import numpy as np
import pandas as pd
import pytest

from services import comparison_engine as ce
from services.models import ComparisonSettings, DecoderResult, EngineState, Status

from tests.conftest import make_frame, result


def run(py, java, **kw):
    s = ComparisonSettings(**kw)
    return ce.compare_results(py, java, s, "f.asc")


def statuses(res_):
    return set(res_.rows["status"])


# ------------------------------------------------------------------ tolerance
def test_identical_signals_match():
    f = make_frame("Speed", unit="km/h", discrete=False, v=np.linspace(0.5, 100.5, 10))
    r = run(result("python", f), result("java", f))
    assert r.performed
    assert statuses(r) == {Status.MATCH}
    assert ce.summarize(r.rows)["match_percentage"] == 100.0


def test_abs_tolerance_boundary():
    t = [0.0, 1.0, 2.0]
    p = make_frame("A", t, [10.5, 20.5, 30.5])
    j = make_frame("A", t, [10.55, 20.7, 30.5])
    r = run(result("python", p), result("java", j), abs_tol=0.1, rel_tol=0.0, auto_discrete=False)
    st = r.rows.sort_values("timestamp")["status"].tolist()
    assert st == [Status.MATCH, Status.MISMATCH, Status.MATCH]
    row = r.rows[r.rows["status"] == Status.MISMATCH].iloc[0]
    assert row["abs_diff"] == pytest.approx(0.2)
    assert row["tolerance"] == pytest.approx(0.1)


def test_relative_tolerance():
    t = [0.0, 1.0]
    p = make_frame("A", t, [1000.5, 0.5])
    j = make_frame("A", t, [1001.0, 0.6])  # 0.05 % off; 20 % off
    r = run(result("python", p), result("java", j), abs_tol=0.0, rel_tol=0.001, auto_discrete=False)
    st = r.rows.sort_values("timestamp")["status"].tolist()
    assert st == [Status.MATCH, Status.MISMATCH]


def test_zero_tolerance_requires_equality():
    p = make_frame("A", [0, 1], [1.5, 2.5])
    j = make_frame("A", [0, 1], [1.5, 2.5000001])
    r = run(result("python", p), result("java", j), abs_tol=0.0, rel_tol=0.0, auto_discrete=False)
    assert Status.MISMATCH in statuses(r)


def test_inf_equal_matches():
    p = make_frame("A", [0, 1], [np.inf, 1.5])
    j = make_frame("A", [0, 1], [np.inf, 1.5])
    r = run(result("python", p), result("java", j), auto_discrete=False)
    assert statuses(r) == {Status.MATCH}


# ------------------------------------------------------------------ missing
def test_missing_signals_are_not_mismatches():
    py = result("python", make_frame("OnlyPy"), make_frame("Both"))
    jv = result("java", make_frame("OnlyJava"), make_frame("Both"))
    r = run(py, jv)
    by_sig = r.rows.groupby("signal")["status"].agg(set).to_dict()
    assert by_sig["OnlyPy"] == {Status.MISSING_IN_JAVA}
    assert by_sig["OnlyJava"] == {Status.MISSING_IN_PYTHON}
    assert by_sig["Both"] == {Status.MATCH}
    summ = ce.summarize(r.rows)
    assert summ["mismatch"] == 0
    assert summ["signals_missing_in_java"] == 1
    assert summ["signals_missing_in_python"] == 1
    # one row per missing signal, with empty timestamp
    only = r.rows[r.rows["signal"] == "OnlyPy"]
    assert len(only) == 1 and only["timestamp"].isna().all()


def test_signal_with_all_nan_timestamps_is_missing():
    p = make_frame("A")
    j = make_frame("A", t=[np.nan] * 10, v=np.zeros(10))
    r = run(result("python", p), result("java", j))
    assert statuses(r) == {Status.MISSING_IN_JAVA} or statuses(r) == {Status.MISSING_IN_PYTHON}


def test_nan_value_flagged_as_missing_not_mismatch():
    t = [0, 1, 2]
    p = make_frame("A", t, [1.5, np.nan, 3.5])
    j = make_frame("A", t, [1.5, 2.5, np.nan])
    r = run(result("python", p), result("java", j), auto_discrete=False)
    st = dict(zip(r.rows["timestamp"], r.rows["status"]))
    assert st[0.0] == Status.MATCH
    assert st[1.0] == Status.MISSING_IN_PYTHON
    assert st[2.0] == Status.MISSING_IN_JAVA


def test_both_nan_matches_and_skip_policy_drops_nan_rows():
    t = [0, 1, 2]
    p = make_frame("A", t, [1.5, np.nan, 3.5])
    j = make_frame("A", t, [1.5, np.nan, np.nan])
    r = run(result("python", p), result("java", j), auto_discrete=False)
    assert dict(zip(r.rows["timestamp"], r.rows["status"]))[1.0] == Status.MATCH
    r2 = run(result("python", p), result("java", j), auto_discrete=False, missing_policy="skip")
    assert sorted(r2.rows["timestamp"]) == [0.0]


# ------------------------------------------------------------------ timestamps
def test_timestamp_within_tolerance_pairs_samples():
    p = make_frame("A", [0.0, 1.0, 2.0], [1.5, 2.5, 3.5])
    j = make_frame("A", [0.0004, 1.0004, 2.0004], [1.5, 2.5, 3.5])
    r = run(result("python", p), result("java", j), time_tol=0.001, auto_discrete=False)
    assert statuses(r) == {Status.MATCH}


def test_timestamp_outside_tolerance_reports_missing_samples():
    p = make_frame("A", [0.0, 1.0], [1.5, 2.5])
    j = make_frame("A", [0.0, 1.5], [1.5, 2.5])
    r = run(result("python", p), result("java", j), time_tol=0.01, auto_discrete=False)
    st = r.rows["status"].tolist()
    assert st.count(Status.MATCH) == 1
    assert st.count(Status.MISSING_IN_JAVA) == 1
    assert st.count(Status.MISSING_IN_PYTHON) == 1
    assert Status.MISMATCH not in st


def test_unsorted_timestamps_are_handled():
    p = make_frame("A", [2.0, 0.0, 1.0], [3.5, 1.5, 2.5])
    j = make_frame("A", [0.0, 1.0, 2.0], [1.5, 2.5, 3.5])
    r = run(result("python", p), result("java", j), auto_discrete=False)
    assert statuses(r) == {Status.MATCH}


# ------------------------------------------------------------------ resampling
def test_resample_1s_aligns_different_rates():
    tp = np.arange(0, 10.01, 0.1)
    tj = np.arange(0, 10.01, 0.25)
    p = make_frame("Ramp", tp, tp * 2.0 + 0.5)
    j = make_frame("Ramp", tj, tj * 2.0 + 0.5)
    r = run(result("python", p), result("java", j), resample_1s=True, abs_tol=1e-6, auto_discrete=False)
    assert statuses(r) == {Status.MATCH}
    assert sorted(r.rows["timestamp"]) == [float(i) for i in range(0, 11)]


def test_resample_1s_detects_value_difference():
    t = np.arange(0, 5.01, 0.5)
    p = make_frame("A", t, t + 0.5)
    jv = t + 0.5
    jv[t == 3.0] += 1.0
    j = make_frame("A", t, jv)
    r = run(result("python", p), result("java", j), resample_1s=True, abs_tol=0.01, auto_discrete=False)
    bad = r.rows[r.rows["status"] == Status.MISMATCH]
    assert list(bad["timestamp"]) == [3.0]


def test_resample_no_overlap_is_not_comparable():
    p = make_frame("A", [0.1, 0.2, 0.3], [1.5, 2.5, 3.5])
    j = make_frame("A", [0.1, 0.2, 0.3], [1.5, 2.5, 3.5])
    r = run(result("python", p), result("java", j), resample_1s=True, auto_discrete=False)
    assert statuses(r) == {Status.NOT_COMPARABLE}


def test_resample_discrete_uses_zero_order_hold():
    tp = [0.0, 0.5, 1.5, 2.5]
    p = make_frame("Gear", tp, [1, 1, 2, 3], discrete=True)
    j = make_frame("Gear", tp, [1, 1, 2, 3], discrete=True)
    r = run(result("python", p), result("java", j), resample_1s=True)
    assert statuses(r) == {Status.MATCH}
    # grid is 1 s and 2 s (overlap [0, 2.5]); held values are the last sample at/before the grid point
    rows = r.rows.sort_values("timestamp")
    assert rows["timestamp"].tolist() == [0.0, 1.0, 2.0]
    assert rows["python_value"].tolist() == [1.0, 1.0, 2.0]


# ------------------------------------------------------------------ discrete / text
def test_discrete_signals_compare_exactly_ignoring_tolerance():
    p = make_frame("Gear", [0, 1, 2], [1, 2, 3], discrete=True)
    j = make_frame("Gear", [0, 1, 2], [1, 2, 4], discrete=True)
    r = run(result("python", p), result("java", j), abs_tol=10.0, rel_tol=1.0)
    assert r.rows.sort_values("timestamp")["status"].tolist() == [Status.MATCH, Status.MATCH, Status.MISMATCH]
    assert r.rows["discrete"].all()


def test_auto_discrete_detects_small_integer_signals():
    p = make_frame("Flag", [0, 1, 2], [0, 1, 1])
    j = make_frame("Flag", [0, 1, 2], [0, 1, 1])
    r = run(result("python", p), result("java", j), abs_tol=0.5)
    assert r.rows["discrete"].all()
    r2 = run(result("python", p), result("java", j), auto_discrete=False)
    assert not r2.rows["discrete"].any()


def test_discrete_signal_list_setting():
    p = make_frame("Mode", [0, 1], [1.5, 2.5])
    j = make_frame("Mode", [0, 1], [1.5, 2.6])
    r = run(result("python", p), result("java", j), abs_tol=1.0, auto_discrete=False, discrete_signals=("Mode",))
    assert Status.MISMATCH in statuses(r)


def test_text_signals_compared_as_strings():
    p = make_frame("State", [0, 1], ["ON", "OFF"])
    j = make_frame("State", [0, 1], ["ON ", "IDLE"])
    r = run(result("python", p), result("java", j))
    assert r.rows.sort_values("timestamp")["status"].tolist() == [Status.MATCH, Status.MISMATCH]


def test_numeric_vs_text_not_comparable():
    p = make_frame("A", [0, 1], [1.5, 2.5])
    j = make_frame("A", [0, 1], ["x", "y"])
    r = run(result("python", p), result("java", j))
    assert statuses(r) == {Status.NOT_COMPARABLE}


# ------------------------------------------------------------------ units
def test_unit_mismatch_noted_but_compared_by_default():
    p = make_frame("A", [0, 1], [1.5, 2.5], unit="km/h")
    j = make_frame("A", [0, 1], [1.5, 2.5], unit="mph")
    r = run(result("python", p), result("java", j), auto_discrete=False)
    assert statuses(r) == {Status.MATCH}
    assert r.rows["note"].str.contains("unit mismatch").all()


def test_unit_mismatch_strict_is_not_comparable():
    p = make_frame("A", [0, 1], [1.5, 2.5], unit="km/h")
    j = make_frame("A", [0, 1], [1.5, 2.5], unit="mph")
    r = run(result("python", p), result("java", j), unit_policy="strict", auto_discrete=False)
    assert statuses(r) == {Status.NOT_COMPARABLE}


# ------------------------------------------------------------------ engine states
def test_java_unavailable_means_no_comparison_and_no_rows():
    py = result("python", make_frame("A"))
    java = DecoderResult.unavailable("java", "not configured")
    r = ce.compare_results(py, java, ComparisonSettings(), "f.asc")
    assert not r.performed
    assert "MOCK / UNAVAILABLE" in r.reason
    assert r.rows.empty


def test_java_error_produces_error_row():
    py = result("python", make_frame("A"))
    java = DecoderResult.failed("java", "HTTP 500")
    r = ce.compare_results(py, java, ComparisonSettings(), "f.asc")
    assert not r.performed
    assert statuses(r) == {Status.ERROR}
    assert "HTTP 500" in r.rows["note"].iloc[0]


def test_python_error_produces_error_row():
    py = DecoderResult.failed("python", "bad file")
    java = result("java", make_frame("A"))
    r = ce.compare_results(py, java, ComparisonSettings(), "f.asc")
    assert statuses(r) == {Status.ERROR}


def test_exception_in_signal_becomes_error_row(monkeypatch):
    def boom(*a, **k):
        raise RuntimeError("kaboom")

    monkeypatch.setattr(ce, "_compare_signal", boom)
    r = run(result("python", make_frame("A")), result("java", make_frame("A")))
    assert statuses(r) == {Status.ERROR}
    assert "kaboom" in r.rows["note"].iloc[0]


# ------------------------------------------------------------------ summaries
def test_summary_counts_and_percentage():
    t = [0, 1, 2, 3]
    p = make_frame("A", t, [1.5, 2.5, 3.5, 4.5])
    j = make_frame("A", t, [1.5, 2.5, 9.5, 9.5])
    r = run(result("python", p, make_frame("X")), result("java", j), auto_discrete=False)
    s = ce.summarize(r.rows)
    assert s["match"] == 2 and s["mismatch"] == 2
    assert s["match_percentage"] == 50.0
    assert s["missing_in_java"] == 1
    assert s["signals_with_mismatch"] == 1
    sig = r.signal_summary.set_index("signal")
    assert sig.loc["A", "status"] == Status.MISMATCH
    assert sig.loc["A", "max_abs_diff"] == pytest.approx(6.0)
    assert sig.loc["X", "status"] == Status.MISSING_IN_JAVA


def test_settings_validation():
    with pytest.raises(ValueError):
        ComparisonSettings(abs_tol=-1).validate()
    with pytest.raises(ValueError):
        ComparisonSettings(missing_policy="nope").validate()
    with pytest.raises(ValueError):
        ComparisonSettings(unit_policy="nope").validate()


def test_empty_both_outputs_note():
    r = run(result("python"), result("java"))
    assert r.performed and r.rows.empty
    assert r.notes
