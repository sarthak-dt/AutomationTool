"""Python reference decoder.

Supported inputs
================
* **BLF / ASC / LOG** (raw CAN logs): read with ``python-can`` and decoded
  frame by frame with ``cantools`` using the selected DBC files.
* **MF4 with raw CAN bus logging** (ASAM MDF bus-logging format): decoded with
  ``asammdf`` ``MDF.extract_bus_logging`` using the selected DBC files.
* **MF4 that is already decoded** (plain signal channels): channels are read
  as-is with ``asammdf``; DBCs are *not* applicable and a warning says so.

asammdf alone does not decode every kind of CAN log; that is why python-can +
cantools are used for BLF/ASC/LOG.

Timestamps are seconds. For BLF/ASC/LOG they are made relative to the first
frame in the file. For MF4 they are asammdf's measurement-relative timestamps.

Limitations (see README): CAN only (no LIN/FlexRay/Ethernet), CAN-FD decoded
through the same cantools path, container/J1939-transport messages are not
reassembled for BLF/ASC/LOG, signal values use physical scaling with value
tables *not* applied (numeric output).
"""
from __future__ import annotations

import re
import time
from pathlib import Path
from typing import Any, Callable, Iterable

import numpy as np
import pandas as pd

from services.dbc_service import parse_dbc
from services.logging_utils import get_logger, redact_text
from services import timeutil
from services.models import ComparisonSettings, DecoderResult, EngineState, normalize_frame

log = get_logger("python_decoder")

CAN_LOG_EXTS = (".blf", ".asc", ".log")
MDF_EXTS = (".mf4",)
SUPPORTED_EXTS = CAN_LOG_EXTS + MDF_EXTS


def library_status() -> dict[str, str]:
    """Report which optional libraries are importable (for the UI sidebar)."""
    status = {}
    for mod in ("asammdf", "cantools", "can", "boto3", "plotly"):
        try:
            m = __import__(mod)
            status[mod] = getattr(m, "__version__", "installed")
        except Exception:
            status[mod] = "missing"
    return status


# ---------------------------------------------------------------------------
# Frame-level decoding (library independent -> unit-testable with fakes)
# ---------------------------------------------------------------------------
def build_message_lookup(databases: Iterable[Any]) -> dict[tuple[int, bool], Any]:
    """Map (frame_id, is_extended) -> cantools message. First database wins."""
    lookup: dict[tuple[int, bool], Any] = {}
    for db in databases:
        for msg in db.messages:
            key = (int(msg.frame_id), bool(msg.is_extended_frame))
            lookup.setdefault(key, msg)
    return lookup


def _signal_is_discrete(sig: Any) -> bool:
    try:
        if getattr(sig, "choices", None):
            return True
        if int(getattr(sig, "length", 0)) == 1:
            return True
    except Exception:
        pass
    return False


