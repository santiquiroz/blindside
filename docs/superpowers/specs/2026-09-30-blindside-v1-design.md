# Blindside v1: diseño

- **Fecha:** 2026-09-30, revisión 2 (incorpora la revisión adversarial y las respuestas de Santiago del 30-sep por la noche)
- **Estado:** borrador para revisión de Santiago
  - **Aprobado en la conversación:**
    - Sección 1 (piezas, protocolo BLE y repositorio), que aquí son §2 y §4.
    - Sección 2 (corrección de errores), que aquí es §6.
    - Postura del reloj (D7).
    - Las respuestas D12-D18.
  - **Pendiente de revisión:** todo lo demás. En particular:
    - lo marcado **[CAMBIO]**, que se aparta de una decisión acordada;
    - los criterios de éxito (§1);
    - el alcance del MVP para Manizales (§12);
    - la postura de juego limpio (§10);
    - el roadmap (§11);
    - las preguntas abiertas (§13).
- **Investigación de respaldo:**
  - [`docs/research/reports/`](../../research/reports/): el informe final.
  - [`docs/research/research_notes/`](../../research/research_notes/): notas con fuentes, una por tema. Las referencias como "notas: algoritmos §7" apuntan a esas notas.
- **Revisión adversarial (30-sep):** 5 revisores independientes, y cada hallazgo pasó por verificadores que intentaban refutarlo. De 104 hallazgos sobrevivieron 84, de los cuales 66 eran del spec. Todos quedaron incorporados en esta versión.

---

## 1. Qué es y qué no es

**Blindside** es un radar de pulsera para airsoft.
- Dos radares HLK-LD2450 de 24 GHz van en el cinturón, uno a la altura de cada bolsillo delantero.
- Cada caja de radar lleva además un IMU MPU6050.
- Un ESP32, guardado en un pouch del cinturón sobre la pierna derecha, reenvía los datos por BLE a una app nativa en un Galaxy Watch 7.
- El reloj muestra a las personas que se mueven frente al jugador (unos 180°, hasta ~6 m) y vibra con un patrón distinto según el lado por el que aparecen.

**Para quién:** cualquier jugador que lo arme con la guía del repo (D12). El primer usuario es Santiago:
- diestro;
- usa **empuñadura vertical**;
- tiene un Galaxy Watch 7 de **44 mm**;
- juega partidas de ~1 h, y el **11-oct-2026** va a una partida de **5 h** en Manizales.

### Criterios de éxito de la v1

Son objetivos iniciales: se miden en las grabaciones de campo (F5) y algunos se adelantan en los spikes.

1. **Latencia de confirmación.** Se mide en `.bsrec` (p90), desde la primera trama cruda del LD2450 que contiene a la persona hasta que su pista pasa a confirmada, antes del limitador de §5.5.
   - **≤ 0,7 s** con el jugador quieto.
   - **≤ 1,0 s** caminando o girando (objetivo provisional; se fija con la línea base de F5).
2. **Latencia de la vibración.** Desde esa misma primera trama hasta que empieza la vibración: **≤ 0,75 s (p90)** con el jugador quieto. El tiempo hasta que termina el patrón (~0,5 s más) se reporta aparte.
3. **Dirección:** la vibración indica el lado correcto en **≥ 90 %** de los casos, medido en el modo práctica.
4. **Falsos contactos confirmados por minuto:**
   - **≤ 1/min** con el jugador quieto.
   - **≤ 3/min** caminando.
   - Se reportan además el **% de tramas con los 3 slots ocupados** por radar (saturación, §9) y la línea base de la primera sesión.
5. **Sesión: ≥ 5 h** sin que la app muera, en Sigilo con la pantalla apagada. Es la partida de Manizales.
6. **Reconexión.** Con el reloj y el cinturón en rango, vuelven a llegar datos en **≤ 5 s** desde que el ESP32 anuncia de nuevo. Una caída por bloqueo o rango se detecta en lo que dure el *supervision timeout* (~4 s).
7. **Batería del reloj (44 mm):**
   - **≥ 6 h en Sigilo**, con margen sobre las 5 h de Manizales.
   - **≥ 3 h en Vista.**
   - El spike S8 da la estimación temprana; la verificación final es en F5 con la app terminada.
8. **Sin interacción en juego.** La única excepción es activar o desactivar el modo eliminado (§10.1), que se hace fuera del juego activo: al recibir el impacto y al reaparecer.

**Fuera de alcance de la v1:**
- Identificar compañeros (IFF).
- Cobertura trasera o de 360°.
- Cámara térmica.
- App de celular.
- Compartir datos con el equipo.
- Audio.
- Nodo en el riel de la réplica.
- Motores de vibración en el cinturón.

Todo esto está en el roadmap (§11), salvo WiFi, que D1 descartó para la v1.

### Decisiones tomadas con Santiago (30-sep-2026)

| # | Decisión |
|---|---|
| D1 | La v1 usa Bluetooth (BLE), no WiFi. |
| D2 | La primera pantalla es una app para el Galaxy Watch 7. El celular queda para después. |
| D3 | El reloj siempre vibra ante un contacto nuevo. La pantalla tiene dos modos: ahorro y siempre encendida. **[CAMBIO parcial en §5.5 y §10.1]** |
| D4 | Los radares miran adelante. |
| D5 | Dos radares abiertos hacia afuera, uno a la altura de cada bolsillo, en el cinturón. |
| D6 | Pantalla "frente arriba", con los giros suavizados por el IMU. Sin brújula. |
| D7 | Dos posturas de lectura del reloj (normal y táctica), detectadas por la gravedad y encajadas en ángulos fijos con histéresis, más la opción de forzarlas. |
| D8 | Arquitectura "ESP32 simple, reloj inteligente": la lógica vive en `radar-core` (Kotlin puro). Hay grabación y reproducción de partidas. |
| D9 | IMU: MPU6050 (GY-521). Sin magnetómetro. |
| D10 | La v1 no lleva cámara térmica. El protocolo deja un tipo reservado para sumarla. |
| D11 | Hay que tener en cuenta la postura natural al apuntar un M4. |
| D12 | La app es para cualquier jugador, no solo para Santiago: todo lo personal es configurable. |
| D13 | Santiago es diestro y usa **empuñadura vertical**. |
| D14 | Hay impresora 3D: las cajas se imprimen. |
| D15 | Reloj: Galaxy Watch 7 de **44 mm**. |
| D16 | El ESP32 va en un **pouch del cinturón sobre la pierna derecha**, con **2 cables** a las cajas de los radares. El pouch se mueve con la pierna, así que **cada caja lleva su propio MPU6050** (Santiago tiene dos). |
| D17 | **No se compra nada más** antes del 11-oct. |
| D18 | **MVP jugable para la partida de 5 h en Manizales el 11-oct-2026**, grabando la partida completa (§12). |

---

## 2. Hardware del cinturón

### 2.1 Lista de materiales (D17: solo lo que ya hay)

| Pieza | Estado | Nota |
|---|---|---|
| ESP32-WROOM-32 DevKit de 30 pines (kit LAFVIN) | tiene | Va en la placa de borneras, dentro del pouch |
| Placa de borneras de 30 pines | tiene (llegó) | |
| 2× HLK-LD2450 | tiene | El RD-03D de Ai-Thinker usa la misma trama (sin probar) |
| 2× MPU6050 GY-521 | tiene | **Uno por caja de radar.** No queda repuesto |
| Cables JST ZH 1,5 mm de 4 pines | tiene (llegó) | Del radar a la regleta de su caja |
| 2 cables de 7 hilos, de unos 50-80 cm | **reutilizar** | De la caja al pouch. Sirven cables USB viejos (4 hilos cada uno, así que 2 por caja), un cable Ethernet viejo (8 hilos) o cables Dupont empalmados y forrados como último recurso |
| Cajas impresas en 3D con cuña y radomo | por imprimir | §2.3 |
| Power bank | tiene (UGREEN 100 W) | |
| Galaxy Watch 7 de 44 mm | tiene | |
| Electrolíticos de 100-470 µF | solo si hay a mano | Uno junto a cada radar (§2.2). Si no, va a la v1.5 y se vigilan los reinicios con `esp_reset_reason()` |
| Interruptor MOSFET para cortar la alimentación de los radares | **no (v1.5)** | Requiere compra. Hasta entonces, el watchdog solo reinicia por comando (§4.1) |

### 2.2 Conexiones

**Cada caja es idéntica:** radar + IMU, con un cable de 7 hilos.

| Hilo del cable de caja | Radar A (caja izquierda) | Radar B (caja derecha) |
|---|---|---|
| 5V del radar | VIN | VIN |
| GND (común) | GND | GND |
| Radar TX → ESP32 | GPIO16 (RX2, UART2) | **GPIO26** (RX1 reasignado) |
| Radar RX ← ESP32 | GPIO17 (TX2) | **GPIO27** (TX1 reasignado) |
| IMU 3V3 | 3V3 | 3V3 |
| IMU SDA | **GPIO32** (I2C1) | GPIO21 (I2C0) |
| IMU SCL | **GPIO33** (I2C1) | GPIO22 (I2C0) |

