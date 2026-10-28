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


DATA_COLUMNS = ["signal", "timestamp", "value", "unit", "is_discrete", "message", "can_id"]


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
        }
    )


def normalize_frame(df: pd.DataFrame | None) -> pd.DataFrame:
    """Return ``df`` with exactly ``DATA_COLUMNS`` and sane dtypes."""
    if df is None or len(df) == 0:
        return empty_frame()
    out = df.copy()
    for col in DATA_COLUMNS:
        if col not in out.columns:
            if col in ("unit", "message", "can_id"):
                out[col] = ""
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
    out["can_id"] = (
        out["can_id"].astype(object).where(out["can_id"].notna(), "").map(lambda x: str(x))
    )
    out["is_discrete"] = out["is_discrete"].fillna(False).astype(bool)
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

    def to_dict(self) -> dict[str, Any]:
        d = asdict(self)
        d["discrete_signals"] = list(self.discrete_signals)
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
