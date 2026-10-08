import argparse
import hashlib
import importlib.util
import json
import ssl
import sys
import threading
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from xml.sax.saxutils import escape

_spec = importlib.util.spec_from_file_location("tak_probe", Path(__file__).with_name("tak-probe.py"))
probe = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(probe)

D_OPEN = "<" + "detail" + ">"
D_CLOSE = "</" + "detail" + ">"


def _esc(s):
    return escape(str(s), {'"': '&quot;', "'": '&apos;'})


def _first_lonlat(coords):
    c = coords
    try:
        while isinstance(c, (list, tuple)) and len(c) > 0 and isinstance(c[0], (list, tuple)):
            c = c[0]
        if isinstance(c, (list, tuple)) and len(c) >= 2:
            return float(c[0]), float(c[1])
    except (TypeError, ValueError):
        pass
    return 0.0, 0.0


def _num(value, default):
    try:
        if value is None or value == "":
            return default
        return float(value)
    except (TypeError, ValueError):
        return default


def _color(props, key, default):
    v = props.get(key) or default
    if not isinstance(v, str) or not v:
        return default
    return v


def _labels(props):
    # A big zone (a whole slope) repeats its name along every edge; "labels": false keeps the map readable.
    return "false" if props.get("labels") is False else "true"


def argb_int(hex_rgb: str, opacity: float) -> int:
    try:
        o = float(opacity)
    except (TypeError, ValueError):
        o = 1.0
    if o != o:
        o = 1.0
    if o < 0:
        o = 0
    if o > 1:
        o = 1
    try:
        alpha = round(o * 255)
    except (ValueError, OverflowError):
        alpha = 255
    s = hex_rgb.strip().lstrip("#") if isinstance(hex_rgb, str) else ""
    try:
        if len(s) != 6:
            raise ValueError
        r = int(s[0:2], 16)
        g = int(s[2:4], 16)
        b = int(s[4:6], 16)
    except ValueError:
        r, g, b = 255, 255, 255
    v = (alpha << 24) | (r << 16) | (g << 8) | b
    if v >= 2 ** 31:
        v -= 2 ** 32
    return v


def feature_uid(feature: dict, prefix: str) -> str:
    props = feature.get("properties") or {}
    if not isinstance(props, dict):
        props = {}
    fid = props.get("id")
    if fid is not None and fid != "":
        return f"{prefix}-{fid}"
    folder = props.get("folder")
    name = props.get("name")
    folder = "" if folder is None else str(folder)
    name = "" if name is None else str(name)
    geom = feature.get("geometry") or {}
    if not isinstance(geom, dict):
        geom = {}
    gtype = geom.get("type")
    gtype = "" if gtype is None else str(gtype)
    lon, lat = _first_lonlat(geom.get("coordinates"))
    h = hashlib.sha1(f"{folder}|{name}|{gtype}|{lat:.5f},{lon:.5f}".encode("utf-8")).hexdigest()[:12]
    return f"{prefix}-{h}"


def _vertices(line):
    out = []
    if not isinstance(line, list):
        return out
    for p in line:
        try:
            out.append((float(p[0]), float(p[1])))
        except (TypeError, ValueError, IndexError):
            return []
    return out


