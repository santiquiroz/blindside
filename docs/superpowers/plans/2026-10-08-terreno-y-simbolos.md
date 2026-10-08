# Terreno, visibilidad y símbolos: plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que ATAK, iTAK y Mando tengan elevación, visibilidad desde las torres, zona muerta, rutas cubiertas, fichas tocables de los edificios y símbolos militares que caducan, para OP MEDUSA.

**Architecture:** Blindside genera los productos de terreno con GDAL en WSL (`tak/terrain.py`, que usa `tak/terrain_core.py`, puro y probado) y los empaqueta en el paquete del campo v4. El overlay publica fichas de edificios y respeta `cot_type`. tak-mando lee el mismo DTED y la rejilla de exposición con stdlib (`mando/elevation.py`, `mando/exposure.py`) y suma tres herramientas.

**Tech Stack:** Python ≥ 3.10. Blindside `terrain.py`: GDAL 3.8 (CLI y `osgeo`) y numpy en WSL. `terrain_core.py`, el overlay y tak-mando: sólo stdlib. Pruebas con pytest.

**Spec:** `docs/superpowers/specs/2026-10-08-terreno-y-simbolos-design.md`.

## Global Constraints

- tak-mando y `tak/terrain_core.py`: sólo librería estándar. `tak/terrain.py` puede usar `osgeo.gdal`, `numpy` y los ejecutables de GDAL, porque sólo corre en WSL.
- Texto al usuario en español; código y nombres en inglés. Sin docstrings nuevos salvo el de módulo. Funciones pequeñas.
- Las pruebas no abren sockets, no llaman a GDAL ni a internet.
- Atribución exacta de las capas derivadas: `Elevación: Copernicus GLO-30 © DLR e.V. 2010-2014 y © Airbus Defence and Space GmbH 2014-2018, provisto por la ESA (Copernicus).`
- Campo (GRG): N 5.1650, S 5.1570, O -75.4960, E -75.4880. Rectángulo ampliado: N 5.174, S 5.148, O -75.505, E -75.479.
- Alturas: `bajo` 3 m, `medio` 8 m, `alto` 15 m; ojo 1,7 m.
- Colores: visibilidad `#ff3b30` con alfa 0,35; zona muerta `#34c759` con alfa 0,30; ruta cubierta `#34c759` con ancho 4; contorno de edificio blanco con alfa 0,15, ancho 1 y relleno alfa 0.
- Los delegados no hacen commit; los hace el orquestador. Nunca `Co-Authored-By`.

## Review Focus

1. Coordenadas fuera del DTED o en celdas vacías → `None` y un mensaje claro, nunca una excepción ni una altura falsa (Tareas 4 y 6).
2. La ruta cubierta entre dos puntos sin camino (rodeados de celdas fuera de la rejilla) → mensaje, nunca un bucle infinito (Tarea 5).
3. Un `cot_type` inventado o malicioso en la capa → el overlay usa el marcador normal (Tarea 3).
4. Un jugador que reporta contactos en ráfaga → límite de 20 s (Tarea 6).
5. Contactos vencidos mientras el bot estaba apagado → se borran en el primer `tick` (Tarea 6).

## Datos fijos (verificados en WSL el 2026-10-08)

