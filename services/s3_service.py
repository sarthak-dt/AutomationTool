"""S3 / S3-compatible storage access (boto3).

* Credentials are optional: if no access key is given, boto3's default
  credential chain is used (IAM role, environment, shared config).
* ``boto3`` is imported lazily so the rest of the module (and its tests) work
  with an injected client.
* Credentials are never logged; ``S3Config.__repr__`` redacts them.
"""
from __future__ import annotations

import posixpath
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

from services.file_service import ValidationError, human_size, safe_join, sanitize_filename
from services.logging_utils import get_logger, redact_text, register_secret

log = get_logger("s3")

ADDRESSING_STYLES = ("auto", "virtual", "path")
DEFAULT_PORTS = {"https": 443, "http": 80}


class S3Error(Exception):
    """User-presentable S3 failure (message is already redacted/friendly)."""


@dataclass
class S3Config:
    bucket: str = ""
    region: str = ""
    endpoint_url: str = ""
    port: str | int | None = None
    access_key_id: str = ""
    secret_access_key: str = ""
    addressing_style: str = "auto"
    verify_ssl: bool = True

    def __repr__(self) -> str:  # never leak credentials
        return (
            f"S3Config(bucket={self.bucket!r}, region={self.region!r}, "
            f"endpoint_url={self.endpoint_url!r}, port={self.port!r}, "
            f"access_key_id={'<set>' if self.access_key_id else '<none>'}, "
            f"secret_access_key={'<set>' if self.secret_access_key else '<none>'}, "
            f"addressing_style={self.addressing_style!r})"
        )

    __str__ = __repr__

    def validate(self) -> None:
        if not self.bucket.strip():
            raise ValidationError("S3 bucket name is required.")
        if self.addressing_style not in ADDRESSING_STYLES:
            raise ValidationError(f"Request style must be one of {ADDRESSING_STYLES}.")
        if bool(self.access_key_id) != bool(self.secret_access_key):
            raise ValidationError(
                "Provide both Access key ID and Secret access key, or leave both empty "
                "to use the default credential chain (IAM role)."
            )
        if self.port not in (None, ""):
            try:
                port = int(self.port)
            except (TypeError, ValueError) as exc:
                raise ValidationError("Endpoint port must be a number.") from exc
            if not 1 <= port <= 65535:
                raise ValidationError("Endpoint port must be between 1 and 65535.")
        build_endpoint_url(self.endpoint_url, self.port)  # raises on conflicts


def build_endpoint_url(endpoint: str | None, port: str | int | None) -> str | None:
    """Combine endpoint URL and port. Returns None for plain AWS S3."""
    endpoint = (endpoint or "").strip()
    if not endpoint:
        return None
    if "://" not in endpoint:
        endpoint = "https://" + endpoint
    parsed = urlparse(endpoint)
    if parsed.scheme not in ("http", "https") or not parsed.hostname:
        raise ValidationError(f"Invalid endpoint URL: {endpoint!r}")
    port_val: int | None = None
    if port not in (None, ""):
        port_val = int(port)
    url_port = parsed.port
    if port_val is not None and url_port is not None and port_val != url_port:
        raise ValidationError(
            f"Endpoint URL already specifies port {url_port} but port field says {port_val}."
        )
    final_port = url_port if url_port is not None else port_val
    host = parsed.hostname
    if ":" in host:  # IPv6
        host = f"[{host}]"
    netloc = host
    if final_port is not None and final_port != DEFAULT_PORTS[parsed.scheme]:
        netloc = f"{host}:{final_port}"
    path = parsed.path.rstrip("/")
    return f"{parsed.scheme}://{netloc}{path}"


