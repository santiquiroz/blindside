import importlib.util
import json
from datetime import datetime, timezone
from pathlib import Path
from xml.etree import ElementTree

import pytest

spec = importlib.util.spec_from_file_location("tak_overlay", Path(__file__).with_name("tak-overlay.py"))
overlay = importlib.util.module_from_spec(spec)
spec.loader.exec_module(overlay)

NOW = datetime(2024, 5, 1, 12, 0, 0, tzinfo=timezone.utc)
DET = "Detail".lower()


def test_argb_int_examples():
    assert overlay.argb_int("#ff0000", 1) == -65536
    assert overlay.argb_int("#ffffff", 1) == -1
    assert overlay.argb_int("#000000", 0) == 0
    assert overlay.argb_int("#ff3b30", 0.35) == 1509899056


def test_argb_int_invalid_color():
    assert overlay.argb_int("notacolor", 1) == -1
    assert overlay.argb_int("#zzz", 1) == -1
    assert overlay.argb_int("", 1) == -1


def test_feature_uid_uses_id():
    f = {"properties": {"id": "abc"}, "geometry": {"type": "Point", "coordinates": [0, 0]}}
    assert overlay.feature_uid(f, "overlay") == "overlay-abc"


def test_feature_uid_stable():
    f = {"properties": {"name": "n", "folder": "f"}, "geometry": {"type": "Point", "coordinates": [1.5, 2.5]}}
    assert overlay.feature_uid(f, "overlay") == overlay.feature_uid(f, "overlay")


def test_feature_uid_differs_position():
    a = {"properties": {"name": "same"}, "geometry": {"type": "Point", "coordinates": [0, 0]}}
    b = {"properties": {"name": "same"}, "geometry": {"type": "Point", "coordinates": [1, 1]}}
    assert overlay.feature_uid(a, "overlay") != overlay.feature_uid(b, "overlay")


def _poly(open_ring):
    return {
        "properties": {"name": "p", "stroke": "#ff0000", "fill": "#00ff00"},
        "geometry": {"type": "Polygon", "coordinates": [open_ring]},
    }


def test_polygon_event_closed_and_centroid():
    ring = [[0, 0], [0, 1], [1, 1], [1, 0]]
    ev = overlay.feature_event(_poly(ring), "u1", NOW, 3600)
    root = ElementTree.fromstring(ev)
    assert root.get("type") == "u-d-f"
    links = root.findall(DET + "/link")
    assert len(links) == 5
    assert links[0].get("point") == links[-1].get("point")
    assert root.find(DET + "/fill" + "Color") is not None
    assert root.find(DET + "/labels_on").get("value") == "true"
    pt = root.find("point")
    assert abs(float(pt.get("lat")) - 0.5) < 1e-6
    assert abs(float(pt.get("lon")) - 0.5) < 1e-6


def test_polygon_event_already_closed():
    ring = [[0, 0], [0, 1], [1, 1], [1, 0], [0, 0]]
    ev = overlay.feature_event(_poly(ring), "u1", NOW, 3600)
    root = ElementTree.fromstring(ev)
    links = root.findall(DET + "/link")
    assert links[0].get("point") == links[-1].get("point")
    pt = root.find("point")
    assert abs(float(pt.get("lat")) - 0.5) < 1e-6
    assert abs(float(pt.get("lon")) - 0.5) < 1e-6


def test_line_event():
    f = {
        "properties": {"name": "l", "stroke": "#ffffff"},
        "geometry": {"type": "LineString", "coordinates": [[2, 10], [3, 20]]},
    }
    ev = overlay.feature_event(f, "u1", NOW, 3600)
    root = ElementTree.fromstring(ev)
    assert root.get("type") == "u-d-f"
    assert root.find(DET + "/fill" + "Color") is None
    assert root.find(DET + "/labels_on").get("value") == "false"
    links = root.findall(DET + "/link")
    assert links[0].get("point") == "10.0000000,2.0000000"
    assert links[1].get("point") == "20.0000000,3.0000000"


def test_point_event():
    f = {
        "properties": {"name": "p", "marker-color": "#ff0000"},
        "geometry": {"type": "Point", "coordinates": [5, 6]},
    }
    ev = overlay.feature_event(f, "u1", NOW, 3600)
    root = ElementTree.fromstring(ev)
    assert root.get("type") == "b-m-p-s-m"
    assert root.find(DET + "/usericon") is not None
    color = root.find(DET + "/color")
    assert color is not None
    assert color.get("argb") == str(overlay.argb_int("#ff0000", 1))