def feature_event(feature: dict, uid: str, now: datetime, stale_s: int) -> str | None:
    geom = feature.get("geometry") if isinstance(feature, dict) else None
    if not isinstance(geom, dict):
        return None
    gtype = geom.get("type")
    props = feature.get("properties") or {}
    if not isinstance(props, dict):
        props = {}
    name = props.get("name")
    desc = props.get("description")
    name = "" if name is None else str(name)
    desc = "" if desc is None else str(desc)
    t = probe.cot_time(now)
    s = probe.cot_time(now + timedelta(seconds=stale_s))
    head = f'<event version="2.0" uid="{_esc(uid)}" type="TYPE" how="h-g-i-g-o" time="{t}" start="{t}" stale="{s}">'
    remarks = f"<remarks>{_esc(desc)}</remarks>" if desc != "" else ""
    if gtype == "Point":
        try:
            coords = geom.get("coordinates")
            lon = float(coords[0])
            lat = float(coords[1])
        except (TypeError, ValueError, IndexError):
            return None
        argb = argb_int(_color(props, "marker-color", "#ffcc00"), 1)
        body = (
            f'<point lat="{lat:.7f}" lon="{lon:.7f}" hae="9999999.0" ce="9999999.0" le="9999999.0"/>'
            + D_OPEN + f'<contact callsign="{_esc(name)}"/>'
            + f'<usericon iconsetpath="COT_MAPPING_SPOTMAP/b-m-p-s-m/{argb}"/>'
            + f'<color argb="{argb}"/>{remarks}<archive/>' + D_CLOSE + "</event>"
        )
        return head.replace('type="TYPE"', 'type="b-m-p-s-m"') + body
    if gtype == "LineString":
        verts = _vertices(geom.get("coordinates"))
        if not verts:
            return None
        mlon = sum(v[0] for v in verts) / len(verts)
        mlat = sum(v[1] for v in verts) / len(verts)
        links = "".join(f'<link point="{lat:.7f},{lon:.7f}"/>' for lon, lat in verts)
        argb = argb_int(_color(props, "stroke", "#ffffff"), _num(props.get("stroke-opacity", 1), 1))
        w = _num(props.get("stroke-width", 2), 2)
        body = (
            f'<point lat="{mlat:.7f}" lon="{mlon:.7f}" hae="9999999.0" ce="9999999.0" le="9999999.0"/>'
            + D_OPEN + links
            + f'<strokeColor value="{argb}"/><strokeWeight value="{w:.1f}"/>'
            + f'<contact callsign="{_esc(name)}"/>{remarks}<archive/><labels_on value="false"/>' + D_CLOSE + "</event>"
        )
        return head.replace('type="TYPE"', 'type="u-d-f"') + body
    if gtype == "Polygon":
        coords = geom.get("coordinates")
        if not isinstance(coords, list) or not coords or not isinstance(coords[0], list):
            return None
        ring = _vertices(coords[0])
        if not ring:
            return None
        if ring[0] != ring[-1]:
            ring = ring + [ring[0]]
        distinct = ring[:-1] if len(ring) >= 2 and ring[0] == ring[-1] else ring
        if not distinct:
            return None
        mlon = sum(v[0] for v in distinct) / len(distinct)
        mlat = sum(v[1] for v in distinct) / len(distinct)
        links = "".join(f'<link point="{lat:.7f},{lon:.7f}"/>' for lon, lat in ring)
        sargb = argb_int(_color(props, "stroke", "#ffffff"), _num(props.get("stroke-opacity", 1), 1))
        w = _num(props.get("stroke-width", 2), 2)
        fill = props.get("fill") or props.get("stroke") or "#ffffff"
        if not isinstance(fill, str) or not fill:
            fill = "#ffffff"
        fargb = argb_int(fill, _num(props.get("fill-opacity", 0.25), 0.25))
        body = (
            f'<point lat="{mlat:.7f}" lon="{mlon:.7f}" hae="9999999.0" ce="9999999.0" le="9999999.0"/>'
            + D_OPEN + links
            + f'<strokeColor value="{sargb}"/><strokeWeight value="{w:.1f}"/><fillColor value="{fargb}"/>'
            + f'<contact callsign="{_esc(name)}"/>{remarks}<archive/><labels_on value="{_labels(props)}"/>' + D_CLOSE + "</event>"
        )
        return head.replace('type="TYPE"', 'type="u-d-f"') + body
    return None


def delete_event(target_uid: str, now: datetime) -> str:
    t = probe.cot_time(now)
    s = probe.cot_time(now + timedelta(seconds=20))
    return (
        f'<event version="2.0" uid="{_esc(f"{target_uid}-delete")}" type="t-x-d-d" how="h-g-i-g-o" time="{t}" start="{t}" stale="{s}">'
        f'<point lat="0.0" lon="0.0" hae="9999999.0" ce="9999999.0" le="9999999.0"/>'
        + D_OPEN + f'<link uid="{_esc(target_uid)}" relation="none" type="none"/><__forcedelete/>' + D_CLOSE + "</event>"
    )


def identity_event(uid: str, callsign: str, now: datetime) -> str:
    t = probe.cot_time(now)
    s = probe.cot_time(now + timedelta(seconds=5))
    return (
        f'<event version="2.0" uid="{_esc(uid)}" type="a-f-G-E-S" how="h-g-i-g-o" time="{t}" start="{t}" stale="{s}">'
        f'<point lat="0.0" lon="0.0" hae="9999999.0" ce="9999999.0" le="9999999.0"/>'
        + D_OPEN + f'<contact callsign="{_esc(callsign)}"/>'
        + '<takv device="tak-overlay" platform="Blindside" os="python" version="1"/>' + D_CLOSE + "</event>"
    )


def load_features(path: Path, skip_folders: set[str]) -> list[dict]:
    text = Path(path).read_text(encoding="utf-8")
    try:
        data = json.loads(text)
    except json.JSONDecodeError as e:
        raise ValueError(f"json inválido: {e}") from e
    if not isinstance(data, dict) or data.get("type") != "FeatureCollection" or not isinstance(data.get("features"), list):
        raise ValueError("no es un FeatureCollection")
    out = []
    for f in data["features"]:
        if not isinstance(f, dict):
            continue
        props = f.get("properties") or {}
        if not isinstance(props, dict):
            props = {}
        if props.get("folder") in skip_folders:
            continue
        out.append(f)
    return out


def latest_mtime(paths) -> float:
    latest = 0.0
    for p in paths:
        try:
            m = Path(p).stat().st_mtime
        except OSError:
            continue
        if m > latest:
            latest = m
    return latest


