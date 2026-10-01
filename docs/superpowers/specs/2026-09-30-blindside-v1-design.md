# Blindside v1: diseño

- **Fecha:** 2026-09-30, revisión 3 (incorpora las dos revisiones adversariales y las respuestas y decisiones de Santiago del 30-sep por la noche)
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
- **Primera revisión adversarial (30-sep):** 5 revisores independientes, y cada hallazgo pasó por verificadores que intentaban refutarlo. De 104 hallazgos sobrevivieron 84, de los cuales 66 eran del spec. Todos quedaron incorporados en esta versión.
- **Segunda revisión (30-sep, noche):** 39 hallazgos verificados (R1-R39), incorporados con las decisiones de Santiago sobre el limitador, "detenerse y escanear", el tamaño y orden del `bundler`, los parámetros del enlace, el sesgo y el signo táctico.

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
   - **≤ 1,0 s** caminando o girando (objetivo provisional: depende de la latencia medida de la regla anti-fantasmas 1, §6.1, y se fija con la línea base de F5).
2. **Latencia de la vibración.** Desde esa misma primera trama hasta que empieza la vibración: **≤ 0,75 s (p90)** con el jugador quieto, medido en alertas que el limitador no retuvo. El tiempo hasta que termina el patrón (~0,5 s más) se reporta aparte.
   - Las alertas que el limitador retuvo en el hueco de 1 s (§5.5) se reportan aparte: latencia desde la primera trama y retraso añadido por el limitador.
3. **Dirección:** la vibración indica el lado correcto en **≥ 90 %** de los casos, medido en el modo práctica.
4. **Falsos contactos confirmados por minuto:**
   - **≤ 1/min** con el jugador quieto.
   - **≤ 3/min** caminando.
   - Se reportan además el **% de tramas con los 3 slots ocupados** por radar (saturación, §9) y la línea base de la primera sesión.
5. **Sesión: ≥ 5 h** sin que la app muera, en Sigilo con la pantalla apagada. Es la partida de Manizales.
6. **Reconexión.** Con el reloj y el cinturón en rango, vuelven a llegar datos en **≤ 5 s** desde que el ESP32 anuncia de nuevo. Una caída por bloqueo o rango se detecta en lo que dure el *supervision timeout* (~5 s).
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
- WiFi (descartado por D1).

Todo esto, salvo WiFi, está en el roadmap (§11).

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

**Asignación de conductores** (UM10204, "Wiring pattern of the bus lines": en tramos de más de 10 cm, cada línea I2C va trenzada con GND, o SCL con GND y SDA con 3V3). SDA y SCL nunca van en el mismo par trenzado. Las líneas UART tampoco van pegadas a SDA o SCL.
- **Ethernet:** par 1 = SDA + GND; par 2 = SCL + 3V3; par 3 = TX + RX del radar; par 4 = 5V + GND (segundo hilo de tierra, unido a GND en los dos extremos).
- **2 cables USB:** el cable 1 lleva el radar (VBUS = 5V, GND = GND, D+/D− = TX/RX). El cable 2 lleva el IMU (VBUS = 3V3, D+ = SDA, D− = GND unido en los dos extremos, y el hilo GND original = SCL). Si hay malla, se une a GND solo del lado del ESP32.
- **Dupont (último recurso):** trenzar a mano SDA con un hilo de GND y SCL con otro hilo de GND o de 3V3. TX y RX van en otro trenzado, separado de los del I2C.

- **Dos buses I2C separados,** uno por caja. Las dos placas pueden quedar en la dirección 0x68 (AD0 a GND), y una falla de cable no tumba el otro IMU, siempre que cada IMU tenga su propia tarea y timeout (§4.4).
- **Velocidad I2C: 100 kHz**, por el largo de los cables. Con lecturas de 14 B a 200 Hz el bus queda a ~30 % de carga.
- **IMU a 3V3,** no a 5V: así las resistencias de pull-up del GY-521 quedan a 3,3 V sin depender de cómo esté cableada la placa clon.
- **Botón BOOT (GPIO0):** solo se lee como entrada **después** del arranque y solo durante los primeros 60 s, para abrir la ventana de emparejamiento o borrar los bonds (§4.3). Nunca mantenerlo presionado al encender, porque entra en modo descarga. En el pouch lo cubre una tapa impresa (§2.3).
- **LED de estado:** GPIO2, el de la placa (§4.5).
- **Pines prohibidos:**
  - GPIO6-11 (flash).
  - Los de arranque 0, 2, 5, 12 y 15, salvo los dos usos de arriba.
  - **Nunca** conectar la salida de un radar a GPIO12, porque impide que el ESP32 arranque.
- **Condensador de reserva** (si hay, §2.1): 100-470 µF entre 5V y GND junto a cada LD2450. Hi-Link pide una fuente de más de 200 mA con un consumo medio de 120 mA, lo que sugiere picos en las ráfagas de transmisión. Con dos radares y el BLE en el mismo riel, puede caer el VIN.
- Los UART del LD2450 trabajan a 3,3 V, así que se conectan directo.
- Con esta asignación de conductores, 256000 baudios y 100 kHz de I2C, 50-80 cm de cable no son un problema.

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
- **Pouch del ESP32:**
  - La DevKit va con el extremo de la antena PCB hacia arriba (hacia el cinturón) y la antena mirando hacia afuera, lejos de la pierna.
  - Nada de metal entre la antena y el exterior, incluidos la placa de borneras, los cables y el power bank (§2.4).
  - Una **tapa impresa** cubre BOOT y EN: el lado de EN queda cerrado del todo y BOOT solo se alcanza por un agujero de alfiler, para que la presión de la pierna no lo mantenga apretado. También evita que EN + BOOT pongan la placa en modo descarga.

### 2.4 Energía

- **Consumo estimado:** 2 × 120 mA (radares) + ~40 mA (ESP32 con BLE) + ~2 × 4 mA (IMUs) ≈ **300 mA a 5 V (~1,6 W)**.
- 5 h en Manizales consumen ~1.500 mAh: el UGREEN da de sobra. El cargador del reloj también se puede enchufar al power bank en un descanso.
- A ~300 mA el power bank no debería apagarse solo, pero se confirma en S8.
- **Dónde va el power bank:** del lado de la pierna de la DevKit, o en otro bolsillo que el jugador ya tenga (sin comprar nada); nunca entre la antena y el exterior (§2.3).

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
  - El IMU muestrea a 50 Hz, pero el reloj recibe las muestras juntas cada 100 ms (§4.2). Para que la pantalla no salte a 10 Hz, el yaw se **extrapola al presente**: ψ(ahora) = ψ(t_última) + ω̄_v · min(ahora − t_última, 150 ms), con ω̄_v como promedio de las últimas 2-3 muestras (t_última: §4.2).
  - Cuando llega un paquete, la diferencia entre lo extrapolado y lo medido se mezcla con un τ de ~50 ms.
  - Ese mismo ψ(ahora) rota las pistas y define los sectores de vibración.
  - La deriva lenta del giroscopio no se nota, porque la pantalla nunca muestra la odometría directamente.

### 3.2 Postura del reloj (D7, precisada por la revisión)

- **La gravedad solo decide entre normal y táctica,** con bandas muertas. Se mide con `TYPE_GRAVITY`, o con un pasabajos sobre el acelerómetro si no existe. α es el ángulo de la gravedad en el plano de la pantalla:
  - |α| ≤ 35°: normal.
  - 55° ≤ |α| ≤ 125°, de cualquier signo: táctica.
  - Cualquier otro caso (35-55°, 125-180°, o una magnitud en el plano menor de 2 m/s²): **conserva la postura actual**.
  - Un cambio requiere que la nueva postura se mantenga ≥ 300 ms.
- **El signo de la táctica (±90°) es un valor guardado por jugador y nunca se calcula con la gravedad.**
  - Tiene tres orígenes posibles: "del cero" (lo fija el paso 3 de §3.3), +90° o −90° (elegidos a mano en ajustes).
  - Mientras no haya cero ni un valor manual, el signo queda **"sin calibrar"**. En ese estado la postura automática no entra en táctica: se queda en normal y la app muestra "táctica sin calibrar: haz el cero o elige ±90°". "Forzar táctica" sigue el mismo criterio.
  - No se deduce de la mano ni de la muñeca, porque depende de cómo se abroche la correa (notas: biomecánica).
  - **Por qué no la gravedad:** la regla "el borde más alto apunta adelante" falla en low ready, con la bocacha 30-45° hacia abajo, y giraría la pantalla 180°.
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
   - Anota la hora de llegada de la cabecera con `millis()`, en la tarea `radar_rx` de su UART (§4.4).
   - Cada UART tiene un buffer de recepción de 2 KB.
