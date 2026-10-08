import json
import sys
from datetime import datetime
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from tak.replay import build_tracks, haversine_m, kind_of, load_rows, main, parse_local, render_html, track_stats

FIXTURE = (
    "timestamp,uid,device_uid,callsign,type,latitude,longitude,course,speed,battery\n"
    "2026-10-10 19:00:00,p1,d1,Falcon,a-f-G-E,4.7000,-74.1000,90,2.5,95\n"
    "2026-10-10 19:01:00,p1,d1,Falcon,a-f-G-E,4.7005,-74.1005,95,,94\n"
    "2026-10-10 19:00:30,p2,d2,</script><b>x,a-f-G-E,4.7100,-74.1100,,,80\n"
    "2026-10-10 19:02:00,p2,d2,</script><b>x,a-f-G-E,4.7105,-74.1105,180,3.0,\n"
    "2026-10-10 19:00:10,c1,dc,,a-u-G,4.7200,-74.1200,,,\n"
    "2026-10-10 19:00:20,c1,dc,,a-u-G,4.7201,-74.1201,,,\n"
    "2026-10-10 19:00:05,m1,dm,Base,b-m-p-marker,4.7300,-74.1300,,,\n"
    "2026-10-10 19:00:00,ghost,dg,Fantasma,a-f-G-E,0,0,,,\n"
)

EMPTY_STATS = {"players": 0, "contacts": 0, "start": None, "end": None, "duration_s": 0, "per_player": {}}


def stats_for(tracks):
    all_t = [s["t"] for tr in tracks.values() for s in tr["samples"]]
    players = [u for u, tr in tracks.items() if tr["kind"] == "player"]
    return {
        "players": len(players),
        "contacts": len([u for u, tr in tracks.items() if tr["kind"] == "contact"]),
        "start": min(all_t),
        "end": max(all_t),
        "duration_s": int((max(all_t) - min(all_t)).total_seconds()),
        "per_player": {u: track_stats(tracks[u]["samples"]) for u in players},
    }


def test_parse_local_convierte_a_utc():
    assert parse_local("2026-10-10 14:00", -5) == datetime(2026, 10, 10, 19, 0)


def test_fecha_malformada_sale_2(capsys):
    assert main(["--since", "no-es-fecha", "--until", "2026-10-10 15:00"]) == 2
    assert "Error" in capsys.readouterr().err
    assert main(["--since", "2026/10/10 14:00", "--until", "2026-10-10 15:00"]) == 2
    with pytest.raises(ValueError):
        parse_local("2026-10-10", -5)


def test_until_anterior_a_since_sale_2(capsys):
    assert main(["--since", "2026-10-10 15:00", "--until", "2026-10-10 14:00"]) == 2
    assert "Error" in capsys.readouterr().err
    assert main(["--since", "2026-10-10 14:00", "--until", "2026-10-10 14:00"]) == 2


@pytest.mark.parametrize("cot,esperado", [
    ("a-f-G-E", "player"),
    ("a-f", "player"),
    ("a-u-G", "contact"),
    ("a-h-G", "hostile"),
    ("b-m-p-marker", "marker"),
    ("a-x", "other"),
    ("", "other"),
])
def test_kind_of(cot, esperado):
    assert kind_of(cot) == esperado


def test_salto_glitch_se_ignora():
    a = {"t": datetime(2026, 10, 10, 19, 0, 0), "lat": 4.7, "lon": -74.1, "battery": 90.0}
    b = {"t": datetime(2026, 10, 10, 19, 0, 1), "lat": 4.709, "lon": -74.1, "battery": 89.0}
    st = track_stats([a, b])
    assert st["distance_m"] == 0.0
    assert st["max_speed_kmh"] == 0.0
    c = {"t": datetime(2026, 10, 10, 19, 0, 10), "lat": 4.7 + 10 / 111195, "lon": -74.1, "battery": 88.0}
    st2 = track_stats([a, c])
    assert st2["distance_m"] == pytest.approx(10.0, abs=0.5)
    assert st2["max_speed_kmh"] == pytest.approx(3.6, abs=0.2)


def test_haversine_un_grado_latitud():
    assert abs(haversine_m((0.0, 0.0), (1.0, 0.0)) - 111195) < 50


def test_filas_cero_cero_se_descartan():
    rows = load_rows(FIXTURE)
    assert rows
    assert all(not (r["latitude"] == 0 and r["longitude"] == 0) for r in rows)
    assert "ghost" not in {r["uid"] for r in rows}


def test_campos_vacios_son_none():
    rows = {(r["uid"], r["timestamp"]): r for r in load_rows(FIXTURE)}
    r = rows[("p1", datetime(2026, 10, 10, 19, 1, 0))]
    assert r["speed"] is None
    assert r["battery"] == 94.0
    r2 = rows[("p2", datetime(2026, 10, 10, 19, 0, 30))]
    assert r2["course"] is None
    assert r2["speed"] is None
    assert r2["battery"] == 80.0


def test_tracks_por_uid_con_tipos():
    tracks = build_tracks(load_rows(FIXTURE))
    assert set(tracks) == {"p1", "p2", "c1", "m1"}
    assert tracks["p1"]["kind"] == "player"
    assert tracks["p2"]["kind"] == "player"
    assert tracks["c1"]["kind"] == "contact"
    assert tracks["m1"]["kind"] == "marker"
    assert tracks["p1"]["label"] == "Falcon"
    assert tracks["c1"]["label"] == "Contacto"
    assert tracks["m1"]["label"] == "Base"


def test_render_incrusta_datos_y_escapa_callsign():
    tracks = build_tracks(load_rows(FIXTURE))
    page = render_html(tracks, stats_for(tracks), "Prueba", -5, None)
    assert '<script type="application/json" id="data">' in page
    assert "</script><b>x" not in page
    assert "<\\/script><b>x" in page
    assert "innerHTML" not in page
    assert "https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.js" in page
    assert "https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.css" in page
    assert 'id="play"' in page and 'id="speed"' in page and 'id="slider"' in page and 'id="clock"' in page
    assert "Sin datos en ese rango" not in page


def test_render_sin_muestras():
    page = render_html({}, EMPTY_STATS, "Prueba", -5, None)
    assert "Sin datos en ese rango" in page


def test_main_con_csv_escribe_archivo(tmp_path, capsys):
    csvf = tmp_path / "in.csv"
    csvf.write_text(FIXTURE, encoding="utf-8")
    out = tmp_path / "replay.html"
    rc = main(["--csv", str(csvf), "--since", "2026-10-10 14:00", "--until", "2026-10-10 15:00", "--out", str(out)])
    assert rc == 0
    text = out.read_text(encoding="utf-8")
    assert "Falcon" in text
    assert "</script><b>x" not in text
    assert str(out) in capsys.readouterr().out


def test_main_con_overlay(tmp_path):
    csvf = tmp_path / "in.csv"
    csvf.write_text(FIXTURE, encoding="utf-8")
    ov = tmp_path / "campo.geojson"
    ov.write_text(json.dumps({"type": "FeatureCollection", "features": [
        {"type": "Feature", "properties": {"name": "Zona"}, "geometry": {"type": "Point", "coordinates": [-74.1, 4.7]}}
    ]}), encoding="utf-8")
    out = tmp_path / "replay.html"
    assert main(["--csv", str(csvf), "--since", "2026-10-10 14:00", "--until", "2026-10-10 15:00",
                 "--overlay", str(ov), "--out", str(out)]) == 0
    text = out.read_text(encoding="utf-8")
    assert "Campo" in text
    assert "Zona" in text
