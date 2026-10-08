import json
import math
import re
import sys
import zipfile
from pathlib import Path
from xml.sax.saxutils import escape

_HEX_RE = re.compile(r"^#[0-9a-fA-F]{6}$")
_ICON_HREF = "http://maps.google.com/mapfiles/kml/paddle/wht-blank.png"


def _valid_color(value, default):
    if isinstance(value, str) and _HEX_RE.match(value):
        return value.lower()
    return default


def _valid_number(value, default, minimum, maximum):
    if isinstance(value, bool):
        return default
    if isinstance(value, (int, float)):
        number = value
    elif isinstance(value, str):
        try:
            number = float(value)
        except ValueError:
            return default
    else:
        return default
    if isinstance(number, float) and not math.isfinite(number):
        return default
    if minimum is not None and number < minimum:
        return default
    if maximum is not None and number > maximum:
        return default
    return number


def _fmt_num(value):
    if isinstance(value, float) and value.is_integer():
        return str(int(value))
    return str(value)


def _is_num(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def _fmt_pos(coords):
    if not isinstance(coords, (list, tuple)) or len(coords) < 2:
        return None
    lon, lat = coords[0], coords[1]
    if not _is_num(lon) or not _is_num(lat):
        return None
    if len(coords) >= 3 and _is_num(coords[2]):
        return f"{_fmt_num(lon)},{_fmt_num(lat)},{_fmt_num(coords[2])}"
    return f"{_fmt_num(lon)},{_fmt_num(lat)}"


def _fmt_line(coords):
    if not isinstance(coords, (list, tuple)):
        return None
    pts = []
    for pos in coords:
        text = _fmt_pos(pos)
        if text is not None:
            pts.append(text)
    if not pts:
        return None
    return " ".join(pts)


def _fmt_ring(ring):
    line = _fmt_line(ring)
    if line is None:
        return None
    pts = line.split(" ")
    if pts[0] != pts[-1]:
        pts.append(pts[0])
    return " ".join(pts)


def _polygon_xml(coords):
    if not isinstance(coords, (list, tuple)) or not coords:
        return None
    outer = _fmt_ring(coords[0])
    if outer is None:
        return None
    parts = ["<Polygon><outerBoundaryIs><LinearRing><coordinates>"]
    parts.append(outer)
    parts.append("</coordinates></LinearRing></outerBoundaryIs>")
    for hole in coords[1:]:
        inner = _fmt_ring(hole)
        if inner is not None:
            parts.append("<innerBoundaryIs><LinearRing><coordinates>")
            parts.append(inner)
            parts.append("</coordinates></LinearRing></innerBoundaryIs>")
    parts.append("</Polygon>")
    return "".join(parts)


def kml_color(hex_rgb: str, opacity: float) -> str:
    alpha = int(opacity * 255 + 0.5)
    if alpha < 0:
        alpha = 0
    if alpha > 255:
        alpha = 255
    return f"{alpha:02x}{hex_rgb[5:7].lower()}{hex_rgb[3:5].lower()}{hex_rgb[1:3].lower()}"


def style_xml(props: dict, geometry_type: str) -> str:
    stroke = _valid_color(props.get("stroke"), "#ffffff")
    width = _valid_number(props.get("stroke-width"), 2, 0, None)
    stroke_op = _valid_number(props.get("stroke-opacity"), 1, 0, 1)
    fill = _valid_color(props.get("fill"), "#ffffff")
    fill_op = _valid_number(props.get("fill-opacity"), 0.25, 0, 1)
    marker = _valid_color(props.get("marker-color"), "#ffcc00")
    parts = ["<Style>"]
    parts.append(f"<LineStyle><color>{kml_color(stroke, stroke_op)}</color><width>{_fmt_num(width)}</width></LineStyle>")
    parts.append(f"<PolyStyle><color>{kml_color(fill, fill_op)}</color><outline>1</outline></PolyStyle>")
    if geometry_type in ("Point", "MultiPoint"):
        parts.append(f"<IconStyle><color>{kml_color(marker, 1)}</color><scale>1.1</scale><Icon><href>{_ICON_HREF}</href></Icon></IconStyle>")
    label_scale = "0" if props.get("labels") is False else "0.9"
    parts.append(f"<LabelStyle><scale>{label_scale}</scale></LabelStyle>")
    parts.append("</Style>")
    return "".join(parts)


def geometry_xml(geometry: dict) -> str | None:
    if not isinstance(geometry, dict):
        return None
    gtype = geometry.get("type")
    coords = geometry.get("coordinates")
    if gtype == "Point":
        pos = _fmt_pos(coords)
        if pos is None:
            return None
        return f"<Point><coordinates>{pos}</coordinates></Point>"
    if gtype == "LineString":
        line = _fmt_line(coords)
        if line is None:
            return None
        return f"<LineString><coordinates>{line}</coordinates></LineString>"
    if gtype == "Polygon":
        return _polygon_xml(coords)
    if gtype == "MultiPoint":
        if not isinstance(coords, (list, tuple)):
            return None
        items = []
        for pos in coords:
            text = _fmt_pos(pos)
            if text is not None:
                items.append(f"<Point><coordinates>{text}</coordinates></Point>")
        if not items:
            return None
        return f"<MultiGeometry>{''.join(items)}</MultiGeometry>"
    if gtype == "MultiLineString":
        if not isinstance(coords, (list, tuple)):
            return None
        items = []
        for line in coords:
            text = _fmt_line(line)
            if text is not None:
                items.append(f"<LineString><coordinates>{text}</coordinates></LineString>")
        if not items:
            return None
        return f"<MultiGeometry>{''.join(items)}</MultiGeometry>"
    if gtype == "MultiPolygon":
        if not isinstance(coords, (list, tuple)):
            return None
        items = []
        for poly in coords:
            text = _polygon_xml(poly)
            if text is not None:
                items.append(text)
        if not items:
            return None
        return f"<MultiGeometry>{''.join(items)}</MultiGeometry>"
    return None


def placemark_xml(feature: dict) -> str | None:
    props = feature.get("properties") if isinstance(feature, dict) else None
    if not isinstance(props, dict):
        props = {}
    geometry = feature.get("geometry") if isinstance(feature, dict) else None
    gml = geometry_xml(geometry)
    if gml is None:
        print("advertencia: se omite una entidad por geometría desconocida o nula", file=sys.stderr)
        return None
    gtype = geometry.get("type")
    if not isinstance(gtype, str):
        gtype = ""
    parts = ["<Placemark>"]
    name = props.get("name")
    if name is not None:
        parts.append(f"<name>{escape(str(name))}</name>")
    description = props.get("description")
    if description is not None:
        parts.append(f"<description>{escape(str(description))}</description>")
    parts.append(style_xml(props, gtype))
    parts.append(gml)
    parts.append("</Placemark>")
    return "".join(parts)


def document_xml(features: list[dict], name: str) -> str:
    folders = {}
    loose = []
    for feature in features:
        props = feature.get("properties") if isinstance(feature, dict) else None
        if not isinstance(props, dict):
            props = {}
        mark = placemark_xml(feature)
        if mark is None:
            continue
        folder = props.get("folder")
        if isinstance(folder, str) and folder:
            folders.setdefault(folder, []).append(mark)
        else:
            loose.append(mark)
    parts = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<kml xmlns="http://www.opengis.net/kml/2.2">',
        "<Document>",
        f"<name>{escape(str(name))}</name>",
    ]
    for folder_name, marks in folders.items():
        parts.append(f"<Folder><name>{escape(folder_name)}</name>{''.join(marks)}</Folder>")
    parts.extend(loose)
    parts.append("</Document></kml>")
    return "".join(parts) + "\n"


