"""S3 logic tested with an injected fake client (no network, no boto3 required)."""
from datetime import datetime
from pathlib import Path

import pytest

from services.file_service import ValidationError
from services.s3_service import (
    S3Config,
    S3Error,
    S3Service,
    build_endpoint_url,
    friendly_error,
    normalize_prefix,
)


class ClientError(Exception):
    def __init__(self, code, status=None):
        super().__init__(f"An error occurred ({code})")
        self.response = {"Error": {"Code": code}, "ResponseMetadata": {"HTTPStatusCode": status}}


class FakeS3:
    def __init__(self, objects, page_size=2, fail=None):
        self.objects = objects  # {key: size}
        self.page_size = page_size
        self.fail = fail
        self.calls = []
        self.downloads = []

    def list_objects_v2(self, **p):
        self.calls.append(p)
        if self.fail:
            raise self.fail
        prefix, delim = p.get("Prefix", ""), p.get("Delimiter")
        keys = sorted(k for k in self.objects if k.startswith(prefix))
        folders, files = [], []
        for k in keys:
            rest = k[len(prefix):]
            if delim and delim in rest:
                cp = prefix + rest.split(delim)[0] + delim
                if cp not in folders:
                    folders.append(cp)
            else:
                files.append(k)
        entries = [("c", f) for f in folders] + [("f", k) for k in files]
        start = int(p.get("ContinuationToken", 0) or 0)
        page = entries[start : start + self.page_size]
        resp = {
            "CommonPrefixes": [{"Prefix": v} for t, v in page if t == "c"],
            "Contents": [
                {"Key": v, "Size": self.objects[v], "LastModified": datetime(2026, 1, 1)} for t, v in page if t == "f"
            ],
            "IsTruncated": start + self.page_size < len(entries),
        }
        if resp["IsTruncated"]:
            resp["NextContinuationToken"] = str(start + self.page_size)
        return resp

    def download_file(self, bucket, key, dest):
        self.downloads.append((bucket, key, dest))
        Path(dest).write_bytes(b"x" * self.objects[key])


OBJECTS = {
    "customer_a/vehicle_01/run1.mf4": 100,
    "customer_a/vehicle_01/run2.BLF": 200,
    "customer_a/vehicle_01/notes.txt": 5,
    "customer_a/vehicle_01/sub/deep.asc": 7,
    "customer_a/vehicle_01/log.log": 9,
    "root.asc": 1,
}


def svc(client=None, **kw):
    cfg = S3Config(bucket="b", region="us-east-1")
    return S3Service(cfg, client=client or FakeS3(OBJECTS), **kw)


# ------------------------------------------------------------------ endpoint building
@pytest.mark.parametrize(
    "endpoint,port,expected",
    [
        ("", "443", None),
        (None, None, None),
        ("https://s3.example.com", "", "https://s3.example.com"),
        ("https://s3.example.com", 443, "https://s3.example.com"),
        ("https://s3.example.com", 9000, "https://s3.example.com:9000"),
        ("s3.example.com", 9000, "https://s3.example.com:9000"),
        ("http://minio.local:9000", None, "http://minio.local:9000"),
        ("http://minio.local:9000", 9000, "http://minio.local:9000"),
        ("http://minio.local", 80, "http://minio.local"),
        ("https://host/base/", 8443, "https://host:8443/base"),
    ],
)
def test_build_endpoint_url(endpoint, port, expected):
    assert build_endpoint_url(endpoint, port) == expected


def test_endpoint_port_conflict_and_invalid():
    with pytest.raises(ValidationError, match="already specifies port"):
        build_endpoint_url("http://h:9000", 9001)
    with pytest.raises(ValidationError):
        build_endpoint_url("ftp://h", None)


def test_config_validation():
    with pytest.raises(ValidationError, match="bucket"):
        S3Config(bucket="").validate()
    with pytest.raises(ValidationError, match="both"):
        S3Config(bucket="b", access_key_id="AKIAXXXXXXXXXXXXXXXX").validate()
    with pytest.raises(ValidationError, match="port"):
        S3Config(bucket="b", port="abc").validate()
    with pytest.raises(ValidationError, match="port"):
        S3Config(bucket="b", port="70000").validate()
    with pytest.raises(ValidationError, match="style"):
        S3Config(bucket="b", addressing_style="weird").validate()
    S3Config(bucket="b").validate()  # IAM role / default chain is fine


def test_config_repr_hides_credentials():
    cfg = S3Config(bucket="b", access_key_id="AKIAABCDEFGHIJKLMNOP", secret_access_key="TOPSECRETVALUE")
    text = repr(cfg) + str(cfg)
    assert "TOPSECRETVALUE" not in text and "AKIAABCDEFGHIJKLMNOP" not in text