3. **IMUs** (uno por caja, cada uno en su bus):
   - Muestreo a **200 Hz**.
   - Giroscopio a **±500 °/s** (65,5 LSB/(°/s)) y acelerómetro a **±8 g** (AFS_SEL = 2, 4096 LSB/g).
   - Filtro interno con `DLPF_CFG = 3` (~42 Hz).
   - **Clones de la familia MPU-6500/9250/ICM-206xx** (WHO_AM_I ≠ 0x68): se escribe además `ACCEL_CONFIG2` (0x1D) con `A_DLPF_CFG = 3`, porque en esos chips el filtro de `CONFIG` solo afecta al giroscopio.
   - **Salida a 50 Hz:** cada 20 ms promedia 4 muestras.
   - **Sumas acumuladas** desde el arranque de gx, gy, gz crudos a 200 Hz (u32 con vuelta), para reconstruir el giro si se pierde un paquete (§6.3).
     - Junto con cada muestra de 50 Hz se guardan las sumas acumuladas al final de su bloque, para poder cerrar una sección IMU en cualquier muestra (§4.2).
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
| `info` | lectura cifrada (READ_ENC) | Versiones del protocolo y del firmware; versión y baud de cada radar; WHO_AM_I y **escalas del giroscopio y del acelerómetro** de cada IMU; lecturas repetidas de cada IMU (tiempos del IMU, abajo); MTU negociado; intervalo de conexión, latencia y *supervision timeout* vigentes (los últimos que reportó `NimBLEServerCallbacks::onConnParamsUpdate`, vía `NimBLEConnInfo::getConnInterval/getConnLatency/getConnTimeout`); potencia TX leída con `NimBLEDevice::getPower()` (§4.3); `esp_reset_reason()`; `boot_id` aleatorio de cada arranque; uptime |
| `control` | escritura cifrada y autenticada (WRITE_AUTHEN) | `0x01 RESTART_RADAR(id)`, `0x03 IDENTIFY` (solo fuera de sesión, §4.5). `0x02 SET_ZONES(id, …)` **solo si el spike S5 demuestra que el filtro de zonas libera slots** |

**Tabla GATT congelada desde el día 1:** el UUID del servicio y las 3 características, con sus propiedades y su orden. Android guarda en caché la tabla de los dispositivos con bond.
- El firmware conserva el servicio GATT de NimBLE con Service Changed. Si la tabla cambia alguna vez, llama a `sendServiceChangedIndication()` tras cifrar, en la primera conexión con la tabla nueva. Un hash de la tabla en NVS es opcional; no hace falta en el MVP.
- La app reacciona con `onServiceChanged()` (§5.2).
- En desarrollo, si la tabla cambia sin la indicación, se olvida el cinturón en el reloj y se vuelve a emparejar.

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
  0x04 LINK    u16 intervalo (×1,25 ms) | u16 latencia | u16 timeout (×10 ms)                    → 8 B
  0x10-0x1F    reservadas (0x10 = térmica, D10)
