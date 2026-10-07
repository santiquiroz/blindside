# Blindside × ATAK — diseño

Fecha: 2026-10-07 · Estado: aprobado (enfoque A) · Meta: partida del 2026-10-11 en Manizales.

## 1. Objetivo

El equipo comparte posiciones en un mapa TAK (ATAK en Android, iTAK en iPhone) y Blindside agrega dos cosas:

1. **Publica** en ese mapa los contactos del radar del cinturón y los puntos tácticos del jugador.
2. **Muestra en el reloj** a los compañeros, como cuñas en el anillo de la brújula (igual que los puntos tácticos).

Criterios de éxito:

- Un compañero con iTAK en datos móviles ve el punto amarillo de un contacto del radar de Santiago moverse en su mapa con menos de 3 s de retraso.
- Santiago ve en el reloj la cuña de cada compañero conectado, con su distancia, refrescada cada 2 s.
- Si el servidor o la señal se caen, el radar y la vibración siguen exactamente igual.

Fuera de alcance (después del 11): ver en el reloj los contactos que publican los radares de otros, un plugin de ATAK, el reloj sin celular, rumbo desde la brújula del celular.

## 2. Arquitectura

```
Reloj (radar + rumbo) ──1 Hz, MessageClient /tak/telemetry──▶ Celular (GPS + enlace TAK) ──TLS mutuo :8089──▶ OpenTAKServer (PC)
Reloj ◀──c/2 s, /tak/team (tu posición + compañeros)── Celular ◀──────────── CoT del equipo ──────────────────┘
                                                                     ATAK / iTAK de compañeros ──▶ mismo servidor
```

- **Servidor:** OpenTAKServer (OTS, GPL-3.0, instalador oficial de Ubuntu) dentro de WSL2 Ubuntu 24.04 en el PC de Santiago. Al router solo se le abre el **8089** (TLS con certificado de cliente obligatorio). La interfaz de administración (8443) queda en `localhost` del PC.
- **Celular:** dueño del enlace. Tiene datos, GPS y el certificado. Un servicio en primer plano ("Enlace TAK") que el jugador arranca a mano antes de la partida.
- **Reloj:** sigue siendo el único central BLE del cinturón. Manda telemetría al celular y dibuja a los compañeros.
- **ATAK del propio jugador** publica su posición (punto azul). Blindside **no** publica la posición del jugador: así no aparece dos veces.
- El enlace TAK es una capa opcional: nada del radar, la vibración o la sesión depende de él.

## 3. Servidor (runbook en `docs/tak-server.md`)

1. WSL2 Ubuntu 24.04 con systemd y un usuario `ots`; instalador oficial `ubuntu_installer` sin ZeroTier ni Mumble.
2. Puertos que escucha OTS: 8088 (TCP plano), 8089 (TLS), 8443 (web HTTPS), 8446 (enrolamiento), 80/8080 (web). **Solo 8089 sale a internet.**
3. Red Windows (una vez, como administrador): `netsh portproxy` de `<IP LAN del PC>:8089` → `127.0.0.1:8089` (el relé de localhost de WSL2 lo lleva a la distro) y regla de firewall de entrada solo para TCP 8089.
4. Router: reenvío TCP 8089 → IP LAN del PC (UPnP si el router lo permite; si no, a mano). Antes, confirmar que la IP WAN del router es la pública (no CGNAT 100.64.0.0/10).
5. WSL debe seguir vivo sin terminal abierta: una tarea al iniciar sesión que mantiene `wsl -d Ubuntu-24.04` corriendo oculto.
6. Cambiar la contraseña de `administrator` (por defecto `password`).
7. Un usuario OTS por jugador. Su certificado y paquetes (`<usuario>_CONFIG.zip` para ATAK, `<usuario>_CONFIG_iTAK.zip` para iTAK) se generan con `POST /api/certificate`. **El `connectString` del paquete toma el host con que se pidió**, así que se piden con la IP pública como host (o se reescribe el `.pref`), nunca con `localhost`.
8. Todos en el grupo por defecto `__ANON__` para que se vean entre sí.
9. Los paquetes llevan la llave privada: se mandan por chat privado, nunca a un grupo ni al repo. Al terminar la temporada se revocan desde OTS.

Ningún dato del servidor real (IP pública, contraseñas, paquetes) entra al repo; el runbook usa marcadores.

## 4. Mensajes entre reloj y celular

JSON en `MessageClient` (no `DataItem`: son efímeros y no deben reenviarse tarde). Códecs puros en `android-shared/.../tak/`.

