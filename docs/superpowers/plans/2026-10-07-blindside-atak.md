# Blindside × ATAK — plan de implementación

> **Para agentes:** ejecutar tarea por tarea. Cada tarea es autocontenida: lee solo los archivos que nombra. Pasos con casillas `- [ ]`.

**Meta:** Blindside publica en un servidor TAK los contactos del radar y los puntos tácticos, y muestra en el reloj a los compañeros.

**Arquitectura:** reloj → `/tak/telemetry` (1 Hz, MessageClient) → servicio "Enlace TAK" del celular → TLS mutuo :8089 → OpenTAKServer. Del servidor vuelven los compañeros → `/tak/team` (2 s) → cuñas en el anillo del reloj.

**Stack:** Kotlin 2.2, AGP 8.10, Compose/Wear Compose, JUnit 5, `MiniJson` (radar-core) para JSON, `java.util.zip`, `javax.net.ssl`, `javax.xml.parsers` (DOM). Sin dependencias nuevas.

**Spec:** `docs/superpowers/specs/2026-10-07-blindside-atak-design.md`

## Restricciones globales

- Raíz Gradle: `watch/`. Tests: `./gradlew :radar-core:test`, `:android-shared:testDebugUnitTest`, `:phone-app:testDebugUnitTest`, `:wear-app:testDebugUnitTest`. Build: `:phone-app:assembleDebug :wear-app:assembleDebug`.
- JDK 17: `C:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot` (usar `JAVA_HOME` si gradle no lo encuentra).
- Tests JUnit 5 (`org.junit.jupiter.api.Test`, `Assertions.*`). Nada de `kotlin.test`.
- Estilo del repo: funciones pequeñas con nombre que dice lo que hacen, sin KDoc, comentario de una línea solo para el porqué no obvio, datos inmutables (`copy`).
- Convención de ángulos del proyecto: 0 = al frente, positivo = horario (derecha). `YawTracker` sube al girar a la derecha. Rumbo de brújula: 0 = norte, horario.
- Callsigns únicos por dispositivo: OpenTAKServer descarta en silencio un callsign ya conocido bajo otro uid.
- Prohibido en el repo: IPs públicas reales, contraseñas, paquetes `.zip`/`.p12` reales. Los fixtures de prueba se generan con `keytool` y son solo de prueba.
- Textos de UI en español. Prohibido `git commit`/`push`/`reset`/`checkout` a los agentes delegados: el orquestador commitea.
- Uids propios siempre empiezan por `BLINDSIDE-`.

## Foco de revisión

1. Flujo TCP de TAK con eventos partidos entre lecturas y basura entre eventos → no se pierde ni duplica ningún evento.
2. Paquete de OTS para ATAK (zip dentro de zip) y para iTAK (plano) → ambos se importan.
3. Sin rumbo o sin GPS → se borran del mapa los contactos ya publicados (evento `t-x-d-d`), no quedan congelados.
4. El celular deja el enlace prendido y el reloj termina la sesión → el reloj deja de mandar telemetría; el celular no se cae.
5. Callsign con `&`, `<` o comillas → XML válido.

---

### Tarea 1: yaw del cuerpo en la escena (radar-core)

