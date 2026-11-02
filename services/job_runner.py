"""Orchestrates one comparison job: download -> Python decode -> Java decode -> compare.

Both engines receive the *same* local measurement file, the *same* ordered DBC
files and the *same* ``ComparisonSettings``. All temporary files live in a
``JobWorkspace`` that is removed when the job ends (even on errors).
"""
from __future__ import annotations

import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any, Callable

import pandas as pd

from config import AppConfig
from services import comparison_engine as ce
from services.dbc_service import Conflict, DbcFile, dbc_channel_bindings, dbc_channel_label, detect_conflicts_by_channel
from services.file_service import JobWorkspace, ValidationError, sanitize_filename
from services.java_decoder_client import JavaDecoderClient
from services.logging_utils import get_logger, redact_text
from services.models import ComparisonSettings, DecoderResult, EngineState
from services.python_decoder import decode_measurement, discover_can_channels, match_mf4_dbcs
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
            for (ch, cid, sig), g in df.groupby(["channel", "can_id", "signal"], sort=True):
                v = pd.to_numeric(g["value"], errors="coerce")
                recs.append(
                    {
                        "file": fname,
                        "can_id": cid,
                        "channel": int(ch),
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
            columns=["file", "can_id", "channel", "signal", "message", "unit", "samples", "t_start", "t_end", "min", "max"],
        )


def _can_id_value(value: Any) -> int | None:
    try:
        text = str(value).strip()
        return int(text, 16) if text.lower().startswith("0x") else int(text)
    except (TypeError, ValueError):
        return None


def _add_dbc_names(rows: pd.DataFrame, dbcs: list[DbcFile]) -> pd.DataFrame:
    """Annotate comparison rows with the selected DBC owning each CAN signal."""
    if rows is None or rows.empty:
        if rows is not None:
            rows["dbc_name"] = pd.Series(dtype="object")
        return rows

    owners: dict[tuple[int, str, int | None], str] = {}
    for dbc in dbcs:
        binding = dbc_channel_bindings(dbc)
        for message in dbc.catalog:
            for signal in message.signals:
                for channel in (binding if binding is not None else (None,)):
                    owners.setdefault((int(message.frame_id), signal.name, channel), dbc.name)

    can_ids = rows["can_id"].map(_can_id_value)
    rows["dbc_name"] = [
        (
            owners.get((can_id, str(signal), int(py_ch)))
            or owners.get((can_id, str(signal), int(java_ch)))
            or owners.get((can_id, str(signal), None), "")
        ) if can_id is not None else ""
        for can_id, signal, py_ch, java_ch in zip(
            can_ids, rows["signal"], rows["channel"], rows["java_channel"]
        )
    ]
    return rows


def _decode_java_dbcs(
    java_client: JavaDecoderClient,
    measurement_path: Path,
    dbc_paths: list[Path],
    dbcs: list[DbcFile],
    settings: ComparisonSettings,
) -> DecoderResult:
    """Call the single-DBC Java endpoint once per DBC and combine its outputs.

    Java's validate endpoint accepts one ``dbcPath`` per request. Preserve the
    UI's multi-DBC workflow by making sequential Java requests for the same
    measurement. Apply each DBC's channel binding locally and keep the first
    DBC's definition when later DBCs overlap on a CAN ID/channel, matching the
    Python decoder's configured precedence.
    """
    if len(dbc_paths) == 1:
        return java_client.decode(measurement_path, dbc_paths, settings)

    parts: list[pd.DataFrame] = []
    warnings: list[str] = []
    claimed_ids: dict[int, set[int]] = {}
    per_dbc: list[dict[str, Any]] = []

    for dbc_path, dbc in zip(dbc_paths, dbcs):
        result = java_client.decode(measurement_path, [dbc_path], settings)
        if result.state != EngineState.OK:
            if result.state == EngineState.UNAVAILABLE:
                return result
            return DecoderResult.failed(
                "java",
                f"Java decode failed for DBC '{dbc.name}': {result.error or result.state}",
                warnings + result.warnings,
            )

        frame = result.data
        if frame is None or frame.empty:
            warnings.extend(f"{dbc.name}: {warning}" for warning in result.warnings)
            per_dbc.append({"dbc": dbc.name, **result.stats})
            continue

        binding = dbc_channel_bindings(dbc)
        if binding is not None:
            frame = frame[frame["channel"].isin(binding)]
        if settings.decode_channels and not frame.empty:
            frame = frame[frame["channel"].isin(settings.decode_channels)]

        # Java is called once for each DBC, so remove outputs for message IDs
        # already owned by an earlier DBC on the same channel.
        if not frame.empty and dbc.catalog:
            numeric_ids = frame["can_id"].map(_can_id_value)
            keep = pd.Series(True, index=frame.index)
            for channel in frame["channel"].dropna().unique():
                channel_number = int(channel)
                blocked = claimed_ids.get(channel_number, set())
                if blocked:
                    keep &= ~((frame["channel"] == channel_number) & numeric_ids.isin(blocked))
            frame = frame.loc[keep].reset_index(drop=True)

        if not frame.empty:
            parts.append(frame)
            observed_channels = {int(ch) for ch in frame["channel"].dropna().unique()}
            if binding is not None:
                observed_channels.intersection_update(binding)
            if settings.decode_channels:
                observed_channels.intersection_update(settings.decode_channels)
            message_ids = {int(message.frame_id) for message in dbc.catalog}
            for channel in observed_channels:
                claimed_ids.setdefault(channel, set()).update(message_ids)

        warnings.extend(f"{dbc.name}: {warning}" for warning in result.warnings)
        per_dbc.append({"dbc": dbc.name, **result.stats})

    combined = pd.concat(parts, ignore_index=True) if parts else pd.DataFrame()
    if combined.empty and not parts:
        # Keep the standard empty frame schema used by DecoderResult.
        from services.models import empty_frame

        combined = empty_frame()
    return DecoderResult(
        engine="java",
        state=EngineState.OK,
        data=combined,
        warnings=warnings,
        stats={
            "signals": int(combined["signal"].nunique()) if not combined.empty else 0,
            "samples": int(len(combined)),
            "dbc_results": per_dbc,
        },
    )


def new_job_id(now: datetime | None = None) -> str:
    return "CMP-" + (now or datetime.now()).strftime("%Y%m%d-%H%M%S")


def discover_measurement_channels(
    measurements: list[MeasurementRef], app_config: AppConfig,
    s3_service: S3Service | None = None,
) -> dict[str, list[int]]:
    """Discover the channels present in each selected measurement file."""
    found: dict[str, list[int]] = {}
    with JobWorkspace(app_config.temp_root or None) as ws:
        for index, ref in enumerate(measurements):
            local_path = _materialize(ws, ref, index, s3_service, app_config)
            found[ref.name] = discover_can_channels(local_path)
    return found


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
        dbc_names=[f"{d.name} ({dbc_channel_label(d)})" for d in dbcs],
    )
    conflicts: list[Conflict] = detect_conflicts_by_channel(dbcs)
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

            report(base + span * 0.15, f"[{i + 1}/{n}] Decoding {ref.name} with Python and Java in parallel")
            dbc_channels = [dbc_channel_bindings(d) for d in dbcs]
            if any(binding is not None for binding in dbc_channels):
                decoder_bindings = [
                    binding[0] if binding is not None and len(binding) == 1 and binding[0] > 0 else binding
                    for binding in dbc_channels
                ]
                py_args = (local_path, dbc_paths, settings)
                py_kwargs = {"dbc_channels": decoder_bindings}
            else:
                py_args = (local_path, dbc_paths, settings)
                py_kwargs = {}
            with ThreadPoolExecutor(max_workers=2, thread_name_prefix="decoder") as pool:
                py_future = pool.submit(python_decode, *py_args, **py_kwargs)
                java_future = pool.submit(_decode_java_dbcs, java_client, local_path, dbc_paths, dbcs, settings)
                py = py_future.result()
                jr = java_future.result()
            # The Java API has no channel filter parameter. Fence its output to
            # channels observed by the Python file reader, which also prevents
            # missing channel numbers from appearing in an All Channels run.
            actual_channels = py.stats.get("channels_in_file")
            if jr.state == EngineState.OK and actual_channels is not None:
                allowed_channels = set(int(ch) for ch in actual_channels)
                if settings.decode_channels:
                    allowed_channels.intersection_update(settings.decode_channels)
                # The Java validate API accepts paths but has no DBC channel
                # binding parameter. Mirror the selected DBC's binding here;
                # Python already applies it while extracting MF4 CAN frames.
                if len(dbcs) == 1:
                    dbc_binding = dbc_channel_bindings(dbcs[0])
                    if dbc_binding is not None:
                        allowed_channels.intersection_update(dbc_binding)
                jr.data = jr.data[jr.data["channel"].isin(allowed_channels)].reset_index(drop=True)
                jr.stats["channels"] = sorted(int(ch) for ch in jr.data["channel"].unique())
                jr.stats["samples"] = int(len(jr.data))
            report(base + span * 0.55, f"[{i + 1}/{n}] Both decoders finished for {ref.name}; comparing outputs")

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
            outcome.warnings.extend(f"compare: {n}" for n in cmp.notes)
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
    job.rows = _add_dbc_names(job.rows, dbcs)
    performed = any(c.performed for c in comparisons)
    job.summary = ce.summarize(job.rows, performed=performed, signal_summary=job.signal_summary)
    job.rows = ce.compact_rows(job.rows)
    if job.files and all(f.java_state == EngineState.UNAVAILABLE for f in job.files if f.java_state):
        job.java_state = EngineState.UNAVAILABLE
    elif any(f.java_state == EngineState.ERROR for f in job.files):
        job.java_state = EngineState.ERROR
    else:
        job.java_state = EngineState.OK
    job.duration_s = round(time.time() - t_job, 2)
    job.finished_at = datetime.now().isoformat(timespec="seconds")
    return job


def match_dbc_to_measurement(
    ref: MeasurementRef,
    dbcs: list[DbcFile],
    app_config: AppConfig,
    s3_service: S3Service | None = None,
    selected_channels: tuple[int, ...] = (),
) -> pd.DataFrame:
    """Compare selected DBC message IDs with one MF4's raw CAN IDs, by channel."""
    if Path(ref.name).suffix.lower() != ".mf4":
        raise ValidationError("DBC Match to This File requires an MF4 measurement.")
    if not dbcs:
        raise ValidationError("Select at least one valid DBC file.")
    if any(not dbc.info.usable for dbc in dbcs):
        raise ValidationError("DBC Match to This File cannot use invalid DBC files.")

    with JobWorkspace(app_config.temp_root or None) as ws:
        measurement = _materialize(ws, ref, 0, s3_service, app_config)
        dbc_paths = [ws.write_bytes("dbc", dbc.name, dbc.data) for dbc in dbcs]
        bindings = [dbc_channel_bindings(dbc) for dbc in dbcs]
        return match_mf4_dbcs(measurement, dbc_paths, bindings, selected_channels)


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
