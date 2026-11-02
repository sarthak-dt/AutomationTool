"""Job orchestration, report generation (CSV/JSON/XLSX/ZIP), history and secret hygiene."""
import io
import json
import zipfile
from unittest.mock import MagicMock

import numpy as np
import pandas as pd
import pytest

from config import AppConfig
from services import history_service as hist
from services import report_service as rep
from services.dbc_service import DbcFile, DbcInfo
from services.file_service import ValidationError
from services.java_decoder_client import JavaApiConfig, JavaDecoderClient
from services.job_runner import MeasurementRef, run_job
from services.models import ComparisonSettings, DecoderResult, EngineState, Status, normalize_frame

from tests.conftest import make_frame


def dbc(name="a.dbc", usable=True):
    return DbcFile(
        name=name, original_name=name, data=b"DBC-BYTES", sha256=name,
        info=DbcInfo(status="VALID" if usable else "INVALID", n_messages=1, error=None if usable else "bad"),
    )


def py_decoder(frames_by_name=None, fail_for=()):
    calls = []

    def decode(path, dbc_paths, settings):
        calls.append((path, list(dbc_paths), settings))
        name = path.name.split("_", 1)[-1]
        assert path.exists(), "file must exist while decoding"
        assert all(p.exists() for p in dbc_paths)
        if name in fail_for:
            return DecoderResult.failed("python", "cannot read file")
        df = (frames_by_name or {}).get(name)
        if df is None:
            df = pd.concat(
                [make_frame("Speed", unit="km/h", v=np.linspace(0.5, 9.5, 10)), make_frame("RPM", unit="rpm", v=np.linspace(1000.5, 1009.5, 10))]
            )
        return DecoderResult("python", EngineState.OK, normalize_frame(df), stats={"samples": len(df)})

    decode.calls = calls
    return decode


class JavaEcho(JavaDecoderClient):
    """Java stand-in returning data derived from a callable (test-only)."""

    def __init__(self, producer):
        super().__init__(JavaApiConfig(enabled=True, base_url="http://x", endpoint_path="/d"))
        self.producer = producer

    def decode(self, measurement_path, dbc_paths, settings):
        return self.producer(measurement_path)


def unavailable_java():
    return JavaDecoderClient(JavaApiConfig())


@pytest.fixture
def cfg(tmp_path):
    return AppConfig(temp_root=str(tmp_path / "tmp"), data_dir=tmp_path / "data")


def local(name, data=b"x" * 10):
    return MeasurementRef(name=name, size=len(data), data=data)


# ------------------------------------------------------------------ job runner
def test_job_with_unavailable_java_has_python_data_but_no_comparison(cfg):
    dec = py_decoder()
    job = run_job([local("a.asc"), local("b.asc")], [dbc()], ComparisonSettings(), cfg, unavailable_java(), python_decode=dec)
    assert job.java_state == EngineState.UNAVAILABLE
    assert not job.comparison_performed
    assert job.rows.empty and job.summary["comparison_performed"] is False
    assert set(job.python_data) == {"a.asc", "b.asc"}
    assert job.python_signal_table["signal"].nunique() == 2
    assert all(f.java_state == EngineState.UNAVAILABLE for f in job.files)
    assert job.java_data == {}


def test_both_engines_receive_same_file_dbcs_and_settings(cfg):
    dec = py_decoder()
    seen = []

    def java(path):
        seen.append(path.name)
        return DecoderResult("java", EngineState.OK, normalize_frame(make_frame("Speed")))

    settings = ComparisonSettings(abs_tol=0.5, resample_1s=True)
    run_job([local("a.asc")], [dbc("x.dbc"), dbc("y.dbc")], settings, cfg, JavaEcho(java), python_decode=dec)
    (path, dbc_paths, s) = dec.calls[0]
    assert path.name == seen[0]
    assert [p.name for p in dbc_paths] == ["x.dbc", "y.dbc"]
    assert s is settings


