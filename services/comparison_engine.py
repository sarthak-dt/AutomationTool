"""Comparison engine: Python reference decoder vs Java decoder.

Rules (also documented in README.md)
====================================
Keys
    Signals are matched per (file, signal name, channel). Name comparison is exact.
    A MATCH requires known, equal CAN channel numbers on both sides; channel 0
    is valid for MF4 when a CAN ID is present. Same-name samples from different
    channels, or with unknown channel metadata, are NOT_COMPARABLE. A known channel difference is MISMATCH.
    Channel gate (see services/channel_gate.py): when a Python/Java channel is selected, only
    that channel of each output is compared. Different numbers are MISMATCH (values are not
    compared across different physical channels).
    ``can_id`` is informational (shown in the results, never used for matching).

Statuses (per row)
    MATCH              values agree within tolerance
    MISMATCH           values differ beyond tolerance
    MISSING_IN_PYTHON  signal (or a sample) exists only in the Java output
    MISSING_IN_JAVA    signal (or a sample) exists only in the Python output
    NOT_COMPARABLE     numeric vs text signal, strict unit mismatch, no overlap
    ERROR              the comparison of that signal raised an exception

    A signal that is absent from one engine produces exactly one
    ``MISSING_IN_*`` row (timestamp empty). It is never counted as a numeric
    mismatch.

Tolerance (numeric, continuous signals)
    match if  |p - j| <= max(abs_tol, rel_tol * max(|p|, |j|))
    Equal values (including equal +/-inf) always match.

Discrete signals (exact comparison, tolerance 0, zero-order-hold when resampling)
    A signal is discrete if (a) either engine flags it ``is_discrete``
    (value table / single bit), (b) its name is in ``discrete_signals``, or
    (c) ``auto_discrete`` is on and *both* outputs contain only integer values
    with at most ``discrete_max_unique`` distinct values.
    Text-valued signals are compared as stripped strings.

Timestamp alignment (resample off)
    Each Python sample is paired with the nearest Java sample within
    ``time_tol`` seconds. Python samples without partner -> MISSING_IN_JAVA
    (sample level); Java samples never paired -> MISSING_IN_PYTHON.

1-second resampling (resample on)
    Both outputs are evaluated on the grid t = n * 1.0 s that lies inside the
    overlap of the two time ranges. Continuous signals: linear interpolation.
    Discrete/text signals: last value at or before t (zero-order hold).

NaN values
    both NaN -> MATCH; one NaN -> MISSING_IN_<that engine>;
    ``missing_policy="skip"`` drops rows where either value is NaN.

Units
    Units are compared when both are non-empty. A difference is noted in
    ``note``; with ``unit_policy="strict"`` the signal becomes NOT_COMPARABLE.
"""
from __future__ import annotations

import math
from typing import Any

import numpy as np
import pandas as pd

from services.channel_gate import channel_gate
from services.models import (
    ComparisonResult,
    ComparisonSettings,
    DecoderResult,
    EngineState,
    Status,
)

ROW_COLUMNS = [
    "file",
    "can_id",
    "channel",
    "java_channel",
    "signal",
    "timestamp",
    "python_value",
    "java_value",
    "abs_diff",
    "rel_diff",
    "tolerance",
    "status",
    "unit_python",
    "unit_java",
    "discrete",
    "note",
]


# ---------------------------------------------------------------------------
# Per-signal side preparation
# ---------------------------------------------------------------------------
class _Side:
    __slots__ = ("t", "v", "kind", "unit", "flag_discrete", "dropped_ts")

    def __init__(self, df: pd.DataFrame):
        d = df
        ts = pd.to_numeric(d["timestamp"], errors="coerce")
        keep = ts.notna().to_numpy()
        self.dropped_ts = int((~keep).sum())
        d = d.loc[keep]
        ts = ts.loc[keep].to_numpy(dtype=float)
        order = np.argsort(ts, kind="mergesort")
        self.t = ts[order]
        raw = d["value"].reset_index(drop=True).iloc[order].reset_index(drop=True)
        self.kind, self.v = _coerce_values(raw)
        units = [u for u in d["unit"].astype(str).tolist() if u.strip()]
        self.unit = units[0].strip() if units else ""
        self.flag_discrete = bool(d["is_discrete"].astype(bool).any()) if len(d) else False

    @property
    def n(self) -> int:
        return int(self.t.size)