**Archivos:**
- Modificar: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/scene/RadarScene.kt`
- Modificar: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/scene/SceneBuilder.kt`
- Modificar: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/RadarPipeline.kt`
- Test: el test de pipeline existente que mejor use el simulador (buscar en `watch/radar-core/src/test/kotlin/.../core/` uno que use `sim/Scenarios.kt` o `simulate(`), o uno nuevo `core/SceneYawTest.kt`.

**Interfaces producidas:**
```kotlin
data class RadarScene(
    /* campos actuales sin cambios */
    val bodyYawDeg: Double = 0.0,
    val yawFromBelt: Boolean = false,
)
data class SceneInputs(/* actuales */ val yawFromBelt: Boolean = false)
```

- [ ] Agregar los dos campos al final de `RadarScene` con esos valores por defecto (las 10 construcciones existentes en tests siguen compilando).
- [ ] `SceneInputs` gana `yawFromBelt: Boolean = false` al final; `buildScene` llena `bodyYawDeg = inputs.yawDeg` y `yawFromBelt = inputs.yawFromBelt`.
- [ ] `RadarPipeline.scene(...)` pasa `yawFromBelt = linkUp && !state.motion.turnFromWatch`.
- [ ] Tests (escribir primero, verlos fallar):
  - escenario simulado con giro a la derecha de 90° y las IMUs del cinturón vivas → `scene.bodyYawDeg` sube ~90 (tolerancia 10°) y `yawFromBelt == true`;
  - mismo pipeline sin enlace (o sin IMUs usables, giro desde el reloj) → `yawFromBelt == false`;
  - `buildScene` con `yawDeg = 33.0, yawFromBelt = true` → la escena trae `33.0` y `true`.
- [ ] `cd watch && ./gradlew :radar-core:test` en verde.

---

### Tarea 2: mensajes TAK, rumbo y compañeros (android-shared)

**Archivos:**
- Modificar: `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/tactical/TacticalGeo.kt`
- Crear en `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/tak/`: `TakMessages.kt`, `BodyHeading.kt`, `TelemetryOf.kt`, `TeamView.kt`
- Tests en `watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/tak/` (uno por archivo) y `.../tactical/TacticalGeoTest.kt` (crear o ampliar).

**Consume:** `MiniJson` (`io.github.santiquiroz.blindside.core.protocol.MiniJson`: `parseOrNull`, `quote`, `obj`, `array`), `longField` de `shared/bridge/JsonFields.kt` (es `internal` del módulo: usable), `GeoPoint`, `bearingDeg`, `distanceM`, `TacticalKind`, `normalizedDeg` (`shared/compass`), `RadarScene`, `Blip`, `Confidence`. **No depende de la Tarea 1**: `telemetryOf` recibe el rumbo ya calculado.

**Interfaces producidas:**
```kotlin
// TacticalGeo.kt (agregar)
fun destinationOf(from: GeoPoint, bearingDeg: Double, distanceM: Double): GeoPoint  // gran círculo, radio 6_371_000

// tak/TakMessages.kt
const val TAK_TELEMETRY_PATH = "/tak/telemetry"
const val TAK_TEAM_PATH = "/tak/team"
const val TAK_TELEMETRY_PERIOD_MS = 1_000L
const val TAK_TEAM_PERIOD_MS = 2_000L
const val TAK_LINK_FRESH_MS = 10_000L
data class TelemetryBlip(val id: Int, val bearingDeg: Double, val rangeM: Double, val confidence: Confidence)
data class Telemetry(val headingOk: Boolean, val blips: List<TelemetryBlip>, val points: Map<TacticalKind, GeoPoint>)
data class GeoFix(val point: GeoPoint, val accuracyM: Double)
data class Mate(val callsign: String, val point: GeoPoint, val ageS: Int)
data class TeamUpdate(val self: GeoFix?, val mates: List<Mate>)
fun encodeTelemetry(telemetry: Telemetry): String
fun decodeTelemetry(json: String): Telemetry?
fun encodeTeamUpdate(update: TeamUpdate): String
fun decodeTeamUpdate(json: String): TeamUpdate?

// tak/BodyHeading.kt
const val HEADING_FRESH_NANOS = 3_000_000_000L
const val HEADING_BELT_HOLD_NANOS = 120_000_000_000L
data class HeadingAnchor(val offsetDeg: Double, val atNanos: Long)
fun anchorOf(frontHeadingDeg: Double, bodyYawDeg: Double, atNanos: Long): HeadingAnchor
fun bodyHeadingDeg(anchor: HeadingAnchor?, bodyYawDeg: Double, yawFromBelt: Boolean, nowNanos: Long): Double?

// tak/TelemetryOf.kt
fun telemetryOf(scene: RadarScene?, headingDeg: Double?, points: Map<TacticalKind, GeoPoint>): Telemetry

// tak/TeamView.kt
const val MATE_MAX_AGE_S = 60
const val MATE_MAX_DISTANCE_M = 1_000.0
const val MATE_MAX_SHOWN = 6
const val SELF_FIX_FRESH_MS = 15_000L
data class MateMark(val label: String, val bearingDeg: Double, val distanceM: Double)
fun teamLinkActive(lastTeamAtMs: Long?, nowMs: Long): Boolean
fun hereOf(team: TeamUpdate?, teamAtMs: Long?, nowMs: Long, watchFix: GeoPoint?): GeoPoint?
fun mateMarks(mates: List<Mate>, here: GeoPoint): List<MateMark>
fun mateLabel(callsign: String): String
```

**Formato JSON (exacto):**
- Telemetría: `{"v":1,"headingOk":true,"blips":[{"id":3,"bearing":127.5,"range":4.2,"conf":"BOTH"}],"points":{"BASE":[5.0689,-75.5174]}}`
- Equipo: `{"v":1,"self":{"lat":5.0689,"lon":-75.5174,"acc":4.0},"mates":[{"cs":"Toro","lat":5.0691,"lon":-75.517,"age":3}]}` (`"self":null` si no hay fix).
- Decoders: `null` si no es JSON, si `v != 1` o falta un campo obligatorio. Ítems mal formados (conf desconocido, clave de punto desconocida, mate sin `cs`) se saltan sin tumbar el mensaje. Los números pueden venir como Long o Double: leerlos con un helper privado `doubleField`.

**Reglas:**
- `anchorOf`: `offsetDeg = normalizedDeg(frontHeadingDeg - bodyYawDeg)`.
- `bodyHeadingDeg`: `null` si `anchor == null`. Edad = `nowNanos - anchor.atNanos`. Si edad ≤ `HEADING_FRESH_NANOS` → `normalizedDeg(offset + bodyYawDeg)`. Si `yawFromBelt` y edad ≤ `HEADING_BELT_HOLD_NANOS` → lo mismo. Si no, `null`. Edad negativa cuenta como fresca.
- `telemetryOf`: `headingOk = scene != null && headingDeg != null`. Si `headingOk`, blips = `scene.blips` sin `COASTING` ni `outOfView`, con `bearingDeg = normalizedDeg(headingDeg + blip.bearingDeg)`; si no, lista vacía. `points` pasa tal cual.
- `teamLinkActive`: `lastTeamAtMs != null && nowMs - lastTeamAtMs in 0..TAK_LINK_FRESH_MS`.
- `hereOf`: el `self` del equipo si el enlace está activo (≤ `SELF_FIX_FRESH_MS` desde `teamAtMs`) y `self != null`; si no, `watchFix`.
- `mateMarks`: solo `ageS <= 60` y distancia ≤ 1000 m; orden por distancia ascendente; máximo 6; `bearingDeg = bearingDeg(here, mate.point)`.
- `mateLabel`: primeras 2 letras o dígitos del callsign, en mayúsculas; si no hay ninguno, `"??"`. `"toro"` → `"TO"`, `"  ñu-7"` → `"ÑU"`, `"--"` → `"??"`.

- [ ] Tests primero, cada uno en rojo antes de implementar:
  - `destinationOf(p, 90.0, 100.0)` y vuelta: `distanceM(p, dest)` ≈ 100 (±0.01) y `bearingDeg(p, dest)` ≈ 90 (±0.01), con `p = GeoPoint(5.0689, -75.5174)`; también rumbos 0, 225 y distancia 0 (devuelve el mismo punto).
  - ida y vuelta de ambos códecs con valores no triviales (incluye `self = null`, `blips` vacío, los 3 `TacticalKind`); `decodeTelemetry("nope")`, `decodeTelemetry("{\"v\":2}")` → null; un blip con `"conf":"ZZZ"` se salta y el resto se conserva.
  - `bodyHeadingDeg`: ancla de 1 s sin cinturón → valor; de 10 s sin cinturón → null; de 10 s con cinturón → valor; de 121 s con cinturón → null; `anchorOf(10.0, 30.0, 0)` y yaw ahora 40 → 20.0; envoltura: `anchorOf(350.0, 0.0, 0)` y yaw 20 → 10.0.
  - `telemetryOf`: escena con un blip `BOTH` a +30°, uno `COASTING` y uno `outOfView`, rumbo 350 → un solo blip con `bearingDeg` 20.0; rumbo null → `headingOk=false`, sin blips, puntos intactos; escena null → `headingOk=false`.
  - `mateMarks`: 8 compañeros a distancias distintas → 6 más cercanos en orden; uno con `ageS=61` y otro a 1500 m quedan fuera.
  - `hereOf`: enlace fresco con self → self; enlace viejo → watchFix; self null → watchFix.
- [ ] `cd watch && ./gradlew :android-shared:testDebugUnitTest` en verde.

---

### Tarea 3: núcleo TAK puro del celular (CoT, flujo, roster, paquete, publicador)

**Archivos** (paquete `io.github.santiquiroz.blindside.phone.tak`, en `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/tak/`): `CotXml.kt`, `CotStream.kt`, `TeamRoster.kt`, `TakPackage.kt`, `TakPublisher.kt`. Tests espejo en `watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/tak/`.

Solo JVM estándar (nada de `android.*`): `java.time`, `java.util.zip`, `javax.xml.parsers`.

**Consume (Tarea 2):** `GeoPoint`, `destinationOf`, `TacticalKind`, `Telemetry`, `GeoFix`, `Mate`. Si la Tarea 2 aún no está, crear stubs locales NO: esperar o coordinar; los nombres de arriba son el contrato.

**Interfaces producidas:**
```kotlin
// CotXml.kt
fun cotTime(epochMs: Long): String                     // Instant.ofEpochMilli(ms).toString(), p. ej. "2026-10-07T22:31:05.123Z"
fun xmlAttr(value: String): String                     // escapa & < > " '
fun identityEvent(uid: String, callsign: String, appVersion: String, nowMs: Long): String
fun contactEvent(uid: String, callsign: String, at: GeoPoint, ceM: Double, nowMs: Long): String
fun deleteEvent(targetUid: String, targetType: String, nowMs: Long): String
fun markerEvent(uid: String, callsign: String, at: GeoPoint, argb: Int, nowMs: Long): String
fun pingEvent(uid: String, nowMs: Long): String

// CotStream.kt
class CotSplitter { fun feed(chunk: CharSequence): List<String> }
data class CotEvent(val uid: String, val type: String, val callsign: String?, val point: GeoPoint?, val linkUid: String?)
fun parseCotEvent(xml: String): CotEvent?

// TeamRoster.kt
data class RosterEntry(val callsign: String, val point: GeoPoint, val receivedAtMs: Long)
data class TeamRoster(val entries: Map<String, RosterEntry> = emptyMap()) {
    fun with(event: CotEvent, ownCallsign: String, nowMs: Long): TeamRoster
    fun mates(nowMs: Long): List<Mate>
}

// TakPackage.kt
class TakPackage(val host: String, val port: Int, val truststore: ByteArray, val truststorePassword: String,
                 val client: ByteArray, val clientPassword: String, val clientName: String)
sealed interface PackageResult {
    data class Ok(val pkg: TakPackage) : PackageResult
    data class Invalid(val reason: String) : PackageResult
}
fun readTakPackage(zip: ByteArray): PackageResult

// TakIds.kt (ya existe, escrito por el orquestador): data class TakIds(deviceId, callsign) con identityUid, pingUid,
// contactUid(id), markerUid(kind); y fun deviceIdOf(clientName: String): String.
// TakPublisher.kt
data class PublishState(val publishedUids: Set<String> = emptySet(), val lastMarkersMs: Long? = null)
data class Outgoing(val state: PublishState, val events: List<String>)
const val CONTACT_TYPE = "a-u-G"
const val FIX_MAX_AGE_MS = 10_000L
const val MARKER_PERIOD_MS = 30_000L
fun publishTelemetry(state: PublishState, telemetry: Telemetry, fix: GeoFix?, fixAgeMs: Long?,
                     ids: TakIds, publishContacts: Boolean, nowMs: Long): Outgoing
```

**Plantillas CoT (atributos en este orden, sin espacios extra, `<?xml?>` no se incluye):**
- Contacto: `<event version="2.0" uid="U" type="a-u-G" how="m-g" time="T" start="T" stale="T+10s"><point lat="LAT" lon="LON" hae="9999999.0" ce="CE" le="9999999.0"/><detail><contact callsign="CS"/><remarks>Blindside: contacto de radar, posición aproximada</remarks></detail></event>`
- Borrado: `<event version="2.0" uid="U-delete" type="t-x-d-d" how="h-g-i-g-o" time="T" start="T" stale="T+20s"><point lat="0.0" lon="0.0" hae="9999999.0" ce="9999999.0" le="9999999.0"/><detail><link uid="U" relation="none" type="TYPE"/><__forcedelete/></detail></event>`
- Marcador: `<event version="2.0" uid="U" type="b-m-p-s-m" how="h-g-i-g-o" time="T" start="T" stale="T+600s"><point lat="LAT" lon="LON" hae="9999999.0" ce="9999999.0" le="9999999.0"/><detail><contact callsign="CS"/><color argb="ARGB"/><remarks>Blindside: punto táctico</remarks></detail></event>`
- Identidad: `<event version="2.0" uid="U" type="a-f-G-E-S" how="h-g-i-g-o" time="T" start="T" stale="T+5s"><point lat="0.0" lon="0.0" hae="9999999.0" ce="9999999.0" le="9999999.0"/><detail><contact callsign="CS"/><takv device="Blindside" platform="Blindside" os="Android" version="V"/></detail></event>`
- Ping: `<event version="2.0" uid="U" type="t-x-c-t" how="h-g-i-g-o" time="T" start="T" stale="T+20s"><point lat="0.0" lon="0.0" hae="9999999.0" ce="9999999.0" le="9999999.0"/><detail/></event>`
- Coordenadas con `String.format(Locale.ROOT, "%.7f", v)`; `ce` con `"%.1f"`. Todo atributo de texto pasa por `xmlAttr`.

**Reglas:**
- `CotSplitter.feed`: acumula; devuelve cada `<event` … `</event>` completo, en orden; descarta lo anterior a `<event` (incluye prólogos `<?xml…?>`); un evento partido en varios `feed` sale entero una sola vez; si el búfer pasa de 262 144 caracteres sin cerrar un evento, se vacía.
- `parseCotEvent`: `null` si contiene `<!DOCTYPE` o `<!ENTITY`, si no parsea o si falta `uid`/`type`. DOM con `DocumentBuilderFactory` (`isNamespaceAware=false`, `isExpandEntityReferences=false`, intentar `setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)` dentro de `runCatching`). `callsign` = atributo de `detail/contact`; `point` desde `point@lat/lon` (null si no son números); `linkUid` = `detail/link@uid`.
- `TeamRoster.with`: si `type == "t-x-d-d"` y hay `linkUid` → quita esa entrada. Acepta solo `type` que empieza por `a-f-`, `callsign` no vacío, `point` no nulo y distinto de (0,0), `uid` que no empieza por `BLINDSIDE-` y callsign distinto (trim, sin mayúsculas) de `ownCallsign`. Reemplaza por uid. Poda entradas con más de 60 s.
- `TeamRoster.mates`: entradas de ≤ 60 s, `ageS = ((nowMs - receivedAtMs) / 1000).toInt()`, ordenadas por callsign.
- `readTakPackage`: recorre el zip y, recursivamente, zips internos (profundidad ≤ 2). Rechaza entradas de más de 1 MiB (`Invalid("El paquete es demasiado grande")`). El `.pref` es la primera entrada `*.pref` que contiene `connectString`; sus `entry` se leen por atributo `key`. `connectString0` = `host:puerto:protocolo`; protocolo ≠ `ssl` → `Invalid("El paquete no usa TLS (puerto ssl)")`. Contraseñas: `caPassword` o `caPassword0`, `clientPassword` o `clientPassword0`; por defecto `atakatak`. Truststore: el `.p12` cuyo nombre (sin carpeta) contiene `truststore`; cliente: el otro `.p12`; `clientName` = su nombre sin `.p12`. Lo que falte → `Invalid` con motivo en español ("Falta el certificado del jugador", "Falta el truststore del servidor", "Falta la configuración del servidor (.pref)", "No es un zip válido").
- `publishTelemetry`:
  - Contactos solo si `publishContacts && telemetry.headingOk && fix != null && fixAgeMs != null && fixAgeMs <= FIX_MAX_AGE_MS`. Por blip: `contactEvent(ids.contactUid(id), "Radar ${ids.callsign} $id", destinationOf(fix.point, bearingDeg, rangeM), ce, nowMs)` con `ce = round(fix.accuracyM + rangeM * sin(15°))` (mínimo 1.0).
  - Para cada uid de `state.publishedUids` que no salió en esta vuelta → `deleteEvent(uid, CONTACT_TYPE, nowMs)`.
  - Marcadores: si `points` no está vacío y (`lastMarkersMs == null` o pasaron ≥ 30 000 ms) → un `markerEvent` por punto, callsign `"Base X"`, `"Spawn X"`, `"Objetivo X"` (X = callsign), argb BASE `0xFF00C853.toInt()`, SPAWN `0xFF00B8D4.toInt()`, OBJECTIVE `0xFFFF1744.toInt()`; actualiza `lastMarkersMs`.
  - Orden de `events`: contactos, borrados, marcadores.

- [ ] Tests primero (en rojo), luego implementación:
  - cada plantilla: el string exacto para entradas fijas (`nowMs = 1_760_000_000_000`), y que `DocumentBuilderFactory` lo parsea; callsign `Tom & "Jerry" <1>` sale escapado y parsea.
  - `CotSplitter`: dos eventos en un solo chunk → 2; un evento partido en 3 chunks → 1 al final; basura y prólogo entre eventos → se ignoran; 300 000 caracteres de basura → búfer vacío y el siguiente evento sale bien.
  - `parseCotEvent`: SA de ATAK real (`a-f-G-U-C` con `contact callsign="Toro"`, `__group`, `takv`) → campos correctos; con `<!DOCTYPE` → null; sin `uid` → null.
  - `TeamRoster`: acepta un `a-f-G-U-C`; rechaza `a-h-G`, uid `BLINDSIDE-x-C1`, el propio callsign `santi` vs `Santi `, punto (0,0); `t-x-d-d` con link quita; entrada de 61 s desaparece de `mates`.
  - `readTakPackage`: zip iTAK plano y zip ATAK anidado (construidos en el test con `ZipOutputStream`, contenidos de `.p12` falsos `byteArrayOf(1,2,3)`) → `Ok` con host, puerto, contraseñas y `clientName` correctos; `connectString0` con `:tcp` → `Invalid`; sin `.pref` → `Invalid`; bytes que no son zip → `Invalid`.
  - `publishTelemetry`: con heading y fix fresco → 1 contacto con uid y callsign esperados y lat/lon = `destinationOf`; la vuelta siguiente sin ese blip → un borrado de ese uid; `fixAgeMs = 10_001` → cero contactos y borrado de lo publicado; `publishContacts=false` → igual; marcadores la primera vez y no a los 10 s, sí a los 30 s.
- [ ] `cd watch && ./gradlew :phone-app:testDebugUnitTest --tests "*tak*"` en verde.

---

### Tarea 4: enlace TLS del celular (conexión, ping, reconexión)

**Archivos:** `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/tak/TakTls.kt`, `TakLink.kt`; tests `.../phone/tak/TakTlsTest.kt`, `TakLinkTest.kt`; fixtures en `watch/phone-app/src/test/resources/tak/`.

**Consume (Tarea 3):** `TakPackage`, `TakIds`, `identityEvent`, `pingEvent`, `CotSplitter`, `parseCotEvent`, `CotEvent`.

**Interfaces producidas:**
```kotlin
// TakTls.kt
class TakSetupException(message: String) : Exception(message)
fun sslContextOf(pkg: TakPackage): SSLContext
fun interface TakConnector { fun open(): Socket }
fun tlsConnector(pkg: TakPackage, context: SSLContext): TakConnector

