"""Deletes the per-player connection packages (*_CONFIG.zip, *_CONFIG_iTAK.zip) OpenTAKServer leaves public."""

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from datetime import datetime, timedelta
from pathlib import Path

CONFIG_RE = re.compile(r"^.+_CONFIG(_iTAK)?\.zip$")


# OpenTAKServer stamps uploads with the server's LOCAL time followed by a "Z", so the time is read as naive local time.
def parse_time(text):
    if not isinstance(text, str):
        return None
    try:
        return datetime.fromisoformat(text.rstrip("Z")).replace(tzinfo=None)
    except ValueError:
        return None


def stale_config_packages(packages, now, max_age):
    stale = []
    for package in packages:
        uploaded = parse_time(package.get("submission_time"))
        if CONFIG_RE.match(str(package.get("filename") or "")) and uploaded is not None and now - uploaded >= max_age:
            stale.append(package)
    return stale


class Session:
    def __init__(self, host, connect, jar):
        self.host, self.connect, self.jar = host, connect, jar
        self.csrf = None

    def call(self, path, *args):
        cmd = ["curl", "-sk", "-b", self.jar, "-c", self.jar, "-H", "Content-Type: application/json",
               "-H", f"Referer: https://{self.host}/", "--connect-to", f"{self.host}:443:{self.connect}"]
        if self.csrf:
            cmd += ["-H", f"X-XSRF-TOKEN: {self.csrf}"]
        out = subprocess.run(cmd + [f"https://{self.host}{path}", *args], capture_output=True, text=True, check=True)
        return json.loads(out.stdout or "{}")

    def login(self, password):
        body = json.dumps({"username": "administrator", "password": password})
        self.csrf = self.call("/api/login", "-X", "POST", "-d", body)["response"]["csrf_token"]

    def packages(self):
        return self.call("/api/data_packages?per_page=500").get("results", [])

    def delete(self, package_hash):
        return self.call(f"/api/data_packages?hash={package_hash}", "-X", "DELETE")


def read_password(path):
    env = os.environ.get("OTS_ADMIN_PASSWORD")
    return env if env else Path(path).expanduser().read_text().strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default=os.environ.get("OTS_PUBLIC_HOST"), help="nombre público del servidor (el de la web)")
    parser.add_argument("--connect", default="127.0.0.1:443", help="dónde atiende la web realmente")
    parser.add_argument("--max-age-min", type=float, default=30.0, help="minutos antes de borrar un paquete de conexión")
    parser.add_argument("--password-file", default="~/.blindside/tak-admin.txt")
    parser.add_argument("--dry-run", action="store_true", help="solo muestra qué borraría")
    args = parser.parse_args()
    if not args.host:
        parser.error("falta --host (o OTS_PUBLIC_HOST)")
    with tempfile.NamedTemporaryFile(prefix="ots-purge-", suffix=".cookies") as jar:
        session = Session(args.host, args.connect, jar.name)
        session.login(read_password(args.password_file))
        stale = stale_config_packages(session.packages(), datetime.now(), timedelta(minutes=args.max_age_min))
        for package in stale:
            result = "no borrado (prueba)" if args.dry_run else session.delete(package["hash"])
            print(f"{package['filename']} de {package.get('submission_user')}: {result}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