- Tesela: `/vsicurl/https://copernicus-dem-30m.s3.amazonaws.com/Copernicus_DSM_COG_10_N05_00_W076_00_DEM/Copernicus_DSM_COG_10_N05_00_W076_00_DEM.tif`.
- Recorte: `gdalwarp -q -te -75.505 5.148 -75.479 5.174 <SRC> dsm.tif` (94×94, 1734–2235 m).
- DTED2: `gdalwarp -q -te <-76-h> <5-h> <-75+h> <6+h> -tr 1/3600 1/3600 -dstnodata -32767 -ot Int16 dsm.tif cell.tif`, con h = 0,5/3600, y luego `gdal_translate -q -of DTED cell.tif DTED/w076/n05.dt2`. Pesa 26 MB y 77 KB en zip.
- Formato DTED2 leído del archivo real. UHL en los bytes 0–80: `[4:12]` longitud origen `DDDMMSSH` (`0760000W`), `[12:20]` latitud origen (`0050000N`), `[20:24]` y `[24:28]` intervalos en décimas de segundo (`0010`), `[47:51]` número de columnas de longitud (`3601`), `[51:55]` postes por columna (`3601`). Los datos empiezan en el byte 3428 (80 + 648 + 2700). Cada registro (una columna, de oeste a este) mide 12 + 2·N bytes: 8 de encabezado (`0xAA`, 3 bytes de bloque, 2 de columna, 2 de fila), N alturas de sur a norte en big-endian con signo en el bit alto, y 4 de suma de control. El vacío es `0xFFFF` (-32767). Torre sur (5.1594, -75.4934) → columna 1824, fila 574 → 2190 m.
- Visibilidad: reproyectar a UTM 18N (`gdalwarp -t_srs EPSG:32618 -tr 10 10 -r bilinear`), `gdal_viewshed -ox X -oy Y -oz <altura> -tz 1.7 -md 2500 -vv 255 -iv 0 -ov 0` y volver a EPSG:4326 sobre el rectángulo del campo (`-te -75.4960 5.1570 -75.4880 5.1650 -ts 400 400 -r near`). Desde la Torre sur a 16,7 m se ve el 44,2 % del campo.

## Orden y carriles

| Ola | Tareas | Carril |
|---|---|---|
| 1 | 1 núcleo de terreno · 3 overlay `cot_type` · 4 elevación · 5 exposición | Muse ×4 (repos y archivos distintos) |
| 2 | 2 CLI de terreno (Blindside) · 6 herramientas y bot (tak-mando) | Muse ×2 |
| 3 | 7 generar, empaquetar, desplegar y verificar en ATAK | orquestador |
| 3 | 8 documentación | Muse ×2 |

Worktrees: `C:\personal\blindside-terreno` (rama `feature/terreno`) y `C:\personal\tak-mando-terreno` (rama `feature/terreno`).

---

### Task 1: Núcleo de terreno puro (`tak/terrain_core.py`, Blindside)

**Files:** Create `tak/terrain_core.py` and `tak/test_terrain_core.py`.

**Produces:**
- `HEIGHTS_M = {"bajo": 3.0, "medio": 8.0, "alto": 15.0}`, `EYE_M = 1.7`, `ATTRIBUTION` (la cadena exacta de Global Constraints).
- `height_from_text(text) -> float`: la primera palabra normalizada (minúsculas, sin tildes) de `"alto (sombra larga)"` → 15.0. Si no se reconoce → 3.0.
- `dted_names(lat, lon) -> tuple[str, str]`: `(-75.49, 5.16)` → `("w076", "n05")`. Usa el floor de la longitud y la latitud, con 3 dígitos para la longitud y 2 para la latitud.
- `grid_steps(north, rows, cell_m) -> tuple[float, float]`: `dlat = cell_m / 110574.0`, `dlon = cell_m / (111320.0 * cos(radians(north - rows * dlat / 2)))`.
- `exposure_grid(north, south, west, east, cell_m) -> dict`: `rows = ceil((north - south) / dlat0)` con `dlat0 = cell_m / 110574.0`; `cols` con `grid_steps` usando esos `rows`; devuelve `{"north", "west", "south": north - rows*dlat, "east": west + cols*dlon, "cell_m", "rows", "cols"}`.
- `exposure_doc(grid: dict, counts: list[list[int]]) -> dict`: `grid` más `"count": counts`. Valida `len(counts) == rows` y cada fila `== cols` (si no, `ValueError`).
- `png_rgba(width, height, rgba_rows: list[bytes]) -> bytes`: PNG RGBA de 8 bits, filtro 0 en cada fila, `zlib`; cada fila mide `width * 4` bytes.
- `mask_png(mask: list[list[bool]], color_hex: str, alpha: float) -> bytes`: verdadero → color con alfa `round(alpha*255)`; falso → transparente.
- `ground_overlay_kml(name, description, href, north, south, east, west) -> str` y `write_kmz(path, kml: str, files: dict[str, bytes])` (`doc.kml` más `files/<nombre>`, con escape XML de nombre y descripción).
- `circle_ring(lat, lon, radius_m, n=16) -> list[list[float]]`: anillo `[lon, lat]` cerrado (n + 1 puntos).
- `building_card(props: dict, visible_pct: float | None) -> str`: `"12 Torre sur · E6 · Techo parcial · Alto (≈15 m) · Cobertura: concreto · Ve el 34 % del campo · <nota>"`. Omite `Ve el…` si es None. El techo va como `Techo <roof>` tal cual, y la altura como la primera palabra capitalizada seguida de `(≈<metros sin decimales> m)`.
- `buildings_geojson(structures_fc: dict, radii: dict[int, float], visible: dict[int, float]) -> dict`: un Polygon `circle_ring` por estructura (radio `radii.get(num, 10.0)`), con propiedades `{"name", "folder": "Edificios", "description": building_card(...), "stroke": "#ffffff", "stroke-opacity": 0.15, "stroke-width": 1, "fill": "#ffffff", "fill-opacity": 0, "labels": False, "num", "height_m": height_from_text(height) + EYE_M}`.

