# Filtro Doppler y aliado probable — plan de implementación

> **Para agentes:** tareas autocontenidas; Gradle NO corre en el sandbox de Muse (el orquestador compila y prueba). Lee el spec primero.

**Spec:** `docs/superpowers/specs/2026-10-07-blindside-doppler-iff-design.md`

## Restricciones globales

- Raíz Gradle `watch/`. Kotlin 2.2, JUnit 5 (`assertNotNull` devuelve Unit → `requireNotNull`; `fail<Nothing>(...)`).
- Convenciones: rumbos 0 = al frente / norte, positivo = horario; marco del cuerpo x = derecha, y = al frente (`Point2.bearingDeg = atan2(x, y)`); `radialSpeedMps` positivo = se aleja.
- Funciones pequeñas con nombre que dice lo que hacen, sin KDoc, datos inmutables, comentario de una línea solo para el porqué no obvio. Textos de UI en español.
- Prohibido a delegados: `git commit/push/reset/checkout/stash`. Cada tarea toca solo sus archivos.
- UUID de la baliza de equipo: `6f1b5a2e-8c1d-4f2a-9b3e-5b1d5e7a0c42` (servicio BLE), datos de servicio = id de 4 bytes big-endian.

---

### D1 — Filtro Doppler y confirmación al caminar (radar-core) + interruptor

**radar-core** (`watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/`):
```kotlin
// config/TuningParams.kt
data class DopplerParams(
    val enabled: Boolean = true, val windowMs: Long = 1_000, val minDetections: Int = 4,
    val outlierMps: Double = 0.3, val maxRmsMps: Double = 0.2, val minSpeedMps: Double = 0.2,
    val maxSpeedMps: Double = 2.5, val minBearingSpreadDeg: Double = 20.0,
    val staticToleranceMps: Double = 0.25, val minRangeM: Double = 1.0,
)
// TuningParams gana `val doppler: DopplerParams = DopplerParams()` (al final)

// imu/EgoMotion.kt (nuevo)
data class EgoSample(val tMs: Long, val bearingDeg: Double, val radialMps: Double)
data class EgoMotion(val samples: List<EgoSample> = emptyList()) {
    fun with(detections: List<Detection>, tMs: Long, params: DopplerParams): EgoMotion   // agrega (rumbo en marco del cuerpo, radial) de detecciones con radarRangeM ≥ minRangeM; poda < tMs − windowMs
    fun velocity(params: DopplerParams): Point2?                                          // (vx, vy) m/s o null según el spec §2
}
fun expectedStaticRadialMps(velocity: Point2, bearingDeg: Double): Double                // −(vx·sinθ + vy·cosθ)
fun isStaticEcho(detection: Detection, velocity: Point2, params: DopplerParams): Boolean  // |radial − esperado| ≤ staticToleranceMps, θ = detection.bodyPoint.bearingDeg
```
Ajuste: mínimos cuadrados 2×2 sobre `radial = −(vx sinθ + vy cosθ)`; descarta residuos > `outlierMps`, reajusta; `null` si quedan < `minDetections`, si el RMS > `maxRmsMps`, si `|v|` fuera de [`minSpeedMps`, `maxSpeedMps`], si el rango de rumbos < `minBearingSpreadDeg` o si el sistema es singular.

`PipelineState` gana `ego: EgoMotion = EgoMotion()` y `walkScanSinceMs: Long? = null`. En `PacketIngest.withFrame`: actualizar `ego` con las detecciones del cuadro filtrado; `walkingScan = doppler.enabled && motion.isWalking(t) && !motion.isTurning() && velocity != null`; si `walkingScan`, quitar del cuadro las detecciones `isStaticEcho` (sumar en un contador nuevo `PipelineCounters.clutterRejected`); `walkScanSinceMs` = primer t en que `walkingScan` fue verdadero (null cuando deja de serlo). `motionContext`: si `walkingScan` → `MotionContext(moving = false, gateOpenFromMs = walkScanSinceMs)`; si no, lo de hoy.

