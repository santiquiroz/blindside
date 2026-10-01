# Blindside firmware: checklist de hardware (Santiago)

Los agentes no tienen hardware. Estos pasos son los únicos que validan el firmware de verdad.
Comandos desde `firmware/`. Monitor serie: `pio device monitor -b 115200`.
Línea de diagnóstico cada 5 s (`reads` y `smp` cuentan solo los últimos 5 s; `gap` es el Δt mínimo..máximo entre tramas en esa ventana):
`diag up=… radar0[alive ok bad rst baud gap=min..max] radar1[…] imu0[ok who reads fail rep smp] imu1[…] link[conn sub trusted mtu itvl lat sup disc] tx[sent fail dropped skipped]`.
Además, cada cambio de estado de un IMU sale en su propia línea: `imu N: DOWN at <ms>` / `imu N: ok at <ms>`.

## Preparación

- [ ] **H0. CI.** El remoto `origin` ya existe (`https://github.com/santiquiroz/blindside.git`). Solo falta autorizar el push: `unset GITHUB_TOKEN` (en este PC apunta a la cuenta de trabajo) y `git push -u origin main`. Los jobs `vectors` y `firmware` deben quedar en verde; `radar-core` y `wear-app` en verde con aviso de "skipping" hasta que existan los planes 01 y 03. Sin compilador local, `python firmware/tools/run_native_tests.py` (con `python -m pip install --user ziglang`) corre las mismas suites antes del push.
- [ ] **H1. Flasheo.** `pio run -e esp32dev -t upload`. En el monitor: `blindside fw 0.1.0 boot_id=… reset=POWERON` y `blindside passkey: NNNNNN`. **Anotar la clave en una etiqueta del cinturón.**
- [ ] **H2. Pruebas en la placa.** `pio test -e esp32dev`: todas las suites terminan en `0 Failures`.
- [ ] **H3. Cableado (spec §2.2).** Radar A: RX2 GPIO16 ← TX del radar, TX2 GPIO17 → RX del radar. Radar B: GPIO26 ← TX, GPIO27 → RX. IMU A: SDA GPIO32, SCL GPIO33. IMU B: SDA GPIO21, SCL GPIO22. IMUs a 3V3, radares a 5V. GPIO12 libre. Arrancar **sin** presionar BOOT.

## Radares

- [ ] **H4. Arranque.** Para cada radar: `read-firmware ok`, `multi-target ok`, `bluetooth-off ok`, `restart ok` y `radar N: baud=256000 fw=V2.…`. Con HLKRadarTool, después de este arranque el LD2450 ya no aparece por Bluetooth (§10.3).
- [ ] **H4b. Radar tardío.** Arrancar con el conector del radar B suelto: `radar 1: baud=0 fw=`. Conectarlo: si sus tramas llegan a 256000, de inmediato `radar 1: enable-config ok`, `read-firmware ok`, …, `radar 1: baud=256000 fw=V2.…`; si no, lo mismo en el siguiente reintento (≤ 30 s). En los dos casos el LD2450 deja de aparecer por Bluetooth sin reiniciar el ESP32.
- [ ] **H5. Tramas.** `radar0[ok=…]` y `radar1[ok=…]` suben ~50 entre dos líneas `diag` (10 tramas/s, haya o no personas al frente) y `gap` queda cerca de `100..100`. Con el cinturón quieto 5 min, `bad` no sube.
- [ ] **H6. Watchdog.** Desconectar 5 s el hilo TX del radar B con todo encendido: `radar1[alive=0]` y, ~2 s después de la última trama, `radar 1: restart sent (total 1)`. Reconectar: `alive=1` (inmediato si el módulo no se colgó; si no, en el siguiente reintento, ≤ 30 s).
- [ ] **H16. Reinicio por control.** Desde la app (o nRF Connect ya emparejado, escribiendo `01 00` en `control`): `radar 0: restart sent` y las tramas vuelven.

## IMUs

- [ ] **H7. Detección y caída.** `imu0[ok=1 who=0x68 …]` (un clon con 0x70/0x71/0x98 también debe decir `ok=1`). Desconectar el SDA de la IMU A: sale `imu 0: DOWN at …`; reconectar: `imu 0: ok at …` en ≤ 2 s.
- [ ] **H7b. Cable de IMU en falla (spec §9).** Con los radares transmitiendo, mantener el SDA de la IMU A a GND 10 s y soltarlo. Pasa si se cumplen las cuatro:
  - `gap` de los dos radares no cambia más de ±2 ms frente a la corrida sana (H5);
  - la IMU B sigue con `reads` ≥ 995 y `smp` = 250 por línea, es decir ≥ 199 lecturas/s y n = 5 por corte;
  - el bit2 cae en ≤ 100 ms mientras el bit3 sigue en 1: `imu 0: DOWN at T1` aparece ≤ 100 ms después de poner el SDA a GND (cronometrar contra el video del monitor o un segundo observador), y `imu1[ok=1]` no cambia;
  - la IMU A vuelve sola: `imu 0: ok at T2` con T2 ≤ 2 s después de soltar el SDA.