def build_client(config: S3Config):  # pragma: no cover - needs boto3 + network
    """Create a boto3 S3 client from ``config``."""
    config.validate()
    try:
        import boto3
        from botocore.config import Config as BotoConfig
    except ImportError as exc:
        raise S3Error("boto3 is not installed. Run: pip install -r requirements.txt") from exc
    register_secret(config.secret_access_key)
    register_secret(config.access_key_id)
    kwargs: dict[str, Any] = {
        "config": BotoConfig(
            signature_version="s3v4",
            s3={"addressing_style": config.addressing_style},
            connect_timeout=10,
            read_timeout=60,
            retries={"max_attempts": 3, "mode": "standard"},
        ),
        "verify": config.verify_ssl,
    }
    if config.region:
        kwargs["region_name"] = config.region
    endpoint = build_endpoint_url(config.endpoint_url, config.port)
    if endpoint:
        kwargs["endpoint_url"] = endpoint
    if config.access_key_id:
        kwargs["aws_access_key_id"] = config.access_key_id
        kwargs["aws_secret_access_key"] = config.secret_access_key
    session = boto3.session.Session()
    return session.client("s3", **kwargs)


def friendly_error(exc: BaseException) -> str:
    """Translate boto3/botocore exceptions into actionable messages (no secrets)."""
    name = type(exc).__name__
    code = ""
    http = None
    response = getattr(exc, "response", None)
    if isinstance(response, dict):
        code = str(response.get("Error", {}).get("Code", ""))
        http = response.get("ResponseMetadata", {}).get("HTTPStatusCode")
    hints = {
        "NoSuchBucket": "Bucket does not exist. Check the bucket name (and region/endpoint).",
        "404": "Bucket or object not found. Check the bucket name, region and path.",
        "AccessDenied": "Access denied. The credentials/role lack permission "
        "(s3:ListBucket / s3:GetObject) for this bucket or prefix.",
        "403": "Access denied (403). Check permissions, and that the region/endpoint match.",
        "InvalidAccessKeyId": "The access key ID is not recognised by this endpoint.",
        "SignatureDoesNotMatch": "Signature mismatch: the secret access key is wrong, or the "
        "endpoint/request style/region is incorrect.",
        "ExpiredToken": "The credentials have expired.",
        "AuthorizationHeaderMalformed": "The region does not match the bucket's region.",
        "PermanentRedirect": "The bucket is in a different region/endpoint.",
        "301": "The bucket is in a different region/endpoint.",
        "RequestTimeTooSkewed": "Your system clock is too far from the server time.",
    }
    if name == "NoCredentialsError":
        msg = "No credentials found. Enter an access key/secret, or run with an IAM role."
    elif name == "PartialCredentialsError":
        msg = "Incomplete credentials: provide both access key ID and secret access key."
    elif name in ("EndpointConnectionError", "ConnectTimeoutError"):
        msg = "Cannot reach the endpoint. Check endpoint URL, port, network/VPN and proxy."
    elif name == "ReadTimeoutError":
        msg = "The endpoint did not respond in time."
    elif name in ("SSLError", "CertificateError"):
        msg = "TLS/SSL error. Check the endpoint's certificate or the HTTP/HTTPS choice."
    elif name == "InvalidRegionError":
        msg = "Invalid region name."
    elif name == "ParamValidationError":
        msg = "Invalid S3 parameter (check the bucket name format)."
    elif code in hints:
        msg = hints[code]
    elif http is not None and str(http) in hints:
        msg = hints[str(http)]
    elif code:
        msg = f"S3 returned error '{code}'."
    else:
        msg = f"{name}: {exc}"
    return redact_text(msg)


@dataclass
class S3Object:
    key: str
    size: int
    last_modified: datetime | None = None

    @property
    def name(self) -> str:
        return posixpath.basename(self.key)

    @property
    def ext(self) -> str:
        return Path(self.name).suffix.lower()

    @property
    def size_text(self) -> str:
        return human_size(self.size)


@dataclass
class Listing:
    prefix: str
    folders: list[str] = field(default_factory=list)  # full prefixes ending with '/'
    files: list[S3Object] = field(default_factory=list)  # supported measurement files
    skipped_unsupported: int = 0
    truncated: bool = False


@dataclass
class ConnectionResult:
    ok: bool
    message: str


