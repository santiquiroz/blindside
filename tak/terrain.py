"""Build terrain products (DEM, contours, viewsheds, exposure, buildings) with GDAL."""

import argparse
import json
import subprocess
import sys
import zipfile
from pathlib import Path
from xml.sax.saxutils import escape

sys.path.insert(0, str(Path(__file__).resolve().parent))

import geojson2kml
from terrain_core import (
    ATTRIBUTION,
    EYE_M,
    buildings_geojson,
    dted_names,
    exposure_doc,
    exposure_grid,
    ground_overlay_kml,
    height_from_text,
    mask_png,
    write_kmz,
)

TILE_URL = "/vsicurl/https://copernicus-dem-30m.s3.amazonaws.com/Copernicus_DSM_COG_10_N05_00_W076_00_DEM/Copernicus_DSM_COG_10_N05_00_W076_00_DEM.tif"
FIELD_NORTH = 5.1650
FIELD_SOUTH = 5.1570
FIELD_WEST = -75.4960
FIELD_EAST = -75.4880
FIELD_COLS = 400
FIELD_ROWS = 400
EXT_NORTH = 5.174
EXT_SOUTH = 5.148
EXT_WEST = -75.505
EXT_EAST = -75.479
CELL_M = 10
MAX_DIST_M = 2500
RADII = {1: 21.0, 2: 12.0, 3: 12.0, 4: 12.0, 5: 12.0, 16: 12.0}

_utm_cache = {}


def run(cmd):
    subprocess.run([str(part) for part in cmd], check=True)


def clean(path):
    if path.exists():
        path.unlink()
    return path


def read_array(path):
    from osgeo import gdal

    gdal.UseExceptions()
    ds = gdal.Open(str(path))
    arr = ds.ReadAsArray()
    ds = None
    return arr


def lonlat_to_utm(lat, lon):
    from osgeo import osr

    src = osr.SpatialReference()
    src.ImportFromEPSG(4326)
    src.SetAxisMappingStrategy(osr.OAMS_TRADITIONAL_GIS_ORDER)
    dst = osr.SpatialReference()
    dst.ImportFromEPSG(32618)
    dst.SetAxisMappingStrategy(osr.OAMS_TRADITIONAL_GIS_ORDER)
    to_utm = osr.CoordinateTransformation(src, dst)
    x, y, _ = to_utm.TransformPoint(lon, lat)
    return (x, y)


def ensure_utm(dsm, work):
    utm = clean(work / "dsm_utm.tif")
    run(["gdalwarp", "-q", "-t_srs", "EPSG:32618", "-tr", "10", "10",
         "-r", "bilinear", dsm, utm])
    return utm


def viewshed_utm(dsm_utm, work, lat, lon, height_m, tag):
    key = (tag, lat, lon, height_m)
    if key in _utm_cache:
        return _utm_cache[key]
    x, y = lonlat_to_utm(lat, lon)
    out = clean(work / f"vs_{tag}_utm.tif")
    run(["gdal_viewshed", "-ox", repr(x), "-oy", repr(y), "-oz", str(height_m),
         "-tz", str(EYE_M), "-md", str(MAX_DIST_M),
         "-vv", "255", "-iv", "0", "-ov", "0", dsm_utm, out])
    _utm_cache[key] = out
    return out


def warp_grid(src, west, south, east, north, cols, rows, dst):
    clean(dst)
    run(["gdalwarp", "-q", "-t_srs", "EPSG:4326",
         "-te", repr(west), repr(south), repr(east), repr(north),
         "-ts", str(cols), str(rows), "-r", "near", src, dst])
    return dst


def field_array(dsm_utm, work, lat, lon, height_m, tag):
    src = viewshed_utm(dsm_utm, work, lat, lon, height_m, tag)
    tif = warp_grid(src, FIELD_WEST, FIELD_SOUTH, FIELD_EAST, FIELD_NORTH,
                    FIELD_COLS, FIELD_ROWS, work / f"vs_{tag}_field.tif")
    return read_array(tif)


def fmt_size(path):
    size = path.stat().st_size
    if size >= 1048576:
        return f"{size / 1048576:.1f} MB"
    if size >= 1024:
        return f"{size / 1024:.1f} KB"
    return f"{size} bytes"


