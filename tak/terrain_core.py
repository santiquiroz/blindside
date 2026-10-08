"""Pure terrain helpers (no GDAL): heights, grids, PNG, KMZ, building cards."""

import math
import re
import struct
import unicodedata
import zipfile
import zlib

HEIGHTS_M = {"bajo": 3.0, "medio": 8.0, "alto": 15.0}
EYE_M = 1.7
ATTRIBUTION = "Elevación: Copernicus GLO-30 © DLR e.V. 2010-2014 y © Airbus Defence and Space GmbH 2014-2018, provisto por la ESA (Copernicus)."

_LAT_M = 110574.0
_LON_M = 111320.0
_SLUG_RE = re.compile(r"^[a-z0-9][a-z0-9-]*$")


def observer_problem(index, obs):
    if not isinstance(obs, dict):
        return f"observador {index}: no es un objeto"
    for key in ("name", "slug", "lat", "lon", "height_m"):
        if key not in obs:
            return f'observador {index}: falta "{key}"'
    slug = obs["slug"]
    if not isinstance(slug, str) or not _SLUG_RE.match(slug):
        return f'observador {index}: slug inválido "{slug}" (solo minúsculas, números y guiones)'
    for key in ("lat", "lon", "height_m"):
        val = obs[key]
        if isinstance(val, bool) or not isinstance(val, (int, float)):
            return f"observador {index}: {key} debe ser un número"
    return None


def _first_word(text):
    words = (text or "").split()
    return words[0] if words else ""


def _plain(text):
    flat = unicodedata.normalize("NFD", text)
    return "".join(c for c in flat if unicodedata.category(c) != "Mn")


def height_from_text(text):
    return HEIGHTS_M.get(_plain(_first_word(text)).lower(), 3.0)


def dted_names(lat, lon):
    la = math.floor(lat)
    lo = math.floor(lon)
    lon_name = ("w" if lo < 0 else "e") + f"{abs(lo):03d}"
    lat_name = ("s" if la < 0 else "n") + f"{abs(la):02d}"
    return (lon_name, lat_name)


def grid_steps(north, rows, cell_m):
    dlat = cell_m / _LAT_M
    mid = north - rows * dlat / 2
    dlon = cell_m / (_LON_M * math.cos(math.radians(mid)))
    return (dlat, dlon)


def exposure_grid(north, south, west, east, cell_m):
    rows = math.ceil((north - south) / (cell_m / _LAT_M))
    dlat, dlon = grid_steps(north, rows, cell_m)
    cols = math.ceil((east - west) / dlon)
    return {
        "north": north,
        "west": west,
        "south": north - rows * dlat,
        "east": west + cols * dlon,
        "cell_m": cell_m,
        "rows": rows,
        "cols": cols,
    }


def exposure_doc(grid, counts):
    rows = grid["rows"]
    cols = grid["cols"]
    if len(counts) != rows or any(len(row) != cols for row in counts):
        raise ValueError(f"counts debe medir {rows}x{cols}")
    doc = dict(grid)
    doc["count"] = counts
    return doc


def _chunk(ctype, data):
    crc = zlib.crc32(ctype + data) & 0xFFFFFFFF
    return struct.pack(">I", len(data)) + ctype + data + struct.pack(">I", crc)


def png_rgba(width, height, rgba_rows):
    raw = b"".join(b"\x00" + row for row in rgba_rows)
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + _chunk(b"IHDR", ihdr)
        + _chunk(b"IDAT", zlib.compress(raw))
        + _chunk(b"IEND", b"")
    )


def _hex_rgb(color_hex):
    digits = color_hex.lstrip("#")
    return (int(digits[0:2], 16), int(digits[2:4], 16), int(digits[4:6], 16))


def mask_png(mask, color_hex, alpha):
    red, green, blue = _hex_rgb(color_hex)
    on = bytes([red, green, blue, round(alpha * 255)])
    off = b"\x00\x00\x00\x00"
    rows = [b"".join(on if cell else off for cell in row) for row in mask]
    height = len(rows)
    width = len(rows[0]) // 4 if rows else 0
    return png_rgba(width, height, rows)


def _escape(text):
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def ground_overlay_kml(name, description, href, north, south, east, west):
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<kml xmlns="http://www.opengis.net/kml/2.2">\n'
        "<GroundOverlay>\n"
        f"<name>{_escape(name)}</name>\n"
        f"<description>{_escape(description)}</description>\n"
        f"<Icon><href>{_escape(href)}</href></Icon>\n"
        "<LatLonBox>"
        f"<north>{north}</north><south>{south}</south>"
        f"<east>{east}</east><west>{west}</west>"
        "</LatLonBox>\n"
        "</GroundOverlay>\n"
        "</kml>\n"
    )


def write_kmz(path, kml, files):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as out:
        out.writestr("doc.kml", kml)
        for name, data in files.items():
            out.writestr(f"files/{name}", data)


def circle_ring(lat, lon, radius_m, n=16):
    dlat = radius_m / _LAT_M
    dlon = radius_m / (_LON_M * math.cos(math.radians(lat)))
    ring = []
    for i in range(n + 1):
        angle = 2 * math.pi * i / n
        ring.append([lon + dlon * math.cos(angle), lat + dlat * math.sin(angle)])
    return ring


def building_card(props, visible_pct):
    height_text = props.get("height", "")
    meters = f"{height_from_text(height_text):g}"
    parts = [
        props.get("name", ""),
        props.get("grid", ""),
        f"Techo {props.get('roof', '')}",
        f"{_first_word(height_text).capitalize()} (≈{meters} m)",
        f"Cobertura: {props.get('cover', '')}",
    ]
    if visible_pct is not None:
        parts.append(f"Ve el {round(visible_pct)} % del campo")
    parts.append(props.get("note", ""))
    return " · ".join(parts)


def buildings_geojson(structures_fc, radii, visible):
    features = []
    for feat in structures_fc.get("features", []):
        props = feat.get("properties", {})
        num = props.get("num")
        lon, lat = feat["geometry"]["coordinates"][:2]
        ring = circle_ring(lat, lon, radii.get(num, 10.0))
        props_out = {
            "name": props.get("name", ""),
            "folder": "Edificios",
            "description": building_card(props, visible.get(num)),
            "stroke": "#ffffff",
            "stroke-opacity": 0.15,
            "stroke-width": 1,
            "fill": "#ffffff",
            "fill-opacity": 0,
            "labels": False,
            "num": num,
            "height_m": height_from_text(props.get("height", "")) + EYE_M,
        }
        features.append(
            {
                "type": "Feature",
                "geometry": {"type": "Polygon", "coordinates": [ring]},
                "properties": props_out,
            }
        )
    return {"type": "FeatureCollection", "features": features}
