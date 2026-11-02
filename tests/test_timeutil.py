import numpy as np
import pandas as pd
import pytest

from services import comparison_engine as ce, report_service as rep, timeutil
from services.java_import import JavaCsvError, apply_java_csv, parse_java_csv
from services.job_runner import FileOutcome, JobResult
from services.models import ComparisonSettings, DecoderResult, EngineState, normalize_frame


def test_format_and_parse():
    st = timeutil.parse_start("2023-08-14T17:14:38+00:00")
    assert str(st) == "2023-08-14 17:14:38"
    s = pd.Series([0.0, 0.01003, 0.49968])
    assert list(timeutil.datetimes(st, s)) == ["2023-08-14 17:14:38.000", "2023-08-14 17:14:38.010", "2023-08-14 17:14:38.500"]
    assert list(timeutil.datetimes(None, s)) == ["", "", ""]
    assert timeutil.display_time(1.5, None) == "1.500 s"
    assert timeutil.display_time(float("nan"), st) == "—"


def test_epoch_to_start():
    assert timeutil.epoch_to_start(5.0) is None  # relative
    assert timeutil.epoch_to_start(None) is None
    assert timeutil.epoch_to_start(1692033278.0).startswith("2023-08-14T17:14:38")


def _job(start):
    df = normalize_frame(pd.DataFrame({"signal": "A", "timestamp": [0.0, 1.0, 2.0], "value": [1.0, 2.0, 3.0], "unit": "", "message": "", "is_discrete": False}))
    job = JobResult(job_id="J", started_at="x", settings=ComparisonSettings().to_dict(), python_data={"f.mf4": df})
    job.files.append(FileOutcome(name="f.mf4", source="local", size=1, python_state=EngineState.OK,
                                 python_stats={"start_time": start} if start else {}))
    return job


def test_python_output_csv_has_datetime_and_no_is_discrete():
    out = rep.python_output_csv(_job("2023-08-14T17:14:38")).decode()
    lines = out.strip().splitlines()
    assert lines[0] == "file,can_id,channel,signal,datetime,timestamp,value,unit,message"
    assert lines[2].startswith("f.mf4,,0,A,2023-08-14 17:14:39.000,1.0,")


def test_python_output_csv_without_start_has_no_datetime_column():
    assert "datetime" not in rep.python_output_csv(_job(None)).decode().splitlines()[0]


def test_java_csv_with_datetime_only_is_converted():
    job = _job("2023-08-14T17:14:38")
    csv = b"signal,datetime,value\nA,2023-08-14 17:14:38.000,1\nA,2023-08-14 17:14:39.000,2\nA,2023-08-14 17:14:40.000,9\n"
    apply_java_csv(job, csv)
    assert job.summary["match"] == 2 and job.summary["mismatch"] == 1


def test_java_csv_datetime_needs_known_start():
    with pytest.raises(JavaCsvError):
        apply_java_csv(_job(None), b"signal,datetime,value\nA,2023-08-14 17:14:38.000,1\n")


def test_java_csv_bad_datetime_and_missing_time():
    with pytest.raises(JavaCsvError):
        parse_java_csv(b"signal,datetime,value\nA,not-a-date,1\n")
    with pytest.raises(JavaCsvError):
        parse_java_csv(b"signal,value\nA,1\n")


def test_exported_python_csv_roundtrips_as_java_csv():
    job = _job("2023-08-14T17:14:38")
    apply_java_csv(job, rep.python_output_csv(job))
    assert job.summary["mismatch"] == 0 and job.summary["match"] == 3
