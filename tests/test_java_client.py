"""Java adapter: unavailable-by-default, HTTP error handling, response validation, secret hygiene."""
from unittest.mock import MagicMock

import pytest
import requests

from services.java_decoder_client import (
    JavaApiConfig,
    JavaDecoderClient,
    JavaResponseError,
    validate_normalized_payload,
)
from services.logging_utils import clear_secrets, redact_text
from services.models import ComparisonSettings, EngineState

SETTINGS = ComparisonSettings()
SECRET = "s3cr3t-token-value-123"


def cfg(**kw):
    base = dict(
        enabled=True, base_url="http://java.example:8080", endpoint_path="/decode", auth_type="bearer", token=SECRET
    )
    base.update(kw)
    return JavaApiConfig(**base)


class FakeResponse:
    def __init__(self, status=200, payload=None, text="", json_error=False):
        self.status_code = status
        self._payload = payload
        self.text = text
        self._json_error = json_error

    def json(self):
        if self._json_error:
            raise ValueError("not json")
        return self._payload


class ConfiguredClient(JavaDecoderClient):
    """Subclass implementing the two integration points with a *test-only* schema."""

    def _build_request(self, measurement_path, dbc_paths, settings):
        return {"json": {"file": str(measurement_path)}}

    def _map_response(self, response):
        return response.json()


GOOD_PAYLOAD = {
    "signals": [
        {"name": "Engine_RPM", "unit": "rpm", "timestamps": [0.0, 1.0], "values": [800.0, 900.0]},
        {"name": "Gear", "is_discrete": True, "timestamps": [0.0, 1.0], "values": [1, 2]},
    ]
}


def make_client(session, **kw):
    return ConfiguredClient(cfg(**kw), session=session)


# --------------------------------------------------------------- availability
def test_default_config_is_unavailable_and_makes_no_http_call():
    session = MagicMock()
    client = JavaDecoderClient(JavaApiConfig(), session=session)
    res = client.decode("m.asc", ["a.dbc"], SETTINGS)
    assert res.state == EngineState.UNAVAILABLE
    assert res.is_mock and not res.ok
    assert res.data.empty
    session.post.assert_not_called()


def test_enabled_but_incomplete_config_is_unavailable_with_reason():
    client = JavaDecoderClient(JavaApiConfig(enabled=True, base_url="http://x"), session=MagicMock())
    res = client.decode("m.asc", [], SETTINGS)
    assert res.state == EngineState.UNAVAILABLE
    assert "JAVA_API_ENDPOINT_PATH" in res.error


def test_live_request_uses_file_path_dbc_path_and_page_size(tmp_path):
    client = JavaDecoderClient(cfg())
    request = client._build_request(tmp_path / "m.blf", [tmp_path / "x.dbc"], SETTINGS)
    assert request["json"] == {
        "filePath": str((tmp_path / "m.blf").resolve()),
        "dbcPath": str((tmp_path / "x.dbc").resolve()),
        "pageSize": 10000,
    }


def test_default_response_mapping_rejects_unknown_schema_without_fake_data():
    class OnlyRequest(JavaDecoderClient):
        def _build_request(self, *a, **k):
            return {"json": {}}

    session = MagicMock()
    session.post.return_value = FakeResponse(200, GOOD_PAYLOAD)  # not the Java parser schema 2.0
    res = OnlyRequest(cfg(), session=session).decode("m.asc", [], SETTINGS)
    assert res.state == EngineState.ERROR
    assert res.data.empty


def test_default_response_mapping_accepts_java_parser_schema_2():
    class OnlyRequest(JavaDecoderClient):
        def _build_request(self, *a, **k):
            return {"json": {}}

    body = {"status": "COMPLETED", "diagnostics": ["outcome=COMPLETED_WITH_WARNINGS"], "signals": {"k": {
        "name": "A", "unit": "V", "messageName": "M", "channel": 1, "availability": "AVAILABLE",
        "timestamps": [0.0, 1.0], "samples": [1.0, 2.0], "validity": ["VALID", "VALID"]}}}
    session = MagicMock()
    session.post.return_value = FakeResponse(200, body)
    res = OnlyRequest(cfg(), session=session).decode("m.mf4", [], SETTINGS)
    assert res.state == EngineState.OK and list(res.data["value"]) == [1.0, 2.0]
    assert any("diagnostic" in w for w in res.warnings)


