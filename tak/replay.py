import argparse
import calendar
import csv
import html
import io
import json
import math
import os
import subprocess
import sys
from datetime import datetime, timedelta

PALETTE = ["#ff3838", "#00e5ff", "#ffd600", "#00e676", "#ff6eff", "#ff9100", "#ffffff", "#8c9eff", "#76ff03", "#ff3d00"]
CONTACT_COLOR = "#ffe600"
HOSTILE_COLOR = "#ff2d2d"
MARKER_COLOR = "#f2c94c"
OTHER_COLOR = "#9aa7b4"


def parse_local(s: str, offset_h: float) -> datetime:
    return datetime.strptime(s, "%Y-%m-%d %H:%M") - timedelta(hours=offset_h)


def query_sql(since_utc: str, until_utc: str) -> str:
    return f"select p.timestamp, p.uid, p.device_uid, coalesce(e.callsign, c.sender_callsign) as callsign, c.type, p.latitude, p.longitude, p.course, p.speed, p.battery from points p join cot c on c.id = p.cot_id left join euds e on e.uid = p.uid where p.timestamp >= '{since_utc}' and p.timestamp < '{until_utc}' and not (p.latitude = 0 and p.longitude = 0) order by p.timestamp"


def fetch_csv_via_wsl(sql: str) -> str:
    cmd = ["wsl.exe", "-d", "Ubuntu-24.04", "-u", "postgres", "--", "psql", "-d", "ots", "-c", "\\copy (" + sql + ") to stdout with csv header"]
    env = dict(os.environ)
    env["MSYS_NO_PATHCONV"] = "1"
    done = subprocess.run(cmd, env=env, capture_output=True, text=True, check=True)
    return done.stdout


def _num(v):
    v = (v or "").strip()
    if not v:
        return None
    try:
        return float(v)
    except ValueError:
        return None


def load_rows(csv_text: str) -> list[dict]:
    rows = []
    for rec in csv.DictReader(io.StringIO(csv_text or "")):
        try:
            lat = float((rec.get("latitude") or "").strip())
            lon = float((rec.get("longitude") or "").strip())
        except ValueError:
            continue
        if not (math.isfinite(lat) and math.isfinite(lon)):
            continue
        if lat == 0 and lon == 0:
            continue
        if abs(lat) > 90 or abs(lon) > 180:
            continue
        ts = (rec.get("timestamp") or "").strip()
        t = None
        for fmt in ("%Y-%m-%d %H:%M:%S.%f", "%Y-%m-%d %H:%M:%S"):
            try:
                t = datetime.strptime(ts, fmt)
                break
            except ValueError:
                continue
        if t is None:
            continue
        uid = (rec.get("uid") or "").strip()
        if not uid:
            continue
        rows.append({
            "timestamp": t,
            "uid": uid,
            "device_uid": (rec.get("device_uid") or "").strip(),
            "callsign": (rec.get("callsign") or "").strip(),
            "type": (rec.get("type") or "").strip(),
            "latitude": lat,
            "longitude": lon,
            "course": _num(rec.get("course")),
            "speed": _num(rec.get("speed")),
            "battery": _num(rec.get("battery")),
        })
    return rows


def kind_of(cot_type: str) -> str:
    c = cot_type or ""
    if c.startswith("a-f"):
        return "player"
    if c.startswith("a-u"):
        return "contact"
    if c.startswith("a-h"):
        return "hostile"
    if c.startswith("b-m-p"):
        return "marker"
    return "other"


