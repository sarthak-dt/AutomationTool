"""Mapping of the Java parser API response (schemaVersion 2.0) to the app's normalized payload.

Based on the real sample ``JavaParser_SampleResponse.txt`` (columnar JSON)::

    status, file.fileName, dbcFiles, paginationComplete, nextCursor, diagnostics[],
    signals: { "<key>": {name, unit, messageName, canId, channel, availability,
                          timestamps[], samples[], rawSamples[], textValues[], validity[]} }

Rules (only what the response really contains is used):
* ``status`` must be COMPLETED, otherwise the response is rejected.
* One entry per signal, CAN ID *and CAN channel* (as in the response). The channel and
  CAN ID are kept, so the same signal on channel 1 and channel 2 stays separate.
  Entries with identical (name, canId, channel) are merged by time.
* ``samples`` are the physical values. ``textValues`` (DBC labels) are ignored, so
  the comparison is numeric like the Python output. Non-finite values -> NaN.
* Samples whose ``validity`` is not VALID are dropped (and counted in a warning).
* ``timestamps`` (seconds) are used as returned. The API states that no origin is
  subtracted; the Python MF4 decoder also uses asammdf's measurement-relative time.
* ``diagnostics`` are passed on as warnings; ``paginationComplete=false`` is flagged as partial.
"""
from __future__ import annotations

import math
from typing import Any

from services.java_decoder_client import JavaResponseError
from services.models import fmt_can_id

MAX_DIAGNOSTICS = 12


def _num(x: Any) -> float:
    if isinstance(x, bool) or x is None or isinstance(x, dict):
        return math.nan  # null / {"representation": "non-finite"} objects
    try:
        return float(x)
    except (TypeError, ValueError):
        return math.nan


def source_file_name(payload: dict[str, Any]) -> str | None:
    f = payload.get("file")
    if isinstance(f, dict) and f.get("fileName"):
        return str(f["fileName"])
    mf = payload.get("measurementFiles")
    if isinstance(mf, list) and len(mf) == 1:
        return str(mf[0])
    return None


def map_java_parser_response(payload: Any) -> dict[str, Any]:
    """Return ``{"signals": [...], "warnings": [...]}`` (see ``validate_normalized_payload``)."""
    if not isinstance(payload, dict) or not isinstance(payload.get("signals"), dict):
        raise JavaResponseError("Unexpected Java response: expected an object with a 'signals' object.")
    status = str(payload.get("status", "")).upper()
    if status != "COMPLETED":
        raise JavaResponseError(f"Java parser status is '{payload.get('status')}', expected COMPLETED.")

    warnings: list[str] = []
    diags = [str(d) for d in (payload.get("diagnostics") or [])]
    warnings.extend("diagnostic: " + d for d in diags[:MAX_DIAGNOSTICS])
    if len(diags) > MAX_DIAGNOSTICS:
        warnings.append(f"{len(diags) - MAX_DIAGNOSTICS} more diagnostic message(s) not shown.")
    if payload.get("paginationComplete") is False:
        warnings.append(
            "Response is PARTIAL (paginationComplete=false, more pages exist). Missing samples will show as MISSING_IN_JAVA."
        )

    groups: dict[str, dict[str, Any]] = {}
    dropped_invalid = 0
    for key, s in payload["signals"].items():
        if not isinstance(s, dict) or not s.get("name"):
            raise JavaResponseError(f"Signal entry '{str(key)[:60]}' has no 'name'.")
        name = str(s["name"])
        if str(s.get("availability", "AVAILABLE")).upper() != "AVAILABLE":
            warnings.append(f"signal '{name}' (channel {s.get('channel')}) is {s.get('availability')}; skipped.")
            continue
        ts, vals = s.get("timestamps"), s.get("samples")
        if not isinstance(ts, list) or not isinstance(vals, list) or len(ts) != len(vals):
            raise JavaResponseError(f"signal '{name}': 'timestamps' and 'samples' must be lists of equal length.")
        validity = s.get("validity")
        if validity is not None and (not isinstance(validity, list) or len(validity) != len(ts)):
            raise JavaResponseError(f"signal '{name}': 'validity' must have one entry per sample.")
        try:
            channel = int(s.get("channel") or 0)
        except (TypeError, ValueError):
            channel = 0
        can_id = fmt_can_id(s.get("canIdHex") or s.get("canId"))
        g = groups.setdefault(
            (name, channel, can_id),
            {"unit": str(s.get("unit") or ""), "message": str(s.get("messageName") or ""), "rows": [], "entries": 0},
        )
        g["entries"] += 1
        for i, (t, v) in enumerate(zip(ts, vals)):
            if validity is not None and str(validity[i]).upper() != "VALID":
                dropped_invalid += 1
                continue
            tt = _num(t)
            if math.isnan(tt) or math.isinf(tt):
                raise JavaResponseError(f"signal '{name}': timestamp {i} is not a finite number.")
            g["rows"].append((tt, _num(v)))

    out = []
    for (name, channel, can_id), g in groups.items():
        if g["entries"] > 1:
            warnings.append(f"signal '{name}' (CAN ID {can_id or '?'}, channel {channel}) occurs {g['entries']} times; samples merged by time.")
        rows = sorted(g["rows"], key=lambda r: r[0])
        out.append(
            {
                "name": name,
                "unit": g["unit"],
                "message": g["message"],
                "can_id": can_id,
                "channel": channel,
                "channel_known": s.get("channel") is not None,
                "timestamps": [r[0] for r in rows],
                "values": [r[1] for r in rows],
            }
        )
    if dropped_invalid:
        warnings.append(f"{dropped_invalid} sample(s) with validity other than VALID were not used.")
    return {"signals": out, "warnings": warnings}
