#!/usr/bin/env bash
# Creates an OpenTAKServer user and copies its ATAK and iTAK connection packages out of WSL.
# Usage: OTS_PUBLIC_HOST=<public ip or name> tak/ots-player.sh <username> [out_dir]
# The admin password is read from $OTS_ADMIN_PASSWORD or ~/.blindside/tak-admin.txt.
set -euo pipefail

USERNAME="${1:?usage: ots-player.sh <username> [out_dir]}"
OUT_DIR="${2:-$HOME/.blindside/tak-packages}"
PUBLIC_HOST="${OTS_PUBLIC_HOST:?set OTS_PUBLIC_HOST to the address players will connect to}"
DISTRO="${OTS_WSL_DISTRO:-Ubuntu-24.04}"
ADMIN_PASSWORD="${OTS_ADMIN_PASSWORD:-$(tr -d '\r\n' < "$HOME/.blindside/tak-admin.txt")}"
COOKIES="$(mktemp)"
trap 'rm -f "$COOKIES"' EXIT

# Every call names the public host (resolved to this PC): the session cookie and the package's connectString both follow it.
api() {
  local path="$1"
  shift
  curl -sk -b "$COOKIES" -c "$COOKIES" -H "Content-Type: application/json" -H "Referer: https://$PUBLIC_HOST/" \
    --connect-to "$PUBLIC_HOST:443:127.0.0.1:443" "https://$PUBLIC_HOST$path" "$@"
}

csrf_token() {
  api /api/login -X POST -d "{\"username\":\"administrator\",\"password\":\"$ADMIN_PASSWORD\"}" |
    python -c "import sys,json;print(json.load(sys.stdin)['response']['csrf_token'])"
}

CSRF="$(csrf_token)"
USER_PASSWORD="$(python -c "import secrets;print(secrets.token_urlsafe(18))")"

# A user that already exists answers with an error; the certificate step still works for it.
api /api/user/add -H "X-XSRF-TOKEN: $CSRF" -X POST \
  -d "{\"username\":\"$USERNAME\",\"password\":\"$USER_PASSWORD\",\"confirm_password\":\"$USER_PASSWORD\",\"roles\":[\"user\"]}"
echo

api /api/certificate -H "X-XSRF-TOKEN: $CSRF" -X POST -d "{\"username\":\"$USERNAME\"}"
echo

mkdir -p "$OUT_DIR"
chmod 700 "$OUT_DIR"
for suffix in CONFIG CONFIG_iTAK; do
  MSYS_NO_PATHCONV=1 wsl.exe -d "$DISTRO" -- cat "/home/ots/ots/ca/certs/$USERNAME/${USERNAME}_${suffix}.zip" > "$OUT_DIR/${USERNAME}_${suffix}.zip"
done
ls -l "$OUT_DIR/${USERNAME}"_CONFIG*.zip
