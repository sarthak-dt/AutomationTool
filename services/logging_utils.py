"""Secret-free logging helpers.

``register_secret`` records sensitive strings (e.g. the S3 secret key typed in
the UI). A logging filter then replaces them with ``***`` in every record, and
``redact_text`` / ``redact_mapping`` can be used when building reports.
"""
from __future__ import annotations

import logging
import re
import threading
from typing import Any, Iterable

REDACTED = "***REDACTED***"

_SENSITIVE_KEY_RE = re.compile(
    r"(secret|password|passwd|token|api[_-]?key|access[_-]?key|authorization|credential|session)",
    re.IGNORECASE,
)
# AWS access key ids look like AKIA/ASIA + 16 chars. Redact them defensively.
_AWS_KEY_ID_RE = re.compile(r"\b(AKIA|ASIA)[A-Z0-9]{16}\b")
_BEARER_RE = re.compile(r"(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]{6,}")

_lock = threading.Lock()
_secrets: set[str] = set()


def register_secret(value: str | None) -> None:
    """Remember a secret so it can be scrubbed from logs/messages."""
    if value and len(value) >= 4:
        with _lock:
            _secrets.add(value)


def clear_secrets() -> None:
    with _lock:
        _secrets.clear()


def redact_text(text: Any) -> str:
    """Remove registered secrets and credential-looking tokens from ``text``."""
    s = str(text)
    with _lock:
        known = sorted(_secrets, key=len, reverse=True)
    for secret in known:
        s = s.replace(secret, REDACTED)
    s = _AWS_KEY_ID_RE.sub(REDACTED, s)
    s = _BEARER_RE.sub(lambda m: f"{m.group(1)} {REDACTED}", s)
    return s


def is_sensitive_key(key: str) -> bool:
    return bool(_SENSITIVE_KEY_RE.search(str(key)))


def redact_mapping(obj: Any) -> Any:
    """Recursively replace values of sensitive-looking keys in dicts/lists."""
    if isinstance(obj, dict):
        out = {}
        for k, v in obj.items():
            if is_sensitive_key(k) and v not in (None, "", False):
                out[k] = REDACTED
            else:
                out[k] = redact_mapping(v)
        return out
    if isinstance(obj, (list, tuple)):
        return [redact_mapping(v) for v in obj]
    if isinstance(obj, str):
        return redact_text(obj)
    return obj


class SecretFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        try:
            message = record.getMessage()
        except Exception:
            return True
        redacted = redact_text(message)
        if redacted != message:
            record.msg = redacted
            record.args = ()
        return True


_configured = False


def setup_logging(level: str = "INFO") -> logging.Logger:
    """Configure the root app logger once; attach the secret filter."""
    global _configured
    logger = logging.getLogger("decoder_comparator")
    if not _configured:
        handler = logging.StreamHandler()
        handler.setFormatter(
            logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s")
        )
        handler.addFilter(SecretFilter())
        logger.addHandler(handler)
        logger.propagate = False
        _configured = True
    logger.setLevel(getattr(logging, level.upper(), logging.INFO))
    return logger


def get_logger(name: str) -> logging.Logger:
    return logging.getLogger(f"decoder_comparator.{name}")


def redact_all(values: Iterable[Any]) -> list[str]:
    return [redact_text(v) for v in values]