- [ ] **Step 1: tests** (`tak/test_terrain_core.py`, cargando el módulo con importlib como las demás pruebas de `tak/`):

```python
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
```

- [ ] **Step 2:** `python -m pytest tak/test_terrain_core.py -q` → falla porque no existe el módulo.
- [ ] **Step 3:** implementar según la lista.
- [ ] **Step 4:** `python -m pytest tak -q` → todo verde.
- [ ] **Step 5 (orquestador):** commit `feat(tak): núcleo de terreno (alturas, rejilla de exposición, PNG, KMZ y fichas de edificios)`.

### Task 2: CLI de terreno con GDAL (`tak/terrain.py`, Blindside)

**Files:** Create `tak/terrain.py` (no lleva pruebas unitarias: llama a GDAL; lo verifica el orquestador en WSL).

**Consumes:** Task 1.

**Produces:** `python3 tak/terrain.py build --field-dir F --out O [--observers F/observers.json] [--structures F/grg-structures.geojson]`. Los pasos son funciones, una por producto, y `build` las corre en orden:
1. `dem`: los dos comandos de "Datos fijos" → `O/dsm.tif`, `O/DTED/w076/n05.dt2` (nombres con `dted_names`) y `O/dted-cementera.zip`, que guarda la ruta `DTED/w076/n05.dt2` dentro del zip.
2. `contours`: `gdal_contour -a elev -i 5` sobre `dsm.tif`, recortado al campo con `-spat`… o filtrado en Python. Las maestras (`elev % 25 == 0`) van con ancho 2 y nombre `"<elev> m"`; el resto con ancho 1 y sin nombre. Color `#a0522d`, `labels: false`. Se escribe `O/curvas-5m.geojson` y se convierte a `O/curvas-5m.kmz` con `tak/geojson2kml.py` (importado como módulo). La descripción del documento lleva `ATTRIBUTION`.
3. `viewshed`: por cada observador de `observers.json` (`[{"name", "slug", "lat", "lon", "height_m"}]`): `gdaltransform` (u `osr`) a UTM 18N, `gdal_viewshed` y reproyección al campo 400×400. Se escribe `O/visible-<slug>.kmz` con `mask_png(visible, "#ff3b30", 0.35)`, nombre `"Visible desde <name>"` y descripción `"Rojo: lo que ve <name> a <altura> m. <ATTRIBUTION>"`.
4. `deadground`: AND de "no visible" de todos los observadores → `O/zona-muerta.kmz` con verde 0,30, nombre `"Zona muerta"` y descripción `"Verde: lo que no ve ninguna torre. <ATTRIBUTION>"`.
5. `exposure`: `exposure_grid(5.174, 5.148, -75.505, -75.479, 10)`; cada viewshed se reproyecta con `-te west south east north -ts cols rows -r near` y se suma → `O/exposure.json` con `exposure_doc`.
6. `stats`: para cada estructura de `grg-structures.geojson`, un viewshed a `height_from_text(height) + EYE_M` y su porcentaje visible del campo → `O/visible-pct.json` (`{num: pct}`).
7. `buildings`: `buildings_geojson(structures, RADII, visible_pct)` → `O/edificios.geojson`, con `RADII = {1: 21.0, 2: 12.0, 3: 12.0, 4: 12.0, 5: 12.0, 16: 12.0}`.
- Cada `subprocess.run` va con argumentos en lista y `check=True`. Llamar a `gdal.UseExceptions()` al inicio.
- [ ] **Step 1:** implementar. **Step 2 (orquestador):** correr en WSL y verificar las salidas (ver Task 7). **Step 3 (orquestador):** commit `feat(tak): generación de elevación DTED, curvas, visibilidad, zona muerta, exposición y fichas de edificios`.

