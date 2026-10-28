"""Orchestrates one comparison job: download -> Python decode -> Java decode -> compare.

Both engines receive the *same* local measurement file, the *same* ordered DBC
files and the *same* ``ComparisonSettings``. All temporary files live in a
``JobWorkspace`` that is removed when the job ends (even on errors).
"""
from __future__ import annotations

import time
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any, Callable

import pandas as pd

from config import AppConfig
from services import comparison_engine as ce
from services.dbc_service import Conflict, DbcFile, detect_conflicts
from services.file_service import JobWorkspace, ValidationError, sanitize_filename
from services.java_decoder_client import JavaDecoderClient
from services.logging_utils import get_logger, redact_text
from services.models import ComparisonSettings, DecoderResult, EngineState
from services.python_decoder import decode_measurement
from services.s3_service import S3Error, S3Object, S3Service

log = get_logger("job")

ProgressCB = Callable[[float, str], None]


@dataclass
class MeasurementRef:
    """A measurement file to process: from S3 (``s3_object``) or a local upload (``data``)."""

    name: str
    size: int
    s3_object: S3Object | None = None
    data: bytes | None = field(default=None, repr=False)

    @property
    def source(self) -> str:
        return "s3" if self.s3_object is not None else "local"


@dataclass
class FileOutcome:
    name: str
    source: str
    size: int
    python_state: str = ""
    java_state: str = ""
    python_error: str | None = None
    java_error: str | None = None
    warnings: list[str] = field(default_factory=list)
    python_stats: dict[str, Any] = field(default_factory=dict)
    java_stats: dict[str, Any] = field(default_factory=dict)
    comparison_performed: bool = False
    comparison_reason: str = ""
    seconds: float = 0.0
    error: str | None = None  # e.g. download failure


@dataclass
class JobResult:
    job_id: str
    started_at: str
    finished_at: str = ""
    duration_s: float = 0.0
    settings: dict[str, Any] = field(default_factory=dict)
    s3_context: dict[str, Any] = field(default_factory=dict)  # bucket/folder only, no credentials
    dbc_names: list[str] = field(default_factory=list)
    conflicts: list[dict[str, str]] = field(default_factory=list)
    files: list[FileOutcome] = field(default_factory=list)
    rows: pd.DataFrame = field(default_factory=lambda: pd.DataFrame(columns=ce.ROW_COLUMNS))
    signal_summary: pd.DataFrame = field(default_factory=pd.DataFrame)
    summary: dict[str, Any] = field(default_factory=dict)
    python_data: dict[str, pd.DataFrame] = field(default_factory=dict, repr=False)
    java_data: dict[str, pd.DataFrame] = field(default_factory=dict, repr=False)
    java_state: str = EngineState.UNAVAILABLE
    java_note: str = ""
    log_lines: list[str] = field(default_factory=list)
    is_demo: bool = False  # illustrative preview job; never exported or stored

    @property
    def comparison_performed(self) -> bool:
        return any(f.comparison_performed for f in self.files)

    @property
    def python_signal_table(self) -> pd.DataFrame:
        """Per-file/signal statistics of the Python output (always real data)."""
        recs = []
        for fname, df in self.python_data.items():
            if df is None or df.empty:
                continue
            for sig, g in df.groupby("signal", sort=True):
                v = pd.to_numeric(g["value"], errors="coerce")
                recs.append(
                    {
                        "file": fname,
                        "signal": sig,
                        "message": g["message"].iloc[0],
                        "unit": g["unit"].iloc[0],
                        "samples": int(len(g)),
                        "t_start": float(g["timestamp"].min()),
                        "t_end": float(g["timestamp"].max()),
                        "min": float(v.min()) if v.notna().any() else None,
                        "max": float(v.max()) if v.notna().any() else None,
                    }
                )
        return pd.DataFrame(
            recs,
            columns=["file", "signal", "message", "unit", "samples", "t_start", "t_end", "min", "max"],
        )


def new_job_id(now: datetime | None = None) -> str:
    return "CMP-" + (now or datetime.now()).strftime("%Y%m%d-%H%M%S")


