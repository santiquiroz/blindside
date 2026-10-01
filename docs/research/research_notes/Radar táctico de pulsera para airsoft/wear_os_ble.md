# Plataforma Galaxy Watch 7 / Wear OS y enlace BLE ESP32↔reloj para Blindside

Leyenda de confianza usada en todo el documento: **[V]** = verificado leyendo la página citada en esta sesión (2026-09-30); **[S]** = solo resumen/snippet del buscador (la página no se abrió o devolvió 404/403): tratar como indicio, no como hecho; todo lo que está bajo "Inferences" es razonamiento propio, no dato publicado. Aviso de vigencia: el encargo habla de "Wear OS 5 / One UI 6-7 Watch", pero a sept-2026 el Galaxy Watch 7 (GW7) estable corre **One UI 8 Watch (Wear OS 6, Android 16/API 36)** y ya recibe **beta de One UI 9 Watch (Wear OS 7, Android 17)**; conviene diseñar contra Wear OS 6 y dejar preparado Wear OS 7.

## 1. Hardware del Galaxy Watch 7: pantallas, SoC, RAM, Bluetooth, baterías, sensores y tasas de muestreo

### Takeaway
El GW7 (julio 2024) viene en 40 mm (1,31" 432×432, 300 mAh) y 44 mm (1,47" 480×480, 425 mAh), ambos con Exynos W1000 3 nm (1×A78 1,6 GHz + 4×A55 1,5 GHz, Mali-G68 MP2), 2 GB LPDDR5, 32 GB y Bluetooth 5.3. Para apps de terceros el límite práctico de muestreo es 200 Hz con `registerListener()` sin permisos especiales. Gravedad y aceleración lineal no aparecen en ninguna ficha publicada, así que hay que comprobarlas en runtime.