### Task 3: Overlay con `cot_type` y `stale_minutes` (`tak/tak-overlay.py`, Blindside)

**Files:** Modify `tak/tak-overlay.py` (`feature_event`, rama de puntos). Test: `tak/test_tak_overlay.py`.

**Produces:** si `props["cot_type"]` cumple `^a-[fhnu]-[A-Z](-[A-Za-z]+)*$`, el punto sale con ese `type`, **sin** `usericon` ni `color`, con `contact callsign`, `remarks`, `archive` y `labels_on value="false"`. `props["stale_minutes"]` (entero de 1 a 60) reemplaza el `stale_s` de ese evento. Cualquier otro valor se ignora.

- [ ] **Step 1: tests:**

```python
def _pt(props):
    return {"type": "Feature", "properties": {"name": "3 infantería", "id": "j-9", **props}, "geometry": {"type": "Point", "coordinates": [-75.49, 5.16]}}


def test_point_with_cot_type_uses_symbol():
    ev = overlay.feature_event(_pt({"cot_type": "a-h-G-U-C-I", "description": "visto en E6"}), "overlay-j-9", NOW, 43200)
    assert 'type="a-h-G-U-C-I"' in ev and "usericon" not in ev and "<remarks>visto en E6</remarks>" in ev


def test_invalid_cot_type_falls_back_to_marker():
    for bad in ["b-m-p-s-m", "a-h-G\"><x", "x", 5]:
        ev = overlay.feature_event(_pt({"cot_type": bad}), "overlay-j-9", NOW, 43200)
        assert 'type="b-m-p-s-m"' in ev


def test_stale_minutes_shortens_stale():
    ev = overlay.feature_event(_pt({"cot_type": "a-u-G", "stale_minutes": 10}), "overlay-j-9", NOW, 43200)
    assert 'stale="2024-05-01T12:10:00' in ev
```

- [ ] **Step 2–4:** fallar, implementar y `python -m pytest tak -q` en verde. **Step 5 (orquestador):** commit `feat(tak): el overlay publica símbolos militares (cot_type) y caducidad corta por objeto`.

### Task 4: Elevación DTED2 y línea de vista (`mando/elevation.py`, tak-mando)

**Files:** Create `mando/elevation.py` and `tests/test_elevation.py`.

**Produces:**
- `Dem` (dataclass): `lon0`, `lat0`, `dlon`, `dlat` (en grados), `ncols`, `nrows`, `data` (bytes), `reclen`.
- `load_dted(path) -> Dem` según el formato de "Datos fijos". `lon0`/`lat0` salen de `DDDMMSSH` (W y S negativos); los intervalos en décimas de segundo se pasan a grados (`/ 36000`). Si el encabezado no empieza con `UHL1` o el tamaño no cuadra → `ValueError("DTED inválido: <path>")`.
- `post(dem, col, row) -> int | None`: el valor sign-magnitude; `None` si es vacío (`0xFFFF` o -32767) o está fuera de rango.
- `elevation(dem, lat, lon) -> float | None`: interpolación bilineal de los 4 postes; `None` si alguno es `None`.
- `line_of_sight(dem, a_lat, a_lon, a_height_m, b_lat, b_lon, b_height_m, step_m=10.0) -> tuple[bool | None, float | None, float]`: `(visible, blocked_at_m, distance_m)`. Muestrea cada `step_m` metros (distancia con `mando.geo.haversine_m`, interpolación lineal en lat/lon). La línea de vista sube de `elevation(a) + a_height_m` a `elevation(b) + b_height_m`. Está tapada en la primera muestra intermedia cuyo terreno supere la línea en más de 1,0 m. Si alguna altura es `None` → `(None, None, dist)`.