- [ ] **H8. Ritmo.** Durante 10 min con los dos radares y las dos IMUs: `reads` ≥ 995 y `smp` = 250 por línea en cada IMU (≥ 199 lecturas/s, 50 muestras/s), y `rep` no sube.

## BLE, emparejamiento y seguridad

- [ ] **H9. Primer emparejamiento.** Sin bond, el LED parpadea 60 s. Con la app del reloj (plan 03) o nRF Connect: conectar, escribir la clave, ver `pairing: bonded …` y que el LED deja de parpadear.
- [ ] **H10. Spike S10 (decisión pendiente de Santiago).** Con `config::kConnectWhitelistOnly = false` (por defecto): apagar y encender el ESP32 y medir la reconexión del reloj (≤ 5 s); repetir tras > 15 min (rotación de la dirección privada). Opcional: poner `true`, recompilar y repetir; si el reloj ya no reconecta fuera de la ventana, volver a `false` (plan B) y anotarlo en la spec. Si con `false` el reloj emparejado sale desconectado con `pairing: dropped an unknown or unauthenticated peer`, NimBLE no resolvió su dirección: anotarlo, es un fallo de S10.
- [ ] **H11. Extraños.** Fuera de la ventana, un teléfono con nRF Connect no logra datos: con el filtro apagado conecta y queda desconectado de inmediato (`pairing: dropped …`, < 0,5 s); con el filtro encendido no conecta. Dentro de la ventana conecta, pero suscribirse a `stream` o leer `info` pide la clave y falla sin ella.
- [ ] **H19. Rival en bucle.** Con el reloj emparejado desconectado (Bluetooth del reloj apagado), poner un teléfono con nRF Connect a reconectarse en bucle al cinturón. Encender el Bluetooth del reloj: reconecta en ≤ 5 s igual que sin el rival.
- [ ] **H12. Botón BOOT** (solo cuenta en los primeros 60 s tras encender, y actúa al soltar). Sostener 3-10 s y soltar: `pairing window open (60 s)` y parpadeo. Sostener ≥ 10 s y soltar: `pairing: bonds erased`, la ventana se abre y la clave **no** cambia; el reloj anterior debe olvidar el cinturón y emparejarse otra vez. Pasados los 60 s, sostener y soltar no hace nada.
- [ ] **H18. Comandos serie.** Escribir `key` + Enter en el monitor: imprime `blindside passkey: NNNNNN`. `key new`: imprime una clave nueva (reimprimir la etiqueta y volver a emparejar el reloj).
- [ ] **H20. Spike S12.** Con `config::kRequireMitm = true` (por defecto): el reloj pide la clave al emparejar y después la app escribe en `control` (por ejemplo IDENTIFY sin sesión). Si el GW7 no muestra el teclado o la escritura falla, poner `kRequireMitm = false` (plan B: Just Works solo dentro de la ventana, `control` con WRITE_ENC), recompilar, olvidar el cinturón en el reloj, emparejar de nuevo y anotarlo en la spec.
- [ ] **H14. Enlace sano.** Con el reloj conectado: `link[conn=1 sub=1 trusted=1 mtu=255 itvl=24…40]`, `tx[sent]` sube ~50-60 por línea, `fail`, `dropped` y `skipped` cerca de 0 con el reloj al lado.
- [ ] **H15. Enlace bloqueado.** Tapar el pouch con el cuerpo o alejarse hasta casi perder el enlace: `dropped` y `skipped` pueden subir, pero el ESP32 no se reinicia y al volver los datos fluyen solos.

## Luz y resistencia

- [ ] **H13. Disciplina de luz.** A oscuras, con la cámara del teléfono (ve infrarrojo cercano): el LED se apaga a los 2 s del arranque; con la sesión activa (`SESSION_ACTIVE=1`) IDENTIFY no enciende nada; sin sesión, IDENTIFY parpadea 3 veces. Tapar o desoldar el LED de power de la DevKit, el del GY-521, los de la placa de borneras y el del power bank.
- [ ] **H17. Resistencia (S8).** 5 h con el power bank UGREEN y el reloj conectado. Al final, `info` sigue con `reset=POWERON` y el mismo `boot_id`. Si aparece `BROWNOUT`, anotarlo (condensadores de reserva, v1.5).
