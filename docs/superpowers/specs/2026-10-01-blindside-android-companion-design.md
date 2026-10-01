# Blindside: app Android (opción C) y pasada visual

- **Fecha:** 2026-10-01
- **Estado:** aprobado para ejecución autónoma. Santiago eligió la opción C el 1-oct y pidió "realiza todo lo que puedas sin mi asistencia, incluso la app de celular"; revisa al volver.
- **Base:** el MVP ya funciona de punta a punta (reloj ↔ ESP32 emparejados, 524 paquetes sin pérdidas). Spec del MVP: [`2026-09-30-blindside-v1-design.md`](2026-09-30-blindside-v1-design.md).
- **Regla de oro:** nada de esta fase puede romper el reloj solo. Toda rama se fusiona únicamente si pasa todas las pruebas **y** el E2E de regresión del reloj (§7).

## 1. Objetivo

Que el reloj y el celular (Galaxy S25 Ultra) **funcionen a la vez o cada uno por su cuenta**:
- **El reloj tiene prioridad.**
- **El celular suma:** pantalla grande, visor de grabaciones con mapa de calor y diagnóstico del cinturón.
- **El reloj y el celular comparten datos** por el canal de Wear OS.

## 2. Firmware: doble conexión (`firmware/`)

| Tema | Regla |
|---|---|
| Conexiones | `CONFIG_BT_NIMBLE_MAX_CONNECTIONS=2`. Mientras haya menos de 2 conexiones, el cinturón **sigue anunciándose** |
| Bonds | Hasta **2** guardados. La lista blanca del controlador contiene **todas** las identidades de confianza. Si ya hay 2 y entra un emparejamiento nuevo, reemplaza al bond que **no** está conectado; si los dos están conectados, se rechaza |
| Ventana de emparejamiento | **Sin bonds:** abierta hasta el primer bond (como hoy). **Con bonds:** se abre 60 s por el gesto BOOT (primeros 60 s, como hoy) **o** con el comando nuevo `05 OPEN_PAIRING_WINDOW`, aceptado **solo desde un enlace de confianza**. Así el reloj ya emparejado autoriza al celular sin tocar el botón |
| Rol | Comando nuevo `06 SET_ROLE <0=reloj, 1=celular>` por conexión. Si un enlace no lo envía, se trata como reloj |
| Prioridad del reloj | Parámetros de conexión del **reloj: 30-50 ms**; del **celular: 60-100 ms**. Cada paquete se notifica **primero al reloj**. Si la cola de notificaciones se llena, se descartan paquetes del **celular** y nunca del reloj; cada conexión tiene su propio contador |
| Flujo | Cada paquete va a **cada** conexión de confianza y suscrita. El `bundler` y el formato no cambian |
| `info` | Agrega `"conns":[{"role":"watch","itvl_ms":45.0,"lat":0,"timeout_ms":5000,"sent":N,"dropped":N}, …]` y `"bonds":K`, dentro de los 512 B |
| `SESSION_ACTIVE` / `IDENTIFY` | El estado de sesión se guarda por conexión. `IDENTIFY` se ignora si **alguna** conexión tiene la sesión activa |
| Diagnóstico serie | La línea `diag` muestra cada conexión (rol, trusted, sub, itvl, sent, dropped) |
| Seguridad | Sin cambios en el resto: un enlace sin autenticar se expulsa, la clave es por dispositivo y las características van cifradas. Riesgo nuevo, documentado: con un solo reloj conectado el cinturón vuelve a anunciarse, pero el filtro de la lista blanca sigue impidiendo que se conecte un rival |

**Pruebas nativas** (lógica pura en `lib/belt_rules`): qué bond se reemplaza, en qué orden se notifica y qué se descarta, cuándo se acepta `05` (solo con enlace de confianza), cómo se parsean `05` y `06`, y el tamaño de `info` con 2 conexiones (≤ 512 B).

## 3. Módulo compartido `watch/android-shared` (biblioteca Android)

