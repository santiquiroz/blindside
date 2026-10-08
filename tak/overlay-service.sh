#!/usr/bin/env bash
# Installs tak-overlay.py as a systemd service inside the OpenTAKServer WSL distro, so the overlay is broadcast whenever the server runs.
# Usage (inside WSL, as root): bash tak/overlay-service.sh <package.zip> <callsign> <geojson> [<geojson>...]
set -euo pipefail
USAGE="usage: overlay-service.sh <package.zip> <callsign> <geojson> [<geojson>...]"
PACKAGE="$(realpath "${1:?$USAGE}")"
CALLSIGN="${2:?$USAGE}"
shift 2
if [ "$#" -lt 1 ]; then echo "$USAGE" >&2; exit 1; fi
GEOJSONS=""
for G in "$@"; do
  GP="$(realpath "$G")"
  GEOJSONS="$GEOJSONS \"$GP\""
done
SCRIPT="$(realpath "$(dirname "$0")/tak-overlay.py")"

cat > /etc/systemd/system/blindside-overlay.service <<EOF
[Unit]
Description=Blindside: capa táctica TAK ($CALLSIGN)
After=network-online.target eud_handler_ssl.service
Wants=network-online.target

[Service]
User=ots
Environment=PYTHONUNBUFFERED=1
ExecStart=/usr/bin/python3 "$SCRIPT" "$PACKAGE"$GEOJSONS --host 127.0.0.1 --callsign "$CALLSIGN"
Restart=always
RestartSec=15

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now blindside-overlay.service
systemctl --no-pager status blindside-overlay.service | head -5
