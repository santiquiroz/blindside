#!/usr/bin/env bash
# Installs tak-overlay.py as a systemd service inside the OpenTAKServer WSL distro, so the overlay is broadcast whenever the server runs.
# Usage (inside WSL, as root): bash tak/overlay-service.sh <package.zip> <overlay.geojson> [callsign]
set -euo pipefail
PACKAGE="$(realpath "${1:?usage: overlay-service.sh <package.zip> <overlay.geojson> [callsign]}")"
GEOJSON="$(realpath "${2:?usage: overlay-service.sh <package.zip> <overlay.geojson> [callsign]}")"
CALLSIGN="${3:-Mapa}"
SCRIPT="$(realpath "$(dirname "$0")/tak-overlay.py")"

cat > /etc/systemd/system/blindside-overlay.service <<EOF
[Unit]
Description=Blindside: capa táctica TAK ($CALLSIGN)
After=network-online.target eud_handler_ssl.service
Wants=network-online.target

[Service]
User=ots
Environment=PYTHONUNBUFFERED=1
ExecStart=/usr/bin/python3 "$SCRIPT" "$PACKAGE" "$GEOJSON" --host 127.0.0.1 --callsign "$CALLSIGN"
Restart=always
RestartSec=15

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now blindside-overlay.service
systemctl --no-pager status blindside-overlay.service | head -5
