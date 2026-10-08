# Blindside — filtro de fantasmas Doppler y aliado probable (TAK + BLE) — diseño

Fecha: 2026-10-07 · Estado: aprobado por Santiago (las tres piezas) · Meta: partida del 2026-10-11.

## 1. Problemas que se corrigen

1. **Fantasmas al caminar / ceguera al caminar.** Hoy la v1 evita los ecos del entorno con la regla "detenerse y escanear" (spec v1 §6.6): mientras el jugador se mueve no se confirma ningún contacto nuevo. Resultado: los fantasmas casi no aparecen, pero tampoco los rivales reales hasta que el jugador se detiene.
2. **El radar no distingue amigos de enemigos.** Un compañero a menos de 6 m vibra igual que un rival y, con el enlace TAK, se publica en el mapa como "desconocido".

## 2. Filtro de fantasmas Doppler (radar-core)

Cada detección ya trae `radialSpeedMps` (velocidad radial del LD2450 con `speedSign` calibrado por radar; positivo = se aleja). Un objeto quieto visto desde un jugador que se desplaza con velocidad `(vx, vy)` en el marco del cuerpo (x derecha, y al frente) tiene velocidad radial `−(vx·sin θ + vy·cos θ)`, con θ el rumbo de la detección en el marco del cuerpo. Una persona que se mueve por su cuenta se aparta de esa recta.

- **Estimador de velocidad propia** (`EgoMotion`): acumula las detecciones de ~1 s (ambos radares, alcance ≥ 1 m) y ajusta `(vx, vy)` por mínimos cuadrados con una pasada de descarte (residuo > 0,3 m/s fuera, se reajusta). Válido solo si quedan ≥ 4 detecciones, el ajuste final tiene residuo RMS ≤ 0,2 m/s, la rapidez está entre 0,2 y 2,5 m/s y los rumbos cubren ≥ 20° (si no, el sistema es degenerado). Si el signo de un radar estuviera mal calibrado, el ajuste no cuadra y el estimador queda "desconocido": el filtro se apaga solo y vuelve el comportamiento de la v1.
- **Filtro:** mientras el jugador **camina** (no gira) y el estimador es válido, una detección con `|radial − esperado| ≤ 0,25 m/s` se considera eco de algo quieto y se descarta antes del tracker (contador `clutterRejected`). Las demás siguen.
- **Confirmación al caminar:** en ese mismo estado (caminando, sin girar, estimador válido) se permite confirmar contactos (hoy no). Girando, o con el estimador desconocido, sigue la regla v1.
- Consecuencia honesta: mientras caminas, una persona **quieta** también se ve como "algo quieto" y no aparece. Es la misma limitación de siempre del radar de movimiento ("quien se queda quieto se desvanece").
- Ajuste: `TuningParams.doppler.enabled` (por defecto encendido) y un interruptor en Ajustes del reloj "Filtro de fantasmas al caminar".

Pruebas con el simulador: `walkingTowardWall` sigue sin confirmar nada; `rivalWhileWalking` confirma al rival **durante** la caminata; escenario nuevo con árboles a los lados y un rival cruzando mientras el jugador camina; signo de un radar invertido → estimador desconocido → comportamiento v1.

## 3. Aliado probable

Un contacto del radar se marca **aliado probable** solo cuando la evidencia no es ambigua. Nunca se silencia: vibra con un patrón corto y suave en vez de la alerta de lado, se dibuja en cian con "?" y **no se publica** al mapa del equipo.

**Por GPS (TAK):** para cada compañero de `/tak/team` a ≤ 15 m de "aquí" (GPS del celular) con edad ≤ 15 s, su rumbo relativo al cuerpo = rumbo geográfico − rumbo del cuerpo (ancla de §5 del spec ATAK). Los contactos a ≤ 45° de ese rumbo forman el sector del compañero. Si en el sector hay **a lo sumo tantos contactos como compañeros** que lo reclaman, esos contactos son aliados probables; si hay más contactos que compañeros (un rival puede estar junto al aliado), ninguno se marca. Sin rumbo del cuerpo o sin "aquí", no se marca nada por GPS.

**Por BLE:** los celulares Android con Blindside y el enlace TAK prendido emiten una baliza BLE (UUID de equipo + id de 4 bytes derivado del uid del dispositivo). El reloj la escucha durante la sesión. Una baliza ajena con RSSI suavizado ≥ −70 dBm vista en los últimos 5 s cuenta como "aliado a ≲ 4 m". Si el número de contactos a ≤ 4 m es **menor o igual** que el de esas balizas, esos contactos son aliados probables; si hay más, ninguno. La baliza del propio celular se ignora: el celular manda su id en `/tak/team` (`me`). Los iPhone no emiten baliza (solo cuentan por GPS).

## 4. Piezas

| Pieza | Módulo |
|---|---|
| `EgoMotion`, filtro y confirmación al caminar, `TuningParams.doppler`, escenarios del simulador | radar-core |
| Interruptor "Filtro de fantasmas al caminar" (AppSettings → PipelineConfig) | android-shared + wear-app Ajustes |
| `likelyAllyIds(...)` puro, patrón háptico `ALLY_PATTERN`, gancho en `SessionEngine`, `telemetryOf` excluye aliados, `TeamUpdate.me`, balizas en `SessionStore` | android-shared |
| Escáner de balizas, dibujo cian "?", cableado del gancho | wear-app |
| Anunciante BLE, `me` en `/tak/team`, permiso `BLUETOOTH_ADVERTISE` | phone-app |

## 5. Pruebas

JVM: estimador (velocidad conocida → recupera `(vx, vy)`; signo invertido → desconocido; pocas detecciones → desconocido), filtro (eco quieto descartado, caminante lateral conservado), escenarios del simulador; `likelyAllyIds` (un compañero en el sector → marcado; compañero + rival en el mismo sector → ninguno; sin rumbo → ninguno; balizas: 1 baliza y 1 contacto cercano → marcado, 1 baliza y 2 contactos → ninguno, baliza propia ignorada); códec `TeamUpdate.me`. Campo: caminar por un pasillo con el cinturón (fantasmas antes/después) y un compañero con Blindside al lado.
