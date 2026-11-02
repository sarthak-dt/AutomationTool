"""Readable absolute times (e.g. ``2023-08-14 17:14:38.010``) for the relative sample timestamps.

The decoders and the comparison always work in *seconds since the first frame*
(identical reference for Python and Java). The file's start time is stored in
the decoder stats (``start_time``) and is only used to show / export a readable
date-time. The wall-clock time is shown as stored in the file: MF4 = header
start time, BLF = UTC. If a file has no usable start time (e.g. relative ASC),
the readable time is simply not available and seconds are shown instead.
"""
from __future__ import annotations

from typing import Any

import pandas as pd

FMT = "%Y-%m-%d %H:%M:%S.%f"


def parse_start(value: Any) -> pd.Timestamp | None:
    """ISO string / datetime / epoch -> naive wall-clock Timestamp, or None."""
    if value is None or value == "":
        return None
    try:
        ts = pd.Timestamp(value)
    except (ValueError, TypeError):
        return None
    if pd.isna(ts):
        return None
    if ts.tzinfo is not None:
        ts = ts.tz_localize(None)  # keep the wall-clock time of the file's own offset
    return ts


def epoch_to_start(epoch: float | None) -> str | None:
    """Absolute epoch seconds (BLF) -> ISO string in UTC; None for relative values."""
    if epoch is None or epoch < 1e9:  # before 2001 -> a relative timestamp, not an epoch
        return None
    return pd.Timestamp(epoch, unit="s", tz="UTC").isoformat()


def format_dt(ts: pd.Timestamp) -> str:
    return ts.round("ms").strftime(FMT)[:-3]


def datetimes(start: pd.Timestamp | None, seconds: pd.Series) -> pd.Series:
    """Series of 'YYYY-MM-DD HH:MM:SS.mmm' strings ('' when start is unknown)."""
    if start is None:
        return pd.Series([""] * len(seconds), index=seconds.index, dtype=object)
    dt = start + pd.to_timedelta(pd.to_numeric(seconds, errors="coerce"), unit="s")
    return dt.dt.round("ms").dt.strftime(FMT).str[:-3].fillna("")


def file_starts(job: Any) -> dict[str, pd.Timestamp]:
    out: dict[str, pd.Timestamp] = {}
    for f in getattr(job, "files", []):
        st = parse_start((getattr(f, "python_stats", None) or {}).get("start_time"))
        if st is not None:
            out[f.name] = st
    return out


def display_time(seconds: Any, start: pd.Timestamp | None) -> str:
    if seconds is None or pd.isna(seconds):
        return "—"
    if start is None:
        return f"{seconds:.3f} s"
    return format_dt(start + pd.to_timedelta(float(seconds), unit="s"))