def decode_frames(
    frames: Iterable[Any],
    lookup: dict[tuple[int, bool], Any],
    max_frames: int = 0,
) -> tuple[pd.DataFrame, dict[str, Any], list[str]]:
    """Decode CAN frames (python-can ``Message``-like objects) with ``lookup``.

    Returns ``(frame, stats, warnings)``.
    """
    cols: dict[str, dict[str, Any]] = {}
    stats = {
        "frames_total": 0,
        "frames_decoded": 0,
        "frames_without_definition": 0,
        "frames_skipped_error_or_remote": 0,
        "decode_errors": 0,
        "samples": 0,
        "truncated": False,
    }
    warnings: list[str] = []
    error_examples: dict[str, int] = {}
    t0: float | None = None

    for frame in frames:
        if max_frames and stats["frames_total"] >= max_frames:
            stats["truncated"] = True
            break
        stats["frames_total"] += 1
        ts = float(frame.timestamp)
        if t0 is None:
            t0 = ts
        if getattr(frame, "is_error_frame", False) or getattr(frame, "is_remote_frame", False):
            stats["frames_skipped_error_or_remote"] += 1
            continue
        msg = lookup.get((int(frame.arbitration_id), bool(frame.is_extended_id)))
        if msg is None:
            stats["frames_without_definition"] += 1
            continue
        try:
            decoded = msg.decode(bytes(frame.data), decode_choices=False, scaling=True)
        except Exception as exc:
            stats["decode_errors"] += 1
            key = f"{msg.name}: {type(exc).__name__}"
            error_examples[key] = error_examples.get(key, 0) + 1
            continue
        stats["frames_decoded"] += 1
        rel = ts - t0
        sig_by_name = {s.name: s for s in msg.signals}
        for name, value in decoded.items():
            try:
                fval = float(value)
            except (TypeError, ValueError):
                continue
            col = cols.get(name)
            if col is None:
                sig = sig_by_name.get(name)
                col = cols[name] = {
                    "t": [],
                    "v": [],
                    "can_id": [],
                    "unit": str(getattr(sig, "unit", "") or "") if sig is not None else "",
                    "discrete": _signal_is_discrete(sig) if sig is not None else False,
                    "message": str(msg.name),
                }
            col["t"].append(rel)
            col["v"].append(fval)
            col["can_id"].append(f"0x{int(frame.arbitration_id):X}")
            stats["samples"] += 1

    stats["start_time"] = timeutil.epoch_to_start(t0)
    if stats["truncated"]:
        warnings.append(f"Stopped after {max_frames} frames (max_frames setting); results are partial.")
    if stats["frames_total"] == 0:
        warnings.append("The log contains no frames.")
    elif stats["frames_decoded"] == 0:
        warnings.append(
            "No frame matched any message in the selected DBC files (check CAN IDs / standard vs "
            "extended IDs / that the right DBC is selected)."
        )
    if stats["frames_without_definition"]:
        warnings.append(
            f"{stats['frames_without_definition']} frame(s) had no definition in the selected DBCs and were not decoded."
        )
    if stats["decode_errors"]:
        examples = "; ".join(f"{k} x{v}" for k, v in list(error_examples.items())[:5])
        warnings.append(f"{stats['decode_errors']} frame(s) failed to decode ({examples}).")

    if not cols:
        from services.models import empty_frame

        return empty_frame(), stats, warnings

    parts = []
    for name, c in cols.items():
        parts.append(
            pd.DataFrame(
                {
                    "signal": name,
                    "timestamp": np.asarray(c["t"], dtype=float),
                    "value": np.asarray(c["v"], dtype=float),
                    "unit": c["unit"],
                    "is_discrete": c["discrete"],
                    "message": c["message"],
                    "can_id": c["can_id"],
                }
            )
        )
    frame_df = pd.concat(parts, ignore_index=True)
    return normalize_frame(frame_df), stats, warnings


# ---------------------------------------------------------------------------
# File readers
# ---------------------------------------------------------------------------
def _load_databases(dbc_paths: list[Path]) -> tuple[list[Any], list[str]]:
    dbs = []
    warns: list[str] = []
    for p in dbc_paths:
        db, info = parse_dbc(Path(p).read_bytes())
        if db is None or not info.usable:
            raise ValueError(f"DBC '{Path(p).name}' could not be parsed: {info.error}")
        warns.extend(f"{Path(p).name}: {w}" for w in info.warnings)
        dbs.append(db)
    return dbs, warns


def _decode_can_log(path: Path, dbc_paths: list[Path], settings: ComparisonSettings) -> DecoderResult:
    try:
        import can  # python-can
    except ImportError:
        return DecoderResult.failed("python", "python-can is not installed (pip install -r requirements.txt).")
    try:
        dbs, warns = _load_databases(dbc_paths)
    except Exception as exc:
        return DecoderResult.failed("python", redact_text(exc))
    lookup = build_message_lookup(dbs)
    try:
        with can.LogReader(str(path)) as reader:
            df, stats, w = decode_frames(reader, lookup, settings.max_frames)
    except Exception as exc:
        return DecoderResult.failed("python", f"Could not read '{path.name}': {redact_text(exc)}", warns)
    return DecoderResult(
        engine="python",
        state=EngineState.OK,
        data=df,
        warnings=warns + w,
        stats=stats,
    )


def _iter_mdf_signals(mdf: Any) -> Iterable[Any]:
    # skip_master: the time channel is not a signal
    return mdf.iter_channels(skip_master=True, copy_master=False)


