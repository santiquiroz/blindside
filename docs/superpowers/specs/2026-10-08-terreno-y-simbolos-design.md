# Terreno, visibilidad y símbolos para el mapa del equipo

Fecha: 2026-10-08 · Estado: aprobado · Meta: listo el 2026-10-09 en la noche para OP MEDUSA (Cementos Caldas, Neira, 2026-10-10 y 11).

Complementa a [Mando con cerebro](../../../../tak-mando/docs/superpowers/specs/2026-10-08-mando-cerebro-design.md) (repo tak-mando) y a la capa táctica en vivo de `tak/tak-overlay.py`.

## 1. Objetivo

El mapa del equipo (ATAK, iTAK y Mando) gana lo que le falta frente a lo que muestra iTAK de fábrica y suma inteligencia del terreno:

1. Mapa híbrido (satélite con nombres) y mapa topográfico como fuentes de mapa.
2. Curvas de nivel cada 5 m sobre el campo.
3. Elevación DTED2 de la zona, para la línea de vista nativa de ATAK.
4. Mapas de visibilidad desde las estructuras altas, la "zona muerta" que no ve ninguna, y fichas de cada edificio que se ven al tocarlo.
5. Mando responde "¿me ven desde X?", traza rutas cubiertas y publica reportes de contacto con símbolos militares que caducan solos.

Criterios de éxito:

1. Al conectarse, ATAK recibe el paquete v4. Después tiene "Google Hybrid" y "OpenTopoMap" en la lista de mapas, las capas KMZ nuevas en el gestor de capas y elevación en la zona (la herramienta de línea de vista de ATAK da perfiles reales en la cementera).
2. Tocar un edificio en ATAK muestra su ficha (nombre, cuadro, techo, altura, cobertura, nota, qué porcentaje del campo ve) sin que haya etiquetas nuevas en el mapa.
3. "Mando, ¿me ven desde la torre sur?" responde con la línea de vista calculada, y "Mando, contacto, 3 enemigos en E6" pone un símbolo hostil que desaparece solo a los 10 minutos.
4. "Mando, ruta cubierta de la llegada al 9" dibuja una línea en el mapa de todos que evita las zonas visibles desde las torres.

## 2. Alcance

Dentro: herramientas de terreno en Blindside (`tak/terrain.py`), paquete del campo v4, fichas tocables de los 23 edificios en la capa táctica en vivo, soporte de `cot_type` en el overlay, módulo de elevación y tres herramientas nuevas en tak-mando, y documentación.

Fuera: alturas reales de edificios (se estiman por sombra), visibilidad a través de árboles, rutas en tiempo real según la posición del enemigo, y modelos de elevación más finos que 30 m.

## 3. Datos de partida

- Modelo de superficie **Copernicus GLO-30 DSM** (ESA, licencia libre con atribución), tesela `Copernicus_DSM_COG_10_N05_00_W076_00_DEM` del bucket público `copernicus-dem-30m` (AWS). Es 1 segundo de arco (~30 m), incluye edificios y árboles, y coincide con la malla de DTED2.
- Campo: rectángulo de la GRG (N 5.1650, S 5.1570, O -75.4960, E -75.4880), ampliado 1 km por lado para el DTED y las rutas.
- Estructuras: `~/.blindside/field/grg-structures.geojson` (23 puntos con número, nombre, cuadro, techo, altura cualitativa, cobertura y nota).
- Observadores para visibilidad: 12 Torre sur, 6 Torre de silos y 11 Torre oeste (el Bloque oeste, 10, queda junto a la 11 y no agrega una capa). La altura se estima por la sombra: "alto" equivale a 15 m, "medio" a 8 m y "bajo" a 3 m sobre el suelo, más 1,7 m de ojo. Los valores viven en un archivo JSON del campo y se pueden corregir.

## 4. Componentes

### 4.1 Blindside `tak/terrain.py` (corre en WSL, con GDAL)

Subcomandos (cada uno idempotente, con sus salidas en una carpeta indicada):

