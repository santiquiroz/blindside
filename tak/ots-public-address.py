#!/usr/bin/env python3
# Makes OpenTAKServer packages use OTS_PUBLIC_ADDRESS instead of the browser's host; rerun after upgrading OpenTAKServer, then restart it.
import argparse
import re
import sys
from pathlib import Path

HOST_EXPR = 'urlparse(request.url_root).hostname'
PATCHED_EXPR = '(_ots_public_app.config.get("OTS_PUBLIC_ADDRESS") or urlparse(request.url_root).hostname)'
IMPORT_LINE = 'from flask import current_app as _ots_public_app'


def is_patched(text: str) -> bool:
    return "_ots_public_app" in text


def patch_source(text: str) -> str:
    if is_patched(text):
        return text
    if HOST_EXPR not in text:
        return text
    new = text.replace(HOST_EXPR, PATCHED_EXPR)
    lines = new.splitlines(keepends=True)
    for i, line in enumerate(lines):
        if line.startswith("from flask import"):
            ending = "\r\n" if line.endswith("\r\n") else "\n"
            if not line.endswith("\n"):
                lines[i] = line + ending
            lines.insert(i + 1, IMPORT_LINE + ending)
            return "".join(lines)
    raise ValueError("no 'from flask import' line found for OTS_PUBLIC_ADDRESS import")


def patch_file(path: Path) -> bool:
    with open(path, "r", encoding="utf-8", newline="") as f:
        text = f.read()
    new = patch_source(text)
    if new == text:
        return False
    backup = path.with_name(path.name + ".orig")
    if not backup.exists():
        with open(backup, "w", encoding="utf-8", newline="") as f:
            f.write(text)
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(new)
    return True


def patch_tree(package_dir: Path) -> list[Path]:
    changed = []
    for path in sorted(package_dir.rglob("*.py")):
        if patch_file(path):
            changed.append(path)
    return changed


def set_config_value(text: str, key: str, value: str) -> str:
    pattern = re.compile("^" + re.escape(key) + ":.*$", re.MULTILINE)
    new, count = pattern.subn(lambda m: key + ": " + value, text)
    if count:
        return new
    if text and not text.endswith("\n"):
        text += "\n"
    return text + key + ": " + value + "\n"


def update_config(path: Path, address: str) -> None:
    with open(path, "r", encoding="utf-8", newline="") as f:
        text = f.read()
    new = set_config_value(text, "OTS_PUBLIC_ADDRESS", address)
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(new)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--package-dir", required=True, type=Path)
    parser.add_argument("--config", type=Path, default=None)
    parser.add_argument("--address", default=None)
    args = parser.parse_args(argv)
    if args.config is not None and args.address is None:
        parser.error("--address is required when --config is given")
    changed = patch_tree(args.package_dir)
    if changed:
        for path in changed:
            print(f"patched {path}")
    else:
        print("already patched")
    if args.config is not None:
        update_config(args.config, args.address)
        print(f"OTS_PUBLIC_ADDRESS={args.address} in {args.config}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