- **Dos buses I2C separados,** uno por caja. Las dos placas pueden quedar en la dirección 0x68 (AD0 a GND), y una falla de cable no tumba el otro IMU.
- **Velocidad I2C: 100 kHz**, por el largo de los cables. Con lecturas de 14 B a 200 Hz el bus queda a ~30 % de carga.
- **IMU a 3V3,** no a 5V: así las resistencias de pull-up del GY-521 quedan a 3,3 V sin depender de cómo esté cableada la placa clon.
- **Botón BOOT (GPIO0):** solo se lee como entrada **después** del arranque, para el modo emparejamiento (§4.3). Nunca mantenerlo presionado al encender, porque entra en modo descarga.
- **LED de estado:** GPIO2, el de la placa (§4.5).
- **Pines prohibidos:**
  - GPIO6-11 (flash).
  - Los de arranque 0, 2, 5, 12 y 15, salvo los dos usos de arriba.
  - **Nunca** conectar la salida de un radar a GPIO12, porque impide que el ESP32 arranque.
- **Condensador de reserva** (si hay, §2.1): 100-470 µF entre 5V y GND junto a cada LD2450. Hi-Link pide una fuente de más de 200 mA con un consumo medio de 120 mA, lo que sugiere picos en las ráfagas de transmisión. Con dos radares y el BLE en el mismo riel, puede caer el VIN.
- Los UART del LD2450 trabajan a 3,3 V, así que se conectan directo.
- A 256000 baudios y 100 kHz de I2C, 50-80 cm de cable no son un problema.

### 2.3 Montaje

- **Posición (D5):** un radar sobre cada bolsillo delantero, a unos **±15 cm de la hebilla medidos con cinta sobre el cinturón**.
- **Apertura objetivo por perfil** (el ángulo negativo es a la izquierda, visto desde el jugador):

  | Perfil | Radar A (izq) | Radar B (der) | Cobertura | Solape |
  |---|---|---|---|---|
  | Diestro (**por defecto, Santiago**) | −40° | +20° | −100° a +80° | −40° a +20° |
  | Zurdo | −20° | +40° | −80° a +100° | −20° a +40° |
  | Cambia de hombro seguido | −30° | +30° | −90° a +90° | −30° a +30° |

  **Por qué:** un diestro apunta 0-30° a la **izquierda** de hacia donde mira su cadera (~45° de rodilla, notas: biomecánica).

- **La curvatura no hace el trabajo sola (corrección de la revisión):**
  - El modelo de cintura ANSUR II de las notas da 20-40° hacia afuera **solo a 8-12 cm** de la línea media.
  - A ±15 cm da ~48° si se miden con cinta sobre el cinturón, y ~60° si son 15 cm laterales en línea recta.
  - Con las posiciones de los bolsillos, el perfil diestro necesita **cuñas hacia adentro**: ~8° en A y ~28° en B.
  - Un cinturón acolchado y rígido aplana la curva, así que **se mide con transportador sobre el cinturón puesto** antes de imprimir.
  - La alternativa de las notas es colocar A a ~12 cm y B a ~7 cm de la hebilla (cuñas de 0-15°), pero B dejaría de estar sobre su bolsillo. Se mantiene D5.
- **Cada caja contiene radar + IMU, rígidos entre sí.**
  - Así cada IMU mide la orientación de *su* radar, y la flexión del cinturón entre las dos cajas se vuelve observable (en la v1 se registra; la corrección queda para la v1.5).
  - Si se mueve o reajusta el cinturón, se repite la alineación (§6.8).
- **Orientación del módulo:** el eje de azimut va horizontal. El proyecto ScienceShack montó el módulo girado y el eje X dejó de actualizarse.
- **Inclinación:** se arranca con 10-15° hacia arriba y se valida en S7. A ~1 m de altura y sin inclinar, el haz de ±35° de elevación toca el piso a ~1,4 m.
- **Radomo** (Hi-Link §9.2):
  - Plano, de espesor uniforme y paralelo a la antena, a **12,4 mm** de ella.
  - Impreso **macizo, con 100 % de relleno** (nunca el relleno por defecto del slicer), de ~3,6-4 mm. El relleno parcial forma un dieléctrico no homogéneo.
  - Sin metal ni pintura metálica.
  - La εr del PLA/PETG no está verificada: S7 compara con tapa y sin ella.
- **Blindaje trasero:** papel aluminio o un pedazo de lata entre el radar y el cuerpo, aislado de la electrónica. El lóbulo trasero detecta movimiento detrás del módulo.
- **BBs:** el radomo y la caja tienen que aguantar un impacto directo.
- **Disciplina de luz:** tapar o desoldar el LED de power del GY-521 dentro de cada caja. Una ventana de 4 mm de PLA deja pasar luz.
- **Cables:** van a lo largo del cinturón, con alivio de tensión en cada caja y en el pouch.

### 2.4 Energía

- **Consumo estimado:** 2 × 120 mA (radares) + ~40 mA (ESP32 con BLE) + ~2 × 4 mA (IMUs) ≈ **300 mA a 5 V (~1,6 W)**.
- 5 h en Manizales consumen ~1.500 mAh: el UGREEN da de sobra. El cargador del reloj también se puede enchufar al power bank en un descanso.
- A ~300 mA el power bank no debería apagarse solo, pero se confirma en S8.

---

## 3. Sistemas de referencia y postura

### 3.1 Sistemas de referencia

- **Radar:** lo que entrega cada LD2450: X lateral y Y hacia adelante, en mm.
  - Hi-Link no documenta hacia qué lado es +X ni el signo de la velocidad. Cada radar guarda sus valores `flipX` y `speedSign`, que salen del asistente de signos (§6.8) o del spike S6.
- **Cuerpo:** origen en la hebilla, Y hacia donde mira la cadera, X a la derecha. Cada radar tiene su posición (x, y) y su ángulo, con el rumbo común calibrado aparte (§6.8).
- **Odometría:** es el cuerpo corregido por dos cosas:
  - la rotación acumulada que miden los IMUs de las cajas (§6.3);
  - **la traslación del jugador**, estimada contando pasos (§6.4).

  En el estado del Kalman la velocidad es **relativa al suelo**, así que un objeto quieto queda en ≈ 0.
- **Marco lógico de pantalla:** es el que usan `RadarScene`, los lados de las alertas y las pruebas.
  - En postura normal, arriba es el frente de la cadera.
  - En postura táctica, arriba es la cadera más el desfase táctico (§3.3).
- **Rotación de dispositivo:** 0° o el ±90° guardado por jugador (§3.2). **Solo se aplica al dibujar.** Un error de postura puede girar el dibujo, pero nunca cambia izquierda por derecha en la vibración.
- **El giro llega en lotes** (corrección de la revisión).
  - El IMU muestrea a 50 Hz, pero el reloj recibe las muestras juntas cada 100 ms (§4.2). Para que la pantalla no salte a 10 Hz, el yaw se **extrapola al presente**: ψ(ahora) = ψ(t_última) + ω̄_v · min(ahora − t_última, 150 ms), con ω̄_v como promedio de las últimas 2-3 muestras.
  - Cuando llega un paquete, la diferencia entre lo extrapolado y lo medido se mezcla con un τ de ~50 ms.
  - Ese mismo ψ(ahora) rota las pistas y define los sectores de vibración.
  - La deriva lenta del giroscopio no se nota, porque la pantalla nunca muestra la odometría directamente.

### 3.2 Postura del reloj (D7, precisada por la revisión)

- **La gravedad solo decide entre normal y táctica,** con bandas muertas. Se mide con `TYPE_GRAVITY`, o con un pasabajos sobre el acelerómetro si no existe. α es el ángulo de la gravedad en el plano de la pantalla:
  - |α| ≤ 35°: normal.
  - 55° ≤ |α| ≤ 125°, de cualquier signo: táctica.
  - Cualquier otro caso (35-55°, 125-180°, o una magnitud en el plano menor de 2 m/s²): **conserva la postura actual**.
  - Un cambio requiere que la nueva postura se mantenga ≥ 300 ms.
- **El signo de la táctica (±90°) es un valor guardado por jugador.** Nunca se vuelve a calcular con la gravedad durante el juego.
  - Lo fija el "cero" (§3.3).
  - Se puede cambiar en ajustes (auto / +90° / −90°).
  - "Forzar táctica" usa ese valor.
  - **Por qué:** la regla "el borde más alto apunta adelante" falla en low ready, con la bocacha 30-45° hacia abajo, y giraría la pantalla 180°.
- En ajustes, la postura se puede forzar: automática, normal o táctica.

### 3.3 Postura de tiro del M4 (D11, D13)

**Lo que dice la investigación** (notas: biomecánica):
- **Ángulo entre cadera y cañón** en un diestro: 0-15° en postura cuadrada, 20-30° en semiperfilada y ~45° de rodilla.
- **La postura táctica solo se lee con el agarre tradicional** (palma arriba bajo el guardamanos) y con el reloj en la **cara interna de la muñeca de la mano de apoyo** (izquierda en diestros, derecha en zurdos).
  - Con **empuñadura vertical** o C-clamp, el reloj queda mirando a la réplica y **no se lee**.
  - En la mano del pistol grip tampoco se lee.