- [ ] **Step 1: tests**, con un escritor DTED sintético en la prueba (rejilla pequeña de 0,01°, intervalo 10 décimas de segundo → 37×37 postes) que respeta exactamente el formato:

```python
import struct
from mando.elevation import Dem, elevation, line_of_sight, load_dted, post


def _sm(v):
    return 0xFFFF if v is None else (v if v >= 0 else 0x8000 | -v)


def _write_dted(path, heights, lon0=-75.5, lat0=5.15, n=37):
    uhl = bytearray(b" " * 80)
    uhl[0:4] = b"UHL1"; uhl[4:12] = b"0753000W"; uhl[12:20] = b"0050900N"
    uhl[20:24] = b"0010"; uhl[24:28] = b"0010"; uhl[47:51] = f"{n:04d}".encode(); uhl[51:55] = f"{n:04d}".encode()
    body = bytearray(uhl) + b" " * 648 + b" " * 2700
    for c in range(n):
        rec = bytes([0xAA]) + c.to_bytes(3, "big") + c.to_bytes(2, "big") + (0).to_bytes(2, "big")
        rec += b"".join(struct.pack(">H", _sm(heights(c, r))) for r in range(n)) + b"\0\0\0\0"
        body += rec
    path.write_bytes(bytes(body))


def test_reads_header_and_posts(tmp_path):
    p = tmp_path / "n05.dt2"
    _write_dted(p, lambda c, r: 2000 + r)
    dem = load_dted(p)
    assert (dem.ncols, dem.nrows) == (37, 37)
    assert abs(dem.lon0 - (-75.5)) < 1e-9 and abs(dem.lat0 - 5.15) < 1e-9
    assert post(dem, 0, 0) == 2000 and post(dem, 3, 10) == 2010


def test_negative_and_void(tmp_path):
    p = tmp_path / "n05.dt2"
    _write_dted(p, lambda c, r: None if c == 5 else -12)
    dem = load_dted(p)
    assert post(dem, 1, 1) == -12 and post(dem, 5, 1) is None
    assert elevation(dem, 5.15 + 1 / 3600, -75.5 + 5 / 3600) is None


def test_bilinear_and_outside(tmp_path):
    p = tmp_path / "n05.dt2"
    _write_dted(p, lambda c, r: 2000 + 10 * r)
    dem = load_dted(p)
    assert abs(elevation(dem, 5.15 + 1.5 / 3600, -75.5 + 2 / 3600) - 2015) < 1e-6
    assert elevation(dem, 6.0, -75.5) is None


def test_line_of_sight_blocked_by_ridge(tmp_path):
    p = tmp_path / "n05.dt2"
    _write_dted(p, lambda c, r: 2100 if c == 18 else 2000)
    dem = load_dted(p)
    vis, blocked, dist = line_of_sight(dem, 5.155, -75.5 + 10 / 3600, 1.7, 5.155, -75.5 + 26 / 3600, 1.7)
    assert vis is False and 200 < blocked < 300 and 450 < dist < 520
    vis, blocked, _ = line_of_sight(dem, 5.155, -75.5 + 10 / 3600, 300, 5.155, -75.5 + 26 / 3600, 1.7)
    assert vis is True and blocked is None


def test_bad_file(tmp_path):
    import pytest
    p = tmp_path / "x.dt2"; p.write_bytes(b"nope")
    with pytest.raises(ValueError):
        load_dted(p)
```