def haversine_m(a: tuple, b: tuple) -> float:
    r = 6371000.0
    la1 = math.radians(a[0])
    lo1 = math.radians(a[1])
    la2 = math.radians(b[0])
    lo2 = math.radians(b[1])
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def track_stats(samples: list[dict]) -> dict:
    s = sorted(samples, key=lambda d: d["t"])
    if not s:
        return {"start": None, "end": None, "duration_s": 0, "distance_m": 0.0, "max_speed_kmh": 0.0, "battery": None}
    start = s[0]["t"]
    end = s[-1]["t"]
    dist = 0.0
    vmax = 0.0
    for p, q in zip(s, s[1:]):
        dt = (q["t"] - p["t"]).total_seconds()
        d = haversine_m((p["lat"], p["lon"]), (q["lat"], q["lon"]))
        if dt <= 0:
            continue
        v = d / dt
        if v > 50:
            continue
        dist += d
        if dt >= 1:
            kmh = v * 3.6
            if kmh > vmax:
                vmax = kmh
    bat = None
    for d in s:
        if d.get("battery") is not None:
            bat = d["battery"]
    return {"start": start, "end": end, "duration_s": int((end - start).total_seconds()), "distance_m": dist, "max_speed_kmh": vmax, "battery": bat}


def build_tracks(rows) -> dict[str, dict]:
    tracks = {}
    for r in rows or []:
        uid = (r.get("uid") or "").strip()
        if not uid:
            continue
        tr = tracks.get(uid)
        if tr is None:
            tr = {"uid": uid, "kind": kind_of(r.get("type") or ""), "callsign": "", "samples": []}
            tracks[uid] = tr
        cs = (r.get("callsign") or "").strip()
        if cs and not tr["callsign"]:
            tr["callsign"] = cs
        tr["samples"].append({"t": r["timestamp"], "lat": r["latitude"], "lon": r["longitude"], "course": r.get("course"), "speed": r.get("speed"), "battery": r.get("battery")})
    for uid, tr in tracks.items():
        tr["samples"].sort(key=lambda d: d["t"])
        cs = tr["callsign"]
        k = tr["kind"]
        if k == "player":
            tr["label"] = cs or uid
        elif k == "contact":
            tr["label"] = "Contacto"
        elif k == "hostile":
            tr["label"] = cs or "Hostil"
        elif k == "marker":
            tr["label"] = cs or uid
        else:
            tr["label"] = cs or uid
    return tracks