**Consecuencia para Santiago (D13, empuñadura vertical):**
- Mientras apunta, **la vibración es el canal principal**.
- La pantalla se consulta en postura normal, en pausas o con la réplica baja.
- La postura táctica y el "cero" **no entran en el MVP de Manizales** (§12). En la v1 completa quedan para los jugadores con agarre tradicional.

**Diseño:**
- **Postura normal:** arriba = el frente de la cadera.
- **Postura táctica:** arriba = el cañón (cadera + desfase táctico).
- **Calibración "cero"** (asistente de ~10 s, con un compañero; corregida por la revisión):
  1. Un compañero se para a 3-4 m, **dentro del solape**, y se **balancea un poco hacia adelante y atrás** o marca el paso en el sitio, sin desplazarse de lado. El LD2450 no reporta a quien está quieto, y el balanceo radial mantiene constante el acimut que se mide. El jugador le apunta.
  2. La app promedia el ángulo β solo con **detecciones frescas** de una pista confirmada, nunca con una posición congelada.
     - Acepta la medición si durante 2-3 s hay **exactamente una** pista confirmada a 2-5 m dentro del solape, con **≥ 20 detecciones** y una dispersión de β bajo un umbral configurable.
     - Si no se cumple, explica el motivo (sin contacto / varios contactos / fuera del solape / pocas muestras) y repite.
     - Control opcional: en un diestro de pie se espera β entre −5° y −30°; si cae fuera, avisa.
  3. Con la muñeca en posición de lectura:
     - **Si el compañero aparece abajo,** se invierte el signo de ±90° (no se corrige con el bisel).
     - Después se corrige el error residual con el bisel táctil (eventos rotatorios) o con los botones ± de la pantalla.
- **Cambio de hombro:** es un ajuste que se elige **antes** de la partida e invierte el desfase. Cambiar de hombro a mitad de partida no se corrige en la v1; un acceso rápido queda para el roadmap.

---

## 4. Firmware del ESP32 (`firmware/`)

### 4.1 Responsabilidades

El firmware **no interpreta los datos de los blancos.** Sí se ocupa del mantenimiento de los módulos.

1. **Arranque de cada LD2450.** Todos los comandos van **después de *enable* (0x00FF)**. El **reinicio (0x00A3) se envía al final, todavía en modo configuración**: aplica los cambios y cierra la sesión.
   - Detecta el baud rate: primero 256000 y luego la lista de las notas. El firmware V2.14 del módulo **puede** cambiarlo.
   - Lee la versión del módulo.
   - Activa el modo multiobjetivo.
   - **Apaga el Bluetooth del propio módulo** (§10.3).
   - Reinicio.
2. **Tramas:**
   - Se sincroniza con `AA FF 03 00 … 55 CC` (30 bytes) y descarta lo que no cierra bien.
   - Anota la hora de llegada de la cabecera con `millis()`.
   - Cada UART tiene un buffer de recepción de 2 KB.
3. **IMUs** (uno por caja, cada uno en su bus):
   - Muestreo a **200 Hz**.
   - Giroscopio a **±500 °/s** (65,5 LSB/(°/s)) y acelerómetro a **±8 g** (AFS_SEL = 2, 4096 LSB/g).
   - Filtro interno con `DLPF_CFG = 3` (~42 Hz).
   - **Clones de la familia MPU-6500/9250/ICM-206xx** (WHO_AM_I ≠ 0x68): se escribe además `ACCEL_CONFIG2` (0x1D) con `A_DLPF_CFG = 3`, porque en esos chips el filtro de `CONFIG` solo afecta al giroscopio.
   - **Salida a 50 Hz:** cada 20 ms promedia 4 muestras.
   - **Sumas acumuladas** desde el arranque de gx, gy, gz crudos a 200 Hz (u32 con vuelta), para reconstruir el giro si se pierde un paquete (§6.3).
4. **Empaquetado:** un corte cada **100 ms** (§4.2).
5. **Vigilancia de los radares:**
   - Si un radar pasa 2 s sin tramas válidas, el firmware envía **enable + reinicio**.
   - Si sigue sin tramas, reintenta cada ~30 s durante toda la sesión y lo marca caído en `flags`.
   - El corte de alimentación con MOSFET queda para la v1.5 (D17).
6. **Emparejamiento y LED** (§4.3, §4.5).

### 4.2 Protocolo BLE

**Servicio GATT de Blindside** (UUID de 128 bits propio, definido en el código):

| Característica | Propiedad | Contenido |
|---|---|---|
| `stream` | notify; el CCCD solo se puede escribir con el enlace cifrado | Paquetes compuestos |
| `info` | lectura cifrada (READ_ENC) | Versiones del protocolo y del firmware; versión y baud de cada radar; WHO_AM_I y **escalas del giroscopio y del acelerómetro** de cada IMU; MTU negociado; `esp_reset_reason()`; `boot_id` aleatorio de cada arranque; uptime |
| `control` | escritura cifrada y autenticada (WRITE_AUTHEN) | `0x01 RESTART_RADAR(id)`, `0x03 IDENTIFY` (solo fuera de sesión, §4.5). `0x02 SET_ZONES(id, …)` **solo si el spike S5 demuestra que el filtro de zonas libera slots** |

**Formato del paquete** (little-endian):

```
Cabecera (8 B):  u8 version=1 | u8 flags | u16 seq | u32 t_ms (reloj del ESP32)
  flags: bit0 radar A vivo, bit1 radar B vivo, bit2 IMU A ok, bit3 IMU B ok, bit4 se descartaron datos
Secciones TLV (u8 tipo | u8 largo | payload), repetidas:
  0x01 RADAR   u8 radar_id | u32 t_ms (llegada de la cabecera) | 24 B de blancos crudos del LD2450   → 31 B
  0x02 IMU     u8 imu_id | u32 t_primera_ms | u8 n | n × (i16 ax, ay, az, gx, gy, gz)
               | 3 × u32 sumas acumuladas de gx, gy, gz crudos a 200 Hz                                → 20 + 12n B
  0x03 STATUS  (1 Hz) por radar: u8 radar_id | u16 tramas_malas (acumulado, satura en 0xFFFF)
               | u8 reinicios (satura en 0xFF) | u8 índice_baud (tabla 0x01-0x08; 0x07 = 256000)   → 12 B
  0x10-0x1F    reservadas (0x10 = térmica, D10)
```

- **Contenido típico de un corte de 100 ms:** 8 + 2 × 31 (radar) + 2 × 80 (IMU con n = 5) = **230 B**, y **242 B** con STATUS (1 Hz). Ambos caben en un paquete de ≤ 244 B; con una trama de radar extra (jitter), el `bundler` lo parte en dos.
- **Contrato del `bundler`:**
  - Es una función pura que se prueba en el entorno `native`.
  - Corta por tiempo: solo incluye tramas **completas** con t_ms anterior al corte, así que el orden entre paquetes queda garantizado.
  - Recibe `max_payload = getPeerMTU() − 3`.
  - **Si el contenido no cabe, lo parte en varios paquetes consecutivos**, cada uno con su cabecera y su seq. **Nunca parte una sección.**
  - Solo descarta (y marca `flags.bit4`) si la cola de notificaciones de NimBLE está llena (`BLE_HS_ENOMEM`). Primero descarta las tramas de radar más viejas y después las muestras de IMU más viejas, y lo cuenta.
- **MTU:**
  - El reloj pide `requestMtu(517)`.
  - NimBLE-Arduino propone **255** por defecto y acepta hasta 527, así que en Android 14+ el MTU final es 255 (252 B útiles).
  - Cada paquete se mantiene **≤ 244 B** para que quepa en un solo paquete de enlace con DLE (`setDataLen(251)`).
- **El ESP32 no notifica** hasta que el CCCD esté activo y `getPeerMTU() − 3` alcance para la cabecera más una sección RADAR (39 B).
- **El lector TLV:**
  - Salta los tipos desconocidos usando el campo de largo.
  - **Si `largo` supera los bytes que quedan, descarta el resto del paquete** y lo cuenta como truncado.
- **seq** da la vuelta cada 65536 paquetes, unas 1,8 h a 10-20 paquetes/s.
  - Paquetes perdidos = `((seq − seq_prev) mod 65536) − 1`, con aritmética sin signo.
  - Si `t_ms` retrocede (el ESP32 se reinició), no se calcula el hueco: se reinicia la referencia.

### 4.3 Emparejamiento, seguridad y conexión

**Corregido por la revisión: una clave fija en un repo público no protege nada.**

- **Ventana de emparejamiento explícita:**
  - Se abre solo si **no hay ningún bond guardado**, o si se mantiene el botón **BOOT 3 s con el firmware ya arrancado**.
  - Dura **60 s**, con el LED parpadeando.
  - Solo dentro de la ventana se acepta un emparejamiento nuevo (LE Secure Connections + bonding con distribución de IRK). Se cierra a los 60 s o con el primer bond exitoso.
  - Un bond nuevo reemplaza al anterior.
  - Mantener BOOT **10 s** borra los bonds y regenera la clave.
