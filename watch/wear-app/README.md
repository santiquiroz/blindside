# Blindside: app del reloj (`wear-app`)

App de Wear OS para el Galaxy Watch 7. Recibe por BLE los paquetes del cinturón, los procesa con `radar-core`, dibuja el radar, vibra por lado y graba la partida completa en `.bsrec`.

## Compilar y probar

Desde `watch/`:

```bash
./gradlew :android-shared:testDebugUnitTest :wear-app:testDebugUnitTest   # pruebas JVM (código compartido y del reloj)
./gradlew :wear-app:assembleDebug       # APK en wear-app/build/outputs/apk/debug/wear-app-debug.apk
```

El código que no depende de Wear OS (BLE, grabación, ajustes, vibración, sesión y dibujo del radar) vive en `watch/android-shared` y lo comparte la app del celular.

## Instalar en el reloj (depuración por Wi-Fi)

1. En el reloj: Ajustes → Acerca del reloj → Información de software → tocar "Versión de software" hasta activar las opciones de desarrollador. Luego, Opciones de desarrollador → Depuración ADB → Depuración inalámbrica.
2. Emparejar una sola vez: "Emparejar nuevo dispositivo" muestra IP, puerto y código. En el PC: `adb pair IP:PUERTO_DE_EMPAREJAMIENTO`.
3. Conectar: `adb connect IP:PUERTO_DE_CONEXIÓN`. Es otro puerto, y cambia al reiniciar la depuración inalámbrica.
4. `adb install -r wear-app/build/outputs/apk/debug/wear-app-debug.apk`

## Primer emparejamiento con el cinturón

1. Enciende el cinturón. Sin un bond guardado, la ventana de emparejamiento se abre sola (60 s, LED parpadeando). Si ya hay uno, mantén BOOT 3 s en el primer minuto tras encender y suéltalo.
2. En el reloj: Blindside → "Iniciar partida". Concede Bluetooth (dispositivos cercanos) y actividad física.
3. El reloj conecta y lee `info`. Como todavía no hay bond, Android pide la clave: el estado dice "Emparejando: escribe la clave de la etiqueta" y aparece el diálogo del sistema. Escribe los 6 dígitos de la etiqueta del cinturón antes de que se cierre la ventana.
4. El estado pasa a "Recibiendo datos" y el reloj recuerda el cinturón.
5. Si sale "Clave incorrecta o ventana cerrada", vuelve a abrir la ventana (BOOT 3 s) y toca "Reintentar" en Inicio.

Para cambiar de cinturón: Ajustes → "Cinturón" (olvida la dirección) y olvídalo también en los ajustes de Bluetooth del reloj.

## Si el cinturón olvidó este reloj

Pasa tras borrar los bonds del cinturón (BOOT 10 s), emparejar otro reloj o borrar la flash. Tras dos intentos fallidos seguidos, el reloj deja de reconectar y de escanear, y muestra "El cinturón olvidó este reloj: olvídalo en los ajustes Bluetooth del reloj y vuelve a emparejar". La app no puede borrar el bond por su cuenta.

1. En el reloj, olvida "Blindside-XXXX" en los ajustes de Bluetooth. Ruta exacta en el GW7: (anótala aquí tras el spike S10).
2. Abre la ventana del cinturón (se abre sola si borraste los bonds) y toca "Reintentar" en Inicio. Empareja como la primera vez.

Si alguna vez sale "MTU insuficiente", el reloj ya reconectó una vez por su cuenta. Toca "Reintentar"; si vuelve a salir, anota el modelo del reloj y la versión del firmware. El registro `info` de la grabación trae el MTU que vio el cinturón.

## Grabaciones `.bsrec`

- Se guardan en `/sdcard/Android/data/io.github.santiquiroz.blindside/files/recordings/` con nombres `blindside-belt-AAAAMMDD-HHMMSS.bsrec` (o `-demo-`).
- La cabecera JSON lleva la primera lectura de `info` del cinturón (o `null` en la demo o si el cinturón no respondió en 10 s), `"config"` (`TuningParams` y los montajes, escritos por `PipelineConfig.toJson()` de `radar-core`, que `pipelineConfigFromHeader` relee al reproducir) y la hora de inicio.
- Tamaño esperado en 5 h: ~43 MB de paquetes BLE, ~5 MB de gravedad y ~5 MB de giroscopio del reloj, unos **53 MB** en total.
- Desde Git Bash, pon `MSYS_NO_PATHCONV=1` antes de cada comando con rutas `/sdcard/...`.

