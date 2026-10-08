import importlib.util
from pathlib import Path

import pytest

spec = importlib.util.spec_from_file_location("ots_public_address", Path(__file__).with_name("ots-public-address.py"))
ots = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ots)

SOURCE_TWO = (
    "from flask import Blueprint\n"
    "from flask import request\n"
    "a = urlparse(request.url_root).hostname\n"
    "b = urlparse(request.url_root).hostname\n"
)


def test_patch_source_replaces_all_and_inserts_import_once():
    new = ots.patch_source(SOURCE_TWO)
    assert new.count(ots.PATCHED_EXPR) == 2
    assert ots.HOST_EXPR not in new.replace(ots.PATCHED_EXPR, "")
    assert new.count(ots.IMPORT_LINE) == 1
    lines = new.splitlines()
    assert lines[0] == "from flask import Blueprint"
    assert lines[1] == ots.IMPORT_LINE
    assert lines[2] == "from flask import request"


def test_patch_source_is_idempotent():
    once = ots.patch_source(SOURCE_TWO)
    assert ots.patch_source(once) == once


def test_patch_source_without_host_expr_unchanged():
    src = "from flask import request\nx = 1\n"
    assert ots.patch_source(src) == src
    assert ots.patch_source("x = 1\n") == "x = 1\n"


def test_patch_source_without_flask_import_raises():
    with pytest.raises(ValueError):
        ots.patch_source("x = urlparse(request.url_root).hostname\n")


def test_patch_file_preserves_crlf(tmp_path):
    p = tmp_path / "a.py"
    p.write_bytes(b"from flask import request\r\nx = urlparse(request.url_root).hostname\r\n")
    assert ots.patch_file(p) is True
    data = p.read_bytes()
    assert b"\r\n" in data
    assert b"\n" not in data.replace(b"\r\n", b"")
    assert ots.PATCHED_EXPR.encode() in data


def test_patch_tree_nested_backup_and_second_call_empty(tmp_path):
    pkg = tmp_path / "opentakserver"
    sub = pkg / "sub"
    sub.mkdir(parents=True)
    target = sub / "views.py"
    original = "from flask import request\nx = urlparse(request.url_root).hostname\n"
    target.write_text(original, encoding="utf-8")
    (pkg / "plain.py").write_text("x = 1\n", encoding="utf-8")
    changed = ots.patch_tree(pkg)
    assert changed == [target]
    backup = target.with_name(target.name + ".orig")
    assert backup.read_text(encoding="utf-8") == original
    before = backup.read_bytes()
    assert ots.patch_tree(pkg) == []
    assert backup.read_bytes() == before


def test_set_config_value_replaces_existing():
    text = "OTS_SSL_STREAMING_PORT: 8089\nOTS_PUBLIC_ADDRESS: old\nOTHER: 1\n"
    assert ots.set_config_value(text, "OTS_PUBLIC_ADDRESS", "tak.example.com") == (
        "OTS_SSL_STREAMING_PORT: 8089\nOTS_PUBLIC_ADDRESS: tak.example.com\nOTHER: 1\n"
    )


def test_set_config_value_appends_without_trailing_newline():
    text = "OTS_SSL_STREAMING_PORT: 8089"
    assert ots.set_config_value(text, "OTS_PUBLIC_ADDRESS", "1.2.3.4") == (
        "OTS_SSL_STREAMING_PORT: 8089\nOTS_PUBLIC_ADDRESS: 1.2.3.4\n"
    )


def test_main_config_without_address_exits(tmp_path):
    with pytest.raises(SystemExit):
        ots.main(["--package-dir", str(tmp_path), "--config", str(tmp_path / "config.yml")])