- **Clave por dispositivo:**
  - 6 dígitos aleatorios generados en el primer arranque y guardados en NVS.
  - Se imprimen por el puerto serie y se anotan en una etiqueta del cinturón. **Nunca** van en el código ni en un flag de compilación versionado.
  - **Riesgo residual:** un rival presente durante la ventana podría recuperar una clave estática; conviene emparejar lejos de los rivales.
- **Lista blanca por bond:**
  - Se usa la *resolving list* de NimBLE: la lista guarda la **identity address** resuelta con la IRK, porque el reloj usa direcciones privadas que cambian cada ~15 min.
  - Fuera de la ventana se anuncia con *filter policy* "conexiones solo desde la lista blanca", así que el controlador descarta las conexiones de terceros.
  - Si el spike S10 muestra que el WROOM-32 no resuelve la dirección del reloj con el filtro activo, el plan B es verificar el bond en el host y desconectar al instante. En ese caso se documenta que un rival podría ocupar el slot.
- **Una sola conexión:** con el reloj conectado, el ESP32 no anuncia.
- **Tras una desconexión:**
  - `advertiseOnDisconnect(true)`, con intervalo de 20-50 ms durante 30 s y después 100-200 ms.
  - Al conectar, `updateConnParams(h, 24, 40, 0, 400)`: intervalo de 30-50 ms y *supervision timeout* de 4 s. El valor real lo impone el reloj y queda registrado en `info`/STATUS.

### 4.4 Estructura del código

- **Proyecto:** PlatformIO con dos entornos: `esp32dev` (Arduino + NimBLE-Arduino 2.x) y `native`.
- **Módulos:**
  - `ld2450_reader`
  - `ld2450_commands`
  - `imu_mpu6050` (2 instancias)
  - `bundler`
  - `ble_link`
  - `pairing`
  - `radar_watchdog`
  - `status_led`
- Los parsers y el `bundler` no dependen del hardware y se prueban en `native`.

### 4.5 Disciplina de luz

- **LED de estado (GPIO2):**
  - Se usa en el arranque (2 s), durante la ventana de emparejamiento e `IDENTIFY`.
  - En partida queda **apagado**.
  - `IDENTIFY` solo lo ofrece la app en las pantallas de emparejamiento y configuración, nunca con una sesión activa.
- **LEDs que el firmware no controla:** el LED rojo de power de la DevKit (va directo a 3V3), el del GY-521 y los de la placa de borneras y del power bank. Se **desueldan o se tapan con cinta opaca**. El pouch es opaco.
- **Verificación:** a oscuras, con la cámara del teléfono, que capta también el infrarrojo cercano.

---

## 5. App del reloj (`watch/`)

### 5.1 Plataforma

- **Firmware del Galaxy Watch 7 en 2026:** One UI 8 Watch (Wear OS 6, API 36). One UI 9 Watch (Wear OS 7) está en beta.
- **Versiones:** `minSdk` 34 y `targetSdk` 36.
- **Herramientas:** Android Studio Quail 4, AGP 9.4.0, Compose for Wear OS 1.7.0 y `wear-ongoing` 1.1.0.
- La depuración en el reloj se hace por WiFi.

### 5.2 Módulos

**`watch/radar-core`:** biblioteca Kotlin/JVM pura, inmutable, con funciones pequeñas. Paquetes:
- `protocol`
- `geometry`
- `imu`
- `egomotion`
- `tracking`
- `pose`
- `scene`
- `alerts`
- `calibration`
- `replay`
- `sim`
- `config`

**`watch/wear-app`:**

- **Permisos y arranque:**
  - El botón "Iniciar partida" pide `BLUETOOTH_SCAN` (neverForLocation), `BLUETOOTH_CONNECT` y `ACTIVITY_RECOGNITION`.
  - El manifiesto declara `HIGH_SAMPLING_RATE_SENSORS`, un permiso normal que se concede al instalar y que por sí solo cumple el requisito del tipo `health`. Por eso el servicio siempre arranca como `connectedDevice|health`.
  - Si se deniega `BLUETOOTH_SCAN` o `BLUETOOTH_CONNECT`, la sesión **no arranca**: la app explica por qué y lleva a los ajustes.
  - Si se deniega `ACTIVITY_RECOGNITION`, la sesión arranca sin el detector de pasos del reloj. El del cinturón sigue siendo el principal.
- **`BlindsideSessionService`:** servicio en primer plano. Es dueño de `BluetoothGatt`, de los sensores, del pipeline, del `Vibrator` y del grabador, y publica `StateFlow<RadarScene>`.
  - Toma un **`PARTIAL_WAKE_LOCK`** (permiso `WAKE_LOCK`) con timeout de 10 min, que renueva mientras dura la sesión.
  - Al terminar lo suelta, junto con `stopForeground(STOP_FOREGROUND_REMOVE)` y el cierre de GATT.
  - **Todo el trabajo vive en el servicio,** no en la pantalla.
  - Mientras la sesión está activa hay una Ongoing Activity (Live Updates en Wear OS 7).
- **`ble`:**
  - **Cola GATT serial:** `discoverServices` → `requestMtu(517)` → escritura del CCCD.
    - El CCCD se escribe tras `onMtuChanged`, o tras 2 s sin ese callback. No se desconecta por eso: el control de tamaño real lo hace el ESP32.
  - **Prioridad de conexión:** `requestConnectionPriority(HIGH)` **solo durante el setup**. Después, **BALANCED** para toda la partida. LOW_POWER no se usa, porque suma hasta ~125 ms de latencia.
  - **Reconexión:**
    - Al recibir DISCONNECTED: `close()` e **inmediatamente** `connectGatt(dispositivo_bond, autoConnect=true, TRANSPORT_LE)`. Es una conexión pendiente sin timeout, sin espera progresiva, que se vuelve a pedir en cada caída.
    - Si pasan más de 10 s sin reconectar, se intenta una conexión directa (`autoConnect=false`, que Samsung corta a ~10 s) y se vuelve a la pendiente.
  - **Escaneo:** siempre con `ScanFilter` por el UUID del servicio. Solo se escanea si no hay bond, o si el dispositivo emparejado no responde en 30 s. Un limitador propio permite **≤ 4 `startScan` cada 30 s**, porque el 5.º falla en silencio.
- **`sensors`:**
  - `getDefaultSensor(TYPE_GRAVITY / TYPE_STEP_DETECTOR, true)` cuando exista la variante wake-up. Si no, se apoya en el wake lock.
  - Con la pantalla apagada, `maxReportLatencyUs` acotado.
  - Si no hay `TYPE_GRAVITY`, pasabajos sobre el acelerómetro.
- **`haptics`:**
  - Cada alerta llama a `vibrate(effect, VibrationAttributes.createForUsage(USAGE))`, **siempre con un uso explícito**: USAGE_ALARM o USAGE_NOTIFICATION, el que el spike S3 muestre que pasa.
  - Al iniciar consulta qué soporta el motor. Si no hay primitivas, usa patrones de encendido y apagado.
- **`ui`** (Compose con Canvas), **`settings`** (DataStore) y **`recording`**.

### 5.3 Pantalla

- **Abanico** con la cobertura real de la geometría configurada (diestro: −100° a +80°), escala **fija de 6 m** y anillos a 2 y 4 m. El origen queda bajo el centro.
- **Contactos:** todos del mismo tamaño, y cada rasgo visual codifica una sola cosa:
  - el **relleno** codifica la confianza: sólido si lo confirmaron los dos radares, contorno si lo vio uno, punteado si está en espera;
  - la **opacidad** codifica la antigüedad;
  - los contactos fuera de vista se muestran como una flecha en el borde.
  - Color seleccionable: **rojo** (contra la vista de los rivales) o **verde** (contra visión nocturna de tubo).
- **Estado honesto:**
  - El barrido solo se anima si llegan datos frescos.
  - Si el enlace se corta más de 1 s, todo pasa a gris con "--".
  - Un mini estado muestra el enlace, los radares A y B, los IMUs A y B y "sin pasos del reloj".
- **Fondo negro.**
- **En Vista** (`keepScreenOn`, sin ambient), se aplican las reglas de ambient como guía contra el burn-in, porque la protección del sistema solo actúa en ambient:
  - retícula y anillos en **gris oscuro**, sin blancos ni bloques sólidos;
  - lo crítico a ≥ 10 px del borde;
  - **todo lo estático se desplaza 1-2 px cada pocos minutos**.
- **Rendimiento:** 30 fps solo en Vista. Cada pista se predice hasta "ahora" y se rota con ψ(ahora) (§3.1); las correcciones se suavizan con τ = 150 ms.

### 5.4 Modos de pantalla [CAMBIO frente a la investigación]

En Wear OS 6 el ambient se actualiza **una vez por minuto,** así que no puede mostrar contactos en vivo. Encender la pantalla desde la app no está documentado (spike S2).

- **Sigilo** (ahorro, **por defecto** y el modo de Santiago):
  - **Ante un contacto nuevo, el reloj solo vibra; la pantalla no se enciende sola.**
  - Ajuste "Encender ante contacto": **nunca** (por defecto) / solo en postura táctica / siempre. Las dos últimas opciones solo existen si S2 lo permite.
    - "Solo en postura táctica" significa un destello de 1,5-2 s a brillo mínimo, con menos del 5 % de píxeles (solo los contactos, sin anillos ni texto) y con la luz hacia el jugador.
    - En postura normal la pantalla se enciende solo con un toque o al levantar la muñeca.
  - **[CAMBIO]:** el modo ahorro de la investigación despertaba la pantalla con la vibración. Aquí eso queda como opción apagada, por disciplina de luz.
  - En Sigilo, la pantalla atenuada cumple las reglas de Wear OS: ≤ 15 % de píxeles encendidos, margen de 10 px y sin animaciones.