# ------------------------------------------------------------------ listing
def test_normalize_prefix():
    assert normalize_prefix("") == ""
    assert normalize_prefix("/a/b") == "a/b/"
    assert normalize_prefix("a/") == "a/"


def test_list_root_shows_folders_and_supported_files_only():
    listing = svc().list_prefix("")
    assert listing.folders == ["customer_a/"]
    assert [f.name for f in listing.files] == ["root.asc"]


def test_list_folder_filters_extensions_and_paginates():
    s = svc(FakeS3(OBJECTS, page_size=1))
    listing = s.list_prefix("customer_a/vehicle_01")
    assert listing.prefix == "customer_a/vehicle_01/"
    assert listing.folders == ["customer_a/vehicle_01/sub/"]
    assert [f.name for f in listing.files] == ["log.log", "run1.mf4", "run2.BLF"]  # case-insensitive ext
    assert listing.skipped_unsupported == 1  # notes.txt
    assert [f.size for f in listing.files] == [9, 100, 200]
    assert len(s.client.calls) > 1  # pagination used


def test_listing_never_recurses_into_other_folders():
    listing = svc().list_prefix("customer_a/vehicle_01/")
    assert all(f.key.startswith("customer_a/vehicle_01/") for f in listing.files)
    assert "deep.asc" not in [f.name for f in listing.files]


def test_listing_truncation_flag():
    many = {f"d/f{i}.mf4": 1 for i in range(50)}
    s = svc(FakeS3(many, page_size=10), max_keys=20)
    listing = s.list_prefix("d/")
    assert listing.truncated and len(listing.files) < 50


# ------------------------------------------------------------------ connection test + errors
def test_connection_ok():
    res = svc().test_connection()
    assert res.ok and "accessible" in res.message


@pytest.mark.parametrize(
    "exc,fragment",
    [
        (ClientError("NoSuchBucket", 404), "does not exist"),
        (ClientError("AccessDenied", 403), "Access denied"),
        (ClientError("InvalidAccessKeyId", 403), "access key ID"),
        (ClientError("SignatureDoesNotMatch", 403), "Signature mismatch"),
        (ClientError("AuthorizationHeaderMalformed", 400), "region"),
        (ClientError("SomethingElse", 500), "SomethingElse"),
    ],
)
def test_connection_failure_messages(exc, fragment):
    res = svc(FakeS3({}, fail=exc)).test_connection()
    assert not res.ok and fragment in res.message


def test_connection_failure_by_exception_name():
    class NoCredentialsError(Exception):
        pass

    class EndpointConnectionError(Exception):
        pass

    assert "No credentials" in svc(FakeS3({}, fail=NoCredentialsError())).test_connection().message
    assert "Cannot reach" in svc(FakeS3({}, fail=EndpointConnectionError())).test_connection().message


def test_connection_validation_error_returned_not_raised():
    res = S3Service(S3Config(bucket=""), client=FakeS3({})).test_connection()
    assert not res.ok and "bucket" in res.message


def test_error_message_does_not_leak_secret_when_registered():
    from services.logging_utils import clear_secrets, register_secret

    register_secret("SUPERSECRETKEY123")
    msg = friendly_error(Exception("failed using SUPERSECRETKEY123"))
    assert "SUPERSECRETKEY123" not in msg
    clear_secrets()


def test_list_failure_raises_s3error():
    with pytest.raises(S3Error, match="Access denied"):
        svc(FakeS3({}, fail=ClientError("AccessDenied", 403))).list_prefix("")


# ------------------------------------------------------------------ download
def test_download_uses_safe_unique_local_name(tmp_path):
    s = svc()
    p = s.download("customer_a/vehicle_01/run1.mf4", tmp_path, 3, 100, 1000)
    assert p.parent == tmp_path.resolve() and p.name == "003_run1.mf4"
    assert p.stat().st_size == 100


def test_download_enforces_size_limit_before_and_after(tmp_path):
    s = svc()
    with pytest.raises(S3Error, match="limit"):
        s.download("customer_a/vehicle_01/run2.BLF", tmp_path, 0, 200, 150)
    assert s.client.downloads == []
    # listing said 10 bytes but the object is really 200
    with pytest.raises(S3Error, match="exceeds"):
        s.download("customer_a/vehicle_01/run2.BLF", tmp_path, 1, 10, 150)
    assert not list(tmp_path.iterdir())


def test_download_failure_is_friendly(tmp_path):
    class Boom(FakeS3):
        def download_file(self, *a):
            raise ClientError("AccessDenied", 403)

    with pytest.raises(S3Error, match="Access denied"):
        svc(Boom(OBJECTS)).download("root.asc", tmp_path, 0, 1, 100)