Tests (`radar-core/src/test/...`): `EgoMotionTest` (velocidad conocida (0, 1.2) con detecciones a −40°, 0°, +35°, +60° → recupera ±0.05; signo invertido → null; 3 detecciones → null; todos al mismo rumbo → null); escenarios: `walkingTowardWall` sigue sin confirmar; `rivalWhileWalking` alerta una vez **antes** de que el jugador se detenga con doppler encendido y después con doppler apagado (actualiza el test MVP existente para cubrir ambos); escenario nuevo en `sim/Scenarios.kt` `treesWhileCrossingRival` (árboles quietos a los lados, jugador caminando 1.0 m/s, rival cruzando lateral a 0.8 m/s) → 1 confirmación, 0 confirmaciones de árboles.

**Interruptor:** `android-shared/.../settings/AppSettings.kt` gana `dopplerFilter: Boolean = true`; persistirlo como los demás campos del reloj (ver `SettingsPreferences.kt`); `PipelineConfigMapping.toPipelineConfig` pone `tuning = TuningParams(doppler = DopplerParams(enabled = settings.dopplerFilter))` (conservando lo demás que ya arme). Pantalla de ajustes del reloj (`wear-app/.../ui/SettingsScreen.kt`): interruptor "Filtro de fantasmas al caminar".

---

### D2 — Aliado probable, baliza en el mensaje y gancho háptico (android-shared)

```kotlin
// tak/TakMessages.kt: TeamUpdate(val self: GeoFix?, val mates: List<Mate>, val me: Long? = null)
//   JSON "me": número o ausente/null (compatibilidad hacia atrás: ausente → null)

// tak/AllyHints.kt (nuevo)
const val TEAM_BEACON_UUID = "6f1b5a2e-8c1d-4f2a-9b3e-5b1d5e7a0c42"
const val ALLY_GPS_RADIUS_M = 15.0
const val ALLY_SECTOR_DEG = 45.0
const val ALLY_MATE_MAX_AGE_S = 15
const val BEACON_NEAR_RSSI_DBM = -70.0
const val BEACON_FRESH_MS = 5_000L
const val BEACON_CONTACT_RANGE_M = 4.0
data class BeaconSeen(val rssiDbm: Double, val lastSeenMs: Long)
fun smoothedBeacon(previous: BeaconSeen?, rssiDbm: Int, nowMs: Long): BeaconSeen          // EMA α = 0.3
fun nearBeaconCount(beacons: Map<Long, BeaconSeen>, ownBeacon: Long?, nowMs: Long): Int
fun likelyAllyIds(blips: List<Blip>, mates: List<Mate>, here: GeoPoint?, bodyHeadingDeg: Double?,
                  beacons: Map<Long, BeaconSeen>, ownBeacon: Long?, nowMs: Long): Set<Int>
fun likelyAllyIdsOf(state: SessionUiState, nowMs: Long, nowNanos: Long): Set<Int>          // arma los argumentos desde el estado
```
Reglas (spec §3): **GPS** — para cada compañero con `ageS ≤ 15` y `distanceM(here, punto) ≤ 15`: `rel = normalizedDeg(bearingDeg(here, punto) − bodyHeadingDeg)`; `S_m` = blips con diferencia angular ≤ 45° a `rel`; `k_m` = compañeros válidos cuyo `rel` está a ≤ 45° del de m (incluye a m); si `|S_m| ≤ k_m` se marcan todos los de `S_m`, si no ninguno. Sin `here` o sin `bodyHeadingDeg` → nada por GPS. **BLE** — `cercanos` = blips con `rangeM ≤ 4`; si no está vacío y `cercanos.size ≤ nearBeaconCount(...)` se marcan todos. Resultado = unión. `likelyAllyIdsOf`: blips de `state.scene`, compañeros de `state.team` solo si `teamLinkActive(state.teamAtMs, nowMs)`, `here = hereOf(state.team, state.teamAtMs, nowMs, null)`, rumbo = `bodyHeadingDeg(state.headingAnchor, scene.bodyYawDeg, scene.yawFromBelt, nowNanos)`, balizas de `state.beacons`, propia = `state.team?.me`.