def normalize_prefix(prefix: str | None) -> str:
    prefix = (prefix or "").strip().lstrip("/")
    if prefix and not prefix.endswith("/"):
        prefix += "/"
    return prefix


class S3Service:
    def __init__(
        self,
        config: S3Config,
        client: Any | None = None,
        allowed_exts: tuple[str, ...] = (".mf4", ".blf", ".log", ".asc"),
        max_keys: int = 5000,
    ):
        self.config = config
        self._client = client
        self.allowed_exts = tuple(e.lower() for e in allowed_exts)
        self.max_keys = max_keys

    @property
    def client(self):
        if self._client is None:
            self._client = build_client(self.config)
        return self._client

    # ------------------------------------------------------------------
    def test_connection(self) -> ConnectionResult:
        try:
            self.config.validate()
            self.client.list_objects_v2(Bucket=self.config.bucket, MaxKeys=1, Delimiter="/")
        except ValidationError as exc:
            return ConnectionResult(False, str(exc))
        except S3Error as exc:
            return ConnectionResult(False, str(exc))
        except Exception as exc:  # boto3/botocore errors
            log.warning("S3 connection test failed: %s", type(exc).__name__)
            return ConnectionResult(False, friendly_error(exc))
        return ConnectionResult(True, f"Connected. Bucket '{self.config.bucket}' is accessible.")

    def list_prefix(self, prefix: str = "") -> Listing:
        """List one 'folder' level: sub-folders and supported measurement files."""
        prefix = normalize_prefix(prefix)
        listing = Listing(prefix=prefix)
        token = None
        scanned = 0
        try:
            while True:
                params: dict[str, Any] = {
                    "Bucket": self.config.bucket,
                    "Prefix": prefix,
                    "Delimiter": "/",
                    "MaxKeys": 1000,
                }
                if token:
                    params["ContinuationToken"] = token
                resp = self.client.list_objects_v2(**params)
                for cp in resp.get("CommonPrefixes", []) or []:
                    listing.folders.append(cp["Prefix"])
                for obj in resp.get("Contents", []) or []:
                    key = obj["Key"]
                    if key == prefix or key.endswith("/"):
                        continue
                    scanned += 1
                    s3obj = S3Object(key=key, size=int(obj.get("Size", 0)), last_modified=obj.get("LastModified"))
                    if s3obj.ext in self.allowed_exts:
                        listing.files.append(s3obj)
                    else:
                        listing.skipped_unsupported += 1
                if scanned + len(listing.folders) >= self.max_keys and resp.get("IsTruncated"):
                    listing.truncated = True
                    break
                if resp.get("IsTruncated"):
                    token = resp.get("NextContinuationToken")
                    if not token:
                        break
                else:
                    break
        except Exception as exc:
            if isinstance(exc, (ValidationError, S3Error)):
                raise
            raise S3Error(friendly_error(exc)) from exc
        listing.folders.sort()
        listing.files.sort(key=lambda o: o.name.lower())
        return listing

    def download(
        self, key: str, dest_dir: Path, index: int, expected_size: int | None, max_bytes: int
    ) -> Path:
        """Download one object into ``dest_dir`` with a safe, unique local name."""
        if expected_size is not None and expected_size > max_bytes:
            raise S3Error(
                f"'{posixpath.basename(key)}' is {human_size(expected_size)}, above the "
                f"limit of {human_size(max_bytes)}."
            )
        local_name = f"{index:03d}_{sanitize_filename(posixpath.basename(key))}"
        dest = safe_join(dest_dir, local_name)
        try:
            self.client.download_file(self.config.bucket, key, str(dest))
        except Exception as exc:
            if dest.exists():
                dest.unlink(missing_ok=True)
            if isinstance(exc, (ValidationError, S3Error)):
                raise
            raise S3Error(f"Download of '{posixpath.basename(key)}' failed: {friendly_error(exc)}") from exc
        if dest.stat().st_size > max_bytes:
            dest.unlink(missing_ok=True)
            raise S3Error(f"Downloaded file exceeds the size limit of {human_size(max_bytes)}.")
        return dest