def test_java_cursor_pages_are_normalized_incrementally_and_appended():
    class OnlyRequest(JavaDecoderClient):
        def _build_request(self, *args, **kwargs):
            return {"json": {"filePath": "m.mf4", "dbcPath": "a.dbc", "pageSize": 1}}

    def signal(timestamp, value):
        return {"name": "A", "unit": "V", "messageName": "M", "channel": 1,
                "availability": "AVAILABLE", "timestamps": [timestamp], "samples": [value],
                "validity": ["VALID"]}

    first = {"schemaVersion": "2.0", "status": "COMPLETED", "signals": {"A": signal(0.0, 1.0)},
             "paginationComplete": False, "nextCursor": "next", "diagnostics": []}
    second = {"schemaVersion": "2.0", "status": "COMPLETED", "signals": {"A": signal(1.0, 2.0)},
              "paginationComplete": True, "diagnostics": []}
    session = MagicMock()
    session.post.side_effect = [FakeResponse(200, first), FakeResponse(200, second)]

    res = OnlyRequest(cfg(), session=session).decode("m.mf4", ["a.dbc"], SETTINGS)

    assert res.state == EngineState.OK
    assert list(res.data["value"]) == [1.0, 2.0]
    assert list(res.data["timestamp"]) == [0.0, 1.0]
    assert session.post.call_count == 2
    assert session.post.call_args_list[1].kwargs["json"] == {"cursor": "next"}
    assert not any("PARTIAL" in warning for warning in res.warnings)


# --------------------------------------------------------------- success path
def test_success_returns_normalized_frame_and_sends_auth_and_timeouts():
    session = MagicMock()
    session.post.return_value = FakeResponse(200, GOOD_PAYLOAD)
    res = make_client(session).decode("m.asc", [], SETTINGS)
    assert res.state == EngineState.OK and not res.is_mock
    assert sorted(res.data["signal"].unique()) == ["Engine_RPM", "Gear"]
    assert len(res.data) == 4
    args, kwargs = session.post.call_args
    assert args[0] == "http://java.example:8080/decode"
    assert kwargs["headers"]["Authorization"] == f"Bearer {SECRET}"
    assert kwargs["timeout"] == (5.0, 120.0)
    assert kwargs["verify"] is True


@pytest.mark.parametrize(
    "auth,expect_header,expect_auth",
    [
        ("api_key", ("X-API-Key", SECRET), None),
        ("basic", None, ("u", "p")),
        ("none", None, None),
    ],
)
def test_auth_modes(auth, expect_header, expect_auth):
    session = MagicMock()
    session.post.return_value = FakeResponse(200, GOOD_PAYLOAD)
    make_client(session, auth_type=auth, username="u", password="p").decode("m", [], SETTINGS)
    kwargs = session.post.call_args.kwargs
    if expect_header:
        assert kwargs["headers"][expect_header[0]] == expect_header[1]
    assert kwargs.get("auth") == expect_auth
    if auth == "none":
        assert "headers" not in kwargs


# --------------------------------------------------------------- HTTP errors
@pytest.mark.parametrize(
    "status,fragment",
    [
        (401, "credentials"),
        (403, "credentials"),
        (404, "not found"),
        (408, "timed out"),
        (413, "too large"),
        (422, "rejected the request"),
        (500, "server error"),
        (503, "server error"),
    ],
)
def test_http_errors_map_to_error_state(status, fragment):
    session = MagicMock()
    session.post.return_value = FakeResponse(status, text="boom")
    res = make_client(session).decode("m", [], SETTINGS)
    assert res.state == EngineState.ERROR
    assert fragment in res.error
    assert str(status) in res.error


def test_resource_limit_429_gives_actionable_java_limit_message():
    session = MagicMock()
    body = {
        "schemaVersion": "1.0",
        "status": "FAILED",
        "error": {
            "code": "RESOURCE_LIMIT",
            "message": "The decoded result exceeds the configured sample or signal limit.",
            "details": {},
        },
    }
    session.post.return_value = FakeResponse(429, payload=body, text=str(body))
    result = make_client(session).decode("large.mf4", ["engine.dbc"], SETTINGS)
    assert result.state == EngineState.ERROR
    assert "configured decoded-result limit" in result.error
    assert "smaller measurement" in result.error
    assert "increase the Java service's sample/signal limit" in result.error