def test_job_with_real_java_output_compares(cfg):
    py_frames = pd.concat([make_frame("Speed", v=np.linspace(0.5, 9.5, 10)), make_frame("Only_Py")])
    dec = py_decoder({"a.asc": py_frames})
    jv = np.linspace(0.5, 9.5, 10)
    jv[4] += 3
    java = JavaEcho(lambda p: DecoderResult("java", EngineState.OK, normalize_frame(make_frame("Speed", v=jv))))
    job = run_job([local("a.asc")], [dbc()], ComparisonSettings(auto_discrete=False), cfg, java, python_decode=dec)
    assert job.comparison_performed and job.java_state == EngineState.OK
    s = job.summary
    assert s["match"] == 9 and s["mismatch"] == 1 and s["missing_in_java"] == 1
    assert s["match_percentage"] == 90.0
    assert set(job.java_data) == {"a.asc"}


def test_java_all_channels_are_fenced_to_channels_present_in_measurement(cfg):
    channels = [1, 2, 3, 6, 7, 8]
    frame = normalize_frame(pd.DataFrame({
        "signal": ["S"] * len(channels), "timestamp": range(len(channels)),
        "value": range(len(channels)), "channel": channels,
    }))

    def python_decode(path, dbc_paths, settings):
        return DecoderResult("python", EngineState.OK, frame.copy(), stats={"channels_in_file": channels})

    java_channels = [0, 1, 2, 3, 4, 5, 6, 7, 8]
    java_frame = normalize_frame(pd.DataFrame({
        "signal": ["S"] * len(java_channels), "timestamp": range(len(java_channels)),
        "value": range(len(java_channels)), "channel": java_channels,
    }))
    java = JavaEcho(lambda _path: DecoderResult("java", EngineState.OK, java_frame.copy()))
    job = run_job([local("a.asc")], [dbc()], ComparisonSettings(), cfg, java, python_decode=python_decode)

    assert sorted(job.java_data["a.asc"]["channel"].unique()) == channels
    assert not set(job.java_data["a.asc"]["channel"]) & {0, 4, 5}


def test_java_output_respects_the_selected_dbcs_channel_binding(cfg):
    bound_dbc = dbc()
    bound_dbc.channel_bindings = (2,)
    java_frame = normalize_frame(pd.DataFrame({
        "signal": ["Engine_RPM", "Engine_RPM"], "timestamp": [0.0, 1.0],
        "value": [800.0, 900.0], "channel": [1, 2], "can_id": [0x7E8, 0x7E8],
    }))

    def python_decode(path, dbc_paths, settings, **kwargs):
        return DecoderResult("python", EngineState.OK, normalize_frame(None),
                             stats={"channels_in_file": [1, 2]})

    java = JavaEcho(lambda _path: DecoderResult("java", EngineState.OK, java_frame.copy()))
    job = run_job([local("a.asc")], [bound_dbc], ComparisonSettings(), cfg, java,
                  python_decode=python_decode)

    assert set(job.java_data["a.asc"]["channel"]) == {2}


def test_failing_file_does_not_stop_job(cfg):
    dec = py_decoder(fail_for=("bad.asc",))
    java = JavaEcho(lambda p: DecoderResult("java", EngineState.OK, normalize_frame(make_frame("Speed"))))
    job = run_job([local("bad.asc"), local("ok.asc")], [dbc()], ComparisonSettings(), cfg, java, python_decode=dec)
    by = {f.name: f for f in job.files}
    assert by["bad.asc"].python_state == EngineState.ERROR
    assert by["ok.asc"].comparison_performed
    assert (job.rows["status"] == Status.ERROR).any()  # failure visible, not silent


def test_java_error_is_error_row_not_mock(cfg):
    java = JavaEcho(lambda p: DecoderResult.failed("java", "HTTP 500"))
    job = run_job([local("a.asc")], [dbc()], ComparisonSettings(), cfg, java, python_decode=py_decoder())
    assert job.java_state == EngineState.ERROR
    assert set(job.rows["status"]) == {Status.ERROR}


