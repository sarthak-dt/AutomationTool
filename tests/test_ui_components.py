"""Pure HTML builders used by the UI (no Streamlit needed)."""
import numpy as np
import pandas as pd

from services import comparison_engine as ce
from services.models import ComparisonSettings, DecoderResult, EngineState, Status
from tests.conftest import make_frame, result
from ui import components as C


def rows_for(jv_delta=3.0, extra_py=True, unit="km/h"):
    t = np.arange(0, 5, 0.5)
    p = [make_frame("Speed", t, t * 10 + 0.5, unit=unit)]
    if extra_py:
        p.append(make_frame("Brake"))
    jv = t * 10 + 0.5
    jv[3] += jv_delta
    r = ce.compare_results(result("python", *p), result("java", make_frame("Speed", t, jv, unit=unit)),
                           ComparisonSettings(auto_discrete=False), "f.asc")
    return r


def test_table_has_prototype_columns_pills_and_units():
    h = C.comparison_table_html(rows_for().rows)
    for col in ("#", "Date / time", "Timestamp (s)", "CAN ID", "Python ch", "Java ch", "Signal", "Python value", "Python unit", "Java value", "Java unit", "Abs. error", "Status"):
        assert f"<th>{col}</th>" in h
    assert 'status pass">MATCH' in h and 'status fail">MISMATCH' in h and 'status fail">MISSING IN JAVA' in h
    assert "km/h" in h and "Missing" in h and "—" in h


def test_table_escapes_html_and_limits_rows():
    df = rows_for().rows.copy()
    df.loc[df.index[0], "signal"] = "<img src=x onerror=alert(1)>"
    h = C.comparison_table_html(df, limit=3)
    assert "<img" not in h and "&lt;img" in h
    assert h.count("<tr") - 1 == 3


def test_agreement_html_percentages_and_colors():
    class Job:
        pass

    r = rows_for()
    job = Job()
    job.summary = ce.summarize(r.rows)
    job.signal_summary = r.signal_summary
    body, rest = C.agreement_html(job)
    assert "90.0%" in body and "Signal agreement" in body and "REVIEW" in body
    assert 'class="red"' in body or 'class="amber"' in body  # Speed 90% -> amber, Brake missing -> red
    assert rest == ""
    body2, rest2 = C.agreement_html(job, max_signals=0)
    assert "Speed" not in body2 and "Speed" in rest2


def test_agreement_pass_when_all_match():
    class Job:
        pass

    t = np.arange(0, 3, 1.0)
    f = make_frame("A", t, t + 0.5)
    r = ce.compare_results(result("python", f), result("java", f), ComparisonSettings(auto_discrete=False), "x")
    job = Job()
    job.summary, job.signal_summary = ce.summarize(r.rows), r.signal_summary
    body, _ = C.agreement_html(job)
    assert ">PASS<" in body and "100.0%" in body


def test_metrics_row_and_helpers_escape():
    h = C.metrics_row_html([("A<b>", 5, "sub", "▤", "trend")])
    assert "A&lt;b&gt;" in h and 'class="metrics"' in h and 'class="trend"' in h
    assert "&lt;" in C.chips_html(["<x>.dbc"])
    assert C.val_with_unit(1500.0, "rpm") == "1500 rpm" and C.val_with_unit(np.nan, "rpm") == "Missing"


def test_recent_jobs_table_states():
    entries = [
        {"job_id": "CMP-1", "files": ["a.mf4", "b.mf4"], "n_dbcs": 2, "comparison_performed": False, "python_signals": 7, "duration_s": 75},
        {"job_id": "CMP-2", "files": ["c.asc"], "n_dbcs": 1, "comparison_performed": True, "signals_total": 4, "match_percentage": 100.0, "mismatch": 0, "error": 0, "duration_s": 9},
        {"job_id": "CMP-3", "files": ["<d>.asc"], "n_dbcs": 1, "comparison_performed": True, "signals_total": 4, "match_percentage": 90.0, "mismatch": 2, "error": 0, "duration_s": 3},
    ]
    h = C.recent_jobs_table_html(entries)
    assert "PYTHON ONLY" in h and "a.mf4 (+1 more)" in h and "01:15" in h
    assert 'status pass">PASS' in h and 'status warn">REVIEW' in h
    assert "&lt;d&gt;.asc" in h and "<d>" not in h


def test_table_shows_datetime_channel_can_id_and_separate_unit_columns():
    import pandas as pd

    rows = pd.DataFrame({
        "file": ["f"], "can_id": ["0x625"], "channel": [2], "signal": ["BIN"], "timestamp": [1.5],
        "python_value": [48.0], "java_value": [49.0], "abs_diff": [1.0], "rel_diff": [0.02], "tolerance": [0.1],
        "status": ["MISMATCH"], "unit_python": ["V"], "unit_java": ["mV"], "discrete": [False], "note": [""],
    })
    h = C.comparison_table_html(rows, starts={"f": pd.Timestamp("2026-06-23 00:00:01")})
    assert "<td>1</td><td>2026-06-23 00:00:02.500</td><td>1.5</td><td>0x625</td><td>2</td><td>—</td><td>BIN</td>" in h
    assert "<td>48</td><td>V</td><td>49</td><td>mV</td><td>1</td>" in h and "MISMATCH" in h
    h2 = C.comparison_table_html(rows)  # no start time -> no readable time, seconds still shown
    assert "<td>—</td><td>1.5</td>" in h2


def test_channel_zero_is_visible_when_a_can_id_is_present():
    rows = pd.DataFrame({
        "file": ["f"], "can_id": ["0x100"], "channel": [0], "java_channel": [0], "signal": ["A"], "timestamp": [0.0],
        "python_value": [1.0], "java_value": [1.0], "abs_diff": [0.0], "rel_diff": [0.0], "tolerance": [0.0],
        "status": ["MATCH"], "unit_python": [""], "unit_java": [""], "discrete": [False], "note": [""],
    })
    html = C.comparison_table_html(rows)
    assert "<td>0</td><td>0</td><td>A</td>" in html


def test_export_frame_layout_and_datetime():
    import pandas as pd
    from services import report_service as rep

    rows = pd.DataFrame({
        "file": ["f", "f"], "can_id": ["0x1", "0x1"], "channel": [1, 1], "signal": ["A", "A"], "timestamp": [0.0, 1.0],
        "python_value": [1.0, 2.0], "java_value": [1.0, 3.0], "abs_diff": [0.0, 1.0], "rel_diff": [0.0, 0.3], "tolerance": [0.0, 0.0],
        "status": ["MATCH", "MISMATCH"], "unit_python": ["", ""], "unit_java": ["", ""], "discrete": [False, False], "note": ["", ""],
    })
    out = rep.export_frame(rows, {"f": pd.Timestamp("2026-06-23 00:00:00")})
    assert list(out.columns) == rep.EXPORT_COLUMNS
    assert out["index"].tolist() == [1, 2] and out["datetime"].tolist() == ["2026-06-23 00:00:00.000", "2026-06-23 00:00:01.000"]
    assert out["abs_error"].tolist() == [0.0, 1.0]
