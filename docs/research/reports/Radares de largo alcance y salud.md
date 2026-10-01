# Blindside: radares para pasar de 6 m a 15–25 m

*Informe para Santiago · 2026-10-01 · Blindside (radar vestible de airsoft: hoy 2× HLK-LD2450 → ESP32-WROOM-32 → BLE → Galaxy Watch 7)*

**Pregunta:** ¿con qué radares podemos reemplazar los LD2450 para llegar a 25 m, o al menos a 15 m, sin que sean nocivos para la salud?

**Leyenda de marcas:**
- **[V]**: verificado en fuente primaria (datasheet, página del fabricante o del distribuidor).
- **[B]**: dato de un buscador o de una tienda; no se abrió la fuente.
- **[C]**: cálculo propio hecho con datos [V].
- **[E]**: estimación o interpretación propia.

Precios y stock de DigiKey al 2026-10-01, salvo que se indique otra cosa.

---

## 1. Respuesta corta

- **Ningún módulo barato tipo Hi-Link o Ai-Thinker trackea personas a 15 m o más.** Todos los de 24 y 60 GHz con tracking se quedan en 5–12 m: LD2460 6 m, LD2461 8 m, RD-03D 8 m, Rd-60/61 8 m, IR60TR1A 12 m. Para pasar de ahí hay que ir a **RFbeam** (Suiza, 24 y 60 GHz) o al chip **TI IWRL6432** (60 GHz).
- **15 m: hay reemplazo vestible hoy mismo.** A 25 m con tracking, en cambio, lo vestible solo cubre un **sector angosto** (K-MD7, ±30°). No encontré ningún módulo vestible que junte FoV ancho, tracking y 25 m documentados. Los que ven personas a 50 m con FoV ancho (TI IWR6843) consumen 1,5–2 W cada uno.
- **Salud: todos los candidatos vestibles son seguros si la antena mira hacia afuera.** Ninguno pasa de 20 dBm (100 mW) PIRE, que es el tope legal en Colombia. A 1 m eso son 0,008 W/m², unas **1250 veces menos** que el límite ICNIRP de cuerpo entero (10 W/m²) [C]. El detalle está en el §3.
- **El riesgo técnico n.º 1 es el movimiento del propio jugador, no el alcance.** Todos los trackers suponen que el sensor está quieto (§5.2).

### 1.1 Recomendación por nivel

| Meta | Qué usar | Por qué | Costo | Ojo con |
|---|---|---|---|---|
| **15 m ya, con el mismo ESP32** | 2× **RFbeam K-LD7** en lugar de los 2 LD2450 | 15 m para persona (σ = 1 m²), 80°×34°, 5 g, UART simple [V] | 2 × USD 70,95 = **USD 141,90** [V/C] | Sigue **1 solo objetivo** (para varios hay que agrupar su lista cruda en el ESP32). Solo ve lo que se mueve. **0 en stock; llegan 100 unidades el 29-oct-2026** [V] |
| **15–20 m con varios objetivos** (dirección principal) | **RFbeam V-LD3** (chip TI IWRL6432). Prototipar antes con **TI IWRL6432BOOST** | Hasta 20 m, 110°×50°, tracking multiobjetivo y clasificador humano/no humano en el chip, 6 g, 2 mm de espesor [V] | BOOST **USD 193,75** (7 en stock) [V]. El V-LD3 no tiene precio público: cotizar en info@rfbeam.ch | **No tiene certificación modular**: CE se evalúa en el producto final y la FCC modular está "planned" [V]. Alimentación de 1,8 V con picos de 1,36 A [V]. El clasificador de TI está entrenado hasta 10 m [B] |
| **25 m al frente** | **RFbeam K-MD7** como "ojo largo" frontal | 50 m para persona (σ = 1 m²), 8 tracks, 11 g, 0,18–0,58 W [V] | **USD 212,68** (94 en stock) [V] | FoV de ±30°: para 360° harían falta 6 o más. Solo ve lo que se mueve |
| **25 m con FoV ancho** (solo si aceptás 1,5–2 W por sensor) | **Joybien BM201-LPD** (IWR6843 con firmware de largo alcance ya cargado) | Personas de 1 a 50 m, 108°×44°, módulo de 15 g, UART [V] | **USD 420** en eBay [B] | Consumo alto. En EE. UU., un IWR6843 de uso móvil tiene que bajar a ≤14 dBm (§4) |
| **Experimento de 25 m por menos de USD 30** | **DFRobot C4001** + **HLK-LD2451** | Sirven para medir en campo hasta dónde ven a un caminante con el radar puesto | USD 13,90 [V] + EUR 11,89 [B] | El C4001 da 1 objetivo, sin ángulo, y su distancia "no está calibrada" [V]. El LD2451 trae firmware para vehículos, ve ±20° y no documenta alcance a personas [V] |

### 1.2 Modo Halo (360°, 3–5 módulos alrededor del torso)

| Opción | N.º | Costo de los radares | Consumo promedio | Peso de los radares | Alcance en las uniones | Comentario |
|---|---|---|---|---|---|---|
| **A. 4× TI IWRL6432WMOD** (SPI compartido) | 4 | Módulo sin precio (estado PREVIEW). EVM ~USD 186 [B] | 4 × 54–59 mW ≈ **0,22–0,24 W** [C] | n/d (31×15,5 mm cada uno) | **12–13 m a ±45°**. Prueba de TI: 15 m a 0°, 13/12 m a ±45°, 9/7 m a ±60° [V] | El de menor consumo. El tracking lo hace el host (ESP32-S3) |
| **B. 4–6× RFbeam V-LD3** (UART) | 4 (5–6 para tener ~20 m también en las uniones) | A cotizar | **0,2–0,6 W** [E] | **24–36 g** [C] | Entre ~10 y 20 m [E]. En el borde de los 110° el alcance cae a la mitad [V] | Tracking en el chip. Pide 4–6 UART |
| **C. 5× K-LD7** (se compra hoy) | 5 | **USD 354,75** [C] | **0,3–1,7 W** [C] | **25 g** [C] | ~11–13 m [E] | Barato, pero 1 track por módulo y solo ve movimiento |
| **D. Híbrido**: A o B + 1 K-MD7 al frente | 5–7 | A o B + USD 212,68 | + 0,18–0,58 W | + 11 g | 50 m en el sector frontal | **Meta recomendada** [E]: 360° a 12–20 m y alcance largo hacia adelante |

