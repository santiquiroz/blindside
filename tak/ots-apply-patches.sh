#!/usr/bin/env bash
# Applies tak/ots-patches/*.patch to the installed OpenTAKServer; rerun after upgrading it, then restart opentakserver.
# Usage (inside WSL, as the user that owns the venv): bash tak/ots-apply-patches.sh [site-packages dir]
set -euo pipefail

SITE="${1:-/home/ots/.opentakserver_venv/lib/python3.12/site-packages}"
PATCHES="$(cd "$(dirname "$0")" && pwd)/ots-patches"

# The OpenTAKServer wheel ships CRLF sources while upstream patches are LF: convert hunk lines before applying.
to_crlf() {
  python3 - "$1" "$2" <<'PY'
import sys
out, hunk = [], False
for line in open(sys.argv[1], encoding="utf-8").read().split("\n"):
    if line.startswith("@@"):
        hunk = True
    elif line.startswith("-- ") or line == "--" or line.startswith("diff --git"):
        hunk = False
    out.append(line + ("\r" if hunk and line and not line.startswith("@@") else ""))
open(sys.argv[2], "w", encoding="utf-8", newline="").write("\n".join(out))
PY
}

cd "$SITE"
for patch_file in "$PATCHES"/*.patch; do
  converted="$(mktemp)"
  to_crlf "$patch_file" "$converted"
  if patch -p1 -R --binary --dry-run -s -f < "$converted" >/dev/null 2>&1; then
    echo "already applied: $(basename "$patch_file")"
  else
    patch -p1 -N --binary -b -z .orig-patch < "$converted"
    echo "applied: $(basename "$patch_file")"
  fi
  rm -f "$converted"
done