def test_validation_errors(cfg):
    dec = py_decoder()
    with pytest.raises(ValidationError, match="measurement"):
        run_job([], [dbc()], ComparisonSettings(), cfg, unavailable_java(), python_decode=dec)
    with pytest.raises(ValidationError, match="DBC"):
        run_job([local("a.asc")], [], ComparisonSettings(), cfg, unavailable_java(), python_decode=dec)
    with pytest.raises(ValidationError, match="invalid"):
        run_job([local("a.asc")], [dbc(usable=False)], ComparisonSettings(), cfg, unavailable_java(), python_decode=dec)
    small = AppConfig(max_files_per_job=1, temp_root=cfg.temp_root)
    with pytest.raises(ValidationError, match="Too many"):
        run_job([local("a.asc"), local("b.asc")], [dbc()], ComparisonSettings(), small, unavailable_java(), python_decode=dec)


def test_temp_files_are_cleaned_up(cfg, tmp_path):
    run_job([local("a.asc")], [dbc()], ComparisonSettings(), cfg, unavailable_java(), python_decode=py_decoder())
    root = tmp_path / "tmp"
    assert not root.exists() or not list(root.glob("decodercmp_*"))


def test_temp_files_cleaned_even_if_decoder_crashes(cfg, tmp_path):
    def crash(path, dbcs, settings):
        raise RuntimeError("decoder exploded")

    with pytest.raises(RuntimeError):
        run_job([local("a.asc")], [dbc()], ComparisonSettings(), cfg, unavailable_java(), python_decode=crash)
    root = tmp_path / "tmp"
    assert not root.exists() or not list(root.glob("decodercmp_*"))


def test_s3_files_are_downloaded_via_service_and_oversize_skipped(cfg):
    from services.s3_service import S3Error, S3Object

    class FakeS3Svc:
        def __init__(self):
            self.keys = []

        def download(self, key, dest_dir, index, expected_size, max_bytes):
            self.keys.append(key)
            if "huge" in key:
                raise S3Error("'huge.mf4' is too big")
            p = dest_dir / f"{index:03d}_{key.split('/')[-1]}"
            p.write_bytes(b"data")
            return p

    svc = FakeS3Svc()
    refs = [
        MeasurementRef("run.asc", 4, s3_object=S3Object("f/run.asc", 4)),
        MeasurementRef("huge.mf4", 9, s3_object=S3Object("f/huge.mf4", 9)),
    ]
    job = run_job(refs, [dbc()], ComparisonSettings(), cfg, unavailable_java(), s3_service=svc, python_decode=py_decoder(), s3_context={"bucket": "b", "folder": "f/"})
    assert svc.keys == ["f/run.asc", "f/huge.mf4"]
    by = {f.name: f for f in job.files}
    assert by["huge.mf4"].error and "too big" in by["huge.mf4"].error
    assert by["run.asc"].python_state == EngineState.OK
    assert job.s3_context == {"bucket": "b", "folder": "f/"}


def test_progress_callback_reaches_one_and_errors_are_ignored(cfg):
    seen = []

    def cb(frac, msg):
        seen.append(frac)
        raise RuntimeError("ui exploded")

    run_job([local("a.asc")], [dbc()], ComparisonSettings(), cfg, unavailable_java(), progress=cb, python_decode=py_decoder())
    assert seen[-1] == 1.0 and seen == sorted(seen)


def test_dbc_conflicts_are_recorded_in_job(cfg):
    from tests.test_dbc_service import dbc as cat_dbc, msg, sig

    a = cat_dbc("a.dbc", msg(0x100, "M", [sig("S", scale=1)]))
    b = cat_dbc("b.dbc", msg(0x100, "M", [sig("S", scale=2)]))
    a.data = b.data = b"x"
    job = run_job([local("a.asc")], [a, b], ComparisonSettings(), cfg, unavailable_java(), python_decode=py_decoder())
    assert any(c["kind"] == "MESSAGE_ID_CONFLICT" for c in job.conflicts)


