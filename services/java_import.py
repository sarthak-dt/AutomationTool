"""Compare a finished job against Java decoder output supplied as a CSV file.

This lets you see the real MATCH / MISMATCH table before the Java API is
connected: export the decoded samples from your Java decoder (any way you
like), save them as CSV and upload them on the Results page.

Expected columns (header names are case-insensitive)::

    signal, value, and timestamp OR datetime   required
    file (or file name)                 required when the job has several files
    unit                                 optional
    is_discrete, message                 optional

``timestamp`` is in seconds, with the same reference as the Python output
(relative to the first frame). Alternatively give ``datetime`` (e.g.
``2023-08-14 17:14:38.010``): it is converted to seconds using the start time
of the decoded file, so it needs the same wall-clock reference as the Python output. The columns of ``python_output.csv`` from the
Reports page are accepted as-is. Nothing is invented: only rows of the uploaded
file are compared.

The decoded-output export labels ``file`` and ``value`` as ``file name`` and
``decode value``; those labels are accepted here as aliases. ``CAN ID`` is
optional and is retained when present.
"""
from __future__ import annotations

import io
from typing import Any

import pandas as pd

from services import comparison_engine as ce
from services import timeutil
from services.java_decoder_client import normalize_frame_keep_values
from services.models import ComparisonSettings, DecoderResult, EngineState

REQUIRED = ("signal", "value")
MAX_BYTES = 200 * 1024 * 1024


class JavaCsvError(ValueError):
    """User-facing problem with the uploaded CSV."""


def settings_from_dict(d: dict[str, Any]) -> ComparisonSettings:
    d = dict(d)
    d["discrete_signals"] = tuple(d.get("discrete_signals", ()))
    s = ComparisonSettings(**d)
    s.validate()
    return s


def parse_java_csv(data: bytes) -> pd.DataFrame:
    """Parse + validate the CSV. Returns a frame with signal/timestamp/value/unit/is_discrete/message[/file]."""
    if not data:
        raise JavaCsvError("The file is empty.")
    if len(data) > MAX_BYTES:
        raise JavaCsvError("The file is larger than 200 MB.")
    try:
        df = pd.read_csv(io.BytesIO(data), dtype=str, keep_default_na=False, encoding="utf-8-sig")
    except Exception as exc:
        raise JavaCsvError(f"Could not read the CSV: {type(exc).__name__}") from exc
    df.columns = [str(c).strip().lower() for c in df.columns]
    aliases = {}
    if "value" not in df.columns and "decode value" in df.columns:
        aliases["decode value"] = "value"
    if "file" not in df.columns and "file name" in df.columns:
        aliases["file name"] = "file"
    if "can_id" not in df.columns and "can id" in df.columns:
        aliases["can id"] = "can_id"
    df = df.rename(columns=aliases)
    missing = [c for c in REQUIRED if c not in df.columns]
    if missing:
        raise JavaCsvError("Missing column(s): " + ", ".join(missing) + ". Required: signal, value and timestamp (or datetime).")
    if "timestamp" not in df.columns and "datetime" not in df.columns:
        raise JavaCsvError("Missing column: timestamp (seconds) or datetime. Required: signal, value and timestamp (or datetime).")
    if df.empty:
        raise JavaCsvError("The CSV has a header but no rows.")
    df["signal"] = df["signal"].str.strip()
    if (df["signal"] == "").any():
        raise JavaCsvError(f"{int((df['signal'] == '').sum())} row(s) have an empty signal name.")
    if "timestamp" not in df.columns or (df["timestamp"].str.strip() == "").all():
        dts = pd.to_datetime(df["datetime"].str.strip(), errors="coerce")
        if dts.isna().any():
            first = int(dts.isna().idxmax()) + 2
            raise JavaCsvError(f"{int(dts.isna().sum())} row(s) have an unreadable datetime (first at line {first}); use e.g. 2023-08-14 17:14:38.010.")
        if getattr(dts.dt, "tz", None) is not None:
            dts = dts.dt.tz_localize(None)
        df["_dt"] = dts
        df["timestamp"] = "0"  # placeholder, replaced per file in apply_java_csv
    ts = pd.to_numeric(df["timestamp"], errors="coerce")
    bad = ts.isna() | ~ts.apply(lambda x: pd.notna(x) and abs(x) != float("inf"))
    if bad.any():
        first = int(bad.idxmax()) + 2  # +2: header line + 1-based
        raise JavaCsvError(f"{int(bad.sum())} row(s) have a non-numeric timestamp (first at line {first}).")
    df["timestamp"] = ts.astype(float)
    vals = df["value"].str.strip()
    numeric = pd.to_numeric(vals.replace("", pd.NA), errors="coerce")
    if (vals == "").sum() == 0 and numeric.notna().all():
        df["value"] = numeric.astype(float)
    elif numeric.notna().any() and (numeric.notna() | (vals == "")).all():
        df["value"] = numeric.astype(float)  # empty cells -> NaN
    else:
        df["value"] = vals.where(vals != "", None)  # text signals
    if "unit" not in df.columns:
        df["unit"] = ""
    if "message" not in df.columns:
        df["message"] = ""
    if "can_id" not in df.columns:
        df["can_id"] = ""
    if "is_discrete" not in df.columns:
        df["is_discrete"] = False
    else:
        df["is_discrete"] = df["is_discrete"].str.strip().str.lower().isin(("1", "true", "yes", "y"))
    cols = ["signal", "timestamp", "value", "unit", "is_discrete", "message", "can_id"] + [c for c in ("_dt", "file") if c in df.columns]
    return df[cols]