def _coerce_values(values: pd.Series) -> tuple[str, np.ndarray]:
    if values.dtype == bool:
        return "numeric", values.astype(float).to_numpy()
    if pd.api.types.is_numeric_dtype(values):
        return "numeric", values.to_numpy(dtype=float)
    notnull = values.notna()
    conv = pd.to_numeric(values, errors="coerce")
    if bool((conv.notna() | ~notnull).all()):
        return "numeric", conv.to_numpy(dtype=float)
    arr = values.astype(object).where(notnull, None).map(lambda x: None if x is None else str(x).strip())
    return "text", arr.to_numpy(dtype=object)


def _is_integer_valued(v: np.ndarray) -> bool:
    finite = v[np.isfinite(v)]
    return finite.size > 0 and bool(np.all(np.mod(finite, 1) == 0))


def _decide_discrete(name: str, p: _Side, j: _Side, s: ComparisonSettings) -> bool:
    if p.kind == "text" or j.kind == "text":
        return True
    if p.flag_discrete or j.flag_discrete or name in set(s.discrete_signals):
        return True
    if s.auto_discrete:
        for side in (p, j):
            if not _is_integer_valued(side.v):
                return False
            if len(np.unique(side.v[np.isfinite(side.v)])) > s.discrete_max_unique:
                return False
        return True
    return False


# ---------------------------------------------------------------------------
# Alignment
# ---------------------------------------------------------------------------
def _nearest_pairs(p_t: np.ndarray, j_t: np.ndarray, tol: float) -> np.ndarray:
    """For every p_t return index into j_t of the nearest sample within tol, else -1."""
    if j_t.size == 0:
        return np.full(p_t.size, -1, dtype=int)
    idx = np.searchsorted(j_t, p_t)
    left = np.clip(idx - 1, 0, j_t.size - 1)
    right = np.clip(idx, 0, j_t.size - 1)
    dl = np.abs(p_t - j_t[left])
    dr = np.abs(p_t - j_t[right])
    best = np.where(dl <= dr, left, right)
    dist = np.minimum(dl, dr)
    return np.where(dist <= tol + 1e-12, best, -1)


def _resample(side: _Side, grid: np.ndarray, discrete: bool, skip_nan: bool) -> np.ndarray:
    t, v = side.t, side.v
    if side.kind == "text" or discrete:
        idx = np.searchsorted(t, grid + 1e-12, side="right") - 1
        idx = np.clip(idx, 0, t.size - 1)
        return v[idx]
    ok = np.isfinite(v)
    if not ok.all():
        t, v = t[ok], v[ok]
    if t.size == 0:
        return np.full(grid.size, np.nan)
    return np.interp(grid, t, v)


# ---------------------------------------------------------------------------
# Classification
# ---------------------------------------------------------------------------
def _classify_numeric(
    pv: np.ndarray, jv: np.ndarray, discrete: bool, s: ComparisonSettings
) -> dict[str, np.ndarray]:
    pn, jn = np.isnan(pv), np.isnan(jv)
    with np.errstate(invalid="ignore"):
        diff = np.abs(pv - jv)
        scale = np.maximum(np.abs(pv), np.abs(jv))
        tol = np.zeros_like(diff) if discrete else np.maximum(s.abs_tol, s.rel_tol * scale)
        equal = pv == jv
        within = diff <= tol
        rel = np.where(scale > 0, diff / np.where(scale > 0, scale, 1.0), 0.0)
    status = np.where(equal | within, Status.MATCH, Status.MISMATCH).astype(object)
    status[pn & jn] = Status.MATCH
    status[pn & ~jn] = Status.MISSING_IN_PYTHON
    status[~pn & jn] = Status.MISSING_IN_JAVA
    diff = np.where(pn | jn, np.nan, np.where(equal, 0.0, diff))
    rel = np.where(pn | jn, np.nan, np.where(equal, 0.0, rel))
    tol_out = np.where(pn | jn, np.nan, tol)
    return {"status": status, "abs_diff": diff, "rel_diff": rel, "tolerance": tol_out}