```

- **Contenido típico de un corte de 100 ms:** 8 + 2 × 31 (radar) + 2 × 80 (IMU con n = 5) = **230 B**, y **242 B** con STATUS (1 Hz). Ambos caben en un paquete de ≤ 244 B; con una trama de radar extra (jitter), el `bundler` lo parte en dos.
- **Tiempos del IMU:** el muestreo a 200 Hz sigue una grilla fija de 5 ms (`vTaskDelayUntil` en la tarea de cada IMU, §4.4), así que cada bloque de 4 lecturas ocupa exactamente una ranura de una grilla de 20 ms.
  - El t de cada muestra de 50 Hz es el centro de su bloque. `t_primera_ms` es el t de la primera muestra de la sección, y t_última = t_primera_ms + (n − 1)·20 ms.
  - Si una lectura falla o su ranura se salta, se repite la lectura anterior, para que las sumas avancen exactamente 4 lecturas por ranura. Las repeticiones se cuentan por IMU en `info`.
  - Las sumas de una sección cubren hasta el final de su último bloque.
- **LINK (0x04):** se envía en el paquete siguiente a cada `onConnParamsUpdate` de NimBLE y una vez tras la suscripción al CCCD. Si no cabe, el `bundler` parte el paquete como siempre. Es raro, así que el presupuesto de 244 B de un corte típico no cambia.
- **Contrato del `bundler`:**
  - Es una función pura que se prueba en el entorno `native`.
  - Corta por tiempo: incluye las tramas **completas** con t_ms < corte − 5 ms; una trama más nueva, o todavía en recepción, espera al corte siguiente (≤ 5 ms más de latencia). El margen existe porque `radar_rx` anota t_ms al ver la cabecera y entrega la trama ~1,2 ms después (30 B a 256000 baud), más lo que tarde en planificarse su tarea; sin él, una trama más vieja podría salir en el paquete siguiente y el reloj la descartaría (§6.1, paso 5).
  - Recibe `max_payload = min(getPeerMTU() − 3, 244)`: el tope de 244 B hace que cada notificación quepa en un solo paquete de enlace con DLE (251 − 4 de L2CAP − 3 de ATT).
  - **Si el contenido no cabe, lo parte en varios paquetes consecutivos**, cada uno con su cabecera y su seq. **Nunca parte una sección** (la única excepción es una IMU con n > 18, abajo).
    - **Orden de llenado:** primero las secciones IMU, STATUS y LINK del corte; después las tramas RADAR de ambos radares, ordenadas por t_ms. Llena cada paquete en ese orden antes de abrir el siguiente.
    - Así, toda trama RADAR del paquete k tiene t_ms ≤ las del paquete k+1, y los datos IMU de un corte nunca llegan después de sus tramas de radar.
  - Solo descarta (y marca `flags.bit4`) si la cola de notificaciones de NimBLE está llena (`BLE_HS_ENOMEM`). Primero descarta las tramas de radar más viejas y después las muestras de IMU más viejas, y lo cuenta.
- **MTU:**
  - El reloj pide `requestMtu(517)`.
  - NimBLE-Arduino propone **255** por defecto y acepta hasta 527, así que en Android 14+ el MTU final es 255 (252 B útiles).
  - Por eso el `bundler` limita cada paquete a **244 B** aunque el MTU deje 252 (`setDataLen(251)`).
- **El ESP32 no notifica** hasta que el CCCD esté activo y `getPeerMTU()` ≥ 247 (max_payload = 244; cualquier reloj Android negocia 255).
  - Una sección IMU nunca supera 244 − 8 B: si n > 18, el `bundler` la divide por muestras en secciones IMU consecutivas, cada una con su `t_primera_ms`, su n y las sumas al final de su último bloque.
- **El lector TLV:**
  - Salta los tipos desconocidos usando el campo de largo.
  - **Si `largo` supera los bytes que quedan, descarta el resto del paquete** y lo cuenta como truncado.
- **seq** da la vuelta cada 65536 paquetes: ~1,8 h al ritmo típico de 10 paquetes/s, y ~0,9 h si el `bundler` partiera todos los cortes (20/s). En las 5 h de Manizales da la vuelta varias veces.
  - Paquetes perdidos = `((seq − seq_prev) mod 65536) − 1`, con aritmética sin signo.
  - Si `t_ms` retrocede (el ESP32 se reinició), no se calcula el hueco: se reinicia la referencia.

### 4.3 Emparejamiento, seguridad y conexión

**Corregido por la revisión: una clave fija en un repo público no protege nada.**

- **Ventana de emparejamiento explícita:**
  - Se abre solo si **no hay ningún bond guardado**, o con el gesto de **BOOT de 3-10 s** (abajo).
  - Dura **60 s**, con el LED parpadeando.
  - Solo dentro de la ventana se acepta un emparejamiento nuevo (LE Secure Connections + bonding con distribución de IRK). Se cierra a los 60 s o con el primer bond exitoso.
  - Un bond nuevo reemplaza al anterior.
- **Botón BOOT:** los gestos solo cuentan en los **primeros 60 s tras encender**, con el firmware ya arrancado, y actúan **al soltar**:
  - 3-10 s: abre la ventana de emparejamiento.
  - **10 s o más:** borra los bonds y nada más. Como ya no queda bond, la ventana se abre sola.
  - Después de borrar hay que olvidar el cinturón también en el reloj y volver a emparejar. Lo mismo aplica si otro reloj reemplaza el bond o si se borra la flash. La app detecta el bond perdido (§5.2), así que un borrado de 10 s se deshace en el campo sin portátil.
  - La tapa impresa del pouch evita pulsar BOOT o EN sin querer (§2.3).
- **Clave por dispositivo:**
  - 6 dígitos aleatorios generados en el primer arranque y guardados en NVS.
  - Se imprimen por el puerto serie **en cada arranque** y con el comando serie `key`, y se anotan en una etiqueta del cinturón. **Nunca** van en el código ni en un flag de compilación versionado.
  - Solo se regeneran con el comando serie `key new`, nunca con un gesto del botón.
  - Un borrado completo de la flash o de la NVS (por ejemplo "Erase Flash" de PlatformIO) también crea una clave nueva. Después hay que reimprimir la etiqueta desde el puerto serie y olvidar el bond en el reloj; la guía de armado lo advierte.
  - **IO capability del ESP32: DisplayOnly** (`NimBLEDevice::setSecurityIOCap(BLE_HS_IO_DISPLAY_ONLY)`), `setSecurityAuth(bond, MITM, SC)` y la clave de NVS con `setSecurityPasskey`. El valor por defecto de NimBLE (NoInputNoOutput) cae en Just Works y hace fallar `control` (WRITE_AUTHEN).
  - **Riesgo residual:** un rival presente durante la ventana podría recuperar una clave estática; conviene emparejar lejos de los rivales.
  - **Plan B si S12 falla:** LE SC Just Works solo dentro de la ventana de 60 s, con la lista blanca, y `control` relajado a WRITE_ENC. **Debilidad conocida:** quien se conecte durante la ventana puede emparejarse sin clave.
- **Lista blanca por bond:**
  - Se usa la *resolving list* de NimBLE: la lista guarda la **identity address** resuelta con la IRK, porque el reloj usa direcciones privadas que cambian cada ~15 min.
  - Fuera de la ventana se anuncia con *filter policy* "conexiones solo desde la lista blanca", así que el controlador descarta las conexiones de terceros.
  - Si el spike S10 muestra que el WROOM-32 no resuelve la dirección del reloj con el filtro activo, el plan B es verificar el bond en el host y desconectar al instante. En ese caso se documenta que un rival podría ocupar el slot.
- **Una sola conexión:** con el reloj conectado, el ESP32 no anuncia.
- **Tras una desconexión:**
  - `advertiseOnDisconnect(true)`, con intervalo de 20-50 ms durante 30 s y después 100-200 ms.
  - Al conectar, `updateConnParams(h, 24, 40, 0, 400)`: pide un intervalo de 30-50 ms y un *supervision timeout* de 4 s. Es una petición inofensiva; no se reenvía con temporizador para forzar los 4 s.
  - El valor real lo impone el reloj: AOSP fija ~5 s en cada `requestConnectionPriority`, dentro del rango de 4-6 s de la investigación. Se confirma en el GW7 con el TLV LINK, y queda registrado en el TLV LINK y en `info`.
- **Potencia de transmisión:** al máximo antes de anunciar, con `NimBLEDevice::setPower(9)`.
  - En NimBLE-Arduino 2.x el argumento va en dBm y el tipo por defecto (`All`) cubre anuncios y conexiones.
  - No pasar `ESP_PWR_LVL_P9`: es el valor 7 del enum y termina en +6 dBm.
  - `info` reporta `NimBLEDevice::getPower()` para confirmar que da 9.
  - **Por qué:** con el pouch en la pierna derecha y el reloj en la muñeca izquierda (la de apoyo de un diestro, §3.3), el cuerpo puede quedar entre los dos. El margen del enlace se mide en la prueba del 4-oct (§12).

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
- **Tareas (FreeRTOS):** los dos buses I2C aíslan los IMUs en lo eléctrico; estas tareas los aíslan en el software.
  - `imu_a_task` (Wire1, GPIO32/33) e `imu_b_task` (Wire, GPIO21/22): **una tarea por IMU**, fijadas al núcleo 1, con prioridad mayor que `loop()`. Cada una corre a 200 Hz con `vTaskDelayUntil` de 5 ms (la grilla de §4.2) y escribe en su propio buffer circular.
  - `Wire.setTimeOut(4)` y `Wire1.setTimeOut(4)`: entre 3 y 5 ms, nunca menos de 2× la transacción de 1,55 ms.
  - Tras **5 errores seguidos** se apaga el bit2/bit3 de `flags` de ese IMU. Desde ahí, una vez por segundo y dentro de la propia tarea del IMU, recupera el bus (`end()`/`begin()`, que emite 9 pulsos de SCL + STOP) y lo reinicializa. El bit vuelve tras 5 lecturas buenas.
  - Una lectura que falla se cuenta y, para las sumas acumuladas, se repite la lectura anterior (§4.2), así el N de la reconstrucción de huecos sigue exacto (§6.3).
  - `radar_rx` (una por UART), con `onReceive` o una tarea bloqueada en el UART, toma `millis()` al ver `AA FF 03 00`. La marca de tiempo nunca sale de un bucle que también toque el I2C.
  - El `bundler` corre en su propia tarea, con un tick de 100 ms.

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
  - **Cola GATT serial:** `discoverServices` → `requestMtu(517)` → **lectura de `info`** → escritura del CCCD.
    - La cola corre en cada (re)conexión, así que `info` se relee tras cada caída (§6.10, tipo 8). Su `boot_id` es el que usa el mapeo de relojes (§6.3).
    - La lectura de `info` sigue a `onMtuChanged`, o a 2 s sin ese callback; el CCCD se escribe cuando esa lectura termina.
    - **MTU insuficiente:** el ESP32 no notifica con un MTU menor que 247 (§4.2). Si el MTU final (de `onMtuChanged` o el campo MTU de `info`) es < 247, la app lo registra, muestra "MTU insuficiente" y reconecta una vez repitiendo `requestMtu(517)`. Si persiste, la sesión no arranca y la app lo explica; en una reconexión a mitad de partida, la pantalla queda en gris con "MTU insuficiente" y suena una vibración de sistema.
  - **Emparejamiento:** sin bond, la primera operación cifrada (la lectura de `info`, READ_ENC) devuelve INSUFFICIENT_AUTHENTICATION/ENCRYPTION. `BluetoothGatt` la reintenta sola con autenticación, y eso dispara el emparejamiento: el jugador teclea la clave de la etiqueta en el diálogo del sistema. No se llama a `createBond()`.
    - Esa operación de la cola no tiene timeout corto: espera la respuesta hasta 60 s, lo que dura la ventana.
    - Si termina en error, la app muestra "clave incorrecta o ventana cerrada" y ofrece reintentar.
  - **Bond perdido:** un dispositivo con bond puede conectar y no cifrar (borrado de 10 s, otro reloj, flash borrada; §4.3).
    - Señales: la lectura de `info` o la escritura del CCCD devuelven GATT 5, 15 o 137, o el enlace cae antes de escribir el CCCD.
    - Si pasa **2 veces seguidas**, se cancela la conexión pendiente (`close()`, sin más `connectGatt`), se detiene el escaneo de 30 s y se muestra "El cinturón olvidó este reloj: olvídalo en los ajustes Bluetooth del reloj y vuelve a emparejar", con la ruta exacta del menú que encuentre S10.
    - La app no puede borrar el bond ella misma: `removeBond()` no es API pública.
  - **Caché GATT:** la app implementa `onServiceChanged()` (API 31+): vuelve a correr `discoverServices`, obtiene de nuevo las características y reescribe el CCCD (§4.2).
  - **Prioridad de conexión:** `requestConnectionPriority(HIGH)` **solo durante el setup**. Después, **BALANCED** para toda la partida. LOW_POWER no se usa, porque suma hasta ~125 ms de latencia.
  - **Reconexión:**
    - Al recibir DISCONNECTED: `close()` e **inmediatamente** `connectGatt(dispositivo_bond, autoConnect=true, TRANSPORT_LE)`. Es una conexión pendiente sin timeout, sin espera progresiva, que se vuelve a pedir en cada caída.
    - Si pasan más de 10 s sin reconectar, se intenta una conexión directa (`autoConnect=false`, que Samsung corta a ~10 s) y se vuelve a la pendiente.
  - **Escaneo:** siempre con `ScanFilter` por el UUID del servicio. Solo se escanea si no hay bond, o si el dispositivo emparejado no responde en 30 s. Un limitador propio permite **≤ 4 `startScan` cada 30 s**, porque el 5.º falla en silencio.
  - **RSSI:** `readRemoteRssi()` a 1 Hz por la cola GATT durante la sesión; cada valor va al `.bsrec` (§6.10, tipo 10). Mide la dirección ESP32 → reloj, la que lleva `stream`.
- **`sensors`:**
  - `getDefaultSensor(TYPE_GRAVITY / TYPE_STEP_DETECTOR / TYPE_GYROSCOPE, true)` cuando exista la variante wake-up. Si no, se apoya en el wake lock.
  - `TYPE_GYROSCOPE` es el testigo de quietud para el sesgo de las cajas (§6.3) y da "girando" si fallan los dos IMUs (§6.6).
  - En el MVP, gravedad y giroscopio del reloj a **≤ 10 Hz**: la postura táctica no entra (§12) y así la grabación no crece (§6.10).
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
  - **Flanco de subida:** la primera alerta sale de inmediato. Si varios contactos se confirman en el mismo paquete, se elige uno con la prioridad centro > lados y, dentro de un lado, el más cercano; los demás quedan pendientes.
  - **≥ 1 s entre alertas, sin fusión.**
    - Todo contacto que se confirma después de una alerta queda **pendiente**.
    - Al cumplirse 1 s desde la última vibración de contacto, sale un disparo para el pendiente de mayor prioridad (misma regla) que siga confirmado, con su sector recalculado en ese momento con ψ(ahora) (§6.1, paso 8).
    - Los demás pendientes esperan al siguiente hueco de 1 s.
    - Un pendiente que deja de estar confirmado solo se muestra en pantalla.
    - Ningún contacto se descarta por llegar junto a otro. Esta acumulación de 1 s reemplaza a la ventana de agregación de ~300 ms de la investigación.
    - Las alertas de sistema salen de inmediato, no esperan el hueco de 1 s ni lo reinician; si coinciden con un disparo de contacto, este espera a que termine el patrón de sistema.
  - **Pausa de 5 s por sector** (rango a probar: 5-8 s): una pista nueva que nace a < 1,5 m de una pista del mismo sector perdida hace < 5 s, y que ya alertó, se trata como el mismo contacto re-adquirido: hereda el estado "ya alertó" y no vibra. Cualquier otra pista nueva vibra.
    - No es una excepción a D3: extiende la herencia de ID de §6.5 (< 1 m, < 2 s) a pérdidas más largas del mismo contacto.
  - **Tope de 10 alertas de contacto en cualquier ventana móvil de 60 s.**
    - Solo cuentan las vibraciones que salieron.
    - Las alertas de sistema no cuentan y nunca se callan.
    - Al llegar al tope, los pendientes pasan a solo pantalla y la pantalla muestra un indicador de saturación.
  - **[CAMBIO D3]: excepciones a "siempre vibra"** (la lista completa). Motivos: evitar la habituación y la distracción.
    - (a) el tope de 10 alertas de contacto en 60 s móviles;
    - (b) un pendiente que ya no está confirmado cuando le llega el turno: solo pantalla;
    - (c) ninguna otra: ningún contacto se fusiona ni se descarta por llegar junto a otro.
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
- **Postura:** automática, normal o táctica. Signo táctico: del cero, +90° o −90° (sin calibrar hasta que se haga el cero o se elija uno).
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
   - Las tramas de cada paquete se ordenan por t_ms y se procesan **apenas llega el paquete, sin buffer de espera**. El `bundler` garantiza el orden entre paquetes, también entre los fragmentos de un mismo corte (§4.2).
   - Una trama con t_ms menor que la última procesada (de cualquier radar) se descarta y se cuenta. El chequeo es global, porque el tracker que comparten los dos radares no acepta mediciones más viejas que su estado. Con el contrato del `bundler` (incluido el margen de 5 ms del corte) este contador debe quedar en 0: solo detecta violaciones.
6. **Kalman** (§6.2), con R inflada ×1,5 en la zona de solape.
7. **Gestión de pistas** (§6.5-6.6).
8. **Escena:** cada pista se predice hasta "ahora" y se rota con ψ(ahora) al marco lógico (§3.1).

**Presupuesto de latencia** (criterios 1 y 2):

| Término | Mín. | Típico | Máx. |
|---|---|---|---|
| Latencia interna del LD2450 | ? | ? | ? (se mide en S1/F5) |
| Espera hasta el corte del paquete (con el margen de 5 ms, §4.2) | 5 ms | 55 ms | 105 ms |
| Intervalo de conexión BLE (BALANCED) | 0 | 25 ms | 50 ms |
| Procesamiento en el reloj | ~0 | ~1 ms | ~5 ms |
| 3 de 5 ventanas desde el primer acierto | 200 ms | 200-300 ms | 400 ms |
| Regla anti-fantasmas 1, caminando (v1 completa) | — | +400-800 ms | simulado: p90 ≈ 1,0 s de frente y ≈ 1,2 s cruzando, desde el nacimiento de la pista; se fija en F5 |
| Limitador (flanco de subida) | 0 | 0 | 0 para la primera alerta |
| Limitador (hueco de 1 s), alertas retenidas | 0 | — | hasta ~1 s, +1 s por cada pendiente con más prioridad; se reportan aparte (criterio 2) |

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
  - **Testigo del reloj:** además se rechaza la ventana si, durante esos 2 s, el giroscopio del reloj (`TYPE_GYROSCOPE`, que Android ya entrega compensado de deriva) marca |ω_reloj| > 3 °/s. Las cajas no distinguen un giro lento y constante de un offset; el reloj sí.
  - Si el reloj no tiene giroscopio y hay un sesgo guardado para esa caja, se compara |ĝᵀ(media − b_guardado)|. Pasar de ~5 °/s no rechaza la ventana: solo la marca "sin verificar" y la UI pide quedarse quieto, porque la temperatura mueve el sesgo unos 3 °/s entre sesiones.
  - Sin giroscopio en el reloj y sin sesgo guardado, b₀ queda "sin verificar" y la UI pide quedarse quieto.
  - Si se rechaza, se reintenta con la siguiente ventana. Mientras tanto se usa el último sesgo aceptado (guardado en el reloj) y el IMU aparece como "calibrando".
- **Recalibración en reposo:** se dispara en una ventana ≥ 3 s quieta según los mismos criterios de dispersión del arranque, sin pasos y con el reloj quieto (|ω_reloj| < 3 °/s).
  - Si |ω − b| < 3 °/s, actualiza b con un promedio móvil exponencial de τ = 20 s.
  - Si 3 °/s ≤ |ω − b| < 45 °/s, el b vigente está mal (un giro absorbido al arrancar): se re-siembra b con la media de la ventana y se registra el evento. Así un b₀ errado no bloquea la recalibración.
  - Sin giroscopio en el reloj, la re-siembra solo se hace si además |ĝᵀ(media − b_guardado)| ≤ 5 °/s; si no, se registra y se marca "sin verificar". Sin ese testigo, un giro lento en reposo se confundiría con un sesgo nuevo.
- **Gravedad:** pasabajos con τ = 1 s, que se salta si la aceleración se aleja más de 0,15 g de 1 g.
- **Gravedad de referencia "de pie":** se toma en el arranque. Si la gravedad actual se aleja más de ~60° de ella, el estado pasa a **"tendido"** y se muestra "radar degradado".
- **Factor de escala:** ±3 % da ~2,7° de error cada 90° de giro. Se calibra con un asistente opcional de 5 vueltas.
- **Retraso τ entre radar e IMU:** 50 ms a 180 °/s ya son 9° de error.
  - **Valor por defecto del MVP:** la latencia medida en S1. Si S1 no da un número, 100 ms: el centro del barrido de 0-200 ms y coherente con "≥ una trama de 100 ms" de las notas del LD2450. Reemplaza al 0 de las notas (algoritmos §7).
  - Se ajusta minimizando el NIS durante los giros (barrido de 0-200 ms, notas: algoritmos §3), con la grabación del 4-oct en el MVP (§12) y con las de F5 después.
- **Base de tiempo del reloj:** **`SystemClock.elapsedRealtimeNanos()`**, la misma de `SensorEvent.timestamp`, tomada en el callback de llegada de cada notificación.
  - **Mapeo de relojes:** el mínimo móvil de (llegada − t_esp) en ventanas de 10 s, más una deriva lineal.
  - El mapeo se **reinicia en cada (re)conexión**, cuando cambia `boot_id` o si `t_ms` retrocede.
- **Paquetes perdidos:** con las sumas acumuladas (§4.1, §4.2), entre la última sección recibida y la nueva:
  - N = 4·round((t_última_nueva − t_última_anterior)/20 ms) y Δψ ≈ ĝᵀ·(ΔΣω − N·b)/(escala·200), con b en LSB y escala en LSB/(°/s) (de `info`).
  - Ese Δψ fija ψ(t_última_nueva). Las muestras recibidas de la sección nueva se integran hacia atrás desde ese punto y el resto se interpola en el hueco.
  - No hay que sumar Δψ y además integrar la sección nueva, porque ese giro se contaría dos veces.

### 6.4 Movimiento del jugador

- **Estado "girando":** |ω_v| > 20 °/s, filtrado a 0,2 s.
- **Estado "caminando":** desviación de |a| de las cajas > 0,08 g en 1 s, o un paso en los últimos 1,2 s.
  - El detector de pasos de las cajas es el principal. El del reloj confirma, pero tarda hasta 2 s.
- **Velocidad propia:** cadencia × largo de paso (fórmula de Weinberg, con una constante K calibrable), en la dirección de la cadera.
  - Su covarianza Σ_ego es **anisotrópica** (σ_fwd y σ_lat viven en `TuningParams`, §6.9): σ_fwd = 0,3 m/s; σ_lat = 0,4 m/s si la dirección de marcha confirma que se camina hacia adelante, y 0,9 m/s en otro caso, porque en airsoft se camina de costado y de espaldas.
- **Dirección de marcha:** PCA de la aceleración horizontal de los IMUs de las cajas (sin la gravedad) en los últimos 2 pasos.
  - Si el eje principal queda a ±30° del eje de la cadera y la razón de autovalores es ≥ 2, σ_lat = 0,4. La ambigüedad de 180° no importa, porque Σ es simétrica.
  - Si no, o sin datos, σ_lat = 0,9.
  - **Consecuencia:** con σ_lat = 0,9, un rival que cruza a paso de caminata nunca pasa la regla 1 (d² ≤ v²/σ_lat² = 2,4). En ese estado, los rivales que cruzan solo se confirman cuando el jugador se detiene.
- **Ego-velocidad por Doppler (Kellner):** opcional. Solo se usa con ≥ 3 detecciones consistentes y un residuo < 0,2 m/s.

### 6.5 Ciclo de vida de las pistas

**Ventanas** (corregido por la revisión):
- Todas las cuentas M-de-N de §6.5 y §6.6 se hacen por ventana de **100 ms en la grilla del reloj del ESP32**: [100k, 100(k+1)) ms, según el t_ms de cada trama.
- Para cada pista, una ventana es:
  - **acierto,** si algún radar le asoció una detección;
  - **fallo,** si algún radar cuyo cono cubre la predicción entregó en esa ventana una trama válida (cabecera y cola correctas, no descartada por t_ms) con al menos un slot vacío (0, 0, 0), y no hubo asociación. Solo un slot (0, 0, 0) cuenta como libre: los slots ocupados por objetivos rancios, descartados por plausibilidad, excluidos por campo cercano o por el mapa de ruido propio, o asociados a otra pista, cuentan como ocupados;
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
   - Una pista **nueva** se confirma solo si su **velocidad respecto del suelo** (estado del Kalman, donde un objeto quieto queda en ≈ 0) es distinguible de cero en 3 de las últimas 5 ventanas evaluables, contando solo desde la 3.ª actualización de la pista. Así el prior σ_v0 no bloquea la confirmación.
   - **Prueba principal:** d² = vᵀ(P_vv + Σ_ego)⁻¹v > umbral (en `TuningParams`, §6.9; arranca en χ²₂ al 90 % = 4,61). Σ_ego va rotada a la odometría; si la Q_ego ya está propagada en P, no se suma dos veces.
   - **Piso:** |v| > 0,6 m/s cuando el jugador gira sin caminar.
   - En cada ventana se usa la velocidad de la detección asociada (el promedio, si la ven los dos radares).
   - Las pistas ya confirmadas no se tocan.
   - En el marco del cuerpo sin compensar, esto equivale a |v_aparente − (−v_propia)| > umbral.
2. **Sombra (reflejo en una pared).**
   - Un blanco nuevo con **rango > rango de una pista existente (tentativa o confirmada) + 0,4 m**, a ±20° de ella y con |Δv_r| < 0,3 m/s, necesita **5 de 7 ventanas**. El rango y el rumbo se miden **desde el radar que lo detectó**.
   - Para v_r se usa el campo de velocidad del LD2450. Si S6 muestra que no es Doppler radial, se usa la proyección radial de la velocidad del Kalman respecto de ese radar.
   - Diferencia con las notas, que exigían una pista de referencia confirmada: aquí basta con que exista, para cubrir el fantasma que aparece a la vez que el blanco real.
3. **Consistencia entre radares.** Si el otro radar cubre el contacto (≥ 10° dentro de su cono) y durante 3 ventanas entregó tramas válidas con al menos un slot vacío (0, 0, 0) (§6.5) sin respaldarlo, la pista no se confirma.
   - **Limitación:** los reflejos de una pared caen en el mismo punto para los dos radares, así que esta regla no los elimina.
4. **Objetivos rancios y mapa de ruido propio** (§6.1).

**MVP de Manizales: "detenerse y escanear".** Es la única definición; §6.7, §8, §9 y §12 remiten aquí.
- Mientras el estado sea "caminando" o "girando" (§6.4) y durante 0,5 s después, ninguna pista tentativa pasa a confirmada, sin importar cuándo nació.
- Las tentativas se borran con la regla normal.
- Al terminar la cola de 0,5 s, una pista se confirma (y alerta por el limitador de §5.5) solo con 3 aciertos en ventanas evaluables posteriores a la cola.
- Las pistas confirmadas antes del movimiento conservan su ID y no vuelven a alertar; las que están en espera se re-asocian por GNN.
- En el MVP, esta compuerta reemplaza a las reglas anti-fantasmas 1-3. La regla 4 (objetivos rancios) y la exclusión de 0,8 m siguen.
- En la v1, tras el corte de F0 (§9), reemplaza solo a la regla 1; las reglas 2-4 siguen activas.
- Si fallan los dos IMUs, "caminando" sale del `TYPE_STEP_DETECTOR` del reloj (ventana de 1,2 s medida desde la llegada del evento) y "girando" del giroscopio del reloj (|ω| > 20 °/s, filtrado 0,2 s). El aviso dice "más fantasmas y alertas tardías al moverte".

### 6.7 Escena y alertas

- **`RadarScene`:** para cada contacto, ángulo en el **marco lógico**, distancia, confianza, antigüedad, ID y estado, más el estado del enlace, de los radares y de los IMUs.
- **Alertas:** un evento cuando una pista pasa a confirmada y su ID de pantalla todavía no alertó (§5.5). Su lado se calcula en el marco lógico, con el ψ(ahora) del momento en que vibra (§5.5).
  - En el MVP, "detenerse y escanear" (§6.6) no deja confirmar mientras el jugador camina o gira ni durante la cola de 0,5 s; el evento sale cuando la pista se confirma después de la cola.

### 6.8 Calibraciones

| Calibración | Quién | Cuándo |
|---|---|---|
| Signos de +X y velocidad por radar. El valor por defecto sale de S6 | asistente en la app, en banco: cinturón sobre una mesa, caminar hacia cada radar y luego pararse a su derecha | una vez por módulo o al cambiar cómo va montado |
| **Alineación relativa:** traslación medida con cinta; solo se estima el yaw relativo (1 grado de libertad; Umeyama completo solo como diagnóstico), con un ayudante caminando por el solape | asistente | al cambiar o reajustar el montaje |
| **Rumbo absoluto del par:** el jugador queda quieto con la cadera hacia una línea de cinta en el piso; el ayudante camina por esa línea a más de 0,8 m; se ajusta la dirección de la trayectoria. Se puede hacer en la misma sesión que la alineación | asistente | ídem |
| *Sin rumbo absoluto:* la bisectriz del par se supone en el **promedio de los ángulos nominales del perfil** (−10° en diestro), **nunca en el frente de la cadera (0°)**; la corrección relativa ±Δ/2 del paso de alineación relativa se reparte alrededor de ese valor. La app muestra "rumbo sin calibrar" | automático | — |
| **Mapa de ruido propio:** **2-3 min** solo; quieto moviendo brazos y réplica, caminando y girando en el sitio, en campo abierto y junto a una pared | asistente | antes de jugar, opcional |
| "Cero" de postura táctica, con el signo ±90° (§3.3) | asistente | una vez por jugador (no en el MVP) |
| Sesgo de los giroscopios | automática | al arrancar y en reposo |
| Factor de escala (5 vueltas) | asistente opcional | una vez |
| Retraso τ entre radar e IMU (por defecto: S1, o 100 ms; §6.3) | barrido de NIS sobre las grabaciones (en el MVP, la del 4-oct) | desarrollo |
| Constante K del largo de paso (caminar 20 m medidos) | asistente opcional | una vez por jugador |

### 6.9 Configuración

Todos los umbrales viven en **`TuningParams`**, una clase de datos inmutable. Sus valores por defecto vienen de las notas: algoritmos §7, y los del limitador de ideas_creativas_ux §3. Se puede cargar una variante desde un archivo para hacer barridos.

Excepciones a esos valores por defecto:
- **Regla 1 (v1 completa, §6.6):** el umbral de d² y Σ_ego (σ_fwd, σ_lat) son campos propios.
  - Valores de arranque: umbral χ²₂ al 90 % = 4,61; σ_fwd = 0,3 m/s; σ_lat = 0,4 m/s caminando hacia adelante y 0,9 m/s en otro caso (§6.4).
  - Los valores finales salen de estos escenarios, en el simulador y en las grabaciones, con el jugador caminando a 1,4 m/s: un rival de frente a 1,4 m/s a 2-5 m; un rival cruzando a 1,4 m/s a 2-5 m; una pared o un árbol, con 0 confirmaciones; y falsos contactos dentro del presupuesto del criterio 4 al caminar.
- **τ (radar → IMU):** en el MVP, la latencia medida en S1, o 100 ms si S1 no da un número (§6.3); no el 0 de las notas.

### 6.10 Grabación y reproducción (`.bsrec`)

- **Cabecera:** firma `BSREC`, versión, la primera lectura de `info` del cinturón, `TuningParams` y calibración (JSON), y la hora de inicio.
- **Registros:** `u8 tipo | u32 ms desde el inicio de la grabación (elapsedRealtime) | u16 largo | payload`. Tipos:
  1. paquete BLE crudo;
  2. **gravedad del reloj** (`TYPE_GRAVITY`): 3 × f32 + i64 con el timestamp del evento en ns, no el de escritura;
  3. paso del reloj;
  4. **marcador manual**, que el jugador dispara cuando ve a un rival real (ground truth barato);
  5. **pista confirmada**;
  6. **vibración iniciada**;
  7. cambio de modo: eliminado, Sigilo o Vista;
  8. **`info` releído** (bytes crudos), en cada (re)conexión (§5.2). Conserva `esp_reset_reason()` y el `boot_id` nuevo tras un reinicio a mitad de partida;
  9. **giroscopio del reloj** (`TYPE_GYROSCOPE`): 3 × f32 + i64 con el timestamp del evento en ns, para que la reproducción siga siendo determinista (§6.3);
  10. **RSSI** (i16 dBm), de `readRemoteRssi()` a 1 Hz (§5.2).

  Los tipos 5 y 6 hacen medible la latencia de extremo a extremo desde F0.
- **Tamaño:** cada corte de 100 ms ocupa 230 B (§4.2) más 7 B de cabecera de registro, y STATUS suma 12 B/s: ~2,4 kB/s, o sea **~17 MB cada 2 h** de paquetes crudos y **~43 MB para las 5 h de Manizales**.
  - La gravedad del reloj (tipo 2, ~27 B por registro) suma ~5 MB en 5 h a 10 Hz, o ~24 MB a 50 Hz. En el MVP basta ≤ 10 Hz, porque la postura táctica no entra (§12).
  - El giroscopio del reloj (tipo 9, ~27 B por registro) suma ~5 MB en 5 h a 10 Hz. Los tipos 8 y 10 son despreciables.
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
| Se corta el enlace BLE | Se detecta tras el *supervision timeout* (~5 s). La pantalla pasa a gris con "--" y queda una conexión pendiente (`autoConnect=true`) que reconecta en cuanto el ESP32 anuncia (§5.2). **Una sola** vibración de sistema |
| Un radar deja de emitir | El firmware le envía enable + reinicio y reintenta cada ~30 s. `flags` lo marca caído, la pantalla dibuja la cobertura que queda con un aviso y el tracking sigue con un solo radar. El corte de alimentación queda para la v1.5 |
| Tramas u objetivos corruptos | El ESP32 descarta por cabecera o cola y lo cuenta en STATUS. El reloj descarta por plausibilidad y lo cuenta. Si se supera un umbral, aparece un aviso |
| MTU sin negociar o paquete truncado | El ESP32 no notifica hasta MTU ≥ 247 (§4.2). El reloj lo detecta (`onMtuChanged` o el MTU de `info`), muestra "MTU insuficiente" y reconecta una vez repitiendo `requestMtu(517)`; si persiste, la sesión no arranca, o en plena partida la pantalla queda en gris con una vibración de sistema (§5.2). El ESP32 parte sin cortar secciones y solo descarta con `BLE_HS_ENOMEM`, marcando `flags.bit4`. El reloj descarta la sección truncada y lo cuenta |
| Huecos en la secuencia | Se cuentan en módulo 2¹⁶. El Kalman predice el hueco y el giro se **reconstruye** con las sumas acumuladas (§6.3) |
| Falla un IMU | Se usa el otro, con un aviso. En el ESP32, su tarea lo marca caído tras 5 errores y recupera el bus cada 1 s sin frenar a los radares ni al otro IMU (§4.4) |
| Fallan los dos IMUs | Sin compensación de giro, con el aviso "más fantasmas y alertas tardías al moverte". Se usa "detenerse y escanear" (§6.6), con "caminando" y "girando" tomados del reloj como dice esa sección |
| El reloj no tiene `TYPE_GRAVITY` | Pasabajos sobre el acelerómetro |
| El motor no soporta primitivas | Patrones de encendido y apagado |
| El sistema silencia la vibración (No molestar, teatro, dormir o intensidad baja) | El quiz obligatorio del modo práctica lo detecta antes de la partida y avisa |
| Permiso BLE denegado | La sesión no arranca y la app explica el motivo |
| `ACTIVITY_RECOGNITION` denegado | Sesión sin pasos del reloj; el mini estado lo indica |
| Jugador tendido | Aviso "radar degradado" |
| Batería baja del reloj | Sugiere Sigilo |
| Un rival intenta conectarse | Fuera de la ventana de emparejamiento, el controlador lo descarta (lista blanca por identity address). Con el reloj conectado, el ESP32 no anuncia. Dentro de la ventana podría conectarse, pero sin la clave no cifra ni puede leer `stream` ni `info`. **Con el plan B de S12** (Just Works en la ventana), quien se conecte en esos 60 s sí puede emparejarse: debilidad conocida (§4.3) |
| El cinturón perdió el bond (borrado de 10 s, otro reloj, flash borrada) | Tras 2 fallos de cifrado seguidos, la app cancela la conexión pendiente, deja de escanear y pide olvidar el cinturón en el reloj y volver a emparejar (§5.2) |

---

## 9. Pruebas y validación

### Pruebas unitarias de `radar-core` (JVM)

- **Decodificador:** tramas de ejemplo del manual (−782 mm / +1713 mm / −16 cm/s).
- **TLV:** tipos desconocidos, **`largo` fuera de rango o paquete truncado**, **vuelta de seq**.
- **Transformaciones y Kalman.**
- **GNN.**
- **Cada regla anti-fantasmas,** incluidos estos casos:
  - el jugador camina hacia una pared: **0 pistas confirmadas**;
  - (v1 completa) un rival camina de frente hacia el jugador mientras este avanza a la misma velocidad: **la pista se confirma**;
  - (v1 completa) un rival cruza de lado a 1,4 m/s mientras el jugador camina hacia adelante: **la pista se confirma**.
- **Postura:**
  - en táctica, con el antebrazo 20° hacia abajo, el signo y el lado de la vibración no cambian;
  - un ángulo cercano a 180° conserva la postura actual;
  - sin cero ni signo manual, una gravedad de táctica mantiene la postura normal.
- **Sesgo:**
  - una señal quieta con un offset de (8, −5, 3) °/s y balanceo de 1 °/s se acepta, y b₀ queda a menos de 0,1 °/s del offset;
  - un giro constante de 5 °/s en las cajas, con el reloj marcando el mismo giro, se rechaza;
  - sin giroscopio en el reloj y con sesgo guardado, la misma ventana queda "sin verificar";
  - sin giroscopio en el reloj ni sesgo guardado, b₀ queda "sin verificar";
  - sin giroscopio en el reloj, un giro lento de 10 °/s durante una ventana de reposo no re-siembra b: se registra y se marca "sin verificar";
  - si b₀ quedó 5 °/s errado, la primera ventana quieta de 3 s lo re-siembra y ψ deja de derivar;
  - un sesgo guardado 3 °/s distinto del real (temperatura) no impide calibrar;
  - la recalibración se dispara con el offset de 8 °/s.
- **Limitador:**
  - la primera alerta no se retrasa;
  - una persona que se pierde ~2 s y reaparece genera **una sola** alerta, y también si se pierde ~4 s y reaparece a < 1,5 m;
  - X alerta a la izquierda y se pierde; una persona Y nace a la izquierda a más de 1,5 m de X antes de 5 s: **Y vibra**;
  - una alerta de sistema en t = 0,5 s tras una de contacto en t = 0 sale de inmediato y no corre el hueco: el pendiente sale en t ≈ 1,0 s, o al terminar el patrón de sistema si este sigue sonando;
  - izquierda en t = 0 y centro en t = 0,25 s: vibra el centro en t ≈ 1,0 s;
  - izquierda en t = 0, centro en t = 0,25 s y derecha en t = 0,5 s: centro en ≈ 1,0 s y derecha en ≈ 2,0 s si sigue confirmada;
  - la 11.ª alerta dentro de 60 s móviles no vibra, y una alerta de sistema en ese momento sí vibra;
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

**MVP ("detenerse y escanear", §6.6).** El simulador inyecta 100 ms de latencia de radar con el τ del modelo errado en 100 ms, y hace aparecer ruido estático solo mientras el jugador gira:
- el jugador gira 90° en 0,5 s frente a una pared a 3 m: **0 alertas**;
- un caminante ya confirmado antes del giro: **0 alertas extra**;
- un caminante aparece en vista mientras el jugador gira: **exactamente 1 alerta**, ≤ 1,0 s después de que termina la cola de 0,5 s;
- un rival aparece mientras el jugador camina, y el jugador se detiene y queda quieto: **exactamente 1 alerta**.

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

- Entorno `native`: parser, tabla de STATUS y `bundler`, que parte sin cortar secciones y respeta `max_payload = min(getPeerMTU() − 3, 244)`. Casos del `bundler`:
  - MTU 255: un contenido de 249 B (cabecera 8, tres RADAR 93, IMU con n = 4 → 68 y n = 5 → 80) se parte, y ningún paquete pasa de 244 B;
  - MTU 185 (max_payload 182): sigue partiendo sin romper secciones (prueba de la función pura; el ESP32 no notifica con ese MTU);
  - max_payload = 244 con 3 tramas de radar e IMU con n = 7: dos paquetes, ninguno de más de 244 B;
  - margen del corte: con el corte en t = 100 ms, una trama a 94 ms entra y una a 96 ms queda para el corte siguiente;
  - orden al partir: 3 tramas de 2 radares desordenadas por radar (A@12, A@92, B@40) más 2 IMU con n = 6, con max_payload = 241 y con max_payload = 100. Concatenar las tramas RADAR de los fragmentos en orden de seq da t_ms no decreciente (0 descartes en el lector del reloj), y ninguna sección RADAR va antes que las secciones IMU del mismo corte.
- En banco, registro por el puerto serie.
- **En banco, cable de IMU en falla:** con los radares transmitiendo, mantener el SDA del IMU A a GND 10 s y soltarlo. Pasa si:
  - la distribución de Δt_ms entre tramas de radar no cambia más de ±2 ms frente a la corrida sana;
  - el IMU B hace ≥ 199 lecturas/s y mantiene n = 5 por paquete;
  - el bit2 cae en ≤ 100 ms mientras el bit3 sigue en 1;
  - el IMU A vuelve solo en ≤ 2 s tras soltar el SDA.

### Seguridad

- La reconexión sigue funcionando en ≤ 5 s después de que el reloj rota su dirección privada.
- Fuera de la ventana, un teléfono con nRF Connect no puede conectarse.
- Dentro de la ventana se conecta, pero falla al suscribirse a `stream` o al leer `info` (con S12 aprobado; con el plan B de S12, quien se conecta en la ventana sí se empareja: debilidad conocida de §4.3).
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
| S8 | **MVP:** una sola configuración, la que trae §5.2: BALANCED tras el setup, `PARTIAL_WAKE_LOCK` siempre y Sigilo con la pantalla apagada. Se mide en la prueba de 5 h del 8-oct, desde 100 %, registrando %/h, `esp_reset_reason`, el power bank (¿se apaga solo? ¿cae el VIN con los dos radares y el BLE?) y las reconexiones; el criterio 7 (≥ 6 h en Sigilo) se proyecta con esa tasa. Si Santiago va a usar Vista en Manizales, una medición corta en Vista (30-60 min al brillo que usará) el 9-oct; si no, la batería en Vista queda sin verificar en el MVP. **F5:** matriz BALANCED (HIGH como referencia) × {Sigilo, Vista a brillo mínimo, medio y máximo}, 30 min por combinación (8 corridas). LOW_POWER y "sin wake lock" no son configuraciones del producto (§5.2): solo datos de referencia si se quieren | Criterios 5 y 7 |
| S9 | Clasificador de postura con agarre tradicional, C-clamp y empuñadura vertical; apuntando, en low ready (bocacha 30-45° abajo) y con la réplica colgada | §3.2 (no en el MVP) |
| S10 | ¿El WROOM-32 con NimBLE 2.x resuelve la dirección privada del Galaxy Watch con el filtro de lista blanca activo? Además: ¿dónde se olvida en el GW7 (One UI 8 Watch) un bond BLE que no es de audio? La ruta exacta va al mensaje de bond perdido (§5.2); si el reloj no lo lista, se anota aquí y se busca otra vía | §4.3, o el plan B; el mensaje de §5.2 |
| S11 | ¿Puede la app recibir la pulsación del botón físico del Watch 7 (KEYCODE_STEM_*) con Sigilo activo? | Acceso al modo eliminado |
| S12 | Emparejamiento en el GW7: ¿muestra el teclado de clave cuando la primera operación cifrada (la lectura de `info`, §5.2) dispara el emparejamiento? ¿Funciona después una escritura en `control`? Eso prueba un bond autenticado (MITM) y no Just Works. ¿Vuelve a cifrar sin diálogo después de apagar y prender el Bluetooth o de que rote la dirección? | §4.3 y el MVP; si falla, plan B |

**Corte de F0 tras S7:** si la saturación al caminar supera **X %**, la v1 usa **"detenerse y escanear"** (§6.6) en lugar de la regla 1, aunque la velocidad propia esté lista; las reglas 2-3 siguen activas, y se redefinen los criterios 1 y 4 para el caso "caminando". El valor de X lo decide Santiago (Q8).

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
  - **Debilidad conocida si S12 falla:** el plan B (Just Works dentro de la ventana) deja que quien se conecte durante esos 60 s se empareje sin clave (§4.3).
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
- **Largo plazo, más allá del airsoft** (ideas aprobadas por Santiago como futuro lejano, 1-oct-2026):
  - Usan el mismo hardware:
    - modo centinela o trampa;
    - estadísticas de partida;
    - entrenamiento de reacción y CQB;
    - "alguien detrás de mí" al caminar;
    - análisis de marcha con los dos IMUs de la cadera;
    - presencia en casa con Home Assistant;
    - juegos y eventos;
    - apoyo a personas ciegas (solo como complemento del bastón).
  - Necesitan compra:
    - radar trasero para la moto integrado en RevScope (HLK-LD2451);
    - signos vitales con el Seeed MR60BHA2 a 60 GHz (1,5 m para signos vitales y 6 m para presencia).
- **Modo Halo** (rastreador de movimiento de 360° con compañeros en otro color; largo plazo). Detalle y fuentes en [Radares de largo alcance y salud](../../research/reports/Radares%20de%20largo%20alcance%20y%20salud.md):
  - **15 m:** 2× RFbeam K-LD7 (USD 70,95 c/u, 80°×34°). Sirve el mismo ESP32, pero cada uno sigue un solo objetivo.
  - **15-20 m con varios objetivos:** RFbeam V-LD3 (chip TI IWRL6432: 110°×50°, tracking en el chip, 6 g). Se prototipa primero con el IWRL6432BOOST.
  - **25-50 m al frente:** RFbeam K-MD7 (8 tracks, ±30°, USD 212,68).
  - **360° recomendado:** 4-6 V-LD3, o 4 IWRL6432WMOD, más 1 K-MD7 al frente. Necesita un ESP32-S3 o un RP2040 (más UART), sincronización TDM entre radares y compensación del movimiento propio.
  - **Salud:** todos emiten ≤ 20 dBm PIRE con la antena hacia afuera. A 1 m quedan ~1250 veces por debajo del límite ICNIRP.
  - **No sirven los de 77 GHz:** esa banda es solo para vehículos.

---

## 12. Fases y MVP para Manizales (11-oct-2026)

El plan detallado se escribe con la skill `writing-plans` cuando Santiago apruebe este documento. Quedan **10 días de desarrollo** (1-10 oct) y no se puede comprar nada (D17).

### Alcance del MVP

| Entra | Queda para la v1 completa |
|---|---|
| Firmware: arranque de los LD2450 (BT apagado), tramas crudas, 2 IMUs (una tarea FreeRTOS cada uno), `bundler` TLV que parte paquetes (con LINK), NimBLE con ventana de emparejamiento + clave por dispositivo (DisplayOnly) + lista blanca + TX a 9 dBm, watchdog por comando. Si S10 o S12 fallan, sale con su plan B (§4.3) | Plan B de S10 pulido |
| `radar-core`: decodificación con plausibilidad, geometría con ángulos nominales y medidos, exclusión 0,8 m, GNN + Kalman, ventanas M-de-N, espera según contexto, rotación compensada con los IMUs (τ por defecto de S1, o 100 ms), **"detenerse y escanear"** (§6.6: al caminar o girar, y 0,5 s después, ninguna pista se confirma; reemplaza a las reglas anti-fantasmas 1-3, y siguen la regla 4 y la exclusión de 0,8 m) | Velocidad propia completa (regla 1 con Σ_ego), reglas 2-3, Doppler, mapa de ruido propio |
| App: servicio + wake lock, BLE (bond, reconexión, bond perdido, RSSI), abanico en Vista, **Sigilo con vibración izq/centro/der**, limitador, **modo eliminado**, mini estado, **grabación `.bsrec`** completa, quiz de vibración, ajustes básicos (mano, ángulos, signos) | Postura táctica y "cero" (Santiago usa empuñadura vertical), asistentes de calibración, color, encender ante contacto, burn-in fino |
| Pruebas: unitarias del decodificador, TLV y tracker; escenarios básicos del simulador | Batería completa de escenarios y golden |

### Calendario propuesto

| Día | Trabajo |
|---|---|
| **1 oct (jue)** | Spikes S6 y S4 en banco. Imprimir las cajas (medir el ángulo con transportador primero). Cablear. Esqueleto del firmware |
| **2-3 oct** | Firmware completo + `radar-core` (decodificación, tracker) con pruebas. Spike S1 con una app mínima que ya usa el grabador real: `.bsrec` con cabecera mínima (BSREC, versión, `info`; `TuningParams` y calibración pueden ir vacíos) y registros de tipos 1 (paquete BLE crudo), 5 (pista confirmada), 6 (vibración iniciada) y 10 (RSSI). El tipo 1 basta para S7 el 4-oct; los tipos 5 y 6 son los que S1 necesita para medir la latencia de contacto a vibración. En banco, con esa app y el NimBLE del firmware, ~1 h cada uno: S3 (su resultado fija el USAGE de §5.2 antes del 5-7 oct), S10, S12 y la ventana de emparejamiento. Si S10 o S12 fallan, el MVP sale con su plan B (§4.3) |
| **4 oct (dom)** | Primera prueba en el patio con un amigo, **grabada**. S7 (saturación). Escenario para ajustar τ: el amigo camina despacio mientras Santiago gira a un lado y al otro. **Enlace:** con el reloj en la muñeca de Q7, ~5 min de cada postura (brazo colgando, de rodilla, tendido, caminando), anotando RSSI (tipo 10), huecos de seq y desconexiones. Si alguna postura causa desconexiones o huecos sostenidos, el pouch pasa a la cadera del lado del reloj (los cables de 50-80 cm alcanzan) y se repite |
| **5-7 oct** | App MVP: servicio, BLE, pantalla, vibración, grabación completa (tipos 2-4 y 7-9, cabecera completa), modo eliminado. Ajuste con las grabaciones: τ con el barrido de NIS (0-200 ms, notas: algoritmos §3) sobre la grabación del 4-oct, y se actualiza el valor por defecto |
| **8 oct** | **Prueba de resistencia de 5 h en casa** (parte MVP de S8: batería, reconexión, ningún crash) |
| **9 oct** | Correcciones. Si se puede, una partida de 1 h de prueba. Si Santiago va a usar Vista en Manizales, la medición corta de batería en Vista (S8). **Lista de recortes**, en orden, para lo que no funcione al cerrar el día: abanico de Vista, después el detalle del mini estado, después el pulido del quiz. Nunca se recortan: la vibración en Sigilo, el botón del modo eliminado, la grabación y el quiz de vibración básico |
| **10 oct** | Congelar. Cargar todo. Checklist: LEDs tapados, quiz de vibración, No molestar, cargador del reloj al power bank |
| **11 oct** | **Manizales:** jugar y **grabar las 5 h** |

### Fases de la v1 completa (después del 11-oct)

- **F4:** velocidad propia completa, reglas anti-fantasmas 1-3, asistentes, postura táctica y "cero".
- **F5:** campo con barridos de parámetros sobre las grabaciones de Manizales; matriz completa de S8; verificar los criterios de §1.
- **F6:** release con APK firmado, binario del firmware, guía de armado (incluidos tapar los LEDs, la tapa de BOOT/EN y el aviso de que borrar la flash cambia la clave) y archivos de las cajas.

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
2. **Q8:** ¿Qué umbral X de saturación al caminar (S7) aceptas antes de que la v1 completa pase a "detenerse y escanear" (el MVP ya lo usa)? Propuesta: 30 % de tramas saturadas.
3. **Q9:** ¿Apruebas estas excepciones a D3: el limitador de §5.5, con (a) el tope de 10 alertas de contacto en cualquier ventana móvil de 60 s y (b) un pendiente que ya no está confirmado cuando le llega el turno, que solo se muestra en pantalla (ninguna otra: ningún contacto se fusiona ni se descarta por llegar junto a otro); y el modo eliminado sin vibración (§10.1)?
4. **Q10:** ¿Te parece bien que en Sigilo la pantalla **no** se encienda sola por defecto (§5.4)?
5. **Q11:** ¿Apruebas el alcance y el calendario del MVP (§12)? Es lo que destraba escribir el plan e implementar.