// TakLink.kt
sealed interface LinkStatus {
    data object Idle : LinkStatus
    data object Connecting : LinkStatus
    data class Connected(val sinceMs: Long) : LinkStatus
    data class Retrying(val inMs: Long, val reason: String) : LinkStatus
}
fun backoffMs(failures: Int): Long
const val PING_PERIOD_MS = 15_000L
const val SILENCE_LIMIT_MS = 45_000L
const val STABLE_AFTER_MS = 60_000L
class TakLink(private val connector: TakConnector, private val ids: TakIds, private val appVersion: String,
              private val clock: () -> Long = System::currentTimeMillis) {
    val status: StateFlow<LinkStatus>
    val incoming: SharedFlow<CotEvent>
    fun send(event: String)
    suspend fun run()
}
```

**Reglas:**
- `sslContextOf`: cliente = `KeyStore.getInstance("PKCS12")` con `clientPassword` → `KeyManagerFactory(getDefaultAlgorithm())`. Confianza: carga el truststore PKCS12 con `truststorePassword`, junta los `getCertificate(alias)` no nulos de **todos** los alias; si no hay ninguno → `TakSetupException("El truststore del paquete no trae certificados")`; arma un `KeyStore.getInstance(KeyStore.getDefaultType())` vacío con `setCertificateEntry("ca$i", cert)` → `TrustManagerFactory`. Error de carga del p12 → `TakSetupException("El certificado no se pudo abrir (formato o contraseña)")`. `SSLContext.getInstance("TLS")`.
- `tlsConnector`: `context.socketFactory.createSocket()`, `connect(InetSocketAddress(host, port), 10_000)`, `soTimeout = 1_000`, `startHandshake()`. Sin verificación de hostname a propósito: solo se aceptan certificados firmados por la CA del paquete (comentario de una línea).
- `backoffMs`: 0→2 000, 1→4 000, 2→8 000, 3→16 000, ≥4→30 000.
- `run()` (en `Dispatchers.IO`, hasta cancelación): `Connecting` → `connector.open()`; al abrir escribe primero `identityEvent(ids.identityUid, "${ids.callsign} radar", appVersion, now)`, luego `Connected(now)`. Lector: lee chars (`InputStreamReader` UTF-8) con `soTimeout` 1 s (el timeout no es error), pasa por `CotSplitter`, parsea y emite en `incoming`; recuerda la hora del último dato. Escritor: drena un `Channel<String>(64, BufferOverflow.DROP_OLDEST)` y escribe+flush. Ping `pingEvent(ids.pingUid, now)` cada 15 s. Si pasan 45 s sin datos recibidos, o falla lectura/escritura/conexión → cierra el socket, vacía el canal (los eventos viejos no se reenvían), `Retrying(backoffMs(fallos), motivo)`, espera y reintenta. Si la conexión duró ≥ 60 s, `fallos` vuelve a 0. Cancelación → cierra y `Idle`.
- `send` nunca bloquea ni lanza.

**Fixtures (generar una vez con keytool de JDK 17 y commitear; contraseña `atakatak`, solo de prueba):**
```bash
KT="/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin/keytool"
D=watch/phone-app/src/test/resources/tak
"$KT" -genkeypair -alias ca -keyalg RSA -keysize 2048 -dname CN=test-ca -ext bc:c -validity 36500 -keystore $D/ca.p12 -storetype PKCS12 -storepass atakatak
"$KT" -exportcert -alias ca -keystore $D/ca.p12 -storepass atakatak -rfc -file $D/ca.pem
"$KT" -importcert -noprompt -alias ca -file $D/ca.pem -keystore $D/truststore-root.p12 -storetype PKCS12 -storepass atakatak
# server y player: genkeypair, certreq, gencert firmado por ca (-validity 36500), importcert de ca.pem y del cert firmado en su propio p12
```
(Resultado: `truststore-root.p12`, `server.p12` CN=opentakserver, `player.p12` CN=player, todos firmados por `ca`.)

- [ ] Tests primero:
  - `TakTlsTest`: `sslContextOf` con los fixtures → handshake mutuo contra un `SSLServerSocket` local en puerto efímero que exige cliente (`needClientAuth = true`) con `server.p12`; contraseña mala → `TakSetupException`; truststore que es un p12 de solo llave sin certificados de confianza → `TakSetupException`.
  - `TakLinkTest` con `TakConnector` que abre un `Socket` plano a un `ServerSocket` local: lo primero que llega al servidor es la identidad (`uid="BLINDSIDE-abc123"`, `callsign="Santi radar"`); `send(contacto)` llega al servidor; el servidor escribe un SA `a-f-G-U-C` partido en 2 escrituras → `incoming` lo emite una vez; el servidor cierra → `status` pasa a `Retrying(2000, …)` y vuelve a conectar (la segunda conexión vuelve a mandar identidad primero). Usar `clock` falso y `kotlinx-coroutines-test` solo si ya está en el classpath de test; si no, tiempos reales cortos con `withTimeout`.
  - `backoffMs` para 0..6.
- [ ] `cd watch && ./gradlew :phone-app:testDebugUnitTest --tests "*tak*"` en verde.

---

### Tarea 5: servicio "Enlace TAK" y pestaña "Equipo" (celular)

**Archivos:**
- Crear: `phone/tak/TakPrefs.kt`, `phone/tak/TakStore.kt`, `phone/tak/TakLinkService.kt`, `phone/tak/TakLocation.kt`, `phone/ui/team/TeamTab.kt` (todo bajo `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/`)
- Modificar: `phone/nav/PhoneNav.kt` (`PhoneTab` + `TEAM`), `phone/ui/PhoneApp.kt` (rama `TEAM -> TeamTab`), `phone/ui/PhoneNavigationBar.kt` (ícono `Icons.Filled.Groups`, etiqueta "Equipo"), `watch/phone-app/src/main/AndroidManifest.xml`.
- Test: `watch/phone-app/src/test/kotlin/.../phone/tak/TakStoreTest.kt`.

**Consume:** Tareas 2–4. Patrones existentes: DataStore como `phone/settings/PhonePrefs.kt`; notificación como `phone/session/PhoneNotification.kt`; MessageClient como `phone/bridge/PhoneBridge.kt`.

**Interfaces:**
```kotlin
// TakPrefs.kt — DataStore "blindside_tak"; el zip del paquete vive en filesDir/tak/package.zip
data class TakPrefs(val callsign: String, val publishContacts: Boolean, val deviceId: String, val packageSummary: String?)
fun Context.takPrefsRepository(): TakPrefsRepository   // prefs: Flow<TakPrefs>, current(), update(transform)
suspend fun importTakPackage(context: Context, zip: ByteArray): PackageResult   // valida con readTakPackage y, si Ok, guarda el zip y packageSummary = "host:puerto · clientName"; callsign por defecto = clientName si estaba vacío
fun loadTakPackage(context: Context): TakPackage?
// deviceId = deviceIdOf(clientName) del paquete importado: OpenTAKServer descarta un callsign conocido bajo un uid nuevo, así que el uid no puede cambiar al reinstalar

