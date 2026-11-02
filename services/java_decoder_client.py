"""Java decoder adapter for Stellar's decoder validation API.

Everything Java-specific lives here: URL, endpoint, authentication, request
body, response mapping. The rest of the application only calls
``JavaDecoderClient.decode(...)`` and receives a ``DecoderResult``.

Input files are copied into a short lived staging directory under Downloads so
the Java API's allowed-root check can read them by path. Cursor pages are
collected before mapping, so comparisons use the complete response.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd
import shutil
import tempfile

from config import AppConfig
from services.logging_utils import get_logger, redact_text, register_secret
from services.models import ComparisonSettings, DecoderResult, EngineState, empty_frame, normalize_frame

log = get_logger("java_client")

AUTH_TYPES = ("none", "bearer", "api_key", "basic")


class JavaApiNotImplemented(NotImplementedError):
    """Raised by the integration points until the real API mapping is written."""


class JavaApiError(Exception):
    """Any failure talking to the Java API (message is safe to display)."""


class JavaResponseError(JavaApiError):
    """The API answered, but the response is not a valid normalized payload."""


class _JsonResponse:
    def __init__(self, body: dict[str, Any]):
        self._body = body

    def json(self) -> dict[str, Any]:
        return self._body


@dataclass
class JavaApiConfig:
    enabled: bool = False
    base_url: str = ""
    endpoint_path: str = ""
    auth_type: str = "none"
    token: str = ""
    api_key_header: str = "X-API-Key"
    username: str = ""
    password: str = ""
    connect_timeout: float = 5.0
    read_timeout: float = 120.0
    verify_tls: bool = True
    extra_headers: dict[str, str] = field(default_factory=dict)

    @classmethod
    def from_app_config(cls, cfg: AppConfig) -> "JavaApiConfig":
        return cls(
            enabled=cfg.java_enabled,
            base_url=cfg.java_base_url,
            endpoint_path=cfg.java_endpoint_path,
            auth_type=cfg.java_auth_type,
            token=cfg.java_token,
            api_key_header=cfg.java_api_key_header,
            username=cfg.java_username,
            password=cfg.java_password,
            connect_timeout=cfg.java_connect_timeout,
            read_timeout=cfg.java_read_timeout,
            verify_tls=cfg.java_verify_tls,
        )

    def __repr__(self) -> str:
        return (
            f"JavaApiConfig(enabled={self.enabled}, base_url={self.base_url!r}, "
            f"endpoint_path={self.endpoint_path!r}, auth_type={self.auth_type!r}, "
            f"credentials={'<set>' if (self.token or self.password) else '<none>'})"
        )

    __str__ = __repr__

    @property
    def full_url(self) -> str:
        return self.base_url.rstrip("/") + "/" + self.endpoint_path.lstrip("/")

    def missing_settings(self) -> list[str]:
        missing = []
        if not self.base_url.strip():
            missing.append("JAVA_API_BASE_URL")
        if not self.endpoint_path.strip():
            missing.append("JAVA_API_ENDPOINT_PATH")
        if self.auth_type not in AUTH_TYPES:
            missing.append(f"JAVA_API_AUTH_TYPE (one of {', '.join(AUTH_TYPES)})")
        elif self.auth_type in ("bearer", "api_key") and not self.token:
            missing.append("JAVA_API_TOKEN")
        elif self.auth_type == "basic" and not (self.username and self.password):
            missing.append("JAVA_API_USERNAME / JAVA_API_PASSWORD")
        return missing

    def register_secrets(self) -> None:
        register_secret(self.token)
        register_secret(self.password)


def validate_normalized_payload(payload: Any) -> pd.DataFrame:
    """Validate ``_map_response`` output and convert it to a decoder DataFrame.

    Required normalized payload (this is the app-internal contract, NOT the
    Java API's schema)::

        {"signals": [
            {"name": "Engine_RPM",
             "unit": "rpm",                  # optional
             "is_discrete": false,           # optional
             "message": "EngineData",        # optional
             "can_id": "0x100",              # optional (hex text or number)
             "channel": 1,                   # optional, 1-based CAN bus channel
             "channel_known": true,          # optional; false when the response lacks source channel metadata
             "timestamps": [0.0, 0.1, ...],  # seconds, same unit/reference as Python engine
             "values": [800.0, 810.5, ...]}  # same length as timestamps
        ]}
    """
    if not isinstance(payload, dict) or "signals" not in payload:
        raise JavaResponseError("Response mapping must return a dict with a 'signals' list.")
    signals = payload["signals"]
    if not isinstance(signals, list):
        raise JavaResponseError("'signals' must be a list.")
    parts = []
    for i, sig in enumerate(signals):
        if not isinstance(sig, dict):
            raise JavaResponseError(f"signals[{i}] must be an object.")
        name = sig.get("name")
        if not isinstance(name, str) or not name.strip():
            raise JavaResponseError(f"signals[{i}] has no valid 'name'.")
        ts = sig.get("timestamps")
        vals = sig.get("values")
        if not isinstance(ts, list) or not isinstance(vals, list):
            raise JavaResponseError(f"signal '{name}': 'timestamps' and 'values' must be lists.")
        if len(ts) != len(vals):
            raise JavaResponseError(
                f"signal '{name}': {len(ts)} timestamps but {len(vals)} values."
            )
        try:
            ts_arr = np.asarray(ts, dtype=float)
        except (TypeError, ValueError) as exc:
            raise JavaResponseError(f"signal '{name}': timestamps must be numeric.") from exc
        if ts_arr.size and not np.isfinite(ts_arr).all():
            raise JavaResponseError(f"signal '{name}': timestamps contain NaN/Infinity.")
        # values: numeric where possible, text otherwise (compared as text)
        series = pd.Series(vals, dtype=object)
        numeric = pd.to_numeric(series, errors="coerce")
        if series.notna().equals(numeric.notna()):
            values: Any = numeric.to_numpy(dtype=float)
        else:
            values = series.to_numpy(dtype=object)
        parts.append(
            pd.DataFrame(
                {
                    "signal": name,
                    "timestamp": ts_arr,
                    "value": values,
                    "unit": str(sig.get("unit") or ""),
                    "is_discrete": bool(sig.get("is_discrete", False)),
                    "message": str(sig.get("message") or ""),
                    "can_id": sig.get("can_id") if sig.get("can_id") is not None else "",
                    "channel": sig.get("channel") if sig.get("channel") is not None else 0,
                    "channel_known": bool(sig.get("channel_known", sig.get("channel") is not None)),
                }
            )
        )
    if not parts:
        return empty_frame()
    return normalize_frame_keep_values(pd.concat(parts, ignore_index=True))


def normalize_frame_keep_values(df: pd.DataFrame) -> pd.DataFrame:
    """Like ``normalize_frame`` but keeps text values (``value`` stays object if mixed)."""
    values = df["value"]
    out = normalize_frame(df.assign(value=0.0))
    out["value"] = values.reset_index(drop=True)
    return out


class JavaDecoderClient:
    """HTTP adapter for the custom Java decoder."""

    def __init__(self, config: JavaApiConfig, session: Any | None = None):
        self.config = config
        self._session = session

    # ---- status ---------------------------------------------------------
    def availability(self) -> tuple[bool, str]:
        """(usable?, reason). Reason is shown in the UI when not usable."""
        if not self.config.enabled:
            return False, "Java API is not enabled (JAVA_API_ENABLED=false). Results are MOCK / UNAVAILABLE."
        missing = self.config.missing_settings()
        if missing:
            return False, "Java API configuration incomplete: " + ", ".join(missing) + "."
        return True, "Java API configured."

    # ---- integration points (EDIT HERE) -----------------------------------
    def _build_request(
        self,
        measurement_path: Path,
        dbc_paths: list[Path],
        settings: ComparisonSettings,
    ) -> dict[str, Any]:
        """Build the documented path based request for one measurement and DBC."""
        if len(dbc_paths) != 1:
            raise JavaApiError("The Java decoder requires one DBC file.")
        return {"json": {"filePath": str(measurement_path.resolve()),
                          "dbcPath": str(dbc_paths[0].resolve()), "pageSize": 10000}}

    def _map_response(self, response: Any) -> dict[str, Any]:
        """Convert the HTTP response into the normalized payload (see
        ``validate_normalized_payload``).

        The response schema (schemaVersion 2.0) is implemented in ``services/java_response.py``
        (based on the real JavaParser sample response).

        """
        from services.java_response import map_java_parser_response

        try:
            body = response.json()
        except ValueError as exc:
            raise JavaResponseError("Java API did not return valid JSON.") from exc
        return map_java_parser_response(body)

    def _auth_kwargs(self) -> dict[str, Any]:
        """Authentication for ``requests`` based on configuration.

        """
        cfg = self.config
        headers = dict(cfg.extra_headers)
        kwargs: dict[str, Any] = {}
        if cfg.auth_type == "bearer":
            headers["Authorization"] = f"Bearer {cfg.token}"
        elif cfg.auth_type == "api_key":
            headers[cfg.api_key_header or "X-API-Key"] = cfg.token
        elif cfg.auth_type == "basic":
            kwargs["auth"] = (cfg.username, cfg.password)
        if headers:
            kwargs["headers"] = headers
        return kwargs

    # ---- main entry --------------------------------------------------------
    def decode(
        self,
        measurement_path: str | Path,
        dbc_paths: list[str | Path],
        settings: ComparisonSettings,
    ) -> DecoderResult:
        usable, reason = self.availability()
        if not usable:
            return DecoderResult.unavailable("java", reason)
        self.config.register_secrets()
        measurement = Path(measurement_path)
        dbcs = [Path(p) for p in dbc_paths]
        staging = None
        try:
            # The endpoint accepts paths and enforces its own allowed roots. The
            # app's job workspace may live in /tmp, so stage files under Downloads.
            if type(self)._build_request is JavaDecoderClient._build_request:
                root = Path.home() / "Downloads" / ".decoder-comparator-staging"
                root.mkdir(parents=True, exist_ok=True)
                staging = tempfile.TemporaryDirectory(prefix="job-", dir=root)
                staged = Path(staging.name)
                staged_measurement = staged / measurement.name
                shutil.copyfile(measurement, staged_measurement)
                staged_dbcs = []
                for idx, dbc in enumerate(dbcs):
                    target = staged / f"{idx}-{dbc.name}"
                    shutil.copyfile(dbc, target)
                    staged_dbcs.append(target)
                measurement, dbcs = staged_measurement, staged_dbcs
            request_kwargs = self._build_request(measurement, dbcs, settings)
        except JavaApiNotImplemented as exc:
            if staging is not None:
                staging.cleanup()
            return DecoderResult.unavailable("java", str(exc))
        except Exception as exc:
            if staging is not None:
                staging.cleanup()
            return DecoderResult.failed("java", f"Building Java request failed: {redact_text(exc)}")

        try:
            frame, response_warnings = self._fetch_all_pages(
                request_kwargs, selected_channels=settings.decode_channels
            )
        except JavaApiError as exc:
            return DecoderResult.failed("java", redact_text(exc))
        except Exception as exc:
            return DecoderResult.failed(
                "java", f"Unexpected error handling Java response: {type(exc).__name__}: {redact_text(exc)}"
            )
        finally:
            if staging is not None:
                staging.cleanup()
        return DecoderResult(
            engine="java",
            state=EngineState.OK,
            data=frame,
            warnings=response_warnings,
            stats={"signals": int(frame["signal"].nunique()) if not frame.empty else 0, "samples": int(len(frame))},
        )

    def _fetch_all_pages(
        self, request_kwargs: dict[str, Any], selected_channels: tuple[int, ...] = ()
    ) -> tuple[pd.DataFrame, list[str]]:
        """Normalize each cursor page immediately instead of retaining raw JSON pages."""
        kwargs = dict(request_kwargs)
        body = dict(kwargs.get("json") or {})
        frames: list[pd.DataFrame] = []
        warnings: list[str] = []
        while True:
            kwargs["json"] = body
            response = self._post(kwargs)
            self._raise_for_http_error(response)
            try:
                page = response.json()
            except ValueError as exc:
                raise JavaResponseError("Java API did not return valid JSON.") from exc
            # The API's schema 2.0 uses a signal object. Custom adapters may
            # return the app's normalized payload directly; accept that shape
            # as a single page for backwards compatibility.
            if isinstance(page, dict) and isinstance(page.get("signals"), dict):
                mapped = self._map_response(_JsonResponse(page))
                chunk = validate_normalized_payload(mapped)
                # A page can contain channels the user did not select. Drop
                # them before retaining the page's DataFrame.
                if selected_channels and not chunk.empty:
                    chunk = chunk[chunk["channel"].isin(selected_channels)].reset_index(drop=True)
                frames.append(chunk)
                # Partial is expected while following cursors; only surface it
                # if the server failed to provide a continuation cursor.
                warnings.extend(w for w in mapped.get("warnings", []) if "Response is PARTIAL" not in w)
                complete = page.get("paginationComplete", True)
                cursor = page.get("nextCursor")
            else:
                mapped = self._map_response(response)
                chunk = validate_normalized_payload(mapped)
                if selected_channels and not chunk.empty:
                    chunk = chunk[chunk["channel"].isin(selected_channels)].reset_index(drop=True)
                frames.append(chunk)
                warnings.extend(str(w) for w in (mapped.get("warnings") or []))
                break
            del response, page, mapped, chunk
            if complete:
                break
            if not cursor:
                raise JavaResponseError("Java response is partial but has no nextCursor.")
            body = {"cursor": cursor}
        frame = pd.concat(frames, ignore_index=True, copy=False) if frames else empty_frame()
        return frame, list(dict.fromkeys(warnings))

    # ---- HTTP plumbing -------------------------------------------------------
    def _get_session(self):
        if self._session is None:
            import requests

            self._session = requests.Session()
        return self._session

    def _post(self, request_kwargs: dict[str, Any]):
        import requests

        cfg = self.config
        kwargs = dict(request_kwargs)
        auth = self._auth_kwargs()
        headers = {**auth.pop("headers", {}), **kwargs.pop("headers", {})}
        if headers:
            kwargs["headers"] = headers
        kwargs.update(auth)
        try:
            return self._get_session().post(
                cfg.full_url,
                timeout=(cfg.connect_timeout, cfg.read_timeout),
                verify=cfg.verify_tls,
                **kwargs,
            )
        except requests.exceptions.ConnectTimeout as exc:
            raise JavaApiError(f"Timed out connecting to the Java API ({cfg.connect_timeout}s).") from exc
        except requests.exceptions.ReadTimeout as exc:
            raise JavaApiError(f"The Java API did not answer within {cfg.read_timeout}s.") from exc
        except requests.exceptions.SSLError as exc:
            raise JavaApiError("TLS/SSL error talking to the Java API (certificate problem?).") from exc
        except requests.exceptions.ConnectionError as exc:
            raise JavaApiError("Cannot connect to the Java API (is it running? URL/port correct?).") from exc
        except requests.exceptions.RequestException as exc:
            raise JavaApiError(f"Request to the Java API failed: {type(exc).__name__}.") from exc

    @staticmethod
    def _raise_for_http_error(response: Any) -> None:
        status = getattr(response, "status_code", None)
        if status is None or 200 <= int(status) < 300:
            return
        status = int(status)
        snippet = ""
        try:
            snippet = redact_text((response.text or "")[:200]).replace("\n", " ")
        except Exception:
            pass
        if status in (401, 403):
            msg = f"Java API rejected the credentials (HTTP {status}). Check the auth settings."
        elif status == 404:
            msg = "Java API endpoint not found (HTTP 404). Check JAVA_API_BASE_URL / JAVA_API_ENDPOINT_PATH."
        elif status == 429:
            code = ""
            try:
                body = response.json()
                error = body.get("error", {}) if isinstance(body, dict) else {}
                code = str(error.get("code", "")) if isinstance(error, dict) else ""
            except Exception:
                pass
            if code == "RESOURCE_LIMIT":
                msg = (
                    "Java API reached its configured decoded-result limit (HTTP 429, RESOURCE_LIMIT): "
                    "the decoded sample or signal count is too large for the Java service. "
                    "Use a smaller measurement or a DBC with fewer matching signals, or increase the "
                    "Java service's sample/signal limit. The Java API returned no comparison data."
                )
            else:
                msg = f"Java API rate or resource limit reached (HTTP 429)."
        elif status in (408, 504):
            msg = f"Java API timed out (HTTP {status})."
        elif status == 413:
            msg = "Java API refused the upload as too large (HTTP 413)."
        elif 400 <= status < 500:
            msg = f"Java API rejected the request (HTTP {status})."
        else:
            msg = f"Java API server error (HTTP {status})."
        if snippet:
            msg += f" Response: {snippet}"
        raise JavaApiError(msg)