**Descartados para el Halo:**
- 4× IWR6843ISK: USD 1132 y 6–8 W [C/E].
- 6× K-MD7: USD 1276, 66 g y 1,1–3,5 W [C].
- 3× InnoSenT iSYS-5005: **descontinuado** [V].

**Plan sugerido [E]:**
1. Ahora: comprar 1 C4001, 1 LD2451 y 1 K-MD7 (o 1 K-LD7 cuando vuelva el stock). Con eso se mide el alcance real a un caminante con el radar en el cinturón y el jugador moviéndose.
2. Prototipar el Halo con 1 IWRL6432BOOST (USD 193,75) para validar el tracking con el portador en movimiento.
3. Después de eso: cotizar 4–6 V-LD3, o esperar a que el IWRL6432WMOD salga de PREVIEW.

### 1.3 Por qué cuesta tanto pasar de 6 a 25 m

- **El alcance crece con la raíz cuarta del presupuesto de enlace.** Pasar de 6 a 15 m exige unos +16 dB; de 6 a 25 m, unos +25 dB [C].
- **La PIRE está topada en 20 dBm** (ANE, ETSI, FCC). El margen extra tiene que salir de antenas más directivas (haz más angosto) y de más tiempo de integración. Por eso se cambia FoV por alcance: el K-MD7 llega a 50 m porque su haz mide 30° y su antena da 12,2 dBi [V/E].
- **El LD2450 está muy lejos de ese tope.** La FCC lo midió en −13,2 dBm PIRE [V], unos 33 dB por debajo de 20 dBm [C]. Ese margen es el que aprovechan los candidatos de largo alcance.
- **Las cifras de los datasheets son de laboratorio:** σ ≈ 1 m², persona de frente y caminando, y el sensor fijo. Si la persona está agachada, de perfil o entre vegetación, o si el radar se mueve, el alcance baja [E].

### 1.4 Comprar desde Colombia

- En MercadoLibre Colombia solo aparecen los chinos baratos (LD2450 y similares).
- RFbeam, TI e InnoSenT se traen por DigiKey o Mouser, que sí envían a Colombia. Aranceles e IVA los paga el comprador.
- Desde 2026 se reporta un IVA del 19 % para compras internacionales de más de USD 50 (Decreto 1390, **pendiente de confirmar**).
- Los módulos suizos, alemanes o chinos no aplican al TLC con EE. UU.

---

## 2. Tabla comparativa (datos corregidos por el verificador)

### 2.1 Candidatos vestibles: rendimiento

| Modelo (fabricante) | Banda | Alcance a persona | FoV | Salida / tracking | PIRE |
|---|---|---|---|---|---|
| **LD2450** (actual, de referencia) | 24,014–24,244 GHz [V] | ~6 m | ±60° | 3 objetivos | −13,2 dBm, medido por la FCC [V] |
| **V-LD3** (RFbeam, TI IWRL6432) | Configurable 57–64 GHz; antena para 57–61,5 (RFbeam no recomienda 61–64) [V] | Hasta 20 m, "human detection and multi-object tracking" (marketing; RCS no especificado) [V]. ~10 m en el borde del FoV [V] | 110°×50° a −12 dB [V]. **La orientación se confirma en banco**: la tabla y la figura 3 no coinciden [V] | Nube de puntos 3D, tracking multiobjetivo y clasificador humano en el chip; tramas TLV de TI [V]. N.º de tracks no publicado (la demo de TI usa 5 [B]) | 20 dBm típ.; antena de 9 dBi [V] |
| **IWRL6432WMOD** (TI) | 57–61,5 GHz [V] | Presencia típica: 15 m a 0°, 13/12 m a ±45°, 9/7 m a ±60° [V] | ±60° / ±60° [V] | Solo nube de puntos (SPI) o presencia (GPIO); **sin tracker** [V] | 19 dBm (FCC), 10 dBm (MIC); antena de 4,4–6 dBi [V] |
| **IWRL6432BOOST / AOPEVM** (TI, para prototipar) | 57–64 GHz [V] | El chip llega "typically up to 25 m" y el diseño de referencia TIDEP-01033 detecta peatones a 15 m [V]. La demo de people tracking viene configurada a ~10 m [B] | AOP ~±60° [E] | Demo: hasta 5 tracks + clasificador [B, foro TI] | 11 dBm conducidos por TX [V]. PIRE ~16–18 dBm [E] |
| **K-LD7** (RFbeam) | 24,05–24,25 GHz [V] | 15 m para persona (σ = 1 m²) y 30 m para auto; tracking hasta 30 m [V] | 80°×34° a −3 dB [V] | Lista cruda (PDAT) + **1 track** (TDAT); solo movimiento [V] | 6 dBm típ., 10 dBm máx.; antena de 8,6 dBi [V] |
| **K-MD7** (RFbeam) | 24,075–24,175 GHz [V] | **50 m para persona** (σ = 1 m²); tracking de 1 a 300 m [V] | TX 30°×30°, RX 46°×30°; ángulo válido ±30° [V] | 24 objetivos crudos + **8 tracks**; solo movimiento [V] | 20 dBm; antena de 12,2 dBi [V] |
| **C4001 / SEN0609** (DFRobot) | 24 GHz FMCW [V] | Movimiento hasta 25 m y presencia hasta 16 m; mide de 1,2 a 25 m (viene en 6 m por defecto) [V] | 100°×40° [V] | **1 objetivo, sin ángulo**; distancia "not calibrated" [V] | n/d |
| **HLK-LD2451** (Hi-Link) | 24–24,25 GHz FMCW [V] | Vehículos hasta 100 m (configurable de 10 a 100) [V]. Personas: sin dato; hay que medirlo (15–30 m [E]) | ±20° [V] | Ángulo, distancia, velocidad y dirección. N.º de objetivos no documentado (~5 según tiendas [B]) | n/d (tiene FCC y CE) |
| **IWR6843ISK + laboratorio LRPD** (TI) | 60–64 GHz [V] | Personas hasta 50 m con el perfil 2D [V, foro TI]. Hay un perfil de 100 m con beamforming de 3 TX [V] | 120°×30° [V] | Group tracker en el chip; TLV [V] | ~19 dBm por TX [E]. Con beamforming, ~28 dBm [E] |
| **BM201-LPD** (Joybien, IWR6843) | 60–64 GHz [V] | Personas de 1 a 50 m [V] | 108°×44° [V] | Firmware LPD: "Key Data" (tracks y conteo) o "Raw" [V] | ~19 dBm por TX [E] |
| **IWRL6844** (TI, a futuro) | 57–64 GHz [V] | "20 m de conteo" según TI [B, sin verificar] | n/d (~120° [E]) | n/d | 12,5 dBm típ. por TX [V] |
| **A212 / EVK XE212** (Acconeer, a futuro) | 60 GHz PCR [V] | "Absolute range up to 23 m", según el objeto [V] | 120°, resolución de 10°, 3TX/4RX [V] | Conteo y clasificación (marketing) [V] | n/d |

