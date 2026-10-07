#!/usr/bin/env python3
"""Connects to a TAK server with an OpenTAKServer connection package, prints every event it receives
and can pose as a teammate. Standard library plus the `openssl` CLI (to unwrap the legacy .p12 files)."""
import argparse
import io
import re
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import zipfile
from datetime import datetime, timedelta, timezone
from pathlib import Path
from xml.etree import ElementTree

ENTRY = re.compile(r'<entry key="([^"]+)"[^>]*>([^<]*)</entry>')


def package_files(data, depth=0):
    files = {}
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for name in archive.namelist():
            content = archive.read(name)
            if name.endswith(".zip") and depth < 2:
                files.update(package_files(content, depth + 1))
            else:
                files[name.rsplit("/", 1)[-1]] = content
    return files


def read_package(path):
    files = package_files(Path(path).read_bytes())
    pref = next(v.decode() for k, v in files.items() if k.endswith(".pref") and b"connectString" in v)
    entries = dict(ENTRY.findall(pref))
    host, port, _ = entries["connectString0"].split(":")
    password = entries.get("clientPassword", entries.get("clientPassword0", "atakatak"))
    trust = next(v for k, v in files.items() if k.endswith(".p12") and "truststore" in k)
    client = next(v for k, v in files.items() if k.endswith(".p12") and "truststore" not in k)
    return host, int(port), password, trust, client


def to_pem(p12, password, out, extra, openssl):
    source = out.with_suffix(".p12")
    source.write_bytes(p12)
    subprocess.run([openssl, "pkcs12", "-legacy", "-in", str(source), "-out", str(out), "-passin", f"pass:{password}", *extra],
                   check=True, capture_output=True)
    return out


def tls_socket(args):
    host, port, password, trust, client = read_package(args.package)
    work = Path(tempfile.mkdtemp(prefix="tak-probe-"))
    client_pem = to_pem(client, password, work / "client.pem", ["-nodes"], args.openssl)
    trust_pem = to_pem(trust, password, work / "trust.pem", ["-nokeys"], args.openssl)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    # Trust is pinned to the package's own CA; TAK server certificates do not carry the connect address.
    context.check_hostname = False
    context.load_verify_locations(str(trust_pem))
    context.load_cert_chain(str(client_pem))
    target = (args.host or host, args.port or port)
    raw = socket.create_connection(target, timeout=10)
    print(f"conectado a {target[0]}:{target[1]}", flush=True)
    tls = context.wrap_socket(raw)
    tls.settimeout(None)
    return tls


def cot_time(moment):
    return moment.strftime("%Y-%m-%dT%H:%M:%S.") + f"{moment.microsecond // 1000:03d}Z"


def sa_event(uid, callsign, lat, lon):
    now = datetime.now(timezone.utc)
    return (f'<event version="2.0" uid="{uid}" type="a-f-G-U-C" how="h-e" time="{cot_time(now)}" start="{cot_time(now)}" '
            f'stale="{cot_time(now + timedelta(seconds=30))}"><point lat="{lat}" lon="{lon}" hae="9999999.0" ce="5.0" le="9999999.0"/>'
            f'<detail><contact callsign="{callsign}"/><takv device="tak-probe" platform="tak-probe" os="python" version="1"/></detail></event>')


def summary(xml):
    if "<!DOCTYPE" in xml or "<!ENTITY" in xml:
        return "(descartado: DOCTYPE/ENTITY)"
    try:
        event = ElementTree.fromstring(xml)
    except ElementTree.ParseError:
        return f"(no parsea) {xml[:120]}"
    point = event.find("point")
    contact = event.find("detail/contact")
    where = f"{point.get('lat')},{point.get('lon')}" if point is not None else "-"
    name = contact.get("callsign") if contact is not None else "-"
    return f"{event.get('type'):<12} {name:<22} {where:<26} {event.get('uid')}"


def read_events(sock):
    buffer = ""
    while True:
        chunk = sock.recv(65536)
        if not chunk:
            print("el servidor cerró la conexión", flush=True)
            return
        buffer += chunk.decode("utf-8", "replace")
        while "</event>" in buffer:
            end = buffer.index("</event>") + len("</event>")
            start = buffer.find("<event")
            if 0 <= start < end:
                print(time.strftime("%H:%M:%S"), summary(buffer[start:end]), flush=True)
            buffer = buffer[end:]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("package", help="paquete .zip de OpenTAKServer (ATAK o iTAK)")
    parser.add_argument("--host", help="otra dirección para conectar (p. ej. la IP LAN del servidor)")
    parser.add_argument("--port", type=int)
    parser.add_argument("--callsign", default="Prueba")
    parser.add_argument("--at", help="lat,lon: se anuncia como compañero en ese punto cada 2 s")
    parser.add_argument("--seconds", type=float, default=0, help="0 = hasta Ctrl+C")
    parser.add_argument("--openssl", default="openssl")
    args = parser.parse_args()

    sock = tls_socket(args)
    # OpenTAKServer keys devices by callsign too: a new uid under a callsign it already knows is silently dropped.
    uid = f"tak-probe-{args.callsign.lower()}"
    lat, lon = (args.at or "0,0").split(",")
    sock.sendall(sa_event(uid, args.callsign, lat, lon).encode())
    threading.Thread(target=read_events, args=(sock,), daemon=True).start()
    deadline = time.time() + args.seconds if args.seconds else float("inf")
    while time.time() < deadline:
        time.sleep(2)
        if args.at:
            sock.sendall(sa_event(uid, args.callsign, lat, lon).encode())
    sock.close()


if __name__ == "__main__":
    sys.exit(main())
