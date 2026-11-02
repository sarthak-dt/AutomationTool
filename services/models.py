"""Shared data models used by every module.

Normalized decoder output (both engines) is a pandas DataFrame with the
columns in ``DATA_COLUMNS``: one row per decoded sample.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass, field
from typing import Any

import numpy as np
import pandas as pd


class Status:
    MATCH = "MATCH"
    MISMATCH = "MISMATCH"
    MISSING_IN_PYTHON = "MISSING_IN_PYTHON"
    MISSING_IN_JAVA = "MISSING_IN_JAVA"
    NOT_COMPARABLE = "NOT_COMPARABLE"
    ERROR = "ERROR"

    ALL = (MATCH, MISMATCH, MISSING_IN_PYTHON, MISSING_IN_JAVA, NOT_COMPARABLE, ERROR)
    # worst first; used to roll sample statuses up to a signal status
    PRIORITY = (ERROR, MISMATCH, MISSING_IN_JAVA, MISSING_IN_PYTHON, NOT_COMPARABLE, MATCH)


class EngineState:
    OK = "OK"
    UNAVAILABLE = "MOCK / UNAVAILABLE"
    ERROR = "ERROR"


DATA_COLUMNS = ["signal", "timestamp", "value", "unit", "is_discrete", "message", "can_id", "channel", "channel_known"]
# can_id: "0x7E8" style text ("" = unknown); channel_known distinguishes an unknown 0 from real MF4 bus channel 0.


def fmt_can_id(value) -> str:
    """Normalize a CAN ID (int, "1573", "0x625") to "0x625" upper-case hex; "" if unknown."""
    if value is None or (isinstance(value, float) and value != value):
        return ""
    try:
        if isinstance(value, str):
            t = value.strip()
            if not t:
                return ""
            n = int(t, 16) if t.lower().startswith("0x") else int(t)
        else:
            n = int(value)
    except (TypeError, ValueError):
        return ""
    return f"0x{n:X}"


def empty_frame() -> pd.DataFrame:
    return pd.DataFrame(
        {
            "signal": pd.Series(dtype="object"),
            "timestamp": pd.Series(dtype="float64"),
            "value": pd.Series(dtype="float64"),
            "unit": pd.Series(dtype="object"),
            "is_discrete": pd.Series(dtype="bool"),
            "message": pd.Series(dtype="object"),
            "can_id": pd.Series(dtype="object"),
            "channel": pd.Series(dtype="int64"),
            "channel_known": pd.Series(dtype="bool"),
        }
    )


def normalize_frame(df: pd.DataFrame | None) -> pd.DataFrame:
    """Return ``df`` with exactly ``DATA_COLUMNS`` and sane dtypes."""
    if df is None or len(df) == 0:
        return empty_frame()
    out = df.copy()
    has_channel_known = "channel_known" in out.columns
    for col in DATA_COLUMNS:
        if col not in out.columns:
            if col in ("unit", "message", "can_id"):
                out[col] = ""
            elif col == "channel":
                out[col] = 0
            elif col == "channel_known":
                out[col] = False
            elif col == "is_discrete":
                out[col] = False
            else:
                raise ValueError(f"decoder output is missing required column '{col}'")
    out = out[DATA_COLUMNS].copy()
    out["signal"] = out["signal"].astype(object).map(lambda x: str(x))
    out["timestamp"] = pd.to_numeric(out["timestamp"], errors="coerce").astype("float64")
    out["unit"] = out["unit"].astype(object).where(out["unit"].notna(), "").map(lambda x: str(x))
    out["message"] = (
        out["message"].astype(object).where(out["message"].notna(), "").map(lambda x: str(x))
    )
    out["is_discrete"] = out["is_discrete"].fillna(False).astype(bool)
    out["can_id"] = out["can_id"].map(fmt_can_id).astype(object)
    out["channel"] = pd.to_numeric(out["channel"], errors="coerce").fillna(0).astype("int64")
    if has_channel_known:
        out["channel_known"] = out["channel_known"].fillna(False).astype(bool)
    else:
        # Preserve backwards compatibility for normalized data from APIs/tests:
        # a positive channel is explicit, and channel 0 with a CAN ID may be a
        # real MF4 bus channel. Readers with lost metadata set False explicitly.
        out["channel_known"] = (out["channel"] > 0) | (
            (out["channel"] == 0) & out["can_id"].astype(str).str.strip().ne("")
        )
    return out.reset_index(drop=True)


@dataclass
class DecoderResult:
    """Result of running one decoder engine on one measurement file."""

    engine: str  # "python" | "java"
    state: str  # EngineState.*
    data: pd.DataFrame = field(default_factory=empty_frame)
    warnings: list[str] = field(default_factory=list)
    error: str | None = None
    stats: dict[str, Any] = field(default_factory=dict)

    @property
    def ok(self) -> bool:
        return self.state == EngineState.OK

    @property
    def is_mock(self) -> bool:
        return self.state == EngineState.UNAVAILABLE

    @classmethod
    def unavailable(cls, engine: str, reason: str) -> "DecoderResult":
        return cls(engine=engine, state=EngineState.UNAVAILABLE, error=reason)

    @classmethod
    def failed(cls, engine: str, error: str, warnings: list[str] | None = None) -> "DecoderResult":
        return cls(engine=engine, state=EngineState.ERROR, error=error, warnings=warnings or [])

    @property
    def signal_names(self) -> list[str]:
        if self.data is None or self.data.empty:
            return []
        return sorted(self.data["signal"].unique().tolist())


_VALID_MISSING = ("flag", "skip")
_VALID_UNIT = ("flag", "strict")


@dataclass
class ComparisonSettings:
    """Processing + comparison settings. The same object is given to both engines."""

    abs_tol: float = 1e-6
    rel_tol: float = 1e-6
    time_tol: float = 1e-3  # seconds; max distance when pairing samples
    resample_1s: bool = False
    missing_policy: str = "flag"  # flag | skip  (NaN values)
    unit_policy: str = "flag"  # flag (note only) | strict (NOT_COMPARABLE)
    auto_discrete: bool = True
    discrete_max_unique: int = 32
    discrete_signals: tuple[str, ...] = ()
    max_frames: int = 0  # 0 = unlimited (CAN log readers)
    decode_channels: tuple[int, ...] = ()  # empty = all CAN channels; otherwise zero-based MF4 bus channels
    python_channel: int = 0  # CAN channel taken from the Python output (0 = all channels)
    java_channel: int = 0  # CAN channel taken from the Java output (0 = all channels)
    channel_mapping_confirmed: bool = False  # legacy field; channel numbers must now match exactly

    def validate(self) -> None:
        if self.abs_tol < 0 or self.rel_tol < 0 or self.time_tol < 0:
            raise ValueError("Tolerances must be >= 0")
        if self.missing_policy not in _VALID_MISSING:
            raise ValueError(f"missing_policy must be one of {_VALID_MISSING}")
        if self.unit_policy not in _VALID_UNIT:
            raise ValueError(f"unit_policy must be one of {_VALID_UNIT}")
        if self.discrete_max_unique < 1:
            raise ValueError("discrete_max_unique must be >= 1")
        if self.max_frames < 0:
            raise ValueError("max_frames must be >= 0")
        if any(channel < 0 for channel in self.decode_channels):
            raise ValueError("decode_channels must contain channel numbers >= 0")
        if len(set(self.decode_channels)) != len(self.decode_channels):
            raise ValueError("decode_channels must not contain duplicates")
        if self.python_channel < 0 or self.java_channel < 0:
            raise ValueError("channel numbers must be >= 0 (0 = all channels)")

    def to_dict(self) -> dict[str, Any]:
        d = asdict(self)
        d["discrete_signals"] = list(self.discrete_signals)
        d["decode_channels"] = list(self.decode_channels)
        return d


@dataclass
class ComparisonResult:
    """Outcome of comparing the two engines for one measurement file."""

    file: str
    performed: bool
    reason: str = ""  # why not performed
    rows: pd.DataFrame = field(default_factory=lambda: pd.DataFrame())
    signal_summary: pd.DataFrame = field(default_factory=lambda: pd.DataFrame())
    notes: list[str] = field(default_factory=list)


def nan_to_none(x: Any) -> Any:
    if x is None:
        return None
    if isinstance(x, (float, np.floating)) and not np.isfinite(x):
        return None
    if isinstance(x, np.generic):
        return x.item()
    return x
