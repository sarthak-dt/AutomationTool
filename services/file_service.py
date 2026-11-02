"""Safe file handling: filename sanitising, path containment, upload limits,
per-job temporary workspaces and stale temp cleanup."""
from __future__ import annotations

import hashlib
import re
import shutil
import tempfile
import time
from pathlib import Path

from services.logging_utils import get_logger

log = get_logger("files")

_SAFE_CHARS_RE = re.compile(r"[^A-Za-z0-9._ \-()]")
TEMP_PREFIX = "decodercmp_"


class ValidationError(ValueError):
    """User-facing validation problem (bad extension, too large, empty...)."""


class UnsafePathError(ValueError):
    """A path escaped its base directory."""


def human_size(num_bytes: float | int | None) -> str:
    if num_bytes is None:
        return "?"
    size = float(num_bytes)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if size < 1024 or unit == "TB":
            return f"{int(size)} {unit}" if unit == "B" else f"{size:.1f} {unit}"
        size /= 1024
    return f"{size:.1f} TB"


def sanitize_filename(name: str, default: str = "file", max_len: int = 120) -> str:
    """Return a harmless basename: no directories, no odd characters."""
    base = str(name).replace("\\", "/").split("/")[-1]
    base = base.replace("\x00", "")
    base = _SAFE_CHARS_RE.sub("_", base).strip(" .")
    if not base:
        base = default
    if len(base) > max_len:
        stem, dot, ext = base.rpartition(".")
        if dot and len(ext) <= 10:
            base = stem[: max_len - len(ext) - 1] + "." + ext
        else:
            base = base[:max_len]
    return base


def safe_join(base: Path | str, *parts: str) -> Path:
    """Join ``parts`` under ``base``; raise if the result escapes ``base``."""
    base_path = Path(base).resolve()
    target = base_path.joinpath(*parts).resolve()
    try:
        target.relative_to(base_path)
    except ValueError as exc:
        raise UnsafePathError(f"Path escapes base directory: {parts!r}") from exc
    return target


def unique_name(name: str, existing: set[str] | list[str]) -> str:
    """``a.dbc`` -> ``a (2).dbc`` when ``a.dbc`` is taken (case-insensitive)."""
    taken = {e.lower() for e in existing}
    if name.lower() not in taken:
        return name
    stem, dot, ext = name.rpartition(".")
    if not dot:
        stem, ext = name, ""
    n = 2
    while True:
        candidate = f"{stem} ({n}){'.' + ext if ext else ''}"
        if candidate.lower() not in taken:
            return candidate
        n += 1


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def validate_upload(
    name: str, size: int, allowed_exts: tuple[str, ...], max_bytes: int
) -> str:
    """Validate an uploaded/selected file; return the sanitized filename."""
    clean = sanitize_filename(name)
    ext = Path(clean).suffix.lower()
    if ext not in allowed_exts:
        raise ValidationError(
            f"'{clean}': unsupported file type '{ext or 'none'}'. "
            f"Allowed: {', '.join(allowed_exts)}"
        )
    if size <= 0:
        raise ValidationError(f"'{clean}' is empty.")
    if size > max_bytes:
        raise ValidationError(
            f"'{clean}' is {human_size(size)}, which exceeds the limit of {human_size(max_bytes)}."
        )
    return clean


class JobWorkspace:
    """Temporary working directory for one job; always cleaned up.

    Usage::

        with JobWorkspace() as ws:
            p = ws.write_bytes("dbc", "a.dbc", data)
    """

    def __init__(self, root: str | Path | None = None):
        self._root_arg = str(root) if root else None
        self.path: Path | None = None

    def __enter__(self) -> "JobWorkspace":
        self.create()
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.cleanup()

    def create(self) -> Path:
        if self.path is None:
            if self._root_arg:
                Path(self._root_arg).mkdir(parents=True, exist_ok=True)
            self.path = Path(
                tempfile.mkdtemp(prefix=TEMP_PREFIX, dir=self._root_arg or None)
            ).resolve()
            for sub in ("dbc", "measurements"):
                (self.path / sub).mkdir()
        return self.path

    def path_for(self, kind: str, filename: str) -> Path:
        base = self.create()
        return safe_join(base, kind, sanitize_filename(filename))

    def write_bytes(self, kind: str, filename: str, data: bytes) -> Path:
        dest = self.path_for(kind, filename)
        dest.write_bytes(data)
        return dest

    def cleanup(self) -> None:
        if self.path is not None and self.path.exists():
            shutil.rmtree(self.path, ignore_errors=True)
            log.debug("Removed workspace %s", self.path.name)
        self.path = None


def cleanup_stale_workspaces(root: str | Path | None = None, max_age_hours: float = 24) -> int:
    """Delete leftover ``decodercmp_*`` temp dirs older than ``max_age_hours``."""
    base = Path(root) if root else Path(tempfile.gettempdir())
    if not base.exists():
        return 0
    cutoff = time.time() - max_age_hours * 3600
    removed = 0
    for child in base.glob(f"{TEMP_PREFIX}*"):
        try:
            if child.is_dir() and child.stat().st_mtime < cutoff:
                shutil.rmtree(child, ignore_errors=True)
                removed += 1
        except OSError:
            continue
    return removed