def load_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write_json(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")


def step_dem(out):
    work = out / "work"
    work.mkdir(parents=True, exist_ok=True)
    dsm = clean(out / "dsm.tif")
    run(["gdalwarp", "-q", "-te", repr(EXT_WEST), repr(EXT_SOUTH),
         repr(EXT_EAST), repr(EXT_NORTH), TILE_URL, dsm])
    half = 0.5 / 3600
    cell = clean(work / "cell.tif")
    run(["gdalwarp", "-q", "-te", repr(-76 - half), repr(5 - half),
         repr(-75 + half), repr(6 + half), "-tr", repr(1 / 3600), repr(1 / 3600),
         "-dstnodata", "-32767", "-ot", "Int16", dsm, cell])
    lon_name, lat_name = dted_names(FIELD_SOUTH, FIELD_WEST)
    dt2 = out / "DTED" / lon_name / f"{lat_name}.dt2"
    dt2.parent.mkdir(parents=True, exist_ok=True)
    clean(dt2)
    run(["gdal_translate", "-q", "-of", "DTED", cell, dt2])
    zip_path = clean(out / "dted-cementera.zip")
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.write(dt2, arcname=f"DTED/{lon_name}/{lat_name}.dt2")
    print(f"DEM: {dsm} ({fmt_size(dsm)})")
    print(f"DTED: {dt2} ({fmt_size(dt2)})")
    print(f"ZIP DTED: {zip_path} ({fmt_size(zip_path)})")
    return {"dsm": dsm, "dt2": dt2, "zip": zip_path}


def inside_field(lon, lat):
    return FIELD_WEST <= lon <= FIELD_EAST and FIELD_SOUTH <= lat <= FIELD_NORTH


def geom_kept(geom):
    if not isinstance(geom, dict):
        return False
    if geom.get("type") == "LineString":
        lines = [geom.get("coordinates", [])]
    elif geom.get("type") == "MultiLineString":
        lines = geom.get("coordinates", [])
    else:
        return False
    for line in lines:
        for pos in line:
            if inside_field(pos[0], pos[1]):
                return True
    return False


def style_contour(props):
    level = int(round(float(props.get("elev", 0))))
    props["stroke"] = "#a0522d"
    props["labels"] = False
    if level % 25 == 0:
        props["stroke-width"] = 2
        props["name"] = f"{level} m"
    else:
        props["stroke-width"] = 1
        props.pop("name", None)
    return props


def step_contours(out):
    work = out / "work"
    work.mkdir(parents=True, exist_ok=True)
    raw = clean(work / "contours_raw.geojson")
    run(["gdal_contour", "-q", "-a", "elev", "-i", "5", "-f", "GeoJSON",
         out / "dsm.tif", raw])
    feats = [f for f in load_json(raw).get("features", [])
             if geom_kept(f.get("geometry") or {})]
    for feat in feats:
        feat["properties"] = style_contour(feat.get("properties") or {})
    geo = out / "curvas-5m.geojson"
    write_json(geo, {"type": "FeatureCollection", "features": feats})
    kml = geojson2kml.document_xml(feats, "Curvas cada 5 m")
    kml = kml.replace("</name>", f"</name><description>{escape(ATTRIBUTION)}</description>", 1)
    kmz = out / "curvas-5m.kmz"
    geojson2kml.write_output(kml, kmz)
    print(f"Curvas: {geo} ({fmt_size(geo)}, {len(feats)} líneas)")
    print(f"Curvas KMZ: {kmz} ({fmt_size(kmz)})")
    return {"geojson": geo, "kmz": kmz}


def step_viewshed(out, observers):
    work = out / "work"
    work.mkdir(parents=True, exist_ok=True)
    dsm_utm = ensure_utm(out / "dsm.tif", work)
    results = []
    for obs in observers:
        seen = field_array(dsm_utm, work, obs["lat"], obs["lon"], obs["height_m"], obs["slug"]) == 255
        pct = round(float(seen.mean()) * 100, 1)
        png = mask_png(seen.tolist(), "#ff3b30", 0.35)
        name = f"Visible desde {obs['name']}"
        desc = f"Rojo: lo que ve {obs['name']} a {obs['height_m']:g} m. {ATTRIBUTION}"
        kml = ground_overlay_kml(name, desc, f"files/visible-{obs['slug']}.png",
                                 FIELD_NORTH, FIELD_SOUTH, FIELD_EAST, FIELD_WEST)
        kmz = out / f"visible-{obs['slug']}.kmz"
        write_kmz(kmz, kml, {f"visible-{obs['slug']}.png": png})
        print(f"Visible desde {obs['name']}: {kmz} ({fmt_size(kmz)}, ve el {pct:.1f} % del campo)")
        results.append({"slug": obs["slug"], "pct": pct, "kmz": kmz})
    return results


def step_deadground(out, observers):
    work = out / "work"
    work.mkdir(parents=True, exist_ok=True)
    dsm_utm = ensure_utm(out / "dsm.tif", work)
    hidden = None
    for obs in observers:
        not_seen = field_array(dsm_utm, work, obs["lat"], obs["lon"], obs["height_m"], obs["slug"]) != 255
        hidden = not_seen if hidden is None else (hidden & not_seen)
    pct = round(float(hidden.mean()) * 100, 1)
    png = mask_png(hidden.tolist(), "#34c759", 0.30)
    kml = ground_overlay_kml("Zona muerta", f"Verde: lo que no ve ninguna torre. {ATTRIBUTION}",
                             "files/zona-muerta.png",
                             FIELD_NORTH, FIELD_SOUTH, FIELD_EAST, FIELD_WEST)
    kmz = out / "zona-muerta.kmz"
    write_kmz(kmz, kml, {"zona-muerta.png": png})
    print(f"Zona muerta: {kmz} ({fmt_size(kmz)}, oculta el {pct:.1f} % del campo)")
    return {"kmz": kmz, "pct": pct}


def step_exposure(out, observers):
    work = out / "work"
    work.mkdir(parents=True, exist_ok=True)
    dsm_utm = ensure_utm(out / "dsm.tif", work)
    grid = exposure_grid(EXT_NORTH, EXT_SOUTH, EXT_WEST, EXT_EAST, CELL_M)
    total = None
    for obs in observers:
        src = viewshed_utm(dsm_utm, work, obs["lat"], obs["lon"], obs["height_m"], obs["slug"])
        tif = warp_grid(src, grid["west"], grid["south"], grid["east"], grid["north"],
                        grid["cols"], grid["rows"], work / f"vs_{obs['slug']}_exp.tif")
        vis = (read_array(tif) == 255).astype("uint8")
        total = vis if total is None else total + vis
    path = out / "exposure.json"
    write_json(path, exposure_doc(grid, total.tolist()))
    print(f"Exposición: {path} ({fmt_size(path)}, rejilla {grid['rows']}x{grid['cols']})")
    return {"json": path, "grid": grid}


def step_stats(out, structures):
    work = out / "work"
    work.mkdir(parents=True, exist_ok=True)
    dsm_utm = ensure_utm(out / "dsm.tif", work)
    visible = {}
    for feat in structures.get("features", []):
        props = feat.get("properties", {})
        num = props.get("num")
        lon, lat = feat["geometry"]["coordinates"][:2]
        height = height_from_text(props.get("height", "")) + EYE_M
        seen = field_array(dsm_utm, work, lat, lon, height, f"struct-{num}") == 255
        visible[num] = round(float(seen.mean()) * 100, 1)
    path = out / "visible-pct.json"
    write_json(path, visible)
    print(f"Porcentajes: {path} ({fmt_size(path)}, {len(visible)} estructuras)")
    return {"json": path, "visible": visible}


def step_buildings(out, structures, visible):
    fc = buildings_geojson(structures, RADII, visible)
    path = out / "edificios.geojson"
    write_json(path, fc)
    print(f"Edificios: {path} ({fmt_size(path)}, {len(fc['features'])} edificios)")
    return {"geojson": path}


def load_observers(path):
    try:
        data = load_json(path)
    except FileNotFoundError:
        print(f"error: no se encontró observers.json: {path}", file=sys.stderr)
        raise SystemExit(1)
    if not isinstance(data, list) or not data:
        print(f"error: {path} no trae observadores", file=sys.stderr)
        raise SystemExit(1)
    return data


def load_structures(path):
    try:
        data = load_json(path)
    except FileNotFoundError:
        print(f"error: no se encontró grg-structures.geojson: {path}", file=sys.stderr)
        raise SystemExit(1)
    if not isinstance(data, dict) or data.get("type") != "FeatureCollection":
        print(f"error: {path} no es una FeatureCollection", file=sys.stderr)
        raise SystemExit(1)
    return data


def load_visible(out):
    path = out / "visible-pct.json"
    if not path.exists():
        return {}
    visible = {}
    for key, val in load_json(path).items():
        try:
            visible[int(key)] = val
        except ValueError:
            continue
    return visible


def parse_args(argv):
    parser = argparse.ArgumentParser(prog="terrain.py")
    subs = parser.add_subparsers(dest="cmd", required=True)
    for name in ("dem", "contours", "viewshed", "deadground", "exposure",
                 "stats", "buildings", "build"):
        sub = subs.add_parser(name)
        sub.add_argument("--field-dir", required=True)
        sub.add_argument("--out", required=True)
        sub.add_argument("--observers", default=None)
        sub.add_argument("--structures", default=None)
    return parser.parse_args(argv)


def main(argv=None):
    from osgeo import gdal

    gdal.UseExceptions()
    args = parse_args(argv)
    field_dir = Path(args.field_dir)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    observers_path = Path(args.observers) if args.observers else field_dir / "observers.json"
    structures_path = Path(args.structures) if args.structures else field_dir / "grg-structures.geojson"
    if args.cmd in ("viewshed", "deadground", "exposure", "build"):
        observers = load_observers(observers_path)
    if args.cmd in ("stats", "buildings", "build"):
        structures = load_structures(structures_path)
    if args.cmd == "dem":
        step_dem(out)
    elif args.cmd == "contours":
        step_contours(out)
    elif args.cmd == "viewshed":
        step_viewshed(out, observers)
    elif args.cmd == "deadground":
        step_deadground(out, observers)
    elif args.cmd == "exposure":
        step_exposure(out, observers)
    elif args.cmd == "stats":
        step_stats(out, structures)
    elif args.cmd == "buildings":
        step_buildings(out, structures, load_visible(out))
    elif args.cmd == "build":
        step_dem(out)
        step_contours(out)
        step_viewshed(out, observers)
        step_deadground(out, observers)
        step_exposure(out, observers)
        stats = step_stats(out, structures)
        step_buildings(out, structures, stats["visible"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