### 2.2 Candidatos vestibles: integración y compra

| Modelo | Interfaz | Alimentación y consumo | Tamaño y peso | Precio y stock (2026-10-01) | Estado |
|---|---|---|---|---|---|
| V-LD3 | UART (también SPI, I2C y CAN-FD); IO a 1,8 o 3,3 V; FFC de 10 pines o SMT de 24 [V] | 1,8 V; Icc máx. 1360 mA + IO 90 mA [V]. Promedio de 50–100 mW [E]. Usa la topología "BOM optimized": 1290 mW activos con 2TX/3RX [V] | 20×25×2 mm, 6 g [V] | Sin precio público; el kit no aparece en DigiKey ni en Mouser [V] ("EUR 172" no tiene respaldo) | Active; datasheet Rev. A de 05/2026; **sin certificación modular** [V] |
| IWRL6432WMOD | SPI + GPIO; el UART solo sirve para parches [V] | 3,3 V única; 54–59 mW a 15 m / 5 Hz; pico de 1 A [V] | 31×15,5 mm; peso n/d [V] | EVM ~USD 186 [B] | ti.com dice "Preview", el datasheet dice "Production Data" [V]. Certificación modular FCC/RED/MIC [V] |
| IWRL6432BOOST | UART por USB [V] | Chip a ~0,05–0,1 W con tracking, más el puente USB [E] | Placa tipo BoosterPack; no es vestible [E] | **USD 193,75**, 7 en stock [V]. AOPEVM a USD 174 [B] | Active |
| K-LD7 | UART a 115200 + 4 salidas [V] | 3,2–5,5 V; 20–60 mA RMS (~0,06–0,33 W); pico de 116–200 mA [V] | 37×25×13,5 mm, 5 g [V] | **USD 70,95 y 0 en stock**; 100 unidades el 29-oct-2026 [V]. Mouser USD 98,56 [B] | Active |
| K-MD7 | UART a 3,3 V + 1 salida "Detection out"; las otras 3 E/S están reservadas [V] | 3,2–5,5 V; 55–105 mA RMS (~0,18–0,58 W); pico de 180–250 mA [V] | 70×32×13,5 mm, 11 g [V] | **USD 212,68, 94 en stock** [V] | Active |
| C4001 | UART (9600 o 115200: la documentación se contradice) + OUT [V] | 3,3 o 5 V. Consumo no publicado (≤100 mA según Cirkit [B]) | 26×30 mm [V] | **USD 13,90** en DFRobot [V] | Active |
| HLK-LD2451 | UART a 115200, IO a 3,3 V, 2 GPIO y BLE [V] | 5 V; 107 mA promedio (~0,54 W); fuente de más de 300 mA [V] | 70×35 mm [V] | EUR 11,89 en Paradisetronic; USD 5–10 en AliExpress; también en Amazon US [B] | Active |
| IWR6843ISK | 2 UART: CLI a 115200 + datos a ~921600 [E] | ~1,5 W típ., ~2 W máx. [V, foro TI] | Placa EVM | USD 282,98, **0 en stock** (ETA 24-dic-2026) [V] | Active |
| BM201-LPD | 2 UART, 2 SPI, I2C y GPIO; SDK en Python [V] | El Pi-hat pide 5 V / 2,1 A por micro-USB [V]. Consumo real de 1,5–2 W [E] | Módulo de 67×46×2 mm y 15 g; Pi-hat de 23 g [V] | USD 420 en eBay [B] | Proveedor pequeño, sin datasheet de RF |
| IWRL6844 | UART, SPI, CAN-FD [V parcial] | <0,5 W promedio [V] | Solo existe el EVM [V] | n/d | Active; sin módulo compacto |
| A212 | n/d | n/d (la familia PCR consume muy poco [E]) | n/d | EVK solo para clientes seleccionados; ~USD 247 [B] | Disponibilidad general "later in 2026" [V] |

### 2.3 Descartados