def _match_files(df: pd.DataFrame, job: Any) -> dict[str, pd.DataFrame]:
    """Map job file name -> Java frame."""
    names = [f.name for f in job.files if f.python_state == EngineState.OK and job.python_data.get(f.name) is not None]
    if not names:
        raise JavaCsvError("The job has no successfully decoded file to compare.")
    if "file" not in df.columns:
        if len(names) != 1:
            raise JavaCsvError(
                f"The job has {len(names)} files, so the CSV needs a 'file' column with the log file names "
                f"({', '.join(names[:4])}{', ...' if len(names) > 4 else ''})."
            )
        return {names[0]: df.drop(columns=[c for c in ("file",) if c in df.columns])}
    lookup = {n.lower(): n for n in names}
    # job file names may carry a numeric prefix only for temp files; job.files names are the user-visible ones
    out: dict[str, pd.DataFrame] = {}
    unknown = []
    for raw, g in df.groupby(df["file"].str.strip(), sort=False):
        key = str(raw).replace("\\", "/").split("/")[-1].lower()
        if key in lookup:
            out[lookup[key]] = g.drop(columns=["file"])
        else:
            unknown.append(str(raw))
    if unknown:
        raise JavaCsvError(
            "Unknown file name(s) in the 'file' column: " + ", ".join(unknown[:5]) + ". Job files: " + ", ".join(names[:6]) + "."
        )
    return out


def apply_java_csv(job: Any, data: bytes, settings: ComparisonSettings | None = None) -> Any:
    """Compare ``job`` with the Java output in ``data`` (mutates and returns ``job``)."""
    df = parse_java_csv(data)
    frames = _match_files(df, job)
    settings = settings or settings_from_dict(job.settings)
    outcomes = {f.name: f for f in job.files}
    comparisons = []
    java_data: dict[str, pd.DataFrame] = {}
    starts = timeutil.file_starts(job)
    for name, jdf in frames.items():
        jdf = jdf.reset_index(drop=True)
        if "_dt" in jdf.columns:
            if name not in starts:
                raise JavaCsvError(
                    f"'{name}' has no known start time, so a datetime column cannot be converted. Use a 'timestamp' column (seconds since the first frame)."
                )
            jdf["timestamp"] = (jdf["_dt"] - starts[name]).dt.total_seconds()
            jdf = jdf.drop(columns=["_dt"])
        java_frame = normalize_frame_keep_values(jdf)
        py = DecoderResult("python", EngineState.OK, job.python_data[name])
        jr = DecoderResult("java", EngineState.OK, java_frame, stats={"samples": int(len(java_frame)), "source": "csv"})
        cmp = ce.compare_results(py, jr, settings, file_name=name)
        comparisons.append(cmp)
        java_data[name] = java_frame
        o = outcomes[name]
        o.java_state, o.java_error, o.java_stats = EngineState.OK, None, jr.stats
        o.comparison_performed, o.comparison_reason = True, ""
    skipped = [f.name for f in job.files if f.name not in frames]
    job.rows, job.signal_summary = ce.combine(comparisons)
    job.summary = ce.summarize(job.rows, performed=True)
    job.java_data = java_data
    job.java_state = EngineState.OK
    job.java_note = "Java output imported from an uploaded CSV file (not from the Java API)."
    job.log_lines.append(f"Imported Java output CSV: {len(df)} rows, compared {len(frames)} file(s).")
    for name in skipped:
        outcomes[name].warnings.append("java: no rows for this file in the uploaded CSV; not compared.")
        outcomes[name].comparison_performed = False
    return job
