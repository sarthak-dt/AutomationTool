"""DBC upload handling: validation, duplicate-name handling, conflict reporting.

Parsing uses ``cantools``. Conflict detection works on a small neutral
"catalog" (plain dataclasses) built from the parsed database, so the logic is
independent of cantools internals and unit-testable.

Rule when several selected DBCs define the same CAN ID: the DBC listed first
(top of the selection) wins in the Python decoder. Every such overlap is
reported as a conflict - nothing is ignored silently.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Iterable

from services.file_service import (
    ValidationError,
    sanitize_filename,
    sha256_bytes,
    unique_name,
    validate_upload,
)
from services.logging_utils import get_logger

log = get_logger("dbc")

DBC_EXTS = (".dbc",)


# ---------------------------------------------------------------------------
# Neutral catalog
# ---------------------------------------------------------------------------
@dataclass(frozen=True)
class SignalDef:
    name: str
    start: int
    length: int
    byte_order: str
    signed: bool
    scale: float
    offset: float
    unit: str


@dataclass(frozen=True)
class MessageDef:
    frame_id: int
    extended: bool
    name: str
    length: int
    signals: tuple[SignalDef, ...]

    @property
    def key(self) -> tuple[int, bool]:
        return (self.frame_id, self.extended)

    def layout(self) -> tuple:
        return (
            self.name,
            self.length,
            tuple(sorted((s.name, s.start, s.length, s.byte_order, s.signed, s.scale, s.offset) for s in self.signals)),
        )


def catalog_from_db(db: Any) -> list[MessageDef]:
    """Convert a cantools Database (or any duck-typed equivalent)."""
    out: list[MessageDef] = []
    for m in db.messages:
        sigs = tuple(
            SignalDef(
                name=str(s.name),
                start=int(s.start),
                length=int(s.length),
                byte_order=str(s.byte_order),
                signed=bool(s.is_signed),
                scale=float(s.scale),
                offset=float(s.offset),
                unit=str(s.unit or ""),
            )
            for s in m.signals
        )
        out.append(
            MessageDef(
                frame_id=int(m.frame_id),
                extended=bool(m.is_extended_frame),
                name=str(m.name),
                length=int(m.length),
                signals=sigs,
            )
        )
    return out


# ---------------------------------------------------------------------------
# Parsing / validation
# ---------------------------------------------------------------------------
@dataclass
class DbcInfo:
    status: str  # VALID | VALID_WITH_WARNINGS | INVALID
    n_messages: int = 0
    n_signals: int = 0
    encoding: str = "utf-8"
    warnings: list[str] = field(default_factory=list)
    error: str | None = None

    @property
    def usable(self) -> bool:
        return self.status != "INVALID"


@dataclass
class DbcFile:
    name: str  # unique display / on-disk name
    original_name: str
    data: bytes
    sha256: str
    info: DbcInfo
    catalog: list[MessageDef] = field(default_factory=list, repr=False)
    channel: int = 0  # legacy binding field; 0 = Any, retained for existing callers
    channel_bindings: tuple[int, ...] | None = field(default=None, repr=False)


def dbc_channel_bindings(dbc: DbcFile) -> tuple[int, ...] | None:
    """Return explicit channel bindings; ``None`` means all channels.

    ``channel`` is retained for compatibility with existing callers, where 0
    means Any. ``channel_bindings`` can distinguish an explicit MF4 channel 0
    from Any.
    """
    if dbc.channel_bindings is not None:
        return tuple(dbc.channel_bindings) or None
    return (dbc.channel,) if dbc.channel > 0 else None


def dbc_channel_label(dbc: DbcFile) -> str:
    bindings = dbc_channel_bindings(dbc)
    return "Any" if bindings is None else ", ".join(f"channel {channel}" for channel in bindings)


def _decode_text(data: bytes) -> tuple[str, str]:
    for enc in ("utf-8-sig", "cp1252", "latin-1"):
        try:
            return data.decode(enc), ("utf-8" if enc == "utf-8-sig" else enc)
        except UnicodeDecodeError:
            continue
    return data.decode("latin-1", errors="replace"), "latin-1"


def parse_dbc(data: bytes) -> tuple[Any, DbcInfo]:
    """Parse DBC bytes with cantools. Returns (database or None, info)."""
    try:
        import cantools
    except ImportError:
        return None, DbcInfo(
            status="INVALID", error="cantools is not installed (pip install -r requirements.txt)."
        )
    if b"\x00" in data[:4096]:
        return None, DbcInfo(status="INVALID", error="File looks binary, not a text DBC.")
    text, encoding = _decode_text(data)
    warnings: list[str] = []
    if encoding != "utf-8":
        warnings.append(f"File is not valid UTF-8; decoded as {encoding}.")
    db = None
    try:
        db = cantools.database.load_string(text, database_format="dbc", strict=True)
    except Exception as strict_exc:
        try:
            db = cantools.database.load_string(text, database_format="dbc", strict=False)
            warnings.append(f"Strict validation failed (loaded in lenient mode): {strict_exc}")
        except Exception as exc:
            return None, DbcInfo(
                status="INVALID",
                encoding=encoding,
                error=f"Not a valid DBC: {exc}",
            )
    n_msg = len(db.messages)
    n_sig = sum(len(m.signals) for m in db.messages)
    if n_msg == 0:
        # Some CANdb++ exports contain a VECTOR__INDEPENDENT_SIG_MSG record
        # with length 0. cantools intentionally drops that pseudo-message, so
        # parsing succeeds while the resulting database cannot decode frames.
        # Report this separately from malformed DBC syntax to make the upload
        # failure actionable.
        placeholder_only = (
            " The file contains only a zero-length VECTOR__INDEPENDENT_SIG_MSG placeholder; "
            "add real BO_ CAN message definitions to decode it."
            if "VECTOR__INDEPENDENT_SIG_MSG" in text and "BO_ " in text
            else " Add at least one BO_ CAN message definition to decode it."
        )
        return db, DbcInfo(
            status="INVALID",
            encoding=encoding,
            error="DBC contains no usable CAN message definitions." + placeholder_only,
        )
    status = "VALID_WITH_WARNINGS" if warnings else "VALID"
    return db, DbcInfo(
        status=status, n_messages=n_msg, n_signals=n_sig, encoding=encoding, warnings=warnings
    )


def build_dbc_file(display_name: str, original_name: str, data: bytes) -> DbcFile:
    db, info = parse_dbc(data)
    catalog = catalog_from_db(db) if (db is not None and info.usable) else []
    return DbcFile(
        name=display_name,
        original_name=original_name,
        data=data,
        sha256=sha256_bytes(data),
        info=info,
        catalog=catalog,
    )


def add_uploads(
    library: list[DbcFile],
    uploads: Iterable[tuple[str, bytes]],
    max_files: int,
    max_bytes: int,
) -> tuple[list[DbcFile], list[tuple[str, str]]]:
    """Add uploaded DBCs to ``library``.

    Returns ``(new_library, messages)`` where messages are ``(level, text)``
    with level in {"success", "warning", "error"}.
    """
    lib = list(library)
    messages: list[tuple[str, str]] = []
    for raw_name, data in uploads:
        try:
            clean = validate_upload(raw_name, len(data), DBC_EXTS, max_bytes)
        except ValidationError as exc:
            messages.append(("error", str(exc)))
            continue
        if len(lib) >= max_files:
            messages.append(("error", f"'{clean}' not added: limit of {max_files} DBC files reached."))
            continue
        digest = sha256_bytes(data)
        same_name = [d for d in lib if d.name.lower() == clean.lower()]
        if same_name and any(d.sha256 == digest for d in same_name):
            messages.append(("warning", f"'{clean}' is already uploaded with identical content - skipped."))
            continue
        same_content = [d for d in lib if d.sha256 == digest]
        display = unique_name(clean, {d.name for d in lib})
        if display != clean:
            messages.append(
                ("warning", f"'{clean}' has the same name as an existing DBC but different content; stored as '{display}'.")
            )
        entry = build_dbc_file(display, raw_name, data)
        if same_content:
            entry.info.warnings.append(f"Identical content to '{same_content[0].name}'.")
            if entry.info.status == "VALID":
                entry.info.status = "VALID_WITH_WARNINGS"
            messages.append(("warning", f"'{display}' has identical content to '{same_content[0].name}'."))
        lib.append(entry)
        if entry.info.usable:
            messages.append(
                (
                    "success",
                    f"'{display}': {entry.info.n_messages} messages, {entry.info.n_signals} signals.",
                )
            )
        else:
            messages.append(("error", f"'{display}' rejected: {entry.info.error}"))
    return lib, messages


# ---------------------------------------------------------------------------
# Conflict detection
# ---------------------------------------------------------------------------
@dataclass
class Conflict:
    kind: str  # MESSAGE_ID_CONFLICT | DUPLICATE_MESSAGE | SIGNAL_NAME_COLLISION | MESSAGE_NAME_COLLISION
    severity: str  # error | warning | info
    detail: str
    dbcs: tuple[str, ...] = ()

    def as_dict(self) -> dict[str, str]:
        return {
            "kind": self.kind,
            "severity": self.severity,
            "dbcs": ", ".join(self.dbcs),
            "detail": self.detail,
        }


def detect_conflicts_by_channel(dbcs: list[DbcFile]) -> list[Conflict]:
    """Like ``detect_conflicts`` but DBCs bound to different channels never conflict.

    Two DBCs can only overlap on a channel both of them apply to (same channel, or one is "any").
    """
    usable = [d for d in dbcs if d.info.usable]
    bound = sorted(
        {
            channel
            for dbc in usable
            if (bindings := dbc_channel_bindings(dbc)) is not None
            for channel in bindings
        }
    )
    if not bound:
        return detect_conflicts(usable)
    seen: set[str] = set()
    out: list[Conflict] = []
    for ch in bound:
        for dbc in detect_conflicts(
            [d for d in usable if (bindings := dbc_channel_bindings(d)) is None or ch in bindings]
        ):
            key = repr(dbc.as_dict())
            if key not in seen:
                seen.add(key)
                out.append(dbc)
    return out


def detect_conflicts(dbcs: list[DbcFile]) -> list[Conflict]:
    """Report overlapping/conflicting definitions across the given (ordered) DBCs."""
    conflicts: list[Conflict] = []
    usable = [d for d in dbcs if d.info.usable]

    by_id: dict[tuple[int, bool], list[tuple[str, MessageDef]]] = {}
    by_msg_name: dict[str, list[tuple[str, MessageDef]]] = {}
    sig_owner: dict[str, list[tuple[str, MessageDef]]] = {}
    for d in usable:
        for m in d.catalog:
            by_id.setdefault(m.key, []).append((d.name, m))
            by_msg_name.setdefault(m.name, []).append((d.name, m))
            for s in m.signals:
                sig_owner.setdefault(s.name, []).append((d.name, m))

    for key, entries in by_id.items():
        if len(entries) < 2:
            continue
        frame_id, ext = key
        id_txt = f"0x{frame_id:X}{' (extended)' if ext else ''}"
        first_dbc, first_msg = entries[0]
        for other_dbc, other_msg in entries[1:]:
            names = (first_dbc, other_dbc)
            if first_msg.layout() == other_msg.layout():
                conflicts.append(
                    Conflict(
                        "DUPLICATE_MESSAGE",
                        "info",
                        f"CAN ID {id_txt} ('{first_msg.name}') is defined identically in both; "
                        f"'{first_dbc}' is used.",
                        names,
                    )
                )
            else:
                diffs = []
                if first_msg.name != other_msg.name:
                    diffs.append(f"message name '{first_msg.name}' vs '{other_msg.name}'")
                if first_msg.length != other_msg.length:
                    diffs.append(f"length {first_msg.length} vs {other_msg.length}")
                a = {s.name: s for s in first_msg.signals}
                b = {s.name: s for s in other_msg.signals}
                only_a = sorted(set(a) - set(b))
                only_b = sorted(set(b) - set(a))
                if only_a:
                    diffs.append(f"signals only in '{first_dbc}': {', '.join(only_a[:5])}")
                if only_b:
                    diffs.append(f"signals only in '{other_dbc}': {', '.join(only_b[:5])}")
                changed = sorted(n for n in set(a) & set(b) if a[n] != b[n])
                if changed:
                    diffs.append(f"signal layout/scaling differs: {', '.join(changed[:5])}")
                conflicts.append(
                    Conflict(
                        "MESSAGE_ID_CONFLICT",
                        "error",
                        f"CAN ID {id_txt} is defined differently ({'; '.join(diffs) or 'different definition'}). "
                        f"Decoding uses '{first_dbc}'; '{other_dbc}' is ignored for this ID.",
                        names,
                    )
                )

    for name, entries in by_msg_name.items():
        keys = {m.key for _, m in entries}
        if len(keys) > 1:
            conflicts.append(
                Conflict(
                    "MESSAGE_NAME_COLLISION",
                    "warning",
                    f"Message name '{name}' is used for different CAN IDs: "
                    + ", ".join(f"0x{k[0]:X}" for k in sorted(keys)),
                    tuple(sorted({d for d, _ in entries})),
                )
            )

    for name, entries in sig_owner.items():
        keys = {m.key for _, m in entries}
        if len(keys) > 1:
            msgs = sorted({m.name for _, m in entries})
            conflicts.append(
                Conflict(
                    "SIGNAL_NAME_COLLISION",
                    "warning",
                    f"Signal '{name}' exists in several messages ({', '.join(msgs[:4])}). "
                    "Samples are merged under one signal name in the comparison.",
                    tuple(sorted({d for d, _ in entries})),
                )
            )
    return conflicts


def conflicts_summary(conflicts: list[Conflict]) -> dict[str, int]:
    out = {"error": 0, "warning": 0, "info": 0}
    for c in conflicts:
        out[c.severity] = out.get(c.severity, 0) + 1
    return out