**Se mueve desde `wear-app`, sin cambiar el comportamiento:**
- `ble/*`
- `recording/*`
- `settings/*`
- `haptics/*`
- `permissions/*`
- `demo/*`
- `sensors/*` (`WatchSensors` se renombra a `DeviceSensors`)
- de la sesión: `SessionEngine`, `SessionInput`, `InputAdapters`, `PipelinePort`, `RunningSession`, `ScenePacing`, `SessionCommands`, `SessionSource`, `SessionStore`, `EliminatedToggle`
- la geometría y el Canvas del radar (`RadarGeometry`, `RadarLabels`, `DeviceRotation`, `RadarCanvas`), solo con `compose-ui` y `foundation`, sin Wear Material

**Se queda en `wear-app`:** `MainActivity`, las pantallas Wear (Wear Compose), `BlindsideSessionService`, `SessionNotification` (Ongoing Activity), `SessionWakeLock` (si depende de Wear) y `SpikeScreen`.

**Detalles:**
- Paquete: `io.github.santiquiroz.blindside.shared.*`, con `minSdk 31`.
- Las pruebas JVM se mueven con su código. **Las 242 pruebas de `wear-app` siguen en verde**, ya repartidas entre los dos módulos.
- El servicio se generaliza: `SessionHost` es una interfaz que cada app implementa con su propia notificación. El rol BLE (`SET_ROLE`) se envía al conectar, según la app.

## 4. App del celular `watch/phone-app`

**Configuración:**
- `applicationId` **igual al del reloj** (`io.github.santiquiroz.blindside`), porque lo exige el Data Layer de Wear OS.
- `minSdk 31`, `targetSdk 36`, Compose Material 3 (BOM 2025.05.00) y **solo tema oscuro** (§6).

**Navegación inferior, 4 destinos** con icono y texto:

1. **Radar (en vivo):**
   - Conecta al cinturón con el rol celular y corre **su propio `RadarPipeline`**.
   - Muestra el Canvas compartido a pantalla completa, más una lista de contactos (distancia, rumbo, confianza y antigüedad, con números en JetBrains Mono).
   - Estado del enlace y de los sensores.
   - Botón grande de modo eliminado.
   - Vibración del celular **opcional** (desactivada por defecto).
   - Arranque igual de rápido que el reloj: un botón "Iniciar radar" y arranque automático si el cinturón ya está emparejado.
2. **Grabaciones:**
   - Lista de `.bsrec` locales y botón **"Traer del reloj"** (Data Layer, §5).
   - Compartir con el diálogo de Android.
   - Borrado con confirmación.
3. **Visor** (detalle de una grabación):
   - Reproducción con `BsrecReader` y `RadarPipeline`: play/pausa, velocidades 1×/2×/4×/8× y una línea de tiempo deslizable.
   - El radar se ve igual que en vivo.
   - **Mapa de calor de contactos** en el marco del cuerpo: rejilla de 0,5 m sobre el abanico, con colores accesibles.
   - **Resumen:** duración, contactos confirmados por minuto, alertas, huecos de enlace, % de tiempo caminando o quieto y latencia de confirmación (registros 5 y 6).
4. **Cinturón:**
   - Diagnóstico: `info` en vista legible (firmware, radares, IMUs, conexiones, potencia y MTU) y contadores.
   - Acciones: reiniciar radar (`01`), identificar (`03`, deshabilitado con sesión activa).
   - **"Emparejar este celular"**, con instrucciones: *en el reloj, Ajustes → Emparejar celular*, o el botón BOOT.
   - Ajustes compartidos: mano, montajes y signos.

**Pruebas:** JVM para la lógica pura (heatmap, resumen de grabación, decisiones de arranque y mapeos), `assembleDebug`, y el E2E de §7.

## 5. Puente reloj ↔ celular (Wear Data Layer, `play-services-wearable` 18.2.0)