```bash
MSYS_NO_PATHCONV=1 adb shell ls -l /sdcard/Android/data/io.github.santiquiroz.blindside/files/recordings/
MSYS_NO_PATHCONV=1 adb pull /sdcard/Android/data/io.github.santiquiroz.blindside/files/recordings/ ./recordings/
```

Si `adb pull` no tiene permiso sobre `Android/data`, usa `run-as` (funciona con el APK de depuración):

```bash
MSYS_NO_PATHCONV=1 adb exec-out run-as io.github.santiquiroz.blindside cat /sdcard/Android/data/io.github.santiquiroz.blindside/files/recordings/ARCHIVO.bsrec > ARCHIVO.bsrec
```

Revisa que el archivo bajado pese lo mismo que en `ls -l`: un pull fallido puede dejar un archivo vacío sin dar error.

## Diagnóstico (spikes)

Ajustes → "Diagnóstico (spikes)" abre la pantalla de los spikes S1, S3, S10 y S12 (2-3 oct): "Partida (cinturón)", "Demo", "Wake lock: sí/no (S1)", "Vibración: ALARM/NOTIFICATION" y, con la partida activa, "Reintentar", "Marcar" y "Detener". Graba igual que una partida normal. Es una herramienta de banco: no pide la práctica previa, así que el día de juego la partida se inicia desde Inicio.

## Lista de verificación en el reloj

Marca cada punto en el reloj real. Si un punto falla, anota qué viste y la hora, para ubicarlo en la grabación. `adb logcat -s BeltGatt BeltLink` muestra cada paso del enlace.

**Antes de jugar (app)**
1. [ ] La app abre y muestra "Blindside" con Iniciar/Demo/Práctica/Ajustes. Ajustes → "Diagnóstico (spikes)" abre "Blindside (spikes)" con sus botones.
2. [ ] **Permisos:** deniega Bluetooth → la partida no arranca, aparece el mensaje y "Abrir ajustes" lleva a los ajustes del reloj. Concédelo y deniega actividad física → la partida arranca y el estado mínimo muestra "SIN PASOS". Después, con la app cerrada, quita el permiso de Bluetooth en los ajustes del reloj y vuelve a iniciar una partida: la app lo pide otra vez y, si lo niegas, muestra el mensaje sin cerrarse.
3. [ ] **Práctica (S3):** "Probar izquierda/centro/derecha/sistema" se distinguen con el brazo en movimiento. El quiz pasa con 9/10 o más. Repítelo con No molestar, modo teatro y modo dormir, y con la intensidad de vibración del sistema baja. Anota qué combinación silencia la vibración y cambia Ajustes → Vibración (Alarma/Notificación) según el resultado. Con No molestar activo debe aparecer el aviso.
4. [ ] **Demo:** el radar dibuja el abanico y contactos rojos que se mueven, vibra con el ritmo del lado correcto y en "Inicio" aparece el nombre de la grabación.
5. [ ] **Emparejamiento (S12):** el primer emparejamiento con la clave de la etiqueta funciona (el diálogo aparece al leer `info`); tras reiniciar el reloj, "Iniciar partida" reconecta solo, sin pedir la clave. Con la ventana cerrada o una clave equivocada sale "Clave incorrecta o ventana cerrada"; al abrir la ventana y tocar "Reintentar", empareja.
6. [ ] **Bond perdido:** con la partida activa, apaga el cinturón y vuelve a encenderlo manteniendo BOOT 10 s (borra los bonds). Tras dos intentos sale "El cinturón olvidó este reloj…", y `logcat` deja de mostrar conexiones nuevas. Olvida el cinturón en Bluetooth del reloj y toca "Reintentar": empareja de nuevo.