// TakStore.kt — estado observable para UI y notificación
data class TakUiState(val running: Boolean = false, val link: LinkStatus = LinkStatus.Idle,
                      val mates: Int = 0, val contactsSent: Long = 0, val lastFixAtMs: Long? = null, val error: String? = null)
object TakStore { val state: StateFlow<TakUiState>; fun update(transform: (TakUiState) -> TakUiState) }
fun takStatusText(state: TakUiState, nowMs: Long): String
```
`takStatusText`: no corriendo → "Desconectado"; `error` → el error; `Connecting` → "Conectando…"; `Retrying` → "Reconectando en N s · motivo"; `Connected` sin fix de los últimos 15 s → "Conectado · sin GPS"; `Connected` → "Conectado · N compañeros · M contactos enviados".

**`TakLinkService`** (Service, FGS `location`, acciones `START`/`STOP`, `START_NOT_STICKY`):
- Al iniciar: `startForeground` con notificación (canal `blindside_tak`, ongoing, texto `takStatusText`, acción "Detener"); carga paquete y prefs; `sslContextOf` (si lanza `TakSetupException` → `TakStore.error`, `stopSelf`); crea `TakLink(tlsConnector(...), TakIds(deviceId, callsign), BuildConfig.VERSION_NAME)` y lanza `run()` en un `CoroutineScope(SupervisorJob() + Dispatchers.IO)`.
- GPS: `TakLocation` envuelve `LocationManager.requestLocationUpdates(GPS_PROVIDER, 1000L, 0f, listener, mainLooper)` → último `GeoFix` + hora; quita updates al parar. Sin permiso → `TakStore.error = "Falta permiso de ubicación"`.
- Telemetría: `Wearable.getMessageClient(this).addListener` filtrando `TAK_TELEMETRY_PATH` → `decodeTelemetry` → `publishTelemetry(state, t, fix, fixAge, ids, prefs.publishContacts, now)` → `link.send` de cada evento; suma `contactsSent`.
- Entrantes: `link.incoming` → `roster = roster.with(event, callsign, now)`.
- Cada `TAK_TEAM_PERIOD_MS`: `encodeTeamUpdate(TeamUpdate(fix fresco o null, roster.mates(now)))` a todos los nodos conectados (`Wearable.getNodeClient(this).connectedNodes`) con `sendMessage(node.id, TAK_TEAM_PATH, bytes)`; errores se registran y se ignoran. Actualiza `TakStore` (mates, link, lastFixAtMs) y la notificación.
- `STOP`/`onDestroy`: cancela scope, quita listener y GPS, `TakStore.update { TakUiState() }`.

**Manifiesto:** permisos `INTERNET`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `FOREGROUND_SERVICE_LOCATION`; `<service android:name=".tak.TakLinkService" android:exported="false" android:foregroundServiceType="location"/>`.

**`TeamTab`** (Compose, mismos widgets que `ui/belt/BeltTab.kt`):
- Texto de estado (`takStatusText`) refrescado cada segundo.
- "Importar paquete (.zip)" con `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument())` y tipos `application/zip`, `application/x-zip-compressed`, `application/octet-stream`; lee los bytes con `contentResolver.openInputStream` (máximo 4 MiB) → `importTakPackage`; muestra `packageSummary` o el motivo de `Invalid`.
- Campo "Tu callsign" (`OutlinedTextField`, guarda al perder foco o con "Listo").
- Interruptor "Publicar contactos del radar".
- Botón "Conectar al servidor" / "Desconectar": pide `ACCESS_FINE_LOCATION` (y `POST_NOTIFICATIONS` en 33+) con `RequestMultiplePermissions`, luego `startForegroundService` con `START`, o `STOP`. Deshabilitado sin paquete.
- Texto fijo debajo: "Tu posición la publica ATAK. Blindside solo publica los contactos del radar y tus puntos tácticos."

- [ ] Test `TakStoreTest` de `takStatusText` (los 6 casos).
- [ ] `cd watch && ./gradlew :phone-app:testDebugUnitTest :phone-app:assembleDebug` en verde.

---

### Tarea 6: reloj — telemetría, ancla de rumbo y compañeros en el anillo

**Archivos:**
- Modificar: `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/session/SessionStore.kt` (`SessionUiState` + funciones), test `.../shared/session/SessionStoreTest.kt`.
- Crear: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/bridge/TakTelemetryPublisher.kt`.
- Modificar: `wear/session/WearSession.kt` (agregar el publicador a `launchCompanions`), `wear/bridge/BridgeListenerService.kt` (`onMessageReceived`), `watch/wear-app/src/main/AndroidManifest.xml` (filtro `MESSAGE_RECEIVED` con `android:path="/tak/team"`, mismo formato que los existentes), `wear/ui/radar/RadarScreen.kt`, `wear/ui/radar/CompassRingCanvas.kt`.

