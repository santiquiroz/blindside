# Radar de equipo sin cinturón — plan de implementación

> **Para agentes:** cada tarea es autocontenida. Gradle NO corre en el sandbox de Muse: escribe con cuidado; el orquestador compila y prueba.

**Meta:** pestaña Equipo con radar de aliados y contactos del equipo, avisos hápticos de proximidad y publicación opcional de la posición propia.

**Spec:** `docs/superpowers/specs/2026-10-07-blindside-team-radar-design.md`

## Restricciones globales

- Kotlin 2.2, JUnit 5 (`org.junit.jupiter.api.Test`, `Assertions.*`; `assertNotNull` devuelve Unit → usar `requireNotNull`; `fail<Nothing>(...)`).
- Paquete del celular: `io.github.santiquiroz.blindside.phone`, raíz `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/`; tests en `watch/phone-app/src/test/kotlin/...` espejo.
- Ángulos: 0 = norte / arriba, positivo = horario. Usar `normalizedDeg`, `cardinalLabel` de `io.github.santiquiroz.blindside.shared.compass` y `bearingDeg`, `distanceM`, `GeoPoint` de `io.github.santiquiroz.blindside.shared.tactical`.
- Estilo: funciones pequeñas con nombre que dice lo que hacen, sin KDoc, datos inmutables, comentario de una línea solo para el porqué no obvio. Textos de UI en español.
- Prohibido a delegados: `git commit/push/reset/checkout/stash`.

---

### TR1 — Contactos del equipo, avisos de proximidad y evento de posición propia (puro)

**Crear:** `phone/tak/ContactBoard.kt`, `phone/tak/ProximityAlerts.kt`. **Modificar:** `phone/tak/CotXml.kt` (agregar `selfEvent`). **Tests:** `ContactBoardTest.kt`, `ProximityAlertsTest.kt`, ampliar `CotXmlTest.kt`.

Existe: `CotEvent(uid, type, callsign: String?, point: GeoPoint?, linkUid: String?, staleMs: Long? = null)` en `phone/tak/CotStream.kt`.

```kotlin
// ContactBoard.kt
const val CONTACT_DEFAULT_LIFE_MS = 30_000L
data class TeamContact(val uid: String, val label: String, val point: GeoPoint, val ageS: Int)
data class BoardEntry(val label: String, val point: GeoPoint, val receivedAtMs: Long, val expiresAtMs: Long)
data class ContactBoard(val entries: Map<String, BoardEntry> = emptyMap()) {
    fun with(event: CotEvent, ownUidPrefix: String, nowMs: Long): ContactBoard
    fun contacts(nowMs: Long): List<TeamContact>
}
```
Reglas: `t-x-d-d` con `linkUid` → quita esa entrada. Acepta solo `type` que empieza por `a-h-` o `a-u-`, `point` no nulo y distinto de (0,0), `staleMs` nulo o ≥ `nowMs`, `uid` que no empieza por `ownUidPrefix`. `label = callsign?.takeIf { it.isNotBlank() } ?: "Contacto"`. `expiresAtMs = staleMs ?: nowMs + CONTACT_DEFAULT_LIFE_MS`. Reemplaza por uid. `with` poda entradas con `expiresAtMs < nowMs`. `contacts(now)`: entradas no vencidas, `ageS = ((now - receivedAtMs)/1000).toInt()`, ordenadas por uid.

```kotlin
// ProximityAlerts.kt
const val PROXIMITY_ALERT_M = 30.0
const val ALERT_REPEAT_MS = 30_000L
data class ProximityAlert(val uid: String, val label: String, val distanceM: Double, val bearingDeg: Double)
data class AlertBook(val lastAlertMs: Map<String, Long> = emptyMap())
data class AlertRound(val book: AlertBook, val alerts: List<ProximityAlert>)
fun dueAlerts(book: AlertBook, contacts: List<TeamContact>, here: GeoPoint?, nowMs: Long): AlertRound
fun alertText(alert: ProximityAlert): String
```
Reglas: `here == null` → sin avisos, libro igual. Contacto con `distanceM(here, point) <= 30.0` y (sin aviso previo o `nowMs - last >= 30_000`) → aviso; el libro guarda `nowMs` para ese uid. El libro poda uids sin aviso en los últimos 5 min. Avisos ordenados por distancia. `alertText` = `"Contacto a ${distancia redondeada} m al ${cardinalLabel(bearingDeg)} · ${label}"`, p. ej. `"Contacto a 22 m al NE · Radar Santi 1"`.