@pytest.mark.parametrize(
    "exc,fragment",
    [
        (requests.exceptions.ConnectTimeout(), "Timed out connecting"),
        (requests.exceptions.ReadTimeout(), "did not answer"),
        (requests.exceptions.SSLError(), "TLS/SSL"),
        (requests.exceptions.ConnectionError(), "Cannot connect"),
        (requests.exceptions.InvalidURL(), "failed"),
    ],
)
def test_network_errors_map_to_error_state(exc, fragment):
    session = MagicMock()
    session.post.side_effect = exc
    res = make_client(session).decode("m", [], SETTINGS)
    assert res.state == EngineState.ERROR
    assert fragment in res.error


def test_error_body_snippet_is_redacted():
    clear_secrets()
    session = MagicMock()
    session.post.return_value = FakeResponse(500, text=f"token={SECRET} Bearer abcdefghijkl failed")
    res = make_client(session).decode("m", [], SETTINGS)
    assert SECRET not in res.error
    assert "abcdefghijkl" not in res.error
    clear_secrets()


def test_secret_never_in_repr_or_error():
    c = cfg()
    assert SECRET not in repr(c) and SECRET not in str(c)
    session = MagicMock()
    session.post.side_effect = requests.exceptions.ConnectionError(f"failed with {SECRET}")
    res = make_client(session).decode("m", [], SETTINGS)
    assert SECRET not in res.error
    assert SECRET not in redact_text(f"x {SECRET} y")  # registered by decode()
    clear_secrets()


# --------------------------------------------------------------- response validation
def test_invalid_json_response_is_error():
    class BadJson(ConfiguredClient):
        def _map_response(self, response):
            return response.json()

    session = MagicMock()
    session.post.return_value = FakeResponse(200, json_error=True)
    res = BadJson(cfg(), session=session).decode("m", [], SETTINGS)
    assert res.state == EngineState.ERROR
    assert "not json" in res.error or "valid JSON" in res.error or "Unexpected error" in res.error


@pytest.mark.parametrize(
    "payload,fragment",
    [
        ([], "dict"),
        ({}, "dict"),
        ({"signals": "x"}, "list"),
        ({"signals": [1]}, "must be an object"),
        ({"signals": [{"timestamps": [], "values": []}]}, "name"),
        ({"signals": [{"name": "A", "timestamps": [0], "values": []}]}, "1 timestamps but 0 values"),
        ({"signals": [{"name": "A", "timestamps": "x", "values": []}]}, "lists"),
        ({"signals": [{"name": "A", "timestamps": ["a"], "values": [1]}]}, "numeric"),
        ({"signals": [{"name": "A", "timestamps": [float("nan")], "values": [1]}]}, "NaN"),
    ],
)
def test_payload_validation_rejects_bad_shapes(payload, fragment):
    with pytest.raises(JavaResponseError) as e:
        validate_normalized_payload(payload)
    assert fragment in str(e.value)


def test_payload_validation_accepts_text_and_numeric_values():
    df = validate_normalized_payload(
        {
            "signals": [
                {"name": "T", "timestamps": [0, 1], "values": ["ON", "OFF"]},
                {"name": "N", "timestamps": [0, 1], "values": [1, "2.5"]},
            ]
        }
    )
    assert df[df["signal"] == "N"]["value"].tolist() == [1.0, 2.5]
    assert df[df["signal"] == "T"]["value"].tolist() == ["ON", "OFF"]


def test_invalid_payload_through_decode_is_error_state():
    session = MagicMock()
    session.post.return_value = FakeResponse(200, {"signals": [{"name": "A", "timestamps": [0], "values": []}]})
    res = make_client(session).decode("m", [], SETTINGS)
    assert res.state == EngineState.ERROR
    assert "timestamps" in res.error


def test_empty_signal_list_is_ok_but_empty():
    session = MagicMock()
    session.post.return_value = FakeResponse(200, {"signals": []})
    res = make_client(session).decode("m", [], SETTINGS)
    assert res.state == EngineState.OK and res.data.empty


def test_java_channel_selection_filters_the_mapped_result():
    class ChannelClient(ConfiguredClient):
        def _map_response(self, response):
            return {"signals": [
                {"name": "A", "timestamps": [0.0, 1.0], "values": [1.0, 2.0], "channel": 1},
                {"name": "A", "timestamps": [0.0, 1.0], "values": [3.0, 4.0], "channel": 4},
            ]}

    session = MagicMock()
    session.post.return_value = FakeResponse(200, {"signals": []})
    selected = ComparisonSettings(decode_channels=(1, 3, 6, 8))
    result = ChannelClient(cfg(), session=session).decode("m", [], selected)
    assert result.state == EngineState.OK
    assert set(result.data["channel"]) == {1}