| Modelo | Motivo |
|---|---|
| InnoSenT iSYS-5005 | **Descontinuado**: DigiKey lo marca "Obsolete" y su página en innosent.de da 404 [V]. Era la mejor opción de tracking listo por UART (±75°, 8 tracks, hasta 20 m) [V] |
| InnoSenT iSYS-5020/5021 | Ve personas a 120 m, pero mide 101×79×12,4 mm y consume hasta 6,25 W. El kit del 5021 cuesta USD 936,86 [V] |
| RFbeam V-MD3 | Llega a 30 m y ve personas quietas, pero cuesta USD 1246,75, usa Ethernet y pide 8–32 V [V] |
| TI IWR6843AOPEVM | Según TI, le cuesta pasar de 20–25 m. Consume 1,6–2,8 W. 0 en stock [V, foro TI / DigiKey] |
| Infineon BGT60TR13C | 15 m de frente y tracking hasta 10 m, sin tracker en el chip. El DEMO está sin stock y sin backorder [V] |
| Infineon BGT60ATR24C | Máximo 15 m y shield descontinuado [B] |
| Acconeer XM125 | ~7 m para personas y sin ángulo; sus "20 m" son para reflectores [V] |
| Nanoradar SP70C | Sin datasheet. Los ~40 m y el FoV no tienen fuente [B] |
| Hi-Link LD2460 / LD2461 / LD6001 / LD6004 | 6 m / 8 m (descontinuado) / 8 m / 6 m [V/B] |
| Ai-Thinker RD-03D y Rd-60/Rd-61 | 8 m para personas [B] |
| IR60TR1A y MicRadar R60ATT1 | 12 m y 5 m [B] |
| Murata Type1VN / Type2LP | Existen (40–90 m a 0°), pero un aficionado no los consigue con facilidad [B] |
| Cualquier radar de 76–81 GHz (AWR/IWR1443, 1843…) | **Ilegal llevarlo en el cuerpo**: en Colombia y EE. UU. esa banda es solo para vehículos (§4) |

---

## 3. Salud

### 3.1 Veredicto en lenguaje simple

- **No ionizante.** Los radares de 24 y 60 GHz no rompen moléculas ni ADN. Su único efecto comprobado es calentar, y por encima de 6 GHz solo calientan la superficie de la piel: a 24 GHz penetran ~1,2 mm y a 60 GHz ~0,5 mm [V/E, ICNIRP].
- **El LD2450 que usamos hoy** emite −13,2 dBm PIRE (~0,05 mW), según la medición de la FCC [V].
  - Pegado a 1 cm da 0,038 W/m², el **0,12 %** del nivel de referencia ICNIRP a 24 GHz [C].
  - El Bluetooth del propio ESP32 (+9 dBm) emite ~166 veces más que ese radar [C].
- **Cualquier candidato de hasta 20 dBm (100 mW) con la antena hacia afuera es seguro.**
  - En el eje del haz baja de 10 W/m² a partir de 2,8 cm [C].
  - A 1 m (otro jugador) da 0,008 W/m²: ~1250 veces menos que el límite de cuerpo entero y ~3300 veces menos que el nivel local a 60 GHz [C].
  - Al portador le llega solo el lóbulo trasero más lo que deje pasar el escudo, unas 1000 veces menos que de frente [E].
- **Ojos.** Incluso 100 mW apuntando a 5 cm del ojo dan 0,32 mW/cm² [C]:
  - ~157 veces por debajo del nivel que no causó daño en conejos (50 mW/cm² durante 6 min).
  - ~733 veces por debajo de la dosis que daña la córnea con 50 % de probabilidad a 60 GHz (233 mW/cm²) [V].
- **Lo único que no conviene:**
  - Modos de beamforming con 3 TX (por ejemplo, el perfil de 100 m del IWR6843, ~28 dBm [E]).
  - Cualquier diseño en el que la antena apunte al cuerpo.
  - Los 77 GHz, aunque eso es un problema legal, no de salud.

### 3.2 Límites de referencia

| Norma | Qué limita | Valor |
|---|---|---|
| ICNIRP 2020, público general, más de 6 GHz | Densidad de potencia absorbida (restricción básica) | 20 W/m² sobre 4 cm² y 6 min. Por encima de 30 GHz, además, 40 W/m² sobre 1 cm² [V] |
| ICNIRP 2020 | Nivel de referencia local (55/f^0,177) | **31,3 W/m²** a 24 GHz; **26,6 W/m²** a 60 GHz (53,3 sobre 1 cm²) [C] |
| ICNIRP 2020 | Nivel de referencia de cuerpo entero, más de 2 GHz | **10 W/m²** promediado en 30 min [V] |
| IEEE C95.1-2019 | Armonizado con ICNIRP: 20 W/m² sobre 4 cm² | Su regla de pulsos (ventanas de 100 ms) es **más laxa** que el límite continuo, no más estricta. Por encima de 30 GHz rige también una fluencia por pulso de 0,2·√τ kJ/m². Con 100 mW a 1 cm y ráfagas de 1–10 ms queda ~25–80 veces de margen [V/C] |
| FCC 47 CFR 1.1310 | Público general, 1,5–100 GHz | 10 W/m² en 30 min. Los portátiles de más de 6 GHz se promedian sobre 4 cm² [V] |
| FCC 1.1307(b)(3)(i)(A) | Exención | Con ≤1 mW promedio queda exento de evaluación a cualquier distancia [V] |

Comparar contra 10 W/m² (cuerpo entero) es la opción **conservadora**. Para exposición local, ICNIRP admite ~27–31 W/m².

### 3.3 Densidad de potencia por candidato (peor caso: en el eje del haz y sin escudo)

Se usa la fórmula S = PIRE/(4πr²) [C].
- A pocos centímetros esta fórmula **sobreestima**, porque ahí todavía es campo cercano.
- Cota física: la densidad promediada sobre 4 cm² no puede superar la potencia conducida dividida por 4 cm².

| Módulo | PIRE | Distancia para bajar de 10 W/m² | A 10 cm (W/m²) | A 1 m, otro jugador (W/m²) |
|---|---|---|---|---|
| LD2450 (actual) | −13,2 dBm [V] | < 1 mm | 0,0004 | 0,000004 |
| K-LD7 | 6–10 dBm [V] | ≤ 0,9 cm | 0,03–0,08 | 0,0003–0,0008 |
| BGT60TR13C (referencia) | 7,5–13 dBm [C] | ≤ 1,3 cm | 0,04–0,16 | 0,0004–0,0016 |
| IWR6843AOP | 16 dBm [V] | 1,8 cm | 0,32 | 0,003 |
| IWRL6432WMOD | 19 dBm [V] | 2,5 cm | 0,63 | 0,006 |
| V-LD3 y K-MD7 | 20 dBm [V] | 2,8 cm | 0,80 | 0,008 |

Todos los valores de la tabla son picos con transmisión continua. Los FMCW reales emiten en ráfagas, así que el promedio en 6 o 30 min es menor [E].