def _classify_text(pv: np.ndarray, jv: np.ndarray) -> dict[str, np.ndarray]:
    n = pv.size
    status = np.empty(n, dtype=object)
    for i in range(n):
        a, b = pv[i], jv[i]
        if a is None and b is None:
            status[i] = Status.MATCH
        elif a is None:
            status[i] = Status.MISSING_IN_PYTHON
        elif b is None:
            status[i] = Status.MISSING_IN_JAVA
        else:
            status[i] = Status.MATCH if a == b else Status.MISMATCH
    nan = np.full(n, np.nan)
    return {"status": status, "abs_diff": nan, "rel_diff": nan.copy(), "tolerance": nan.copy()}


def _row_frame(
    file: str,
    signal: str,
    ts: np.ndarray,
    pv: Any,
    jv: Any,
    cls: dict[str, np.ndarray] | None,
    status: Any,
    p_unit: str,
    j_unit: str,
    discrete: bool,
    note: Any,
) -> pd.DataFrame:
    n = len(ts)
    df = pd.DataFrame(
        {
            "file": file,
            "can_id": "",
            "channel": 0,
            "java_channel": 0,
            "signal": signal,
            "timestamp": ts,
            "python_value": pv,
            "java_value": jv,
            "abs_diff": cls["abs_diff"] if cls else np.full(n, np.nan),
            "rel_diff": cls["rel_diff"] if cls else np.full(n, np.nan),
            "tolerance": cls["tolerance"] if cls else np.full(n, np.nan),
            "status": cls["status"] if cls else status,
            "unit_python": p_unit,
            "unit_java": j_unit,
            "discrete": discrete,
            "note": note,
        }
    )
    return df[ROW_COLUMNS]


def _single_row(
    file: str, signal: str, status: str, note: str, p_unit: str = "", j_unit: str = "", discrete: bool = False
) -> pd.DataFrame:
    return _row_frame(
        file, signal, np.array([np.nan]), np.array([np.nan]), np.array([np.nan]), None, status, p_unit, j_unit, discrete, note
    )