- [ ] **Steps 2–4:** fallar, implementar y `python -m pytest tests -q` en verde. **Step 5 (orquestador):** commit `feat: lector DTED2 y línea de vista`.

### Task 5: Rejilla de exposición y ruta cubierta (`mando/exposure.py`, tak-mando)

**Files:** Create `mando/exposure.py` and `tests/test_exposure.py`.

**Produces:**
- `Exposure` (dataclass): `north`, `west`, `cell_m`, `rows`, `cols`, `count` (lista de listas), `dlat`, `dlon`, con `dlat = cell_m / 110574.0` y `dlon = cell_m / (111320.0 * cos(radians(north - rows*dlat/2)))`.
- `load_exposure(path) -> Exposure` (si falta algún campo o las dimensiones no cuadran → `ValueError`).
- `cell_of(e, lat, lon) -> tuple[int, int] | None` y `center_of(e, row, col) -> tuple[float, float]`.
- `covered_route(e, a_lat, a_lon, b_lat, b_lon) -> list[tuple[float, float]] | None`: A* de 8 vecinos. El costo de un paso es `(1 o √2) * cell_m * (1 + 4 * count[destino])` y la heurística es la distancia en celdas × `cell_m`. Devuelve `None` si un extremo cae fuera o no hay camino. El resultado son centros de celda con los dos extremos exactos, submuestreado uniformemente a 20 vértices como máximo.
- `exposed_fraction(e, path) -> float`: fracción de vértices en celdas con `count > 0`.

- [ ] **Step 1: tests:**

```python
import json
from mando.exposure import Exposure, cell_of, center_of, covered_route, exposed_fraction, load_exposure


def _exp(tmp_path, count):
    doc = {"north": 5.17, "west": -75.5, "cell_m": 10, "rows": len(count), "cols": len(count[0]), "count": count}
    p = tmp_path / "e.json"; p.write_text(json.dumps(doc), encoding="utf-8")
    return load_exposure(p)


def test_avoids_exposed_band(tmp_path):
    rows, cols = 20, 20
    count = [[0] * cols for _ in range(rows)]
    for r in range(0, 15):
        count[r][10] = 3
    e = _exp(tmp_path, count)
    a = center_of(e, 2, 2); b = center_of(e, 2, 17)
    path = covered_route(e, *a, *b)
    assert path[0] == a and path[-1] == b and len(path) <= 20
    cells = [cell_of(e, *p) for p in path]
    assert min(r for r, _ in cells) >= 0
    assert exposed_fraction(e, path) <= 0.1


def test_outside_and_no_path(tmp_path):
    count = [[0] * 5 for _ in range(5)]
    e = _exp(tmp_path, count)
    assert covered_route(e, 0.0, 0.0, *center_of(e, 1, 1)) is None


def test_bad_doc(tmp_path):
    import pytest
    p = tmp_path / "e.json"; p.write_text('{"north": 5}', encoding="utf-8")
    with pytest.raises(ValueError):
        load_exposure(p)
```

- [ ] **Steps 2–4:** igual que en la Task 4. **Step 5 (orquestador):** commit `feat: ruta cubierta por A* sobre la rejilla de exposición`.

### Task 6: Herramientas nuevas, caducidad y opciones (tak-mando)

**Files:** Modify `mando/tools.py`, `mando/brainbot.py`, `mando/bot.py`, `mando/__main__.py`, `mando/mcp.py`. Tests: `tests/test_tools.py`, `tests/test_bot_brain.py`, `tests/test_mcp.py`.

**Consumes:** Tasks 4 y 5; spec §4.5.