**`/tak/telemetry`** reloj → celular, 1 Hz, solo mientras el reloj recibió un `/tak/team` en los últimos 10 s (el enlace está prendido):

```json
{"v":1,"headingOk":true,"blips":[{"id":3,"bearing":127.5,"range":4.2,"conf":"BOTH"}],
 "points":{"BASE":[5.0689,-75.5174]}}
```

- `bearing` ya es **absoluto** (grados desde el norte verdadero/magnético que da la brújula, sentido horario): el reloj suma el rumbo del cuerpo al rumbo del blip. El celular no sabe nada de posturas ni de IMUs.
- Sin rumbo confiable → `headingOk:false` y `blips` vacío. Los puntos tácticos viajan igual.
- Solo blips `BOTH` y `SINGLE` que no estén `outOfView`; los `COASTING` no se publican.

**`/tak/team`** celular → reloj, cada 2 s mientras el enlace está prendido (aunque la lista esté vacía):

```json
{"v":1,"self":{"lat":5.0689,"lon":-75.5174,"acc":4.0},"mates":[{"cs":"Toro","lat":5.0691,"lon":-75.5170,"age":3}]}
```

- `self` es el GPS del celular (null si no hay fix de los últimos 15 s). El reloj lo prefiere a su propio último fix para las cuñas tácticas y de compañeros.
- `mates` excluye al propio jugador (mismo callsign que el configurado) y todo uid `BLINDSIDE-*`.

## 5. Rumbo del cuerpo (en el reloj)

- La pantalla del radar ya calcula el rumbo del frente: `frontHeadingDeg(azimutBrújula, rotaciónDePostura)`. Solo existe con pantalla activa (en ambiente la brújula se apaga).
- `RadarScene` gana dos campos con valor por defecto: `bodyYawDeg` (yaw de cuerpo que ya calcula `YawTracker.displayYawAt`) y `yawFromBelt` (`true` si el yaw viene de las IMUs del cinturón y no del giroscopio del reloj).
- **Ancla:** cada vez que la pantalla tiene rumbo con brújula confiable, se guarda `offset = rumboFrente − bodyYawDeg` y su hora.
- **Rumbo del cuerpo ahora** = `offset + bodyYawDeg` si (a) el ancla tiene ≤ 3 s, o (b) `yawFromBelt` y el ancla tiene ≤ 120 s. Si no, no hay rumbo y no se publican contactos.
- Signos: el plan verifica con el simulador (`sim/PlayerPose`) que un giro horario sube `bodyYawDeg` igual que sube el rumbo de la brújula, y que `Blip.bearingDeg` crece hacia la derecha; si no, se corrige en la función pura, con prueba.

## 6. Enlace TAK (celular)

Piezas puras (pruebas JVM): proyección geográfica, CoT de salida, lectura del flujo de entrada, paquete de conexión. Piezas Android: socket TLS, servicio, GPS, pantalla.

**Paquete de conexión.** Se importa el mismo `.zip` que OTS genera para el jugador (ATAK o iTAK; el de ATAK trae un zip adentro). Se recorre el zip (recursivo en zips internos) y se toma: el `.pref` con `connectString0` (`host:puerto:ssl`), `caPassword`/`caPassword0`, `clientPassword`/`clientPassword0`, el `.p12` cuyo nombre contiene `truststore` y el otro `.p12` (cliente). Los `.p12` se copian al almacenamiento privado de la app. Si el `.p12` no carga en Android (OTS lo exporta con `-legacy`), el error lo dice claro y el runbook trae cómo exportar uno moderno.

**Conexión.** `SSLContext` TLS con `KeyManager` del p12 de cliente y `TrustManager` del truststore. Al conectar:

1. Envía una identidad: uid `BLINDSIDE-<id del dispositivo>`, tipo `a-f-G-E-S`, punto 0,0 con `ce`/`le` 9999999, `stale` = `time` + 5 s, `<contact callsign="<callsign> radar"/>`. OTS solo registra la conexión (y le empieza a reenviar el tráfico del equipo) tras un evento con `<contact>`; el `stale` corto evita que quede un marcador.
2. Lector: separa eventos por `</event>`, parsea con `XmlPullParser`, guarda compañeros (`a-f-*` con `contact callsign`, sin uid `BLINDSIDE-*`, sin el callsign propio) con su hora de llegada.
3. Ping `t-x-c-t` cada 15 s (OTS lo devuelve). Sin nada recibido en 45 s → reconexión.
4. Reconexión con espera 2, 4, 8, 16, 30 s (tope); se reinicia tras 60 s conectado.

**Publicación (≤ 1 Hz, al llegar cada telemetría con fix de GPS ≤ 10 s):**

