import re
import subprocess
import sys
import uuid
import zipfile
from importlib import util as importlib_util
from pathlib import Path
from xml.etree import ElementTree

import pytest

HERE = Path(__file__).parent
SOURCES = HERE / "map-sources"


def load_module():
    spec = importlib_util.spec_from_file_location("datapackage", HERE / "datapackage.py")
    module = importlib_util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


dp = load_module()


def params(root):
    return {p.get("name"): p.get("value") for p in root.find("Configuration")}


def test_same_name_same_uid():
    assert dp.package_uid("Mapas") == dp.package_uid("Mapas")
    assert dp.package_uid("Mapas") == str(uuid.uuid5(uuid.NAMESPACE_URL, "blindside-datapackage:Mapas"))


def test_different_name_different_uid():
    assert dp.package_uid("Mapas") != dp.package_uid("Otros")


def test_manifest_lists_every_entry():
    uid = dp.package_uid("Prueba")
    root = ElementTree.fromstring(dp.manifest_xml(uid, "Prueba", ["a.txt", "b.xml"]))
    assert root.tag == "MissionPackageManifest"
    assert root.get("version") == "2"
    assert params(root)["uid"] == uid
    assert params(root)["name"] == "Prueba"
    assert params(root)["onReceiveDelete"] == "false"
    entries = [c.get("zipEntry") for c in root.find("Contents")]
    assert entries == [f"{uid}/a.txt", f"{uid}/b.xml"]


def test_build_package_roundtrip(tmp_path):
    src = tmp_path / "a.txt"
    src.write_bytes("hola".encode())
    nested = tmp_path / "sub" / "b.bin"
    nested.parent.mkdir()
    nested.write_bytes(bytes(range(256)))
    out = tmp_path / "out" / "paquete.zip"
    assert dp.build_package("Prueba", [src, nested], out) == out
    uid = dp.package_uid("Prueba")
    with zipfile.ZipFile(out) as archive:
        assert sorted(archive.namelist()) == sorted(["MANIFEST/manifest.xml", f"{uid}/a.txt", f"{uid}/b.bin"])
        assert archive.read(f"{uid}/a.txt") == "hola".encode()
        assert archive.read(f"{uid}/b.bin") == bytes(range(256))
        root = ElementTree.fromstring(archive.read("MANIFEST/manifest.xml").decode("utf-8"))
    assert [c.get("zipEntry") for c in root.find("Contents")] == [f"{uid}/a.txt", f"{uid}/b.bin"]


def test_duplicate_basename_exits_2(tmp_path, capsys):
    first = tmp_path / "x" / "mismo.txt"
    first.parent.mkdir()
    first.write_text("1")
    second = tmp_path / "y" / "mismo.txt"
    second.parent.mkdir()
    second.write_text("2")
    code = dp.main(["--name", "P", "--out", str(tmp_path / "o.zip"), str(first), str(second)])
    assert code == 2
    assert capsys.readouterr().err.strip() != ""


def test_missing_file_exits_2(tmp_path, capsys):
    code = dp.main(["--name", "P", "--out", str(tmp_path / "o.zip"), str(tmp_path / "no-existe.txt")])
    assert code == 2
    assert capsys.readouterr().err.strip() != ""


def test_special_chars_roundtrip():
    name = 'Mapas & rutas <2026> "norte"'
    uid = dp.package_uid(name)
    root = ElementTree.fromstring(dp.manifest_xml(uid, name, ["a&b.txt"]))
    assert params(root)["name"] == name
    assert root.find("Contents")[0].get("zipEntry") == f"{uid}/a&b.txt"


def test_cli_builds_package(tmp_path):
    src = tmp_path / "nota.txt"
    src.write_text("hola")
    out = tmp_path / "paquete.zip"
    proc = subprocess.run(
        [sys.executable, str(HERE / "datapackage.py"), "--name", "Prueba", "--out", str(out), str(src)],
        capture_output=True, text=True,
    )
    assert proc.returncode == 0
    assert out.is_file()
    assert str(out) in proc.stdout


def test_cli_missing_file_exits_2(tmp_path):
    proc = subprocess.run(
        [sys.executable, str(HERE / "datapackage.py"), "--name", "P",
         "--out", str(tmp_path / "o.zip"), str(tmp_path / "no-existe.txt")],
        capture_output=True, text=True,
    )
    assert proc.returncode == 2
    assert proc.stderr.strip() != ""


SOURCE_FILES = sorted(SOURCES.glob("*.xml"))


def test_map_sources_present():
    assert len(SOURCE_FILES) == 6


@pytest.mark.parametrize("path", [p.name for p in SOURCE_FILES])
def test_map_source(path):
    raw = (SOURCES / path).read_text(encoding="utf-8")
    assert re.search(r"&(?!(amp|lt|gt|quot|apos);)", raw) is None
    root = ElementTree.parse(SOURCES / path).getroot()
    assert root.tag == "customMapSource"
    name = root.findtext("name")
    url = root.findtext("url")
    assert name and name.strip() != ""
    assert url and url.strip() != ""
    assert root.findtext("tileType") in ("jpg", "png")
    int(root.findtext("maxZoom"))
    assert ("{$x}" in url and "{$y}" in url and "{$z}" in url) or "{$q}" in url
    assert (root.find("serverParts") is not None) == ("{$serverpart}" in url)