def _fmt_dur(sec):
    sec = int(sec or 0)
    return str(sec // 3600) + ":" + str((sec % 3600) // 60).zfill(2)


def _fmt_bat(b):
    if b is None:
        return "—"
    return ("%g" % b) + " %"


_HTML = '''<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>__TITLE__</title>
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.css">
<style>
:root { color-scheme: dark; }
* { box-sizing: border-box; }
body { margin: 0; background: #0b0f14; color: #e6edf3; font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
header { padding: 12px 16px 2px; }
header h1 { margin: 0; font-size: 1.25rem; color: #f2c94c; }
#controls { display: flex; gap: 10px; align-items: center; padding: 10px 16px; flex-wrap: wrap; }
#play { background: #f2c94c; color: #0b0f14; border: 0; border-radius: 6px; padding: 8px 14px; font-weight: 700; cursor: pointer; font-size: 1rem; }
#play:disabled { opacity: 0.4; cursor: default; }
#speed { background: #161d26; color: #e6edf3; border: 1px solid #30363d; border-radius: 6px; padding: 7px; font-size: 1rem; }
#slider { flex: 1 1 200px; accent-color: #f2c94c; }
#clock { font-variant-numeric: tabular-nums; font-size: 1.1rem; min-width: 5.5em; text-align: right; }
#layout { display: flex; gap: 10px; padding: 0 16px 16px; align-items: flex-start; }
#map { flex: 1 1 auto; height: calc(100vh - 230px); min-height: 320px; border-radius: 8px; background: #000; }
#panel { flex: 0 0 360px; max-height: calc(100vh - 230px); min-height: 200px; overflow: auto; background: #11161d; border: 1px solid #21262d; border-radius: 8px; }
#totals { padding: 10px 10px 4px; font-size: 0.9rem; }
table { border-collapse: collapse; width: 100%; font-size: 0.85rem; }
th, td { padding: 6px 8px; text-align: left; border-bottom: 1px solid #21262d; }
th { position: sticky; top: 0; background: #161d26; color: #f2c94c; }
tbody tr { cursor: pointer; }
tbody tr:hover { background: #1a2230; }
#nodata { margin: 12px 16px 0; padding: 16px; border: 1px solid #f2c94c; border-radius: 8px; font-size: 1.1rem; }
.leaflet-container { background: #000; font-family: inherit; }
@media (max-width: 700px) {
  #layout { flex-direction: column; }
  #map { width: 100%; height: 55vh; flex: none; }
  #panel { flex: none; width: 100%; max-height: none; }
}
</style>
</head>
<body>
<header><h1>__TITLE__</h1></header>
__NODATA__
<div id="controls">
<button id="play" type="button">▶ Reproducir</button>
<select id="speed" aria-label="Velocidad">
<option value="1">1x</option>
<option value="10">10x</option>
<option value="30" selected>30x</option>
<option value="60">60x</option>
<option value="120">120x</option>
</select>
<input id="slider" type="range" min="0" max="100" value="0" step="1" aria-label="Tiempo">
<span id="clock">--:--:--</span>
</div>
<div id="layout">
<div id="map" role="application" aria-label="Mapa"></div>
<div id="panel"><div id="totals"></div><table id="ptable"><thead><tr><th>Jugador</th><th>Dist. km</th><th>Vel. km/h</th><th>Duración</th><th>Batería</th></tr></thead><tbody></tbody></table></div>
</div>
<script type="application/json" id="data">__JSON__</script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.js"></script>
<script>
(function () {
  "use strict";
  var D = JSON.parse(document.getElementById("data").textContent || "{}");
  var tracks = D.tracks || [];
  var off = (D.offset_h || 0) * 3600;
  var i, j;

  var sat = L.tileLayer("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}", {
    maxNativeZoom: 18, maxZoom: 20, attribution: "Esri, Maxar, Earthstar Geographics"
  });
  var osm = L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
    maxZoom: 19, attribution: "© OpenStreetMap"
  });
  var map = L.map("map", { layers: [sat] });
  map.setView([4.7, -74.1], 12);

  var bounds = [];
  for (i = 0; i < tracks.length; i++) {
    var smp = tracks[i].samples || [];
    for (j = 0; j < smp.length; j++) bounds.push([smp[j][1], smp[j][2]]);
  }
  if (bounds.length) map.fitBounds(bounds);

  var overlays = {};
  if (D.overlay) {
    var campo = L.geoJSON(D.overlay, {
      style: function (f) {
        var p = (f && f.properties) || {};
        var fo = (p["fill-opacity"] === undefined || p["fill-opacity"] === null) ? 0.2 : p["fill-opacity"];
        return { color: p.stroke || "#f2c94c", weight: p["stroke-width"] || 3, fillColor: p.fill || "#f2c94c", fillOpacity: fo };
      },
      pointToLayer: function (f, latlng) {
        var p = (f && f.properties) || {};
        var c = p["marker-color"] || "#f2c94c";
        var m = L.circleMarker(latlng, { radius: 6, color: c, fillColor: c, fillOpacity: 0.9, weight: 2 });
        if (p.name) {
          var d = document.createElement("span");
          d.textContent = p.name;
          m.bindTooltip(d);
        }
        return m;
      },
      onEachFeature: function (f, layer) {
        var p = (f && f.properties) || {};
        var g = f && f.geometry && f.geometry.type;
        if (p.name && g !== "Point" && g !== "MultiPoint") {
          var d = document.createElement("span");
          d.textContent = p.name;
          layer.bindTooltip(d);
        }
      }
    });
    campo.addTo(map);
    overlays.Campo = campo;
  }
  L.control.layers({ "Satélite": sat, "Mapa": osm }, overlays).addTo(map);

  var tot = D.totals || { players: 0, contacts: 0, dur: "0:00" };
  var totalsEl = document.getElementById("totals");
  totalsEl.textContent = "Jugadores: " + tot.players + " · Contactos radar: " + tot.contacts + " · Duración: " + tot.dur;

  var tbody = document.querySelector("#ptable tbody");
  (D.players || []).forEach(function (p) {
    var tr = document.createElement("tr");
    tr.setAttribute("tabindex", "0");
    var cells = [p.label, p.dist_km, p.vmax, p.dur, p.bat];
    for (var k = 0; k < cells.length; k++) {
      var td = document.createElement("td");
      td.textContent = cells[k];
      tr.appendChild(td);
    }
    tr.addEventListener("click", function () { focusPlayer(p.uid); });
    tr.addEventListener("keydown", function (e) { if (e.key === "Enter") focusPlayer(p.uid); });
    tbody.appendChild(tr);
  });

  var slider = document.getElementById("slider");
  var clock = document.getElementById("clock");
  var playBtn = document.getElementById("play");
  var speedSel = document.getElementById("speed");

  var start = Infinity, end = -Infinity;
  for (i = 0; i < tracks.length; i++) {
    var a0 = tracks[i].samples || [];
    for (j = 0; j < a0.length; j++) {
      if (a0[j][0] < start) start = a0[j][0];
      if (a0[j][0] > end) end = a0[j][0];
    }
  }
  if (start === Infinity) {
    slider.disabled = true;
    playBtn.disabled = true;
    return;
  }
  slider.min = String(Math.floor(start));
  slider.max = String(Math.ceil(end));
  slider.step = "1";
  slider.value = String(Math.floor(start));

  function tip(text) {
    var d = document.createElement("span");
    d.textContent = text;
    return d;
  }
  for (i = 0; i < tracks.length; i++) {
    var t = tracks[i];
    if (t.kind === "player") {
      t._mk = L.circleMarker([0, 0], { radius: 7, color: "#0b0f14", weight: 2, fillColor: t.color, fillOpacity: 1 });
      t._mk.bindTooltip(tip(t.label));
      t._trail = L.polyline([], { color: t.color, weight: 3 });
      t._trail.addTo(map);
    } else if (t.kind === "contact") {
      t._mk = L.circleMarker([0, 0], { radius: 3, color: "#ffe600", weight: 1, fillColor: "#ffe600", fillOpacity: 1 });
      t._mk.bindTooltip(tip(t.label));
    } else if (t.kind === "hostile") {
      t._mk = L.circleMarker([0, 0], { radius: 4, color: "#ff2d2d", weight: 1, fillColor: "#ff2d2d", fillOpacity: 1 });
      t._mk.bindTooltip(tip(t.label));
    } else {
      t._mk = L.circleMarker([0, 0], { radius: 5, color: "#0b0f14", weight: 2, fillColor: t.color, fillOpacity: 1 });
      t._mk.bindTooltip(tip(t.label));
    }
  }

  var T = start, playing = false, speed = 30, timer = null;

  function latestAt(a, t) {
    var lo = 0, hi = a.length - 1, ans = -1;
    while (lo <= hi) {
      var m = (lo + hi) >> 1;
      if (a[m][0] <= t) { ans = m; lo = m + 1; } else { hi = m - 1; }
    }
    return ans;
  }

  function pad(n) { return (n < 10 ? "0" : "") + n; }

  function fmtClock(t) {
    var d = new Date((t + off) * 1000);
    return pad(d.getUTCHours()) + ":" + pad(d.getUTCMinutes()) + ":" + pad(d.getUTCSeconds());
  }

  function render() {
    clock.textContent = fmtClock(T);
    if (document.activeElement !== slider) slider.value = String(Math.floor(T));
    for (var q = 0; q < tracks.length; q++) {
      var t = tracks[q];
      var a = t.samples || [];
      if (!a.length) continue;
      var k = latestAt(a, T);
      if (t.kind === "player") {
        if (k < 0 || T - a[k][0] > 120) {
          if (map.hasLayer(t._mk)) map.removeLayer(t._mk);
          t._trail.setLatLngs([]);
          continue;
        }
        t._mk.setLatLng([a[k][1], a[k][2]]);
        if (!map.hasLayer(t._mk)) t._mk.addTo(map);
        var pts = [];
        var m = k;
        while (m >= 0 && a[k][0] - a[m][0] <= 300) { pts.push([a[m][1], a[m][2]]); m--; }
        pts.reverse();
        t._trail.setLatLngs(pts);
      } else if (t.kind === "contact" || t.kind === "hostile") {
        if (k < 0 || T - a[k][0] > 15) {
          if (map.hasLayer(t._mk)) map.removeLayer(t._mk);
          continue;
        }
        t._mk.setLatLng([a[k][1], a[k][2]]);
        if (!map.hasLayer(t._mk)) t._mk.addTo(map);
      } else {
        if (k < 0) {
          if (map.hasLayer(t._mk)) map.removeLayer(t._mk);
          continue;
        }
        t._mk.setLatLng([a[k][1], a[k][2]]);
        if (!map.hasLayer(t._mk)) t._mk.addTo(map);
      }
    }
  }

  function setPlaying(v) {
    playing = v;
    playBtn.textContent = playing ? "⏸ Pausar" : "▶ Reproducir";
    if (timer) { clearInterval(timer); timer = null; }
    if (playing) {
      if (T >= end) T = start;
      var last = Date.now();
      timer = setInterval(function () {
        var now = Date.now();
        T += (now - last) / 1000 * speed;
        last = now;
        if (T >= end) { T = end; setPlaying(false); }
        render();
      }, 100);
    }
  }

  function focusPlayer(uid) {
    for (var q = 0; q < tracks.length; q++) {
      if (tracks[q].uid === uid) {
        var a = tracks[q].samples || [];
        var k = latestAt(a, T);
        if (k >= 0) map.panTo([a[k][1], a[k][2]]);
        return;
      }
    }
  }

  playBtn.addEventListener("click", function () { setPlaying(!playing); });
  speedSel.addEventListener("change", function () { speed = parseFloat(speedSel.value) || 30; });
  slider.addEventListener("input", function () { T = parseFloat(slider.value); render(); });
  document.addEventListener("keydown", function (e) {
    if (e.code === "Space") {
      var tag = (document.activeElement && document.activeElement.tagName) || "";
      if (tag !== "INPUT" && tag !== "SELECT" && tag !== "TEXTAREA" && tag !== "BUTTON") {
        e.preventDefault();
        setPlaying(!playing);
      }
    }
  });

  render();
})();
</script>
</body>
</html>
'''


def render_html(tracks, stats, title, offset_h, overlay: dict | None) -> str:
    items = list((tracks or {}).values())
    players = [tr for tr in items if tr.get("kind") == "player"]
    players.sort(key=lambda tr: tr["samples"][0]["t"] if tr.get("samples") else datetime.max)
    pcolor = {}
    for i, tr in enumerate(players):
        pcolor[tr["uid"]] = PALETTE[i % len(PALETTE)]
    jt = []
    for tr in items:
        k = tr.get("kind")
        if k == "player":
            c = pcolor.get(tr.get("uid"), PALETTE[0])
        elif k == "contact":
            c = CONTACT_COLOR
        elif k == "hostile":
            c = HOSTILE_COLOR
        elif k == "marker":
            c = MARKER_COLOR
        else:
            c = OTHER_COLOR
        sm = []
        for s in tr.get("samples", []):
            t = s["t"]
            sm.append([calendar.timegm(t.timetuple()) + t.microsecond / 1000000.0, s["lat"], s["lon"]])
        jt.append({"uid": tr.get("uid"), "kind": k, "label": tr.get("label"), "color": c, "samples": sm})
    jp = []
    for tr in players:
        st = track_stats(tr.get("samples", []))
        jp.append({
            "uid": tr.get("uid"),
            "label": tr.get("label"),
            "color": pcolor.get(tr.get("uid"), PALETTE[0]),
            "dist_km": "%.2f" % (st["distance_m"] / 1000.0),
            "vmax": "%.1f" % st["max_speed_kmh"],
            "dur": _fmt_dur(st["duration_s"]),
            "bat": _fmt_bat(st["battery"]),
        })
    n_contacts = len([tr for tr in items if tr.get("kind") == "contact"])
    all_t = [s["t"] for tr in items for s in tr.get("samples", [])]
    if all_t:
        dur_s = int((max(all_t) - min(all_t)).total_seconds())
        nodata = ""
    else:
        dur_s = (stats or {}).get("duration_s", 0) or 0
        nodata = '<div id="nodata">Sin datos en ese rango</div>'
    payload = {
        "title": title,
        "offset_h": offset_h,
        "tracks": jt,
        "players": jp,
        "totals": {"players": len(players), "contacts": n_contacts, "dur": _fmt_dur(dur_s)},
        "overlay": overlay,
    }
    js = json.dumps(payload, ensure_ascii=False).replace("</", "<\\/")
    return _HTML.replace("__TITLE__", html.escape(title, quote=True)).replace("__NODATA__", nodata).replace("__JSON__", js)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="replay.py", description="Genera una página de replay de una partida de airsoft.")
    ap.add_argument("--since", required=True, help="Inicio en hora local 'YYYY-MM-DD HH:MM'")
    ap.add_argument("--until", required=True, help="Fin en hora local 'YYYY-MM-DD HH:MM'")
    ap.add_argument("--utc-offset", type=float, default=-5, help="Desfase horario en horas (por defecto -5)")
    ap.add_argument("--csv", default=None, help="CSV ya descargado con el mismo formato de la consulta")
    ap.add_argument("--overlay", default=None, help="Archivo GeoJSON del campo")
    ap.add_argument("--title", default="Replay de la partida", help="Título de la página")
    ap.add_argument("--out", default="replay.html", help="Archivo HTML de salida")
    ns = ap.parse_args(argv)
    try:
        since_dt = parse_local(ns.since, ns.utc_offset)
    except (ValueError, TypeError):
        print("Error: --since inválido, usa el formato 'YYYY-MM-DD HH:MM'.", file=sys.stderr)
        return 2
    try:
        until_dt = parse_local(ns.until, ns.utc_offset)
    except (ValueError, TypeError):
        print("Error: --until inválido, usa el formato 'YYYY-MM-DD HH:MM'.", file=sys.stderr)
        return 2
    if until_dt <= since_dt:
        print("Error: --until debe ser posterior a --since.", file=sys.stderr)
        return 2
    sql = query_sql(since_dt.strftime("%Y-%m-%d %H:%M:%S"), until_dt.strftime("%Y-%m-%d %H:%M:%S"))
    if ns.csv:
        try:
            with open(ns.csv, "r", encoding="utf-8-sig") as f:
                csv_text = f.read()
        except OSError:
            print("Error: no se pudo leer el archivo --csv.", file=sys.stderr)
            return 2
    else:
        try:
            csv_text = fetch_csv_via_wsl(sql)
        except (subprocess.CalledProcessError, OSError):
            print("Error: no se pudo consultar la base de datos vía WSL.", file=sys.stderr)
            return 1
    rows = load_rows(csv_text)
    tracks = build_tracks(rows)
    all_t = [s["t"] for tr in tracks.values() for s in tr["samples"]]
    players = [u for u, tr in tracks.items() if tr["kind"] == "player"]
    contacts = [u for u, tr in tracks.items() if tr["kind"] == "contact"]
    if all_t:
        g0 = min(all_t)
        g1 = max(all_t)
    else:
        g0 = None
        g1 = None
    stats = {
        "players": len(players),
        "contacts": len(contacts),
        "start": g0,
        "end": g1,
        "duration_s": int((g1 - g0).total_seconds()) if g0 and g1 else 0,
        "per_player": {u: track_stats(tracks[u]["samples"]) for u in players},
    }
    overlay = None
    if ns.overlay:
        try:
            with open(ns.overlay, "r", encoding="utf-8-sig") as f:
                overlay = json.load(f)
        except (OSError, ValueError):
            print("Error: no se pudo leer el archivo --overlay (GeoJSON inválido).", file=sys.stderr)
            return 2
    page = render_html(tracks, stats, ns.title, ns.utc_offset, overlay)
    try:
        with open(ns.out, "w", encoding="utf-8") as f:
            f.write(page)
    except OSError:
        print("Error: no se pudo escribir el archivo de salida.", file=sys.stderr)
        return 1
    print("Generado: " + ns.out + " (" + str(stats["players"]) + " jugadores, " + str(stats["contacts"]) + " contactos radar, " + str(len(rows)) + " muestras)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
