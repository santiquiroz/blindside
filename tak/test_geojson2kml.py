import json
import sys
import zipfile
from pathlib import Path
from xml.etree import ElementTree

sys.path.insert(0, str(Path(__file__).resolve().parent))

from geojson2kml import (  # noqa: E402
    document_xml,
    geometry_xml,
    kml_color,
    main,
    placemark_xml,
    style_xml,
)


def _feature(geometry, properties=None):
    return {"type": "Feature", "properties": properties or {}, "geometry": geometry}


def test_kml_color_half_opacity():
    assert kml_color("#ff0000", 0.5) == "800000ff"


def test_kml_color_full_opacity():
    assert kml_color("#00ff00", 1) == "ff00ff00"


def test_invalid_color_uses_default():
    xml = style_xml({"stroke": "rojo", "fill": "#12345", "marker-color": None}, "LineString")
    assert "<color>ffffffff</color>" in xml
    assert "<color>40ffffff</color>" in xml


def test_out_of_range_opacity_uses_default():
    xml = style_xml({"stroke-opacity": 5, "fill-opacity": -1}, "Polygon")
    assert "<color>ffffffff</color>" in xml
    assert "<color>40ffffff</color>" in xml


def test_polygon_ring_auto_closed():
    xml = geometry_xml({"type": "Polygon", "coordinates": [[[0, 0], [1, 0], [1, 1], [0, 1]]]})
    assert xml is not None
    assert "<coordinates>0,0 1,0 1,1 0,1 0,0</coordinates>" in xml


def test_folder_grouping_and_order():
    features = [
        _feature({"type": "Point", "coordinates": [0, 0]}, {"name": "en-B", "folder": "B"}),
        _feature({"type": "Point", "coordinates": [1, 1]}, {"name": "en-A", "folder": "A"}),
        _feature({"type": "Point", "coordinates": [2, 2]}, {"name": "suelta"}),
        _feature({"type": "Point", "coordinates": [3, 3]}, {"name": "otra-B", "folder": "B"}),
    ]
    xml = document_xml(features, "doc")
    assert xml.count("<Folder>") == 2
    pos_b = xml.index("<name>B</name>")
    pos_a = xml.index("<name>A</name>")
    pos_loose = xml.index("<name>suelta</name>")
    assert pos_b < pos_a < pos_loose
    folder_b = xml[pos_b:pos_a]
    assert "en-B" in folder_b and "otra-B" in folder_b
    assert xml.rindex("</Folder>") < pos_loose


def test_name_description_escaped_and_parseable():
    feature = _feature(
        {"type": "Point", "coordinates": [0, 0]},
        {"name": "A & B <C>", "description": "x < y & z > w\nsegunda línea"},
    )
    xml = document_xml([feature], "d & <doc>")
    assert "A &amp; B &lt;C&gt;" in xml
    assert "x &lt; y &amp; z &gt; w\nsegunda línea" in xml
    ElementTree.fromstring(xml.encode("utf-8"))


def test_multipolygon_to_multigeometry():
    poly = [[[0, 0], [1, 0], [1, 1], [0, 0]]]
    xml = placemark_xml(_feature({"type": "MultiPolygon", "coordinates": [poly, poly]}, {}))
    assert xml is not None
    assert "<MultiGeometry>" in xml
    assert xml.count("<Polygon>") == 2


def test_unknown_geometry_skipped(capsys):
    features = [
        _feature({"type": "Point", "coordinates": [0, 0]}, {"name": "bueno"}),
        _feature({"type": "Foo", "coordinates": []}, {"name": "raro"}),
        _feature(None, {"name": "nulo"}),
    ]
    xml = document_xml(features, "doc")
    assert xml.count("<Placemark>") == 1
    assert "bueno" in xml
    assert "raro" not in xml and "nulo" not in xml
    assert "advertencia" in capsys.readouterr().err


def test_3d_coordinates_kept():
    point = geometry_xml({"type": "Point", "coordinates": [1.5, 2.5, 100]})
    assert point is not None
    assert "<coordinates>1.5,2.5,100</coordinates>" in point
    line = geometry_xml({"type": "LineString", "coordinates": [[0, 0, 10], [1, 1, 20]]})
    assert line is not None
    assert "<coordinates>0,0,10 1,1,20</coordinates>" in line


def _write_geojson(path, features):
    path.write_text(json.dumps({"type": "FeatureCollection", "features": features}), encoding="utf-8")


def test_kmz_output_is_zip_with_doc_kml(tmp_path):
    src = tmp_path / "entrada.geojson"
    out = tmp_path / "salida.kmz"
    _write_geojson(src, [_feature({"type": "Point", "coordinates": [0, 0]}, {"name": "p"})])
    assert main([str(src), str(out)]) == 0
    assert zipfile.is_zipfile(out)
    with zipfile.ZipFile(out) as zf:
        assert zf.namelist() == ["doc.kml"]
        kml = zf.read("doc.kml").decode("utf-8")
    assert "<name>entrada</name>" in kml
    ElementTree.fromstring(kml.encode("utf-8"))


def test_kml_output_is_plain_text(tmp_path):
    src = tmp_path / "entrada.geojson"
    out = tmp_path / "salida.kml"
    _write_geojson(src, [_feature({"type": "Point", "coordinates": [0, 0]}, {"name": "p"})])
    assert main([str(src), str(out), "--name", "Mi doc"]) == 0
    assert not zipfile.is_zipfile(out)
    text = out.read_text(encoding="utf-8")
    assert text.startswith('<?xml version="1.0"')
    assert "<name>Mi doc</name>" in text
    ElementTree.fromstring(text.encode("utf-8"))