**Consume:** Tarea 1 (`scene.bodyYawDeg`, `scene.yawFromBelt`), Tarea 2 (todo `shared/tak`).

**Interfaces:**
```kotlin
// SessionUiState: campos nuevos al final, con default
val headingAnchor: HeadingAnchor? = null,
val team: TeamUpdate? = null,
val teamAtMs: Long? = null,
// SessionStore
fun anchorHeading(frontHeadingDeg: Double, nowNanos: Long)   // sin escena: no hace nada; si no: headingAnchor = anchorOf(front, scene.bodyYawDeg, nowNanos)
fun receiveTeam(update: TeamUpdate, nowMs: Long)             // team = update, teamAtMs = nowMs
// wear/bridge/TakTelemetryPublisher.kt
suspend fun publishTakTelemetry(context: Context, clockMs: () -> Long, clockNanos: () -> Long)
// CompassRingCanvas.kt
fun DrawScope.drawMateWedges(marks: List<MateMark>, azimuthDeg: Double, ring: RingGeometry, color: Color, measurer: TextMeasurer)
```

**Reglas:**
- Reloj de nanos único en todo el reloj: `SystemClock.elapsedRealtimeNanos()` (UI y publicador).
- `RadarScreen`: si `!ambient` y la lectura de brújula tiene `trust == CompassTrust.GOOD`, como mucho cada 250 ms llama `SessionStore.anchorHeading(frontHeadingDeg(reading.azimuthDeg, postureDeg), elapsedRealtimeNanos())`.
- `publishTakTelemetry`: bucle cada `TAK_TELEMETRY_PERIOD_MS` mientras la corrutina viva. Lee `SessionStore.state`; si `!teamLinkActive(teamAtMs, now)` no manda nada. Si no: `heading = scene?.let { bodyHeadingDeg(headingAnchor, it.bodyYawDeg, it.yawFromBelt, nanos) }`, `encodeTelemetry(telemetryOf(scene, heading, tacticalPoints))`, y `Wearable.getMessageClient(context).sendMessage(nodeId, TAK_TELEMETRY_PATH, bytes)` a cada nodo de `Wearable.getNodeClient(context).connectedNodes` (lista cacheada 30 s). Excepciones (salvo cancelación) se registran con `Log.w` y el bucle sigue.
- `BridgeListenerService.onMessageReceived`: si `event.path == TAK_TEAM_PATH` → `decodeTeamUpdate(String(event.data, UTF_8))?.let { SessionStore.receiveTeam(it, System.currentTimeMillis()) }`; el resto de paths sigue igual.
- `RadarScreen`: `here = hereOf(session.team, session.teamAtMs, now, lastFix)` reemplaza al `here` actual (cuñas tácticas incluidas). Si `teamLinkActive`, `drawMateWedges(mateMarks(team.mates, here), azimuth, ring, colorAliado, measurer)` justo después de `drawTacticalWedges`, mismo `ring` y misma rotación. Etiqueta: `"${mark.label} ${tacticalDistanceLabel(mark.distanceM)}"`. Color aliado: el azul/cian de la paleta del tema que no use ya un punto táctico (mirar `TacticalWedgeColors` / `BlindsideColors`); si no hay uno libre, agregar `ally` a la paleta con un azul de contraste ≥ 4.5:1 sobre el fondo.
- En ambiente no se dibujan cuñas de compañeros (igual que las tácticas: seguir la misma condición).

