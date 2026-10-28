"""Compare a finished job with Java output uploaded as CSV."""
import io

import numpy as np
import pandas as pd
import pytest

from config import AppConfig
from services import report_service as rep
from services.java_import import JavaCsvError, apply_java_csv, parse_java_csv
from services.job_runner import run_job
from services.models import ComparisonSettings, EngineState, Status
from tests.conftest import make_frame
from tests.test_job_runner_and_reports import dbc, local, py_decoder, unavailable_java


@pytest.fixture
def cfg(tmp_path):
    return AppConfig(temp_root=str(tmp_path / "tmp"), data_dir=tmp_path / "data")


def job_for(cfg, names=("a.asc",), settings=None):
    frames = {n: pd.concat([make_frame("Speed", unit="km/h", v=np.linspace(0.5, 9.5, 10)), make_frame("RPM", v=np.linspace(1000.5, 1009.5, 10))]) for n in names}
    return run_job([local(n) for n in names], [dbc()], settings or ComparisonSettings(auto_discrete=False), cfg, unavailable_java(), python_decode=py_decoder(frames))


def csv_of(rows):
    return pd.DataFrame(rows).to_csv(index=False).encode()


def rows(signal, values, file=None, unit=""):
    t = np.arange(0, 5, 0.5)
    out = [{"signal": signal, "timestamp": float(t[i]), "value": v, "unit": unit} for i, v in enumerate(values)]
    if file:
        for r in out:
            r["file"] = file
    return out


def test_python_only_job_becomes_compared_with_real_mismatch_table(cfg):
    job = job_for(cfg)
    assert not job.comparison_performed and job.rows.empty
    jv = list(np.linspace(0.5, 9.5, 10))
    jv[4] += 3
    apply_java_csv(job, csv_of(rows("Speed", jv, unit="km/h")))  # RPM absent -> missing
    assert job.comparison_performed and job.java_state == EngineState.OK
    s = job.summary
    assert s["match"] == 9 and s["mismatch"] == 1 and s["missing_in_java"] == 1
    assert s["match_percentage"] == 90.0
    bad = job.rows[job.rows["status"] == Status.MISMATCH].iloc[0]
    assert bad["signal"] == "Speed" and bad["abs_diff"] == pytest.approx(3.0) and bad["unit_java"] == "km/h"
    assert "uploaded CSV" in job.java_note and "not from the Java API" in job.java_note
    assert job.files[0].comparison_performed and job.files[0].java_state == EngineState.OK
    java_output = pd.read_csv(io.BytesIO(rep.java_output_csv(job)))
    assert "file name" in java_output and "decode value" in java_output and "message" not in java_output


def test_python_output_csv_roundtrip_matches_everything(cfg):
    job = job_for(cfg)
    apply_java_csv(job, rep.python_output_csv(job))
    assert set(job.rows["status"]) == {Status.MATCH}
    assert job.summary["match_percentage"] == 100.0


def test_multi_file_requires_file_column_and_maps_names(cfg):
    job = job_for(cfg, names=("a.asc", "b.asc"))
    with pytest.raises(JavaCsvError, match="'file' column"):
        apply_java_csv(job, csv_of(rows("Speed", np.linspace(0.5, 9.5, 10))))
    data = csv_of(rows("Speed", np.linspace(0.5, 9.5, 10), file="A.ASC") + rows("Speed", np.linspace(0.5, 9.5, 10) + 5, file="C:\\logs\\b.asc"))
    apply_java_csv(job, data)
    by_file = job.rows[job.rows["signal"] == "Speed"].groupby("file")["status"].agg(lambda s: set(s)).to_dict()
    assert by_file["a.asc"] == {Status.MATCH} and by_file["b.asc"] == {Status.MISMATCH}


def test_file_missing_from_csv_is_not_compared(cfg):
    job = job_for(cfg, names=("a.asc", "b.asc"))
    apply_java_csv(job, csv_of(rows("Speed", np.linspace(0.5, 9.5, 10), file="a.asc")))
    by = {f.name: f for f in job.files}
    assert by["a.asc"].comparison_performed and not by["b.asc"].comparison_performed
    assert any("not compared" in w for w in by["b.asc"].warnings)


def test_unknown_file_name_rejected(cfg):
    job = job_for(cfg)
    with pytest.raises(JavaCsvError, match="Unknown file"):
        apply_java_csv(job, csv_of(rows("Speed", np.linspace(0.5, 9.5, 10), file="other.asc")))


@pytest.mark.parametrize(
    "data,fragment",
    [
        (b"", "empty"),
        (b"signal,timestamp\nA,1\n", "Missing column"),
        (b"signal,timestamp,value\n", "no rows"),
        (b"signal,timestamp,value\n,1,2\n", "empty signal"),
        (b"signal,timestamp,value\nA,abc,2\n", "non-numeric timestamp"),
        (b"signal,timestamp,value\nA,inf,2\n", "non-numeric timestamp"),
    ],
)
def test_parse_errors(data, fragment):
    with pytest.raises(JavaCsvError, match=fragment):
        parse_java_csv(data)


def test_parse_headers_case_insensitive_text_values_and_bom():
    df = parse_java_csv("\ufeffSignal,Timestamp,VALUE,Unit\nGear,0,P,\nGear,1,D,\n".encode("utf-8"))
    assert df["value"].tolist() == ["P", "D"]
    df2 = parse_java_csv(b"signal,timestamp,value,is_discrete\nA,0,1,true\nA,1,,true\n")
    assert df2["value"].iloc[0] == 1.0 and np.isnan(df2["value"].iloc[1]) and df2["is_discrete"].all()
    df3 = parse_java_csv(b"file name,CAN ID,signal,timestamp,decode value\nf.asc,0x100,A,0,2.5\n")
    assert df3["file"].tolist() == ["f.asc"] and df3["value"].tolist() == [2.5]
    assert df3["can_id"].tolist() == ["0x100"]


def test_reapplying_replaces_previous_comparison(cfg):
    job = job_for(cfg)
    apply_java_csv(job, rep.python_output_csv(job))
    jv = np.linspace(0.5, 9.5, 10) + 100
    apply_java_csv(job, csv_of(rows("Speed", jv) + rows("RPM", np.linspace(1000.5, 1009.5, 10))))
    assert (job.rows["status"] == Status.MISMATCH).sum() == 10
    assert job.rows["file"].nunique() == 1


def test_python_only_table_lists_real_samples_without_verdict(cfg):
    from ui import components as C

    job = job_for(cfg)
    h = C.python_only_table_html(rep.python_output_frame(job).sort_values(["signal", "timestamp"]))
    assert "NOT COMPARED" in h and "Unavailable" in h and "MATCH" not in h.replace("NOT COMPARED", "")
    assert "km/h" in h and h.count("<tr") - 1 == 20
