#!/usr/bin/env python3
import argparse
import sys
import uuid
import zipfile
from pathlib import Path
from xml.sax.saxutils import escape


def package_uid(name: str) -> str:
    return str(uuid.uuid5(uuid.NAMESPACE_URL, "blindside-datapackage:" + name))


def attr(value: str) -> str:
    return escape(value, {'"': "&quot;"})


def manifest_xml(uid: str, name: str, entries: list[str]) -> str:
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<MissionPackageManifest version="2">',
        "  <Configuration>",
        f'    <Parameter name="uid" value="{attr(uid)}"/>',
        f'    <Parameter name="name" value="{attr(name)}"/>',
        '    <Parameter name="onReceiveDelete" value="false"/>',
        "  </Configuration>",
        "  <Contents>",
    ]
    for entry in entries:
        lines.append(f'    <Content ignore="false" zipEntry="{attr(uid)}/{attr(entry)}"/>')
    lines.append("  </Contents>")
    lines.append("</MissionPackageManifest>")
    return "\n".join(lines) + "\n"


def build_package(name: str, files: list[Path], out: Path) -> Path:
    for path in files:
        if not path.is_file():
            raise FileNotFoundError(f"no existe el archivo: {path}")
    basenames = [path.name for path in files]
    seen = set()
    for base in basenames:
        if base in seen:
            raise ValueError(f"nombre de archivo duplicado: {base}")
        seen.add(base)
    uid = package_uid(name)
    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("MANIFEST/manifest.xml", manifest_xml(uid, name, basenames))
        for path in files:
            archive.write(path, f"{uid}/{path.name}")
    return out


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="crea un paquete de datos TAK (.zip) desde archivos locales")
    parser.add_argument("--name", required=True, help="nombre del paquete")
    parser.add_argument("--out", type=Path, required=True, help="archivo zip de salida")
    parser.add_argument("files", nargs="+", type=Path, help="archivos para incluir en el paquete")
    args = parser.parse_args(argv)
    try:
        out = build_package(args.name, args.files, args.out)
    except (FileNotFoundError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    print(f"paquete creado: {out} ({out.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
