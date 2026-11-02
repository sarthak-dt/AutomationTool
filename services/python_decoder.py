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

Channels: every DBC can be bound to one CAN bus channel or to "any". MF4 bus
channels retain their file channel number (including channel 0); BLF/ASC/LOG
channels retain the existing 1-based display convention. Output rows carry
``channel`` and ``can_id``.
BLF/ASC/LOG: python-can reports 0-based channel numbers; they are shown 1-based
(CHANNEL_OFFSET). Interface names such as ``can0`` -> channel 1. MF4: one
``extract_bus_logging`` run per channel, with extracted signals attributed to
their actual MF4 bus channel.

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
from services.models import ComparisonSettings, DecoderResult, EngineState, fmt_can_id, normalize_frame

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


CHANNEL_OFFSET = 1  # python-can channels are 0-based, CAN tools / asammdf number buses from 1


def frame_channel(frame: Any) -> int:
    """1-based CAN channel of a python-can frame; 0 if unknown."""
    ch = getattr(frame, "channel", None)
    if isinstance(ch, bool) or ch is None:
        return 0
    if isinstance(ch, (int, np.integer)):
        return int(ch) + CHANNEL_OFFSET
    if isinstance(ch, str):
        m = re.search(r"(\d+)$", ch.strip())
        return int(m.group(1)) + CHANNEL_OFFSET if m else 0
    return 0


class ChannelLookups:
    """Message lookup per CAN channel.

    ``databases[i]`` is bound to ``channels[i]``. ``None`` and legacy integer
    ``0`` mean any channel; a tuple can explicitly bind a DBC to channel 0.
    For a frame on channel ``c`` the DBCs bound to ``c`` come first, then the
    "any" DBCs. A frame with unknown channel is decoded by "any" DBCs only.
    """

    def __init__(self, databases: list[Any], channels: list[Any] | None = None):
        self.databases = list(databases)
        raw_bindings = list(channels) if channels is not None else [None] * len(self.databases)
        if len(raw_bindings) != len(self.databases):
            raise ValueError("channels must have one entry per DBC")
        self.bindings: list[tuple[int, ...] | None] = []
        for binding in raw_bindings:
            if binding is None or (isinstance(binding, (int, np.integer)) and int(binding) == 0):
                self.bindings.append(None)
            elif isinstance(binding, (tuple, list, set, frozenset)):
                self.bindings.append(tuple(sorted({int(channel) for channel in binding})))
            else:
                self.bindings.append((int(binding),))
        if any(channel < 0 for binding in self.bindings if binding is not None for channel in binding):
            raise ValueError("channel bindings must be >= 0")
        self.channels = raw_bindings
        self._cache: dict[int, dict[tuple[int, bool], Any]] = {}

    @property
    def uses_channels(self) -> bool:
        return any(binding is not None for binding in self.bindings)

    def get(self, channel: int) -> dict[tuple[int, bool], Any]:
        if channel not in self._cache:
            bound = [
                database
                for database, binding in zip(self.databases, self.bindings)
                if binding is not None and channel in binding
            ]
            anyc = [database for database, binding in zip(self.databases, self.bindings) if binding is None]
            self._cache[channel] = build_message_lookup(bound + anyc)
        return self._cache[channel]

    def get_unknown_channel(self) -> dict[tuple[int, bool], Any]:
        return build_message_lookup(
            [database for database, binding in zip(self.databases, self.bindings) if binding is None]
        )