- `session/SessionStore.kt`: `SessionUiState` gana `beacons: Map<Long, BeaconSeen> = emptyMap()` (al final); `fun sawBeacon(id: Long, rssiDbm: Int, nowMs: Long)` (suaviza con `smoothedBeacon`, poda las de > 30 s).
- `haptics/HapticPattern.kt`: `val ALLY_PATTERN` = un pulso corto suave (40 ms) con la misma forma de datos que los patrones existentes.
- `session/SessionEngine.kt`: parámetro nuevo al final `allyHint: (Int) -> Boolean = { false }`; en la reproducción de un `ContactAlert`, si `allyHint(alert.displayId)` → `ALLY_PATTERN` en vez de `hapticFor(alert)`. Donde se construye el engine (`session/RunningSession.kt`), pasar `{ id -> id in likelyAllyIdsOf(SessionStore.state.value, System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos()) }`.
- `tak/TelemetryOf.kt`: `telemetryOf(scene, headingDeg, points, excludeIds: Set<Int> = emptySet())` omite esos blips.

Tests: `AllyHintsTest` (compañero solo en el sector → marcado; compañero + 2 blips en el sector → ninguno; dos compañeros juntos + 2 blips → ambos; sin rumbo → nada por GPS; compañero a 20 m → nada; 1 baliza cercana + 1 blip a 3 m → marcado; 1 baliza + 2 blips cercanos → ninguno; baliza propia ignorada; baliza vieja de 6 s ignorada), `TakMessagesTest` (`me` ida y vuelta; JSON sin `me` → null), `SessionEngineTest` (con `allyHint` verdadero suena `ALLY_PATTERN`), `TelemetryOfTest` (`excludeIds`).

---

### D3 — Reloj: escáner de balizas, dibujo del aliado probable, telemetría sin aliados (wear-app + radar del canvas compartido)

- `wear-app/.../wear/ble/TeamBeaconScanner.kt` (nuevo): `suspend fun scanTeamBeacons(context: Context, clockMs: () -> Long)` — `BluetoothLeScanner.startScan` con `ScanFilter` por servicio `ParcelUuid.fromString(TEAM_BEACON_UUID)` y `ScanSettings.SCAN_MODE_BALANCED`; en cada resultado lee `scanRecord.getServiceData(uuid)` (4 bytes big-endian → `Long` sin signo) y llama `SessionStore.sawBeacon(id, result.rssi, clockMs())`; detiene el escaneo al cancelarse la corrutina (`suspendCancellableCoroutine` / `try { awaitCancellation() } finally { stopScan }`). Sin permiso `BLUETOOTH_SCAN` o sin adaptador → retorna sin error. Agregarlo a `WearSession.launchCompanions`.
- `wear-app/.../wear/bridge/TakTelemetryPublisher.kt`: pasar `excludeIds = likelyAllyIdsOf(session, nowMs, nowNanos)` a `telemetryOf`.
- Dibujo: `android-shared/.../shared/radar/RadarCanvas.kt` (y lo mínimo en `RadarColors.kt` si hace falta) acepta `allyIds: Set<Int> = emptySet()`; esos blips se dibujan con `BlindsideColors.Ally` y un "?" pequeño junto al punto. `wear-app/.../ui/radar/RadarScreen.kt` calcula `likelyAllyIdsOf(session, now, elapsedRealtimeNanos())` y lo pasa.

---

### D4 — Celular: baliza BLE de equipo y `me` en `/tak/team` (phone-app)

- `phone/tak/TeamBeacon.kt` (nuevo): `fun beaconIdOf(deviceId: String): Long` (CRC32 de los bytes UTF-8, 32 bits sin signo) + test; `class TeamBeaconAdvertiser(context)` con `start(id: Long): Boolean` / `stop()`: `BluetoothLeAdvertiser` con `AdvertiseSettings` (`ADVERTISE_MODE_BALANCED`, `ADVERTISE_TX_POWER_MEDIUM`, no conectable), `AdvertiseData` con `addServiceUuid` y `addServiceData(uuid, 4 bytes big-endian del id)`, sin nombre del dispositivo. Sin permiso `BLUETOOTH_ADVERTISE` o sin adaptador → `false` sin error.
- `phone/tak/TakLinkService.kt`: al arrancar el enlace, `advertiser.start(beaconIdOf(ids.deviceId))`; al parar, `stop()`. El bucle de equipo manda `TeamUpdate(fresh, mates, me = beaconIdOf(ids.deviceId))`.
- Manifiesto: `<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE"/>`. `phone/ui/team/TeamTab.kt`: `takPermissions()` agrega `BLUETOOTH_ADVERTISE` (API 31+); si se niega, el enlace igual arranca.