def wait_for_change(paths, period, poll=2.0, sleep=time.sleep, mtime=latest_mtime) -> bool:
    start = mtime(paths)
    waited = 0
    while waited < period:
        step = min(poll, period - waited)
        sleep(step)
        waited += step
        if mtime(paths) != start:
            return True
    return False


def load_all(paths, skip: set[str], previous: dict) -> tuple[list[dict], dict]:
    all_feats = []
    by_path = {}
    for p in paths:
        key = str(p)
        try:
            feats = load_features(p, skip)
        except Exception as e:
            print(f"aviso: no se pudo leer {p}: {e}, se mantiene la versión anterior", flush=True)
            feats = previous.get(key, [])
        by_path[key] = feats
        all_feats.extend(feats)
    return all_feats, by_path


def plan_cycle(features, previous_uids: set[str], prefix: str, now: datetime, stale_s: int) -> tuple[list[str], set[str]]:
    events = []
    current = set()
    for f in features:
        uid = feature_uid(f, prefix)
        current.add(uid)
        ev = feature_event(f, uid, now, stale_s)
        if ev is not None:
            events.append(ev)
    for uid in sorted(previous_uids - current):
        events.append(delete_event(uid, now))
    return events, current


def _discard(sock):
    try:
        while True:
            data = sock.recv(65536)
            if not data:
                break
    except Exception:
        pass


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("package")
    parser.add_argument("geojson", nargs="+")
    parser.add_argument("--host")
    parser.add_argument("--port", type=int)
    parser.add_argument("--callsign", default="Mapa")
    parser.add_argument("--prefix", default="overlay")
    parser.add_argument("--period", type=float, default=120)
    parser.add_argument("--stale-hours", type=float, default=12, dest="stale_hours")
    parser.add_argument("--skip-folder", action="append", default=None, dest="skip_folder")
    parser.add_argument("--once", action="store_true")
    parser.add_argument("--openssl", default="openssl")
    args = parser.parse_args(argv)
    skip = set(args.skip_folder) if args.skip_folder else {"Curvas de nivel (10 m)"}
    stale_s = int(args.stale_hours * 3600)
    geo_paths = [Path(g) for g in args.geojson]
    prefix = args.prefix
    callsign = args.callsign
    period = args.period
    if args.once:
        try:
            sock = probe.tls_socket(args)
        except (OSError, ssl.SSLError) as e:
            print(f"error conectando: {e}", flush=True)
            return 1
        try:
            now = datetime.now(timezone.utc)
            sock.sendall(identity_event(f"{prefix}-identity", callsign, now).encode("utf-8"))
            features, _by_path = load_all(geo_paths, skip, {})
            events, _current = plan_cycle(features, set(), prefix, now, stale_s)
            for ev in events:
                sock.sendall(ev.encode("utf-8"))
            print(f"{time.strftime('%H:%M:%S')} enviados {len(events)} objetos, 0 borrados", flush=True)
            time.sleep(2)
        except (OSError, ssl.SSLError) as e:
            print(f"error de conexión: {e}", flush=True)
            return 1
        finally:
            try:
                sock.close()
            except Exception:
                pass
        return 0
    features = []
    by_path = {}
    previous = set()
    backoff = 5
    try:
        while True:
            try:
                sock = probe.tls_socket(args)
            except (OSError, ssl.SSLError) as e:
                print(f"error conectando: {e}, reintentando en {backoff} s", flush=True)
                time.sleep(backoff)
                backoff = min(backoff * 2, 60)
                continue
            try:
                now = datetime.now(timezone.utc)
                sock.sendall(identity_event(f"{prefix}-identity", callsign, now).encode("utf-8"))
                threading.Thread(target=_discard, args=(sock,), daemon=True).start()
                while True:
                    now = datetime.now(timezone.utc)
                    features, by_path = load_all(geo_paths, skip, by_path)
                    events, current = plan_cycle(features, previous, prefix, now, stale_s)
                    deleted = len(previous - current)
                    sock.sendall(identity_event(f"{prefix}-identity", callsign, now).encode("utf-8"))
                    for ev in events:
                        sock.sendall(ev.encode("utf-8"))
                    sent = len(events) - deleted
                    print(f"{time.strftime('%H:%M:%S')} enviados {sent} objetos, {deleted} borrados", flush=True)
                    previous = current
                    backoff = 5
                    wait_for_change(geo_paths, period)
            except KeyboardInterrupt:
                try:
                    sock.close()
                except Exception:
                    pass
                return 0
            except (OSError, ssl.SSLError) as e:
                print(f"error de conexión: {e}, reintentando en {backoff} s", flush=True)
                try:
                    sock.close()
                except Exception:
                    pass
                time.sleep(backoff)
                backoff = min(backoff * 2, 60)
                continue
    except KeyboardInterrupt:
        return 0


if __name__ == "__main__":
    sys.exit(main())