- **Vista** (siempre encendida):
  - `keepScreenOn` y radar en vivo a 30 fps.
  - **Rompe la disciplina de luz: no usarla de noche.**

### 5.5 Vibración [CAMBIO: D3 matizado por el limitador]

- **Vocabulario sin ambigüedad de prefijo.** El primer pulso ya separa el centro de los laterales (análisis propio, a validar con A/B):
  - **Centro:** un único pulso largo.
  - **Izquierda:** corto-corto.
  - **Derecha:** corto-largo.
  - **Sistema** (enlace perdido, radar caído): un zumbido continuo claramente más largo que el pulso del centro.
- **Sin información de distancia ni de "urgencia" en la v1.**
- **Una alerta por ID de pantalla.** Una pista que heredó un ID que ya alertó (§6.5) no vuelve a vibrar al confirmarse. Un ID heredado de una pista tentativa, que nunca alertó, sí vibra.
- **Limitador** (valores de las notas: ideas_creativas_ux §3). **Nunca retrasa la primera alerta.**
  - **Flanco de subida:** la primera alerta sale de inmediato. Los contactos que llegan en los ~300 ms siguientes se fusionan con ella. En el siguiente disparo se prioriza el centro sobre los lados y, dentro de un lado, el contacto más cercano.
  - **≥ 1 s entre alertas.** Un contacto que cae en ese hueco se suma al siguiente disparo, y si para entonces ya no está, solo se muestra en pantalla.
  - **Pausa de 5 s por sector** (rango a probar: 5-8 s), **salvo que aumente el número de contactos en ese sector**: en ese caso vibra igual. Así esta regla nunca silencia un contacto nuevo.
  - **Tope de 10 alertas por minuto.** Al llegar al tope, la vibración se calla y la pantalla muestra un indicador de saturación.
  - **[CAMBIO D3]:** el tope por minuto es la única excepción a "siempre vibra". Motivos: evitar la habituación y la distracción.
- **Modo eliminado** (§10.1): no hay alertas de contacto; solo vibran las de sistema.
- **Modo práctica** (obligatorio antes de armar Sigilo):
  - **Quiz de vibraciones con una vibración real.** Sirve para detectar si el modo No molestar, el modo teatro, el modo dormir o una intensidad baja del sistema silencian las alertas.
  - Prueba con compañeros caminando al frente.
  - Advierte si `getCurrentInterruptionFilter()` indica que No molestar está activo y el uso elegido no lo atraviesa.
- **Referencia de los lados:** se calculan en el **marco lógico** (§3.1). "Centro" es ±20° alrededor de arriba.
- **"Aviso cercano"** (detecciones tentativas en movimiento a menos de 2 m durante ≥ 2 ventanas): **[CAMBIO]** la investigación lo propone como mitigación de la latencia. Aquí queda **desactivado por defecto** hasta validarlo en F5, y decidir si viene activado es una tarea de salida de F5.

### 5.6 Ajustes

- **Mano** (define la apertura de §2.3): diestro, zurdo o cambia de hombro seguido.
- **Cambio de hombro:** invierte el desfase táctico. Se elige antes de la partida.
- **Geometría de cada radar** (posición y ángulo) y **signos por radar** (`flipX`, `speedSign`): ver, editar o volver a correr el asistente.
- **Modo de pantalla,** "encender ante contacto", brillo y color de los contactos (rojo o verde).
- **Postura:** automática, normal o táctica. Signo táctico: auto, +90° o −90°.
- **Vibración:** probar los ritmos y modo práctica. No hay interruptor de apagado: eso es el modo eliminado.
- **Modo eliminado:** acceso rápido con un botón grande en la pantalla de la app y una acción en la Ongoing Activity. Usar el botón físico depende del spike S11.
- **Aviso cercano.** Grabación. Asistentes de calibración (§6.8).

---

## 6. `radar-core`: el pipeline

Los valores son de arranque y se ajustan reproduciendo grabaciones. La tabla completa está en las notas: algoritmos §7. Los valores del limitador están en las notas: ideas_creativas_ux §3.

### 6.1 Orden del pipeline

1. **Decodificar.**
   - Paquete TLV (§4.2) y trama del LD2450 en **signo-magnitud: el bit 15 en 1 es positivo** y los 15 bits bajos son la magnitud. Ejemplo oficial: `0x86B1` = +1713 mm.
   - **Plausibilidad por objetivo.** El LD2450 no trae checksum, así que se descarta el objetivo si:
     - Y ≤ 0;
     - |X| > Y·tan 65°;
     - |v| > 10 m/s;
     - la resolución no es 320 ni 360 mm.

     Los descartes se cuentan.
   - Se descartan los espacios vacíos (0, 0, 0).
   - **Objetivo "rancio":** se evalúa por objetivo y sin depender del slot. Un (x, y, v) idéntico al de cualquier slot de la trama anterior del mismo radar durante **≥ 3 tramas consecutivas** no cuenta como detección. Los demás objetivos de esa trama sí cuentan.
2. **Exclusión.**
   - Campo cercano: **r < 0,8 m** (rango a probar: 0,5-1,0 m). Algoritmos propone 0,5 m y biomecánica 0,8-1 m: al caminar y en posición baja, los muslos, las manos y la réplica caen cerca del radar, y un rival a menos de 1 m se ve a simple vista.
   - Mapa de ruido propio (§6.8).
3. **Radar → cuerpo,** con la posición y el ángulo calibrados.
4. **Cuerpo → odometría:** se rota por el yaw en `t − τ` y se aplica la traslación del jugador como entrada de control (x ← F·x − v_ego·dt, con su covarianza).
5. **Asignación GNN exacta,** por enumeración o Hungarian.
   - Las tramas de cada paquete se ordenan por t_ms y se procesan **apenas llega el paquete, sin buffer de espera**. El `bundler` ya garantiza el orden entre paquetes.
   - Una trama con t_ms menor que la última procesada se descarta y se cuenta.
6. **Kalman** (§6.2), con R inflada ×1,5 en la zona de solape.
7. **Gestión de pistas** (§6.5-6.6).
8. **Escena:** cada pista se predice hasta "ahora" y se rota con ψ(ahora) al marco lógico (§3.1).

**Presupuesto de latencia** (criterios 1 y 2):

| Término | Mín. | Típico | Máx. |
|---|---|---|---|
| Latencia interna del LD2450 | ? | ? | ? (se mide en S1/F5) |
| Espera hasta el corte del paquete | 0 | 50 ms | 100 ms |
| Intervalo de conexión BLE (BALANCED) | 0 | 25 ms | 50 ms |
| Procesamiento en el reloj | ~0 | ~1 ms | ~5 ms |
| 3 de 5 ventanas desde el primer acierto | 200 ms | 200-300 ms | 400 ms |
| Regla anti-fantasmas 1, caminando | — | +100-200 ms | ≥ 400 ms en total |
| Limitador (flanco de subida) | 0 | 0 | 0 para la primera alerta |

### 6.2 Modelo

- **Kalman de velocidad constante en 2D,** con estado [x, y, vx, vy] **relativo al suelo**.
- **Ruido de proceso** de tiempo continuo (CWNA), con q = 0,4 m²/s³.
- **Ruido de medición:** σ_r = 0,2 m y σ_θ = 3,5°/cos θ, con cos θ ≥ cos 70°.
- **Velocidad inicial:** v0 = 0, con σ_v0 = 1,5 m/s.
- **El campo de velocidad del LD2450** solo se usa para la asignación y la clasificación, hasta que S6 valide qué significa.

### 6.3 IMUs de las cajas

- **Velocidad de giro de cada caja:** ω_v,i = ĝ_iᵀ(ω_i − b_i), cada IMU con su propia gravedad. La orientación de cada chip dentro de su caja es libre, porque solo se usan ĝᵀω y |a|.
- **Giro del cuerpo:** el **promedio** de los dos ω_v. Si un IMU falla, se usa el otro. La diferencia entre los dos (flexión del cinturón) **se registra** en la v1 y se usa para corregir en la v1.5.
- **Sesgo al arrancar** (corregido por la revisión):
  - Se promedian 2 s **de pie y quieto** sobre ω crudo; la media de cada eje es b₀.
  - La quietud se juzga **por la dispersión, no por la magnitud,** porque el offset inicial llega a ±20 °/s. Se rechaza la ventana si:
    - en algún eje |ω − media de la ventana| > 3 °/s;
    - la desviación de |a| > 0,02 g;
    - |media| > 45 °/s en algún eje, lo que indica un sensor defectuoso y se reporta en el estado.
  - Si se rechaza, se reintenta con la siguiente ventana. Mientras tanto se usa el último sesgo aceptado (guardado en el reloj) y el IMU aparece como "calibrando".