- `dem`: descarga la tesela por `/vsicurl/`, la remuestrea a la malla DTED2 exacta de la celda (1°, 3601×3601 postes) y pone vacío (-32767) fuera del rectángulo ampliado. Genera `DTED/w076/n05.dt2` y `dted-cementera.zip`, que pesa poco porque el vacío comprime casi a cero. También guarda un GeoTIFF recortado del campo para los demás subcomandos.
- `contours`: curvas cada 5 m dentro del rectángulo del campo, con `gdal_contour`. Salen a GeoJSON y de ahí a KMZ con el estilo existente de `geojson2kml.py`: café fino, maestras cada 25 m más gruesas y con su cota como nombre, sin etiquetas visibles.
- `viewshed`: un `gdal_viewshed` por observador, a la altura del archivo de observadores. Cada uno se convierte en PNG transparente (rojo, opacidad 0,35 donde se ve) y en KMZ `GroundOverlay` sobre el rectángulo del campo.
- `deadground`: celdas del campo que no ve ningún observador, en verde con opacidad 0,30, en KMZ.
- `exposure`: rejilla de 10 m sobre el rectángulo ampliado en `exposure.json`: `{"north", "west", "cell_m", "rows", "cols", "count": [[n de observadores que ven la celda]]}`. La usa Mando para las rutas cubiertas.
- `stats`: porcentaje del campo visible desde cada estructura del GRG (un viewshed por estructura a su altura estimada), escrito como `"visible_pct"` en una copia de `grg-structures.geojson`.

Las partes puras (geometría de la malla, nombres DTED, colores, escritura KML, conversión de alturas cualitativas, armado de `exposure.json`) van en funciones probadas con pytest, sin GDAL. Las llamadas a GDAL van por `subprocess` con argumentos en lista.

### 4.2 Paquete del campo v4 (`tak/datapackage.py`, ya existe)

Contenido: `bing-aerial.xml`, `esri-world-imagery.xml`, `google-hybrid.xml`, `opentopomap.xml`, `cementera-op-medusa.kmz`, `cementera-grg.kmz`, `curvas-5m.kmz`, `visible-torre-sur.kmz`, `visible-torre-silos.kmz`, `visible-torre-oeste.kmz`, `zona-muerta.kmz` y `dted-cementera.zip`.

Se sube al servidor reemplazando el v3 (primero se borra y después se sube, porque comparten el uid del manifiesto), con `install_on_connection`. El bot pasa a apuntar al zip v4: como su registro de entregas va por hash, se lo reenvía solo a todos los jugadores.

La importación del DTED desde el paquete se verifica en ATAK por ADB. Si ATAK no lo toma desde el paquete, el zip se entrega aparte con el mismo mecanismo de Mando (un segundo envío) y la guía explica cómo importarlo con "Zipped DTED Directory".

### 4.3 Fichas de los edificios (capa táctica en vivo)

- Un archivo nuevo `~/.blindside/field/edificios.geojson`, publicado por el overlay junto a `cementera.geojson` y `juego.geojson`.
- Cada estructura es un círculo (Polygon de 16 vértices) con radio según su tipo: 21 m el tanque grande, 12 m los silos y 10 m el resto, ajustable por estructura. Contorno blanco con opacidad 0,15, ancho 1, relleno opacidad 0 y sin etiqueta (`labels: false`). Así casi no se ve, pero se puede tocar.
- La descripción (`remarks`) es la ficha: `"12 Torre sur · E6 · Techo parcial · Alto (≈15 m) · Cobertura: concreto · Ve el 34 % del campo · La estructura más alta del complejo por su sombra."`.
- Los polígonos y puntos actuales de `cementera.geojson` mantienen su descripción. Se verifica en ATAK por ADB que tocar uno muestre el texto. Si iTAK no muestra `remarks` en las formas, la guía lo dice y la descripción se repite en el nombre del objeto sólo para iTAK; esto se decide con evidencia.

### 4.4 Overlay de Blindside: `cot_type`

`feature_event` acepta una propiedad `cot_type` en los puntos. Si cumple `^a-[fhnu]-[A-Z](-[A-Za-z]+)*$`, el evento usa ese tipo (símbolo militar 2525 que dibujan ATAK e iTAK) y no lleva `usericon` ni `color`. Mantiene `callsign`, `remarks` y `stale`. Cualquier otro valor se ignora y sale el marcador de color de siempre. Una propiedad `stale_minutes` (1 a 60) acorta el `stale` del evento para los contactos.

### 4.5 tak-mando