# ---------------------------------------------------------------------------
# One signal
# ---------------------------------------------------------------------------
def _compare_signal(
    file: str, name: str, p_df: pd.DataFrame, j_df: pd.DataFrame, s: ComparisonSettings
) -> list[pd.DataFrame]:
    p, j = _Side(p_df), _Side(j_df)
    out: list[pd.DataFrame] = []
    base_notes: list[str] = []
    if p.dropped_ts or j.dropped_ts:
        base_notes.append(f"dropped samples with invalid timestamp (python={p.dropped_ts}, java={j.dropped_ts})")

    if p.n == 0 and j.n == 0:
        return [_single_row(file, name, Status.NOT_COMPARABLE, "no valid samples in either engine", p.unit, j.unit)]
    if p.n == 0:
        return [_single_row(file, name, Status.MISSING_IN_PYTHON, "signal has no valid samples in Python output", p.unit, j.unit)]
    if j.n == 0:
        return [_single_row(file, name, Status.MISSING_IN_JAVA, "signal has no valid samples in Java output", p.unit, j.unit)]

    if p.kind != j.kind:
        return [
            _single_row(
                file, name, Status.NOT_COMPARABLE,
                f"value types differ (python={p.kind}, java={j.kind})", p.unit, j.unit,
            )
        ]

    unit_note = ""
    if p.unit and j.unit and p.unit != j.unit:
        unit_note = f"unit mismatch (python='{p.unit}', java='{j.unit}')"
        if s.unit_policy == "strict":
            return [_single_row(file, name, Status.NOT_COMPARABLE, unit_note + " [strict unit policy]", p.unit, j.unit)]
        base_notes.append(unit_note)

    discrete = _decide_discrete(name, p, j, s)
    text = p.kind == "text"

    def classify(pv: np.ndarray, jv: np.ndarray) -> dict[str, np.ndarray]:
        return _classify_text(pv, jv) if text else _classify_numeric(pv.astype(float), jv.astype(float), discrete, s)

    note_base = "; ".join(base_notes)

    if s.resample_1s:
        lo = max(p.t[0], j.t[0])
        hi = min(p.t[-1], j.t[-1])
        start = math.ceil(lo - 1e-9)
        end = math.floor(hi + 1e-9)
        if end < start:
            return [
                _single_row(
                    file, name, Status.NOT_COMPARABLE,
                    "no common 1 s grid point inside the overlapping time range", p.unit, j.unit, discrete,
                )
            ]
        grid = np.arange(start, end + 1, dtype=float)
        pv = _resample(p, grid, discrete, s.missing_policy == "skip")
        jv = _resample(j, grid, discrete, s.missing_policy == "skip")
        cls = classify(pv, jv)
        frame = _row_frame(file, name, grid, pv, jv, cls, None, p.unit, j.unit, discrete, note_base)
        if s.missing_policy == "skip":
            frame = frame[~(pd.isna(pv) | pd.isna(jv))]
        out.append(frame)
    else:
        pair = _nearest_pairs(p.t, j.t, s.time_tol)
        matched = pair >= 0
        jv_for_p = np.full(p.n, np.nan, dtype=object if text else float)
        if text:
            jv_for_p[:] = None
        jv_for_p[matched] = j.v[pair[matched]]
        cls = classify(p.v, jv_for_p)
        status = cls["status"]
        notes = np.full(p.n, note_base, dtype=object)
        unmatched_p = ~matched
        status[unmatched_p] = Status.MISSING_IN_JAVA
        for arr in ("abs_diff", "rel_diff", "tolerance"):
            cls[arr] = np.where(unmatched_p, np.nan, cls[arr])
        notes[unmatched_p] = _join(note_base, f"no Java sample within {s.time_tol:g} s")
        frame = _row_frame(file, name, p.t, p.v, jv_for_p, cls, None, p.unit, j.unit, discrete, notes)
        if s.missing_policy == "skip":
            # drop NaN *values* of paired samples; alignment gaps stay visible
            frame = frame[~(matched & (pd.isna(p.v) | pd.isna(jv_for_p)))]
        out.append(frame)

        used = np.zeros(j.n, dtype=bool)
        used[pair[matched]] = True
        un_j = ~used
        if un_j.any():
            m = int(un_j.sum())
            pv_none = np.full(m, np.nan, dtype=object if text else float)
            if text:
                pv_none[:] = None
            out.append(
                _row_frame(
                    file, name, j.t[un_j], pv_none, j.v[un_j], None, Status.MISSING_IN_PYTHON,
                    p.unit, j.unit, discrete,
                    _join(note_base, f"no Python sample within {s.time_tol:g} s"),
                )
            )

    return [f for f in out if len(f)]


def _join(a: str, b: str) -> str:
    return f"{a}; {b}" if a else b