- **Recalibración en reposo:** se dispara con |ω − b| < 3 °/s durante ≥ 3 s y desviación de |a| < 0,03 g. Usa un promedio móvil exponencial con τ = 20 s.
- **Gravedad:** pasabajos con τ = 1 s, que se salta si la aceleración se aleja más de 0,15 g de 1 g.
- **Gravedad de referencia "de pie":** se toma en el arranque. Si la gravedad actual se aleja más de ~60° de ella, el estado pasa a **"tendido"** y se muestra "radar degradado".
- **Factor de escala:** ±3 % da ~2,7° de error cada 90° de giro. Se calibra con un asistente opcional de 5 vueltas.
- **Retraso τ entre radar e IMU:** 50 ms a 180 °/s ya son 9° de error. Se ajusta minimizando el NIS durante los giros, en las grabaciones.
- **Base de tiempo del reloj:** **`SystemClock.elapsedRealtimeNanos()`**, la misma de `SensorEvent.timestamp`, tomada en el callback de llegada de cada notificación.
  - **Mapeo de relojes:** el mínimo móvil de (llegada − t_esp) en ventanas de 10 s, más una deriva lineal.
  - El mapeo se **reinicia en cada (re)conexión**, cuando cambia `boot_id` o si `t_ms` retrocede.
- **Paquetes perdidos:** con las sumas acumuladas (§4.1), el giro perdido se reconstruye como Δψ ≈ ĝᵀ·(ΔΣω − N·b)·escala/200 y se interpola en el hueco.

### 6.4 Movimiento del jugador

- **Estado "girando":** |ω_v| > 20 °/s, filtrado a 0,2 s.
- **Estado "caminando":** desviación de |a| de las cajas > 0,08 g en 1 s, o un paso en los últimos 1,2 s.
  - El detector de pasos de las cajas es el principal. El del reloj confirma, pero tarda hasta 2 s.
- **Velocidad propia:** cadencia × largo de paso (fórmula de Weinberg, con una constante K calibrable), en la dirección de la cadera.
  - Su covarianza Σ_ego es **anisotrópica**: 0,3 m/s hacia adelante y 0,9 m/s lateral, porque en airsoft se camina de costado y de espaldas.
- **Ego-velocidad por Doppler (Kellner):** opcional. Solo se usa con ≥ 3 detecciones consistentes y un residuo < 0,2 m/s.

### 6.5 Ciclo de vida de las pistas

**Ventanas** (corregido por la revisión):
- Todas las cuentas M-de-N de §6.5 y §6.6 se hacen por ventana de **100 ms en la grilla del reloj del ESP32**: [100k, 100(k+1)) ms, según el t_ms de cada trama.
- Para cada pista, una ventana es:
  - **acierto,** si algún radar le asoció una detección;
  - **fallo,** si algún radar cuyo cono cubre la predicción entregó una trama válida (no rancia, con slots libres) y no hubo asociación;
  - **no evaluable,** en cualquier otro caso. No cuenta en N.

| Estado | Regla |
|---|---|
| Tentativa | Se borra tras 2 fallos evaluables seguidos |
| Confirmada | 3 aciertos en las últimas 5 ventanas evaluables. Si los dos radares la ven en la misma ventana cuenta como 1 |
| En espera, se perdió **quieta** (\|v\| < 0,3 m/s) | **6 s** con la posición congelada |
| En espera, se perdió **moviéndose** | 1,5 s |
| Saliendo del cono | 0,3 s y luego **"fuera de vista" 5 s**, solo como dirección |
| Protección de la espera | σ de asignación ≤ 0,7 m. La velocidad decae con v·e^(−dt/1 s) |
| ID en pantalla | Una pista nueva a menos de 1 m de la predicción de otra borrada hace menos de 2 s hereda su ID (las alertas, en §5.5) |

### 6.6 Reglas anti-fantasmas

1. **Mientras el jugador se mueve (corregida: el marco estaba invertido).**
   - Una pista **nueva** se confirma solo si su **velocidad respecto del suelo** (estado del Kalman, donde un objeto quieto queda en ≈ 0) es distinguible de cero durante ≥ 5 ventanas evaluables.
   - **Prueba principal:** d² = vᵀ(P_vv + Σ_ego)⁻¹v > χ²₂ al 99 % (9,21). Σ_ego va rotada a la odometría; si la Q_ego ya está propagada en P, no se suma dos veces.
   - **Piso:** |v| > 0,6 m/s cuando el jugador gira sin caminar.
   - En cada ventana se usa la velocidad de la detección asociada (el promedio, si la ven los dos radares).
   - Las pistas ya confirmadas no se tocan.
   - En el marco del cuerpo sin compensar, esto equivale a |v_aparente − (−v_propia)| > umbral.
2. **Sombra (reflejo en una pared).**
   - Un blanco nuevo con **rango > rango de una pista existente (tentativa o confirmada) + 0,4 m**, a ±20° de ella y con |Δv_r| < 0,3 m/s, necesita **5 de 7 ventanas**. El rango y el rumbo se miden **desde el radar que lo detectó**.
   - Para v_r se usa el campo de velocidad del LD2450. Si S6 muestra que no es Doppler radial, se usa la proyección radial de la velocidad del Kalman respecto de ese radar.
   - Diferencia con las notas, que exigían una pista de referencia confirmada: aquí basta con que exista, para cubrir el fantasma que aparece a la vez que el blanco real.
3. **Consistencia entre radares.** Si el otro radar cubre el contacto (≥ 10° dentro de su cono) y durante 3 ventanas entregó tramas válidas con slots libres sin respaldarlo, la pista no se confirma.
   - **Limitación:** los reflejos de una pared caen en el mismo punto para los dos radares, así que esta regla no los elimina.
4. **Objetivos rancios y mapa de ruido propio** (§6.1).

**MVP de Manizales:** si la velocidad propia todavía no está lista, se usa la versión **"detenerse y escanear"**:
- caminando, las pistas nuevas necesitan 5 de 7 ventanas y no generan alertas;
- las alertas vuelven en cuanto el jugador se detiene.

### 6.7 Escena y alertas

- **`RadarScene`:** para cada contacto, ángulo en el **marco lógico**, distancia, confianza, antigüedad, ID y estado, más el estado del enlace, de los radares y de los IMUs.
- **Alertas:** un evento cuando una pista pasa a confirmada y su ID de pantalla todavía no alertó (§5.5). Su lado se calcula en el marco lógico.

### 6.8 Calibraciones

| Calibración | Quién | Cuándo |
|---|---|---|
| Signos de +X y velocidad por radar. El valor por defecto sale de S6 | asistente en la app, en banco: cinturón sobre una mesa, caminar hacia cada radar y luego pararse a su derecha | una vez por módulo o al cambiar cómo va montado |
| **Alineación relativa:** traslación medida con cinta; solo se estima el yaw relativo (1 grado de libertad; Umeyama completo solo como diagnóstico), con un ayudante caminando por el solape | asistente | al cambiar o reajustar el montaje |
| **Rumbo absoluto del par:** el jugador queda quieto con la cadera hacia una línea de cinta en el piso; el ayudante camina por esa línea a más de 0,8 m; se ajusta la dirección de la trayectoria. Se puede hacer en la misma sesión que la alineación | asistente | ídem |
| *Sin rumbo absoluto:* se usa el **promedio de los ángulos nominales del perfil** (−10° en diestro), nunca la bisectriz, y se reparte la corrección relativa ±Δ/2. La app muestra "rumbo sin calibrar" | automático | — |
| **Mapa de ruido propio:** **2-3 min** solo; quieto moviendo brazos y réplica, caminando y girando en el sitio, en campo abierto y junto a una pared | asistente | antes de jugar, opcional |
| "Cero" de postura táctica, con el signo ±90° (§3.3) | asistente | una vez por jugador (no en el MVP) |
| Sesgo de los giroscopios | automática | al arrancar y en reposo |
| Factor de escala (5 vueltas) | asistente opcional | una vez |
| Retraso τ entre radar e IMU | con las grabaciones | desarrollo |
| Constante K del largo de paso (caminar 20 m medidos) | asistente opcional | una vez por jugador |

### 6.9 Configuración

Todos los umbrales viven en **`TuningParams`**, una clase de datos inmutable. Sus valores por defecto vienen de las notas: algoritmos §7, y los del limitador de ideas_creativas_ux §3. Se puede cargar una variante desde un archivo para hacer barridos.

### 6.10 Grabación y reproducción (`.bsrec`)

- **Cabecera:** firma `BSREC`, versión, `info` del cinturón, `TuningParams` y calibración (JSON), y la hora de inicio.
- **Registros:** `u8 tipo | u32 ms desde el inicio de la grabación (elapsedRealtime) | u16 largo | payload`. Tipos:
  1. paquete BLE crudo;
  2. gravedad del reloj, con el **timestamp del evento**, no el de escritura;
  3. paso del reloj;
  4. **marcador manual**, que el jugador dispara cuando ve a un rival real (ground truth barato);
  5. **pista confirmada**;
  6. **vibración iniciada**;
  7. cambio de modo: eliminado, Sigilo o Vista.

  Los tipos 5 y 6 hacen medible la latencia de extremo a extremo desde F0.