RFbeam declara bajo EN IEC 62311 que sus módulos de ≤20 dBm cumplen el límite incluso en contacto [V].

### 3.4 Recomendaciones de montaje

1. **Antena siempre hacia afuera.** Ningún módulo debe apuntar al propio cuerpo.
2. **Escudo trasero** detrás de cada módulo [E]:
   - Material: placa continua de aluminio o cobre de más de 0,1 mm, conectada a GND.
   - Tamaño: que sobresalga al menos λ/2 por cada lado (~6 mm a 24 GHz, ~2,5 mm a 60 GHz).
   - Que no invada el FoV ni forme una cavidad pegada a la antena.
   - Efecto: baja la exposición del portador ~1000 veces o más, y además reduce los reflejos del torso (blancos fantasma y multitrayecto).
3. **Radome plástico separado 1–3 cm de la antena**, sin metal ni pintura metálica. Así nadie toca el campo cercano.
4. **Si algún día va en casco u hombro,** que no apunte a los propios ojos (aunque el peor caso tiene unas 157 veces de margen).
5. **No usar beamforming de 3 TX** ni subir la PIRE de 20 dBm. Como referencia, 1,9 W a 10 cm dan ~15 W/m², que ya se acerca al nivel local [C].
6. **Aprovechar el duty-cycle:** bajar la tasa de frames cuando no hay amenazas. Eso reduce la exposición y ahorra batería.
7. **Mientras sigan los LD2450,** apagar su Bluetooth interno con el comando serial `0x00A4`, valor `0x0000` (se guarda y se aplica al reiniciar) [V]. Ese BLE emite ~21 veces más que el radar del propio módulo [C].

### 3.5 Contexto OMS / IARC

- **IARC 2011:** clasificó la RF en el grupo 2B, "posiblemente carcinógeno". Es la misma categoría que el extracto de aloe vera y no es una prueba de daño [V].
  - Un grupo asesor recomendó reevaluarla entre 2025 y 2029; en lo que revisé no aparece en la agenda de 2026 [V parcial].
- **Karipidis 2024** (revisión para la OMS): no encontró asociación entre uso del móvil y cáncer cerebral [V secundario].
- **Mevissen 2025:** halló efectos en ratas expuestas a 900 MHz en cuerpo entero, con 1,5–6 W/kg durante 9 h al día por 2 años [V]. Eso es 20–75 veces el límite y a una frecuencia que penetra en profundidad, a diferencia de las ondas milimétricas.
- **Karipidis 2021**, revisión de RF de bajo nivel por encima de 6 GHz: no hay evidencia confirmada de efectos relevantes para la salud [V].

---

## 4. Legalidad

### 4.1 Colombia: Res. ANE 105/2020 compilada (hasta la Res. 153/2024), Anexo 1 de uso libre [V]

| Uso | Banda | Límite | Candidatos que caben |
|---|---|---|---|
| Radiodeterminación | 24,05–24,25 GHz | PIRE de 100 mW | K-LD7 |
| Corto alcance, no específicas | 24–24,25 GHz | PIRE de 100 mW | LD2450 (empieza en 24,014 GHz), LD2451, C4001 [E] |
| Sensores de perturbación de campo | 24,075–24,175 **GHz** | 2500 mV/m a 3 m (~1,9 W PIRE [C]) | K-MD7 |
| Corto alcance, no específicas | 57–64 GHz | PIRE de 100 mW y 13 dBm/MHz | V-LD3, IWRL6432, WMOD; IWR6843 solo en TDM (1 TX a la vez) |
| Solo sensores vehiculares | 76–81 GHz | — | **Ninguno vestible** |

- **Sin permiso:** no hace falta tramitarlo (art. 4.1.2).
- **Interferencia:** no se puede causar ni reclamar protección contra ella (art. 4.1.4).
- **Exposición:** el público no puede quedar expuesto por encima de las normas (art. 4.1.3). No encontré restricciones para equipos portátiles en estas bandas.
- **Res. ANE 773/2023:** adopta ICNIRP 2020 pero excluye los terminales de usuario, así que ICNIRP aplica solo por analogía [V].
- **Homologación CRC:** no encontré que aplique a un dispositivo de uso personal [E].

### 4.2 EE. UU.: FCC [V]

| Regla | Banda | Límite |
|---|---|---|
| 15.249 | 24,0–24,25 GHz | 250 mV/m a 3 m en promedio (~12,7 dBm PIRE [C]), con picos de hasta +20 dB. Aquí cabe el K-LD7 |
| 15.245 | 24,075–24,175 GHz (perturbación de campo) | 2500 mV/m a 3 m. Aquí cabe el K-MD7 [E] |
| 15.255 (reforma 2023) | 57–71 GHz | 10 dBm PIRE pico (−10 dBm conducidos), sin límite de ciclo de trabajo |
| 15.255 | 57–61,56 GHz | 20 dBm pico apagándose ≥16,5 ms de cada 33 ms (≤50 %), **también en uso móvil** |
| 15.255 | 57–64 GHz | 14 dBm pico apagándose ≥25,5 ms de cada 33 ms (≤22,7 %) |
| 15.255 | 57–64 GHz a 20 dBm | Solo instalaciones fijas en exterior o vehículos (no dentro del habitáculo) |
| 15.253 / Parte 95 subparte M | 76–81 GHz | Solo vehículos y radares fijos de aeropuerto |

El Report & Order 23-35 incluye además una fila de 57–59,4 GHz (20 dBm interior / 30 dBm exterior) y otra de 3 dBm en 57–61,56 GHz sin condiciones.

Consecuencias:
- **V-LD3 y WMOD:** están diseñados para 57–61,5 GHz, así que admiten hasta 20 dBm con duty ≤50 % aunque el equipo sea móvil.
- **IWR6843 (ISK, AOP, BM201):** sus chirps barren 60–64 GHz. En un equipo vestible quedan limitados a ≤14 dBm con duty ≤22,7 %, o a ≤10 dBm sin restricción, y por eso pierden alcance frente al datasheet [E].
- **15.23:** un aparato casero, no comercializado y de hasta 5 unidades no necesita certificación, pero sí tiene que cumplir los límites técnicos [V]. Es el caso de un Blindside casero con V-LD3, que no tiene certificación modular.