# ---------------------------------------------------------------------------
# One file
# ---------------------------------------------------------------------------
def compare_results(
    py: DecoderResult, java: DecoderResult, settings: ComparisonSettings, file_name: str = ""
) -> ComparisonResult:
    """Compare the outputs of both engines for one measurement file."""
    settings.validate()
    res = ComparisonResult(file=file_name, performed=False)

    if java.state == EngineState.UNAVAILABLE:
        res.reason = f"Java engine MOCK / UNAVAILABLE: {java.error or 'not configured'}. No comparison was performed."
        return res
    if py.state != EngineState.OK:
        res.reason = f"Python engine failed: {py.error}"
        res.rows = _single_row(file_name, "(file)", Status.ERROR, f"Python decoder error: {py.error}")
        res.signal_summary = summarize_signals(res.rows)
        return res
    if java.state == EngineState.ERROR:
        res.reason = f"Java engine failed: {java.error}"
        res.rows = _single_row(file_name, "(file)", Status.ERROR, f"Java decoder error: {java.error}")
        res.signal_summary = summarize_signals(res.rows)
        return res

    res.performed = True
    p_data, j_data = py.data, java.data
    gate = channel_gate(settings)
    channel_metadata_unverified = False
    metadata_python_channel = metadata_java_channel = 0
    channel_metadata_note = ""
    if gate.active:
        if not gate.compatible:
            return _blocked_by_channel_gate(res, file_name, p_data, j_data, gate)
        p_data, p_note = _select_channel(p_data, gate.python, "Python")
        j_data, j_note = _select_channel(j_data, gate.java, "Java")
        res.notes.extend(n for n in (p_note, j_note) if n)
        use_channel = True
        py_has_channels = _has_channel_data(p_data)
        java_has_channels = _has_channel_data(j_data)
    else:
        py_has_channels = _has_channel_data(p_data)
        java_has_channels = _has_channel_data(j_data)
        use_channel = py_has_channels and java_has_channels
    # Selecting a specific channel is an explicit user assignment for outputs
    # whose source metadata is absent. _select_channel records that assumption
    # in p_note/j_note, and rows below carry it into the exported Message field.
    channel_metadata_unverified = (
        not gate.active and not p_data.empty and not j_data.empty and (not py_has_channels or not java_has_channels)
    )
    if channel_metadata_unverified:
        if not py_has_channels and not java_has_channels:
            channel_metadata_note = "Neither decoder reported a known CAN channel; channel identity is unverified."
        elif not py_has_channels:
            channel_metadata_note = "Python channel is unknown (reported as 0); channel identity is unverified."
        else:
            channel_metadata_note = "Java channel is unknown (reported as 0); channel identity is unverified."
        res.notes.append(channel_metadata_note + " Values are NOT_COMPARABLE.")
        py_channel_values = pd.to_numeric(p_data["channel"], errors="coerce").dropna().unique()
        java_channel_values = pd.to_numeric(j_data["channel"], errors="coerce").dropna().unique()
        metadata_python_channel = int(py_channel_values[0]) if py_has_channels and len(py_channel_values) == 1 else 0
        metadata_java_channel = int(java_channel_values[0]) if java_has_channels and len(java_channel_values) == 1 else 0
    if gate.active:
        p_groups = {(name, gate.python): group for name, group in p_data.groupby("signal", sort=False)} if not p_data.empty else {}
        j_groups = {(name, gate.python): group for name, group in j_data.groupby("signal", sort=False)} if not j_data.empty else {}
    elif use_channel:
        p_groups = {k: g for k, g in p_data.groupby(["signal", "channel"], sort=False)} if not p_data.empty else {}
        j_groups = {k: g for k, g in j_data.groupby(["signal", "channel"], sort=False)} if not j_data.empty else {}
    else:
        # When neither side has channel metadata, compare exact signal names.
        p_groups = {(name, 0): group for name, group in p_data.groupby("signal", sort=False)} if not p_data.empty else {}
        j_groups = {(name, 0): group for name, group in j_data.groupby("signal", sort=False)} if not j_data.empty else {}
    p_names, j_names = set(p_groups), set(j_groups)

    frames: list[pd.DataFrame] = []
    reported_channel_pairs: set[tuple[str, int, int]] = set()
    for key in sorted(p_names | j_names):
        name, ch = key
        channel_pair: tuple[int, int] | None = None
        try:
            if key not in j_groups:
                g = p_groups[key]
                peers = [peer for peer in j_groups if peer[0] == name]
                if (use_channel or channel_metadata_unverified) and peers:
                    peer = peers[0]
                    channel_pair = (int(ch), int(peer[1]))
                    pair_id = (name, *channel_pair)
                    if pair_id in reported_channel_pairs:
                        part = []
                    else:
                        reported_channel_pairs.add(pair_id)
                        status = Status.NOT_COMPARABLE if channel_metadata_unverified else Status.MISMATCH
                        message = (channel_metadata_note if channel_metadata_unverified else
                                   f"Channel mismatch: Python decoded on channel {channel_pair[0]} while Java decoded on channel {channel_pair[1]}; channel assignments differ, so this row cannot be a MATCH.")
                        if channel_metadata_unverified:
                            part = [_single_row(file_name, name, status, message, _first_unit(g), _first_unit(j_groups[peer]))]
                        else:
                            part = _compare_signal(file_name, name, g, j_groups[peer], settings)
                            for row in part:
                                row["status"] = Status.MISMATCH
                                row["note"] = message
                else:
                    u = _first_unit(g)
                    part = [_single_row(file_name, name, Status.MISSING_IN_JAVA, "signal not present in Java output", u, "")]
            elif key not in p_groups:
                g = j_groups[key]
                peers = [peer for peer in p_groups if peer[0] == name]
                if (use_channel or channel_metadata_unverified) and peers:
                    peer = peers[0]
                    channel_pair = (int(peer[1]), int(ch))
                    pair_id = (name, *channel_pair)
                    if pair_id in reported_channel_pairs:
                        part = []
                    else:
                        reported_channel_pairs.add(pair_id)
                        status = Status.NOT_COMPARABLE if channel_metadata_unverified else Status.MISMATCH
                        message = (channel_metadata_note if channel_metadata_unverified else
                                   f"Channel mismatch: Python decoded on channel {channel_pair[0]} while Java decoded on channel {channel_pair[1]}; channel assignments differ, so this row cannot be a MATCH.")
                        if channel_metadata_unverified:
                            part = [_single_row(file_name, name, status, message, _first_unit(p_groups[peer]), _first_unit(g))]
                        else:
                            part = _compare_signal(file_name, name, p_groups[peer], g, settings)
                            for row in part:
                                row["status"] = Status.MISMATCH
                                row["note"] = message
                else:
                    u = _first_unit(g)
                    part = [_single_row(file_name, name, Status.MISSING_IN_PYTHON, "signal not present in Python output", "", u)]
            elif channel_metadata_unverified:
                part = [
                    _single_row(
                        file_name,
                        name,
                        Status.NOT_COMPARABLE,
                        channel_metadata_note,
                        _first_unit(p_groups[key]),
                        _first_unit(j_groups[key]),
                    )
                ]
            else:
                part = _compare_signal(file_name, name, p_groups[key], j_groups[key], settings)
        except Exception as exc:  # never let one signal abort the file
            part = [_single_row(file_name, name, Status.ERROR, f"{type(exc).__name__}: {exc}")]
        cid = _first_can_id(p_groups.get(key), j_groups.get(key))
        jg = j_groups.get(key)
        if gate.active and jg is not None:
            jch = gate.java
        elif jg is not None and "java_channel" in jg.columns:
            jch = int(jg["java_channel"].iloc[0])
        else:
            jch = int(jg["channel"].iloc[0]) if jg is not None else 0
        selection_assumption = "; ".join(
            n.replace("assumed to be", "assigned to")
            for n in (p_note, j_note)
            if n and "assumed to be" in n
        ) if gate.active else ""
        for f in part:
            if channel_metadata_unverified:
                f["can_id"], f["channel"], f["java_channel"] = (
                    cid, metadata_python_channel, metadata_java_channel
                )
            elif channel_pair is not None:
                f["can_id"], f["channel"], f["java_channel"] = cid, *channel_pair
            else:
                f["can_id"], f["channel"], f["java_channel"] = cid, int(ch), jch
            if selection_assumption:
                f["note"] = _join(str(f.get("note") or ""), selection_assumption)
        frames.extend(part)
    res.rows = pd.concat(frames, ignore_index=True) if frames else pd.DataFrame(columns=ROW_COLUMNS)
    frames.clear()
    res.signal_summary = summarize_signals(res.rows)
    if not p_names and not j_names:
        res.notes.append("Neither engine produced any signals.")
    return res


