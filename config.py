"""Application configuration.

All values come from environment variables (optionally loaded from a local
``.env`` file). Nothing secret is hard-coded. S3 credentials are intentionally
*not* required here: the recommended production setup is an IAM role / the
default AWS credential chain. If you do put access keys in ``.env`` they are
only used to pre-fill the S3 form and are never written to logs or reports.
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

try:  # python-dotenv is optional at import time
    from dotenv import load_dotenv

    load_dotenv()
except Exception:  # pragma: no cover - dotenv missing is not fatal
    pass

APP_NAME = "Automotive Decoder Comparator"
APP_VERSION = "1.0.0"
BASE_DIR = Path(__file__).resolve().parent

ALLOWED_MEASUREMENT_EXTS = (".mf4", ".blf", ".log", ".asc")
ALLOWED_DBC_EXTS = (".dbc",)


def _env_str(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


def _env_int(name: str, default: int) -> int:
    raw = os.environ.get(name, "").strip()
    if not raw:
        return default
    try:
        return int(raw)
    except ValueError:
        return default


def _env_float(name: str, default: float) -> float:
    raw = os.environ.get(name, "").strip()
    if not raw:
        return default
    try:
        return float(raw)
    except ValueError:
        return default


def _env_bool(name: str, default: bool) -> bool:
    raw = os.environ.get(name, "").strip().lower()
    if not raw:
        return default
    return raw in ("1", "true", "yes", "on")


@dataclass(frozen=True)
class AppConfig:
    # --- limits -----------------------------------------------------------
    max_dbc_size_mb: int = field(default_factory=lambda: _env_int("MAX_DBC_SIZE_MB", 10))
    max_dbc_files: int = field(default_factory=lambda: _env_int("MAX_DBC_FILES", 50))
    max_measurement_size_mb: int = field(
        default_factory=lambda: _env_int("MAX_MEASUREMENT_SIZE_MB", 2048)
    )
    max_files_per_job: int = field(default_factory=lambda: _env_int("MAX_FILES_PER_JOB", 25))
    max_s3_keys_listed: int = field(default_factory=lambda: _env_int("MAX_S3_KEYS_LISTED", 5000))

    # --- storage ----------------------------------------------------------
    temp_root: str = field(default_factory=lambda: _env_str("TEMP_ROOT", ""))
    data_dir: Path = field(
        default_factory=lambda: Path(_env_str("DATA_DIR", str(BASE_DIR / "data")))
    )
    stale_temp_hours: int = field(default_factory=lambda: _env_int("STALE_TEMP_HOURS", 24))

    # --- logging ----------------------------------------------------------
    log_level: str = field(default_factory=lambda: _env_str("LOG_LEVEL", "INFO").upper())

    # --- S3 form defaults (non-secret unless you choose to set keys) -------
    s3_bucket: str = field(default_factory=lambda: _env_str("S3_BUCKET"))
    s3_region: str = field(default_factory=lambda: _env_str("S3_REGION", "us-east-1"))
    s3_endpoint_url: str = field(default_factory=lambda: _env_str("S3_ENDPOINT_URL"))
    s3_port: str = field(default_factory=lambda: _env_str("S3_PORT"))
    s3_addressing_style: str = field(
        default_factory=lambda: _env_str("S3_ADDRESSING_STYLE", "auto").lower()
    )
    s3_access_key_id: str = field(default_factory=lambda: _env_str("S3_ACCESS_KEY_ID"))
    s3_secret_access_key: str = field(default_factory=lambda: _env_str("S3_SECRET_ACCESS_KEY"))

    # --- Java decoder adapter --------------------------------------------
    java_enabled: bool = field(default_factory=lambda: _env_bool("JAVA_API_ENABLED", False))
    java_base_url: str = field(default_factory=lambda: _env_str("JAVA_API_BASE_URL"))
    java_endpoint_path: str = field(default_factory=lambda: _env_str("JAVA_API_ENDPOINT_PATH"))
    java_auth_type: str = field(
        default_factory=lambda: _env_str("JAVA_API_AUTH_TYPE", "none").lower()
    )
    java_token: str = field(default_factory=lambda: _env_str("JAVA_API_TOKEN"))
    java_api_key_header: str = field(
        default_factory=lambda: _env_str("JAVA_API_KEY_HEADER", "X-API-Key")
    )
    java_username: str = field(default_factory=lambda: _env_str("JAVA_API_USERNAME"))
    java_password: str = field(default_factory=lambda: _env_str("JAVA_API_PASSWORD"))
    java_connect_timeout: float = field(
        default_factory=lambda: _env_float("JAVA_API_CONNECT_TIMEOUT", 5.0)
    )
    java_read_timeout: float = field(
        default_factory=lambda: _env_float("JAVA_API_READ_TIMEOUT", 120.0)
    )
    java_verify_tls: bool = field(default_factory=lambda: _env_bool("JAVA_API_VERIFY_TLS", True))

    @property
    def max_dbc_bytes(self) -> int:
        return self.max_dbc_size_mb * 1024 * 1024

    @property
    def max_measurement_bytes(self) -> int:
        return self.max_measurement_size_mb * 1024 * 1024


def load_config() -> AppConfig:
    """Build a fresh config from the current environment."""
    return AppConfig()