- **Tamaño:** ~11-12 MB cada 2 h de paquetes crudos, así que **~30 MB para las 5 h de Manizales**.
- **Reproducción:** es determinista (el reloj se inyecta). En la v1 se exporta con `adb pull`.

### 6.11 Simulador

Un pequeño lenguaje de escenarios en Kotlin genera paquetes: aplica la codificación inversa del LD2450 y sintetiza los IMUs. Se usa en las pruebas y como fuente **"demo"** en la app.

---

## 7. Flujo de datos

```
LD2450 A ─UART2─┐                                   ┌─ gravedad → postura ─┐
IMU A ───I2C1───┤ ESP32 (pouch, pierna der.)        │                      │
LD2450 B ─UART1─┤ tramas crudas + t_ms              │                      ▼
IMU B ───I2C0───┘ IMU 200→50 Hz + sumas ─ TLV/100 ms ─BLE─▶ Servicio ─▶ radar-core ─▶ RadarScene ─▶ Compose
                                                    │   └─ pasos ───────────────┘        └─ alertas ─▶ Vibrator
                                                    └─ grabador .bsrec (paquetes + sensores + eventos)
```

---

## 8. Manejo de errores

| Falla | Qué hace el sistema |
|---|---|
| Se corta el enlace BLE | Se detecta tras el *supervision timeout* (~4 s). La pantalla pasa a gris con "--" y queda una conexión pendiente (`autoConnect=true`) que reconecta en cuanto el ESP32 anuncia (§5.2). **Una sola** vibración de sistema |
| Un radar deja de emitir | El firmware le envía enable + reinicio y reintenta cada ~30 s. `flags` lo marca caído, la pantalla dibuja la cobertura que queda con un aviso y el tracking sigue con un solo radar. El corte de alimentación queda para la v1.5 |
| Tramas u objetivos corruptos | El ESP32 descarta por cabecera o cola y lo cuenta en STATUS. El reloj descarta por plausibilidad y lo cuenta. Si se supera un umbral, aparece un aviso |
| MTU sin negociar o paquete truncado | El ESP32 no notifica hasta tener el MTU, y parte o descarta marcando `flags.bit4`. El reloj descarta la sección y lo cuenta |
| Huecos en la secuencia | Se cuentan en módulo 2¹⁶. El Kalman predice el hueco y el giro se **reconstruye** con las sumas acumuladas (§6.3) |
| Falla un IMU | Se usa el otro, con un aviso |
| Fallan los dos IMUs | Sin compensación de movimiento, con el aviso "más fantasmas al moverte", y la regla 1 pasa a "detenerse y escanear" |
| El reloj no tiene `TYPE_GRAVITY` | Pasabajos sobre el acelerómetro |
| El motor no soporta primitivas | Patrones de encendido y apagado |
| El sistema silencia la vibración (No molestar, teatro, dormir o intensidad baja) | El quiz obligatorio del modo práctica lo detecta antes de la partida y avisa |
| Permiso BLE denegado | La sesión no arranca y la app explica el motivo |
| `ACTIVITY_RECOGNITION` denegado | Sesión sin pasos del reloj; el mini estado lo indica |
| Jugador tendido | Aviso "radar degradado" |
| Batería baja del reloj | Sugiere Sigilo |
| Un rival intenta conectarse | Fuera de la ventana de emparejamiento, el controlador lo descarta (lista blanca por identity address). Con el reloj conectado, el ESP32 no anuncia. Dentro de la ventana podría conectarse, pero sin la clave no cifra ni puede leer `stream` ni `info` |

---

## 9. Pruebas y validación

### Pruebas unitarias de `radar-core` (JVM)

- **Decodificador:** tramas de ejemplo del manual (−782 mm / +1713 mm / −16 cm/s).
- **TLV:** tipos desconocidos, **`largo` fuera de rango o paquete truncado**, **vuelta de seq**.
- **Transformaciones y Kalman.**
- **GNN.**
- **Cada regla anti-fantasmas,** incluidos estos casos:
  - el jugador camina hacia una pared: **0 pistas confirmadas**;
  - un rival camina de frente hacia el jugador mientras este avanza a la misma velocidad: **la pista se confirma**.
- **Postura:**
  - en táctica, con el antebrazo 20° hacia abajo, el signo y el lado de la vibración no cambian;
  - un ángulo cercano a 180° conserva la postura actual.
- **Sesgo:**
  - una señal quieta con un offset de (8, −5, 3) °/s y balanceo de 1 °/s se acepta, y b₀ queda a menos de 0,1 °/s del offset;
  - un giro lento de 5 °/s se rechaza;
  - la recalibración se dispara con el offset de 8 °/s.
- **Limitador:**
  - la primera alerta no se retrasa;
  - una persona que se pierde ~2 s y reaparece genera **una sola** alerta;
  - **con el modo eliminado activo no hay ninguna vibración de contacto ni se enciende la pantalla.**
- **Mapeo de relojes y reconstrucción del giro en un hueco.**

### Escenarios con el simulador

- Una persona cruza.
- El jugador gira con un blanco quieto.
- El jugador camina hacia una pared.
- Un rival avanza de frente.
- Dos personas a la misma distancia.
- Una persona se queda quieta.
- Un blanco sale del cono.

### Reproducción de grabaciones reales

Con resultados de referencia ("golden"). Métricas:
- **falsos contactos confirmados por minuto** y **% de tramas con los 3 slots ocupados por radar**, separados en quieto, girando y caminando;
- **latencia de extremo a extremo** (registros 1, 5 y 6);
- GOSPA;
- fragmentación de pistas;
- NIS/NEES.

### App (pruebas instrumentadas)

- Arranque del servicio con `ACTIVITY_RECOGNITION` denegado: sin crash.
- Arranque con `BLUETOOTH_CONNECT` denegado: la sesión no arranca y se muestra el mensaje.

### Firmware

- Entorno `native`: parser, `bundler` (que parte sin cortar secciones y respeta `max_payload`) y la tabla de STATUS.
- En banco, registro por el puerto serie.

### Seguridad

- La reconexión sigue funcionando en ≤ 5 s después de que el reloj rota su dirección privada.
- Fuera de la ventana, un teléfono con nRF Connect no puede conectarse.
- Dentro de la ventana se conecta, pero falla al suscribirse a `stream` o al leer `info`.
- Una app rival que reconecta en bucle no retrasa al reloj.

### Spikes, primero que todo (F0)

| # | Pregunta | Por qué bloquea |
|---|---|---|
| S1 | ¿Llegan a tiempo los paquetes con la pantalla apagada o atenuada, también con `dumpsys deviceidle force-idle`, **con y sin el wake lock**? ¿Se cumplen la latencia de contacto a vibración y los temporizadores? Además, ¿cuál es la latencia interna del LD2450? | Todo Sigilo y los criterios 1 y 2 |
| S2 | ¿Puede la app encender la pantalla? | Solo define si existe la opción "encender ante contacto" |
| S3 | ¿Qué soporta el motor (primitivas, amplitud)? Con USAGE_ALARM y USAGE_NOTIFICATION, ¿vibra desde el servicio con la pantalla apagada y con **No molestar, teatro y dormir** activos, y con la intensidad del sistema baja? | Sigilo depende de la vibración |
| S4 | ¿Se interfieren dos LD2450 a ~30 cm (la distancia medida) con la apertura de §2.3? | D5 |
| S5 | ¿El filtro de zonas del LD2450 libera slots? | Decide si se agrega `SET_ZONES` |
| S6 | ¿Hacia dónde es +X? ¿Qué signo y qué semántica tiene la velocidad? | El decodificador |
| S7 | **Saturación:** % de tramas con los 3 slots ocupados caminando y girando sin rivales, y fantasmas por minuto. ¿Cambia con el radar inclinado hacia arriba? ¿Y con la tapa impresa frente a sin tapa? | Criterios 1 y 4, el montaje y **el corte de F0** |
| S8 | Batería en Sigilo y en Vista. Matriz: BALANCED frente a LOW_POWER (HIGH como referencia), × brillo, × wake lock; 30 min por combinación. ¿Se apaga solo el power bank? ¿Cae el VIN con los dos radares y el BLE? (`esp_reset_reason`) | Criterios 5 y 7 |
| S9 | Clasificador de postura con agarre tradicional, C-clamp y empuñadura vertical; apuntando, en low ready (bocacha 30-45° abajo) y con la réplica colgada | §3.2 (no en el MVP) |
| S10 | ¿El WROOM-32 con NimBLE 2.x resuelve la dirección privada del Galaxy Watch con el filtro de lista blanca activo? | §4.3, o el plan B |
| S11 | ¿Puede la app recibir la pulsación del botón físico del Watch 7 (KEYCODE_STEM_*) con Sigilo activo? | Acceso al modo eliminado |

**Corte de F0 tras S7:** si la saturación al caminar supera **X %**, la v1 pasa a **"detenerse y escanear"**: confirmaciones y alertas solo con el jugador quieto o lento, y se redefinen los criterios 1 y 4 para el caso "caminando". El valor de X lo decide Santiago (Q8).

---

## 10. Legal, juego limpio y seguridad

### 10.1 Reglas de los campos [CAMBIO: de la investigación]