- `mando/elevation.py` (stdlib): lector de DTED2 (`load_dted(path)`), altura bilineal (`elevation(dem, lat, lon)`, None si cae en vacío o fuera) y `line_of_sight(dem, a, a_height_m, b, b_height_m, step_m=10) -> (visible, blocked_at_m, distance_m)`.
- `mando/exposure.py` (stdlib): carga `exposure.json` y `covered_route(exposure, a, b) -> list[(lat, lon)]` con A* de 8 vecinos. El costo es distancia × (1 + 4 × observadores que ven la celda). Devuelve una línea simplificada de 20 vértices como máximo.
- Herramientas nuevas en `TOOLS`:
  - `linea_de_vista(desde, hasta)`. Si `desde` es un edificio con altura estimada, usa esa altura más 1,7 m; si no, 1,7 m. El destino siempre a 1,7 m. Respuesta: `"Desde 12 Torre sur a E7 (180 m): visible."` o `"… no visible: lo tapa el terreno a 90 m de 12 Torre sur."`. Sin DTED: `"No tengo datos de elevación cargados."`.
  - `reportar_contacto(tipo, cantidad, lugar, nota?)`. Tipos y códigos: infantería `a-h-G-U-C-I`, vehículo `a-h-G-E-V`, dron `a-h-A-M-F-Q`, francotirador `a-h-G-U-C-I` (nombrado "Francotirador"), desconocido `a-u-G`. Cualquier jugador lo publica directo, sin propuesta, porque caduca solo. Crea un punto en Juego con `cot_type`, `stale_minutes: 10` y `expires` (ahora + 10 min). Anuncia a todo el chat `"CONTACTO: 3 infantería en E6 (Recon, 22:41)."`. Límite: 1 reporte cada 20 s por jugador.
  - `ruta_cubierta(desde, hasta)`: crea una LineString en Juego (`kind: "ruta"`, color `#34c759`, ancho 4) con nombre `"Ruta cubierta A→B"`. Sigue la regla de permisos de las escrituras: si no está autorizado, queda como propuesta. Sin `exposure.json`: `"No tengo el mapa de visibilidad cargado."`.
- `marcar_punto` agrega los tipos `aliado` (`a-f-G-U-C-I`) y `desconocido` (`a-u-G`), y `enemigo` pasa a `a-h-G-U-C-I`. Los demás tipos siguen como marcadores de color.
- El bot borra en `tick` los objetos de Juego con `expires` vencido (`layer.delete_feature` con actor `mando-bot`) y registra el evento "contacto retirado".
- Opciones nuevas: `--dted PATH`, `--exposure PATH` y `--buildings PATH`. `--buildings` carga `edificios.geojson`: sus centros se suman a los lugares conocidos (para resolver "12", "torre sur") y su `height_m` da la altura del observador en `linea_de_vista`.
- El MCP carga los mismos archivos con las mismas opciones.

## 5. Seguridad y licencias

- Copernicus GLO-30: atribución "© DLR e.V. 2010-2014 y © Airbus Defence and Space GmbH 2014-2018, distribuido por la ESA/Copernicus" en la guía y en la descripción de las capas derivadas.
- Las teselas de Google y OpenTopoMap se piden desde cada celular, con sus términos. No se redistribuyen teselas.
- Las herramientas nuevas de Mando no cambian el modelo de permisos, salvo la excepción explícita de `reportar_contacto`, que es efímero y tiene límite de frecuencia.

## 6. Pruebas

- Blindside: funciones puras de `terrain.py` (malla, nombres DTED, color, KML, alturas, `exposure.json`) y overlay con `cot_type` válido, inválido y `stale_minutes`.
- tak-mando: lector DTED con un archivo DTED2 sintético pequeño generado en la prueba, línea de vista (visible y tapada), A* (evita celdas expuestas, devuelve None si no hay camino), herramientas nuevas (permisos, límites, caducidad en `tick`, textos exactos).
- En vivo: el DTED real de la zona entrega alturas plausibles (la cementera ronda 2150-2250 m). Línea de vista desde la Torre sur. Una ruta cubierta dibujada que aparece en el overlay. Un contacto que aparece y se borra solo. En ATAK por ADB: el paquete v4 importado, las capas y mapas en las listas, el DTED activo (perfil de línea de vista) y la ficha al tocar un edificio.