**Produces:**
- `ToolContext` gana `dem=None`, `exposure=None` y `heights=None` (diccionario `nombre de lugar → metros`).
- `marcar_punto.tipo` agrega `aliado` y `desconocido`. `enemigo`, `aliado` y `desconocido` guardan `cot_type` `a-h-G-U-C-I`, `a-f-G-U-C-I` y `a-u-G`.
- `linea_de_vista(desde, hasta)`. La altura del origen es `heights.get(label_a, 1.7)` si `desde` resolvió a un lugar con altura, y si no 1,7; el destino va a 1,7. Textos exactos:
  - `"Desde {a} a {b} ({dist}): visible."`;
  - `"Desde {a} a {b} ({dist}): no visible, lo tapa el terreno a {blocked} de {a}."`;
  - `"No tengo datos de elevación cargados."`;
  - `"No tengo elevación en ese punto."`.
  Las distancias se escriben con `format_distance`.
- `reportar_contacto(tipo, cantidad=1, lugar, nota?)`. `tipo` es uno de `infanteria`, `vehiculo`, `dron`, `francotirador`, `desconocido`; `cantidad` va de 1 a 50.
  - Etiquetas: `infantería`, `vehículo`, `dron`, `francotirador`, `desconocido`. Nombre: `"{cantidad} {etiqueta}"`.
  - Tipos CoT: `a-h-G-U-C-I`, `a-h-G-E-V`, `a-h-A-M-F-Q`, `a-h-G-U-C-I`, `a-u-G`.
  - Cualquier jugador lo publica directo. Crea un punto en Juego con `kind: "contacto"`, `cot_type`, `stale_minutes: 10` y `expires` (ahora + 10 min, ISO UTC).
  - Anuncio a todos: `"CONTACTO: {nombre} en {label} ({callsign}, {HH:MM local})."`. Respuesta: `"Contacto publicado: {nombre} en {label}. Se borra a las {HH:MM local}."`.
  - Si ese uid tiene un contacto creado hace menos de 20 s → `"Espera unos segundos antes de otro reporte."`.
  - No consume `writes_left` del límite de 5.
- `ruta_cubierta(desde, hasta)` (en `WRITE_TOOLS`: un no autorizado la convierte en propuesta). Crea una LineString en Juego (`kind: "ruta"`, `stroke: "#34c759"`, `stroke-width: 4`, `name: "Ruta cubierta {a}→{b}"`, `labels: True`). Textos:
  - `"Ruta cubierta {a} → {b} dibujada ({dist}, {pct} % expuesta). id {fid}."`;
  - `"No tengo el mapa de visibilidad cargado."`;
  - `"No encontré una ruta entre esos puntos."`;
  - `"Ese punto queda fuera del mapa de visibilidad."`.
- `brainbot.expire_features(bot, now) -> list[str]`, llamada desde `tick` antes de `reload_hazards`: borra de Juego lo que tenga `expires <= now` (actor `mando-bot`), registra `events.add("contacto", "Contacto retirado: {nombre}", now)` y no envía chat. Si hay `LayerError`, sigue como los demás pasos del tick.
- CLI (bot y MCP): `--dted PATH`, `--exposure PATH` y `--buildings PATH`. `--buildings` se carga con `load_places` (se suma a los lugares) y su `height_m` llena `heights`. Si un archivo falla al cargar, se registra el error y la herramienta responde su mensaje de "no tengo…": el bot nunca se cae por esto.
- `TOOLS` pasa a 19 herramientas; actualizar la prueba de conteo.

- [ ] **Step 1: tests** (agregar a `tests/test_tools.py`, con un DTED sintético reutilizado de `tests/test_elevation.py` vía import de `_write_dted`, y una exposición pequeña):