### 4.3 UE / CEPT [V]

- **ERC Rec 70-03 (edición de febrero de 2025):**
  - 24–24,25 GHz, corto alcance no específico: 100 mW.
  - 24,05–24,25 GHz, radiodeterminación: 100 mW.
  - 57–64 GHz: 100 mW PIRE con un **máximo de 10 dBm de potencia de salida**.
- **Decisión UE 2022/180:** la radiodeterminación en 57–64 GHz queda limitada a medidores de nivel en tanque (TLPR/LPR). Un radar vestible de 60 GHz entraría como "corto alcance no específico" [E].
- **Chips TI en la UE:** dan 11 dBm conducidos típicos por TX [V], así que habría que bajar ~1 dB la potencia de TX [C].

### 4.4 Por qué no 77 GHz

Las familias de 76–81 GHz (AWR/IWR1443, 1843…) tienen más ancho de banda y modelos de largo alcance, pero no se pueden llevar en el cuerpo:
- **Colombia:** la ANE (Res. 105/2020, §3.12) las autoriza solo como sensores montados en vehículos.
- **EE. UU.:** las reglas 15.253 y Parte 95 subparte M las permiten solo en vehículos y en radares fijos de aeropuerto [V].

Es una barrera **legal**, no de salud.

---

## 5. Cómo encaja en Blindside

Situación actual: 2× LD2450 (UART, 3 objetivos, ~6 m, ±60°) → ESP32-WROOM-32 → BLE → Galaxy Watch 7.

### 5.1 ¿Alcanza el ESP32-WROOM-32?

El WROOM-32 tiene 3 UART, pero la UART0 es la consola, así que **quedan 2 libres**. Hoy las ocupan los dos LD2450.

| Configuración | ¿Sirve el WROOM-32? | Qué hace falta |
|---|---|---|
| 2× K-LD7, o K-LD7 + K-MD7 | **Sí** (2 UART libres) | Solo el driver del protocolo RFbeam |
| 2× V-LD3 / IWRL6432 | Sí en cuanto a UART [E] | Un regulador de 1,8 V de más de 1,4 A pico por módulo, el parser TLV y soportar más tasa de datos |
| Halo de 4–6 módulos UART (V-LD3, K-LD7) | **No**: faltan UART | ESP32-S3 + puentes SC16IS752 (I2C/SPI), o un RP2040 con PIO como agregador |
| Halo de 4× IWRL6432WMOD (SPI) | El bus SPI alcanza; la CPU queda justa [E] | ESP32-S3 con PSRAM para clustering (DBSCAN) y tracking (Kalman / GTRACK simplificado) |
| IWR6843 (ISK / BM201) | No | 2 UART por sensor y 5 V / ≥1 A por sensor. Un ESP32-S3 o un MCU intermedio por sensor |
| BGT60TR13C (referencia) | No | Toda la cadena en un ESP32-S3 con PSRAM: FFT de rango y Doppler, ángulo, CFAR, clustering y tracking |

### 5.2 Firmware: qué cambia

- **Capa de drivers** [E]: cada radar entrega una lista normalizada `Track {sensor_id, x, y, vx, vy, clase, calidad}` en el marco del sensor.
  - **K-LD7 / K-MD7:** protocolo RFbeam documentado (INIT, GNFD, PDAT, TDAT) a 115200 baudios; el host pide cada trama. Como el K-LD7 trackea un solo objetivo, para seguir varios hay que agrupar su PDAT en el ESP32.
  - **V-LD3 / IWRL6432:** al arrancar se le envía la configuración CLI de TI; después llegan tramas TLV (magic word + TLV de nube de puntos y de tracks). Se puede portar a C el parser Python de TI.
  - **WMOD:** API por SPI. Varios módulos comparten el bus con CS distintos, y el tracking lo hace el host.
  - **C4001:** tramas `$DFDMD` con 1 objetivo, distancia y velocidad. Hay que subir el rango máximo de 6 a 25 m.
  - **LD2451:** protocolo Hi-Link. Para ver caminantes hay que bajar el umbral de velocidad mínima (configurable de 0 a 120 km/h).
- **Capa de fusión (nueva)** [E]:
  - Rotar cada track según el ángulo de montaje de su sensor.
  - Unir los duplicados en las zonas donde se solapan los sensores.
  - Poner un rango mínimo de 0,5–1 m para ignorar brazos y arma.
- **Movimiento propio (riesgo n.º 1)** [E]. Los trackers de TI, InnoSenT y RFbeam suponen que el sensor está quieto. Cuando el jugador camina o gira el torso, el entorno estático parece moverse, y los sensores que solo ven movimiento (K-LD7, K-MD7, LD2451, C4001) se llenan de falsos objetivos. El manual del LD2451 lo dice explícitamente: "shaking of the radar itself will affect the detection results" [V]. Salidas posibles:
  - Usar una IMU para gating o compensación.
  - Estimar la velocidad propia con los puntos estáticos (Doppler frente al coseno del ángulo). Solo es posible con nube de puntos: V-LD3 o WMOD.
  - Ofrecer un modo "quieto y escanear".
- **Interferencia mutua** [E]. Varios FMCW o FSK en la misma banda se interfieren, tanto en el mismo cuerpo como entre jugadores con Blindside.
  - Solución: sincronizar los frames en TDM con una línea GPIO, o desfasar las frecuencias de inicio.
  - El TDM además evita sumar picos de corriente: 4 V-LD3 transmitiendo a la vez pedirían ~5,8 A a 1,8 V [C].

### 5.3 Protocolo BLE y app del reloj [E]

