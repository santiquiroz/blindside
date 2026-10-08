import importlib.util, io, json, struct, zipfile, zlib
from pathlib import Path
spec = importlib.util.spec_from_file_location("terrain_core", Path(__file__).with_name("terrain_core.py"))
tc = importlib.util.module_from_spec(spec); spec.loader.exec_module(tc)


def test_heights_and_names():
    assert tc.height_from_text("alto (sombra más larga)") == 15.0
    assert tc.height_from_text("Medio") == 8.0
    assert tc.height_from_text("bajo (fundaciones)") == 3.0
    assert tc.height_from_text("-") == 3.0
    assert tc.dted_names(5.16, -75.49) == ("w076", "n05")


def test_exposure_grid_covers_bbox_and_doc_validates():
    g = tc.exposure_grid(5.174, 5.148, -75.505, -75.479, 10)
    assert g["south"] <= 5.148 and g["east"] >= -75.479
    counts = [[0] * g["cols"] for _ in range(g["rows"])]
    assert tc.exposure_doc(g, counts)["count"] is counts
    import pytest
    with pytest.raises(ValueError):
        tc.exposure_doc(g, counts[:-1])


def test_png_is_valid_rgba():
    png = tc.mask_png([[True, False], [False, True]], "#ff3b30", 0.35)
    assert png[:8] == b"\x89PNG\r\n\x1a\n"
    ihdr = png[8:33]
    w, h, depth, ctype = struct.unpack(">IIBB", ihdr[8:18])
    assert (w, h, depth, ctype) == (2, 2, 8, 6)
    idat = png[png.index(b"IDAT") + 4:]
    raw = zlib.decompress(idat[:struct.unpack(">I", png[png.index(b"IDAT") - 4:png.index(b"IDAT")])[0]])
    assert raw[0] == 0 and raw[1:5] == bytes([0xff, 0x3b, 0x30, 89]) and raw[5:9] == b"\x00\x00\x00\x00"


def test_kmz_contains_doc_and_files(tmp_path):
    kml = tc.ground_overlay_kml("Visible <torre>", "a & b", "files/v.png", 5.165, 5.157, -75.488, -75.496)
    assert "&lt;torre&gt;" in kml and "a &amp; b" in kml and "<north>5.165</north>" in kml
    p = tmp_path / "v.kmz"
    tc.write_kmz(p, kml, {"v.png": b"x"})
    z = zipfile.ZipFile(p)
    assert sorted(z.namelist()) == ["doc.kml", "files/v.png"]


def _obs(**kw):
    base = {"name": "Torre sur", "slug": "torre-sur", "lat": 5.1612, "lon": -75.4921, "height_m": 16.7}
    base.update(kw)
    return base


def test_observer_problem_valid():
    assert tc.observer_problem(0, _obs()) is None


def test_observer_problem_bad_slug():
    msg = tc.observer_problem(2, _obs(slug="a/b"))
    assert msg is not None and "2" in msg and "a/b" in msg


def test_observer_problem_missing_height():
    obs = _obs()
    del obs["height_m"]
    msg = tc.observer_problem(1, obs)
    assert msg is not None and "height_m" in msg


def test_observer_problem_bool_lat():
    msg = tc.observer_problem(0, _obs(lat=True))
    assert msg is not None and "lat" in msg


def test_ground_overlay_escapes_href():
    kml = tc.ground_overlay_kml("n", "d", "files/a&b.png", 5.165, 5.157, -75.488, -75.496)
    assert "files/a&amp;b.png" in kml


def test_buildings_geojson_faint_tappable_cards():
    fc = {"type": "FeatureCollection", "features": [{"type": "Feature", "geometry": {"type": "Point", "coordinates": [-75.4934, 5.1594]},
          "properties": {"name": "12 Torre sur", "num": 12, "grid": "E6", "roof": "parcial", "height": "alto (sombra más larga)",
                         "cover": "concreto", "note": "La más alta."}}]}
    out = tc.buildings_geojson(fc, {12: 9.0}, {12: 34.2})
    f = out["features"][0]
    p = f["properties"]
    assert f["geometry"]["type"] == "Polygon" and len(f["geometry"]["coordinates"][0]) == 17
    assert p["labels"] is False and p["fill-opacity"] == 0 and p["stroke-opacity"] == 0.15
    assert p["description"] == "12 Torre sur · E6 · Techo parcial · Alto (≈15 m) · Cobertura: concreto · Ve el 34 % del campo · La más alta."
    assert p["height_m"] == 16.7