def _signals_to_frame(signals: Iterable[Any], warnings: list[str]) -> pd.DataFrame:
    parts = []
    skipped = 0
    for sig in signals:
        try:
            samples = np.asarray(sig.samples)
            ts = np.asarray(sig.timestamps, dtype=float)
            if samples.ndim != 1 or samples.size == 0 or samples.size != ts.size:
                skipped += 1
                continue
            if samples.dtype.kind in "biuf":
                values = samples.astype(float)
                discrete = samples.dtype.kind in "biu" and len(np.unique(samples)) <= 32
            else:
                skipped += 1  # text/bytes/structured channels are not compared numerically
                continue
            parts.append(
                pd.DataFrame(
                    {
                        "signal": str(sig.name),
                        "timestamp": ts,
                        "value": values,
                        "unit": str(getattr(sig, "unit", "") or ""),
                        "is_discrete": bool(discrete),
                        "message": "",
                        "can_id": _mf4_signal_can_id(sig),
                    }
                )
            )
        except Exception as exc:  # one bad channel must not kill the file
            skipped += 1
            log.debug("Skipping channel: %s", type(exc).__name__)
    if skipped:
        warnings.append(f"{skipped} non-numeric / multi-dimensional / unreadable channel(s) were skipped.")
    if not parts:
        from services.models import empty_frame

        return empty_frame()
    return normalize_frame(pd.concat(parts, ignore_index=True))


def _mf4_signal_can_id(signal: Any) -> str:
    source = getattr(signal, "source", None)
    if source is None:
        return ""
    for metadata in (getattr(source, "path", ""), getattr(source, "name", ""), getattr(source, "comment", "")):
        match = re.search(r"\bID=(0x[0-9A-Fa-f]+)\b", str(metadata or ""), re.IGNORECASE)
        if match:
            return match.group(1).upper().replace("0X", "0x")
    return ""


def _decode_mf4(path: Path, dbc_paths: list[Path], settings: ComparisonSettings) -> DecoderResult:
    try:
        from asammdf import MDF
    except ImportError:
        return DecoderResult.failed("python", "asammdf is not installed (pip install -r requirements.txt).")
    warnings: list[str] = []
    stats: dict[str, Any] = {}
    mdf = None
    decoded = None
    try:
        mdf = MDF(str(path))
        bus_map = getattr(mdf, "bus_logging_map", {}) or {}
        has_can_logging = bool(bus_map.get("CAN"))
        stats["mf4_has_can_bus_logging"] = has_can_logging
        try:
            st = mdf.header.start_time
            stats["start_time"] = st.isoformat() if st is not None else None
        except Exception:
            stats["start_time"] = None
        if has_can_logging:
            if not dbc_paths:
                return DecoderResult.failed("python", "This MF4 contains raw CAN bus logging; select at least one DBC.")
            # channel index 0 == "any CAN channel" in asammdf's database_files syntax
            database_files = {"CAN": [(str(p), 0) for p in dbc_paths]}
            decoded = mdf.extract_bus_logging(database_files=database_files)
            frame = _signals_to_frame(_iter_mdf_signals(decoded), warnings)
            if frame.empty:
                warnings.append(
                    "extract_bus_logging produced no signals (DBC does not match the logged CAN IDs/channels?)."
                )
        else:
            warnings.append(
                "MF4 has no raw CAN bus logging; its existing channels were read as-is and the DBC files "
                "were NOT applied."
            )
            frame = _signals_to_frame(_iter_mdf_signals(mdf), warnings)
        stats["samples"] = int(len(frame))
        return DecoderResult(engine="python", state=EngineState.OK, data=frame, warnings=warnings, stats=stats)
    except Exception as exc:
        return DecoderResult.failed(
            "python", f"asammdf failed on '{path.name}': {type(exc).__name__}: {redact_text(exc)}", warnings
        )
    finally:
        for obj in (decoded, mdf):
            try:
                if obj is not None:
                    obj.close()
            except Exception:
                pass


def decode_measurement(
    path: str | Path,
    dbc_paths: list[str | Path],
    settings: ComparisonSettings,
) -> DecoderResult:
    """Decode ``path`` with the Python reference engine."""
    path = Path(path)
    dbcs = [Path(p) for p in dbc_paths]
    started = time.time()
    ext = path.suffix.lower()
    if ext not in SUPPORTED_EXTS:
        return DecoderResult.failed("python", f"Unsupported measurement format '{ext}'. Supported: {', '.join(SUPPORTED_EXTS)}")
    if not path.exists():
        return DecoderResult.failed("python", f"File not found: {path.name}")
    if ext in CAN_LOG_EXTS and not dbcs:
        return DecoderResult.failed("python", "At least one DBC file is required to decode CAN logs.")
    try:
        result = _decode_mf4(path, dbcs, settings) if ext in MDF_EXTS else _decode_can_log(path, dbcs, settings)
    except Exception as exc:  # last-resort guard
        result = DecoderResult.failed("python", f"Unexpected decoder error: {type(exc).__name__}: {redact_text(exc)}")
    result.stats["seconds"] = round(time.time() - started, 3)
    return result