- [ ] Tests en `SessionStoreTest`: `anchorHeading` sin escena no cambia el estado; con escena `bodyYawDeg = 30` y frente 10 → `offsetDeg = 340`; `receiveTeam` guarda update y hora.
- [ ] `cd watch && ./gradlew :android-shared:testDebugUnitTest :wear-app:testDebugUnitTest :wear-app:assembleDebug` en verde.

---

### Tarea 7: documentación y scripts del servidor

**Archivos:** `docs/tak-server.md` (nuevo, español), `README.md` y `README.es.md` (sección corta "ATAK / TAK" con enlace), `tak/windows-network.ps1`, `tak/wsl-keepalive.vbs`, `tak/ots-player.sh` (ya creados por el orquestador).

- [ ] Runbook con: instalación WSL + `ubuntu_installer` desatendido, red Windows (`tak/windows-network.ps1`), keepalive, reenvío del router (TCP 8089 → IP LAN del PC), cambio de contraseña de admin, alta de jugadores (`tak/ots-player.sh`), qué paquete importa cada quien (ATAK: `_CONFIG.zip`; iTAK: `_CONFIG_iTAK.zip`; Blindside: cualquiera), prueba desde datos móviles, solución de problemas (no se ven entre sí → grupos `__ANON__`; TLS rechazado → paquete de otro servidor o revocado; Blindside "truststore sin certificados"). IP pública como `<IP pública>`.

---

### Tarea 8: prueba de punta a punta

- [ ] `assembleDebug` de ambas apps, instalar en S25 y reloj (`adb install -r`, README de cada app).
- [ ] En el celular: importar `santi_CONFIG.zip`, conectar. Estado "Conectado".
- [ ] Oyente de prueba en el PC (cliente TLS con otro jugador, p. ej. `jugador1`) que imprime cada evento recibido: aparece la identidad y, con el radar, contactos `a-u-G` y marcadores.
- [ ] El oyente manda un SA `a-f-G-U-C` con callsign "Prueba" cerca de la posición del celular → en el reloj aparece la cuña "PR".
- [ ] Desde datos móviles (Wi-Fi apagado) repetir la conexión por la IP pública (requiere el reenvío del router).