- **Más capacidad en el mensaje BLE:** hoy son "2 sensores × 3 objetivos a ≤6 m" y pasarían a "N sensores × hasta 8 tracks a 25–50 m". Hay que revisar que el campo de distancia y el n.º de objetivos lo soporten.
- **Enviar la lista ya fusionada** (en el marco del cuerpo) en lugar de las listas crudas de cada sensor. Ahorra ancho de banda BLE.
- **Campos nuevos útiles:** sensor o sector, clase humano/no humano (el V-LD3 la da) y calidad o edad del track.
- **App del reloj:**
  - Escala de 15/25 m con anillos no lineales, para no perder resolución de cerca.
  - Vista de 360° para el Halo.
  - Un aviso cuando el jugador se está moviendo, porque en ese momento los tracks son menos confiables.

### 5.4 Energía y montaje

| Configuración | Consumo de los radares |
|---|---|
| 2× K-LD7 | 0,13–0,66 W [C] |
| Halo de 4× IWRL6432WMOD | 0,22–0,24 W [C] |
| Halo de 4–6× V-LD3 | 0,2–0,6 W [E] |
| Halo de 5× K-LD7 | 0,3–1,7 W [C] |
| + K-MD7 frontal | + 0,18–0,58 W [V] |
| 4× IWR6843 | 6–8 W [E]: **inviable** |

- **Rieles de alimentación:**
  - V-LD3: 1,8 V, más de 1,4 A de pico.
  - WMOD: 3,3 V, 1 A de pico.
  - K-LD7 / K-MD7: 3,2–5,5 V.
  - LD2451: 5 V con más de 300 mA.
- **Montaje en el cinturón:** escudo trasero (§3.4), radome sin metal, una leve inclinación hacia arriba y rango mínimo de 0,5–1 m.

---

## 6. Fuentes

**V-LD3 (RFbeam)**
- https://rfbeam.ch/product/v-ld3-object-tracking-sensor/
- https://rfbeam.ch/download/v-ld3-datasheet-preliminary/
- https://rfbeam.ch/simplifying-60ghz-radar-integration-with-the-v-ld3/
- https://rfbeam.ch/product/v-ld3-evaluation-kit/
- https://www.ti.com/tool/RFBEAM-3P-V-LD3-RFB

**IWRL6432 / IWRL6432WMOD (TI)**
- https://www.ti.com/product/IWRL6432WMOD
- https://www.ti.com/lit/ds/symlink/iwrl6432wmod.pdf
- https://www.ti.com/tool/BP-IWRL6432WMOD
- https://www.ti.com/product/IWRL6432
- https://www.ti.com/lit/ds/symlink/iwrl6432.pdf
- https://e2e.ti.com/support/sensors-group/sensors/f/sensors-forum/1388715/iwrl6432aop-classification-and-multi-people-detection-and-tracking
- https://www.digikey.com/en/products/detail/texas-instruments/IWRL6432BOOST/21296838
- https://www.digikey.com/en/products/detail/texas-instruments/IWRL6432AOPEVM/25982832

**K-LD7 y K-MD7 (RFbeam)**
- https://rfbeam.ch/product/k-ld7-radar-transceiver/
- https://rfbeam.ch/download/k-ld7-datasheet/
- https://www.mouser.com/datasheet/2/1565/K_LD7_Datasheet-3446777.pdf
- https://www.digikey.com/en/products/detail/rfbeam-microwave-gmbh/K-LD7-RFB-00H-01/10638820
- https://rfbeam.ch/product/k-md7-radar-transceiver/
- https://rfbeam.ch/download/k-md7-datasheet/
- https://www.digikey.com/en/products/detail/rfbeam-microwave-gmbh/K-MD7-RFB-00H-02/20413672
- https://www.lcsc.com/product-detail/C20029536.html
- https://rfbeam.ch/download/ce-rf-exposure-statement/

**C4001 (DFRobot) y HLK-LD2451**
- https://www.dfrobot.com/product-2793.html
- https://wiki.dfrobot.com/sen0609/
- https://dfimg.dfrobot.com/wiki/20522/SEN0609_gravity-c4001-24ghz-mmwave-human-presence-detection-sensor_datasheet_V1.pdf
- https://docs.cirkitdesigner.com/component/2d3ef799-066f-41d7-a01a-b7bb255eb862/dfrobot-c4001-mmwave-presence-sensor-25m
- https://www.laskakit.cz/user/related_files/hlk-ld2451_user_manual_v1-0.pdf
- https://www.hlktech.net/index.php?id=1282
- https://paradisetronic.com/en/products/ld2451-24ghz-vehicle-detection-radar-zur-fahrzeugerfassung-und-geschwindigkeitsmessung-bis-100m-reichweite-fmcw-uart-gpio-integriertes-bluetooth-setup
- https://www.amazon.com/MARALANG-Measurement-Millimeter-Condition-Monitoring/dp/B0DGXSPG4R

**IWR6843 (ISK, AOP) y BM201-LPD**
- https://www.ti.com/tool/IWR6843ISK
- https://www.digikey.com/en/products/detail/texas-instruments/IWR6843ISK/10434492
- https://e2e.ti.com/support/sensors-group/sensors/f/sensors-forum/948509/iwr6843aop-max-range-for-people-detection
- https://e2e.ti.com/support/sensors-group/sensors/f/sensors-forum/1013268/iwr6843isk-long-range-people-detection
- https://e2e.ti.com/support/sensors-group/sensors/f/sensors-forum/959320/long-range-people-detection-with-iwr6843isk
- https://e2e.ti.com/support/sensors-group/sensors/f/sensors-forum/1042363/iwr6843isk-antenna-gain
- https://e2e.ti.com/support/sensors-group/sensors/f/sensors-forum/832813/iwr6843-iwr6843-power-consumption-and-how-to-reduce-it
- https://www.ti.com/tool/IWR6843AOPEVM
- https://www.ti.com/lit/ds/symlink/iwr6843aop.pdf
- https://e2e.ti.com/support/sensors-group/sensors/f/sensors-forum/1446945/iwr6843aopevm-iwr6843aopevm-reduce-power-consumption-for-overhead-3d-people-tracking
- https://www.digikey.com/en/products/detail/texas-instruments/IWR6843AOPEVM/12165115
- https://www.joybien.com/product/P_mmwave(BM201_LPD).html
- https://www.ebay.com/itm/155245380886