# ------------------------------------------------------------------ reports
@pytest.fixture
def compared_job(cfg):
    py_frames = pd.concat([make_frame("Speed", v=np.linspace(0.5, 9.5, 10), unit="km/h"), make_frame("Only_Py")])
    jv = np.linspace(0.5, 9.5, 10)
    jv[2] += 4
    java = JavaEcho(lambda p: DecoderResult("java", EngineState.OK, normalize_frame(make_frame("Speed", v=jv, unit="km/h"))))
    return run_job(
        [local("a.asc")], [dbc()], ComparisonSettings(auto_discrete=False), cfg, java,
        s3_context={"bucket": "my-bucket", "folder": "x/", "secret_access_key": "TOPSECRET", "access_key_id": "AKIAABCDEFGHIJKLMNOP"},
        python_decode=py_decoder({"a.asc": py_frames}),
    )


@pytest.fixture
def python_only_job(cfg):
    return run_job([local("a.asc")], [dbc()], ComparisonSettings(), cfg, unavailable_java(), python_decode=py_decoder())


def test_comparison_csv_roundtrip(compared_job):
    df = pd.read_csv(io.BytesIO(rep.comparison_csv(compared_job)))
    assert list(df.columns) == rep.EXPORT_COLUMNS
    assert list(df["index"]) == list(range(1, len(df) + 1))
    assert set(df["status"]) == {Status.MATCH, Status.MISMATCH, Status.MISSING_IN_JAVA}
    assert len(df) == len(compared_job.rows)


def test_json_report_is_valid_strict_json_with_summary(compared_job):
    data = json.loads(rep.result_json(compared_job), parse_constant=lambda c: pytest.fail(f"non-strict JSON constant {c}"))
    assert data["job_id"] == compared_job.job_id
    assert data["summary"]["mismatch"] == 1 and data["summary"]["missing_in_java"] == 1
    assert data["comparison_performed"] is True
    assert len(data["rows"]) == len(compared_job.rows)
    assert any(r["timestamp"] is None for r in data["rows"])  # NaN -> null


def test_reports_never_contain_credentials(compared_job):
    blob = rep.result_json(compared_job).decode() + json.dumps(rep.job_summary_dict(compared_job))
    with zipfile.ZipFile(io.BytesIO(rep.build_report_zip(compared_job))) as z:
        for n in z.namelist():
            if n.endswith((".json", ".csv", ".txt")):
                blob += z.read(n).decode("utf-8", "ignore")
    assert "TOPSECRET" not in blob
    assert "AKIAABCDEFGHIJKLMNOP" not in blob
    assert "my-bucket" in blob  # non-secret context is kept


def test_zip_contents_when_compared(compared_job):
    with zipfile.ZipFile(io.BytesIO(rep.build_report_zip(compared_job))) as z:
        names = set(z.namelist())
        assert {"README.txt", "job_summary.json", "result.json", "comparison.csv", "signal_summary.csv",
                "python_output.csv", "python_signals.csv", "java_output.csv", "report.xlsx", "run_log.txt"} <= names
        assert z.testzip() is None
        for n in names:
            assert ".." not in n and not n.startswith("/")


def test_zip_when_java_unavailable_has_no_comparison_or_java_files(python_only_job):
    with zipfile.ZipFile(io.BytesIO(rep.build_report_zip(python_only_job))) as z:
        names = set(z.namelist())
        assert "comparison.csv" not in names and "java_output.csv" not in names
        assert "python_output.csv" in names
        readme = z.read("README.txt").decode()
        assert "MOCK / UNAVAILABLE" in readme
        summary = json.loads(z.read("job_summary.json"))
        assert summary["comparison_performed"] is False
        assert "MOCK / UNAVAILABLE" in summary["java_engine_state"]
        assert "no Java values" in summary["disclaimer"]


def test_java_output_csv_none_without_real_java(python_only_job):
    assert rep.java_output_csv(python_only_job) is None
    assert not rep.comparison_available(python_only_job)


def test_python_output_csv_contains_real_samples(python_only_job):
    df = pd.read_csv(io.BytesIO(rep.python_output_csv(python_only_job)))
    assert set(df["signal"]) == {"Speed", "RPM"} and len(df) == 20 and set(df["file"]) == {"a.asc"}


def test_excel_report_sheets(compared_job):
    openpyxl = pytest.importorskip("openpyxl")
    wb = openpyxl.load_workbook(io.BytesIO(rep.excel_bytes(compared_job)))
    assert wb.sheetnames == ["Summary", "Signals", "Comparison", "Python signals"]
    assert wb["Comparison"].max_row == len(compared_job.rows) + 1