def test_escaping_roundtrip():
    name = "A & B <C>"
    desc = 'dijo "hola" y \'adios\' <tag> & mas'
    f = {
        "properties": {"name": name, "description": desc},
        "geometry": {"type": "Point", "coordinates": [0, 0]},
    }
    ev = overlay.feature_event(f, "u1", NOW, 3600)
    assert "&amp;" in ev
    root = ElementTree.fromstring(ev)
    assert root.find(DET + "/contact").get("callsign") == name
    assert root.find(DET + "/remarks").text == desc


def test_unsupported_geometry_none():
    mp = {"properties": {}, "geometry": {"type": "MultiPolygon", "coordinates": []}}
    assert overlay.feature_event(mp, "u", NOW, 60) is None
    none_geom = {"properties": {}, "geometry": None}
    assert overlay.feature_event(none_geom, "u", NOW, 60) is None
    missing = {"properties": {}}
    assert overlay.feature_event(missing, "u", NOW, 60) is None


def test_delete_event():
    ev = overlay.delete_event("overlay-abc", NOW)
    root = ElementTree.fromstring(ev)
    assert root.get("type") == "t-x-d-d"
    assert root.get("uid") == "overlay-abc-delete"
    link = root.find(DET + "/link")
    assert link.get("uid") == "overlay-abc"
    assert root.find(DET + "/__forcedelete") is not None


def test_plan_cycle_deletes_only_removed():
    f1 = {"properties": {"id": "a", "name": "a"}, "geometry": {"type": "Point", "coordinates": [0, 0]}}
    f2 = {"properties": {"id": "b", "name": "b"}, "geometry": {"type": "Point", "coordinates": [1, 1]}}
    events1, current1 = overlay.plan_cycle([f1, f2], set(), "overlay", NOW, 60)
    assert current1 == {"overlay-a", "overlay-b"}
    assert len(events1) == 2
    events2, current2 = overlay.plan_cycle([f1], current1, "overlay", NOW, 60)
    assert current2 == {"overlay-a"}
    types = [ElementTree.fromstring(e).get("type") for e in events2]
    assert "t-x-d-d" in types
    deletes = [e for e in events2 if ElementTree.fromstring(e).get("type") == "t-x-d-d"]
    assert len(deletes) == 1
    assert ElementTree.fromstring(deletes[0]).find(DET + "/link").get("uid") == "overlay-b"


def test_load_features_skip_and_invalid(tmp_path):
    good = {
        "type": "FeatureCollection",
        "features": [
            {"type": "Feature", "properties": {"name": "a", "folder": "skipme"}, "geometry": {"type": "Point", "coordinates": [0, 0]}},
            {"type": "Feature", "properties": {"name": "b", "folder": "keep"}, "geometry": {"type": "Point", "coordinates": [1, 1]}},
        ],
    }
    p = tmp_path / "a.geojson"
    p.write_text(json.dumps(good), encoding="utf-8")
    feats = overlay.load_features(p, {"skipme"})
    assert len(feats) == 1
    assert feats[0]["properties"]["name"] == "b"
    bad = tmp_path / "bad.geojson"
    bad.write_text("{no json", encoding="utf-8")
    with pytest.raises(ValueError):
        overlay.load_features(bad, set())
    not_fc = tmp_path / "notfc.geojson"
    not_fc.write_text(json.dumps({"type": "Feature"}), encoding="utf-8")
    with pytest.raises(ValueError):
        overlay.load_features(not_fc, set())


def test_polygon_labels_can_be_turned_off():
    import xml.etree.ElementTree as ET
    from datetime import datetime, timezone

    now = datetime(2026, 10, 10, 21, 0, tzinfo=timezone.utc)
    ring = [[-75.49, 5.16], [-75.48, 5.16], [-75.48, 5.17], [-75.49, 5.16]]
    hidden = {"type": "Feature", "properties": {"name": "Ladera", "labels": False},
              "geometry": {"type": "Polygon", "coordinates": [ring]}}
    shown = {"type": "Feature", "properties": {"name": "Bloque"},
             "geometry": {"type": "Polygon", "coordinates": [ring]}}
    for feature, expected in ((hidden, "false"), (shown, "true")):
        root = ET.fromstring(overlay.feature_event(feature, "u1", now, 60))
        assert root.find("detail/labels_on").get("value") == expected