def run_job(
    measurements: list[MeasurementRef],
    dbcs: list[DbcFile],
    settings: ComparisonSettings,
    app_config: AppConfig,
    java_client: JavaDecoderClient,
    s3_service: S3Service | None = None,
    s3_context: dict[str, Any] | None = None,
    progress: ProgressCB | None = None,
    python_decode: Callable[..., DecoderResult] = decode_measurement,
) -> JobResult:
    """Run the full comparison workflow. Never raises for per-file problems."""
    settings.validate()
    if not measurements:
        raise ValidationError("Select at least one measurement file.")
    if not dbcs:
        raise ValidationError("Select at least one DBC file.")
    if len(measurements) > app_config.max_files_per_job:
        raise ValidationError(
            f"Too many files for one job ({len(measurements)} > {app_config.max_files_per_job})."
        )
    bad = [d.name for d in dbcs if not d.info.usable]
    if bad:
        raise ValidationError("These DBC files are invalid and cannot be used: " + ", ".join(bad))

    t_job = time.time()
    now = datetime.now()
    job = JobResult(
        job_id=new_job_id(now),
        started_at=now.isoformat(timespec="seconds"),
        settings=settings.to_dict(),
        s3_context=dict(s3_context or {}),
        dbc_names=[d.name for d in dbcs],
    )
    conflicts: list[Conflict] = detect_conflicts(dbcs)
    job.conflicts = [c.as_dict() for c in conflicts]
    for c in conflicts:
        if c.severity in ("error", "warning"):
            job.log_lines.append(f"DBC {c.severity}: {c.detail}")

    def report(frac: float, msg: str) -> None:
        job.log_lines.append(msg)
        log.info(msg)
        if progress:
            try:
                progress(min(max(frac, 0.0), 1.0), msg)
            except Exception:  # UI callback problems must not break the job
                pass

    usable, java_reason = java_client.availability()
    job.java_note = java_reason

    comparisons: list[ce.ComparisonResult] = []
    n = len(measurements)
    with JobWorkspace(app_config.temp_root or None) as ws:
        dbc_paths = [ws.write_bytes("dbc", d.name, d.data) for d in dbcs]
        for i, ref in enumerate(measurements):
            base = i / n
            span = 1.0 / n
            t_file = time.time()
            outcome = FileOutcome(name=ref.name, source=ref.source, size=ref.size)
            job.files.append(outcome)
            local_path: Path | None = None
            try:
                report(base, f"[{i + 1}/{n}] Preparing {ref.name}")
                local_path = _materialize(ws, ref, i, s3_service, app_config)
            except (S3Error, ValidationError, OSError) as exc:
                outcome.error = redact_text(exc)
                outcome.warnings.append(f"File skipped: {outcome.error}")
                report(base + span, f"[{i + 1}/{n}] {ref.name}: {outcome.error}")
                outcome.seconds = round(time.time() - t_file, 2)
                continue

            report(base + span * 0.15, f"[{i + 1}/{n}] Python decoding {ref.name}")
            py = python_decode(local_path, dbc_paths, settings)
            report(base + span * 0.55, f"[{i + 1}/{n}] Java decoding {ref.name} ({'API' if usable else 'unavailable'})")
            jr = java_client.decode(local_path, dbc_paths, settings)

            outcome.python_state, outcome.java_state = py.state, jr.state
            outcome.python_error, outcome.java_error = py.error, jr.error
            outcome.python_stats, outcome.java_stats = py.stats, jr.stats
            outcome.warnings.extend(f"python: {w}" for w in py.warnings)
            outcome.warnings.extend(f"java: {w}" for w in jr.warnings)
            job.python_data[ref.name] = py.data
            if jr.state == EngineState.OK:
                job.java_data[ref.name] = jr.data

            report(base + span * 0.85, f"[{i + 1}/{n}] Comparing {ref.name}")
            cmp = ce.compare_results(py, jr, settings, file_name=ref.name)
            comparisons.append(cmp)
            outcome.comparison_performed = cmp.performed
            outcome.comparison_reason = cmp.reason
            outcome.seconds = round(time.time() - t_file, 2)

            # free the (possibly large) downloaded file right away
            if local_path is not None:
                try:
                    local_path.unlink(missing_ok=True)
                except OSError:
                    pass
        report(1.0, "Finalizing results")

    job.rows, job.signal_summary = ce.combine(comparisons)
    performed = any(c.performed for c in comparisons)
    job.summary = ce.summarize(job.rows, performed=performed)
    if job.files and all(f.java_state == EngineState.UNAVAILABLE for f in job.files if f.java_state):
        job.java_state = EngineState.UNAVAILABLE
    elif any(f.java_state == EngineState.ERROR for f in job.files):
        job.java_state = EngineState.ERROR
    else:
        job.java_state = EngineState.OK
    job.duration_s = round(time.time() - t_job, 2)
    job.finished_at = datetime.now().isoformat(timespec="seconds")
    return job


def _materialize(
    ws: JobWorkspace,
    ref: MeasurementRef,
    index: int,
    s3_service: S3Service | None,
    app_config: AppConfig,
) -> Path:
    """Get the measurement file into the workspace (download or write upload)."""
    if ref.s3_object is not None:
        if s3_service is None:
            raise S3Error("S3 is not connected.")
        dest_dir = ws.create() / "measurements"
        return s3_service.download(
            ref.s3_object.key,
            dest_dir,
            index,
            ref.s3_object.size,
            app_config.max_measurement_bytes,
        )
    if ref.data is None:
        raise ValidationError(f"No data for local file '{ref.name}'.")
    if len(ref.data) > app_config.max_measurement_bytes:
        raise ValidationError(f"'{ref.name}' exceeds the size limit.")
    return ws.write_bytes("measurements", f"{index:03d}_{sanitize_filename(ref.name)}", ref.data)