- Ningún reglamento encontrado menciona radares. El caso más parecido es la cámara térmica:
  - algunos eventos la permiten (por ejemplo American Milsim en 2026);
  - muchos campos pequeños la prohíben por ser un "wallhack";
  - entre los que la permiten es común exigir **declararla al registrarse**, además de distancias mínimas y límites de aumento.
- **Postura de Blindside:**
  - **Preguntar en cada campo y declararlo.**
  - **Modo eliminado** **[CAMBIO D3]:** el jugador lo activa cuando le dan y lo desactiva al reaparecer. No hay detección automática de impactos. Mientras está activo:
    - apaga la pantalla;
    - deja de procesar contactos;
    - **no vibra por ninguno;** solo quedan las alertas de sistema;
    - el ESP32 y la grabación siguen funcionando.
  - **Modo torneo:** pasa a la v1.5, alineado con la investigación (solo háptica o sectores, con retraso). "Apagar el dispositivo" no es una función.

### 10.2 Radio

- **Colombia:** la Resolución ANE 105/2020, Anexo 1, Tabla 1.2 permite radiodeterminación en **24,05-24,25 GHz** hasta 100 mW P.I.R.E.
  - El LD2450 declara un barrido de **24,00-24,25 GHz**, así que el tramo de 24,00-24,05 GHz es zona gris.
  - La tabla muestra una adición de la **Res. ANE 153/2024** ("Aplicaciones RCA no específicas") cuyas bandas no se verificaron. Podría cubrir ese tramo.
- **EE. UU.:** FCC §15.249.
- **UE:** la ERC 70-03 recoge 24,05-24,25 GHz para radiodeterminación (Anexo 6) y **24,00-24,25 GHz para dispositivos de corto alcance no específicos** (Anexo 1, banda m), ambos a 100 mW e.i.r.p. (EN 300 440). Si es así, el barrido completo cabe en la UE. Los textos primarios no se leyeron.
- El módulo dice cumplir FCC/CE pero no declara potencia, y no se encontró un FCC ID.

### 10.3 Seguridad del dispositivo

- El Bluetooth del propio LD2450 viene encendido y se puede reconfigurar desde la app de Hi-Link: **el firmware lo apaga** (§4.1).
- BLE de Blindside:
  - emparejamiento solo en una **ventana explícita**;
  - **clave por dispositivo**;
  - **lista blanca por bond** (identity address);
  - **una sola conexión**;
  - **características cifradas** (§4.2-4.3).
- Sin nube ni telemetría: las grabaciones quedan en el reloj.

### 10.4 Avisos (para el README)

- No detecta latidos. No ve de forma confiable a través de paredes ni coberturas. Pierde a las personas quietas. Alcance de ~6 m. Rinde mejor con el jugador quieto o avanzando despacio.
- Muestra también a tus compañeros: la v1 no identifica amigo o enemigo.
- No es un equipo de seguridad.
- **Úsalo solo en partidas y con conocimiento de los participantes;** nunca para detectar o seguir personas fuera del juego. Es un accesorio de juego, no una herramienta de vigilancia.
- No modifiques el firmware, la antena ni la potencia del LD2450, y cumple la normativa local de espectro.
- Publicar el código no plantea problemas de radio, pero **vender kits armados** puede exigir homologación, marcado CE o FCC.
- No tiene relación con Activision (Call of Duty), 20th Century Studios (Aliens) ni Hi-Link.

---

## 11. Roadmap [CAMBIO: priorización tomada de la investigación de UX]

- **v1:** todo lo de este documento. El MVP de Manizales (§12) es un subconjunto.
- **v1.5:**
  - Motores de vibración en las cajas (dirección por posición; requiere compra).
  - Corte de alimentación de los radares con MOSFET y condensadores de reserva (requieren compra).
  - Corrección de la flexión del cinturón entre cajas.
  - Silenciar y atenuar al apuntar.
  - IFF manual (tocar un contacto).
  - Perfil "de rodilla".
  - Cambio de hombro con acceso rápido.
  - Visor de repeticiones y mapa de calor.
  - Modo centinela.
  - Modo "clásico COD".
  - **Modo torneo.**
  - Térmica MLX90640 si hace falta (D10).
- **v2:**
  - IFF por UWB con ancla DW3220 de doble antena y protección contra el "falso amigo".
  - Enlace de escuadra por ESP-NOW.
  - **Interferencia entre jugadores con Blindside** frente a frente, a medir.
  - 360° (3-4 radares).
  - Nodo en el riel.
  - App de celular y exportación a CoT/ATAK.
  - **Audio espacial por auricular** (verificar la latencia BT).
  - IFF "lite" por Channel Sounding (solo distancia; el reloj no lo soporta).
- **Descartado:** IFF por RSSI, balizas infrarrojas, interferir el radar rival, ultrasonido HC-SR04.

---

## 12. Fases y MVP para Manizales (11-oct-2026)

El plan detallado se escribe con la skill `writing-plans` cuando Santiago apruebe este documento. Quedan **10 días de desarrollo** (1-10 oct) y no se puede comprar nada (D17).

### Alcance del MVP

| Entra | Queda para la v1 completa |
|---|---|
| Firmware: arranque de los LD2450 (BT apagado), tramas crudas, 2 IMUs, `bundler` TLV que parte paquetes, NimBLE con ventana de emparejamiento + clave por dispositivo + lista blanca, watchdog por comando | Plan B de S10 pulido |
| `radar-core`: decodificación con plausibilidad, geometría con ángulos nominales y medidos, exclusión 0,8 m, GNN + Kalman, ventanas M-de-N, espera según contexto, rotación compensada con los IMUs, **"detenerse y escanear"** (§6.6) | Velocidad propia completa (regla 1 con Σ_ego), Doppler, mapa de ruido propio |
| App: servicio + wake lock, BLE (bond, reconexión), abanico en Vista, **Sigilo con vibración izq/centro/der**, limitador, **modo eliminado**, mini estado, **grabación `.bsrec`** completa, quiz de vibración, ajustes básicos (mano, ángulos, signos) | Postura táctica y "cero" (Santiago usa empuñadura vertical), asistentes de calibración, color, encender ante contacto, burn-in fino |
| Pruebas: unitarias del decodificador, TLV y tracker; escenarios básicos del simulador | Batería completa de escenarios y golden |

### Calendario propuesto

| Día | Trabajo |
|---|---|
| **1 oct (jue)** | Spikes S6 y S4 en banco. Imprimir las cajas (medir el ángulo con transportador primero). Cablear. Esqueleto del firmware |
| **2-3 oct** | Firmware completo + `radar-core` (decodificación, tracker) con pruebas. Spike S1 con una app mínima |
| **4 oct (dom)** | Primera prueba en el patio con un amigo, **grabada**. S7 (saturación) |
| **5-7 oct** | App MVP: servicio, BLE, pantalla, vibración, grabación, modo eliminado. Ajuste con las grabaciones |
| **8 oct** | **Prueba de resistencia de 5 h en casa** (S8: batería, reconexión, ningún crash) |
| **9 oct** | Correcciones. Si se puede, una partida de 1 h de prueba |
| **10 oct** | Congelar. Cargar todo. Checklist: LEDs tapados, quiz de vibración, No molestar, cargador del reloj al power bank |
| **11 oct** | **Manizales:** jugar y **grabar las 5 h** |

### Fases de la v1 completa (después del 11-oct)

- **F4:** velocidad propia completa, reglas anti-fantasmas, asistentes, postura táctica y "cero".
- **F5:** campo con barridos de parámetros sobre las grabaciones de Manizales; verificar los criterios de §1.
- **F6:** release con APK firmado, binario del firmware, guía de armado (incluida la de tapar los LEDs) y archivos de las cajas.

**Desarrollo:** se delega por lanes (Codex/Antigravity para el razonamiento, bipolar/Ollama para lo mecánico). Claude orquesta y revisa.

---

## 13. Preguntas abiertas

**Respondidas (30-sep):**
- Q1: diestro (D13).
- Q2: empuñadura vertical (D13).
- Q3: hay impresora 3D (D14).
- Q4: el reloj es de 44 mm (D15).
- Q5: partidas de 1 h, y 5 h en Manizales (D18).
- Q6: las portacargadores no son relevantes, y el ESP32 va en el pouch (D16).

**Nuevas:**
1. **Q7:** ¿En qué muñeca llevas el reloj y con la esfera por fuera o por dentro? Para la postura táctica tendría que ir en la muñeca de apoyo con la esfera hacia adentro. Con empuñadura vertical igual no se lee apuntando; ¿te sirve leerlo en postura normal en las pausas?
2. **Q8:** ¿Qué umbral X de saturación al caminar (S7) aceptas antes de pasar a "detenerse y escanear"? Propuesta: 30 % de tramas saturadas.
3. **Q9:** ¿Apruebas las dos excepciones a D3: el tope de 10 vibraciones por minuto (§5.5) y el modo eliminado sin vibración (§10.1)?
4. **Q10:** ¿Te parece bien que en Sigilo la pantalla **no** se encienda sola por defecto (§5.4)?
5. **Q11:** ¿Apruebas el alcance y el calendario del MVP (§12)? Es lo que destraba escribir el plan e implementar.
