"""Comparison engine: Python reference decoder vs Java decoder.

Rules (also documented in README.md)
====================================
Keys
    Signals are matched per (file, signal name). Name comparison is exact.

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

from services.models import (
    ComparisonResult,
    ComparisonSettings,
    DecoderResult,
    EngineState,
    Status,
)

ROW_COLUMNS = [
    "file",
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
    p_names = set(py.data["signal"].unique()) if not py.data.empty else set()
    j_names = set(java.data["signal"].unique()) if not java.data.empty else set()
    p_groups = {k: g for k, g in py.data.groupby("signal", sort=False)} if p_names else {}
    j_groups = {k: g for k, g in java.data.groupby("signal", sort=False)} if j_names else {}

    frames: list[pd.DataFrame] = []
    for name in sorted(p_names | j_names):
        try:
            if name not in j_groups:
                g = p_groups[name]
                u = _first_unit(g)
                frames.append(_single_row(file_name, name, Status.MISSING_IN_JAVA, "signal not present in Java output", u, ""))
            elif name not in p_groups:
                g = j_groups[name]
                u = _first_unit(g)
                frames.append(_single_row(file_name, name, Status.MISSING_IN_PYTHON, "signal not present in Python output", "", u))
            else:
                frames.extend(_compare_signal(file_name, name, p_groups[name], j_groups[name], settings))
        except Exception as exc:  # never let one signal abort the file
            frames.append(_single_row(file_name, name, Status.ERROR, f"{type(exc).__name__}: {exc}"))
    res.rows = pd.concat(frames, ignore_index=True) if frames else pd.DataFrame(columns=ROW_COLUMNS)
    res.signal_summary = summarize_signals(res.rows)
    if not p_names and not j_names:
        res.notes.append("Neither engine produced any signals.")
    return res


def _first_unit(df: pd.DataFrame) -> str:
    units = [u for u in df["unit"].astype(str).tolist() if u.strip()]
    return units[0] if units else ""


# ---------------------------------------------------------------------------
# Summaries
# ---------------------------------------------------------------------------
def summarize_signals(rows: pd.DataFrame) -> pd.DataFrame:
    cols = [
        "file", "signal", "status", "rows", "match", "mismatch",
        "missing_in_python", "missing_in_java", "not_comparable", "error",
        "max_abs_diff", "match_pct",
    ]
    if rows is None or rows.empty:
        return pd.DataFrame(columns=cols)
    recs = []
    for (file, signal), g in rows.groupby(["file", "signal"], sort=True):
        counts = g["status"].value_counts()
        c = {k: int(counts.get(k, 0)) for k in Status.ALL}
        worst = next((s for s in Status.PRIORITY if c[s] > 0), Status.MATCH)
        comparable = c[Status.MATCH] + c[Status.MISMATCH]
        diffs = g["abs_diff"].dropna()
        recs.append(
            {
                "file": file,
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


def summarize(rows: pd.DataFrame, performed: bool = True) -> dict[str, Any]:
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
    sig = summarize_signals(rows)
    base["signals_total"] = int(len(sig))
    base["signals_all_match"] = int((sig["status"] == Status.MATCH).sum())
    base["signals_with_mismatch"] = int((sig["status"] == Status.MISMATCH).sum())
    sig_level = rows[rows["timestamp"].isna()]
    base["signals_missing_in_python"] = int(
        sig_level.loc[sig_level["status"] == Status.MISSING_IN_PYTHON, ["file", "signal"]].drop_duplicates().shape[0]
    )
    base["signals_missing_in_java"] = int(
        sig_level.loc[sig_level["status"] == Status.MISSING_IN_JAVA, ["file", "signal"]].drop_duplicates().shape[0]
    )
    return base


def combine(results: list[ComparisonResult]) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Concatenate rows / signal summaries of several per-file results."""
    rows = [r.rows for r in results if r.rows is not None and not r.rows.empty]
    all_rows = pd.concat(rows, ignore_index=True) if rows else pd.DataFrame(columns=ROW_COLUMNS)
    return all_rows, summarize_signals(all_rows)