- Contacto: uid `BLINDSIDE-<dev>-C<id>`, tipo `a-u-G` (desconocido: el radar no distingue amigo de enemigo), `how="m-g"`, punto = destino geodésico desde el GPS del celular con `bearing` y `range`, `ce` = precisión GPS + `range`·sin(15°) redondeado, `le`/`hae` 9999999, `stale` = ahora + 10 s, `<contact callsign="Radar <callsign> <id>"/>`, `<remarks>Blindside: contacto de radar, posición aproximada</remarks>`.
- Contacto que deja de llegar → un evento de borrado `t-x-d-d` con `<link uid=... relation="none" type="a-u-G"/>` y `<__forcedelete/>`.
- Puntos tácticos cada 30 s: uid `BLINDSIDE-<dev>-BASE|SPAWN|OBJECTIVE`, tipo `b-m-p-s-m` (marcador de mapa), `stale` = ahora + 10 min, callsign "Base <callsign>" / "Spawn <callsign>" / "Objetivo <callsign>".
- Nunca se encolan eventos viejos: sin conexión, la telemetría se descarta.

**Al reloj:** cada 2 s, `/tak/team` con `self` y compañeros con edad ≤ 60 s.

**Servicio en primer plano** tipo `location`, con notificación de estado ("Conectado · 3 compañeros"). GPS con `LocationManager` (`GPS_PROVIDER`, 1 s) — sin dependencias nuevas.

**Pestaña "Equipo"** en la barra inferior del celular:

- Estado: "Sin paquete" / "Conectando…" / "Conectado · N compañeros · M contactos enviados" / "Reconectando en N s · motivo" / "Sin GPS".
- Botón "Importar paquete (.zip)" (selector de archivos del sistema) y, ya importado, servidor y usuario del certificado.
- Campo "Tu callsign" (por defecto, el usuario del certificado).
- Interruptor "Publicar contactos del radar" (encendido por defecto).
- Botón "Conectar al servidor" / "Desconectar" (pide permiso de ubicación si falta).

## 7. Reloj

- Telemetría: el servicio de sesión arma `/tak/telemetry` en el tick y lo manda a 1 Hz si hay enlace (§4). Encoder puro.
- Compañeros: `BridgeListenerService` recibe `/tak/team` y lo guarda en `SessionStore`.
- Pantalla del radar: dibuja hasta 6 compañeros (los más cercanos, ≤ 1 km, edad ≤ 60 s) como cuñas en el anillo, con el mismo cálculo de `drawTacticalWedges` (rumbo desde "aquí" menos el azimut), color propio de aliado y etiqueta de 2 letras del callsign + distancia. "Aquí" = `self` del celular si es de los últimos 15 s; si no, el último fix del reloj.
- La pantalla publica en `SessionStore` cada rumbo confiable (rumbo del frente + hora) para el ancla de §5.

## 8. Errores

| Situación | Comportamiento |
|---|---|
| Servidor caído / sin señal | Estado "Reconectando en N s"; radar y vibración intactos; telemetría descartada. |
| TLS rechazado (certificado revocado o mal paquete) | Estado con el motivo; reintenta con la misma espera (no martilla). |
| p12 no carga | Error en la importación: "El certificado no se pudo abrir (formato antiguo o contraseña)". |
| Sin GPS en el celular | No se publican contactos; `self` null; estado "Sin GPS". |
| Sin rumbo confiable | `headingOk:false`; no se publican contactos; los puntos tácticos sí. |
| Reloj sin celular | No llega `/tak/team`; el reloj deja de mandar telemetría y dibuja lo de siempre. |
| XML corrupto en el flujo | Se descarta ese evento y se sigue leyendo. |

## 9. Pruebas

- **JVM (puras):** destino geodésico (ida y vuelta con `bearingDeg`/`distanceM`), CoT de contacto/borrado/punto/identidad/ping (XML válido, atributos exactos), lector de flujo (eventos partidos entre lecturas, basura entre eventos, filtro de compañeros), parser de paquete (zip ATAK anidado y zip iTAK), códecs `/tak/telemetry` y `/tak/team`, ancla de rumbo (frescura, expiración, signo), selección de compañeros (cercanía, edad, tope 6).
- **Integración local:** cliente del celular contra el OTS del PC por LAN; un oyente de prueba con su propio certificado comprueba que recibe los contactos.
- **Campo (antes del 11):** ATAK en el S25 con el paquete de Santiago; un compañero con iTAK en datos móviles; alguien camina frente al cinturón y aparece en ambos mapas; la cuña del compañero aparece en el reloj.