```python
def test_contact_report_any_player_expires_and_announces(ctx):
    out = execute("reportar_contacto", {"tipo": "infanteria", "cantidad": 3, "lugar": "E6"}, GUEST, ctx)
    assert out.startswith("Contacto publicado: 3 infantería en E6. Se borra a las 17:10")
    f = ctx.layer.features("Juego")[0]["properties"]
    assert f["cot_type"] == "a-h-G-U-C-I" and f["stale_minutes"] == 10 and f["kind"] == "contacto"
    assert [a["text"] for a in ctx.layer.due_announcements(NOW)] == ["CONTACTO: 3 infantería en E6 (Recon, 17:00)."]
    assert execute("reportar_contacto", {"tipo": "dron", "lugar": "E6"}, GUEST, ctx) == "Espera unos segundos antes de otro reporte."


def test_enemy_point_gets_symbol(ctx):
    execute("marcar_punto", {"nombre": "Tirador", "lugar": "E6", "tipo": "enemigo"}, ADMIN, ctx)
    assert ctx.layer.get("j-1")["properties"]["cot_type"] == "a-h-G-U-C-I"


def test_line_of_sight_without_dem(ctx):
    assert execute("linea_de_vista", {"desde": "12", "hasta": "E5"}, ADMIN, ctx) == "No tengo datos de elevación cargados."


def test_covered_route_without_exposure(ctx):
    assert execute("ruta_cubierta", {"desde": "E5", "hasta": "12"}, ADMIN, ctx) == "No tengo el mapa de visibilidad cargado."
```

and in `tests/test_bot_brain.py`:

```python
def test_expired_contacts_are_removed_on_tick(tmp_path):
    bot, layer = _bot(tmp_path, FakeBrain())
    layer.add_feature({"name": "3 infantería", "folder": "Juego", "kind": "contacto", "expires": "2026-10-10T21:55:00Z"},
                      {"type": "Point", "coordinates": [-75.49, 5.16]}, NOW, "u")
    bot.tick(NOW)
    assert layer.features("Juego") == []
```

Plus one test each with a real synthetic DTED/exposure: a visible and a blocked `linea_de_vista`, and a `ruta_cubierta` that creates a LineString.

- [ ] **Steps 2–4:** fallar, implementar y `python -m pytest tests -q` en verde. **Step 5 (orquestador):** commit `feat: línea de vista, reportes de contacto con símbolo que caduca y rutas cubiertas`.

### Task 7: Generar, empaquetar, desplegar y verificar (orquestador)

- [ ] `observers.json` en `~/.blindside/field/`: 12 Torre sur, 6 Torre de silos y 11 Torre oeste, con lat/lon de `grg-structures.geojson` y `height_m` = `height_from_text` + 1,7.
- [ ] `python3 tak/terrain.py build --field-dir /mnt/c/Users/santi/.blindside/field --out /mnt/c/Users/santi/.blindside/field/terreno` en WSL. Verificar: el DTED da 2190 m en la Torre sur, el porcentaje visible desde la Torre sur ronda el 44 %, y cada KMZ se abre (zip válido).
- [ ] Paquete v4 con `tak/datapackage.py`: las 4 fuentes de mapa, los KMZ de la spec y `dted-cementera.zip`. Borrar el v3 del servidor, subir el v4 con `install_on_connection` y apuntar `--share-package` del bot al v4.
- [ ] Overlay: agregar `edificios.geojson` a `blindside-overlay` (tercer archivo). Bot: agregar `--dted`, `--exposure` y `--buildings`.
- [ ] ATAK por ADB: llega y se importa el paquete v4; "Google Hybrid" y "OpenTopoMap" aparecen en la lista de mapas; las capas KMZ en el gestor de capas; elevación activa (perfil de la herramienta de línea de vista en la cementera). Tocar un edificio muestra su ficha. Si el DTED no se importa desde el paquete, aplicar el plan B de la spec §4.2.
- [ ] E2E con jugador falso: `linea_de_vista` desde la torre sur, `reportar_contacto` (aparece con `type="a-h-G-U-C-I"` y se borra solo; para la prueba se acorta `expires`) y `ruta_cubierta` (la línea aparece en el overlay).
- [ ] Merge de las ramas, push y servicios apuntados a `main`.

### Task 8: Documentación

- [ ] Blindside `docs/tak-server.md`: sección "Terreno: elevación, visibilidad y rutas" (cómo regenerar con `terrain.py build`, qué capa es cada KMZ, el DTED en ATAK, la atribución). README (inglés y español): una línea en la lista de funciones.
- [ ] tak-mando README (inglés y español): `linea_de_vista`, `reportar_contacto` y `ruta_cubierta` con ejemplos, y las opciones `--dted`, `--exposure` y `--buildings`.