def _select_channel(df: pd.DataFrame, channel: int, who: str) -> tuple[pd.DataFrame, str]:
    """Keep only the rows of ``channel``. Output without any channel info is assumed to be that channel."""
    if df.empty:
        return df, ""
    if not _has_channel_data(df):
        return df, f"{who} output has no channel information; assumed to be channel {channel} as selected."
    out = df[df["channel"] == channel]
    if out.empty:
        have = sorted(int(c) for c in df["channel"].unique())
        return out, f"{who} output has no samples on channel {channel} (channels present: {have})."
    return out, ""


def _has_channel_data(df: pd.DataFrame) -> bool:
    if df.empty:
        return False
    if "channel_known" in df.columns:
        return bool(df["channel_known"].fillna(False).astype(bool).any())
    channels = pd.to_numeric(df["channel"], errors="coerce")
    return bool((channels > 0).any() or ((channels == 0) & df["can_id"].astype(str).str.strip().ne("")).any())


def _blocked_by_channel_gate(res: ComparisonResult, file_name: str, p_data, j_data, gate) -> ComparisonResult:
    """Different selected channels: report a mismatch for each affected signal."""
    names = sorted(set(p_data["signal"]) | set(j_data["signal"])) if (not p_data.empty or not j_data.empty) else []
    frames = []
    for n in names:
        f = _single_row(file_name, n, Status.MISMATCH, gate.short)
        f["channel"], f["java_channel"] = gate.python, gate.java
        frames.append(f)
    res.rows = pd.concat(frames, ignore_index=True) if frames else pd.DataFrame(columns=ROW_COLUMNS)
    res.signal_summary = summarize_signals(res.rows)
    res.notes.append(gate.short)
    return res


