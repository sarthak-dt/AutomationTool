import json
import math

import pandas as pd
import pytest

from services.java_decoder_client import JavaResponseError, validate_normalized_payload
from services.java_import import JavaCsvError, apply_java_json, apply_java_upload
from services.java_response import map_java_parser_response, source_file_name
from services.job_runner import FileOutcome, JobResult
from services.models import ComparisonSettings, EngineState, normalize_frame


def _sig(name, ch, ts, vals, validity=None, availability="AVAILABLE", unit="", msg="M"):
    return {"name": name, "unit": unit, "messageName": msg, "channel": ch, "availability": availability,
            "timestamps": ts, "samples": vals, "validity": validity or ["VALID"] * len(ts), "textValues": [None] * len(ts)}


def _payload(signals, **kw):
    d = {"status": "COMPLETED", "file": {"fileName": "Bengaluru\\a.mf4"}, "paginationComplete": True, "diagnostics": ["outcome=X"],
         "signals": {f"k{i}": s for i, s in enumerate(signals)}}
    d.update(kw)
    return d


def test_basic_mapping_and_validation_roundtrip():
    m = map_java_parser_response(_payload([_sig("A", 1, [0.0, 1.0], [48.0, 49.0], unit="V")]))
    df = validate_normalized_payload(m)
    assert list(df["value"]) == [48.0, 49.0] and df["unit"].iloc[0] == "V" and df["message"].iloc[0] == "M"
    assert any("diagnostic: outcome=X" in w for w in m["warnings"])


def test_channels_stay_separate_and_carry_can_id():
    a = _sig("A", 1, [0.0, 2.0], [1.0, 3.0]); a["canIdHex"] = "0x625"
    b = _sig("A", 2, [1.0], [2.0]); b["canId"] = "1573"
    m = map_java_parser_response(_payload([a, b]))
    assert [(x["channel"], x["can_id"]) for x in m["signals"]] == [(1, "0x625"), (2, "0x625")]
    df = validate_normalized_payload(m)
    assert sorted(df["channel"].unique()) == [1, 2] and set(df["can_id"]) == {"0x625"}
    assert not any("merged" in w for w in m["warnings"])


def test_identical_name_channel_and_id_entries_are_merged_by_time():
    m = map_java_parser_response(_payload([_sig("A", 1, [0.0, 2.0], [1.0, 3.0]), _sig("A", 1, [1.0], [2.0])]))
    assert len(m["signals"]) == 1 and m["signals"][0]["timestamps"] == [0.0, 1.0, 2.0]
    assert any("merged by time" in w for w in m["warnings"])


def test_invalid_samples_dropped_nonfinite_nan_unavailable_skipped():
    s = _sig("A", 1, [0.0, 1.0, 2.0], [1.0, {"representation": "non-finite"}, 5.0], validity=["VALID", "VALID", "INVALID"])
    m = map_java_parser_response(_payload([s, _sig("B", 1, [0.0], [1.0], availability="MISSING")]))
    assert len(m["signals"]) == 1 and m["signals"][0]["timestamps"] == [0.0, 1.0]
    assert math.isnan(m["signals"][0]["values"][1])
    assert any("not used" in w for w in m["warnings"]) and any("skipped" in w for w in m["warnings"])


def test_rejects_bad_status_and_shapes():
    with pytest.raises(JavaResponseError):
        map_java_parser_response(_payload([], status="FAILED"))
    with pytest.raises(JavaResponseError):
        map_java_parser_response([1])
    with pytest.raises(JavaResponseError):
        map_java_parser_response(_payload([_sig("A", 1, [0.0, 1.0], [1.0])]))


def test_partial_pagination_flagged():
    m = map_java_parser_response(_payload([_sig("A", 1, [0.0], [1.0])], paginationComplete=False))
    assert any("PARTIAL" in w for w in m["warnings"])


def test_source_file_name():
    assert source_file_name({"file": {"fileName": "x.mf4"}}) == "x.mf4"
    assert source_file_name({"measurementFiles": ["y.mf4"]}) == "y.mf4"
    assert source_file_name({}) is None


def _job(name="a.mf4"):
    df = normalize_frame(pd.DataFrame({"signal": "A", "timestamp": [0.0, 1.0, 2.0], "value": [48.0, 48.0, 48.0], "unit": "", "message": "", "is_discrete": False}))
    job = JobResult(job_id="J", started_at="x", settings=ComparisonSettings().to_dict(), python_data={name: df})
    job.files.append(FileOutcome(name=name, source="local", size=1, python_state=EngineState.OK))
    return job


def test_apply_json_compares_and_labels_origin():
    job = _job()
    data = json.dumps(_payload([_sig("A", 1, [0.0, 1.0, 2.0], [48.0, 48.0, 50.0])])).encode()
    apply_java_upload(job, "r.json", data)
    assert job.summary["match"] == 2 and job.summary["mismatch"] == 1
    assert "JSON" in job.java_note and "not from a live" in job.java_note


def test_apply_json_different_file_name_single_file_job_ok_multi_file_rejected():
    job = _job("other.mf4")
    apply_java_json(job, json.dumps(_payload([_sig("A", 1, [0.0], [48.0])])).encode())
    assert job.comparison_performed
    job2 = _job("one.mf4")
    df = job2.python_data["one.mf4"]
    job2.python_data["two.mf4"] = df
    job2.files.append(FileOutcome(name="two.mf4", source="local", size=1, python_state=EngineState.OK))
    with pytest.raises(JavaCsvError):
        apply_java_json(job2, json.dumps(_payload([_sig("A", 1, [0.0], [48.0])])).encode())


def test_apply_json_errors():
    with pytest.raises(JavaCsvError):
        apply_java_json(_job(), b"{not json")
    with pytest.raises(JavaCsvError):
        apply_java_json(_job(), json.dumps(_payload([], status="ERROR")).encode())


def test_upload_dispatches_csv():
    job = _job()
    apply_java_upload(job, "j.csv", b"signal,timestamp,value\nA,0,48\nA,1,48\nA,2,48\n")
    assert job.summary["match"] == 3
