import os
import time
from pathlib import Path

import pytest

from services.file_service import (
    JobWorkspace,
    UnsafePathError,
    ValidationError,
    cleanup_stale_workspaces,
    human_size,
    safe_join,
    sanitize_filename,
    unique_name,
    validate_upload,
)


@pytest.mark.parametrize(
    "raw,expected",
    [
        ("../../etc/passwd", "passwd"),
        ("C:\\Users\\x\\evil.dbc", "evil.dbc"),
        ("a b(1).dbc", "a b(1).dbc"),
        ("we<ird>*name?.asc", "we_ird__name_.asc"),
        ("   ", "file"),
        ("...", "file"),
        (".hidden", "hidden"),
        ("nul\x00.dbc", "nul.dbc"),
    ],
)
def test_sanitize_filename(raw, expected):
    assert sanitize_filename(raw) == expected


def test_sanitize_long_name_keeps_extension():
    out = sanitize_filename("x" * 500 + ".mf4")
    assert len(out) <= 120 and out.endswith(".mf4")


def test_safe_join_blocks_traversal(tmp_path):
    assert safe_join(tmp_path, "a", "b.txt").is_relative_to(tmp_path.resolve())
    with pytest.raises(UnsafePathError):
        safe_join(tmp_path, "..", "x")
    with pytest.raises(UnsafePathError):
        safe_join(tmp_path, "/etc/passwd")


def test_unique_name_is_case_insensitive():
    assert unique_name("a.dbc", {"b.dbc"}) == "a.dbc"
    assert unique_name("a.dbc", {"A.DBC"}) == "a (2).dbc"
    assert unique_name("a.dbc", {"a.dbc", "a (2).dbc"}) == "a (3).dbc"
    assert unique_name("noext", {"noext"}) == "noext (2)"


def test_validate_upload_rules():
    assert validate_upload("../x.DBC", 10, (".dbc",), 100) == "x.DBC"
    with pytest.raises(ValidationError, match="unsupported"):
        validate_upload("x.txt", 10, (".dbc",), 100)
    with pytest.raises(ValidationError, match="empty"):
        validate_upload("x.dbc", 0, (".dbc",), 100)
    with pytest.raises(ValidationError, match="exceeds"):
        validate_upload("x.dbc", 101, (".dbc",), 100)


def test_human_size():
    assert human_size(512) == "512 B"
    assert human_size(2048) == "2.0 KB"
    assert human_size(5 * 1024 * 1024) == "5.0 MB"
    assert human_size(None) == "?"


def test_workspace_creates_and_cleans_up(tmp_path):
    with JobWorkspace(tmp_path) as ws:
        p = ws.write_bytes("dbc", "../../evil.dbc", b"data")
        assert p.parent == ws.path / "dbc" and p.name == "evil.dbc"
        root = ws.path
        assert root.exists()
    assert not root.exists()


def test_workspace_cleans_up_on_exception(tmp_path):
    root = None
    with pytest.raises(RuntimeError):
        with JobWorkspace(tmp_path) as ws:
            root = ws.path
            raise RuntimeError("boom")
    assert root is not None and not root.exists()


def test_cleanup_stale_workspaces(tmp_path):
    old = tmp_path / "decodercmp_old"
    new = tmp_path / "decodercmp_new"
    other = tmp_path / "unrelated"
    for d in (old, new, other):
        d.mkdir()
    past = time.time() - 48 * 3600
    os.utime(old, (past, past))
    os.utime(other, (past, past))
    assert cleanup_stale_workspaces(tmp_path, max_age_hours=24) == 1
    assert not old.exists() and new.exists() and other.exists()