def channel_notices(
    dbc_names: list[str], dbc_channels: list[Any], channels_in_file: list[int], selected_channel: int = 0,
    selected_channels: tuple[int, ...] = (),
) -> list[str]:
    """Clear messages when a channel the user asked for does not exist in the measurement file.

    Only judged when the file itself carries channel numbers (``channels_in_file`` non-empty).
    """
    have = sorted({int(c) for c in channels_in_file if int(c) >= 0})
    if not have:
        return []
    notes: list[str] = []
    for name, binding in zip(dbc_names, dbc_channels):
        channels = (
            tuple(int(channel) for channel in binding)
            if isinstance(binding, (tuple, list, set, frozenset))
            else ((int(binding),) if binding is not None and int(binding) > 0 else ())
        )
        for channel in channels:
            if channel not in have:
                notes.append(
                    f"Channel {channel} is not present in this file (channels in file: {have}). "
                    f"DBC '{name}' was not applied, so nothing was decoded for channel {channel}."
                )
    sel = int(selected_channel or 0)
    if sel > 0 and sel not in have:
        msg = (
            f"Selected channel {sel} is not present in this file (channels in file: {have}); nothing was decoded for it."
        )
        if msg not in notes:
            notes.append(msg)
    for channel in selected_channels:
        if channel not in have:
            msg = f"Selected channel {channel} is not present in this file (channels in file: {have}); nothing was decoded for it."
            if msg not in notes:
                notes.append(msg)
    return notes


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
    lookup: dict[tuple[int, bool], Any] | ChannelLookups,
    max_frames: int = 0,
    selected_channels: tuple[int, ...] = (),
) -> tuple[pd.DataFrame, dict[str, Any], list[str]]:
    """Decode CAN frames (python-can ``Message``-like objects) with ``lookup``.

    Returns ``(frame, stats, warnings)``.
    """
    cols: dict[tuple[str, int, str], dict[str, Any]] = {}
    channels_seen: set[int] = set()
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
        ch = frame_channel(frame)
        channels_seen.add(ch)
        if selected_channels and ch not in selected_channels:
            continue
        if isinstance(lookup, ChannelLookups):
            table = lookup.get_unknown_channel() if ch == 0 else lookup.get(ch)
        else:
            table = lookup
        msg = table.get((int(frame.arbitration_id), bool(frame.is_extended_id)))
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
            ckey = (name, ch, fmt_can_id(frame.arbitration_id))
            col = cols.get(ckey)
            if col is None:
                sig = sig_by_name.get(name)
                col = cols[ckey] = {
                    "t": [],
                    "v": [],
                    "unit": str(getattr(sig, "unit", "") or "") if sig is not None else "",
                    "discrete": _signal_is_discrete(sig) if sig is not None else False,
                    "message": str(msg.name),
                    "channel": ch,
                    "channel_known": ch > 0,
                    "can_id": fmt_can_id(frame.arbitration_id),
                }
            col["t"].append(rel)
            col["v"].append(fval)
            stats["samples"] += 1

    stats["start_time"] = timeutil.epoch_to_start(t0)
    stats["channels_seen"] = sorted(channels_seen)
    if stats["truncated"]:
        warnings.append(f"Stopped after {max_frames} frames (max_frames setting); results are partial.")
    if stats["frames_total"] == 0:
        warnings.append("The log contains no frames.")
    elif stats["frames_decoded"] == 0:
        warnings.append(
            "No frame matched any message in the selected DBC files (check CAN IDs / standard vs "
            "extended IDs / that the right DBC is selected)."
        )
    if isinstance(lookup, ChannelLookups) and lookup.uses_channels and stats["frames_decoded"] == 0 and stats["frames_total"]:
        warnings.append(
            f"DBC channel binding: frames were seen on channel(s) {sorted(channels_seen)} but the DBC files are bound to "
            f"channel binding(s) {lookup.channels}. Check the channel numbers (python-can channel + 1)."
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
    for (name, _ch, _cid), c in cols.items():
        parts.append(
            pd.DataFrame(
                {
                    "signal": name,
                    "channel": c["channel"],
                    "can_id": c["can_id"],
                    "timestamp": np.asarray(c["t"], dtype=float),
                    "value": np.asarray(c["v"], dtype=float),
                    "unit": c["unit"],
                    "is_discrete": c["discrete"],
                    "message": c["message"],
                    "channel_known": bool(c.get("channel_known", False)),
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


def _decode_can_log(
    path: Path, dbc_paths: list[Path], settings: ComparisonSettings, dbc_channels: list[Any] | None = None
) -> DecoderResult:
    try:
        import can  # python-can
    except ImportError:
        return DecoderResult.failed("python", "python-can is not installed (pip install -r requirements.txt).")
    try:
        dbs, warns = _load_databases(dbc_paths)
    except Exception as exc:
        return DecoderResult.failed("python", redact_text(exc))
    lookup = ChannelLookups(dbs, dbc_channels)
    try:
        with can.LogReader(str(path)) as reader:
            df, stats, w = decode_frames(reader, lookup, settings.max_frames, settings.decode_channels)
        stats["channels_in_file"] = [c for c in stats.get("channels_seen", []) if c > 0]
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


def _signal_meta(databases: list[Any]) -> dict[str, tuple[str, str]]:
    """signal name -> (can_id, message name) when the name is unique in the DBCs, else ("", "")."""
    seen: dict[str, set[tuple[int, str]]] = {}
    for db in databases:
        for msg in db.messages:
            for sg in msg.signals:
                seen.setdefault(sg.name, set()).add((int(msg.frame_id), str(msg.name)))
    return {n: (fmt_can_id(next(iter(v))[0]), next(iter(v))[1]) if len(v) == 1 else ("", "") for n, v in seen.items()}


def _choice_values(databases: list[Any]) -> dict[str, dict[str, float]]:
    """Build an unambiguous label -> physical numeric value map for DBC choices.

    asammdf's bus decoder presents value-table signals as their text labels.
    The Java comparison contract uses numeric physical samples and carries the
    labels separately, so convert known labels back to the corresponding DBC
    choice value here. Ambiguous signal names or labels are deliberately left
    unmapped rather than guessed.
    """
    definitions: dict[str, list[dict[str, float] | None]] = {}

    def label_text(value: Any) -> str:
        if isinstance(value, bytes):
            return value.decode("utf-8", errors="replace").strip()
        return str(value).strip()

    for db in databases:
        for msg in getattr(db, "messages", []):
            for signal in getattr(msg, "signals", []):
                choices = getattr(signal, "choices", None)
                if not choices:
                    definitions.setdefault(str(signal.name), []).append(None)
                    continue
                mapping: dict[str, float] = {}
                ambiguous = False
                try:
                    scale = float(getattr(signal, "scale", 1.0))
                    offset = float(getattr(signal, "offset", 0.0))
                    for raw_value, label in choices.items():
                        key = label_text(label)
                        physical_value = float(raw_value) * scale + offset
                        if key in mapping and mapping[key] != physical_value:
                            ambiguous = True
                            break
                        mapping[key] = physical_value
                except (TypeError, ValueError, OverflowError):
                    ambiguous = True
                definitions.setdefault(str(signal.name), []).append(
                    None if ambiguous or not mapping else mapping
                )

    result: dict[str, dict[str, float]] = {}
    for name, maps in definitions.items():
        if any(mapping is None for mapping in maps):
            continue
        first = maps[0]
        if all(mapping == first for mapping in maps[1:]):
            result[name] = first
    return result


def _signal_source_channel(signal: Any) -> int | None:
    source = getattr(signal, "source", None)
    path = str(getattr(source, "path", "") or "")
    match = re.search(r"(?:^|[.])CAN(\d+)\.", path)
    return int(match.group(1)) if match else None


def _signal_source_can_id(signal: Any) -> str:
    source = getattr(signal, "source", None)
    path = str(getattr(source, "path", "") or "")
    match = re.search(r"\.ID=0x([0-9A-Fa-f]+)", path)
    return fmt_can_id(int(match.group(1), 16)) if match else ""


def _signals_to_frame(
    signals: Iterable[Any],
    warnings: list[str],
    channel: int = 0,
    meta: dict[str, tuple[str, str]] | None = None,
    expected_channel: int | None = None,
    choice_values: dict[str, dict[str, float]] | None = None,
) -> pd.DataFrame:
    parts = []
    skipped = 0
    for sig in signals:
        try:
            signal_channel = _signal_source_channel(sig)
            if expected_channel is not None:
                if signal_channel is None:
                    skipped += 1
                    continue
                if signal_channel != expected_channel:
                    continue
            samples = np.asarray(sig.samples)
            ts = np.asarray(sig.timestamps, dtype=float)
            if samples.ndim != 1 or samples.size == 0 or samples.size != ts.size:
                skipped += 1
                continue
            signal_choices = (choice_values or {}).get(str(sig.name))
            if samples.dtype.kind in "biuf":
                values = samples.astype(float)
                discrete = samples.dtype.kind in "biu" and len(np.unique(samples)) <= 32
            elif signal_choices:
                labels = [
                    value.decode("utf-8", errors="replace").strip()
                    if isinstance(value, bytes) else str(value).strip()
                    for value in samples
                ]
                if any(label not in signal_choices for label in labels):
                    skipped += 1
                    continue
                values = np.asarray([signal_choices[label] for label in labels], dtype=float)
                discrete = True
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
                        "message": (meta or {}).get(str(sig.name), ("", ""))[1],
                        "can_id": _signal_source_can_id(sig) or (meta or {}).get(str(sig.name), ("", ""))[0],
                        "channel": signal_channel if signal_channel is not None else channel,
                        "channel_known": signal_channel is not None,
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


def mf4_can_channels(mdf: Any) -> list[int]:
    """Bus channel numbers that carry CAN data frames in an MF4; [] if unknown.

    The bus logging map includes every MDF channel group. This avoids treating
    the first ``CAN_DataFrame.BusChannel`` channel occurrence as the full file
    and preserves a real bus channel 0.
    """
    bus_logging_map = getattr(mdf, "bus_logging_map", None)
    if isinstance(bus_logging_map, dict) and "CAN" in bus_logging_map:
        try:
            return sorted({int(channel) for channel in bus_logging_map["CAN"] if int(channel) >= 0})
        except (TypeError, ValueError):
            return []
    for name in ("CAN_DataFrame.BusChannel",):
        try:
            sig = mdf.get(name)
            vals = np.unique(np.asarray(sig.samples))
            chans = sorted(int(v) for v in vals if int(v) > 0)
            if chans:
                return chans
        except Exception:
            continue
    return []


def discover_can_channels(path: str | Path) -> list[int]:
    """Read the measurement's actual CAN channels without decoding DBC signals."""
    path = Path(path)
    if path.suffix.lower() in MDF_EXTS:
        try:
            from asammdf import MDF
        except ImportError as exc:
            raise RuntimeError("asammdf is required to discover MF4 CAN channels.") from exc
        mdf = MDF(str(path))
        try:
            return mf4_can_channels(mdf)
        finally:
            mdf.close()
    if path.suffix.lower() in CAN_LOG_EXTS:
        try:
            import can
        except ImportError as exc:
            raise RuntimeError("python-can is required to discover BLF/ASC/LOG channels.") from exc
        with can.LogReader(str(path)) as reader:
            return sorted({frame_channel(frame) for frame in reader
                           if not getattr(frame, "is_error_frame", False)
                           and not getattr(frame, "is_remote_frame", False)
                           and frame_channel(frame) > 0})
    raise ValueError(f"Unsupported measurement format '{path.suffix}'.")


def mf4_can_messages(mdf: Any) -> dict[int, set[tuple[int, bool]]]:
    """Return observed (arbitration ID, extended) pairs grouped by MF4 bus channel."""
    from asammdf.blocks.utils import MdfException

    can_map = (getattr(mdf, "bus_logging_map", {}) or {}).get("CAN", {})
    groups = {int(group) for messages in can_map.values() for group in messages.values()}
    observed: dict[int, set[tuple[int, bool]]] = {}
    for group in groups:
        raw_ids = np.asarray(mdf.get("CAN_DataFrame.ID", group=group).samples, dtype=np.uint32)
        buses = np.asarray(mdf.get("CAN_DataFrame.BusChannel", group=group).samples, dtype=np.uint8)
        try:
            extended = np.asarray(mdf.get("CAN_DataFrame.IDE", group=group).samples, dtype=bool)
        except MdfException:
            extended = (raw_ids & np.uint32(0x80000000)) != 0
        if raw_ids.size != buses.size or raw_ids.size != extended.size:
            raise ValueError(f"MF4 CAN metadata arrays do not align in channel group {group}.")
        for raw_id, bus, is_extended in zip(raw_ids, buses, extended):
            channel = int(bus)
            can_id = int(raw_id & np.uint32(0x1FFFFFFF))
            observed.setdefault(channel, set()).add((can_id, bool(is_extended)))
    return observed


def match_mf4_dbcs(
    path: str | Path,
    dbc_paths: list[str | Path],
    dbc_channels: list[Any] | None = None,
    selected_channels: tuple[int, ...] = (),
) -> pd.DataFrame:
    """Report DBC and MF4 CAN-ID matches independently for every bus channel."""
    from asammdf import MDF

    paths = [Path(p) for p in dbc_paths]
    databases, _ = _load_databases(paths)
    if dbc_channels is not None and len(dbc_channels) != len(paths):
        raise ValueError("DBC channel list does not match the DBC files.")
    bindings = ChannelLookups(databases, dbc_channels).bindings

    mdf = MDF(str(path))
    try:
        can_map = (getattr(mdf, "bus_logging_map", {}) or {}).get("CAN", {})
        if not can_map:
            raise ValueError("This MF4 does not contain raw CAN bus logging.")
        channel_map = {int(channel): messages for channel, messages in can_map.items() if int(channel) >= 0}
        observed = mf4_can_messages(mdf)
        available = sorted(channel_map)
        channels = sorted(set(selected_channels)) if selected_channels else available
        columns = ["channel", "can_id", "extended", "status", "dbc"]
        rows: list[dict[str, Any]] = []
        for channel in channels:
            if channel not in channel_map:
                rows.append(
                    {
                        "channel": channel,
                        "can_id": "",
                        "extended": None,
                        "status": "CHANNEL NOT PRESENT IN MF4",
                        "dbc": "",
                    }
                )
                continue
            file_ids = observed.get(channel, set())
            id_to_dbcs: dict[tuple[int, bool], list[str]] = {}
            for name, database, binding in zip(
                (path.name for path in paths), databases, bindings
            ):
                if binding is not None and channel not in binding:
                    continue
                for message in database.messages:
                    key = (int(message.frame_id), bool(message.is_extended_frame))
                    id_to_dbcs.setdefault(key, []).append(name)

            for can_id, is_extended in sorted(file_ids & id_to_dbcs.keys()):
                rows.append(
                    {
                        "channel": channel,
                        "can_id": fmt_can_id(can_id),
                        "extended": is_extended,
                        "status": "MATCHED",
                        "dbc": ", ".join(dict.fromkeys(id_to_dbcs[(can_id, is_extended)])),
                    }
                )
            for can_id, is_extended in sorted(file_ids - id_to_dbcs.keys()):
                rows.append(
                    {
                        "channel": channel,
                        "can_id": fmt_can_id(can_id),
                        "extended": is_extended,
                        "status": "UNMATCHED IN MF4",
                        "dbc": "",
                    }
                )
            for can_id, is_extended in sorted(id_to_dbcs.keys() - file_ids):
                rows.append(
                    {
                        "channel": channel,
                        "can_id": fmt_can_id(can_id),
                        "extended": is_extended,
                        "status": "UNMATCHED IN DBC",
                        "dbc": ", ".join(dict.fromkeys(id_to_dbcs[(can_id, is_extended)])),
                    }
                )
        return pd.DataFrame(rows, columns=columns)
    finally:
        mdf.close()


def match_can_log_dbcs(
    path: str | Path,
    dbc_paths: list[str | Path],
    dbc_channels: list[Any] | None = None,
    selected_channels: tuple[int, ...] = (),
) -> pd.DataFrame:
    """Report DBC and raw CAN-ID matches for ASC, BLF, or LOG files by channel."""
    import can

    path = Path(path)
    paths = [Path(p) for p in dbc_paths]
    databases, _ = _load_databases(paths)
    if dbc_channels is not None and len(dbc_channels) != len(paths):
        raise ValueError("DBC channel list does not match the DBC files.")
    bindings = ChannelLookups(databases, dbc_channels).bindings
    selected = set(selected_channels)
    observed: dict[int, set[tuple[int, bool]]] = {}
    with can.LogReader(str(path)) as reader:
        for frame in reader:
            if getattr(frame, "is_error_frame", False) or getattr(frame, "is_remote_frame", False):
                continue
            channel = frame_channel(frame)
            if channel <= 0 or (selected and channel not in selected):
                continue
            observed.setdefault(channel, set()).add(
                (int(frame.arbitration_id), bool(frame.is_extended_id))
            )

    channels = sorted(selected) if selected else sorted(observed)
    columns = ["channel", "can_id", "extended", "status", "dbc"]
    rows: list[dict[str, Any]] = []
    file_format = path.suffix.lstrip(".").upper()
    for channel in channels:
        file_ids = observed.get(channel)
        if file_ids is None:
            rows.append({"channel": channel, "can_id": "", "extended": None,
                         "status": f"CHANNEL NOT PRESENT IN {file_format}", "dbc": ""})
            continue

        id_to_dbcs: dict[tuple[int, bool], list[str]] = {}
        for name, database, binding in zip((p.name for p in paths), databases, bindings):
            if binding is not None and channel not in binding:
                continue
            for message in database.messages:
                key = (int(message.frame_id), bool(message.is_extended_frame))
                id_to_dbcs.setdefault(key, []).append(name)

        dbc_ids = set(id_to_dbcs)
        for can_id, is_extended in sorted(file_ids & dbc_ids):
            rows.append({"channel": channel, "can_id": fmt_can_id(can_id),
                         "extended": is_extended, "status": "MATCHED",
                         "dbc": ", ".join(dict.fromkeys(id_to_dbcs[(can_id, is_extended)]))})
        for can_id, is_extended in sorted(file_ids - dbc_ids):
            rows.append({"channel": channel, "can_id": fmt_can_id(can_id),
                         "extended": is_extended, "status": f"UNMATCHED IN {file_format}", "dbc": ""})
        for can_id, is_extended in sorted(dbc_ids - file_ids):
            rows.append({"channel": channel, "can_id": fmt_can_id(can_id),
                         "extended": is_extended, "status": "UNMATCHED IN DBC",
                         "dbc": ", ".join(dict.fromkeys(id_to_dbcs[(can_id, is_extended)]))})
    return pd.DataFrame(rows, columns=columns)


def _decode_mf4(
    path: Path, dbc_paths: list[Path], settings: ComparisonSettings, dbc_channels: list[Any] | None = None
) -> DecoderResult:
    try:
        from asammdf import MDF
    except ImportError:
        return DecoderResult.failed("python", "asammdf is not installed (pip install -r requirements.txt).")
    warnings: list[str] = []
    stats: dict[str, Any] = {}
    chans = list(dbc_channels) if dbc_channels is not None else [None] * len(dbc_paths)
    if len(chans) != len(dbc_paths):
        return DecoderResult.failed("python", "DBC channel list does not match the DBC files.")
    bindings = ChannelLookups([object() for _ in dbc_paths], chans).bindings
    mdf = None
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
            # One extract_bus_logging run per bound channel (asammdf database_files syntax: (dbc, channel);
            # channel 0 == "any CAN channel"). DBCs bound to "any" are added to every channel run.
            bound = sorted({c for binding in bindings if binding is not None for c in binding})
            any_dbcs = any(binding is None for binding in bindings)
            found = mf4_can_channels(mdf)  # real bus channels stored in the file
            stats["mf4_can_channels"] = found
            stats["channels_in_file"] = found
            runs: list[tuple[int, list[tuple[str, int]]]]
            selected = set(settings.decode_channels)
            if found:
                # "Any" is bus 0 to asammdf and therefore extracts every bus.
                # Each run is filtered by the extracted signal's actual CANx source,
                # so Any-bound signals are never relabelled as the current loop channel.
                cand = [c for c in found if (any_dbcs or c in bound) and (not selected or c in selected)]
                runs = [
                    (
                        ch,
                        [
                            (str(path), ch if ch > 0 else 0)
                            for path, binding in zip(dbc_paths, bindings)
                            if binding is None or ch in binding
                        ],
                    )
                    for ch in cand
                ]
            elif bound:
                cand = [ch for ch in bound if not selected or ch in selected]
                runs = [
                    (
                        ch,
                        [
                            (str(path), ch if ch > 0 else 0)
                            for path, binding in zip(dbc_paths, bindings)
                            if binding is None or ch in binding
                        ],
                    )
                    for ch in cand
                ]
            elif selected:
                runs = [(ch, [(str(path), 0) for path in dbc_paths]) for ch in sorted(selected)]
            else:
                runs = [(0, [(str(path), 0) for path in dbc_paths])]
                warnings.append("CAN channel numbers could not be read from this MF4; signals have no channel. "
                                "Bind the DBC to a channel in the DBC manager if you need channel-wise results.")
            parts: list[pd.DataFrame] = []
            channels_without_signals: list[int] = []
            for ch, pairs in runs:
                label = f"channel {ch}" if found or selected else "all channels"
                decoded = None
                try:
                    dbs, _ = _load_databases([Path(p) for p, _c in pairs])
                    decoded = mdf.extract_bus_logging(database_files={"CAN": pairs})
                    part = _signals_to_frame(
                        _iter_mdf_signals(decoded),
                        warnings,
                        ch,
                        _signal_meta(dbs),
                        expected_channel=ch if found or selected else None,
                        choice_values=_choice_values(dbs),
                    )
                except Exception as exc:
                    channels_without_signals.append(ch)
                    warnings.append(f"{label}: no signals decoded ({type(exc).__name__}: {redact_text(exc)}).")
                    continue
                finally:
                    try:
                        if decoded is not None:
                            decoded.close()
                    except Exception:
                        pass
                if part.empty:
                    channels_without_signals.append(ch)
                    warnings.append(
                        f"{label}: extract_bus_logging produced no signals (DBC does not match the logged CAN IDs/channel?)."
                    )
                else:
                    parts.append(part)
            frame = normalize_frame(pd.concat(parts, ignore_index=True)) if parts else normalize_frame(None)
            stats["channels_without_decoded_signals"] = sorted(set(channels_without_signals))
        else:
            warnings.append(
                "MF4 has no raw CAN bus logging; its existing channels were read as-is and the DBC files "
                "were NOT applied."
            )
            if settings.decode_channels:
                warnings.append("CAN channel selection was not applied because this MF4 has no raw CAN bus logging.")
            frame = _signals_to_frame(_iter_mdf_signals(mdf), warnings)
        stats["samples"] = int(len(frame))
        return DecoderResult(engine="python", state=EngineState.OK, data=frame, warnings=warnings, stats=stats)
    except Exception as exc:
        return DecoderResult.failed(
            "python", f"asammdf failed on '{path.name}': {type(exc).__name__}: {redact_text(exc)}", warnings
        )
    finally:
        try:
            if mdf is not None:
                mdf.close()
        except Exception:
            pass


def decode_measurement(
    path: str | Path,
    dbc_paths: list[str | Path],
    settings: ComparisonSettings,
    dbc_channels: list[Any] | None = None,
) -> DecoderResult:
    """Decode ``path`` with the Python reference engine.

    ``dbc_channels[i]`` binds ``dbc_paths[i]`` to one or more channels; ``None``
    and legacy integer 0 mean any. A tuple can explicitly bind to MF4 channel 0.
    """
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
        result = (
            _decode_mf4(path, dbcs, settings, dbc_channels) if ext in MDF_EXTS else _decode_can_log(path, dbcs, settings, dbc_channels)
        )
    except Exception as exc:  # last-resort guard
        result = DecoderResult.failed("python", f"Unexpected decoder error: {type(exc).__name__}: {redact_text(exc)}")
    if result.state == EngineState.OK:
        notes = channel_notices(
            [d.name for d in dbcs],
            list(dbc_channels) if dbc_channels is not None else [None] * len(dbcs),
            list(result.stats.get("channels_in_file") or []),
            int(getattr(settings, "python_channel", 0) or 0),
            settings.decode_channels,
        )
        if notes:
            result.stats["channel_notices"] = notes
            result.warnings.extend(f"CHANNEL NOT PRESENT: {n}" for n in notes)
        sel = int(getattr(settings, "python_channel", 0) or 0)
        data = result.data
        if settings.decode_channels and not data.empty and result.stats.get("mf4_has_can_bus_logging"):
            result.data = data[data["channel"].isin(settings.decode_channels)].reset_index(drop=True)
            result.stats["samples"] = int(len(result.data))
            data = result.data
        if sel > 0 and not data.empty and bool((data["channel"] > 0).any()):
            # a specific Python channel was chosen: only that channel is reported
            result.data = data[data["channel"] == sel].reset_index(drop=True)
            result.stats["samples"] = int(len(result.data))
    result.stats["seconds"] = round(time.time() - started, 3)
    return result
