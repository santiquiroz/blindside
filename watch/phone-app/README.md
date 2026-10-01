# Blindside: app del celular (`phone-app`)

App para el Galaxy S25 Ultra. Se conecta al cinturón con el **rol celular** (`06 01`), corre su propio `RadarPipeline` sobre la misma sesión compartida que el reloj, graba como el reloj, trae grabaciones del reloj por el Data Layer de Wear OS, las reproduce con mapa de calor y resumen, y muestra el diagnóstico del cinturón.

El reloj tiene prioridad:
- el celular no pide intervalos de conexión después de conectar, así el cinturón le deja 60-100 ms;
- el cinturón descarta los paquetes del celular antes que los del reloj;
- el celular solo arranca solo y solo guía el emparejamiento con el firmware 0.2.0 del cinturón.

## Desviaciones frente al spec

- **`minSdk 33`, no 31.** `android-shared` usa las sobrecargas GATT de API 33 (plan 05, D1); con 31 el manifiesto no fusiona y Android 12 fallaría.
- **"Conectar para diagnóstico".** Enlace con rol celular que escribe `04 00` y no graba, para que "Identificar" funcione.
- **Sin sensores del celular.** El radar del celular usa solo las IMUs del cinturón (spec §8), y sus grabaciones no traen registros del reloj. No pide "actividad física".
- **Arranque automático y guía de emparejamiento solo con firmware 0.2.0.** Con el 0.1.0 el cinturón tiene una conexión y un emparejamiento: el celular le quitaría el lugar al reloj.
- **Fuentes.** Las de `android-shared` (JetBrains Mono NL e IBM Plex Sans).

## Compilar y probar

Desde `watch/`:

```bash
./gradlew :phone-app:testDebugUnitTest   # pruebas JVM
./gradlew :phone-app:assembleDebug       # APK en phone-app/build/outputs/apk/debug/phone-app-debug.apk
```

## Instalar en el celular (depuración por Wi-Fi)

1. En el celular: Ajustes → Acerca del teléfono → Información de software → tocar "Número de compilación" 7 veces. Luego Opciones de desarrollador → Depuración inalámbrica.
2. Emparejar una vez: `adb pair IP:PUERTO_DE_EMPAREJAMIENTO` (el S25 suele estar en `192.168.10.119`).
3. Conectar: `adb connect 192.168.10.119:PUERTO`. Si el puerto rota, usa el nombre mDNS que muestra `adb mdns services`.
4. `adb -s 192.168.10.119:PUERTO install -r phone-app/build/outputs/apk/debug/phone-app-debug.apk`

**El reloj y el celular deben llevar APKs firmados con la misma clave**: el Data Layer solo une apps con el mismo paquete y la misma firma. Compílalos e instálalos desde el mismo PC: los dos usan `~/.android/debug.keystore`.

## Emparejar el celular con el cinturón (sin tocar el botón)

Requiere el firmware 0.2.0 del cinturón. Con el 0.1.0, emparejar el celular desempareja el reloj.

1. Con el radar del reloj en marcha, en el celular: Cinturón → **Pedir al reloj que abra la ventana**. El reloj envía `05` y el cinturón abre su ventana 60 s.
   - Sin reloj: mantén BOOT 3 s en el primer minuto tras encender el cinturón.
2. En el celular: **Iniciar radar**. Concede "Dispositivos cercanos" y, si quieres, las notificaciones.
3. Cuando Android pida la clave, escribe los 6 dígitos de la etiqueta del cinturón.
4. El estado pasa a "Recibiendo datos". En el diagnóstico del cinturón aparecen dos conexiones: reloj y celular.

Si el reloj y otro celular ya están conectados, no queda espacio: desconecta uno.

## Pestañas

- **Radar:**
  - Sin radar en marcha: el botón grande "Iniciar radar".
  - Con el radar en marcha: el abanico compartido con el reloj, la lista de contactos (distancia, rumbo, confianza y antigüedad), el estado del enlace y de los sensores, y el botón grande de modo eliminado.
  - La notificación tiene "Eliminado" y "Detener".
  - Arranca solo al abrir si el cinturón ya está emparejado, tiene el firmware 0.2.0 e "Iniciar radar al abrir" está activo.