| Canal | Qué hace |
|---|---|
| `MessageClient /recordings/list` | El celular pide la lista y el reloj responde un JSON (`nombre`, `bytes`, `inicio`) |
| `ChannelClient /recordings/get` | El celular descarga un `.bsrec` del reloj por stream |
| `DataClient /settings` | Ajustes compartidos (mano, montajes, signos y postura), con la última escritura como ganadora según su timestamp |
| `DataClient /status` | Con la sesión corriendo, el reloj publica cada 5 s su estado de enlace, los radares y si la sesión está activa. El celular lo muestra |
| `MessageClient /belt/open-pairing` | El celular le pide al reloj que envíe `05 OPEN_PAIRING_WINDOW` al cinturón |

**En el reloj:** Ajustes → **"Emparejar celular"** envía `05` directamente.

**Cada app funciona sola si la otra falta:** el puente es solo un extra.

## 6. Sistema visual (ui-ux-pro-max, 1-oct)

- **Estilo:** Dark Mode (OLED). Una sola acción principal por pantalla. Iconos vectoriales, nunca emojis.

**Tokens:**

| Token | Valor | Uso |
|---|---|---|
| `bg` | `#000000` | Fondo, negro puro OLED |
| `surface` | `#0E1111` | Tarjetas y chips |
| `surface-2` | `#161B1A` | Elevación |
| `ring` | `#25302C` | Anillos y retícula |
| `accent` | `#3BE37A` | Verde táctico: contactos, CTA y enlace OK |
| `accent-dim` | `#1E7A43` | Contactos en espera y acento atenuado de noche |
| `alert-red` | `#FF5A4E` (atenuado `#8C2A24`) | Alternativa roja de color y errores |
| `warn` | `#F2B84B` | Avisos (No molestar, radar caído) |
| `text` | `#E8ECEA` | Texto principal (≥ 7:1 sobre `bg`) |
| `text-2` | `#9AA5A0` | Texto secundario (≥ 4,5:1) |

**Tipografía:**
- **JetBrains Mono** (OFL, incluida en `res/font`) para números: distancias, rumbos, tiempos y contadores, con cifras tabulares.
- **IBM Plex Sans** (OFL) para etiquetas y texto.

**Reglas de componentes:**
- Áreas táctiles ≥ 48 dp.
- Animaciones de 150-300 ms que respetan "reducir movimiento".
- El color nunca es el único indicador: la confianza también se codifica por forma (relleno, contorno o punteado).
- Estados vacíos con una acción.

**Pasada visual del reloj** (con la misma lógica):
- Inicio: un **botón circular grande "Iniciar radar"** al centro y Ajustes como chip pequeño.
- Radar: anillos `ring`, contactos `accent` con forma según la confianza, números en Mono y estado en chips compactos.
- Fondo negro en todo.
- Ajustes nuevo: **"Emparejar celular"** (§5).

## 7. E2E autónomo (ADB + serie, sin Santiago)

1. Cargar el firmware en COM6 y leer `diag`.
2. **Regresión del reloj:** `adb am start` de la app del reloj. Con el arranque automático, la línea `diag` debe mostrar una conexión `watch` con trusted, sub y `sent` subiendo, en ≤ 30 s.
3. Instalar la app en el celular (`192.168.10.119`, por mDNS) y abrirla.
4. **Emparejar el celular sin manos:** en el reloj, Ajustes → "Emparejar celular" (uiautomator). Después, en el celular, "Iniciar radar". Cuando aparezca el diálogo de clave: `adb shell input text 799334` y Enter (con uiautomator).
5. `diag` debe mostrar **2 conexiones**, reloj y celular, las dos trusted y suscritas, con `sent` subiendo en ambas y `dropped(watch)=0`.
6. Desconectar el celular (`am force-stop`): el reloj sigue sin cortes y el cinturón vuelve a anunciarse.
7. **Traer una grabación del reloj** al celular por Data Layer y abrirla en el Visor sin errores, verificando con logcat y uiautomator.

Si algún paso no se puede automatizar, se documenta en la lista de verificación para Santiago y no bloquea las fusiones que ya pasaron las pruebas y la regresión del reloj.

## 8. Fuera de alcance

- GPS del celular en las grabaciones.
- IMU del torso desde el celular.
- Postura automática.
- Equipo e IFF.

Todo eso pasa a la fase siguiente del roadmap.