**Con el cinturón (patio)**
7. [ ] **Datos:** en diestro, el abanico va de −100° a +80°, con anillos a 2 y 4 m. Una persona caminando al frente aparece y el reloj vibra con el lado correcto (criterio 3: ≥ 90 % en el modo práctica).
8. [ ] **Enlace:** apaga el cinturón → en unos 4-5 s la pantalla pasa a gris con "--" y hay **un solo** zumbido de sistema. Enciéndelo → los datos vuelven en ≤ 5 s (criterio 6). Repite 3 veces.
9. [ ] **Eliminado:** "ME DIERON" (en el radar, en Inicio o en la acción de la notificación) → ninguna vibración de contacto y pantalla sin contactos ("ELIMINADO"); las de sistema siguen. "REAPARECÍ" lo devuelve. Iniciar una partida nueva siempre empieza en juego.
10. [ ] **Sigilo:** con un contacto nuevo el reloj vibra y la pantalla **no** se enciende sola. Al levantar la muñeca se ve el radar. En ambient solo quedan el abanico atenuado y "--": sin botón, sin estado mínimo, sin bloques sólidos.
11. [ ] **Vista:** la pantalla no se apaga, y el dibujo se corre 2 px cada 3 min. En Vista + eliminado la pantalla sí se apaga.
12. [ ] **Pantalla apagada (S1):** 10 min con la pantalla apagada y un compañero cruzando cada minuto → todas las vibraciones llegan. Repite tras `adb shell dumpsys deviceidle force-idle`.
13. [ ] **Marcar rival:** "Marcar rival" en Inicio deja un registro `MANUAL_MARKER` (verificar al reproducir la grabación).
14. [ ] **Detener:** dos toques en "Detener partida" → desaparecen el ícono de la esfera y la notificación, Inicio muestra la grabación como "Última grabación", y `adb logcat -s BeltGatt` muestra `WriteSessionActive(active=false) -> status 0` (SESSION_ACTIVE = 0).
15. [ ] **Ongoing Activity:** con la partida activa hay un ícono en la esfera; al tocarlo vuelves a la app.
16. [ ] **Bluetooth apagado** a mitad de partida → "Bluetooth apagado" y **un solo** zumbido de sistema; al encenderlo, reconecta solo.

**Resistencia (8-oct, S8; criterios 5 y 7)**
17. [ ] Partida de 5 h en Sigilo con la pantalla apagada: la app no muere, la batería termina ≥ 17 % (objetivo ≥ 6 h en Sigilo), y se anota el % por hora. Repite 1 h en Vista (objetivo ≥ 3 h).
18. [ ] La grabación de 5 h se baja con `adb pull` y pesa unos 53 MB (≈ 43 MB de paquetes BLE + ~5 MB de gravedad + ~5 MB de giroscopio). Su cabecera trae el `info` del cinturón, y `radar-core` la reproduce entera.

**Día de juego (10/11-oct)**
19. [ ] Quiz de vibración aprobado el mismo día, No molestar apagado, reloj cargado, cargador del reloj en el power bank y modo Sigilo.

**Fase 2: reloj y celular**
20. [ ] **Rol del enlace:** al conectar, `adb logcat -s BeltGatt` muestra `WriteCommand(command=SetRole(role=WATCH)) -> status 0` después de `ReadInfo` y antes de `EnableStreamNotify`. Con el firmware del MVP, que ignora `06`, el radar recibe datos igual que antes.
21. [ ] **Emparejar celular:** con el radar conectado, Ajustes → "Emparejar celular" pasa a "Pedida al cinturón (60 s)" y `adb logcat -s BeltGatt` muestra `WriteCommand(command=OpenPairingWindow) -> status 0`. Al minuto el chip vuelve a "Abre la ventana 60 s". Con el firmware de fase 2, la línea `diag` del cinturón muestra la ventana abierta 60 s; con el del MVP no se abre nada aunque el chip diga "Pedida". Sin partida, el chip dice "Primero inicia el radar", no se envía nada y no queda ninguna notificación.
22. [ ] **Puente con el celular.** Requiere la app del celular, instalada con la misma clave de depuración que el reloj.
    - "Traer del reloj" lista las grabaciones del reloj, sin la que se está grabando, y descarga una completa. Pedir una que no existe termina en un aviso, nunca en un archivo vacío.
    - Al cambiar la mano en el celular, Ajustes del reloj la muestra.
    - Con la partida corriendo, el celular muestra el estado del reloj cada 5 s, y "sesión inactiva" al detenerla.
    - "Emparejar este celular" desde el celular pide la ventana igual que el chip del reloj.
23. [ ] **Sin celular:** con el Bluetooth del celular apagado, una partida de 10 min en el reloj se comporta igual. `adb logcat -s BridgeListener DataLayer` puede mostrar avisos, pero nada se detiene.