def _first_can_id(*dfs: pd.DataFrame | None) -> str:
    for df in dfs:
        if df is not None and "can_id" in df.columns:
            ids = [c for c in df["can_id"].astype(str).tolist() if c.strip()]
            if ids:
                return ids[0]
    return ""


def _first_unit(df: pd.DataFrame) -> str:
    units = [u for u in df["unit"].astype(str).tolist() if u.strip()]
    return units[0] if units else ""


# ---------------------------------------------------------------------------
# Summaries
# ---------------------------------------------------------------------------
def summarize_signals(rows: pd.DataFrame) -> pd.DataFrame:
    cols = [
        "file", "can_id", "channel", "signal", "status", "rows", "match", "mismatch",
        "missing_in_python", "missing_in_java", "not_comparable", "error",
        "max_abs_diff", "match_pct",
    ]
    if rows is None or rows.empty:
        return pd.DataFrame(columns=cols)
    recs = []
    if "channel" not in rows.columns:
        rows = rows.assign(channel=0)
    if "can_id" not in rows.columns:
        rows = rows.assign(can_id="")
    for (file, channel, signal), g in rows.groupby(["file", "channel", "signal"], sort=True):
        counts = g["status"].value_counts()
        c = {k: int(counts.get(k, 0)) for k in Status.ALL}
        worst = next((s for s in Status.PRIORITY if c[s] > 0), Status.MATCH)
        comparable = c[Status.MATCH] + c[Status.MISMATCH]
        diffs = g["abs_diff"].dropna()
        recs.append(
            {
                "file": file,
                "can_id": next((c for c in g["can_id"].astype(str) if c.strip()), ""),
                "channel": int(channel),
                "signal": signal,
                "status": worst,
                "rows": int(len(g)),
                "match": c[Status.MATCH],
                "mismatch": c[Status.MISMATCH],
                "missing_in_python": c[Status.MISSING_IN_PYTHON],
                "missing_in_java": c[Status.MISSING_IN_JAVA],
                "not_comparable": c[Status.NOT_COMPARABLE],
                "error": c[Status.ERROR],
                "max_abs_diff": float(diffs.max()) if len(diffs) else np.nan,
                "match_pct": round(100.0 * c[Status.MATCH] / comparable, 4) if comparable else np.nan,
            }
        )
    return pd.DataFrame(recs, columns=cols)