### Cited Findings
- [V] 40 mm: pantalla "1.31 in (33 mm) 432 x 432p (~330 ppi)" AMOLED, batería 300 mAh Li-Ion; 44 mm: "1.47 in (37 mm) 480 x 480p (~327 ppi)", 425 mAh — [Wikipedia: Samsung Galaxy Watch 7](https://en.wikipedia.org/wiki/Samsung_Galaxy_Watch_7)
- [V] SoC Exynos W1000 con "1x 1.6GHz Cortex-A78" + "4x 1.5GHz Cortex-A55", GPU Mali-G68 MP2, 2 GB RAM LPDDR5, 32 GB eMMC 5.1, "Bluetooth 5.3, A2DP, LE", Wi-Fi dual-band — [Wikipedia](https://en.wikipedia.org/wiki/Samsung_Galaxy_Watch_7)
- [V] GSMArena confirma Exynos W1000 (3 nm), Mali-G68, 2 GB/32 GB, "Bluetooth 5.3, A2DP, LE", "Super AMOLED, 2000 nits (peak)", cristal de zafiro, 44 mm = 44,4×44,4×9,7 mm y 33,8 g, 40 mm = 28,8 g — [GSMArena GW7](https://www.gsmarena.com/samsung_galaxy_watch7-13128.php)
- [V] **Conflicto:** GSMArena da "1.5 inches, 480 x 480" y "425 mAh" como si valieran para ambos tamaños. Eso es incorrecto para el 40 mm según Wikipedia (1,31", 432×432, 300 mAh). Un resumen del buscador también dijo "Bluetooth 5.4", pero las dos páginas abiertas dicen 5.3 — [GSMArena](https://www.gsmarena.com/samsung_galaxy_watch7-13128.php); [Wikipedia](https://en.wikipedia.org/wiki/Samsung_Galaxy_Watch_7)
- [V] Sensores listados: "Accelerometer, gyro, heart rate, barometer, altimeter, compass, SpO2, temperature (skin), BioActive" — [GSMArena](https://www.gsmarena.com/samsung_galaxy_watch7-13128.php). Wikipedia añade sensor de luz y geomagnético — [Wikipedia](https://en.wikipedia.org/wiki/Samsung_Galaxy_Watch_7)
- [V] SO: salió con "Wear OS 5 with One UI Watch 6" y es actualizable a "Wear OS 6 with One UI Watch 8" — [Wikipedia](https://en.wikipedia.org/wiki/Samsung_Galaxy_Watch_7); GSMArena: "Android Wear OS 5, up to 6.0, One UI Watch 8" — [GSMArena](https://www.gsmarena.com/samsung_galaxy_watch7-13128.php)
- [V] Versiones de Wear OS: 5.0 = Android 14 (19-jul-2024); 5.1 = Android 15 (19-mar-2025); 6.0 = Android 16 (22-jul-2025); 6.1 = Android 16 (9-dic-2025); 7.0 = Android 17 (16-jun-2026) — [Wikipedia: Wear OS](https://en.wikipedia.org/wiki/Wear_OS)
- [S] La beta de One UI 9 Watch (basada en Wear OS 7) llegó al GW7 y al Watch Ultra a comienzos de septiembre de 2026, solo en EE. UU. y Corea del Sur. Pesa ~1,9 GB, el firmware termina en ZZI1 y trae el parche de seguridad de septiembre de 2026. La inscripción es por Samsung Members — [GSMArena news](https://www.gsmarena.com/samsung_galaxy_watch7_galaxy_watch_ultra_one_ui_9_watch_beta_update-news-74482.php); [SammyFans, 2026-09-03](https://www.sammyfans.com/2026/09/03/one-ui-9-watch-beta-arrives-on-galaxy-watch-7-with-new-features/)
- [V] Límite de muestreo (apps con target Android 12+): `registerListener()` queda en **200 Hz** y `SensorDirectChannel` en `RATE_NORMAL` (~50 Hz). Para superar el límite hace falta declarar `android.permission.HIGH_SAMPLING_RATE_SENSORS`; sin él se lanza `SecurityException`. Si el usuario desactiva el micrófono con el toggle del sistema, los sensores quedan limitados de todos modos — [Android Sensors overview](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)
- [V] Constantes de retardo: `SENSOR_DELAY_FASTEST` = 0 µs, `SENSOR_DELAY_GAME` = 20.000 µs (50 Hz), `SENSOR_DELAY_UI` = 60.000 µs (~16 Hz), `SENSOR_DELAY_NORMAL` = 200.000 µs (5 Hz) — [Sensors overview](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)
- [V] Desde Android 9, una app en background no recibe eventos de sensores continuos, on-change ni one-shot. La recomendación es leerlos "only when your app is in the foreground or as part of a foreground service" — [Sensors overview](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)
- [V] `android.permission.ACTIVITY_RECOGNITION` es permiso runtime desde Android 10. Los únicos sensores integrados que lo exigen son step counter y **step detector** — [Android 10 privacy changes](https://developer.android.com/about/versions/10/privacy/changes)
- [V] Samsung (blog oficial, 23-abr-2026) usa la variante wake-up del sensor, `sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE, true)`, que "can wake up the processor to deliver data even in deep sleep" — [Samsung Developers blog](https://developer.samsung.com/galaxy-watch/blog/en/2026/04/23/continuous-heart-rate-tracking-on-galaxy-watch-even-with-the-screen-off)
- [S] AOSP sobre sensores non-wake-up: "It is the responsibility of applications to keep a partial wake lock should they wish to receive events from non-wake-up sensors while the screen is off". Con el SoC suspendido los eventos van a una FIFO de hardware y, si se llena, se descartan los más viejos — [AOSP Suspend mode](https://source.android.com/docs/core/interaction/sensors/suspend-mode); [AOSP Batching](https://source.android.com/docs/core/interaction/sensors/batching)

### Inferences
- Para detectar la pose de muñeca (radar mirando al usuario o muñeca bajada) basta `TYPE_GRAVITY` a `SENSOR_DELAY_UI` (~16 Hz) o `SENSOR_DELAY_GAME` (50 Hz). No hace falta pedir `HIGH_SAMPLING_RATE_SENSORS`.
- `TYPE_GRAVITY` y `TYPE_LINEAR_ACCELERATION` suelen ser sensores virtuales (fusión de acelerómetro y giroscopio) que expone el HAL del fabricante. No encontré confirmación publicada para el GW7, así que la app debe hacer `getDefaultSensor(TYPE_GRAVITY)` y, si da `null`, calcular la gravedad con un filtro paso-bajo sobre `TYPE_ACCELEROMETER`.
- `TYPE_STEP_DETECTOR` es "special trigger": emite un evento por paso. Exige `ACTIVITY_RECOGNITION` concedido en runtime y, en sesiones con pantalla apagada, conviene pedir la variante wake-up si existe (`getDefaultSensor(TYPE_STEP_DETECTOR, true)`).
- Si Blindside apunta solo al GW7, API ≥ 34 está garantizada porque salió con Wear OS 5. Eso simplifica permisos (los `BLUETOOTH_*` son API 31+ y los tipos de FGS obligatorios son API 34+).
- El 44 mm conviene para Blindside: 42 % más batería (425 vs 300 mAh) y unos 23 % más de píxeles por lado (480 vs 432) para el radar.
- La CPU no es un cuello de botella. Asociar y seguir ≤3 blancos × 2 radares a 10 Hz más un IMU a 50 Hz son microsegundos por ciclo en un A55.

### Gaps
- No hay documento público de Samsung con la lista de sensores que el GW7 expone por `SensorManager` (tipos virtuales, variantes wake-up, `getMinDelay()`, `getFifoMaxEventCount()`). Hay que volcarla en el dispositivo con `getSensorList(Sensor.TYPE_ALL)`.
- No encontré la frecuencia de refresco del panel ni cuánto puede fijar el brillo una app.
- No hay fecha de la versión estable de One UI 9 Watch para el GW7 (a fin de septiembre de 2026 sigue en beta).

## 2. BLE central en Wear OS 5/6/7 (One UI 6 → 9 Watch) para apps de terceros

### Takeaway
En el GW7 funciona la API BLE central estándar de Android, sin API Samsung aparte. Lo esencial:
- Permisos runtime `BLUETOOTH_SCAN` (con `neverForLocation`) y `BLUETOOTH_CONNECT`.
- Escaneo con `ScanFilter`, porque sin filtro el escaneo muere con la pantalla apagada. Máximo 5 `startScan` cada 30 s.
- `connectGatt(..., TRANSPORT_LE)` y operaciones GATT estrictamente serializadas.
- `requestMtu()`: desde Android 14 el stack pide 517.
- Suscripción por CCCD 0x2902.
- `requestConnectionPriority()`: los valores AOSP por defecto son HIGH 11,25–15 ms, BALANCED 30–50 ms y LOW_POWER 100–125 ms, pero el OEM puede cambiarlos.
- La reconexión la escribe uno mismo (`autoConnect` no reconecta sola).

El riesgo propio de Samsung está en cómo el reloj trata el trabajo con la pantalla apagada: CPU e hilos suspendidos y listeners que se detienen. Todo el BLE tiene que vivir en un foreground service.

### Cited Findings
- [V] Manifiesto recomendado: `BLUETOOTH` y `BLUETOOTH_ADMIN` con `android:maxSdkVersion="30"`, `BLUETOOTH_SCAN` con `android:usesPermissionFlags="neverForLocation"`, `BLUETOOTH_CONNECT` y `ACCESS_FINE_LOCATION` con `maxSdkVersion="30"` cuando nunca se deriva ubicación. Advertencia textual: "If you include `neverForLocation`… some BLE beacons are filtered from the scan results" — [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [V] Alternativa: Companion Device Manager (Android 8+) "provides a pairing UI on behalf of your app and doesn't require location permissions" — [Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [V] Throttling: "Android has an internal limit of five startScan(…) method calls every 30 seconds per app". Al pasarse, el escaneo falla en silencio, sin callback de error y solo con una línea en Logcat — [Punch Through: Android BLE guide](https://punchthrough.com/android-ble-guide/)
- [V] Los `ScanFilter` son obligatorios para escanear en background: "Without filters, the system terminates scans when the screen turns off" — [Punch Through](https://punchthrough.com/android-ble-guide/)
- [V] `autoConnect=true` "doesn't cause Android to automatically try to reconnect… in the event of a connection!"; solo evita el timeout mientras se establece la conexión. El connect directo (`autoConnect=false`) expira a los ~30 s, "Samsung devices may timeout after 10 seconds" — [Punch Through](https://punchthrough.com/android-ble-guide/)
- [V] Conviene especificar `BluetoothDevice.TRANSPORT_LE` (API 23+). `GATT_ERROR 133` aparece cuando el dispositivo sale de rango o rechaza la conexión; la recuperación es `close()` del `BluetoothGatt` — [Punch Through](https://punchthrough.com/android-ble-guide/)
- [V] "Android doesn't like performing rapid, asynchronous, out-of-order BLE operations. There's no internal queuing mechanism." Cada operación (connect, discover, MTU, read, write, descriptor) debe esperar su callback — [Punch Through](https://punchthrough.com/android-ble-guide/)
- [V] Android 14 (todas las apps, sin importar el target): "the Android Bluetooth stack… requests the BLE ATT MTU to 517 bytes when the first GATT client requests an MTU using the `BluetoothGatt#requestMtu(int)` API, and disregards all subsequent MTU requests on that ACL connection". El valor final es `min(517, remoteMtu)`. Workaround a nivel app: `min(SUPPORTED_MTU, 517) - 5` — [Android 14 behavior changes](https://developer.android.com/about/versions/14/behavior-changes-all)
- [V] Hay que asumir MTU 23 (20 bytes útiles) hasta que haya intercambio, y `onMtuChanged` puede no dispararse con algunos firmwares — [Punch Through](https://punchthrough.com/android-ble-guide/)
- [V] API 33+: nuevas firmas `writeCharacteristic(characteristic, value, writeType)`, `onCharacteristicRead(..., value, ...)` y `onCharacteristicChanged(gatt, characteristic, value)`; las versiones viejas quedan deprecadas — [Punch Through](https://punchthrough.com/android-ble-guide/)
- [V] Suscripción: escribir `0x0100` (notify) en el CCCD `0x2902` con `writeDescriptor()`. `onCharacteristicChanged()` puede llegar antes que `onDescriptorWrite()`. Se recomienda llamar `discoverServices()` desde el hilo principal para evitar un deadlock raro. Android cachea la base GATT y `refresh()` es una API oculta desaconsejada — [Punch Through](https://punchthrough.com/android-ble-guide/)
- [V] Para cerrar limpio: `disconnect()`, esperar `STATE_DISCONNECTED`, luego `close()` — [Punch Through](https://punchthrough.com/android-ble-guide/)
- [V] Valores AOSP por defecto de `requestConnectionPriority` (commit "GATT: Move connection parameters to config.xml", 18-may-2016, en unidades de 1,25 ms): HIGH `9`–`12` (11,25–15 ms) con latencia 0; BALANCED `24`–`40` (30–50 ms) con latencia 0; LOW_POWER `80`–`100` (100–125 ms) con latencia 2. Se movieron a `config.xml` para que los OEM los cambien por overlay — [AOSP commit d65009d](https://android.googlesource.com/platform/packages/apps/Bluetooth/+/d65009d%5E!/); [S] [resultado de búsqueda sobre el overlay](https://android.googlesource.com/platform/packages/apps/Bluetooth/+/0e94b62d60f3a729b03841a1891af3a889c9748f%5E2..0e94b62d60f3a729b03841a1891af3a889c9748f/)
- [V] **Conflicto:** Punch Through da HIGH 7,5–10 ms, BALANCED 15–45 ms y LOW_POWER 100–1000 ms, y aclara que dependen del firmware — [Punch Through](https://punchthrough.com/android-ble-guide/). No coinciden con los defaults AOSP de arriba, así que hay que medir en el GW7.
- [S] "Android devices can support a connection interval as low as 7.5 ms" — [Punch Through: BLE throughput part 4](https://punchthrough.com/ble-throughput-part-4/)
- [V] Samsung, sobre el Galaxy Watch con la pantalla apagada: "The CPU may slow down or suspend execution of background threads", "Registered sensor listeners can stop receiving updates", "Any active work in your Activity pauses". La solución que proponen es un foreground service promovido de inmediato en `onStartCommand()` — [Samsung Developers blog 2026-04-23](https://developer.samsung.com/galaxy-watch/blog/en/2026/04/23/continuous-heart-rate-tracking-on-galaxy-watch-even-with-the-screen-off)
- [S] Hilo del foro de desarrolladores Samsung "No Bluetooth connection during physical activity": en un Galaxy Watch, `onConnectionStateChange` pasa una y otra vez a `STATE_DISCONNECTED` con `status=133` durante actividad física. La página devolvió 404; solo tengo el snippet — [Samsung forum (snippet)](https://forum.developer.samsung.com/t/no-bluetooth-connection-during-physical-activity/20230)
- [V] Android bloquea los servicios HID (0x1812), FIDO (0xFFFD), Android TV Remote y LE Audio con `SecurityException`. Un UUID propio de 128 bits no se ve afectado — [Punch Through](https://punchthrough.com/android-ble-guide/)

### Inferences
- Flujo recomendado en el reloj, todo dentro del FGS:
  1. Primer emparejamiento: `startScan` con `ScanFilter` por UUID de servicio de Blindside y `SCAN_MODE_LOW_LATENCY` durante como mucho 10–20 s. Guardar la MAC.
  2. `connectGatt(ctx, false, cb, TRANSPORT_LE)`.
  3. `discoverServices()` en el hilo principal.
  4. `requestMtu(517)`.
  5. Escribir el CCCD de la characteristic de notify.
  6. `requestConnectionPriority(BALANCED)`, o LOW_POWER si la latencia alcanza.
  7. Hacer todo esto con una cola propia (una operación en vuelo a la vez).
- Reconexión: con `STATE_DISCONNECTED`, llamar `close()` e inmediatamente `connectGatt(autoConnect=true)` (conexión "en espera" sin timeout). En paralelo, que el ESP32 vuelva a anunciar con intervalo rápido (`advertiseOnDisconnect`). Si se lleva >N s sin reconectar, alternar con intentos directos (`autoConnect=false`).
- En `onCharacteristicChanged` solo copiar el `ByteArray` y mandarlo a un `Channel`/`SharedFlow`; el tracking va en una corutina propia. El callback corre en un hilo binder y no debe bloquearse.
- La MAC del ESP32 con NimBLE suele ser la pública de fábrica, estable, y sirve para reconectar sin escanear. No lo verifiqué en esta sesión.
- Pedir `CONNECTION_PRIORITY_HIGH` solo durante el setup (discovery + MTU + CCCD) y bajar después a BALANCED o LOW_POWER. HIGH multiplica por 3–8 los eventos de conexión por segundo frente a BALANCED y LOW_POWER, con su costo en batería.
- Android no expone públicamente el intervalo negociado (`onConnectionUpdated` es oculto). Lo más simple es medirlo en el ESP32 con el callback de actualización de parámetros de NimBLE y registrarlo en la telemetría.
- Con `neverForLocation` y API ≥ 31 no debería hacer falta tener encendida la "Ubicación" para escanear. No verifiqué el comportamiento concreto de One UI Watch.
- Librerías como Nordic Kotlin BLE Library o JuulLabs Kable resuelven la cola GATT y los reintentos. No evalué su estado en 2026.

### Gaps
- No encontré documentación ni mediciones de los parámetros de conexión efectivos del GW7 (el overlay de Samsung), ni de si respeta `LOW_POWER` o `HIGH`.
- No hay fuente autorizada sobre si las notificaciones GATT siguen llegando sin cortes con la app en ambient o pantalla apagada en One UI 8/9 Watch. El blog de Samsung habla de sensores y CPU, no de BLE. Hay que hacer un spike en el dispositivo.
- No encontré datos sobre cómo afecta el enlace BT del reloj con el teléfono (compartido en la misma radio) a la latencia de una segunda conexión LE. En el juego no habrá teléfono, pero el reloj puede seguir intentando reconectarlo.

## 3. Mantener la app viva 1–4 h: FGS, Ongoing Activity / Live Updates, Doze y gestión de batería de Samsung

### Takeaway
El patrón que usan Google y Samsung:
- Un foreground service arrancado mientras la Activity está visible y promovido de inmediato, con tipo `connectedDevice` (más `health` si lee sensores con la pantalla apagada).
- Un Ongoing Activity (Wear OS ≤ 6) o Live Update (Wear OS 7) para que el sistema no vuelva a la esfera y el usuario tenga un botón de retorno.
- Sensores wake-up o un wake lock parcial durante la sesión.

Samsung restringe explícitamente los servicios en background de terceros en el Galaxy Watch. Todo el trabajo (BLE, tracking, vibración) va en el servicio, nunca en la Activity.

### Cited Findings
- [V] FGS `connectedDevice`: permiso de manifiesto `FOREGROUND_SERVICE_CONNECTED_DEVICE` y constante `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`. Prerrequisito: "Request and be granted at least one of… `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_SCAN`, `UWB_RANGING`", o declarar `CHANGE_NETWORK_STATE`/`CHANGE_WIFI_STATE`/`NFC`/…, o `UsbManager.requestPermission()` — [FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [V] FGS `health`: `FOREGROUND_SERVICE_HEALTH`, `FOREGROUND_SERVICE_TYPE_HEALTH`. Prerrequisito: declarar `HIGH_SAMPLING_RATE_SENSORS` o tener concedido alguno de `BODY_SENSORS` (API ≤ 35), `READ_HEART_RATE`, `READ_SKIN_TEMPERATURE`, `READ_OXYGEN_SATURATION` o **`ACTIVITY_RECOGNITION`**. Los permisos de body sensors son "while-in-use" — [FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [V] Android 14 exige declarar el tipo de foreground service — [Android 14 behavior changes](https://developer.android.com/about/versions/14/behavior-changes-all)
- [V] Samsung: "Samsung restricts background services from third-party applications to balance user experience and battery life" y "If you want to send notifications through a service, you must implement it as a foreground service" — [Samsung Developers blog 2023](https://developer.samsung.com/sdp/blog/en/2023/06/01/display-notifications-on-a-galaxy-watch-running-wear-os-powered-by-samsung)
- [V] Samsung (2026) para trabajo continuo con la pantalla apagada: "Promote Immediately: In `onStartCommand()`, promote the service to the foreground immediately". Permisos de su ejemplo: `BODY_SENSORS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_HEALTH`, **`WAKE_LOCK`**, `BODY_SENSORS_BACKGROUND`; `android:foregroundServiceType="health"`. Advierten que sin ese tipo la app "will crash with a SecurityException" — [Samsung Developers blog 2026-04-23](https://developer.samsung.com/galaxy-watch/blog/en/2026/04/23/continuous-heart-rate-tracking-on-galaxy-watch-even-with-the-screen-off)
- [S] En el foro de Samsung aparecen hilos del tipo "Is foreground service limited on Galaxy Watch 4?" e "idle mode issue", con afirmaciones como "Samsung restricts foreground service to save more power from 3rd party applications" y datos de sensores que fallan con la pantalla apagada aunque haya FGS (Galaxy Watch 4). Los hilos devolvieron 404 al abrirlos — [Samsung forum (snippet)](https://forum.developer.samsung.com/t/is-foreground-service-limited-on-galaxy-watch-4/20319); [Samsung forum (snippet)](https://forum.developer.samsung.com/t/galaxy-watch-4-idle-mode-issue/19590)
- [V] Ongoing Activity: `androidx.wear:wear-ongoing` (el ejemplo del doc usa `1.1.0` junto con `androidx.core:core:1.19.1`). Se envuelve un `NotificationCompat.Builder` con `.setOngoing(true) // Important!` en `OngoingActivity.Builder(ctx, NOTIFICATION_ID, builder)`, con `.setStaticIcon()`, `.setAnimatedIcon()`, `.setTouchIntent()`, `.setStatus(Status.Builder()…)`, luego `ongoingActivity.apply(ctx)` y `startForeground(...)`. Sin icono estático o touch intent se lanza `IllegalArgumentException` — [Ongoing Activity](https://developer.android.com/training/wearables/ongoing-activity)
- [V] Efecto del Ongoing Activity: icono tocable en la esfera y entrada en **Recents**. Además "keeps your app visible for longer, preventing the system from returning to the watch face after a period of inactivity". El doc lo recomienda para experiencias de más de 1 minuto — [Ongoing Activity](https://developer.android.com/training/wearables/ongoing-activity)
- [V] `wear-ongoing 1.1.0` y `wear 1.4.0` son estables desde el 23-sep-2026 — [Android latest updates](https://developer.android.com/latest-updates)
- [V] Wear OS 7: "In your watch app, use Live Updates instead of the Ongoing Activity API to provide local update publishing on all Wear 7 devices" — [Android Developers Blog: What's new in Wear OS 7](https://android-developers.googleblog.com/2026/05/whats-new-wear-os-7.html). El doc de always-on añade: "For backward compatibility, continue to support Ongoing Activity on devices that run Wear OS 6 or lower" — [Always-on apps](https://developer.android.com/training/wearables/views/always-on)
- [V] Timeouts del sistema: Timeout #1 lleva a Ambient; en el Timeout #2 "the system may hide the current app and display the watch face" (el usuario configura su duración) — [Always-on apps](https://developer.android.com/training/wearables/views/always-on)
- [V] dontkillmyapp (Samsung) trata solo de **teléfonos** (Android 6 a 14) y no menciona el Galaxy Watch. En Android 11 dice: "Apps can no longer hold wake lock in foreground services". Para el desarrollador: "No known solution on dev end". Hay que desactivar "Put unused apps to sleep" (3 días por defecto). La última nota es de julio de 2024 → posiblemente desactualizado — [dontkillmyapp.com/samsung](https://dontkillmyapp.com/samsung)

### Inferences
- Arquitectura sugerida: un `BlindsideSessionService` con `android:foregroundServiceType="connectedDevice|health"` que sea dueño de `BluetoothGatt`, listeners de `SensorManager`, tracker y `Vibrator`, y que publique un `StateFlow<RadarState>`. La Activity o Compose solo lo observa.
  - Arrancar el FGS desde la UI visible (botón "Iniciar partida") para esquivar las restricciones de arranque en background.
  - Pedir `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`/`ACTIVITY_RECOGNITION` antes de `startForeground`, porque sin prerrequisito se lanza `SecurityException`.
- Wake lock: tomar `PARTIAL_WAKE_LOCK` con timeout y renovarlo mientras dure la partida. Para gravedad y pasos, preferir sensores wake-up y `maxReportLatencyUs` razonable. La advertencia de dontkillmyapp sobre wake locks en FGS es de teléfonos Samsung con Android 11; no hay evidencia para One UI Watch, así que hay que probarlo midiendo gaps en la secuencia BLE con la pantalla apagada.
- Doze profundo exige dispositivo quieto con pantalla apagada. En partida el jugador se mueve, así que lo más probable es Doze ligero. Probar con `adb shell dumpsys deviceidle force-idle` y verificar que la secuencia BLE no tenga huecos.
- Implementar Ongoing Activity ahora (el GW7 estable está en Wear OS 6) y una rama con Live Updates condicionada a Wear OS 7 (`Build.VERSION.SDK_INT >= 37`, suponiendo que Android 17 sea API 37; sin verificar).
- Al terminar la partida: `stopForeground(STOP_FOREGROUND_REMOVE)`, cancelar la notificación (eso termina el Ongoing Activity), soltar el wake lock y cerrar GATT.

### Gaps
- No encontré documentación oficial de Samsung que describa las reglas concretas de cierre y suspensión de apps de terceros en One UI 8/9 Watch (equivalentes a "Sleeping apps" del teléfono), ni si respeta wake locks dentro de un FGS.
- No revisé en detalle la API concreta de Live Updates en Wear OS 7 (clases y estilos soportados, p. ej. `ProgressStyle`).
- Falta evidencia directa de que una conexión GATT activa sobreviva 1–4 h con la pantalla apagada en el GW7. Es el primer spike a hacer.

## 4. Pantalla: mantenerla encendida, ambient, despertarla por evento, burn-in, brillo y rendimiento de Canvas

### Takeaway
- **Modo "siempre encendida":** `Modifier.keepScreenOn()` o `FLAG_KEEP_SCREEN_ON`, con el aviso oficial de impacto severo en batería.
- **Modo "ahorro":** dejar que el sistema entre en ambient. Con Wear Compose ≥ 1.6 se usa `LocalAmbientModeManager`/`AmbientTickEffect`, que actualiza ~1 vez por minuto. En Wear OS 6 con targetSdk 36 la app sigue visible atenuada por defecto.
- **Reglas de ambient:** ≤ 15 % de píxeles encendidos, nada de bloques sólidos ni blancos, UI crítica a ≥ 10 px del borde, sin animaciones y sin antialiasing si es low-bit.
- **Despertar la pantalla desde código:** no está documentado para Wear OS. `ACQUIRE_CAUSES_WAKEUP` y `setTurnScreenOn` son candidatos que hay que probar en el reloj.

### Cited Findings
- [V] Estados: Interactive ("full brightness") y Ambient ("display dims"). Una "Always-On App" muestra contenido en ambos. "Ambiactive mode" es cuando la app sigue mostrando su UI en Ambient — [Always-on apps](https://developer.android.com/training/wearables/views/always-on)
- [V] API Compose: `rememberAmbientModeManager()`, `CompositionLocalProvider(LocalAmbientModeManager provides …)`, `ambientModeManager.currentAmbientMode` (`AmbientMode.Interactive`/`AmbientMode.Ambient`) y `ambientModeManager.AmbientTickEffect { … }`, que "is invoked only periodically — typically once per minute". Los valores que cambian rápido deben reemplazarse por placeholders como `--` — [Always-on apps](https://developer.android.com/training/wearables/views/always-on)
- [V] Flags del dispositivo: si `isLowBitAmbientSupported`, "Disable anti-aliasing… and avoid color gradients". Si `isBurnInProtectionRequired`, el sistema desplaza el contenido solo, y la app no debe desplazarlo a mano: "avoid solid white areas and place critical UI elements at least 10 pixels away from the edge" — [Always-on apps](https://developer.android.com/training/wearables/views/always-on)
- [V] Guía de diseño ambient: "Keep at least 85% of the screen black (an on-pixel ratio of 15% or less)", contornos en lugar de rellenos, "Pause or remove all animations", mantener las mismas posiciones entre modos. `TimeText` es consciente del ambient — [Always-on apps](https://developer.android.com/training/wearables/views/always-on)
- [V] Comportamiento por defecto: en Wear OS 5 o anterior, "blurred screenshot of your paused application, with the time overlaid". En Wear OS 6+ con target SDK 36+, "The display is dimmed, but the application continues running and remains visible. (Updates may be as infrequent as once per minute.)" — [Always-on apps](https://developer.android.com/training/wearables/views/always-on)
- [V] Evitar el ambient: modificador `.keepScreenOn()`, con la advertencia "Use this with extreme caution as it severely impacts battery life" — [Always-on apps](https://developer.android.com/training/wearables/views/always-on)
- [V] Pruebas: `adb shell input keyevent KEYCODE_SLEEP` (entra a ambient) y `KEYCODE_WAKEUP` (sale) — [Always-on apps](https://developer.android.com/training/wearables/views/always-on)
- [V] `LocalAmbientModeManager` apareció en Wear Compose **1.6.0** (estable 25-mar-2026). En 1.7.0 (23-sep-2026) llegaron anotaciones de rendimiento `@FrequentlyChangingValue`/`@RememberInComposition` para lint y una "One-handed gestures API" (acción primaria con doble pellizco, descarte con giro de muñeca) — [Wear Compose releases](https://developer.android.com/jetpack/androidx/releases/wear-compose); [Android Developers Blog Wear OS 7](https://android-developers.googleblog.com/2026/05/whats-new-wear-os-7.html)
- [S] `ACQUIRE_CAUSES_WAKEUP`: "turns the screen on when the wake lock is acquired… typical use would be for notifications which are important for the user to see immediately"; exige el permiso `android.permission.WAKE_LOCK`. La fuente es una copia archivada de 2016 de la referencia de `PowerManager`; la página actual no se pudo leer — [PowerManager (archivo)](https://webarchive.library.unt.edu/web/20160706144303mp_/https://developer.android.com/reference/android/os/PowerManager.html)
- [S] `Activity.setTurnScreenOn(true)` hace que la pantalla se encienda si la Activity va a quedar visible y resumida, y normalmente se usa con `showWhenLocked` — [Referencia espejo (Microsoft Learn)](https://learn.microsoft.com/dotnet/api/android.app.activity.setturnscreenon)
- [V] Consejos de batería del GW7 para usuarios: apagar AOD, bajar el timeout de pantalla a 30 s, brillo manual, esferas oscuras y con pocas animaciones — [Android Police](https://www.androidpolice.com/samsung-galaxy-watch-7-extend-battery-life/)

### Inferences
- **Modo ahorro en Blindside:** con la pantalla en ambient, dibujar solo un anillo de contorno más un estado estático ("ARMADO", batería del cinturón) y placeholders (`--`). Los blancos vivos no tienen sentido a 1 actualización por minuto; la vibración es el canal de alerta. El usuario levanta la muñeca (gesto de despertar del sistema) o toca para volver a Interactive.
- Es probable que el ambient con la app visible dependa de que el usuario tenga activado "Always On Display" en el reloj; sin AOD la pantalla se apaga del todo. No lo verifiqué.
- **Despertar por evento:** candidatos a probar el primer día sobre el GW7 con One UI 8 y con la beta 9.
  1. Desde el FGS: `pm.newWakeLock(SCREEN_BRIGHT_WAKE_LOCK or ACQUIRE_CAUSES_WAKEUP, "latido:alert").acquire(3_000)`. Son flags deprecados que pueden ignorarse.
  2. Relanzar la Activity con `setTurnScreenOn(true)` y `setShowWhenLocked(true)` más `FLAG_ACTIVITY_SINGLE_TOP`. Puede chocar con las restricciones de arranque de Activities desde background.
  3. Notificación de alta prioridad. Probablemente enciende la pantalla, pero muestra la notificación en vez del radar.
  
  Si ninguna funciona, el diseño debe asumir "vibra → el usuario gira la muñeca".
- **Brillo:** `window.attributes = window.attributes.apply { screenBrightness = 0.3f }` es el override estándar por ventana (0..1; −1 = el del sistema). Sirve para bajar el consumo en modo siempre encendida; no verifiqué que One UI Watch lo respete.
- **Burn-in en modo siempre encendida:** la protección del sistema solo actúa en ambient. Con `keepScreenOn` durante horas, el anillo y la retícula estáticos pueden marcar el AMOLED. Mitigación: retícula tenue (gris oscuro), desplazamiento lento de 1–2 px cada pocos minutos y fondo negro puro (en AMOLED el negro es un píxel apagado).
- **Canvas a 30–60 fps:** 480×480 son 230 k píxeles; unas decenas de primitivas por frame son triviales para una Mali-G68 MP2. El costo real es energético. Recomendaciones:
  - Leer el estado dentro de la lambda de dibujo (`Canvas {}` o `Modifier.drawBehind {}`) para no recomponer.
  - `drawWithCache` para la retícula estática.
  - Cero asignaciones por frame.
  - Redibujar a la tasa de datos (10 Hz) e interpolar a ≤ 30 fps solo si hay animación de barrido.
  - Sin animación en ambient.
  - Medir con `adb shell dumpsys gfxinfo <pkg>` o Macrobenchmark.
- **Gestos a una mano (Wear Compose 1.7):** el doble pellizco y el giro de muñeca podrían servir para "silenciar o confirmar alerta" sin soltar la réplica. No sé si el GW7 los soporta.

### Gaps
- No hay documentación oficial de Wear OS ni de Samsung para sacar a una app del ambient o encender la pantalla desde código, ni evidencia de que `ACQUIRE_CAUSES_WAKEUP` funcione en One UI 8/9 Watch. No pude leer la referencia actual de `PowerManager`.
- No encontré la frecuencia de refresco del GW7 ni benchmarks de Compose Canvas sobre el W1000.
- No verifiqué que el override `screenBrightness` se respete en One UI Watch.

## 5. Hápticos: Vibrator/VibrationEffect en el Galaxy Watch 7 y patrones distinguibles

### Takeaway
La API es la estándar de Android: primitivas de composición desde API 30, `createWaveform` con amplitudes y el nuevo `VibrationEffect.Builder` con fallback automático en Android 16+. Samsung no publica qué soporta el actuador del GW7, así que hay que consultar `hasAmplitudeControl()` y `arePrimitivesSupported()` en runtime y diseñar patrones que funcionen solo con encendido y apagado, codificando por conteo y ritmo, no por intensidad.

### Cited Findings
- [V] Primitivas de `VibrationEffect.Composition` (Android 11 / API 30): `PRIMITIVE_CLICK`, `PRIMITIVE_TICK`, `PRIMITIVE_LOW_TICK`, `PRIMITIVE_SLOW_RISE`, `PRIMITIVE_QUICK_RISE`, `PRIMITIVE_QUICK_FALL`, `PRIMITIVE_SPIN`, `PRIMITIVE_THUD`. El soporte se consulta con `areAllPrimitivesSupported()`/`arePrimitivesSupported()` y la duración con `getPrimitiveDurations()` — [Custom haptic effects](https://developer.android.com/develop/ui/views/haptics/custom-haptic-effects)
- [V] "If a composition contains even one unsupported primitive, the entire vibration fails to play" (Android 11–15) — [Custom haptic effects](https://developer.android.com/develop/ui/views/haptics/custom-haptic-effects)
- [V] Efectos predefinidos: `EFFECT_CLICK`, `EFFECT_DOUBLE_CLICK`, `EFFECT_HEAVY_CLICK`, `EFFECT_TICK`, que se verifican con `areAllEffectsSupported()` — [Custom haptic effects](https://developer.android.com/develop/ui/views/haptics/custom-haptic-effects)
- [V] Amplitud: `hasAmplitudeControl()` y `VibrationEffect.createWaveform(timings, amplitudes /*0–255*/, repeat)`. En dispositivos sin control de amplitud "Non-zero amplitudes are rounded up to 100%" — [Custom haptic effects](https://developer.android.com/develop/ui/views/haptics/custom-haptic-effects)
- [V] APIs nuevas: "On Android 16 (26Q4) and higher, `VibrationEffect.Builder` is the preferred API", con "automatic framework-level fallback" para `Preset` y envolventes básicas. `BasicEnvelopeBuilder` trabaja con intensidad y nitidez 0..1 y debe terminar en intensidad 0 (si no, `IllegalStateException`). `WaveformEnvelopeBuilder` no tiene fallback, y se ignoran las frecuencias no soportadas. Se consulta con `areEnvelopeEffectsSupported()` y `VibratorFrequencyProfile`. La etiqueta "Android 16 (26Q4)" es ambigua en el propio doc — [Custom haptic effects](https://developer.android.com/develop/ui/views/haptics/custom-haptic-effects)
- [S] Existe un hilo del foro Samsung titulado "hasAmplitudeControl, createWaveform API", señal de que los desarrolladores de Galaxy Watch se preguntan por el soporte de amplitud. El contenido no cargó — [Samsung forum](https://forum.developer.samsung.com/t/hasamplitudecontrol-createwaveform-api/30327)
- [S] En Wear OS la amplitud solo se controla si `Vibrator.hasAmplitudeControl()` devuelve `true` — [Android haptics API reference](https://developer.android.com/develop/ui/views/haptics/haptics-apis)

### Inferences
- **Al arrancar**, registrar `hasAmplitudeControl()`, `arePrimitivesSupported(...)`, `areAllEffectsSupported(...)` y, en API 36+, `areEnvelopeEffectsSupported()` y el `VibratorFrequencyProfile`. Mostrarlo en una pantalla de diagnóstico, porque es la única forma fiable de saber qué hace el GW7.
- **Un solo actuador no da dirección espacial.** Codificar por conteo, que es lo más robusto en movimiento, y usar pulsos de ≥ 80–100 ms separados por ≥ 100–150 ms para que se distingan con el cuerpo en movimiento. Propuesta a validar con jugadores:
  - Izquierda: 1 pulso largo (~300 ms).
  - Centro: 2 pulsos cortos (~100 ms, gap 120 ms).
  - Derecha: 3 pulsos cortos.
  - Alternativa por ritmo (tipo morse): izquierda "largo-corto", derecha "corto-largo", centro "corto-corto".
  - Si hay amplitud: fuerte para blanco cercano (<3 m) y medio para lejano. Si no la hay: repetir el patrón para cercano.
- **Implementación portable:** `createWaveform` con amplitud 255 u on/off como base. Mejorar a `Composition` (`PRIMITIVE_THUD`/`PRIMITIVE_CLICK`) solo si `arePrimitivesSupported` devuelve todo `true`. En Wear OS 6 (Android 16) probar `VibrationEffect.Builder` protegido por `SDK_INT` y anotación de API.
- **Throttling de alertas:** una alerta por blanco nuevo, con cooldown de 2–3 s por sector, para evitar habituación y consumo.
- Usar `vibrate(effect, VibrationAttributes)` con uso `USAGE_ALARM` o `USAGE_NOTIFICATION` y probar la interacción con las opciones de intensidad de vibración, No molestar, "Modo teatro" y "Modo dormir" del reloj. Los jugadores podrían usar el modo teatro para mantener la pantalla oscura.

### Gaps
- No encontré el tipo de actuador del GW7 ni si soporta control de amplitud, qué primitivas soporta o su perfil de frecuencias.
- No sé si No molestar, Modo teatro o Modo dormir de One UI Watch silencian vibraciones de apps de terceros, ni si hay restricciones para vibrar desde un FGS con la pantalla apagada.

## 6. Batería: estimaciones para pantalla siempre encendida + notificaciones BLE continuas + sensores

### Takeaway
No existe una medición publicada de esta carga exacta. Puntos de anclaje:
- Samsung promete ~30 h con AOD encendido y ~40 h con AOD apagado.
- DC Rainmaker (44 mm) midió ~7,4 %/h en ciclismo y ~11 %/h en carrera con GNSS de doble frecuencia, es decir ~31–47 mA de media.

Mi estimación para Blindside, de baja confianza: ~4–10 %/h en modo ahorro y ~10–25 %/h con la pantalla siempre interactiva en el 44 mm. Una partida de 4 h es holgada en ahorro y marginal en siempre encendida, sobre todo en el 40 mm. Hay que medirlo con `batterystats`.

### Cited Findings
- [V] Capacidades: 300 mAh (40 mm) y 425 mAh (44 mm) — [Wikipedia GW7](https://en.wikipedia.org/wiki/Samsung_Galaxy_Watch_7)
- [V] DC Rainmaker probó el 44 mm sin LTE: "Battery claims are 30-hours smartwatch mode, with an always-on display enabled". En uso diario, "Most days I'm getting a solid 1.5 days". GNSS de doble frecuencia "on by default for all activities (there isn't a way to reduce it…)" — [DC Rainmaker GW7 review](https://www.dcrainmaker.com/2024/08/samsung-galaxy-reviewaccuracy.html)
- [V] Mediciones de DC Rainmaker con GPS:
  - Ciclismo de montaña: "Start battery: 91%, End battery: 23%… 64% in 8hr 40mins" (≈ 7,4 %/h).
  - Carrera: "52%… 38%… 14% in 1hr 16mins" (≈ 11 %/h).
  - Bug: tras una actividad con GPS "it almost appears to not shut off the GPS… very high battery burn until attached to a charger".
  
  — [DC Rainmaker](https://www.dcrainmaker.com/2024/08/samsung-galaxy-reviewaccuracy.html)
- [S] La cifra oficial de Samsung es "up to 40 hours with AOD off, and up to 30 hours with it on", "based on results from internal lab tests". Algunos usuarios reportan 20–26 h. Esto viene de un resumen del buscador que agrega varias reseñas; no está verificado en la página de Samsung — [GadgetBrains](https://gadgetbrains.com/galaxy-watch-7-40mm-battery-life/); [SlashGear](https://www.slashgear.com/1915429/samsung-galaxy-watch-7-setting-change-may-extend-battery-life/)
- [S] Reseñas de usuarios: 30–36 h en uso normal con AOD apagado y un entrenamiento GPS de 45 min al día — [RepReturn (fuente de baja calidad)](https://repreturn.com/samsung-galaxy-watch-7-review/)
- [V] Wear OS 7: "average users can expect up to 10% improvement in battery life" al pasar desde Wear OS 6 — [Android Developers Blog](https://android-developers.googleblog.com/2026/05/whats-new-wear-os-7.html)
- [V] `keepScreenOn` "severely impacts battery life" — [Always-on apps](https://developer.android.com/training/wearables/views/always-on)
- [S] GSMArena y DC Rainmaker no publican una prueba estandarizada con pantalla forzada encendida para el GW7. PhoneArena tiene una reseña titulada "falls short on battery life promises", pero devolvió 403 — [PhoneArena](https://www.phonearena.com/reviews/samsung-galaxy-watch-7-review_id6343)

### Inferences
- **Conversión a corriente media (44 mm, 425 mAh):** 7,4 %/h ≈ 31 mA; 11 %/h ≈ 47 mA. La promesa de 30 h con AOD da ≈ 14 mA de media en uso típico y la de 40 h sin AOD ≈ 10,6 mA.
- **Modo ahorro de Blindside** (pantalla apagada o ambient, BLE activo, gravedad a 16–50 Hz, tracking y vibraciones esporádicas): no hay GNSS, que es uno de los grandes consumidores en las cifras de DC Rainmaker, pero la CPU despierta 10–30 veces por segundo por las notificaciones. Estimo ~15–40 mA:
  - 44 mm: 3,5–9,5 %/h → 4 h ≈ 15–40 %.
  - 40 mm: 5–13 %/h → 4 h ≈ 20–55 %.
- **Modo siempre encendida** (AMOLED interactivo más render continuo): la pantalla probablemente domina, sobre todo con brillo alto al sol. Estimo ~40–100 mA:
  - 44 mm: ~10–25 %/h → 4 h ≈ 40–100 %.
  - 40 mm: 4 h de pantalla encendida a brillo alto podría no alcanzar.
- **Palancas, por impacto esperado:**
  1. Brillo bajo (`screenBrightness`) y UI mayormente negra.
  2. Render a la tasa de datos (10 Hz) en vez de 60 fps.
  3. Agrupar BLE en 10 notificaciones/s y probar `LOW_POWER`.
  4. Sensores a `SENSOR_DELAY_UI` con `maxReportLatencyUs` cuando la pantalla está apagada.
  5. Soltar wake locks al salir.
- **Plan de medición:**
  1. `adb shell dumpsys batterystats --reset`.
  2. Sesiones de 30 min por modo (ahorro o siempre encendida) × prioridad (BALANCED o LOW_POWER) × brillo.
  3. `adb shell dumpsys battery` y `dumpsys batterystats` (o Battery Historian) para %/h y wakeups.
  4. Repetir en exterior, donde el brillo automático sube.

### Gaps
- No hay mediciones publicadas del GW7 con la pantalla forzada encendida, ni con una conexión BLE de terceros continua, ni cifras por componente (panel, radio BT, CPU) del W1000 o el GW7.
- No pude verificar en la página de Samsung la cifra oficial "40 h / 30 h".

## 7. Flujo de desarrollo: Android Studio, emulador, depuración inalámbrica, minSdk/targetSdk y versiones de Compose for Wear OS

### Takeaway
A 30-sep-2026:
- Android Studio estable: **Quail 4 | 2026.1.4 Patch 1**. En RC: **Rabbit 1 | 2026.2.1 RC 2**.
- **AGP 9.4.0**.
- **Wear Compose 1.7.0** (23-sep-2026; usar `compose-material3`).
- **wear 1.4.0** y **wear-ongoing 1.1.0**.
- Emulador de Wear OS 7 disponible.

En el reloj físico solo hay depuración por Wi-Fi: la depuración por Bluetooth no existe desde Wear OS 3. Recomiendo targetSdk 36, que es lo que corre hoy el GW7 estable y lo que activa el ambient "visible atenuado". minSdk 34 si Blindside es solo para GW7, o 30 para abarcar GW4–6.

### Cited Findings
- [V] "Rabbit 1 | 2026.2.1 RC 2" (28-sep-2026, RC); "Quail 4 | 2026.1.4 Patch 1" (estable, 18-sep-2026); Android Gradle Plugin 9.4.0 estable (18-sep-2026) — [Android latest updates](https://developer.android.com/latest-updates). **Conflicto menor:** la página de releases de Studio, al extraerla, dio "Quail 4 | 2026.1.4 Patch 1" con fecha 28-abr-2026, probablemente un error de extracción — [Studio releases](https://developer.android.com/studio/releases)
- [V] Artefactos Wear estables del 23-sep-2026: `wear 1.4.0`, `wear-input 1.2.0`, `wear-ongoing 1.1.0`, `wear-phone-interactions 1.1.1`, `wear-remote-interactions 1.2.0`. Wear OS 7 salió el 16-jun-2026 y se puede probar en el emulador oficial — [Android latest updates](https://developer.android.com/latest-updates)
- [V] Historial de Wear Compose:
  - 1.7.0 estable (23-sep-2026): gestos a una mano, mejoras de `TransformingLazyColumn` y `SwipeToReveal`, anotaciones de rendimiento.
  - 1.7.0-rc01 (9-sep-2026).
  - 1.6.0 estable (25-mar-2026): Navigation3 (`compose-navigation3`), `LocalAmbientModeManager`, texto curvo con warp (API 34+).
  - 1.5.6 (3-dic-2025).
  
  "`compose-material` is superseded by `compose-material3`", recomendado por Material 3 Expressive (Wear OS 6) — [Wear Compose releases](https://developer.android.com/jetpack/androidx/releases/wear-compose)
- [V] Wear OS 7 se anunció con un "Wear OS 7 Canary Emulator, based on Android 17" — [Android Developers Blog](https://android-developers.googleblog.com/2026/05/whats-new-wear-os-7.html)
- [V] "Debugging over Bluetooth is no longer supported as of Wear OS 3" — [Debug a Wear OS app](https://developer.android.com/training/wearables/get-started/debugging)
- [V] Procedimiento Wi-Fi oficial:
  - Requisitos: `adb` ≥ 30.0.0 y la misma red Wi-Fi.
  - Activar Developer options, luego **ADB debugging**, luego **Wireless debugging** ("Allow" / "Always allow on this network").
  - Emparejar una sola vez: Wireless debugging → **Pair new device** muestra un código, IP y puerto; en el PC, `adb pair ip:pairing-port`.
  - Conectar: `adb connect ip:connection-port` ("The connection port is usually different from the pairing port"). Hay que reconectar "each time you restart wireless debugging or change Wi-Fi networks".
  - Con varios dispositivos: `adb -s ip:port`.
  - Si falla: las redes empresariales con "access point isolation" bloquean el tráfico (usar un hotspot móvil); probar `adb kill-server`/`start-server` y apagar y encender Wireless debugging.
  
  — [Debug Wear OS over Wi-Fi](https://developer.android.com/training/wearables/get-started/debug-wifi)
- [V] El comportamiento ambient de Wear OS 6 (app visible atenuada) aplica a apps con target SDK 36+ — [Always-on apps](https://developer.android.com/training/wearables/views/always-on)
- [V] Wear OS 6 está basado en Android 16 y Wear OS 7 en Android 17 — [Wikipedia Wear OS](https://en.wikipedia.org/wiki/Wear_OS)

### Inferences
- Base de Gradle sugerida:
  - `compileSdk 36` (o 37 cuando el SDK de Android 17 esté estable en Studio), `targetSdk 36`, `minSdk 34` si es solo GW7.
  - `androidx.wear.compose:compose-material3:1.7.0`, `compose-foundation:1.7.0` y `compose-navigation:1.7.0` (o `navigation3`).
  - `androidx.wear:wear:1.4.0`, que contiene `AmbientLifecycleObserver` para código basado en Views.
  - `androidx.wear:wear-ongoing:1.1.0`.
- En el GW7 las opciones de desarrollador se activan tocando varias veces "Versión de software" en Ajustes → Acerca del reloj → Información de software. Es la ruta típica de Samsung; la guía oficial solo dice "Enable developer options".
- Trampas prácticas, sin verificar:
  - El Wi-Fi del reloj puede apagarse para ahorrar energía cuando la pantalla se apaga o cuando hay enlace BT con el teléfono. Fijarlo en "siempre activo" durante la depuración y dejar el reloj en el cargador en sesiones largas.
  - Tras reiniciar hay que reconectar, y el puerto cambia.
- El GW7 necesita un teléfono Android con Galaxy Wearable para la configuración inicial. "Sin teléfono en el lazo" vale en runtime, no en el alta del dispositivo. No lo verifiqué en esta sesión.
- El emulador no puede conectarse al ESP32 real. Conviene:
  - Definir una interfaz `RadarLink` con dos implementaciones: BLE real y replay de capturas binarias grabadas con sello de tiempo.
  - Probar el módulo de tracking (Kotlin puro) en JVM.
  - Usar el emulador solo para UI, ambient (`KEYCODE_SLEEP`/`KEYCODE_WAKEUP`) y el flujo de permisos.
- Comandos útiles en el reloj: `adb shell dumpsys deviceidle force-idle` (Doze), `dumpsys batterystats`, `dumpsys gfxinfo` y `adb logcat -s BtGatt.GattService` para el throttling de escaneo.

### Gaps
- No verifiqué el minSdk exacto que exige Wear Compose 1.7.0 ni los plazos de target API de Google Play para Wear OS en 2026. Solo importan si se publica en Play; para sideload o F-Droid no.
- No verifiqué la ruta exacta de menús de One UI 8 Watch para activar las opciones de desarrollador.

## 8. Lado ESP32: NimBLE-Arduino como periférico GATT (MTU, throughput, intervalos, interop con Samsung, consumo)

### Takeaway
El ESP32-WROOM-32 es BLE 4.2: tiene Data Length Extension pero no PHY 2M. El MTU llega a 517 (`NimBLEDevice::setMTU` acepta hasta 527 y el preferido por defecto es 255). Entre dos ESP32 el throughput máximo ronda 90 KB/s. Blindside necesita ~1,5 KB/s en ~30 notificaciones por segundo, alrededor del 1,7 % de esa capacidad, así que cabe con holgura incluso con intervalos `LOW_POWER`.

Los riesgos reales son otros:
- Buffers de notificación agotados (`BLE_HS_ENOMEM`).
- La negociación de parámetros de conexión.
- La caché GATT de Android durante el desarrollo.
- Desconexiones por bloqueo del cuerpo o timeouts.

El ESP32 conectado consume de media ~35–43 mA según Espressif.

### Cited Findings
- [V] Throughput: "maximum throughput of Bluetooth LE communication between ESP32 boards can reach up to 700 Kbps, which is about 90 KB/s". MTU "default is 23 bytes; can be configured to reach 517 bytes". "Bluetooth 4.2 DLE is supported in all versions of ESP-IDF". El ESP32 original "only supports Bluetooth LE 4.2", sin 2M PHY. Hasta 9 clientes ("three clients should be good" para estabilidad) — [Espressif ESP-FAQ BLE](https://github.com/espressif/esp-faq/blob/a71fa4f/docs/en/software-framework/ble-bt.rst)
- [V] Consumo del ESP32 según Espressif: anunciando cada 40 ms, media 42,67 mA; conectado como esclavo a 20 ms y latencia 0, media 42,75 mA; a 80 ms y latencia 0, media 35,33 mA. Picos de 142,1 mA y mínimo de 32 mA — [ESP-FAQ](https://github.com/espressif/esp-faq/blob/a71fa4f/docs/en/software-framework/ble-bt.rst)
- [S] Con modem sleep el ESP32 consume ~14,1 mA típicos. La cifra viene de un resumen del buscador atribuible al ejemplo `power_save` de NimBLE y no coincide con el mínimo de 32 mA del FAQ, que usa otra configuración — [ESP-IDF nimble power_save README](https://github.com/espressif/esp-idf/blob/master/examples/bluetooth/nimble/power_save/README.md)
- [V] Parámetros de conexión:
  - "The step size for the connection interval is 1.25 ms, with a range from 7.5 ms (6 steps) to 4.0 s (3200 steps)".
  - El central fija los valores iniciales y cualquiera de las dos partes puede pedir actualización (`ble_gap_update_params()`).
  - La latencia de periférico permite saltarse eventos.
  - "Supervision Timeout defines the maximum time allowed between two successful connection events".
  - MTU por defecto 23; con DLE caben hasta 251 bytes de payload LL.
  
  — [ESP-IDF BLE connection guide](https://docs.espressif.com/projects/esp-idf/en/stable/esp32/api-guides/ble/get-started/ble-connection.html)
- [V] API de NimBLE-Arduino (docs de la versión 2.5.1) en `NimBLEServer`: `updateConnParams()` (intervalo, latencia, timeout), `setDataLen()` ("Data Length Extension updates (27-251 bytes)"), `getPeerMTU()`, `advertiseOnDisconnect()`, `updatePhy()` (máscaras 1M/2M/CODED), `getPeerInfo()`, `disconnect()`, `sendServiceChangedIndication()`, `setCallbacks()` y `startAdvertising()` — [NimBLEServer reference](https://h2zero.github.io/NimBLE-Arduino/class_nim_b_l_e_server.html)
- [S] `CONFIG_BT_NIMBLE_ATT_PREFERRED_MTU` vale 255 por defecto. `NimBLEDevice::setMTU()` acepta valores mayores que 23 y menores o iguales a `BLE_ATT_MTU_MAX` = 527. `CONFIG_BT_NIMBLE_MSYS1_BLOCK_COUNT` puede necesitar un aumento al enviar datos grandes — [NimBLE-Arduino config options](https://h2zero.github.io/NimBLE-Arduino/md__command__line__config.html); [NimBLE-Arduino issue #486](https://github.com/h2zero/NimBLE-Arduino/issues/486)
- [S] `BLE_HS_ENOMEM` significa buffers mbuf/msys agotados: se está enviando más rápido de lo que el stack drena. El evento `BLE_GAP_EVENT_NOTIFY_TX` reporta estado 0 o `BLE_HS_ENOMEM` — [esp-idf issue #9097](https://github.com/espressif/esp-idf/issues/9097); [esp-nimble issue #75](https://github.com/espressif/esp-nimble/issues/75)
- [V] Del lado Android (14+), el MTU final es `min(517, remoteMtu)` — [Android 14 behavior changes](https://developer.android.com/about/versions/14/behavior-changes-all)
- [V] Android cachea la base GATT y `refresh()` es una API oculta y desaconsejada — [Punch Through](https://punchthrough.com/android-ble-guide/)
- [S] Reportes de interoperabilidad:
  - Periféricos Nordic con teléfonos Samsung que se desconectan a los ~25 s con "Error 8 (0x8): GATT CONN TIMEOUT" — [Nordic DevZone](https://devzone.nordicsemi.com/f/nordic-q-a/53262/ble-disconnects-with-samsung-phones/216462).
  - Periféricos NimBLE que se desconectan con "reason 531" — [ESP32 forum t=30182](https://esp32.com/viewtopic.php?t=30182); [ESP32 forum t=17738](https://esp32.com/viewtopic.php?t=17738).
  - Ninguno es específico del Galaxy Watch.

### Inferences
- **Presupuesto de datos (verificación pedida):**

  | Flujo | Mensajes/s | Payload aprox. | B/s |
  |---|---|---|---|
  | Radar LD2450 A | 10 | ~40 B | 400 |
  | Radar LD2450 B | 10 | ~40 B | 400 |
  | IMU MPU6050 (5 muestras × ~12–14 B por notificación, 50 Hz) | 10 | ~70 B | 700 |
  | **Total** | **30** | — | **~1.500 B/s ≈ 12 kbit/s** |

  - Por notificación se suman 3 B de ATT (opcode + handle) y 4 B de L2CAP: radar ≈ 47 B e IMU ≈ 77 B a nivel L2CAP.
  - Con DLE (payload LL ≤ 251 B) cada notificación es un solo paquete LL. Sin DLE (27 B) el radar ocupa 2 fragmentos y el IMU 3, unos 70 paquetes LL/s: sigue siendo trivial.
  - El total equivale a ~1,7 % del máximo de ~90 KB/s entre ESP32.
  - El MTU **sí importa**: con MTU 23 (20 B útiles) un frame de 40 B no cabe en una notificación. El reloj debe llamar `requestMtu(517)`; con el 255 por defecto de NimBLE el resultado es 255 (252 B útiles).
- **Eventos de conexión y notificaciones por evento** (valores AOSP por defecto; el GW7 puede diferir):

  | Prioridad | Intervalo | Eventos/s | Notif./evento (30/s) | Notif./evento (agrupado 10/s) | Latencia extra máx. aprox. |
  |---|---|---|---|---|---|
  | HIGH | 11,25–15 ms | 67–89 | 0,3–0,45 | 0,1–0,15 | ~15 ms |
  | BALANCED | 30–50 ms | 20–33 | 0,9–1,5 | 0,3–0,5 | ~50 ms |
  | LOW_POWER | 100–125 ms (lat. 2) | 8–10 | 3–3,75 | 1–1,25 | ~125 ms |

  Todo cabe. En `LOW_POWER` el ESP32 debe tener en cola 3–4 notificaciones entre eventos, que es justo donde aparece `BLE_HS_ENOMEM` si los msys son pocos.
- **Formato recomendado:** una sola notificación cada 100 ms que agrupe radar A + radar B + 5 muestras IMU, más una cabecera de ~7 B (versión, `seq` uint16, `t_ms` uint32 del ESP32). Pesa ~120–160 B y cabe con MTU ≥ 185. Ventajas:
  - Divide por 3 los paquetes por segundo.
  - Alinea los relojes de los tres flujos.
  - `seq` permite detectar pérdidas (ENOMEM o desconexión) y `t_ms` medir la latencia de extremo a extremo.
  - Revisar siempre el retorno de `notify()` y contar descartes.
- **Parámetros desde el ESP32:** tras conectar, `updateConnParams(connHandle, 24, 40, 0, 400)` (30–50 ms, latencia 0, supervision timeout de 4 s). Android puede aceptarlo o imponer los suyos con `requestConnectionPriority`. Registrar el intervalo real con el callback de actualización de parámetros de NimBLE; en la 2.x debería existir un `onConnParamsUpdate(NimBLEConnInfo&)`, a verificar en la referencia.
- **DLE:** probar `setDataLen(connHandle, 251)` tras conectar. **PHY:** no llamar `updatePhy(2M)`, porque el WROOM-32 original es solo 4.2.
- **Anuncio:** incluir el UUID de servicio de 128 bits en los datos de anuncio para que funcione el `ScanFilter` del reloj, activar `advertiseOnDisconnect(true)` y usar un intervalo rápido (20–100 ms) durante los primeros ~30 s tras una desconexión.
- **Códigos de desconexión:** "531" en NimBLE equivale a 0x200 (`BLE_HS_ERR_HCI_BASE`) + 0x13 ("Remote User Terminated Connection"), es decir, el reloj cerró el enlace. 0x208 sería supervision timeout, típico de fuera de rango o bloqueo del cuerpo (cinturón frente a muñeca contraria). Es inferencia a partir de la convención de códigos de NimBLE.
- **Bloqueo del cuerpo:** 2,4 GHz se atenúa mucho a través del torso. Poner la antena PCB del WROOM hacia afuera, subir la potencia de TX al máximo con la API de NimBLE (nombre exacto a verificar, p. ej. `NimBLEDevice::setPower`) y usar un supervision timeout de 4–6 s para tolerar desvanecimientos breves.
- **Caché GATT en desarrollo:** si cambia la tabla de atributos, emitir `sendServiceChangedIndication()` o cambiar el UUID de servicio. Si no, borrar el emparejamiento o reiniciar el BT del reloj.
- **Emparejamiento y cifrado:** no son necesarios para el prototipo, y evitarlos ahorra diálogos de emparejamiento de Samsung. Con Android usando direcciones privadas aleatorias, no se puede filtrar al reloj por MAC en el ESP32 sin bonding (IRK).
- **Consumo del lado cinturón:** ~35–43 mA de la radio más CPU, más la lectura de dos UART a alta velocidad y el I²C del IMU, que impiden el light sleep. Presupuesto aproximado de 50–80 mA solo para el módulo ESP32, sin contar los radares.

### Gaps
- No encontré reportes de interoperabilidad específicos de Galaxy Watch 7 con NimBLE, ni datos de si el GW7 inicia DLE o qué intervalo concede.
- No verifiqué en esta sesión las firmas exactas de callbacks de NimBLE-Arduino 2.x (`onConnect`, `onMTUChange`, `onConnParamsUpdate`, `onSubscribe`) ni el nombre de la API de potencia de TX.
- Las cifras de consumo de Espressif no dicen con qué frecuencia de CPU ni si había modem o light sleep, así que la estimación del cinturón es orientativa.