- **Grabaciones:**
  - Las `.bsrec` de este celular (`blindside-phone-…`) y las traídas del reloj (`blindside-belt-…`).
  - "Traer del reloj" lista las del reloj y baja la que elijas con barra de progreso y "Cancelar". Una descarga incompleta, cortada o detenida 15 s no deja archivo.
  - Compartir abre el diálogo de Android; Borrar pide confirmación.
  - La grabación en curso dice "Grabando…" y no se abre, comparte ni borra hasta detener el radar.
- **Visor:** reproducción 1×/2×/4×/8× con línea de tiempo deslizable, mapa de calor de contactos (celdas de 0,5 m en el marco del cuerpo) y resumen: duración, contactos por minuto, alertas, huecos de enlace, % caminando/quieto y latencia de confirmación. Un archivo dañado muestra "No se pudo abrir…" y vuelve a Grabaciones.
- **Cinturón:**
  - "Conectar para diagnóstico" conecta sin marcar partida activa, así "Identificar" funciona.
  - Muestra `info` legible, contadores y señal.
  - Permite reiniciar cada radar e identificar el cinturón. "Identificar" se deshabilita con una partida activa en el reloj o en el celular.
  - Incluye los ajustes compartidos con el reloj (mano, ángulos, signos) y los del celular (vibración, apagada por defecto; arranque automático).

## Grabaciones en el celular

```bash
MSYS_NO_PATHCONV=1 adb shell ls -l /sdcard/Android/data/io.github.santiquiroz.blindside/files/recordings/
MSYS_NO_PATHCONV=1 adb pull /sdcard/Android/data/io.github.santiquiroz.blindside/files/recordings/ ./recordings/
```

## Logs útiles

```bash
adb logcat -s BlindsideSession BlindsideService PhoneBridge PhoneBridgeListener BeltGatt BeltLink
```

## E2E autónomo (spec §7, pasos 3-7)

```bash
cd watch && ./gradlew :phone-app:assembleDebug :wear-app:assembleDebug
BELT_PORT=COM6 PASSKEY=799334 bash phone-app/e2e/phone_e2e.sh
```

El informe queda en `phone-app/build/e2e/report.txt`: una línea por paso con PASA, FALLA o NO EJECUTADO y el motivo. Lo que no se ejecute se revisa a mano con la lista de abajo.

## Fuentes

JetBrains Mono NL 2.304 e IBM Plex Sans 1.1.0, ambas bajo SIL Open Font License 1.1. Viven en `watch/android-shared/src/main/res/font` y sus licencias en `licenses/`, en la raíz del repositorio.

## Lista de verificación en el dispositivo

"E2E" marca lo que automatiza `phone_e2e.sh`; repítelo a mano solo si el informe dice NO EJECUTADO.

- [ ] (§7 paso 3, E2E) La app se instala y abre con fondo negro y una barra inferior de 4 pestañas (Radar, Grabaciones, Visor, Cinturón) con icono y texto.
- [ ] Radar sin sesión: botón redondo grande "Iniciar radar" y la pista de emparejamiento.
- [ ] (§7 paso 4, E2E) Emparejamiento sin manos:
  - "Pedir al reloj que abra la ventana" responde "Pedido enviado…".
  - El diálogo de clave aparece y acepta la clave de la etiqueta.
  - Con el radar del reloj detenido, el mismo botón dice "En el reloj, inicia el radar primero…".
- [ ] (§7 paso 5, E2E) La línea `diag` muestra `link0`/`link1` con `role=watch` y `role=phone`, los dos `trusted=1 sub=1`, `sent` subiendo en ambos y `dropped=0` en el reloj. Además:
  - El `itvl` del celular está entre 48 y 80 unidades (60-100 ms).
  - En Cinturón → Diagnóstico, "Conexión celular" está entre 60,0 y 100,0 ms.
- [ ] Radar en vivo:
  - Contactos con forma según la confianza (relleno, contorno, punteado), y los que esperan medida en verde atenuado.
  - Números en monoespaciada; chips de enlace, radares e IMUs con icono.
  - Botón "ME DIERON"/"REAPARECÍ" de 64 dp.