```kotlin
// CotXml.kt (agregar, misma forma que contactEvent)
fun selfEvent(uid: String, callsign: String, at: GeoPoint, ceM: Double, nowMs: Long): String
```
Plantilla exacta: `<event version="2.0" uid="U" type="a-f-G-U-C" how="m-g" time="T" start="T" stale="T+30s"><point lat="LAT" lon="LON" hae="9999999.0" ce="CE" le="9999999.0"/><detail><contact callsign="CS"/><takv device="Blindside" platform="Blindside" os="Android" version="1"/></detail></event>` (coordenadas `%.7f`, ce `%.1f`, atributos de texto por `xmlAttr`; reusar los helpers privados de CotXml.kt).

Tests: aceptar `a-h-G` y `a-u-G`; rechazar `a-f-G-U-C`, propio (`BLINDSIDE-santi-C1` con prefijo `BLINDSIDE-santi-`), vencido, (0,0); `t-x-d-d` borra; vence por `stale` y por 30 s; avisos: a 20 m sí, a 40 m no, repetido a los 10 s no, a los 31 s sí, `here` null nada; texto exacto; `selfEvent` string exacto y parsea con `parseCotEvent`.

---

### TR2 — Geometría del radar y rumbo del celular (puro)

**Crear:** `phone/ui/team/TeamRadarMath.kt`, test `phone/ui/team/TeamRadarMathTest.kt`.

```kotlin
val RANGE_PRESETS_M = listOf(50.0, 100.0, 250.0)
fun nextRange(currentM: Double): Double                      // 50→100→250→50; valor desconocido → 100
enum class MarkKind { ALLY, CONTACT }
data class RadarMark(val kind: MarkKind, val label: String, val screenAngleDeg: Double,
                     val radiusFraction: Double, val offScale: Boolean, val distanceM: Double, val ageS: Int)
fun teamMarks(here: GeoPoint, headingDeg: Double, rangeM: Double, mates: List<Mate>, contacts: List<TeamContact>): List<RadarMark>
fun contactAlpha(ageS: Int): Float                           // 1.0 hasta 5 s, lineal a 0.3 a los 30 s, 0.3 después
fun phoneHeadingDeg(rotationMatrix: FloatArray): Double      // matriz 3x3 fila mayor (SensorManager.getRotationMatrixFromVector)
```
Reglas: `screenAngleDeg = normalizedDeg(bearingDeg(here, p) - headingDeg)`; `radiusFraction = min(d / rangeM, 1.0)`; `offScale = d > rangeM`. Aliados: `label = mateLabel(callsign)` (de `io.github.santiquiroz.blindside.shared.tak`), `ageS = mate.ageS`; contactos: `label = contact.label`. Primero aliados, luego contactos.
`phoneHeadingDeg`: en la matriz R (filas = Este, Norte, Arriba; columnas = ejes X, Y, Z del aparato), el eje Y del aparato en el mundo es `(R[1], R[4])` horizontal y el "atrás" (−Z) es `(-R[2], -R[5])`. Se elige el de mayor norma horizontal y se devuelve `normalizedDeg(toDegrees(atan2(este, norte)))`.

Tests: aliado justo al norte con rumbo 0 → ángulo 0; con rumbo 90 → 270; contacto a 30 m con escala 50 → fracción 0.6, no fuera; a 80 m → fracción 1, fuera de escala; `nextRange` ciclo; `contactAlpha(0)=1`, `(30)=0.3`; `phoneHeadingDeg` con identidad (plano, arriba al norte) → 0; plano girado al este (R = rotación de −90° alrededor de Z, eje Y→Este) → 90; levantado mirando al este (eje Y hacia arriba, −Z hacia el Este) → 90.

---

### TR3 — Servicio, estado y ajustes (Android)

**Modificar:** `phone/tak/TakPrefs.kt` (+ `publishSelf: Boolean = false`, `proximityAlerts: Boolean = true`, claves DataStore nuevas), `phone/tak/TakStore.kt` (+ `data class TeamPicture(val self: GeoFix?, val mates: List<Mate>, val contacts: List<TeamContact>)` y campo `picture: TeamPicture? = null` en `TakUiState`), `phone/tak/TakLinkService.kt`.