def write_output(kml: str, out: Path) -> None:
    if out.suffix.lower() == ".kmz":
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zf:
            zf.writestr("doc.kml", kml.encode("utf-8"))
    else:
        out.write_text(kml, encoding="utf-8")


def main(argv=None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    name = None
    positional = []
    i = 0
    while i < len(args):
        arg = args[i]
        if arg == "--name":
            i += 1
            if i >= len(args):
                print("error: --name necesita un valor", file=sys.stderr)
                return 2
            name = args[i]
        elif arg.startswith("--name="):
            name = arg[len("--name="):]
        elif arg in ("-h", "--help"):
            print('uso: python tak/geojson2kml.py ENTRADA.geojson SALIDA.kml|kmz [--name "Nombre del documento"]')
            return 0
        elif arg.startswith("-"):
            print(f"error: opción desconocida: {arg}", file=sys.stderr)
            return 2
        else:
            positional.append(arg)
        i += 1
    if len(positional) != 2:
        print('uso: python tak/geojson2kml.py ENTRADA.geojson SALIDA.kml|kmz [--name "Nombre del documento"]', file=sys.stderr)
        return 2
    in_path = Path(positional[0])
    out_path = Path(positional[1])
    if out_path.suffix.lower() not in (".kml", ".kmz"):
        print("error: la salida debe terminar en .kml o .kmz", file=sys.stderr)
        return 2
    try:
        text = in_path.read_text(encoding="utf-8")
    except FileNotFoundError:
        print(f"error: no se encontró el archivo: {in_path}", file=sys.stderr)
        return 1
    except OSError as exc:
        print(f"error: no se pudo leer {in_path}: {exc}", file=sys.stderr)
        return 1
    try:
        data = json.loads(text)
    except json.JSONDecodeError as exc:
        print(f"error: JSON no válido en {in_path}: {exc}", file=sys.stderr)
        return 1
    if isinstance(data, dict) and data.get("type") == "FeatureCollection":
        features = data.get("features", [])
    elif isinstance(data, dict) and data.get("type") == "Feature":
        features = [data]
    else:
        print("error: se esperaba una FeatureCollection de GeoJSON", file=sys.stderr)
        return 1
    if not isinstance(features, list):
        print("error: «features» no es una lista en la FeatureCollection", file=sys.stderr)
        return 1
    doc_name = name if name is not None else in_path.stem
    kml = document_xml(features, doc_name)
    try:
        write_output(kml, out_path)
    except OSError as exc:
        print(f"error: no se pudo escribir {out_path}: {exc}", file=sys.stderr)
        return 1
    print(f"Escrito: {out_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
