"""Channel compatibility gate (CAN bus channel of the Python output vs. the Java output).

Rules
-----
* ``python_channel`` / ``java_channel`` = 0 means "all channels": outputs are compared only where
  both report the same known channel. Unknown channel metadata never qualifies for a MATCH.
* Same channel number on both sides -> compatible.
* Different numbers -> MISMATCH. Values are never compared across channel numbers; signal
  names or a user-confirmed mapping do not change the channel identity.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Any


@dataclass(frozen=True)
class ChannelGate:
    python: int  # 0 = all channels
    java: int
    mapped: bool  # user confirmed equivalence
    active: bool  # a specific channel is selected
    same: bool
    compatible: bool
    message: str
    short: str  # one-line note stored in channel mismatch rows

    def as_dict(self) -> dict[str, Any]:
        return asdict(self)


def _name(n: int) -> str:
    return f"Channel {n}" if n else "All channels"


def channel_gate(settings: Any) -> ChannelGate:
    py = int(getattr(settings, "python_channel", 0) or 0)
    ja = int(getattr(settings, "java_channel", 0) or 0)
    mapped = False
    if py and not ja:
        ja = py
    elif ja and not py:
        py = ja
    if not py and not ja:
        msg = ("All channels: values are compared only for signals reported on the same known CAN channel by both decoders. "
               "Known channel differences are MISMATCH; unknown channel metadata is NOT_COMPARABLE. Signal identity and timestamp/sample alignment must still be verified.")
        return ChannelGate(0, 0, mapped, False, True, True, msg, "")
    if py == ja:
        msg = (f"Python Channel {py} ↔ Java Channel {ja}: channel-compatible. Signal identity and timestamp/sample alignment "
               "must still be verified before comparing values.")
        return ChannelGate(py, ja, mapped, True, True, True, msg, "")
    msg = (f"MISMATCH: Python Channel {py} and Java Channel {ja} differ. A row cannot be MATCH when channel assignments differ.")
    short = f"Channel mismatch: Python channel {py} vs Java channel {ja}; channel assignments differ, so this row cannot be a MATCH."
    return ChannelGate(py, ja, False, True, False, False, msg, short)
