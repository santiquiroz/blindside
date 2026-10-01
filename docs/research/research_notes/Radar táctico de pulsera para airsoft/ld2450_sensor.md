# HLK-LD2450 (radar 24 GHz) + ESP32: dossier técnico para Blindside (montaje en cinturón, relay BLE, tracking en el reloj)

Investigación cerrada el 2026-09-30. Cómo leer las etiquetas de confianza: **[PRIM]** = verificado textualmente en un documento primario (PDF oficial de Hi-Link extraído como texto, documentación de Espressif o código fuente leído). **[CÓDIGO]** = verificado en un parser que funciona (ESPHome, rama `dev`). **[COMUNIDAD]** = reporte de usuarios sin verificación independiente. **[RETAIL/SNIPPET]** = ficha de tienda o resumen de buscador, confianza baja. Todo lo que está bajo "Inferences" es razonamiento propio y no está verificado.

Documentos primarios de Hi-Link usados (se extrajo el texto completo con pypdf):
- *HLK-LD2450 Serial Communication Protocol V1.03* (2023-10-17), 14 páginas: [HLK Protocolo V1.03](https://make.net.za/wp-content/datasheets/HLK%20LD2450%20Serial%20Communication%20Protocol%20v1.03.pdf)
- *HLK-LD2450 Instruction manual V1.00* (2023-05-10), 22 páginas: [HLK Manual V1.00](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf)
- *HLK-LD2450 User Guide* (servidor de descargas de HLK; los metadatos del PDF indican que se generó con WPS el 2024-12-20): [HLK User Guide](https://d.hlktech.net/download/HLK-LD2450/1/HLK-LD2450%20operation%20manual.doc..pdf)

---

## 1. Formato de trama UART, codificación de X/Y/velocidad/resolución (verificación del signo), tasa de refresco y latencia

### Takeaway
La trama de datos mide siempre 30 bytes en little-endian: cabecera `AA FF 03 00`, luego 3 bloques de 8 bytes por objetivo (X, Y, velocidad, resolución) y cola `55 CC`. No trae checksum y llega a 10 Hz. **Queda CONFIRMADO** lo que se suele decir del signo: X, Y y velocidad usan signo-magnitud "invertido". Bit 15 = 1 significa positivo, bit 15 = 0 significa negativo, y los 15 bits bajos son la magnitud. **No** es complemento a dos. Unidades: X/Y en mm, velocidad en cm/s, resolución en mm (uint16, prácticamente constante). Lo confirman el protocolo oficial, el manual, los ejemplos de la guía de usuario (que tiene errores en los rótulos) y dos parsers de ESPHome. Hi-Link no documenta la dirección física de +X ni si la velocidad positiva significa alejarse o acercarse. Los parsers funcionales toman velocidad negativa = acercándose.

### Cited Findings

**Parámetros serie**
- [PRIM] "The default baud rate of the serial port is 256000, 1 stop bit, no parity bit". Comunicación "through a serial port (TTL level)" — [HLK Protocolo V1.03](https://make.net.za/wp-content/datasheets/HLK%20LD2450%20Serial%20Communication%20Protocol%20v1.03.pdf) §1.2.1 y §2
- [PRIM] "The LD2450's serial data communication uses the little-end format, and all data in the following tables are in hexadecimal." — [HLK Protocolo V1.03](https://make.net.za/wp-content/datasheets/HLK%20LD2450%20Serial%20Communication%20Protocol%20v1.03.pdf) §2.1.1
- [PRIM] ESPHome exige "parity and stop_bits **must be** respectively `NONE` and `1`" y recomienda UART por hardware: "Hardware UART highly recommended for proper support of default 256000 baud rate" — [ESPHome docs LD2450](https://esphome.io/components/sensor/ld2450/)

**Estructura de la trama de datos (Table 9 y Table 10 del protocolo)**
- [PRIM] Cabecera `AA FF 03 00`, cuerpo "Goal 1 information Goal 2 information Goal 3 information", cola `55 CC`. "The data format reported by the radar is shown in the table below, 10 frames per second." — [HLK Protocolo V1.03](https://make.net.za/wp-content/datasheets/HLK%20LD2450%20Serial%20Communication%20Protocol%20v1.03.pdf) §2.3
- [PRIM] Campos por objetivo, textuales de la Table 10:
  - Target X: "signed int16 type, the highest bit 1 corresponds to positive coordinates, 0 corresponds to negative coordinates, unit: mm"
  - Target Y: igual que X ("...unit: mm")
  - Target speed: "signed int16 type, the highest bit 1 corresponds to the positive speed, 0 corresponds to the negative speed, and the other 15 bits correspond to the speed in cm/s."
  - Distance resolution: "uint16 type, individual distance gate size in mm"
  — [HLK Protocolo V1.03](https://make.net.za/wp-content/datasheets/HLK%20LD2450%20Serial%20Communication%20Protocol%20v1.03.pdf) §2.3; el mismo texto aparece en [HLK Manual V1.00](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf) §6
- [CÓDIGO] ESPHome usa los offsets `TARGET_X = 4, TARGET_Y = 6, TARGET_SPEED = 8, TARGET_RESOLUTION = 10` con `start = OFFSET + index * 8` para los objetivos 0, 1 y 2. Cabecera de datos `{0xAA, 0xFF, 0x03, 0x00}` y cola `{0x55, 0xCC}` — [ESPHome ld2450.cpp](https://raw.githubusercontent.com/esphome/esphome/dev/esphome/components/ld2450/ld2450.cpp)

**Verificación del signo con ejemplos oficiales**
- [PRIM] Ejemplo oficial: `AA FF 03 00 0E 03 B1 86 10 00 40 01 00 00 ... 00 55 CC`. Decodificación textual del documento:
  "Target1 X coordinate: 0x0E+0x03*256=782 → 0-782=-782mm"
  "Target1 Y coordinate: 0xB1+0x86*256=34481 → 34481-2^15=1713mm"
  "Target1 speed: 0x10+0x00*256=16 → 0-16=-16cm/s"
  "Target1 distance resolution: 0x40+0x01*256=320mm"
  Los objetivos 2 y 3 "do not exist, so their corresponding data segment is 0x00" — [HLK Protocolo V1.03](https://make.net.za/wp-content/datasheets/HLK%20LD2450%20Serial%20Communication%20Protocol%20v1.03.pdf) §2.3; idéntico en [HLK Manual V1.00](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf) §6
- [PRIM] Sección "2450 coordinate calculation method" de la guía de usuario: "Note: The data is low first, high last. X, Y coordinate calculation method: When X is a positive coordinate, the calculation method is X-2^15. When X is a negative coordinate, the calculation method is 0-X. Y is always a positive coordinate, calculated as Y-2^15." Trae tres capturas reales:
  - `10 01 52 83 00 00 68 01` → X = 0-272 = **-272 mm**, Y = 33618-32768 = **850 mm**, resolución 0x0168 = **360 mm**
  - `E4 00 87 83 11 80 68 01` → X = **-228 mm**, Y = 33671-32768 = **903 mm**, velocidad 32785-32768 = **+17 cm/s**
  - `BE 8A 47 8E 11 00 68 01` → X = 35518-32768 = **+2750 mm**, Y = 36453-32768 = **3655 mm**, velocidad 0-17 = **-17 cm/s**
  — [HLK User Guide](https://d.hlktech.net/download/HLK-LD2450/1/HLK-LD2450%20operation%20manual.doc..pdf) §6
- [PRIM] **La guía de usuario tiene errores de rótulo que contradicen su propia aritmética.** Para la velocidad `11 80` (0x8011, bit 15 = 1) escribe "Speed, the highest bit is 0, positive speed". Para `11 00` (bit 15 = 0) escribe "positive speed" pero calcula "0-17=-17 cm/s". Además tiene la errata "82+131*256=33671" (debería ser 135+131·256). La aritmética es coherente con bit15 = 1 → positivo y los rótulos no — [HLK User Guide](https://d.hlktech.net/download/HLK-LD2450/1/HLK-LD2450%20operation%20manual.doc..pdf) §6
- [CÓDIGO] Decodificador de ESPHome (textual):
  ```cpp
  static inline int16_t decode_coordinate(uint8_t low_byte, uint8_t high_byte) {
    int16_t coordinate = (high_byte & 0x7F) << 8 | low_byte;
    if ((high_byte & 0x80) == 0) { coordinate = -coordinate; }
    return coordinate;  // mm
  }
  static inline int16_t decode_speed(uint8_t low_byte, uint8_t high_byte) {
    int16_t speed = (high_byte & 0x7F) << 8 | low_byte;
    if ((high_byte & 0x80) == 0) { speed = -speed; }
    return speed * 10;  // mm/s
  }
  ```
  La resolución se lee como uint16 LE sin signo — [ESPHome ld2450.cpp](https://raw.githubusercontent.com/esphome/esphome/dev/esphome/components/ld2450/ld2450.cpp)
- [CÓDIGO] El componente RD-03D de ESPHome (radar de Ai-Thinker) usa la misma cabecera `AA FF 03 00`, la misma cola `55 CC`, 8 bytes por objetivo y el mismo decodificador: `int16_t value = ((high_byte & 0x7F) << 8) | low_byte; if ((high_byte & 0x80) == 0) { value = -value; }`. Es una confirmación independiente del esquema de signo — [ESPHome rd03d.cpp](https://raw.githubusercontent.com/esphome/esphome/dev/esphome/components/rd03d/rd03d.cpp)
- [COMUNIDAD] Síntoma típico de decodificar mal. El usuario cyben76 (foro de Home Assistant, 2023-06-24) escribió: "output seems to be inaccurate, keep showing measurements above 3,000m even when I'm just in front of it, also the target resolution does change always 360mm" (sic) — [HA "Initial experiments"](https://community.home-assistant.io/t/hlk-ld2450-initial-experiments-to-connect-to-homeassistant/578878)
- [PRIM] Campo de resolución. FAQ 4.3 de la guía: "Why is this data always 6801? ... This is the distance resolution, which indicates the size of a single distance gate. It is similar to accuracy and is fixed. In practical applications, this parameter is not required. You only need to parse the X, Y coordinate data and speed." (`68 01` = 0x0168 = 360 mm) — [HLK User Guide](https://d.hlktech.net/download/HLK-LD2450/1/HLK-LD2450%20operation%20manual.doc..pdf) §4.3

**Sentido físico de ejes y velocidad (no lo documenta Hi-Link)**
- [CÓDIGO] ESPHome asigna la dirección así: `if (ts > 0) direction = DIRECTION_MOVING_AWAY; else if (ts < 0) direction = DIRECTION_APPROACHING; else DIRECTION_STATIONARY;`. Es decir, **velocidad positiva = alejándose y negativa = acercándose** — [ESPHome ld2450.cpp](https://raw.githubusercontent.com/esphome/esphome/dev/esphome/components/ld2450/ld2450.cpp)
- [PRIM] La documentación de ESPHome para RD-03D (mismo protocolo): "Positive values indicate the target is moving away from the sensor, negative values indicate the target is approaching." — [ESPHome docs RD-03D](https://esphome.io/components/sensor/rd03d/)
- [COMUNIDAD] ShillehTek (tutorial ESP32 + LD2450) también da "negative = approaching" — [ShillehTek](https://shillehtek.com/blogs/news/esp32-ld2450-live-web-radar-tracking). **Lo contradice** componentindex.net, que según el snippet del buscador (página no leída) dice que positivo = acercándose — [componentindex](https://componentindex.net/components/ld2450/)
- [PRIM] ESPHome sobre el eje X: "negative for left side of the sensor, positive for right side". Y va de 0 a 6000 mm y la documentación da X entre -3000 y 3000 mm — [ESPHome docs LD2450](https://esphome.io/components/sensor/ld2450/)
- [CÓDIGO] ESPHome calcula distancia = `sqrtf(x² + y²)` y ángulo = `atan2f(-tx, ty)` en grados. O sea, **el signo del ángulo de ESPHome es el opuesto al signo de X**. Un objetivo cuenta como presente si `td > 0` (distancia > 0) — [ESPHome ld2450.cpp](https://raw.githubusercontent.com/esphome/esphome/dev/esphome/components/ld2450/ld2450.cpp)
- [COMUNIDAD] El componente externo de TillFleisch define "The y-axis is referred to as the axis pointing straight away from the sensor. The x-axis is located perpendicularly." y trae la opción `flip_x_axis` — [TillFleisch/ESPHome-HLK-LD2450](https://github.com/TillFleisch/ESPHome-HLK-LD2450). ShillehTek aconseja: "If left and right look mirrored for your mounting, negate `t[i].x`" — [ShillehTek](https://shillehtek.com/blogs/news/esp32-ld2450-live-web-radar-tracking)

**Tasa de refresco y latencia**
- [PRIM] "10 frames per second" — [HLK Protocolo V1.03](https://make.net.za/wp-content/datasheets/HLK%20LD2450%20Serial%20Communication%20Protocol%20v1.03.pdf) §2.3. "Data refresh rate 10Hz" (Table 4) — [HLK Manual V1.00](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf) §8
- [PRIM] ESPHome aplica filtros por defecto: los sensores tienen "1s timeout with throttle_with_priority at 1000ms" y los binary sensors "1000ms settle time". **Por defecto ESPHome publica a ~1 Hz, no a 10 Hz** — [ESPHome docs LD2450](https://esphome.io/components/sensor/ld2450/)

### Inferences
- **Decodificador canónico** para portar al reloj (Kotlin/Java) o al ESP32: `raw = lo | (hi << 8); mag = raw & 0x7FFF; val = (raw & 0x8000) ? mag : -mag;`. Aplica igual a X, Y y velocidad. Si se interpreta como complemento a dos, `0x86B1` sale -31055 mm; como uint16 sale 34481 mm (unos 34 m). Eso explica los reportes de "measurements above 3,000m".
- La trama no tiene longitud ni checksum. La validación depende de: (a) cabecera en el offset 0 y cola en el offset 28; (b) plausibilidad física: todo objetivo presente tiene bit 15 de Y = 1 (Y ≥ 0), |X| ≤ Y·tan(60°) con margen, |v| razonable y la resolución constante del módulo (320 o 360 mm según firmware y unidad). Un objetivo presente con Y negativo casi seguro indica desincronización o corrupción.
- Objetivo ausente = 8 bytes a cero. Test de presencia robusto: `resolución != 0` o `(X, Y) != (0, 0)`. El 0 tiene dos codificaciones (0x0000 = "-0" y 0x8000 = "+0").
- Coste en el cable: 30 bytes × 10 bits / 256000 baud ≈ **1,17 ms por trama**. A 10 Hz son **300 B/s por radar**, cerca del 1,2 % de la capacidad de la UART. Los dos radares suman 600 B/s, carga irrelevante.
- Para el sello de tiempo en el ESP32 conviene marcar la llegada de la cola `55 CC` (o el evento de RX-timeout, ver §10) y restar unos 1,2 ms para aproximar el inicio de la trama. La latencia real del sensor (movimiento → trama) incluye al menos un periodo de 100 ms más el filtrado interno del tracker, que no está documentado.
- Hay mayoría (código ESPHome, docs de ESPHome RD-03D y ShillehTek) para "velocidad negativa = acercándose". Adoptarlo como hipótesis de trabajo y **verificarlo en banco** caminando hacia el radar.
- La "speed" casi seguro es velocidad **radial** (Doppler). Un FMCW 1T2R mide Doppler radial. Hi-Link no lo aclara. Prueba: caminar en arco a distancia constante; si es radial, debería dar ≈ 0.
- El sentido físico de +X hay que calibrarlo en banco: ponerse a la derecha del radar, mirando en la dirección de su boresight, y registrar el signo de X. Luego fijar un `flip_x` por unidad en la configuración del tracker. ESPHome invierte el signo del ángulo respecto de X (`atan2(-x, y)`), así que no conviene copiar su ángulo sin verificarlo.

### Gaps
- Hi-Link no define por escrito hacia dónde apunta +X ni qué significa físicamente la velocidad positiva. La "Figure 6 Radar wall mounting angle identification" del manual es una imagen y no se pudo extraer.
- No se encontró ninguna medición publicada de la latencia de extremo a extremo ni del jitter entre tramas. Solo está el dato nominal de 10 Hz.
- No está documentado si la resolución (320 frente a 360 mm en los ejemplos) depende del firmware.
- El significado de los bytes `03 00` de la cabecera no está documentado. Se tratan como constantes.

---

## 2. Protocolo de configuración (modo config, single/multi-target, zonas, Bluetooth, baudios, reinicio/reset, versión de firmware) y qué se guarda

### Takeaway
Los comandos van enmarcados como `FD FC FB FA` + longitud (2 bytes LE) + palabra de comando (2 bytes LE) + valor + `04 03 02 01`. El ACK devuelve `comando | 0x0100` más un estado de 2 bytes (0 = éxito, 1 = fallo). Todo comando tiene que ir entre **Enable configuration (0x00FF, valor 0x0001)** y **End configuration (0x00FE)**. Mientras el módulo está en modo config deja de reportar datos. El protocolo V1.03 solo tiene 11 comandos más enable/end, y ninguno toca sensibilidad, canal ni temporización del chirp. Se guardan tras apagar: baud rate y Bluetooth (se aplican al reiniciar) y las zonas (se aplican al instante). El protocolo no dice si se guarda el modo de tracking. Las zonas usan **complemento a dos**, no el signo-magnitud de la trama de datos.

### Cited Findings

**Enmarcado**
- [PRIM] Envío: `FD FC FB FA | In-frame data length (2 bytes) | In-frame data | 04 03 02 01`. In-frame data = "Command word (2 bytes) | Command value (N bytes)". ACK: mismo enmarcado, con in-frame = "Send Command Word | 0x0100 (2 bytes) | Return value (N bytes)" — [HLK Protocolo V1.03](https://make.net.za/wp-content/datasheets/HLK%20LD2450%20Serial%20Communication%20Protocol%20v1.03.pdf) Tables 2-5
- [PRIM] "Any other orders issued to the radar must be issued after this order or they are invalid." (Enable configuration). Y también: "before sending any other commands to the radar, the developer needs to send the 'Enable Configuration' command first, and then send the configuration command within the specified time." Si no hay ACK, "it means that the radar fails to execute the configuration command" — [HLK Protocolo V1.03](https://make.net.za/wp-content/datasheets/HLK%20LD2450%20Serial%20Communication%20Protocol%20v1.03.pdf) §2.2.1 y §2.4
- [PRIM] "Normally, data reporting stops after the enable configuration command is replied." — [HLK User Guide](https://d.hlktech.net/download/HLK-LD2450/1/HLK-LD2450%20operation%20manual.doc..pdf) §2.3

**Tabla de comandos (bytes textuales del protocolo V1.03)** — [HLK Protocolo V1.03](https://make.net.za/wp-content/datasheets/HLK%20LD2450%20Serial%20Communication%20Protocol%20v1.03.pdf) §2.2.1-§2.2.13

| Comando | Palabra | Envío (hex) | ACK de éxito / retorno |
|---|---|---|---|
| Enable configuration | 0x00FF, valor 0x0001 | `FD FC FB FA 04 00 FF 00 01 00 04 03 02 01` | `FD FC FB FA 08 00 FF 01 00 00 01 00 40 00 04 03 02 01`: estado + "2 bytes protocol version (0x0001)" + "2 bytes buffer size (0x0040)" |
| End configuration | 0x00FE | `FD FC FB FA 02 00 FE 00 04 03 02 01` | `... 04 00 FE 01 00 00 ...` ("the radar restores the working mode after execution") |
| Single target tracking | 0x0080 | `FD FC FB FA 02 00 80 00 04 03 02 01` | `... 04 00 80 01 00 00 ...` |
| Multi target tracking | 0x0090 | `FD FC FB FA 02 00 90 00 04 03 02 01` | `... 04 00 90 01 00 00 ...` |
| Query target tracking mode | 0x0091 | `FD FC FB FA 02 00 91 00 04 03 02 01` | `... 06 00 91 01 00 00 01 00 ...` = single / `... 02 00 ...` = multi ("the default value is multi-target tracking") |
| Read firmware version | 0x00A0 | `FD FC FB FA 02 00 A0 00 04 03 02 01` | `FD FC FB FA 0C 00 A0 01 00 00 00 00 02 01 16 24 06 22 04 03 02 01` → "V1.02.22062416" (tipo 0x0000 + versión mayor de 2 bytes + menor de 4 bytes) |
| Set serial baud rate | 0x00A1 + índice de 2 bytes | `FD FC FB FA 04 00 A1 00 07 00 04 03 02 01` | Índices: 0x0001=9600, 0x0002=19200, 0x0003=38400, 0x0004=57600, 0x0005=115200, 0x0006=230400, **0x0007=256000 (default)**, 0x0008=460800 |
| Restore factory setting | 0x00A2 | `FD FC FB FA 02 00 A2 00 04 03 02 01` | Toma efecto "after rebooting the module" |
| Restart module | 0x00A3 | `FD FC FB FA 02 00 A3 00 04 03 02 01` | "will automatically reboot after the answer is sent" |
| Bluetooth setup | 0x00A4 | `FD FC FB FA 04 00 A4 00 01 00 04 03 02 01` = encender (`00 00` = apagar) | "the Bluetooth function of the module is on by default" |
| Get MAC address | 0x00A5, valor 0x0001 | `FD FC FB FA 04 00 A5 00 01 00 04 03 02 01` | `FD FC FB FA 0A 00 A5 01 00 00 8F 27 2E B8 0F 65 04 03 02 01` → "8F272EB80F65" |
| Query zone filtering | 0x00C1 | `FD FC FB FA 02 00 C1 00 04 03 02 01` | Estado + 2 bytes de tipo + 24 bytes de coordenadas |
| Set zone filtering | 0x00C2 + 26 bytes | `FD FC FB FA 1C 00 C2 00 02 00 E8 03 E8 03 18 FC 88 13` + 16 bytes `00` + `04 03 02 01` | `... 04 00 C2 01 00 00 ...` |

**Persistencia y valores de fábrica**
- [PRIM] Baud rate: "the configured value is not lost when power down, and the configured value takes effect after restarting the module." — §2.2.7
- [PRIM] Bluetooth: "The configured value is not lost when power down, and the configured value takes effect after restarting the module." — §2.2.10
- [PRIM] Zonas: "the configuration value will not be lost when power down, and it takes effect immediately after setup." — §2.2.13
- [PRIM] Factory reset: "restore all configuration values to [factory] values, and the configuration values take effect after rebooting the module". Table 7 de valores de fábrica: "Serial port baud rate 256000 / Bluetooth switch Turn on / Tracking mode Multi-target tracking / Area filtering function Turn off" — §2.2.8
- [PRIM] En single/multi target (§2.2.3-2.2.5) el protocolo **no dice** si el modo se guarda — [HLK Protocolo V1.03](https://make.net.za/wp-content/datasheets/HLK%20LD2450%20Serial%20Communication%20Protocol%20v1.03.pdf)
- [PRIM] ESPHome sobre el switch multi-target: "initial state set based on the corresponding setting as read from LD2450 module at boot". Sobre el cambio de baudios: "Once changed, all sensors will stop working until a fresh install with an updated UART Component configuration" — [ESPHome docs LD2450](https://esphome.io/components/sensor/ld2450/)

**Zonas (Table 8)**
- [PRIM] Tipo: "0 Disable region filtering / 1 Detect only the set region / 2 Do not detect the set area". Son 3 regiones rectangulares definidas por "the diagonal two vertices", "the format of the coordinate values is of type signed int16 in mm; All coordinate values of 0 means this area is not used." Ejemplo `E8 03 E8 03 18 FC 88 13` = vértices (1000, 1000) y (-1000, 5000) — [HLK Protocolo V1.03](https://make.net.za/wp-content/datasheets/HLK%20LD2450%20Serial%20Communication%20Protocol%20v1.03.pdf) §2.2.12-§2.2.13
- [PRIM] "the area detection and area filtering functions cannot be set to take effect at the same time." — [HLK User Guide](https://d.hlktech.net/download/HLK-LD2450/1/HLK-LD2450%20operation%20manual.doc..pdf) §3.2(6)
- [CÓDIGO] ESPHome: `ZONE_DISABLED = 0, ZONE_DETECTION = 1, ZONE_FILTER = 2`. Las coordenadas se leen con `hex_to_signed_int` en mm, sin factor de escala. Hay como máximo 3 zonas — [ESPHome ld2450.cpp](https://raw.githubusercontent.com/esphome/esphome/dev/esphome/components/ld2450/ld2450.cpp), [ESPHome docs LD2450](https://esphome.io/components/sensor/ld2450/)

**Inconsistencias en la documentación oficial**
- [PRIM] MAC. El texto dice "1 byte fixed type (0x00) + 3 bytes MAC address (big end sequence)", pero el ejemplo devuelve 6 bytes (`8F 27 2E B8 0F 65`, longitud 0x0A) y no aparece el byte de tipo — [HLK Protocolo V1.03](https://make.net.za/wp-content/datasheets/HLK%20LD2450%20Serial%20Communication%20Protocol%20v1.03.pdf) §2.2.11
- [PRIM] Bluetooth. El protocolo dice "Command value: 0x0100 turn on bluetooth 0x0000 turn off bluetooth" (escrito en orden de bytes: `01 00` = encender) y lo ilustra con `A4 00 01 00` "Indicates Bluetooth is turned on". **La FAQ 4.5 de la guía de usuario invierte los rótulos**: bajo "(1) Turn on Bluetooth" el paso 2 se llama "Turn off Bluetooth" y envía `A4 00 00 00`; bajo "(2) Turn off Bluetooth" envía `A4 00 01 00`. En ambos casos el tercer paso es `Restart module` — [HLK User Guide](https://d.hlktech.net/download/HLK-LD2450/1/HLK-LD2450%20operation%20manual.doc..pdf) §4.5
- [CÓDIGO] ESPHome envía `{0xA4, enable ? 0x01 : 0x00, 0x00}`, que coincide con el protocolo (`01 00` = encender). También define `NO_MAC[] = {0x08, 0x05, 0x04, 0x03, 0x02, 0x01}` (parece un centinela de "sin MAC") y mete un `delay(50)` fijo después de ciertos comandos — [ESPHome ld2450.cpp](https://raw.githubusercontent.com/esphome/esphome/dev/esphome/components/ld2450/ld2450.cpp)

**Historial del documento**
- [PRIM] "2023-8-2 1.01 Initial version / 2023-9-7 1.02 Configuration commands related to adding zone filtering / 2023-10-17 1.03 Add tracking mode query command. Optimize default value description" — [HLK Protocolo V1.03](https://make.net.za/wp-content/datasheets/HLK%20LD2450%20Serial%20Communication%20Protocol%20v1.03.pdf) §3. También circula una copia de la V1.02 (no leída): [super.so V1.02](https://assets.super.so/79c0d2a8-d37a-438f-8fbe-c44778f3b0dd/files/ee2aafb0-f93e-47cd-b722-ad0f9e76b6f7/LD2450_Serial_Communication_Protocol_V1.02_EN.pdf)

### Inferences
- Secuencia de arranque sugerida para Blindside. La configuración conviene hacerla una sola vez en el banco, porque casi todo persiste:
  1. `FF` (enable)
  2. `A0` para registrar la versión de firmware
  3. `91` para leer el modo y, si hace falta, `90` para pasar a multi
  4. `C1` para leer zonas y `C2` para escribirlas
  5. `A4 00 00` para apagar el BT del módulo
  6. `A3` (restart) para que se apliquen BT y baud (el módulo responde el ACK, reinicia y reanuda el reporte)
  En partida no se debe reconfigurar, porque el modo config corta el flujo de datos.
- Hay dos codecs distintos: la trama de datos usa signo-magnitud (bit 15 = 1 → positivo) y la configuración de zonas usa **complemento a dos** LE (0xFC18 = -1000). Si se mezclan, las zonas quedan espejadas o corruptas.
- El `buffer size 0x0040` del ACK de enable sugiere que ninguna trama de comando debe pasar de 64 bytes. La mayor documentada, set zone, mide 38 bytes.
- Diseño del relay: si el ESP32 solo reenvía, también debería reenviar los ACK (`FD FC FB FA ... 04 03 02 01`) al reloj y aceptar desde el reloj tramas de comando crudas para escribirlas en la UART. Así la configuración también vive en el reloj.
- Las zonas tipo 2 ("Do not detect") sirven para excluir el campo cercano: equipo del propio jugador, el brazo que se balancea, el cañón de la réplica. Un ejemplo sería un rectángulo con X en [-600, 600] mm e Y en [0, 400] mm. Límites: 3 rectángulos y un solo tipo activo a la vez.
- El modo de tracking probablemente persiste, porque ESPHome lo lee del módulo al arrancar. No está confirmado; conviene reenviar `90` en cada boot, que no cuesta nada.
- Hipótesis para probar: si entrar en modo config (que corta el reporte) también detiene el chirp de RF, sería una forma barata de multiplexar en el tiempo los dos radares si interfieren (ver §5).

### Gaps
- El protocolo menciona un plazo ("within the specified time") pero no da el timeout del modo config.
- No se encontró un protocolo más nuevo que V1.03, aunque el firmware llegó a V2.14 (§9). Pudo haber comandos no documentados: contraseña BT, sensibilidad, filtros.
- No se sabe si el modo single/multi persiste.
- No se sabe si en modo config se detiene la emisión de RF.

---

## 3. Campo de visión real (azimut y elevación), alcance según postura, pérdida de personas quietas y precisión cerca de ±60°

### Takeaway
Hi-Link especifica azimut ±60°, elevación ("pitch") ±35° y alcance máximo de tracking de 6 m, para montaje en pared a 1,5-2 m con una persona de prueba de 1,75 m. No publica precisión de distancia ni de ángulo. En la práctica el LD2450 sigue personas **en movimiento** y **pierde a las que se quedan quietas**. Esto lo dicen el fabricante de producto Screek y usuarios de HA, aunque el marketing de Hi-Link afirme que detecta micro-movimiento. Algunas tiendas anuncian 8 m, dato no verificado.

### Cited Findings
- [PRIM] "Longest detection range 6m", "Ultra-small module size: 15mm x 44mm", "Wall mounting", "Azimuth angle ±60°, pitch angle ±35°" — [HLK Manual V1.00](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf) §2.1
- [PRIM] "The typical installation method of LD2450 is wall mounting ... the farthest positioning tracking distance is 6m ... the recommended installation height range is 1.5~2m." y "Figure 7 shows the localization tracking range of this module at a wall height of 1.5m. The test person was 1.75m tall and of medium build. The detection angle range is ±60° centered normal to the radar antenna plane." — [HLK Manual V1.00](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf) §7
- [PRIM] Table 4: "Detection distance 6m", "Detection angle ±60°", "Sweep bandwidth 250MHz", "Modulation method FMCW", "Target application: Detection and tracking of up to three targets" — [HLK Manual V1.00](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf) §8
- [PRIM] Hardware: "an AIoT millimetre wave radar chip, a high performance one-transmitter-two-receiver microstrip antenna and a low cost MCU". El producto está pensado "in general indoor scenarios such as homes, offices and hotels to enable the location tracking of moving human bodies" — [HLK Manual V1.00](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf) §1
- [PRIM] Afirmación de marketing de Hi-Link: "In addition to sensitive sensing of the movement of the human body, for the traditional program can not identify the micro-movement of the human body can also be sensitive to sense" — [HLK Manual V1.00](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf) §2.2. **La contradicen** las dos fuentes siguientes.
- [COMUNIDAD, fabricante de producto] Screek (su sensor 2A usa el LD2450): azimut "60 degrees on either side, adding up to 120 degrees"; vertical "35 degrees up and down, adding up to 70 degrees"; "ld2450 does not support stationary detection". Los usuarios ven desaparecer al objetivo cuando se sienta quieto, y Screek lo atribuye a una limitación de hardware (harían falta más antenas y optimizar el algoritmo). Altura recomendada "1.5~2 m" — [Screek 2A Usage FAQ](https://screek.io/2a/usage-faq)
- [COMUNIDAD] Hilo de HA (abril de 2026), con firmware **V2.14.25112412**: solo funcionaba el sensor de "moving target". koying (Chris B), 2026-04-17: "The focus of 2450 is multiple moving targets" y "that's just how LD2450 works: good for moving targets, sucks for static". El usuario terminó cambiando a un LD2410 para presencia estática — [HA "only see moving targets"](https://community.home-assistant.io/t/having-issues-with-hlk-ld2450-only-see-moving-targets-no-presence/1004773)
- [COMUNIDAD] wojciech6789 (2023-12-22), usando varios LD2450 para posicionamiento en interiores: "The most frustrating aspect is the inconsistency of the data. It feels like the sensor occasionally shifts X and Y by a random value." — [HA "indoor positioning ... multiple LD2450"](https://community.home-assistant.io/t/affordable-and-simple-indoor-positioning-system-using-multiple-ld2450-mmwave-radar-sensors/659634)
- [RETAIL/SNIPPET] Hay tiendas que anuncian unos 8 m y "±60° (Horizontal), ±35° (Vertical)", con montaje "vertically at a height of 1.5m - 2m" (snippets de probots.co.in y Laskakit; el slug de la URL de Laskakit dice "do-8m" y su título en inglés dice "up to 6m") — [probots](https://probots.co.in/hlk-ld2450-24ghz-mmwave-tracking-radar-sensor.html), [Laskakit](https://www.laskakit.cz/en/radarovy-senzor-pohybu-hlk-ld2450-fmcw-24ghz-do-8m-pro-detekci-lidske-pritomnosti/)
- [PRIM, contexto] El modelo hermano LD2461 sí publica cifras por postura ("Static targets 5m", "Seated micro-motion 6m", "Moving targets 8m") y precisión ("测距精度 0.1m", "测角精度 2°", "测角分辨率 15°"). El LD2450 **no** publica nada parecido — [HLK LD2461](https://www.hlktech.com/en/Goods-225.html)

### Inferences
- Resolución en distancia teórica con 250 MHz de barrido: c/(2B) = **0,6 m**. Con 1T2R solo hay una línea base de ángulo. Dos jugadores a menos de unos 0,6 m en distancia y con ángulos parecidos probablemente se fusionan en un solo objetivo, y el límite de 3 objetivos agrava el problema.
- Con 2 antenas RX el ángulo sale de la diferencia de fase, y el error crece aproximadamente como 1/cos θ. **A ±60° el error angular es ~2× el del boresight**. Ilustración: 5° de error a 5 m equivalen a unos 0,44 m de error lateral. Hay que esperar tracks más ruidosos en los bordes, que en Blindside corresponden a los laterales extremos (±90° del cuerpo) y al centro de solape.
- El 1T2R no mide elevación: Y/X son proyecciones de la distancia oblicua. Con el radar a ~1 m y personas a más de 2 m, el error por altura es pequeño.
- A la cadera (~0,9-1,0 m), el borde inferior del haz (-35°) toca el suelo a unos 1,3-1,4 m (h/tan 35°). El suelo es clutter estático mientras el radar está quieto (ver §4 para cuando se mueve).
- Consecuencia de juego: un rival agazapado o emboscado y **quieto** no aparecerá. El radar se comporta como "detector de movimiento", parecido en espíritu al heartbeat sensor pero sin ver campers. Hay que diseñar la UX alrededor de eso.

### Gaps
- No hay especificación oficial de precisión (distancia ni ángulo) para el LD2450.
- No se encontraron mediciones de alcance por postura (caminando, de pie, sentado), ni del tiempo que tarda en descartar a una persona quieta, ni del comportamiento real en ±60°, ni de la distancia mínima de detección.
- No se encontraron datos en exteriores (follaje, lluvia, campo abierto), que es el entorno del airsoft.

---

## 4. Comportamiento cuando el radar se mueve (portátil, en el cuerpo, en un vehículo): objetivos fantasma y clutter estático

### Takeaway
**No se encontró ningún test documentado** del LD2450 en una plataforma móvil o llevado en el cuerpo. Hi-Link sí advierte por escrito que el movimiento del propio radar degrada la detección y que el lóbulo trasero capta movimiento detrás del módulo, y recomienda una placa metálica trasera. Por física (inferencia), al moverse el radar el clutter estático adquiere Doppler y el algoritmo, pensado para montaje fijo, lo reporta como objetivos móviles. Eso consume los 3 slots de tracking. Blindside necesita IMU para descartar tramas o compensar el propio movimiento, y probablemente una UX de "detenerse para escanear".

### Cited Findings
- [PRIM] "Make sure the installation position of the sensor is firm and stable, the shaking of the radar itself will affect the detection effect" — [HLK Manual V1.00](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf) §7.1
- [PRIM] "To ensure that the back of the radar will not have object movement or vibration. Due to the penetrating nature of radar waves, the back flap of the antenna signal may detect the moving objects on the back of the radar. A metal shield or metal back plate can be used to shield the radar back flap to reduce the effect of objects on the back of the radar" — [HLK Manual V1.00](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf) §7.1
- [PRIM] Entornos que degradan la detección: "continuous movement of non-human objects in the sensing area, such as animals, continuously swinging curtains, large green plants facing the air outlet", "a large area of strong reflective objects in the sensing area" — [HLK Manual V1.00](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf) §7.1
- [COMUNIDAD, fabricante de producto] Screek enumera interferencias por "Windblown clothing interference from balconies", ventiladores de techo, extractores, aire acondicionado y animales, y reporta "ghostly shadows" que entran y salen — [Screek 2A Usage FAQ](https://screek.io/2a/usage-faq)
- [RETAIL/SNIPPET] "Reflective surfaces such as metal walls, mirrors, or large glass panels create multipath reflections that appear as ghost targets" (snippet del buscador sobre páginas de retail y tutoriales; página no leída) — [componentindex](https://componentindex.net/components/ld2450/)
- Búsquedas específicas ("robot", "moving platform", "bicycle/car/wearable") solo devolvieron material de montaje en pared. El único proyecto que habla de seguir personas en robots lo hace de forma especulativa y sin mediciones — [búsqueda: probots](https://probots.co.in/hlk-ld2450-24ghz-mmwave-tracking-radar-sensor.html)

### Inferences
- **Mecanismo.** Un radar fijo separa personas de muebles porque los objetos estáticos tienen Doppler ≈ 0 (supresión de clutter / MTI). Si el portador camina a ~1,4 m/s, todo lo estático que tiene delante aparece con velocidad radial ≈ -1,4·cos θ m/s (≈ -140 cm/s, "acercándose"): árboles, barricadas, paredes, el suelo. Todo eso supera cualquier umbral de "moving target". Probablemente el tracker lo acepte como objetivos. Como hay solo 3 slots, **el clutter puede tapar a un jugador real**.
- **Giros.** Una rotación de cadera de ~100°/s mueve un objeto estático situado a 5 m unos 8,7 m/s lateralmente en el marco del sensor. Eso produce saltos de track y fantasmas durante el giro.
- **Marcha.** La oscilación vertical y el impacto de cada paso introducen micro-Doppler en todo el entorno. Además aparecen en campo cercano ropa suelta, bolsillos, cinta del arma y las piernas o brazos del propio jugador, sobre todo por el lóbulo trasero, que en el cinturón mira al propio torso y a los muslos.
- **Mitigaciones propuestas** (hay que validarlas):
  - (a) Montaje rígido con placa metálica trasera (lo recomienda HLK), que además bloquea el lóbulo trasero hacia el cuerpo.
  - (b) Zona tipo 2 que excluya el campo cercano (0-0,4 m).
  - (c) *Gating* por IMU: cada radar lleva su MPU6050. Las tramas con |ω| o |a| por encima de un umbral se marcan como "no fiables", y el reloj congela o degrada los tracks mientras duran.
  - (d) Compensación de rotación: girar los tracks existentes según el yaw integrado del giróscopo.
  - (e) Compensación de velocidad propia: restar v_ego·cos θ a la velocidad radial. Requiere estimar v_ego, cosa difícil sin nube de puntos; el LD2450 solo entrega 3 objetivos, así que no se puede estimar la velocidad propia a partir del clutter como sí se haría con un TI IWR6843.
  - (f) UX de "detenerse para escanear", donde la lectura es válida con el jugador quieto, que encaja con el uso táctico.
- Protocolo de prueba mínimo para cuantificarlo:
  1. Portador quieto en campo abierto
  2. Portador caminando sin rivales
  3. Portador girando en el sitio
  4. Caminando junto a paredes o vehículos
  5. Con un rival real caminando
  En cada caso: tasa de tramas con objetivos, duración de los fantasmas, y si el rival queda desplazado de los 3 slots.

### Gaps
- No hay datos publicados del LD2450 moviéndose: ni tasa de fantasmas ni cuánto persiste un track falso.
- El algoritmo interno (umbral de supresión de estáticos, persistencia de tracks, asignación de los 3 slots) no está documentado.

---

## 5. Interferencia entre dos o más LD2450 con campos solapados (colisión de chirps FMCW) y mitigación

### Takeaway
La única guía de Hi-Link es: no instalar radares de 24 GHz enfrentados y separarlos "as far away as possible". El protocolo V1.03 **no tiene ningún ajuste anti-interferencia** (canal, chirp, temporización). La teoría FMCW con parámetros idénticos (el caso de dos LD2450 con el mismo firmware) dice que casi siempre sube el piso de ruido y **solo ocasionalmente** aparece un fantasma, con probabilidad ≈ ancho de banda del filtro de FI / ancho de banda de barrido, que además deriva entre bins. En la comunidad hay reportes de interferencia **entre modelos distintos** (el LD2450 provoca falsos positivos en un LD2410/LD2412 cercano) que mejoró con unos 15 cm de separación. **No se encontró ningún reporte medido de dos LD2450 con FOV solapados.**

### Cited Findings
- [PRIM] "When multiple 24GHz band radars are present, do not install them in the direction directly opposite to the beam, but as far away as possible to avoid possible mutual interference." — [HLK Manual V1.00](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf) §7.1
- [PRIM] La lista completa de comandos de la V1.03 (enable/end, single/multi, query mode, firmware, baud, factory reset, restart, BT, MAC, query/set zones) no incluye nada de canal, frecuencia ni temporización — [HLK Protocolo V1.03](https://make.net.za/wp-content/datasheets/HLK%20LD2450%20Serial%20Communication%20Protocol%20v1.03.pdf) índice §2.2
- [PRIM, académico] Jin y Cao, *Automotive Radar Interference Mitigation using Adaptive Noise Canceller* (arXiv 1911.06372, 2019): "Interference among frequency modulated continues wave (FMCW) automotive radars can either increase the noise floor, which occurs in the most cases, or generate a ghost target in rare situations." Para "Two FMCW Automotive Radars with Identical Parameters", el desfase "randomly walks due to the clock drift"; "the ghost target occurs only when the frequency difference between the interfering chirp and victim chirp falls into AAF's bandwidth. Thus the occurring probability of ghost target can be expressed as: P = BW_LPF / BW" — [Jin & Cao 2019](https://arxiv.org/abs/1911.06372)
- [RETAIL/SNIPPET] La literatura propone como mitigaciones PRF escalonadas, salto de frecuencia aleatorio y codificación de fase. Todas requieren control del chirp, que el LD2450 no expone — [BlueFMCW](https://asp-eurasipjournals.springeropen.com/articles/10.1186/s13634-022-00838-7) (snippet)
- [COMUNIDAD] Hilo de HA sobre interferencia LD2410 + LD2450 en un ESP32-S3. vvvlladimir (2025-06-04): ambos en la misma caja, mirando al mismo lado, con zonas no solapadas. "When the LD2450 is active, the LD2410 starts reporting false positives". Descartó acoplamiento eléctrico (TX/RX desconectados, fuentes aisladas) y sospecha RF. Nickduino (2026-04-16) confirma el fenómeno y recuerda que Screek (2A, LD2410 + LD2450) y Apollo (R PRO-1, LD2450 + LD2412) sí combinan radares. TOTODODOHOHO (2026-09-28): "6 inches away from each other they are fine. Too close and the 2412 seems to get high feedback triggering false detections." No hubo solución limpia ("Nope, that nope") — [HA LD2410/LD2450 interference](https://community.home-assistant.io/t/interference-issue-between-ld2410-and-ld2450-both-on-esp32-s3/898119)
- [RETAIL/SNIPPET] Otro hilo de HA (no leído completo): colocar LD2450 y LD2412 lado a lado hace que el 2412 "get much higher detection levels leading to false positives" — [HA LD2450 and LD2412 Combo](https://community.home-assistant.io/t/ld2450-and-ld2412-combo/1026592)
- [COMUNIDAD] El hilo de posicionamiento con varios LD2450 (wojciech6789) no aporta datos explícitos de interferencia entre ellos, solo saltos aleatorios de X/Y (ver §3) — [HA indoor positioning](https://community.home-assistant.io/t/affordable-and-simple-indoor-positioning-system-using-multiple-ld2450-mmwave-radar-sensors/659634)
- [RETAIL/SNIPPET, **baja calidad**] Un artículo "wiki" de AliExpress recomienda no montar a menos de 1 m, rotar ±45°, "set staggered update rates" y habla de "built-in anti-interference protocols managed digitally via UART commands". **El protocolo oficial V1.03 no respalda esas afirmaciones**: no existe ningún comando de tasa ni de anti-interferencia. Parece contenido generado sin fuente — [AliExpress wiki](https://www.aliexpress.com/s/wiki-ssr/article/ld2450-radar-sensor)
- [COMUNIDAD] Screek no dice nada sobre usar varios sensores juntos — [Screek 2A Usage FAQ](https://screek.io/2a/usage-faq)

### Inferences
- **Geometría de Blindside.** Los radares van en los bolsillos delanteros abiertos ±30°, cada uno con ±60°. La cobertura total va de -90° a +90° y el solape central de -30° a +30°. Visto desde el radar izquierdo, el derecho queda a unos **120° de su boresight**, fuera del lóbulo principal. El acoplamiento directo solo va por lóbulos laterales o traseros y por reflexión en el cuerpo, un riesgo menor que el caso "enfrentados" contra el que advierte HLK. El riesgo real está en el sector de solape, donde ambos iluminan los mismos dispersores y el eco biestático de A puede entrar en B cuando los chirps se alinean.
- Con parámetros idénticos (mismo firmware), según Jin y Cao se espera sobre todo pérdida de sensibilidad (más ruido y menos alcance) y fantasmas **esporádicos** que se desplazan en distancia. Un tracker con confirmación M-de-N en el reloj debería filtrarlos. Cuantificar P requiere conocer el BW del filtro de FI, que no está documentado.
- Mitigaciones posibles sin control del chirp:
  - (a) Separación física: los bolsillos están a unos 30-40 cm, más que los ~15 cm que bastaron en el reporte de HA.
  - (b) Placa metálica trasera o tabique lateral en cada radar.
  - (c) Validación cruzada en el tracker: en el sector de solape, un objetivo que ve un solo radar pierde confianza.
  - (d) Abrir más los radares (±40-45°) para reducir el solape, a costa de dejar hueco en el centro.
  - (e) TDM por alimentación con MOSFETs, que requiere medir el tiempo de arranque.
  - (f) TDM por modo config, si se verifica que corta la emisión (§2).
- Prueba recomendada: campo vacío, solo A durante N minutos, luego A+B, comparando la tasa de tramas con objetivos y la distribución de fantasmas. Repetir con una persona caminando por el solape.

### Gaps
- No hay mediciones de dos LD2450 con FOV solapados.
- La temporización de trama y chirp, el duty cycle, el BW de FI y la sincronía entre unidades son desconocidos.
- No se sabe si el modo config corta la RF, ni cuánto tarda el módulo en arrancar tras encenderlo.

---

## 6. Alimentación: rango de tensión, consumo medio y de pico, niveles lógicos de la UART, calor

### Takeaway
DC 5 V con fuente capaz de más de 200 mA, **consumo medio de 120 mA**, UART a **3,3 V** (se conecta directo al ESP32), -40 a 85 °C. Hi-Link no publica corriente de pico. Hay reportes cualitativos de que se calienta. No conviene alimentarlo desde 3,3 V.

### Cited Findings
- [PRIM] "The module power supply voltage is 5V, and the power supply capacity of the input power supply is required to be greater than 200mA. The module IO output level is 3.3 V." — [HLK Manual V1.00](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf) §5.1; igual en [HLK Protocolo V1.03](https://make.net.za/wp-content/datasheets/HLK%20LD2450%20Serial%20Communication%20Protocol%20v1.03.pdf) §1.2.1
- [PRIM] Table 4: "Power supply requirements DC5V, power supply capacity >200mA", "Average operating current 120mA", "Operating frequency band 24GHz~24.25GHz", "Ambient temperature -40~85℃", "Dimension 15mm x 44mm" — [HLK Manual V1.00](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf) §8
- [PRIM] Pines: "5V Power supply input 5V / GND Power ground / Tx Serial port Tx pin / Rx Serial port Rx pin". El módulo trae "two kinds of external interfaces, socket and pin, both of which have a serial port and a power supply port" — [HLK Manual V1.00](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf) §4
- [COMUNIDAD] TillFleisch: "I recommend using a `5V` power supply... Using the `3.3V` pin... may cause instabilities." — [TillFleisch/ESPHome-HLK-LD2450](https://github.com/TillFleisch/ESPHome-HLK-LD2450)
- [COMUNIDAD] ShillehTek alimenta el LD2450 desde VIN (5 V) del ESP32 y dice "its TX/RX pins are 3.3 V logic, so it connects to the ESP32 directly" — [ShillehTek](https://shillehtek.com/blogs/news/esp32-ld2450-live-web-radar-tracking)
- [COMUNIDAD] screek-workshop (2023): "The module generates more heat, which, like the LD2410, seems to be a natural characteristic of HLK." En cambio wojciech6789 dice que no notó problemas de calor — [HA "Initial experiments"](https://community.home-assistant.io/t/hlk-ld2450-initial-experiments-to-connect-to-homeassistant/578878), [HA indoor positioning](https://community.home-assistant.io/t/affordable-and-simple-indoor-positioning-system-using-multiple-ld2450-mmwave-radar-sensors/659634)
- [RETAIL/SNIPPET] Pin "OT1" de salida de presencia: studiopieters lo lista como "Optional presence output pin (open-drain)", pero **la tabla oficial de pines de HLK solo tiene 5V, GND, Tx y Rx**. El dato de OT1 no está verificado y probablemente viene de otro módulo — [studiopieters](https://www.studiopieters.nl/the-complete-guide-to-the-hlk-ld2450-24ghz-multi-target-tracking-radar-module/)

### Inferences
- Presupuesto aproximado: 2 × (5 V × 0,12 A) = 1,2 W de radares, más el ESP32 con BLE (unos 0,3-0,8 W, dato general sin verificar aquí), da unos 1,5-2 W. Con un power bank de 10 000 mAh (unos 37 Wh a 3,7 V, ~85 % de eficiencia de conversión) salen unas **15 h**. El consumo total (~0,35-0,45 A) está muy por encima del umbral de autoapagado típico de los power banks.
- Que pidan más de 200 mA de capacidad con una media de 120 mA sugiere picos de corriente en ráfagas de transmisión. Poner un electrolítico o cerámico de reserva (100-470 µF) cerca de cada módulo y llevar los 5 V directos del power bank, sin pasar por el regulador de 3,3 V de la DevKit.
- La UART a 3,3 V conecta directo con el ESP32. HLK no documenta si el RX del módulo tolera 5 V; no hace falta, porque el ESP32 trabaja a 3,3 V.
- Calor: 0,6 W sobre 15×44 mm puede calentar bastante dentro de una funda cerrada al sol de Medellín. Conviene ventilar y no pegar el módulo al cuerpo, aunque la placa trasera metálica también ayuda a disipar.

### Gaps
- No hay datos de corriente de pico, corriente con BT apagado frente a encendido, corriente de arranque ni temperatura de placa medida.

---

## 7. Radomo o cubierta: materiales y espesores transparentes a 24 GHz, distancia a la antena, efecto de metal, tela y plástico; orientación de montaje

### Takeaway
Hi-Link da una guía cuantitativa. La cubierta debe ser plana, de espesor uniforme, paralela a la antena y sin metal ni recubrimientos conductores. La distancia antena-cubierta ideal es un múltiplo de λ0/2 (**6,2 mm**) y lo recomendado es **12,4 o 18,6 mm (±1,2 mm)**. El espesor ideal es un múltiplo de media longitud de onda en el material (ABS con εr 2,5 → **3,92 mm**, ±20 %); si no se puede, material de εr bajo y espesor ≤ λ/8. Sobre el lóbulo trasero recomienda una placa metálica. Qué eje de la PCB corresponde al azimut solo aparece en figuras, no en el texto.

### Cited Findings
- [PRIM] "If the radar needs to install the housing, the housing must have good wave transmission characteristics at 24 GHz and cannot contain metallic materials or materials that have a shielding effect on electromagnetic waves." — [HLK Manual V1.00](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf) §7.1
- [PRIM] Efectos del radomo: reflexión en su borde (pérdida de potencia, el eco reflejado "affecting the isolation between the transmitting and receiving channels", empeora el standing wave y la ganancia), pérdidas en el medio ("the higher the frequency loss will be greater") y refracción, que "Affect the radiation direction map of the antenna, which in turn affects the coverage of the sensor" — [HLK Manual V1.00](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf) §9.1
- [PRIM] Principios de diseño: "Smooth and flat surface, uniform thickness. Such as flat or spherical surface, not uneven"; "If there is surface coating, it cannot contain metal or conductive material"; la cubierta va "Above the antenna, the antenna surface is parallel to the antenna surface" — [HLK Manual V1.00](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf) §9.2
- [PRIM] Altura H: "The ideal height is an integer multiple of the half-wavelength of electromagnetic waves in the air ... 24.125GHz center frequency, its half-wavelength in the air is about 6.2mm". Recomendado: "When space allows, 1 or 1.5 times Wavelength is recommended. For example, 12.4 or 18.6mm is recommended for 24.125GHz. Error control: ±1.2mm" — [HLK Manual V1.00](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf) §9.2-§9.3
- [PRIM] Espesor D: "The ideal thickness is an integer multiple of the half-wavelength of the electromagnetic wave in the medium ... an ABS material εr=2.5, its half wavelength of about 3.92mm". Recomendado: "Recommended half wavelength, error control ±20%. If the thickness requirement of half wavelength cannot be met: Low εr material is recommended; Thickness recommended 1/8 wavelength or thinner". Con materiales no homogéneos o multicapa se recomienda "experimental adjustment during design" — [HLK Manual V1.00](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf) §9.2-§9.3
- [PRIM] El manual trae una "Table 5 Common material properties of antenna covers" que es imagen y no se extrajo, con la advertencia "for reference only, Please confirm the actual value with the supplier" — [HLK Manual V1.00](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf) §9.3
- [PRIM] "Good shell penetration, can be hidden inside the shell work, no need to open holes in the product surface" — [HLK Manual V1.00](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf) §2.2
- [PRIM] Orientación: "Try to ensure that the radar antenna is facing the area to be detected, and the antenna is open and unobstructed around". La placa metálica trasera se recomienda para el lóbulo trasero (§4). La guía de usuario, §5 "Module test installation", dice solo "The module needs to be installed in this direction" y remite a una figura — [HLK Manual V1.00](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf) §7.1, [HLK User Guide](https://d.hlktech.net/download/HLK-LD2450/1/HLK-LD2450%20operation%20manual.doc..pdf) §5
- [COMUNIDAD] Screek: "Center antenna plane should face detection area", montaje en pared a "1.5~2 m" — [Screek 2A Usage FAQ](https://screek.io/2a/usage-faq)
- [PRIM] La antena conectable del LD2450 es la de **Bluetooth**, no la del radar: "The antenna of 2450 is a Bluetooth antenna ... The signal is unstable when the antenna is not connected. Basically, you can only connect it nearby." — [HLK User Guide](https://d.hlktech.net/download/HLK-LD2450/1/HLK-LD2450%20operation%20manual.doc..pdf) §4.4

### Inferences
- Cuentas útiles: λ0 = 12,43 mm a 24,125 GHz. En ABS, λ = 12,43/√2,5 ≈ 7,86 mm, así que λ/2 = 3,93 mm y λ/8 ≈ 0,98 mm. El texto no aclara si el "1/8 wavelength" es en el medio o en el aire (en aire λ0/8 ≈ 1,55 mm). Criterio conservador: ≤ 1 mm si no se llega a media onda.
- Para una caja impresa en 3D (PLA o PETG; su εr no se verificó en esta investigación, típicamente se cita ~2,5-3), una ventana frontal **maciza (100 % infill)** de ~3,6-4 mm a 12,4 mm de la antena es lo más cercano a la receta de HLK. El infill parcial crea un material no homogéneo y HLK pide ajustarlo experimentalmente.
- Tela (funda o MOLLE): Hi-Link no da guía. Una tela sintética fina y seca (≪ λ, εr bajo) debería afectar poco. Los problemas previsibles son tela **mojada** (lluvia o sudor), recubrimientos metalizados o conductores (que HLK prohíbe explícitamente) y tela que se mueve o flamea delante de la antena, porque aparece como objetivo en campo cercano (ver "windblown clothing" en §4).
- Metal cercano (hebillas, cargadores en el bolsillo, herrajes MOLLE, la réplica): no debe haber nada metálico delante ni a los lados del plano de antena. Detrás, en cambio, sí conviene la placa metálica.
- Orientación en el cinturón: el plano de azimut (±60°) tiene que quedar horizontal. Como no está escrito qué eje de la PCB de 15×44 mm es el de azimut, hay que verificarlo en banco: con el radar fijo, desplazarse lateralmente a distancia constante; si X cambia y Y no, la orientación es correcta; si no, rotar 90°. Cerca de la cadera, una leve inclinación hacia arriba (10-15°) aleja del radar el punto donde el haz toca el suelo.

### Gaps
- Los valores de εr y tan δ de la Table 5 (imagen) no se extrajeron.
- No hay texto que diga qué eje físico de la PCB es el de azimut.
- No se encontró guía ni mediciones con tela, sudor o lluvia.

---

## 8. Librerías y parsers existentes y sus licencias

### Takeaway
La referencia más completa y mantenida es el **componente nativo `ld2450` de ESPHome**, con todos los comandos, zonas, BT, baudios y firmware. Su código C++ es **GPLv3**. Para reutilizar código con licencia permisiva existen csRon/HLK-LD2450 (Python, MIT), RBEGamer/HLK-LD2450 (Arduino, MIT), TillFleisch/ESPHome-HLK-LD2450 (componente externo, MIT) y Fiooodooor/HLK-LD245X (Arduino, BSD-3, también cubre el LD2451). Con copyleft: darkjumpy (AGPL-3.0) y smily77/LD2450_BLE (GPL-3.0, lee los datos **por el BLE propio del módulo**).

### Cited Findings
- [PRIM] Licencia de ESPHome: "The C++/runtime codebase of the ESPHome project (file extensions .c, .cpp, .h, .hpp, .tcc, .ino) are published under the GPLv3 license. The python codebase and all other parts of this codebase are published under the MIT license." — [ESPHome LICENSE](https://raw.githubusercontent.com/esphome/esphome/dev/LICENSE)
- [CÓDIGO] Qué aporta el componente de ESPHome: decodificadores (§1), constantes de comandos (`CMD_ENABLE_CONF = 0xFF`, `CMD_DISABLE_CONF = 0xFE`, `CMD_QUERY_VERSION = 0xA0`, `CMD_QUERY_MAC_ADDRESS = 0xA5`, `CMD_RESET = 0xA2`, `CMD_RESTART = 0xA3`, `CMD_BLUETOOTH = 0xA4`, `CMD_SINGLE_TARGET_MODE = 0x80`, `CMD_MULTI_TARGET_MODE = 0x90`, `CMD_QUERY_TARGET_MODE = 0x91`, `CMD_SET_BAUD_RATE = 0xA1`, `CMD_QUERY_ZONE = 0xC1`, `CMD_SET_ZONE = 0xC2`) y la tabla `BAUD_RATES[] = {9600, 19200, 38400, 57600, 115200, 230400, 256000, 460800}` — [ESPHome ld2450.cpp](https://raw.githubusercontent.com/esphome/esphome/dev/esphome/components/ld2450/ld2450.cpp)
- [PRIM] La documentación de ESPHome pide firmware "`V2.02.23090617` or later" y ofrece: presence timeout (5 s por defecto, "applies _only_ to the presence binary sensor states"), hasta 3 zonas (Disabled/Detection/Filter), switch multi-target, switch de Bluetooth, select de baud rate, y botones de restart y factory reset — [ESPHome docs LD2450](https://esphome.io/components/sensor/ld2450/)
- [COMUNIDAD] TillFleisch/ESPHome-HLK-LD2450 es MIT y ofrece zonas como polígonos convexos con márgenes de histéresis, `max_detection_distance`, límites de "tilt angle", `fast_off_detection`, `flip_x_axis`, depuración por objetivo y la recomendación de subir `rx_buffer_size` — [TillFleisch/ESPHome-HLK-LD2450](https://github.com/TillFleisch/ESPHome-HLK-LD2450)
- [RETAIL/SNIPPET] Licencias según el listado de búsqueda de GitHub (no se abrió el LICENSE de cada repositorio):
  - [RBEGamer/HLK-LD2450](https://github.com/RBEGamer/HLK-LD2450): Arduino, MIT; también publicado en [PlatformIO](https://registry.platformio.org/libraries/rbegamer/HLK-LD2450)
  - [Fiooodooor/HLK-LD245X](https://github.com/Fiooodooor/HLK-LD245X): Arduino, LD2450 + LD2451, BSD-3-Clause
  - [darkjumpy/HLK-LD2450_Arduino_Library](https://github.com/darkjumpy/HLK-LD2450_Arduino_Library): ESP8266/ESP32, AGPL-3.0
  - [smily77/LD2450_BLE](https://github.com/smily77/LD2450_BLE): "Arduino Library for the HLK-LD2450 Sensor using BLE (Read data)", GPL-3.0
  - [csRon/HLK-LD2450](https://github.com/csRon/HLK-LD2450): "Python Serial Protocol Implementation and Demos", MIT
  - Hay además una entrada en el índice oficial de Arduino: [Arduino Libraries HLK-LD2450](https://docs.arduino.cc/libraries/hlk-ld2450/)
- [CÓDIGO] ESPHome también tiene el componente `rd03d` para el Ai-Thinker RD-03D, con el mismo formato de trama (§1) — [ESPHome rd03d.cpp](https://raw.githubusercontent.com/esphome/esphome/dev/esphome/components/rd03d/rd03d.cpp)
- [COMUNIDAD] Ejemplo mínimo de ESP32 (Arduino): `Serial2` con RX=16 y TX=17 a 256000 y `Serial2.setRxBufferSize(1024)` — [ShillehTek](https://shillehtek.com/blogs/news/esp32-ld2450-live-web-radar-tracking)

### Inferences
- El decodificador ocupa unas 10 líneas y está completamente especificado por el PDF de Hi-Link. Para Blindside (open source, licencia aún no definida) lo más limpio es **implementarlo desde el protocolo (clean-room)** y usar ESPHome (GPLv3) y csRon (MIT) solo como **oráculo de pruebas**: las tramas de ejemplo oficiales y las de la guía (§1) sirven como vectores de test. Copiar código GPL o AGPL obligaría a licenciar Blindside de forma compatible.
- smily77/LD2450_BLE indica que el BLE propio del módulo expone datos de objetivos. En teoría el reloj podría conectarse **directamente** a los LD2450 sin ESP32, pero se perderían el sello de tiempo común, la sincronía con la IMU y el control de seguridad (BT abierto, ver §9). Protocolo, tasa y límites de conexión no están verificados.

### Gaps
- No se abrieron los LICENSE de cada repositorio ni se revisó su estado de mantenimiento.
- No se revisó el protocolo BLE que usa smily77: UUIDs, tasa, si requiere contraseña.

---

## 9. Revisiones de hardware y firmware, bugs conocidos y comparación con alternativas (RD-03D, LD2451, LD2461, TI IWR6843)

### Takeaway
Versiones de firmware vistas:
- **V1.02.22062416**: ejemplo del protocolo. Los firmware viejos solo permitían OTA desde la app.
- **V2.02.23090617**: mínimo que pide ESPHome.
- **V2.04.23101915**: "latest" según la guía de HLK y mínimo recomendado por Everything Smart.
- **V2.14.25112412**: ya en campo en 2026. **Actualizar a ≥ 2.14 puede cambiar el baud rate** y hay que devolverlo a 256000.

Bugs o limitaciones reportadas: el módulo deja de emitir tras horas hasta que se reinicia, fantasmas por movimiento o reflexiones y ninguna detección estática. Entre las alternativas, el **RD-03D** usa el mismo protocolo de trama, es más barato y consume menos. El **LD2461** tiene 5 objetivos y especificaciones mejores, pero FOV de ±45° y reportes de "super ghosting". El **LD2451** es para vehículos y no sirve aquí. El **TI IWR6843AOP** es otra liga en capacidad, precio y complejidad.

### Cited Findings

**Firmware y hardware del LD2450**
- [PRIM] Ejemplo de ACK de versión → "V1.02.22062416" — [HLK Protocolo V1.03](https://make.net.za/wp-content/datasheets/HLK%20LD2450%20Serial%20Communication%20Protocol%20v1.03.pdf) §2.2.6
- [PRIM] "The old version only supports OTA function using mobile app. Parameter setting is a function of subsequent upgrades. You can OTA upgrade to the latest firmware ... (the latest firmware is version 2.04.23101915)". La app es "2450 app ... https://www.hlktech.com/Mobile/App/12.html" y permite OTA "to the previous old version or to the subsequent new version" — [HLK User Guide](https://d.hlktech.net/download/HLK-LD2450/1/HLK-LD2450%20operation%20manual.doc..pdf) §3.2 y §4.2
- [PRIM] "Ensure that the LD2450 firmware version is `V2.02.23090617` or later for proper integration functionality." — [ESPHome docs LD2450](https://esphome.io/components/sensor/ld2450/)
- [COMUNIDAD, fabricante de producto] Everything Smart (fabrica el EP Lite con LD2450) marca como mínimo "2.04.23101915 or later". Advierte: "If upgrading to version 2.14 or later, you must set the baud rate back to 256000. This update seems to change the baud rate." La OTA se hace con HLKRadarTool por Bluetooth; en iOS la app puede colgarse y la alternativa es "RadarTools". Recomiendan desactivar el BT después "for security purposes" — [Everything Smart: update LD2450 firmware](https://docs.everythingsmart.io/s/products/doc/how-to-update-ld2450-firmware-hag7PcYn8N)
- [COMUNIDAD] Firmware **V2.14.25112412** reportado en campo en abril de 2026 — [HA "only see moving targets"](https://community.home-assistant.io/t/having-issues-with-hlk-ld2450-only-see-moving-targets-no-presence/1004773)
- [COMUNIDAD] Screek habla de firmware "V1.22" estable y "V1.23" beta, este último "overly sensitive", que llega a reconocer "active 3D printers" como humanos, y dice que se puede hacer downgrade. La numeración no coincide con los strings V2.xx.yyyymmdd de HLK y no está claro a qué versión real corresponde — [Screek 2A Usage FAQ](https://screek.io/2a/usage-faq)
- [COMUNIDAD] screek-workshop (2023): "module suddenly stops outputting after a long period of time...Unless the module is restarted". El 2023-06-27 añade "new firmware doesn't need anymore resistors" (sin más contexto) — [HA "Initial experiments"](https://community.home-assistant.io/t/hlk-ld2450-initial-experiments-to-connect-to-homeassistant/578878)
- [COMUNIDAD] Issue de ESPHome #13956, "LD2450 stops detecting randomly after x hours" (ESPHome 2025.4.2, Apollo MTR-1). De tres sensores, solo uno falla. "Factory reset and none helped. They fixed it temporarily" — [esphome#13956](https://github.com/esphome/esphome/issues/13956)
- [COMUNIDAD] Issue de ESPHome #10624, "LD2450 values sticking" (2025-09-06): las distancias se quedan en valores distintos de cero durante largos periodos. Las versiones 25.6.18.1 → 25.8.20.2 que cita parecen ser del firmware del dispositivo Apollo y no del LD2450 (inferencia). El título del issue #11912 indica que el componente LD2450 recibió cambios de throttle en el PR #10196 — [esphome#10624](https://github.com/esphome/esphome/issues/10624), [esphome#11912](https://github.com/esphome/esphome/issues/11912)
- [PRIM] Hardware: conector tipo socket y pines de 2,54 mm (dos interfaces equivalentes) más la antena de Bluetooth (§6-§7) — [HLK Manual V1.00](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf) §4, [HLK User Guide](https://d.hlktech.net/download/HLK-LD2450/1/HLK-LD2450%20operation%20manual.doc..pdf) §4.4

**Alternativas** (precios aproximados y volátiles)

| Módulo | Especificaciones clave | Precio aprox. (USD) | Fuente y confianza |
|---|---|---|---|
| **HLK-LD2450** | 24-24,25 GHz FMCW, 1T2R, 250 MHz, 3 objetivos, 6 m, ±60°/±35°, 10 Hz, 5 V/120 mA | ~6-9 en listados de AliExpress (hay precios < 1 USD que casi seguro son accesorios o errores) | [HLK Manual V1.00](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf) [PRIM]; [AliExpress listing](https://www.aliexpress.us/item/3256806081010903.html) [RETAIL] |
| **Ai-Thinker RD-03D** | 24 GHz, 1T2R, 0,25 GHz de barrido, 15×44 mm, resolución 0,75 m, precisión 0,15 m, ±60° azimut / ±30° elevación, hasta 8 m, 5 V (fuente ≥200 mA, 92 mA típicos), -40 a 85 °C. **Mismo formato de trama que el LD2450** (cabecera, cola, 8 B/objetivo, signo-magnitud, comandos 0x0080/0x0090). ESPHome descarta las velocidades ±248 y ±256 cm/s como centinelas (`SPEED_SENTINEL_248/256`) | LCSC 6,49 (1 ud) → 4,56 (100+); DigiKey 14,45; OpenELAB 5,99-9,69 | Specs vía [openelab](https://openelab.io/blogs/learn/what-is-the-ai-thinker-rd-03d) / [rees52](https://rees52.com/products/ai-thinker-rd-03d-24g-multi-target-human-movement-trajectory-positioning-and-tracking-radar-module-rs5728) [RETAIL/SNIPPET]; protocolo [ESPHome rd03d.cpp](https://raw.githubusercontent.com/esphome/esphome/dev/esphome/components/rd03d/rd03d.cpp) [CÓDIGO]; precios [LCSC](https://www.lcsc.com/product-detail/Sensor-Modules_Ai-Thinker-Rd-03D_C18724179.html), [DigiKey](https://www.digikey.com/en/products/detail/ai-thinker/RD-03D/24614840) [RETAIL/SNIPPET] |
| **HLK-LD2461** | Dos chips 1T2R ("2T4R"), 24,0-24,25 GHz, 5 trayectorias, estáticos 5 m / micro-movimiento sentado 6 m / en movimiento 8 m, ±45° H / ±25° V, precisión en distancia 0,1 m, resolución 0,75 m, precisión angular 2°, resolución angular 15°, 5 V ~260 mA | Alibaba 14,50 (muestra) / 13,49 (≥1000); OpenELAB 25,59 | [HLK LD2461](https://www.hlktech.com/en/Goods-225.html) [PRIM, página del fabricante]; precios [Alibaba](https://www.alibaba.com/product-detail/Hi-Link-mmWave-HLK-LD2461-Human_1601082215353.html), [OpenELAB](https://openelab.com/products/hlk-ld2461-24ghz-radar-sensor) [RETAIL/SNIPPET] |
| **HLK-LD2451** | Detección de vehículos: 24-24,25 GHz FMCW, 250 MHz, 10-100 m ajustable, ±20° horizontal, velocidad de 1 a 240 km/h, hasta 5 vehículos, 5 V con fuente de más de 300 mA, 35×70 mm, salida serie ASCII + GPIO | No encontrado | [Laskakit](https://www.laskakit.cz/en/hi-link-hlk-ld2451-24ghz-radarovy-senzor-rychlosti-vozidla/), [paradisetronic](https://paradisetronic.com/en/products/ld2451-24ghz-vehicle-detection-radar-zur-fahrzeugerfassung-und-geschwindigkeitsmessung-bis-100m-reichweite-fmcw-uart-gpio-integriertes-bluetooth-setup), [manuals.plus](https://manuals.plus/hi-link/hlk-ld2451-vehicle-status-detection-module-manual) [RETAIL/SNIPPET] |
| **TI IWR6843AOP (EVM)** | 60-64 GHz, 3 TX / 4 RX con antenas en el encapsulado, ~120°×120° de FOV, 4 GHz de ancho de banda, Arm Cortex-R4F a 200 MHz + DSP C67x a 600 MHz, nube de puntos por USB | EVM: 149-215; DigiKey 187,74 | [TI IWR6843AOP](https://www.ti.com/product/IWR6843AOP), [AllAboutCircuits](https://www.allaboutcircuits.com/new-industry-products/texas-instruments-iwr6843aopevm-antenna-on-package-aop-evm-new-product-brief/), [DigiKey](https://www.digikey.com/en/products/detail/texas-instruments/IWR6843AOPEVM/12165115) [RETAIL/SNIPPET] |

- [COMUNIDAD] Sobre el LD2461, wojciech6789 (2024-01-24): "there is super ghosting, literally ghost are walking in empty room, targets are duplicated, reflected etc." — [HA indoor positioning](https://community.home-assistant.io/t/affordable-and-simple-indoor-positioning-system-using-multiple-ld2450-mmwave-radar-sensors/659634)
- [RETAIL/SNIPPET] Screek anuncia un **HLK-LD2460** como "improved LD2461", con 5 objetivos y un chip de segunda generación (solo el título del artículo, no leído) — [Screek LD2460](https://screek.io/4a/latest-radar-hlk-ld2460-improved-ld2461-supports-five-targets-uses-second-generation-chip-more-powerful-antenna-for-multiplayer-tracking-radar-more-powerful-detection-capability-compared-to-ld2450)

### Inferences
- **Recomendación de firmware para Blindside:** registrar la versión de cada unidad con `A0` al arrancar. No actualizar en caliente. Si se actualiza a ≥ 2.14, verificar y restablecer 256000 baud y reprobar el signo de ejes y velocidad y el valor de resolución, porque nada de eso está garantizado entre versiones.
- **Watchdog obligatorio** por los reportes de 2023 y 2025 de "deja de emitir tras horas": si el ESP32 no recibe una trama válida en unos 1-2 s, enviar `FF` (enable) y luego `A3` (restart). Si tampoco responde, cortar la alimentación con un MOSFET de lado alto.
- Seguridad en partida: el BT del módulo viene encendido de fábrica y con HLKRadarTool se pueden cambiar parámetros y hacer OTA desde cerca. **Apagarlo** (`A4` con valor 0x0000 y luego restart); persiste tras apagar el módulo.
- **RD-03D** como plan B o complemento: comparte formato de trama y el parser sirve casi igual, tiene menos elevación (±30°), consume menos (92 mA) y cuesta menos. Hay que tener en cuenta los centinelas de velocidad que filtra ESPHome.
- **LD2461** mejora el ángulo (2° de precisión) y el número de objetivos (5). Con ±45° harían falta 2 unidades para 180° sin solape, o 3 con margen, a unos 260 mA cada una, y hay reportes de mucho ghosting. No compensa como sustituto directo sin probarlo.
- **LD2451** no sirve: ±20°, 10-100 m, pensado para vehículos.
- **IWR6843AOP**: su nube de puntos permitiría estimar la velocidad propia a partir del clutter estático y compensarla, la limitación central del caso corporal (§4). Pero cuesta unas 20 veces más, requiere firmware propio con el SDK de TI y consume bastante más (no verificado aquí).

### Gaps
- No se encontró un changelog oficial de firmware del LD2450 entre V2.04 y V2.14.
- No hay datos de revisiones de PCB.
- El precio del LD2451 y las hojas de datos oficiales del LD2451 y del RD-03D no se verificaron (el sitio de Ai-Thinker dio error de conexión).
- Los precios de AliExpress son muy volátiles y algunos listados son engañosos.

---

## 10. Lado ESP32: dos UART a 256000 baud (UART1 remapeada + UART2), fiabilidad, buffers y pines a evitar en el ESP32-WROOM-32

### Takeaway
El ESP32 tiene 3 UART. La UART0 (GPIO1/3) queda para USB y consola. La **UART2 por defecto (RX 16, TX 17) sirve** en el WROOM-32. La **UART1 por defecto (RX 9, TX 10) usa pines del flash SPI y hay que remapearla** por la matriz GPIO. Evitar GPIO6-11 (flash) y los strapping pins 0, 2, 5, 12 y 15 para las líneas TX de los radares, que en reposo están en alto; **GPIO12 es el más peligroso**, porque debe estar en bajo al arrancar. La carga real es de 600 B/s en total, trivial. Hay que fijar el buffer de RX **antes** de `begin()` (256 B por defecto; subirlo a 1-2 KB) y usar UART por hardware.

### Cited Findings
- [PRIM] "The ESP32 chip has 3 UART controllers (also referred to as port)." Las señales van "directly via the IOMUX" en los pines por defecto y "Otherwise the GPIO and signal will be connected via the GPIO Matrix". "Some UART ports have dedicated IO_MUX pins to which they are connected directly. These can be useful if you need very high UART baud rates." Requisito del driver: "Rx_buffer_size should be greater than UART_HW_FIFO_LEN(uart_num)." — [ESP-IDF UART](https://docs.espressif.com/projects/esp-idf/en/stable/esp32/api-reference/peripherals/uart.html)
- [PRIM] Arduino-ESP32 HardwareSerial: "Default RX buffer size is 256 bytes"; "Default TX buffer size is 0 (no buffering)"; `setRxBufferSize` "must be called **before** `begin()` to take effect". `setRxFIFOFull()` acepta "Number of bytes (1-127) that will trigger the FIFO full interrupt". Con `setRxTimeout()`, "When RX timeout occurs, the `onReceive()` callback is triggered", y con `onlyOnTimeout = false` el callback salta al llegar al umbral de FIFO o por timeout. Los pines se asignan con `begin(baud, config, rxPin, txPin)` — [Arduino-ESP32 Serial](https://docs.espressif.com/projects/arduino-esp32/en/latest/api/serial.html)
- [PRIM, secundaria fiable] Pines por defecto: "UART0: GPIO 1 (TX), GPIO 3 (RX); UART1: GPIO 10 (TX), GPIO 9 (RX)", estos últimos "connected to the ESP32 SPI flash memory"; "UART2: GPIO 17 (TX), GPIO 16 (RX)". "To use UART1 to communicate with other devices, you must define different pins using the HardwareSerial library." — [RNT ESP32 pinout](https://randomnerdtutorials.com/esp32-pinout-reference-gpios/)
- [PRIM, secundaria fiable] "GPIO 6 to GPIO 11 ... are connected to the integrated SPI flash on the ESP-WROOM-32 chip and are not recommended for other uses." Strapping pins: "GPIO 0 (must be LOW to enter boot mode), GPIO 2 (must be floating or LOW during boot), GPIO 5 (must be HIGH during boot), GPIO 12 (must be LOW during boot), GPIO 15 (must be HIGH during boot)". "GPIOs 34 to 39 are GPIs – input only pins. These pins don't have internal pull-up or pull-down resistors." Pines con pulsos o nivel alto en el arranque: 0, 1, 3, 5, 6-11, 14 y 15 — [RNT ESP32 pinout](https://randomnerdtutorials.com/esp32-pinout-reference-gpios/)
- [PRIM] ESPHome: "Hardware UART highly recommended for proper support of default 256000 baud rate" — [ESPHome docs LD2450](https://esphome.io/components/sensor/ld2450/)
- [COMUNIDAD] TillFleisch recomienda "increase `rx_buffer_size`" si se pierden actualizaciones en configuraciones con varios componentes — [TillFleisch/ESPHome-HLK-LD2450](https://github.com/TillFleisch/ESPHome-HLK-LD2450)
- [COMUNIDAD] Ejemplo funcional con un LD2450: `Serial2` con RX=GPIO16 y TX=GPIO17 a 256000 y `setRxBufferSize(1024)` — [ShillehTek](https://shillehtek.com/blogs/news/esp32-ld2450-live-web-radar-tracking)
- [CÓDIGO] ESPHome acumula bytes en un buffer con protección de desborde (`if (this->buffer_pos_ < MAX_LINE_LENGTH - 1)`). Detecta una trama de datos por la cola `55 CC` con `buffer_pos_ ≥ 29` y un ACK por la cola `04 03 02 01` — [ESPHome ld2450.cpp](https://raw.githubusercontent.com/esphome/esphome/dev/esphome/components/ld2450/ld2450.cpp)

### Inferences
- **Mapa de pines propuesto para ESP32-WROOM-32 (LAFVIN DevKit):**
  - Radar izquierdo → **UART2**: RX = **GPIO16** (TX del radar), TX = **GPIO17** (RX del radar).
  - Radar derecho → **UART1 remapeada**: RX = **GPIO26** (o GPIO34/35, solo entrada, válidos para RX), TX = **GPIO27** (o GPIO25).
  - IMUs MPU6050 por I2C en GPIO21 (SDA) y GPIO22 (SCL), con direcciones 0x68 y 0x69 según el pin AD0 (conocimiento general, no verificado aquí).
  - Dejar libres GPIO0, 2, 5, 12, 15 y 6-11; no usar GPIO1/3.
  - **No conectar nunca el TX de un radar a GPIO12**: la línea UART en reposo está en alto y el ESP32 exige GPIO12 en bajo al arrancar, así que el arranque falla.
- Con RX en GPIO34-39 (sin pull-up interno), un radar desconectado deja la entrada flotando y genera bytes basura. El parser tiene que resincronizar por cabecera y cola y descartar por plausibilidad (§1), o añadir un pull-up externo de 10 kΩ.
- Margen de temporización: a 256 kbaud llega un byte cada 39 µs. El rango 1-127 de `setRxFIFOFull` sugiere una FIFO de hardware de 128 bytes, unos 5 ms de datos continuos (el TRM no se leyó para confirmarlo). El ISR del driver vacía la FIFO al ring buffer. Con 256 B por defecto caben unas 8 tramas (~850 ms). Subir a 1-2 KB por puerto da holgura frente a pausas de la pila BLE. Remapear por la matriz GPIO no es problema a 256 kbaud, porque ESP-IDF reserva el consejo del IO_MUX para baud "very high".
- Sello de tiempo: `onReceive` con `setRxTimeout` corto (unos pocos símbolos) dispara al terminar cada ráfaga de 30 bytes. Tomar `esp_timer_get_time()` en ese callback, buscar la trama completa (`AA FF 03 00 ... 55 CC`, 30 B) en el buffer y enviar al reloj [id_radar, timestamp_µs, 30 bytes crudos]. Quedan unos 35-40 B por trama y en total ~20 tramas/s, alrededor de 800 B/s por BLE.
- Una observación ajena a la investigación del LD2450 que conviene verificar: el ATT MTU por defecto de BLE (23 B, 20 de carga útil) no alcanza para una trama. Hay que negociar un MTU mayor desde el reloj o fragmentar.
- Fiabilidad: no se encontraron reportes de pérdida de datos en ESP32 con dos LD2450. La carga es de ~1,2 % de cada UART. El riesgo real está en la sincronización del parser y en el watchdog del módulo (§9), no en el ancho de banda.

### Gaps
- No hay reportes específicos de dos LD2450 en un mismo ESP32 con BLE activo.
- El tamaño exacto de la FIFO de hardware (128 B) no se verificó en el Technical Reference Manual del ESP32.
- No se verificó la precisión del divisor de baudios del ESP32 a 256000.
