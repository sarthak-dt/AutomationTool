#!/usr/bin/env python3
"""Collect every page of POST /api/v1/decoder/validate schema 2.0.

The default response keys signals by name. This client appends the parallel
arrays across cursors and checks that a split signal keeps one identity.
It does not decode measurements and it does not call asammdf.
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request

ARRAYS = (
    "timestamps",
    "timestampsNs",
    "samples",
    "rawSamples",
    "textValues",
    "validity",
    "sampleIndex",
    "sourceDatetime",
)


def post(url: str, body: dict, timeout: float) -> dict:
    data = json.dumps(body).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise SystemExit(f"HTTP {error.code}: {detail}") from error
    if payload.get("schemaVersion") != "2.0":
        raise SystemExit(f"expected schemaVersion 2.0, got {payload.get('schemaVersion')}")
    if payload.get("status") not in ("COMPLETED", "INCOMPLETE"):
        raise SystemExit(json.dumps(payload, indent=2))
    return payload


def _append(merged: dict, order: list[str], signal_key: str, signal: dict) -> int:
    lengths = []
    for name in ARRAYS:
        column = signal.get(name)
        if not isinstance(column, list):
            raise SystemExit(f"{signal_key}.{name} is not an array")
        lengths.append(len(column))
    if len(set(lengths)) != 1:
        raise SystemExit(f"{signal_key} parallel arrays differ: {dict(zip(ARRAYS, lengths))}")
    if signal_key not in merged:
        order.append(signal_key)
        merged[signal_key] = {
            field: value for field, value in signal.items() if field not in ARRAYS and field != "returnedSampleCount"
        }
        for name in ARRAYS:
            merged[signal_key][name] = []
    else:
        previous = merged[signal_key].get("signalIdentity")
        current = signal.get("signalIdentity")
        if previous != current:
            raise SystemExit(f"{signal_key} changed identity across pages")
    for name in ARRAYS:
        merged[signal_key][name].extend(signal[name])
    return lengths[0]


def collect(url: str, request: dict, timeout: float) -> dict:
    page = post(url, request, timeout)
    total_samples = page.get("totalSampleCount")
    total_signals = page.get("totalSignalCount")
    merged: dict = {}
    order: list[str] = []
    returned = 0
    pages = 0
    while True:
        pages += 1
        signals = page.get("signals")
        if not isinstance(signals, dict):
            raise SystemExit("signals must be a JSON object")
        if len(signals) != len(set(signals)):
            raise SystemExit("duplicate signal keys on one page")
        for key, signal in signals.items():
            if not isinstance(signal, dict):
                raise SystemExit(f"{key} is not an object")
            if signal.get("name") in (None, ""):
                raise SystemExit(f"{key} has no name")
            returned += _append(merged, order, key, signal)
        if page.get("paginationComplete") is True:
            break
        cursor = page.get("nextCursor")
        if not cursor:
            raise SystemExit("pagination is incomplete and nextCursor is empty")
        page = post(url, {"cursor": cursor}, timeout)
        if page.get("totalSampleCount") != total_samples:
            raise SystemExit("totalSampleCount changed between pages")
    if returned != total_samples:
        raise SystemExit(f"returned {returned} samples, totalSampleCount is {total_samples}")
    if len(merged) != total_signals:
        raise SystemExit(f"collected {len(merged)} signals, totalSignalCount is {total_signals}")
    identities = [item["signalIdentity"] for item in merged.values()]
    if len(identities) != len(set(identities)):
        raise SystemExit("two JSON keys share one signalIdentity")
    for key in order:
        signal = merged[key]
        indexes = signal["sampleIndex"]
        expected = [str(index) for index in range(len(indexes))]
        if indexes != expected:
            raise SystemExit(f"{key} sampleIndex is not 0..{len(indexes) - 1}")
        if signal.get("sampleCount") != len(indexes):
            raise SystemExit(f"{key} sampleCount does not match the collected arrays")
        signal["returnedSampleCount"] = len(indexes)
    result = dict(page)
    result["signals"] = {key: merged[key] for key in order}
    result["returnedSampleCount"] = returned
    result["paginationComplete"] = True
    result["nextCursor"] = None
    result["pagesFetched"] = pages
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description="Collect a schema 2.0 decoder validation result")
    parser.add_argument("--url", default="http://127.0.0.1:8091/api/v1/decoder/validate")
    parser.add_argument("--request", required=True, help="JSON object, or @path to a JSON file")
    parser.add_argument("--timeout", type=float, default=120)
    args = parser.parse_args()
    raw = args.request[1:] if args.request.startswith("@") else args.request
    if args.request.startswith("@"):
        with open(raw, encoding="utf-8") as handle:
            request = json.load(handle)
    else:
        request = json.loads(raw)
    json.dump(collect(args.url, request, args.timeout), sys.stdout, indent=2)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