- [ ] Cambiar entre Radar y Cinturón varias veces con el radar en marcha: el radar y los contadores siguen moviéndose.
- [ ] La vibración del celular está apagada por defecto; al activarla en Cinturón → Este celular, vibra en el siguiente radar.
- [ ] Notificación persistente con "Eliminado" y "Detener". "Detener" cierra la grabación y suelta el cinturón; el reloj sigue recibiendo sin cortes.
- [ ] (§7 paso 6, E2E) `am force-stop io.github.santiquiroz.blindside` en el celular: el `diag` muestra el enlace del celular como `[-]`, el reloj sigue con `sent` subiendo y `dropped=0`, y nRF Connect vuelve a ver el cinturón anunciándose.
- [ ] (§7 paso 7, E2E) Grabaciones → Traer del reloj:
  - Aparece la lista y la descarga avanza con progreso hasta que el archivo aparece en Grabaciones; "Ya está" marca las que ya existen.
  - Abrirla en el Visor muestra el resumen sin errores.
- [ ] Una descarga se puede cancelar: no queda archivo ni `.part`. Alejar el reloj a mitad de descarga termina en "el reloj dejó de enviar datos" en unos 15 s.
- [ ] Sin reloj conectado: "Traer del reloj" y "Pedir al reloj…" muestran un mensaje en menos de 10 s, y el radar del celular sigue funcionando.
- [ ] Con el radar del celular en marcha, su grabación dice "Grabando…" y no tiene Compartir ni Borrar.
- [ ] Compartir abre el diálogo de Android. Borrar pide confirmación, la grabación desaparece y, si estaba abierta, el Visor se vacía.
- [ ] Visor:
  - Barra de análisis, play/pausa y 1×/2×/4×/8×.
  - Deslizar hacia atrás muestra "Buscando…" y luego la escena correcta.
  - Mapa de calor sobre el abanico, con leyenda en segundos, y resumen con las 7 filas.
  - Salir del Visor a mitad del análisis y volver: el porcentaje sigue donde iba, no vuelve a 0 %. Abrir otra grabación detiene el análisis de la anterior.
  - Con el radar del celular en marcha, analizar una grabación larga no frena el radar en vivo.
- [ ] Cinturón: "Conectar para diagnóstico" muestra el info legible (firmware, radares, IMUs, conexiones, potencia, MTU), los contadores y "Actualizar".
- [ ] Con el radar recién iniciado (buscando, conectando o pidiendo la clave), Diagnóstico dice "Esperando la información del cinturón…" y no ofrece "Conectar para diagnóstico", que cortaría la partida.
- [ ] "Reiniciar radar A/B": el chip del radar cae y vuelve en pocos segundos.
- [ ] "Identificar":
  - Está deshabilitado con el radar del celular en marcha o con el reloj en partida.
  - En diagnóstico, con el reloj sin partida, el LED parpadea 3 veces. Si no parpadea, el reloj pudo quedar con una partida abierta: detenla en el reloj.
- [ ] (paso 5b, E2E) Puente con el reloj en partida:
  - Cinturón → Enlace muestra "Reloj: partida activa…" (`/status`).
  - La mano elegida en el celular llega al reloj, la del reloj llega al celular y vuelve a la original (`/settings`). Si hay ángulos personalizados el E2E no cambia la mano, porque cambiarla los borra.
- [ ] Ajustes compartidos:
  - Cambiar la mano o un ángulo en el celular se ve en Ajustes del reloj, y al revés, con el ángulo exacto.
  - Un celular con el reloj del sistema atrasado no pierde su cambio.
- [ ] Con el firmware 0.1.0 en el cinturón:
  - El celular no arranca solo y Cinturón muestra "Actualiza el firmware…".
  - "Pedir al reloj…" está deshabilitado.
- [ ] Con "Quitar animaciones" activo en Accesibilidad, el cambio de pestaña no tiene fundido.
- [ ] TalkBack lee las pestañas, los botones, las filas de contactos y la descripción del radar.