**Candidatos a futuro**
- https://www.ti.com/product/IWRL6844
- https://www.ti.com/lit/po/swrt029/swrt029.pdf
- https://www.ti.com/tool/IWRL6844EVM
- https://acconeer.com/product-overview-a212/
- https://acconeer.com/news/a212-evk-released-on-digikey/

**Descartados**
- iSYS-5005: https://www.digikey.com/en/products/result?keywords=80.00000423
- iSYS-5005: https://www.digikey.com/en/product-highlight/i/innosent/isys-5005-k-band-radar-system-for-security-applications
- iSYS-5005: https://www.innosent.de/en/company/press-informations/view/article/state-of-the-art-radar-technology-for-near-range-detection/
- iSYS-5020/5021: https://media.digikey.com/pdf/Data%20Sheets/InnoSenT/iSYS_5020.pdf
- iSYS-5020/5021: https://www.innosent.de/en/radar-systems/isys-5021-radar-system-with-tracker-license/
- iSYS-5020/5021: https://www.digikey.com/en/products/detail/innosent-gmbh/80-00000409/13676318
- V-MD3: https://www.digikey.com/en/products/detail/rfbeam-microwave-gmbh/V-MD3-M12-RFB-00A-01/10638836
- BGT60TR13C: https://www.digikey.com/en/products/detail/infineon-technologies/DEMOBGT60TR13CTOBO1/16580764
- BGT60TR13C: https://community.infineon.com/t5/Radar-sensors/What-is-the-maximum-detection-distance-of-DEMO-BGT60TR13C/td-p/438166
- BGT60TR13C: https://www.infineon.com/dgdl/Infineon-BGT60TR13C-DataSheet-v02_49-EN.pdf?fileId=8ac78c8c7d718a49017d94bac88e5d43
- BGT60ATR24C: https://infineon.com/cms/en/product/evaluation-boards/shield_60atr24es_01
- BGT60ATR24C: https://africa.rsdelivers.com/product/infineon/shield60atr24es01tobo1/infineon-shield_60atr24es_01-radar-sensor-kit-for/2564722
- XM125: https://www.sparkfun.com/sparkfun-pulsed-coherent-radar-sensor-acconeer-xm125-qwiic.html
- XM125: https://acconeer.com/products/
- SP70C: https://www.asmag.com/suppliers/productcontent.aspx?co=nanoradar&id=34737
- HLK-LD2461: https://www.hlktech.com/en/Goods-225.html
- HLK-LD2460: https://www.hlktech.net/index.php?id=1335
- HLK-LD6004: https://www.hlktech.net/index.php?id=1391
- Ai-Thinker RD-03D: https://www.lcsc.com/blog/ai-thinker-radar-module-selection-guide/
- Ai-Thinker Rd-60/Rd-61: https://hackaday.io/project/202800-under-7two-60g-radar-modules/details
- IR60TR1A: https://www.iflabel.com/product/65.html
- MicRadar R60ATT1: https://www.micradar.cn/prodetails-62.html
- Murata Type1VN/Type2LP: https://www.murata.com/en-eu/products/connectivitymodule/mmwave-radar/industrial/overview/lineup/type2tk

**Salud**
- ICNIRP 2020: https://www.icnirp.org/cms/upload/publications/ICNIRPrfgdl2020.pdf
- IEEE/ICNIRP por encima de 6 GHz (análisis de Health Canada): https://preventcancernow.ca/wp-content/uploads/2022/02/Analysis-of-Recommendations-above-6-GHz_FINAL-Feb-2-2021.pdf
- FCC 1.1310: https://www.law.cornell.edu/cfr/text/47/1.1310
- FCC 1.1307: https://www.law.cornell.edu/cfr/text/47/1.1307
- FCC 19-126: https://docs.fcc.gov/public/attachments/FCC-19-126A1.pdf
- Certificación FCC del LD2450: https://fccid.io/2AD56HLK-LD2450
- Umbrales oculares en conejo: https://link.springer.com/article/10.1007/s10762-022-00850-w
- Umbrales oculares en conejo: https://link.springer.com/article/10.1007/s10762-018-0497-z
- IARC 2011: https://www.iarc.who.int/wp-content/uploads/2018/07/pr208_E.pdf
- Reevaluación IARC: https://pmc.ncbi.nlm.nih.gov/articles/PMC12640648/
- Mevissen 2025: https://microwavenews.com/short-takes-archive/rf-cancer-review-1
- Estudio NTP: https://www.ncbi.nlm.nih.gov/books/NBK561715/
- Karipidis 2021: https://www.nature.com/articles/s41370-021-00297-6

**Legalidad**
- ANE Res. 105/2020 compilada: https://www.ane.gov.co/Sliders/archivos/Normatividad/2.%20DE%20LA%20ANE/Resoluciones%20ANE/Planeaci%C3%B3n%20de%20Espectro/Resoluci%C3%B3n_ANE_105_de_2020_Compilatori.pdf
- ANE Res. 773/2023: https://normograma.mintic.gov.co/mintic/compilacion/docs/resolucion_ane_0773_2023.htm
- FCC 15.255, reforma 2023: https://www.federalregister.gov/documents/2023/07/24/2023-15367/fcc-empowers-short-range-radars-in-the-60-ghz-band
- FCC 15.255, reforma 2023: https://www.govinfo.gov/content/pkg/FR-2023-07-24/html/2023-15367.htm
- FCC 15.255, reforma 2023: https://docs.fcc.gov/public/attachments/DOC-392978A1.pdf
- FCC 15.255 vigente: https://ecfr.io/Title-47/Section-15.255
- FCC 15.255 vigente: https://www.law.cornell.edu/cfr/text/47/15.255
- FCC Parte 95 subparte M: https://www.law.cornell.edu/cfr/text/47/part-95/subpart-M
- CEPT ERC Rec 70-03: https://docdb.cept.org/download/4635

**Compras**
- Envíos internacionales de DigiKey: https://www.digikey.com/en/help-support/delivery-information/international-shipping-rates
- IVA courier 2026 (por confirmar): https://skycourier.com.co/impuestos-envios-courier-colombia-2026-decreto-1390/