def summarize(
    rows: pd.DataFrame, performed: bool = True, signal_summary: pd.DataFrame | None = None
) -> dict[str, Any]:
    """Aggregate counts + match percentage over comparison rows.

    ``match_percentage`` = MATCH / (MATCH + MISMATCH) * 100 over rows that were
    actually compared. Missing/not-comparable/error rows are reported
    separately and do not dilute or inflate that number.
    """
    base: dict[str, Any] = {k.lower(): 0 for k in Status.ALL}
    base.update(
        {
            "comparison_performed": bool(performed),
            "total_rows": 0,
            "compared_rows": 0,
            "match_percentage": None,
            "signals_total": 0,
            "signals_all_match": 0,
            "signals_with_mismatch": 0,
            "signals_missing_in_python": 0,
            "signals_missing_in_java": 0,
        }
    )
    if rows is None or rows.empty:
        return base
    counts = rows["status"].value_counts()
    for k in Status.ALL:
        base[k.lower()] = int(counts.get(k, 0))
    base["total_rows"] = int(len(rows))
    compared = base["match"] + base["mismatch"]
    base["compared_rows"] = compared
    base["match_percentage"] = round(100.0 * base["match"] / compared, 4) if compared else None
    sig = signal_summary if signal_summary is not None else summarize_signals(rows)
    base["signals_total"] = int(len(sig))
    base["signals_all_match"] = int((sig["status"] == Status.MATCH).sum())
    base["signals_with_mismatch"] = int((sig["status"] == Status.MISMATCH).sum())
    sig_level = rows[rows["timestamp"].isna()]
    base["signals_missing_in_python"] = int(
        sig_level.loc[sig_level["status"] == Status.MISSING_IN_PYTHON, ["file", "channel", "signal"]].drop_duplicates().shape[0]
    )
    base["signals_missing_in_java"] = int(
        sig_level.loc[sig_level["status"] == Status.MISSING_IN_JAVA, ["file", "channel", "signal"]].drop_duplicates().shape[0]
    )
    return base


def combine(results: list[ComparisonResult]) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Concatenate rows / signal summaries of several per-file results."""
    rows = [r.rows for r in results if r.rows is not None and not r.rows.empty]
    if len(results) == 1:
        only = results[0]
        return (only.rows if only.rows is not None else pd.DataFrame(columns=ROW_COLUMNS),
                only.signal_summary if only.signal_summary is not None else pd.DataFrame())
    all_rows = pd.concat(rows, ignore_index=True) if rows else pd.DataFrame(columns=ROW_COLUMNS)
    rows.clear()
    return all_rows, summarize_signals(all_rows)


def compact_rows(rows: pd.DataFrame) -> pd.DataFrame:
    """Categorize repeated labels to reduce retained memory for large comparisons."""
    if rows is None or rows.empty:
        return rows
    for column in ("file", "can_id", "signal", "status", "unit_python", "unit_java", "note"):
        if column in rows.columns and rows[column].dtype == object:
            rows[column] = rows[column].astype("category")
    return rows