def test_graph_files_when_plotly_available(compared_job):
    pytest.importorskip("plotly")
    graphs = rep.graph_html_files(compared_job)
    assert graphs and all(k.startswith("graphs/") and k.endswith(".html") for k in graphs)
    first = next(iter(graphs))
    assert "Speed" in first  # mismatching signal first
    fig = rep.build_signal_figure(compared_job, "a.asc", "Speed")
    assert {t.name for t in fig.data} == {"Python reference", "Java decoder", "Mismatch"}


def test_graph_figure_python_only_has_no_java_trace(python_only_job):
    pytest.importorskip("plotly")
    fig = rep.build_signal_figure(python_only_job, "a.asc", "Speed")
    assert [t.name for t in fig.data] == ["Python reference"]


# ------------------------------------------------------------------ history
def test_history_roundtrip_and_aggregate(tmp_path, compared_job, python_only_job):
    hist.append_job(tmp_path, compared_job)
    hist.append_job(tmp_path, python_only_job)
    entries = hist.load_history(tmp_path)
    assert len(entries) == 2
    agg = hist.aggregate(entries)
    assert agg["jobs"] == 2 and agg["jobs_compared"] == 1 and agg["python_only_jobs"] == 1
    assert agg["mismatches"] == 1 and agg["jobs_clean"] == 0
    assert "TOPSECRET" not in (tmp_path / "job_history.json").read_text()
    hist.clear_history(tmp_path)
    assert hist.load_history(tmp_path) == []


def test_history_survives_corrupt_file(tmp_path):
    (tmp_path / "job_history.json").write_text("{not json")
    assert hist.load_history(tmp_path) == []


# ------------------------------------------------------------------ DBC channels
def test_dbc_channels_are_passed_to_the_python_decoder_only_when_used(cfg):
    seen = []

    def dec(path, dbc_paths, settings, dbc_channels=None):
        seen.append(dbc_channels)
        return py_decoder()(path, dbc_paths, settings)

    d1, d2 = dbc("one.dbc"), dbc("two.dbc")
    d1.channel, d2.channel = 1, 2
    job = run_job([local("a.asc")], [d1, d2], ComparisonSettings(), cfg, unavailable_java(), python_decode=dec)
    assert seen == [[1, 2]]
    assert job.dbc_names == ["one.dbc (channel 1)", "two.dbc (channel 2)"]


def test_same_dbc_can_be_assigned_to_multiple_channels_in_job(cfg):
    seen = []

    def dec(path, dbc_paths, settings, dbc_channels=None):
        seen.append(dbc_channels)
        return py_decoder()(path, dbc_paths, settings)

    one = dbc("multi.dbc")
    one.channel_bindings = (0, 2)
    job = run_job([local("a.mf4")], [one], ComparisonSettings(), cfg, unavailable_java(), python_decode=dec)
    assert seen == [[(0, 2)]]
    assert "channel 0, channel 2" in job.dbc_names[0]


def test_dbcs_on_different_channels_do_not_conflict_but_same_or_any_channel_do():
    from services.dbc_service import MessageDef, detect_conflicts_by_channel

    def with_msg(name, ch, length):
        d = dbc(name)
        d.channel = ch
        d.catalog = [MessageDef(frame_id=0x100, extended=False, name="M", length=length, signals=())]
        return d

    # same CAN ID, different definitions: only a conflict where both DBCs apply to the same channel
    assert detect_conflicts_by_channel([with_msg("a.dbc", 1, 8), with_msg("b.dbc", 2, 4)]) == []
    assert detect_conflicts_by_channel([with_msg("a.dbc", 1, 8), with_msg("b.dbc", 1, 4)]) != []
    assert detect_conflicts_by_channel([with_msg("a.dbc", 1, 8), with_msg("b.dbc", 0, 4)]) != []
    zero = with_msg("zero.dbc", 0, 8)
    zero.channel_bindings = (0,)
    assert detect_conflicts_by_channel([zero, with_msg("one.dbc", 1, 4)]) == []