En `TakLinkService` (bajo el mismo `Mutex` que ya protege el estado):
- `board: ContactBoard` actualizado en `collectIncoming` junto a `roster`, con `ownUidPrefix = "BLINDSIDE-${ids.deviceId}-"`.
- Bucle de 1 s nuevo: arma `TeamPicture(fix fresco ≤15 s o null, roster.mates(now), board.contacts(now))` → `TakStore.update { it.copy(picture = …) }`; si `prefs.proximityAlerts`, `dueAlerts(alertBook, contacts, fix?.point, now)` → por cada aviso: vibración `VibrationEffect.createWaveform(longArrayOf(0, 200, 120, 200), -1)` (Vibrator de `getSystemService(VibratorManager::class.java).defaultVibrator`) y notificación en canal `blindside_tak_alerts` ("Avisos del equipo", `IMPORTANCE_HIGH`), id = `ALERT_BASE_ID + (uid.hashCode() and 0xFFF)`, texto `alertText(aviso)`, autoCancel.
- Cada 5 s, si `prefs.publishSelf` y fix fresco: `link.send(selfEvent("BLINDSIDE-${ids.deviceId}-SA", ids.callsign, fix.point, fix.accuracyM, now))`.
- `shutdown()` limpia `board` y `alertBook`; `TakUiState` nuevo sin `picture`.

Test: ampliar `TakStoreTest` si se agrega lógica pura; lo demás se verifica en el celular.

---

### TR4 — Radar en la pestaña Equipo (Compose)

**Crear:** `phone/ui/team/TeamRadar.kt` (Canvas), `phone/ui/team/PhoneHeading.kt`. **Modificar:** `phone/ui/team/TeamTab.kt`.

- `@Composable fun rememberPhoneHeading(active: Boolean): State<CompassReading?>`: `TYPE_ROTATION_VECTOR` a `SENSOR_DELAY_UI`, `SensorManager.getRotationMatrixFromVector(m, values)` → `phoneHeadingDeg(m)`, suavizado con `smoothedHeadingDeg`, confianza con `compassTrust(event.accuracy)`; se desregistra al salir (DisposableEffect / LifecycleStartEffect).
- `@Composable fun TeamRadar(picture: TeamPicture, heading: CompassReading?, rangeM: Double, onTap: () -> Unit, modifier: Modifier)`: fondo `#000`; 3 anillos (`BlindsideColors.Text2` tenue) con etiqueta en metros; letra N en `screenAngle = normalizedDeg(0 - heading)`; triángulo blanco al centro apuntando arriba; aliados = círculo `BlindsideColors.Ally` + texto `"${label} ${distancia} m"`; contactos = círculo `BlindsideColors.Warn` con alpha `contactAlpha(ageS)`; fuera de escala = triángulo en el borde. Tocar → `onTap` (cambia escala). `picture.self == null` → texto centrado "Sin GPS: esperando posición" y sin marcas; `heading == null || trust != GOOD` → texto "Brújula: calibra (mueve en 8)" (constante `CALIBRATE_WARNING`) arriba. Ángulo de pantalla θ → x = cx + r·sin θ, y = cy − r·cos θ.
- `TeamTab`: si `takState.running && takState.picture != null` → arriba `TeamRadar` cuadrado de ancho completo + texto de escala "Escala: 100 m (toca para cambiar)"; la escala vive en `rememberSaveable` (default 100). Debajo lo de hoy, más dos interruptores: "Avisos por vibración (contactos a 30 m)" y "Publicar mi posición (solo si no usas ATAK)". Mantener la pantalla encendida mientras el radar está visible (buscar el helper existente en `phone/ui/common/ScreenEffects.kt`; si no hay, `DisposableEffect` con `FLAG_KEEP_SCREEN_ON` de la ventana de la Activity). Reemplazar el texto fijo "Tu posición la publica ATAK…" por: "Si usas ATAK, él publica tu posición; si no, activa \"Publicar mi posición\"."

---

### TR5 — Pruebas de campo, docs y release (orquestador)

- `tak/tak-probe.py --contact lat,lon` (contacto `a-u-G` de otro "cinturón").
- README (en/es) y `docs/tak-server.md`: sección "Compañeros sin cinturón".
- E2E en el celular de Santiago; release v1.1.0 con APKs.
