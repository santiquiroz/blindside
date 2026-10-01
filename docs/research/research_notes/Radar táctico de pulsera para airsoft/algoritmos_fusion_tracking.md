# Algoritmos de tracking, fusión multisensor y compensación de ego-movimiento para radares mmWave de bajo costo llevados por una persona en movimiento, fusionados con un IMU de cinturón (Blindside)

> Notas de investigación al 2026-09-30. Sistema: 2× HLK-LD2450 (24 GHz, ≤3 objetivos/frame, 10 Hz) en el frente del cinturón, abiertos ±30°, + MPU6050 a 50 Hz; el ESP32 pone timestamps y reenvía crudo por BLE a un Galaxy Watch 7, donde un módulo Kotlin puro hace todo el procesamiento.
> **Cómo leer estas notas:** lo que está bajo "Cited Findings" está respaldado por la fuente enlazada. Si el dato solo lo vi en el resumen del buscador y no pude abrir la página, lo marco **(vía buscador)**: confianza media. Lo que está bajo "Inferences" es razonamiento de ingeniería propio (con los cálculos a la vista) y **no se midió**; hay que validarlo con grabaciones. Las fórmulas se dejan en su forma original.

## 1. Tracking multiobjetivo con muy pocas detecciones por frame (≤6): asociación, Kalman de velocidad constante, confirmación M-de-N, coasting, estabilidad de IDs y qué transferir del group tracker de TI

### Takeaway
Con ≤3 detecciones por radar y pocas pistas, la asociación óptima GNN (enumeración exhaustiva o Hungarian sobre la distancia de Mahalanobis, con gate χ²) cuesta lo mismo que un greedy y evita que los IDs se crucen cuando dos personas se cruzan. JPDA no aporta casi nada, porque el LD2450 ya entrega un centroide por objetivo que su propio tracker interno ya filtró. Del group tracker de TI se trasladan tres ideas:
- la máquina de estados con umbrales que dependen del contexto: sobre todo `static2freeThre` vs `exit2freeThre`, porque "lo perdí porque se quedó quieto" no es lo mismo que "lo perdí porque salió";
- los topes físicos del gate;
- la regla "detrás de una pista existente y con Doppler similar ⇒ exigir más evidencia".

Hay que usar ruido de proceso continuo (CWNA), porque los dos radares intercalados dan un dt variable, y limitar el tamaño del gate durante el coasting.

### Cited Findings
**Qué entrega el LD2450 (la entrada del tracker)**
- **Especificaciones:** FMCW, 24–24.25 GHz, "Sweep bandwidth 250MHz", antena microstrip "one-transmitter-two-receiver" (1T2R), alcance 6 m, azimut ±60°, elevación ("pitch") ±35°, "Data refresh rate 10Hz" y "Detection and tracking of up to three targets" — [HLK-LD2450 Instruction manual V1.00, Hi-Link, 2023-05-10](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf).
- **Formato de trama:** 30 bytes por UART a 256000 baudios. Cabecera `AA FF 03 00`, tres bloques de objetivo y cola `55 CC`. Cada objetivo trae:
  - X (mm), Y (mm);
  - velocidad (cm/s);
  - "Distance Resolution" (uint16, "the size of a single distance gate, the unit is mm").
  
  Fuente: [manual LD2450](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf).
- **La codificación NO es complemento a dos:** "highest bit 1 corresponds to positive coordinates, 0 corresponds to negative coordinates" (lo mismo para la velocidad). Ejemplo del manual:
  - X crudo `0x030E` = 782 → −782 mm;
  - Y crudo `0x86B1` = 34481 → 34481 − 2^15 = 1713 mm;
  - velocidad cruda 16 → −16 cm/s;
  - resolución de distancia `0x0140` = 320 mm;
  - los slots sin objetivo llegan en 0x00.
  
  Fuente: [manual LD2450](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf).
- **Solo sigue objetivos en movimiento:** el manual lo describe como "Motion target tracking ... to track the position of the moving target", con "intelligent algorithm firmware" (el tracking ocurre dentro del módulo) — [manual LD2450](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf). En la comunidad: "that's just how LD2450 works: good for moving targets, sucks for static"; una persona quieta deja de reportarse — [Home Assistant Community](https://community.home-assistant.io/t/having-issues-with-hlk-ld2450-only-see-moving-targets-no-presence/1004773).
- **Valores "pegados":** hay un reporte de distancias de Target-1/Target-2 que quedan fijas en un valor no nulo mucho después de terminar el movimiento. Fue en un dispositivo Apollo R PRO-1 con LD2450, tras actualizaciones de firmware (25.6.18.1 → 25.8.20.2), y sin causa raíz publicada — [esphome/esphome#10624, sep-2025](https://github.com/esphome/esphome/issues/10624).
- **Jitter de posición (comunidad):** un objetivo cerca del borde de una zona "may move in and out of the zone even when the person is not intentionally changing areas", por movimiento corporal, resolución del radar o cambio del punto de reflexión — [esp32.co.uk, LD2450 zones](https://esp32.co.uk/ld2450-zones-home-assistant-coordinates-exclusion-zones-2/).
- **Precedente abierto:** un proyecto de seguridad con LD2450 usa un EKF 2D por objetivo y un "Ghost Detector (noise map)": una grilla 80×80 de reflectores estáticos que se aprende por calibración de fondo — [PeterkoCZ91/HLK-LD2450-security](https://github.com/PeterkoCZ91/HLK-LD2450-security).

**Qué hace el group tracker de TI (demo 3D People Counting)**
Todo este bloque viene de [TI, Group Tracker Parameter Tuning Guide for the 3D People Counting Demo, Rev 1.0](https://e2e.ti.com/cfs-file/__key/communityserver-discussions-components-files/1023/3D_5F00_people_5F00_counting_5F00_tracker_5F00_layer_5F00_tuning_5F00_guide-_2800_1_2900_.pdf); los valores de ejemplo están en configuración "CLI".
- **Modelo:** "3D constant acceleration model characterized by a 9 element State vector". Usa un EKF porque la medición (rango, azimut, elevación, velocidad radial) no es lineal respecto del estado cartesiano. La entrada es una nube de puntos con SNR, no objetivos ya agrupados.
- **Asociación:** se forma un gate alrededor de cada pista con distancia de Mahalanobis. Cada punto recibe un "bidding score" por cada pista que lo acepta, y "the point is assigned to the track with which it has the highest bidding score". Es decir, muchos puntos pueden ir a una pista, pero cada punto va a una sola.
- **Allocation (creación de pistas):** los puntos no asociados se agrupan al estilo DBSCAN; `maxDistanceThre` es "similar to epsilon in DBSCAN". El grupo tiene que superar:
  - `pointsThre`;
  - `velocityThre`: velocidad radial mínima del centroide, 0.05 m/s en montaje de techo y 0.1 m/s en pared;
  - `snrThre`.
  
  `velocityThre` existe para que el "spectral leakage" de reflectores estáticos fuertes no cree pistas.
- **`snrThreObscured`:** umbral más exigente (ej. 100 en pared vs 40 de `snrThre`) cuando "An allocation-set is declared obscured if it is behind AND has similar Doppler to an existing track".
- **Gate:** `gatingParam 3 2 2 3 4` = ganancia 3 ("three sigma rule") y límites de 2 m de ancho, 2 m de profundidad, 3 m de altura y 4 m/s de velocidad radial. La guía advierte: "Setting these too small will result in allocating multiple tracks for the single object, setting these too large will cause allocating single track for multiple objects".
- **Máquina de estados DETECT / ACTIVE / FREE**, con conteos por frame (valores para techo / pared). El ejemplo usa `deltaT` = 120 ms.

  | Umbral | Valor | Cuándo aplica |
  |---|---|---|
  | `det2actThre` | 3 | HITs consecutivos para pasar de DETECT a ACTIVE |
  | `det2freeThre` | 3 | MISS consecutivos para liberar una pista en DETECT |
  | `active2freeThre` | 6 | MISS para liberar una pista ACTIVE en condición normal |
  | `static2freeThre` | 20 / 500 | Pista declarada STATIC dentro de la static box: se asume que el MISS se debe a que el filtro de clutter estático borró al objetivo, y se "extend the life expectation" |
  | `exit2freeThre` | 3 / 5 | Pista fuera de la static box: "the reason we didn't get any associated points ... is that target is exiting" |
  | `sleep2freeThre` | 1000 / 6000 | Vida máxima de una pista estática |

- **`maxAcceleration`** (1 / 0.1 / 1 m/s² en techo; 0.1 en pared) es "maximum amount that the target acceleration is expected to change ... between time-periods". Un valor grande significa "we don't trust our motion model"; la guía recomienda subirlo si se pierden pistas por giros bruscos.
- **Contra fantasmas, TI recomienda:**
  - usar `boundaryBox` para descartar lo que cae detrás de las paredes;
  - subir `pointsThre` y `snrThre` ("Ghosts will usually have fewer points with lower SNRs");
  - subir `det2actThre` contra fantasmas momentáneos;
  - bajar los umbrales `*2free`.

**Asociación, gestión de pistas y ruido de proceso (referencias generales)**
- **Valores por defecto de MATLAB `trackerGNN`:** `ConfirmationThreshold` `[2 3]` (M de N), `DeletionThreshold` `[5 5]` y `AssignmentThreshold` `30` (distancia normalizada). Algoritmos de asignación: `MatchPairs` (defecto), `Munkres` ("guarantees optimal solution but slowest"), `Jonker-Volgenant` y `Auction` — [MathWorks trackerGNN](https://www.mathworks.com/help/fusion/ref/trackergnn-system-object.html).
- **Supuestos de GNN:** cada objetivo genera como mucho una medición, y cada medición viene de un solo objetivo o es clutter. Minimiza el costo global (suma de innovaciones) como un problema de asignación — [Stone Soup, tutorial 6 (data association, multi-target)](https://stonesoup.readthedocs.io/en/latest/auto_tutorials/06_DataAssociation-MultiTargetTutorial.html).
- **Regla práctica para el ruido de proceso:** "Bar-Shalom, Blackman, Blair, Li, and Kirubarajan have suggested as guidelines that √W be selected as a constant equal to 33%, 50%, or 100% of the maximum acceleration". También se advierte que el óptimo puede diferir mucho de eso — ["Design of nearly constant velocity track filters for tracking maneuvering targets" (vía buscador; frase exacta y autoría no verificadas en la página)](https://www.researchgate.net/publication/4370081_Design_of_nearly_constant_velocity_track_filters_for_tracking_maneuvering_targets).
- **Aceleraciones peatonales:**
  - al arrancar, la distribución es aproximadamente normal, con media 0.68 m/s² y máximo 1.44 m/s² — [ResearchGate: Pedestrian acceleration and speeds (vía buscador)](https://www.researchgate.net/publication/290320220_Pedestrian_acceleration_and_speeds);
  - como máximo razonable al caminar a ~1.3 m/s se sugieren ~0.2–0.3 g (2–3 m/s²) — [ResearchGate Q&A (vía buscador)](https://www.researchgate.net/post/What_is_the_maximum_walking_acceleration_deceleration_over_a_very_short_time_period_eg_002_01_05_sec).
- **Implementación de referencia:** filterpy incluye los dos modelos de ruido de proceso para CV, `Q_discrete_white_noise` y `Q_continuous_white_noise` — [filterpy.common docs](https://filterpy.readthedocs.io/en/latest/common/common.html) (página listada por el buscador, no abierta en esta sesión).

### Inferences
**Naturaleza del problema**
- **El tracker de Blindside es un "tracker de tracks".** El LD2450 ya detecta, agrupa y sigue internamente, y entrega ≤3 centroides filtrados. Consecuencias:
  - No hay nube de puntos ni SNR, así que no se pueden replicar las partes de TI que cuentan puntos o SNR (`pointsThre`, `snrThre`).
  - El ruido de salida está correlacionado en el tiempo (el firmware suaviza). Un KF con R estimada del jitter será demasiado confiado: inflar R 1.5–2× o ajustarla por NIS en el replay (ver §6).
  - El firmware ya tiene su propia latencia de confirmación y su propio "hold". Blindside no debe sumar mucha latencia encima: 3/5 a 10 Hz (≈0.3–0.5 s) es razonable; no conviene pasar de 4/6 salvo en casos sospechosos.

**Asociación**
- **GNN exacto.** Con ≤3 detecciones × ≤~8 pistas, enumerar todas las asignaciones parciales (unos cientos a lo sumo) o usar Hungarian es trivial en el reloj.
  - Costo: d² de Mahalanobis, opcionalmente + ln|S| como hacen MATLAB y TI.
  - Gate: χ² con 2 grados de libertad. Las detecciones fuera de gate pasan a ser candidatas a pista nueva.
- **Greedy NN** funcionaría casi igual con tan pocas detecciones, pero intercambia IDs cuando dos personas se cruzan. Como el costo computacional de GNN es el mismo, no hay razón para usarlo.
- **JPDA** solo rinde con clutter denso y objetivos cercanos. Aquí el LD2450 ya fusiona los objetivos cercanos (ver el punto siguiente), y JPDA tiende a hacer "coalescer" pistas vecinas: no vale la complejidad. (Esto es conocimiento de libro de texto, Bar-Shalom; no lo verifiqué en esta sesión más allá de Stone Soup.)
- **El LD2450 fusiona a personas cercanas.** Con 250 MHz, ΔR = c/2B ≈ 0.6 m. Con 2 RX (N = 2) y d ≈ λ/2, la resolución angular λ/(N·d·cosθ) da ≈ 1 rad ≈ 57° en boresight (fórmula de TI en §2).
  - En la práctica el LD2450 solo separa personas por rango (>~0.6 m) o por Doppler. Dos jugadores juntos, a la misma distancia y velocidad, serán UN objetivo.
  - Riesgo: cuando dos pistas convergen y una deja de recibir detecciones, no borrarla rápido. Dejarla en coasting (o marcarla como "grupo").
- **El índice de slot no es un ID.** No usar la posición en la trama (Target 1/2/3) como identidad; tratar las ≤3 detecciones como un conjunto sin orden. No está verificado si el orden es estable (ver Gaps).

**Modelo de movimiento**
- **Estado CV:** [x, y, vx, vy] en el marco de tracking.
- **Usar CWNA, no DWNA.**
  - CWNA: `Q = q·[[dt³/3, dt²/2],[dt²/2, dt]]` por eje.
  - DWNA: `Q = σ_a²·[[dt⁴/4, dt³/2],[dt³/2, dt²]]`.
  - Motivo: con dos radares intercalados, el dt entre updates varía (p. ej. 20–80 ms). En DWNA la varianza de velocidad que se inyecta por segundo es σ_a²·dt, así que duplicar la tasa de updates reduce a la mitad el ruido de proceso efectivo y el filtro se vuelve rígido. En CWNA es q por segundo, sea cual sea el dt.
- **Valor de q:** a 10 Hz, q ≈ σ_a²·0.1 s. Con la regla 0.5–1× a_max y a_max ≈ 2–4 m/s² (jugadores que arrancan, frenan y esquivan), σ_a ≈ 2 m/s² → q ≈ 0.4 m²/s³.

**Coasting**
- **El gate crece rápido.** Con q = 0.4 m²/s³, la varianza de posición que aporta el ruido de proceso tras T s de predicción es ≈ q·T³/3.
  - Para T = 2 s: 1.07 m², más T²·P_vv (con σ_v = 0.3 m/s, +0.36 m²) → σ ≈ 1.2 m.
  - Un gate χ² al 99% (√9.21 ≈ 3σ) tendría entonces ≈ 3.6 m de radio: basta para robarle las detecciones a otra persona.
  - Remedio: tope euclidiano del gate (TI usa límites físicos de 2 m con ganancia 3) y amortiguar la velocidad durante el coasting (v ← v·e^(−dt/τ), τ ≈ 1 s) para que la pista no "salga volando".
- **Coasting según el contexto (idea clave tomada de TI).** El LD2450 deja de reportar a quien se queda quieto. TI resuelve el caso análogo con `static2freeThre` (vida larga si la pista estaba casi quieta) frente a `exit2freeThre` (vida corta si probablemente salió). Para Blindside:
  - (a) Si la pista se perdió estando casi quieta (|v| < 0.3 m/s en los últimos 0.5 s): "probablemente sigue ahí". Coasting largo (5–10 s) con la posición congelada.
  - (b) Si se perdió moviéndose dentro del FOV: 1–1.5 s.
  - (c) Si la predicción la pone fuera del FOV: pasar ya a "fuera de vista" (memoria de 5 s) y mostrarla solo como rumbo/sector, no como punto.

**Confirmación e IDs**
- **M-de-N con dos radares: contar por ventana de tiempo.** Hay que contar N en ventanas de 100 ms (una por frame de radar), no por mensaje. Si en el solape las detecciones de ambos radares cuentan como HITs separados, allí se confirman el doble de rápido tanto las pistas reales como los fantasmas.
  - Ilustración binomial, que asume independencia entre ventanas (optimista para fantasmas persistentes): con P_D = 0.9 por ventana, P(≥3 de 5) ≈ 0.991 y P(≥4 de 6) ≈ 0.984.
  - Un fantasma presente en el 30% de las ventanas se confirma con P ≈ 0.16 con 3/5, y con ≈ 0.07 con 4/6.
- **ID interno ≠ ID de pantalla.** Si nace una pista a <1 m de la predicción de una que se borró hace <2 s, que herede el ID/color de pantalla. Así se reduce el parpadeo y la fragmentación visible sin tocar la lógica del tracker.
- **Detectar datos "rancios".** Que (x, y, v) se repitan idénticos al milímetro durante ≥3 frames es improbable para un humano en movimiento con ruido real. Tratarlo como "hold" del firmware y no contarlo como HIT. Lo motiva el reporte de valores pegados; hay que validarlo con grabaciones.

### Gaps
- No encontré documentación oficial sobre:
  - la latencia interna del LD2450;
  - cómo elige qué 3 objetivos reportar;
  - si el orden de los slots es estable entre frames.
- El manual no publica la precisión de rango ni de ángulo; σ_r y σ_θ hay que medirlos.
- No verifiqué qué es físicamente el campo velocidad (¿Doppler radial crudo o la velocidad del tracker interno?) ni su convención de signo; el manual solo trae el ejemplo de −16 cm/s.
- El campo "Distance Resolution" del ejemplo (320 mm) no coincide con c/2B = 0.6 m para 250 MHz. No sé si varía por objetivo o por configuración.
- La regla √W = 33/50/100% de a_max y las cifras de aceleración peatonal salen de resúmenes del buscador; no abrí las páginas de ResearchGate.
- No encontré mediciones publicadas de las aceleraciones de jugadores de airsoft o paintball; los 2–4 m/s² son extrapolación.

## 2. Fusión de dos radares con FOV solapado: nivel medición vs nivel pista, alineación temporal, ruido vs ángulo y rango, calibración extrínseca online con una persona caminando, rechazo de fantasmas por consistencia cruzada

### Takeaway
Lo correcto es fusionar a nivel de medición: updates secuenciales, en orden de timestamp, sobre una sola pista en el marco común.
- **Ruido:** modelarlo en polares y rotarlo a cartesianas, con σ_θ ∝ 1/cosθ (la resolución y la precisión angulares empeoran fuera de boresight) y un error transversal ∝ r.
- **Tiempo:** el desfase que más importa no es entre radares, sino entre radar e IMU durante los giros.
- **Calibración:** la pose relativa de los dos radares se puede estimar con un registro rígido 2D de detecciones simultáneas de una sola persona dentro del solape.
- **Consistencia cruzada:** sirve contra fantasmas de primer orden, pero no contra los "espejo" de segundo orden (caen en el mismo punto para ambos radares). Además solo vale si el otro radar tenía slots libres.

### Cited Findings
- **El LD2450 es 1T2R** (un TX, dos RX) — [manual LD2450](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf).
- **Resolución y precisión angular en radares FMCW de TI:** la resolución angular es λ/(N·d·cosθ), con N = número de antenas RX, así que empeora con cosθ fuera de boresight. "Resolution" (separar objetos) no es lo mismo que "accuracy" (el ángulo de un único objeto dominante): la precisión depende del SNR y de las no-idealidades de la antena, y con buen SNR puede ser varias veces mejor que la resolución — [TI E2E, "AWR1443: FMCW radar angle estimation" (vía buscador; hilo no abierto)](https://e2e.ti.com/support/sensors-group/sensors/f/sensors-forum/601043/awr1443-fmcw-radar-angle-estimation).
- **Advertencia del manual sobre varios radares:** "When multiple 24GHz band radars are present, do not install them in the direction directly opposite to the beam, but as far away as possible to avoid possible mutual interference" — [manual LD2450](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf).
- **Multisensor en MATLAB:** `trackerGNN` "divides detections by originating sensor". Maneja las mediciones fuera de secuencia (OOSM) con `'Terminate'` (defecto), `'Neglect'` o `'Retrodiction'` (hasta `MaxNumOOSMSteps`) — [MathWorks trackerGNN](https://www.mathworks.com/help/fusion/ref/trackergnn-system-object.html).
- **Calibración sin target:**
  - radar–cámara alineando las trayectorias de un objetivo en movimiento — ["Targetless Radar–Camera Calibration via Trajectory Alignment", Sensors 2025 (vía buscador)](https://doi.org/10.3390/s25247574);
  - varios LiDAR y radares a la vez, automática, "only requires the vehicle to be driven over a short distance" — ["Automatic Targetless Extrinsic Calibration of Multiple 3D LiDARs and Radars", IEEE (vía buscador)](https://ieeexplore.ieee.org/document/9340866/);
  - hay trabajo reciente de odometría radar-inercial con calibración espacio-temporal online (radar↔IMU) — ["Radar-Inertial Odometry with Online Spatio-Temporal Calibration via Continuous-Time IMU Modeling", arXiv 2603.19958 (solo título/resumen)](https://arxiv.org/html/2603.19958).
- **Kellner et al. 2014 (varios radares Doppler):** en cada iteración RANSAC se eligen tres objetivos de al menos dos sensores distintos — [ResearchGate (vía buscador)](https://www.researchgate.net/publication/287218315_Instantaneous_ego-motion_estimation_using_multiple_Doppler_radars).

### Inferences
**Arquitectura de fusión**
- **Nivel medición vs nivel pista.** La fusión centralizada es óptima cuando los dos sensores comparten base de tiempo y calibración, y aquí se tienen ambas (timestamps del ESP32 y marco cuerpo común). La fusión pista-a-pista exigiría manejar la correlación entre las estimaciones (Bar-Shalom–Campo o covariance intersection): más compleja y con pérdida de información. (Libro de texto; no verificado en esta sesión.)
- **Pero ojo: el LD2450 ya entrega pistas.** Cada módulo tiene su tracker interno con errores correlados en el tiempo. En el solape, tratar sus salidas como independientes vuelve al filtro demasiado confiado. Mitigación: inflar R en el solape (×1.5–2) o limitar la ganancia de información por ventana de 100 ms.

**Tiempo**
- **Orden temporal.** Los dos LD2450 corren libres a ~10 Hz y su fase relativa deriva: a veces llegan casi a la vez y a veces 50 ms separados. Procesar en orden de timestamp con un buffer de reordenamiento de ~150 ms y descartar lo que llegue más tarde (semántica `'Neglect'`). CWNA absorbe el dt variable.
  - El ESP32 debe poner el timestamp siempre en el mismo punto de la trama (p. ej. al recibir la cabecera). Transmitir la trama cuesta 30 B × 10 bit / 256000 ≈ 1.2 ms: despreciable.
- **Mapeo de relojes ESP32 → reloj.** La fusión puede correr entera en tiempo del ESP32. Solo hace falta mapear al reloj del Watch para extrapolar la imagen a "ahora".
  - Estimar el offset con un mínimo móvil de (t_llegada_watch − t_esp) en ventanas de ~10 s (filtro de retardo mínimo, estilo NTP), más un ajuste lineal de la deriva.
  - El BLE agrupa paquetes según el intervalo de conexión (conocimiento general: 7.5 ms–4 s; no verificado aquí).
- **El desfase que más importa es radar ↔ IMU.** Si el yaw se interpola en el timestamp del radar pero la trama llega con una latencia interna τ desconocida, girando a 180 °/s un τ = 50 ms equivale a 9° de error de rumbo: ≈ 0.63 m laterales a 4 m. Durante los giros, los objetivos reales "patinan" en el mundo y rompen la regla anti-fantasmas.
  - Calibrar τ en el replay con un barrido de 0–200 ms, eligiendo el τ que minimiza el NIS medio de las pistas mientras el usuario gira. Escenario: un ayudante camina lento y el usuario gira a un lado y otro.

**Ruido de medición**
- **R en función de ángulo y rango.** Medición polar (r, θ) en el marco del radar; `R_cart = Rot(θ)·diag(σ_r², (r·σ_θ(θ))²)·Rot(θ)ᵀ`, con `σ_θ(θ) = σ_θ0 / cos θ` (acotar cos θ ≥ cos 70°). Después se rota por el yaw de montaje del radar y por el yaw del mundo.
  - Punto de partida, a medir: σ_r ≈ 0.2 m y σ_θ0 ≈ 3–4°.
  - Ejemplos: a 4 m en boresight el σ transversal es ≈ 4·0.06 ≈ 0.24 m; a 5 m y 50° es ≈ 5·(0.06/0.64) ≈ 0.47 m.
- **El sesgo de la conversión polar→cartesiana es despreciable.** Vale ≈ r·σ_θ²/2 ≈ 5·0.07²/2 ≈ 1 cm, así que no hace falta un "debiased converted measurement". Un KF lineal en cartesianas con R rotada basta, porque el LD2450 ya entrega X/Y.
- **Sin elevación, Y es rango inclinado.** El 1T2R solo mide azimut. Para un blanco a 1 m con 0.7 m de diferencia de altura, el rango inclinado es 1.22 m. El error es relevante a menos de ~2 m y despreciable a más de 3 m.

**Geometría y calibración**
- **Geometría del solape.** Con los radares abiertos ±30° y un FOV de ±60° cada uno, la cobertura del cuerpo va de −90° a +90° y el solape es de −30° a +30°. Dentro del solape cada radar ve el objetivo a ~30° de su boresight (cos 30° = 0.87): la degradación es leve y cada update aporta una información parecida.
- **Calibración extrínseca con una persona caminando (propuesta; no la encontré publicada para este caso exacto).**
  - (1) Tomar solo los frames en que cada radar reporta exactamente un objetivo y ambos caen en el solape (−25° a +25° en el cuerpo), a 1.5–5 m.
  - (2) Interpolar las detecciones de A a los timestamps de B. A 1 m/s, interpolar linealmente datos de 10 Hz introduce ≤5 cm.
  - (3) Resolver la transformación rígida 2D A→B (3 DoF) en forma cerrada con Kabsch/Umeyama — [Umeyama 1991, doi:10.1109/34.88573](https://doi.org/10.1109/34.88573) (referencia canónica, no abierta en esta sesión). Usar RANSAC o Huber por si hay swaps o fantasmas.
  - Más robusto: fijar la traslación a la distancia medida con cinta entre bolsillos y ajustar solo el yaw relativo (1 DoF), que es lo que más varía (depende del clip y de la forma del cuerpo).
  - (4) El yaw absoluto del par respecto del "frente del cuerpo" no es observable con estos datos. Definirlo como la bisectriz, o calibrarlo con una línea de cinta: el ayudante camina recto hacia el usuario por el eje del frente.
  - (5) Modo "online": acumular pares siempre que haya un solo objetivo en el solape, actualizar con un EMA lento y vigilar el residuo. Un salto en el residuo indica que el clip se movió.
  - Criterio de éxito: RMS del residuo ≈ √2·σ de la medición.
- **Rechazo de fantasmas por consistencia cruzada, con tres condiciones.**
  - Regla: si una detección de A cae en la zona que B debería ver (≥10° dentro del FOV de B) y B no reporta nada dentro del gate durante 3 frames seguidos, bajar la "existencia" de la pista: no dejarla confirmarse. Si ya está confirmada, no borrarla por esto.
  - (a) Aplicarla solo si B reportó menos de 3 objetivos. Con los 3 slots ocupados, que B no lo vea no prueba nada.
  - (b) No aplicarla cerca del borde del FOV de B.
  - (c) Los fantasmas de segundo orden (espejo de pared) caen en la imagen especular del blanco, un punto que no depende de la posición del radar (ver §5), así que ambos radares los "confirman". Esta regla solo filtra fantasmas de primer orden, interferencia y errores de un solo módulo.
- **Interferencia mutua.** Los dos módulos idénticos de 24 GHz miran a ±30° con lóbulos que se solapan. El manual solo advierte no enfrentarlos. Prueba barata: con el usuario quieto, medir la tasa de detecciones espurias de A con B apagado y luego con B encendido.

### Gaps
- No encontré trabajos de calibración radar-radar con una sola persona caminando para radares que reportan ≤3 objetivos; el procedimiento es una adaptación.
- La latencia interna del LD2450 no está documentada, y tampoco si es igual en los dos módulos.
- No hay datos publicados de interferencia mutua entre dos LD2450 ubicados juntos.
- La precisión angular real del LD2450 en función del ángulo no está publicada. La relación 1/cosθ viene de un hilo de TI resumido por el buscador y aplica en rigor a sus arreglos, no verificada para el LD2450.

## 3. Radar sobre plataforma móvil: compensación de ego-movimiento con el yaw del IMU, ego-velocidad por Doppler (Kellner 2013), supresión de objetos estáticos que "se mueven" por el movimiento del usuario, velocidad al caminar por cadencia

### Takeaway
Compensar solo la rotación (yaw del giroscopio) es necesario pero no suficiente. El "mundo" definido como "cuerpo rotado por el yaw integrado" no compensa la traslación: al caminar, todo objeto estático parece moverse a −v_ego, así que la regla "confirmar solo si se mueve en el mundo" deja pasar paredes y árboles, o hay que endurecerla tanto que ciega a Blindside.
- **Qué agregar:** una estimación de ego-velocidad (cadencia × longitud de paso, con incertidumbre anisotrópica) y un test de movimiento relativo a −v_ego.
- **Doppler de Kellner:** su ajuste del perfil de velocidad radial vs azimut con RANSAC es físicamente aplicable, pero con ≤6 detecciones, sin garantía de ≥2 objetos estáticos y con dudas sobre qué es el campo velocidad, conviene usarlo solo como refinamiento opcional y con puerta de confianza.
- **Límite del sensor:** el LD2450 está pensado para montaje fijo ("the shaking of the radar itself will affect the detection effect"). Al caminar cabe esperar objetos estáticos reportados como móviles y los 3 slots ocupados por clutter.

### Cited Findings
- **Kellner et al. 2013 (ITSC):** "robust and self-contained algorithm to instantly determine the velocity and yaw rate of the ego-vehicle" con radares Doppler. Analiza "the distribution of radial velocities over the azimuth angle", no requiere clustering ni supresión de clutter, no guarda historia ni asocia datos, y etiqueta cada objetivo como estacionario o no. Usa RANSAC para hallar el grupo mayoritario de objetivos con el mismo perfil de velocidad, que se supone estático — [ResearchGate (vía buscador)](https://www.researchgate.net/publication/269332200_Instantaneous_ego-motion_estimation_using_Doppler_radar); [Semantic Scholar (vía buscador; la página devolvió 403)](https://www.semanticscholar.org/paper/Instantaneous-ego-motion-estimation-using-Doppler-Kellner-Barjenbruch/627e7e112e4f4a871d77cb1ed9ea6c3f7f3c59c4).
- **Kellner 2014, varios radares:** el RANSAC elige en cada iteración tres objetivos de al menos dos sensores distintos — [ResearchGate (vía buscador)](https://www.researchgate.net/publication/287218315_Instantaneous_ego-motion_estimation_using_multiple_Doppler_radars).
- **Advertencias del manual del LD2450 sobre montaje y lóbulo trasero:**
  - "Make sure the installation position of the sensor is firm and stable, the shaking of the radar itself will affect the detection effect";
  - "the back flap of the antenna signal may detect the moving objects on the back of the radar. A metal shield or metal back plate can be used to shield the radar back flap";
  - recomienda montaje en pared a 1.5–2 m.
  
  Fuente: [manual LD2450](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf).
- **Solo objetivos en movimiento:** el LD2450 está orientado a "multiple moving targets" y no detecta a una persona quieta — [Home Assistant Community](https://community.home-assistant.io/t/having-issues-with-hlk-ld2450-only-see-moving-targets-no-presence/1004773).
- **Detector de pasos de Android** (`TYPE_STEP_DETECTOR`): modo "one event per step taken"; "The timestamp of the event ... corresponds to when the foot hit the ground"; "should have a lower latency (less than two seconds)". El contador de pasos puede tener hasta 10 s de latencia — [AOSP, Sensor types](https://source.android.com/docs/core/interaction/sensors/sensor-types).
- **Longitud de paso:** el modelo de Weinberg es SL = K·(a_max − a_min)^(1/4), con a_max/a_min la aceleración vertical máxima/mínima del paso y K una constante que hay que calibrar. La longitud de zancada a una misma velocidad puede variar ±40% entre personas — [Analog Devices AN-602 (Weinberg) (vía buscador)](https://www.analog.com/media/en/technical-documentation/application-notes/513772624AN602.pdf).
- **Giro horizontal:** la tasa de giro horizontal es la proyección vertical del giroscopio de 3 ejes, ω_v(t) = γ̂(t)ᵀ ω(t), donde γ̂ es la dirección de la gravedad — ["Gravity-Based Methods for Heading Computation in Pedestrian Dead Reckoning", Sensors 2019, 19(5):1170](https://doi.org/10.3390/s19051170).

### Inferences
**Física del Doppler con el usuario en movimiento**
- **Ecuación de Doppler de un objeto estático** (base del método de Kellner): `v_r,i = −(v_s,x·cos θ_i + v_s,y·sin θ_i)`, con v_s la velocidad del sensor en su propio marco.
- **Brazo de palanca.** Con giro ω, cada radar tiene `v_s = R_iᵀ·(v_body + ω × p_i)`, donde p_i es su posición respecto del eje de giro, ≈ 0.12–0.18 m. Con ω = 2 rad/s (115 °/s) eso suma ≈ 0.3 m/s, lo mismo que una caminata lenta: hay que incluirlo.
- **El gyro no alcanza cuando el usuario camina.** Si el usuario gira sobre sí mismo sin trasladarse, los objetos estáticos casi no tienen Doppler (solo por el brazo de palanca) y la compensación de yaw basta. Al caminar, en cambio, los objetos estáticos tienen Doppler de hasta |v_ego| ≈ 1.2–1.5 m/s, y ahí la compensación de yaw no alcanza.

**Factibilidad de estimar la ego-velocidad por Doppler con el LD2450**
- Con ω conocido por el giroscopio, hay 2 incógnitas (v_body en el plano). Hacen falta ≥2 detecciones estáticas con rumbos bien separados, y ≥3 para rechazar outliers.
- Los dos radares abiertos ±30° amplían la dispersión angular hasta ±90° en el cuerpo, lo que mejora el condicionamiento.
- Con ≤6 detecciones, un RANSAC con muestras mínimas de 2 tiene como mucho C(6,2) = 15 hipótesis: se puede enumerar todo (consenso exhaustivo).
- Riesgos:
  - (a) puede haber menos de 2 objetos estáticos entre los 3 slots de cada radar (campo abierto, o slots ocupados por personas);
  - (b) el campo velocidad podría no ser Doppler crudo;
  - (c) el suavizado interno del LD2450 mete retardo;
  - (d) el rebote vertical de la pelvis al caminar (unos cm a ~2 Hz ⇒ ~0.2–0.3 m/s pico) se proyecta en el Doppler de los ecos del suelo (hasta −35° de elevación) sin que el sensor pueda medir elevación.
- Conclusión: usar la estimación por cadencia como base y el Doppler solo cuando haya ≥3 detecciones consistentes con un mismo v_s y el residuo sea bajo.

**Arreglar el marco de tracking (mejora principal al pipeline)**
- Mantener el marco "mundo" rotado por yaw, pero meter la traslación del usuario como entrada de control en la predicción de TODAS las pistas:
  - `x ← F·x − [v_ego·dt; 0]`
  - `P ← F·P·Fᵀ + Q + Q_ego(dt)`
- Así el estado de velocidad pasa a ser relativo al suelo: los objetos estáticos quedan en ≈ 0 y los rivales con su velocidad real. Si no hay estimación de v_ego, Q_ego absorbe la incertidumbre como un ruido de modo común.
- Sin esto, una pista en coasting de 2 s mientras el usuario camina a 1.4 m/s acumula ≈ 2.8 m de error, y la "memoria fuera de vista" de 5 s acumula ≈ 7 m. Esa memoria solo tiene sentido como rumbo aproximado, o con dead-reckoning.

**Reglas para suprimir objetos estáticos que "se mueven"**
- (1) Velocidad en el mundo corregida por traslación: confirmar solo pistas cuya velocidad respecto del suelo (estado del Kalman con la traslación compensada, donde lo estático queda en ≈ 0) supere ~0,6 m/s durante cinco ventanas; −v_ego solo es la referencia para la velocidad aparente sin compensar.
- (2) Consistencia Doppler, si se valida que el campo es Doppler radial: el objetivo es "compatible con estático" si `|v_r,med − (−u_losᵀ·v_s)| < ~0.25 m/s`.
- (3) Modo común: los objetos estáticos comparten una velocidad común que es ≈ 0 en el marco compensado, así que un grupo de ≥2 detecciones con velocidades aparentes iguales es casi seguro clutter estático (la misma lógica de "grupo mayoritario" de Kellner).
- (4) Persistencia de posición: en el marco corregido, un objeto estático se queda en su sitio durante 2–3 s. El error del dead-reckoning por cadencia en ese lapso (~10–20% de 1.4 m/s × 2 s ≈ 0.3–0.6 m) es comparable al ruido del radar.
- (5) En giros puros, tolerancia proporcional a |ω|·(error residual de τ)·r.

**La ego-velocidad no siempre apunta al frente**
- Caminar de costado o hacia atrás es común en airsoft. Asumir que v_ego apunta al frente del cuerpo falla ahí.
- Modelar Q_ego como anisotrópico mientras se camina: σ_frente ≈ 0.3 m/s y σ_lateral ≈ 0.8–1.0 m/s. Cuando haya Doppler fiable, usarlo para desambiguar la dirección.

**Velocidad al caminar a partir de la cadencia**
- `v ≈ f_paso × L_paso`, con L_paso por Weinberg (K calibrado caminando 20 m medidos con cinta, a 3 ritmos) o proporcional a la altura del usuario.
- Detectar los pasos con el acelerómetro del cinturón (picos verticales de la pelvis, bien definidos; 50 Hz sobra para ~2 Hz de pasos) y no con el detector del reloj. Motivos:
  - la especificación de AOSP le permite hasta ~2 s de latencia;
  - con las manos en la réplica, la muñeca casi no oscila (hipótesis sin verificar).
- El detector del reloj sirve, como mucho, de confirmación lenta de la cadencia.

**Otros riesgos**
- **Saturación de slots.** Con el usuario caminando, el LD2450 puede llenar sus 3 slots con reflectores estáticos que "se mueven" relativamente (árboles, barricadas), y un rival real puede no reportarse. Ningún filtro posterior lo recupera. Métrica a medir: "% de frames con 3 slots ocupados por clutter mientras se camina".
- **El eje de giro no es el centro del cinturón.** Al girar pivotando sobre un pie, el eje real se desplaza respecto al centro del cinturón. Eso mete errores de traslación de ~0.1–0.3 m que la compensación de yaw pura no ve; son tolerables.

### Gaps
- No encontré mediciones publicadas de la amplitud de la oscilación de yaw pélvica al caminar ni de su efecto sobre radares en el cinturón. Hay que medirla con el propio MPU6050.
- No está verificado si el LD2450 reporta objetos estáticos del entorno cuando el sensor se mueve, ni con qué prioridad llena los 3 slots.
- No está verificado el significado ni el signo del campo velocidad (Doppler radial vs velocidad del tracker interno).
- No encontré precisión de longitud de paso para los andares de airsoft (agachado, de costado, hacia atrás); los errores del 10–20% son estimación propia.
- No pude abrir el texto completo de Kellner 2013 (403). No confirmé si la versión con un solo radar necesita la condición de Ackermann para el yaw rate; para Blindside no importa porque el yaw rate sale del giroscopio.

## 4. Procesamiento del IMU del cinturón (MPU6050): bias y deriva, detección de reposo para recalibrar, proyección sobre la gravedad, complementario vs Madgwick/Mahony, error de yaw esperado

### Takeaway
El MPU6050 necesita calibrar el bias al arrancar (el ZRO inicial llega a ±20 °/s) y recalibrarlo cuando está en reposo. Hecho eso:
- **1–10 s:** el ruido (ARW ≈ 0.01 °/√s) y la inestabilidad de bias (≈ 0.001–0.0025 °/s) dan errores de yaw despreciables. Dominan el error de factor de escala (±3% ⇒ ~2.7° por cada giro neto de 90°) y el desfase radar–IMU.
- **Minutos:** domina la deriva térmica del bias si no se recalibra.

Blindside dibuja en el marco del cuerpo y su "mundo" solo necesita ser coherente mientras vive una pista (≤5–10 s), así que la deriva lenta del yaw es casi irrelevante. Proyectar el giro sobre la gravedad (ω_v = ĝᵀω) da la tasa de giro horizontal sin importar cómo esté montado el IMU; es imprescindible si el jugador se agacha. Sin magnetómetro, complementario, Mahony y Madgwick hacen lo mismo con el yaw (integrarlo): su único papel es estimar la gravedad ignorando las aceleraciones de correr e impactar.

### Cited Findings
- **Giroscopio**, MPU-6000/6050 Product Specification PS-MPU-6000A-00 Rev 3.1, TA = 25 °C:

  | Parámetro | Valor |
  |---|---|
  | Tolerancia del factor de escala | ±3% |
  | Variación del factor de escala con la temperatura | ±2% |
  | No linealidad | 0.2% |
  | Sensibilidad cruzada | ±2% |
  | "Initial ZRO Tolerance" | ±20 °/s |
  | "ZRO Variation Over Temperature" (−40 a +85 °C) | ±20 °/s |
  | "Linear Acceleration Sensitivity" | 0.1 °/s/g |
  | "Total RMS Noise" (DLPFCFG=2, 100 Hz) | 0.05 °/s-rms |
  | Ruido RMS de baja frecuencia (1–10 Hz) | 0.033 °/s-rms |
  | "Rate Noise Spectral Density" | 0.005 °/s/√Hz |
  | Asentamiento del ZRO al arrancar (a ±1 °/s del valor final) | 30 ms |
  | Filtro pasabajos programable | 5–256 Hz |

  Fuente: [PS-MPU-6000A-00 Rev 3.1](https://cdn.sparkfun.com/datasheets/Components/General%20IC/PS-MPU-6000A.pdf).
- **Acelerómetro** (mismo documento): tolerancia de zero-g inicial ±50 mg en X/Y y ±80 mg en Z; cambio de zero-g con la temperatura (0–70 °C) ±35 mg en X/Y y ±60 mg en Z; densidad de ruido 400 µg/√Hz; tolerancia de sensibilidad ±3% — [PS-MPU-6000A-00 Rev 3.1](https://cdn.sparkfun.com/datasheets/Components/General%20IC/PS-MPU-6000A.pdf).
- **Montaje:** la tensión mecánica del PCB sobre el encapsulado "can affect the output offset and its value over a wide range of temperatures" (§11.4.1). Además, errores de orientación de 0.5° / 1° en el montaje dan 0.87% / 1.75% de sensibilidad cruzada — [PS-MPU-6000A-00 Rev 3.1](https://cdn.sparkfun.com/datasheets/Components/General%20IC/PS-MPU-6000A.pdf).
- **Varianza de Allan de un MPU-6050**, a 70 Hz, 1 h, (22 ± 3) °C, estático:
  - ARW: 0.009145 / 0.009997 / 0.009533 °/s/√Hz (X/Y/Z);
  - inestabilidad de bias: 0.00239 / 0.001277 / 0.002449 °/s (≈ 8.6 / 4.6 / 8.8 °/h), con el mínimo en τ ≈ 20–150 s;
  - "no Markov noise and random rate walk" en 1 h.
  
  Fuente: ["Strapdown Inertial Navigation Systems for Positioning Mobile Robots—MEMS Gyroscopes Random Errors Analysis Using Allan Variance Method", Sensors 2020, 20(17):4841](https://pmc.ncbi.nlm.nih.gov/articles/PMC7506677/).
- **Librería x-io Fusion** (resumen del README):
  - AHRS basado en el algoritmo revisado del capítulo 7 de la tesis de Madgwick; admite solo giroscopio + acelerómetro;
  - ganancia recomendada 0.5 ("appropriate for most applications");
  - "acceleration rejection" de 10°: ignora el acelerómetro si la inclinación que indica difiere más de eso de la estimada;
  - timeout de rechazo de 5 s;
  - corrección de offset del giroscopio: detecta reposo con un umbral de 3 °/s sostenido ~3 s y compensa las variaciones por temperatura con un filtro de muy baja frecuencia.
  
  Fuente: [xioTechnologies/Fusion](https://github.com/xioTechnologies/Fusion).
- **Giro horizontal por gravedad:** la proyección vertical del giroscopio, ω_v(t) = γ̂(t)ᵀ ω(t), da la tasa de giro horizontal; el paper describe estimar γ̂ también en condiciones dinámicas — [Sensors 2019, 19(5):1170](https://doi.org/10.3390/s19051170).
- **Game rotation vector de Android:** usa "Accelerometer, gyroscope, MUST NOT USE magnetometer", así que su yaw deriva igual que una integración pura — [AOSP, Sensor types](https://source.android.com/docs/core/interaction/sensors/sensor-types).

### Inferences
**Presupuesto de error de yaw** (tras calibrar el bias al arrancar; cifras propias con los números citados)

| Fuente de error | Cómo crece | 1 s | 10 s | 60 s | 10 min |
|---|---|---|---|---|---|
| ARW 0.0095 °/√s | σ = N·√t | 0.01° | 0.03° | 0.07° | 0.23° |
| Inestabilidad de bias ~0.002 °/s | ~b·t (orden de magnitud) | ~0 | 0.02° | ~0.1° | ~1° |
| Bias residual bien calibrado 0.01 °/s | b·t | 0.01° | 0.1° | 0.6° | 6° |
| Bias tras un cambio térmico sin recalibrar (p. ej. 0.1 °/s) | b·t | 0.1° | 1° | 6° | 60° |
| Factor de escala ±3% (+±2% térmico) | k·Δψ_neto | — | 2.7° por cada 90° netos | ídem | ídem |
| Desfase radar–IMU τ | ω·τ, instantáneo | — | 9° a 180 °/s con τ = 50 ms | ídem | ídem |

- **El factor de escala solo castiga el giro neto.** Su error es proporcional al cambio neto de rumbo, no al acumulado: girar +90° y volver −90° se cancela si el factor es simétrico (no verificado).
- **Calibrar el factor de escala una vez.** Dar 5 vueltas completas en una silla giratoria, empezando y terminando alineado con una marca; k = 1800° / ∫ω_v dt. Eso deja el error por debajo de ~0.5%.

**Detección de reposo y recalibración del bias**
- Al arrancar, promediar 2 s de reposo. El ZRO se asienta en 30 ms, pero el bias inicial puede estar lejos de cero.
  - Con ARW ≈ 0.0095 °/√s, promediar 2 s deja σ_bias ≈ 0.0095/√2 ≈ 0.007 °/s.
- Criterio de reposo: |ω| < 3 °/s durante ≥3 s (valores de Fusion) Y desviación estándar de |a| < ~0.02–0.03 g.
  - La condición del acelerómetro evita "aprender" como bias un giro lento y constante (<3 °/s).
- Actualizar el bias con un EMA de τ ≈ 10–30 s.
- En airsoft, "quieto" suele ser "apuntando", con un balanceo postural pequeño que el umbral de 3 °/s tolera.
- **Por qué es necesario recalibrar:** el datasheet solo da el peor caso del ZRO en temperatura (±20 °/s en 125 °C, hasta ~0.16 °/s/°C) y no un coeficiente típico. En un cinturón (calor corporal, sol y sombra), la recalibración en reposo es la defensa principal.

**Proyección sobre la gravedad**
- ĝ sale de un filtro de inclinación. Luego ω_v = ĝᵀω y se integra con regla trapezoidal a 50 Hz.
- Guardar un buffer circular de yaw de ≥3 s para interpolarlo en cualquier timestamp de radar menos τ.
- **Sin proyección, el error depende de cuánto se incline el jugador:**
  - el eje z del gyro escala el yaw por cos(inclinación): −6% a 20° y −29% a 45° (jugador agachado o inclinado);
  - además mezcla pitch y roll.
- **Con proyección:** un error de inclinación δ mete ≈ ω_horizontal·sin δ. Con δ = 2° y pitch/roll pélvicos de ~30 °/s al caminar, eso es ~1 °/s oscilatorio y de media casi nula.

**Qué filtro usar**
- Sin magnetómetro, el yaw no es observable. Complementario, Mahony y Madgwick lo integran igual, así que lo que importa es estimar la gravedad robustamente.
- Recomendado: complementario de inclinación (pasabajos del acelerómetro con τ ≈ 1 s) con rechazo de aceleración. Saltar el acelerómetro si ||a| − 1 g| > 0.15 g, o si la inclinación que indica difiere >10° de la estimada, como hace Fusion.
- Alternativa lista para usar: portar la lógica de Fusion (ganancia 0.5, rechazo de 10°).
- Mahony con Ki > 0 solo estimaría el bias de los ejes observables (roll y pitch), no el de yaw.

**Muestreo y montaje**
- **Filtro antialias:** a 50 Hz, configurar el DLPF por debajo de 25 Hz (Nyquist) para no tener aliasing de las vibraciones de impacto. (No extraje la tabla DLPF del register map; los valores exactos quedan sin verificar aquí.)
- **Rango del gyro:** usar ±500 °/s. Los giros rápidos de pelvis pueden superar 250 °/s (supuesto no medido), y el ruido extra por la menor sensibilidad es irrelevante.
- **Opción a considerar: integrar el yaw en el ESP32.** Proyectar e integrar a 100–200 Hz en el ESP32 y enviar yaw + timestamp:
  - reduce errores de integración en giros rápidos;
  - baja el tráfico BLE;
  - mantiene el timestamp común con los radares.
- **Montaje del IMU:** en la misma pieza rígida que los radares, o en el centro de un tramo rígido del cinturón. Si el cinturón flexiona entre el IMU y los radares, aparece una rotación diferencial que ninguna calibración corrige.

### Gaps
- No encontré mediciones comunitarias fiables del coeficiente térmico típico del ZRO del MPU6050 (°/s/°C); el datasheet solo da el máximo.
- La tabla del DLPF (ancho de banda y retardo por DLPF_CFG) está en el Register Map, que no abrí.
- Los parámetros de Fusion vienen de un resumen del README. La duración exacta del periodo de reposo (3 s vs 5 s) y los valores por defecto pueden variar según la versión: confirmar en el código fuente.
- No encontré datos sobre la asimetría del factor de escala (giros positivos vs negativos) del MPU6050.

## 5. Características de multitrayecto y fantasmas en radares FMCW cerca de paredes, y heurísticas simples para suprimirlos

### Takeaway
Las superficies interiores se comportan casi como espejos a mmWave.
- **Fantasma de segundo orden:** cae en la imagen especular del blanco respecto de la pared.
- **Fantasmas de primer orden:** forman dos grupos a una distancia parecida, entre el blanco real y su espejo.

En datasets interiores, alrededor de un tercio de los puntos son fantasmas. Sin nube de puntos ni SNR, las heurísticas útiles para Blindside son geométricas y cinemáticas:
- "detrás de una pista real, en rumbo parecido y con Doppler parecido" ⇒ exigir más evidencia;
- movimiento acoplado (espejado) a una pista real;
- persistencia (M-de-N más estricto);
- mapa de ruido propio;
- consistencia entre radares, que solo vale contra el primer orden.

### Cited Findings
- **Geometría de los fantasmas:**
  - los de segundo orden aparecen "symmetrically positioned relative to the true target about the wall surface";
  - el multitrayecto de primer orden produce "two distinct clusters" que "maintain approximately equal distances from the radar, positioned between the true target and second-order ghost locations";
  - a mmWave "surfaces become effectively smoother as the wavelength increases", y casi todas las superficies interiores actúan "like mirrors".
  
  Fuente: ["Indoor mmWave Radar Ghost Suppression: Trajectory-Guided Spatiotemporal Point Cloud Learning", Sensors 2025 (PMC12158235)](https://pmc.ncbi.nlm.nih.gov/articles/PMC12158235/).
- **Frecuencia:** en ese dataset hay 461,383 puntos anotados, de los cuales 296,247 son reales; ≈36% son fantasmas — [PMC12158235](https://pmc.ncbi.nlm.nih.gov/articles/PMC12158235/).
- **Heuristicas previas que cita ese paper:**
  - conocer a priori la geometría de las paredes;
  - polarización (requiere antenas especiales);
  - diferencia entre la dirección de salida (DOD) y la de llegada (DOA) para identificar el primer orden;
  - rasgos de trayectoria y forma de onda.
  
  Fuente: [PMC12158235](https://pmc.ncbi.nlm.nih.gov/articles/PMC12158235/).
- **Otros enfoques:**
  - existe el reconocimiento de fantasmas de multitrayecto junto con tracking y estimación de paredes para radar MIMO interior — [ResearchGate 377424944 (solo título)](https://www.researchgate.net/publication/377424944_Multipath_Ghost_Recognition_and_Joint_Target_Tracking_with_Wall_Estimation_for_Indoor_MIMO_Radar);
  - un "reflection map" permite eliminar fantasmas de multitrayecto y de sombra, y reforzar personas con reflexión débil en zonas ocluidas — [ACM IMWUT, "Environment-aware Multi-person Tracking in Indoor Environments with MmWave Radars" (vía buscador)](https://dl.acm.org/doi/10.1145/3610902).
- **Qué hace TI contra fantasmas:**
  - "Ignoring the points beyond the walls of a room also helps in eliminating ghost targets";
  - los fantasmas tienen menos puntos y menor SNR;
  - `det2actThre` más alto frena a los fantasmas momentáneos;
  - un grupo candidato "behind AND has similar Doppler to an existing track" necesita más SNR (`snrThreObscured`).
  
  Fuente: [TI Group Tracker Tuning Guide](https://e2e.ti.com/cfs-file/__key/communityserver-discussions-components-files/1023/3D_5F00_people_5F00_counting_5F00_tracker_5F00_layer_5F00_tuning_5F00_guide-_2800_1_2900_.pdf).
- **Advertencias del manual del LD2450:**
  - la detección empeora con "a large area of strong reflective objects in the sensing area" y con objetos no humanos en movimiento continuo (cortinas, plantas con corriente de aire, ventiladores);
  - el lóbulo trasero puede detectar movimiento detrás del radar.
  
  Fuente: [manual LD2450](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf).
- **Precedente comunitario:** calibración de fondo con una grilla 80×80 de reflectores estáticos que luego se filtran, usada como "Ghost Detector (noise map)" — [PeterkoCZ91/HLK-LD2450-security](https://github.com/PeterkoCZ91/HLK-LD2450-security).

### Inferences
**Geometría para el LD2450** (1T2R, SIMO: el ángulo se mide solo en recepción; método de imágenes)
- **Segundo orden** (radar→pared→blanco→pared→radar): aparece en la imagen especular del blanco. Ese punto es el mismo para cualquier posición del radar, así que los dos LD2450 lo ven en el mismo sitio y la consistencia cruzada NO lo rechaza.
- **Primer orden** (radar→blanco→pared→radar y el inverso): el rango aparente es (d_directa + d_espejo)/2 y el Doppler ≈ el promedio del directo y del espejado.
  - En uno de los dos caminos la onda llega desde el blanco: el fantasma queda **en el mismo rumbo que el blanco real pero más lejos**. Es exactamente el caso "behind + similar Doppler" que TI penaliza.
  - En el otro llega desde la pared.
  - Ambos dependen de la posición del radar, así que difieren entre los dos módulos.

**Heurísticas baratas** (sin SNR ni nube de puntos)
- (1) **Regla de sombra**, adaptando `snrThreObscured`: un candidato nuevo con rango > rango de una pista confirmada + 0.4 m, rumbo a ±20° de ella y |Δv_r| < 0.3 m/s necesita M-de-N 5/7 en vez de 3/5.
- (2) **Movimiento acoplado:** si durante ~1 s la velocidad del candidato es la reflexión especular de la de una pista confirmada (misma rapidez, dirección espejada respecto de alguna recta) o está muy correlada con ella, marcarlo como fantasma probable.
- (3) **Persistencia:** los fantasmas son intermitentes (dependen de la geometría). Subir `det2active` es la herramienta más efectiva según TI; aplicarlo sobre todo mientras el usuario se mueve.
- (4) **Mapa de ruido propio en coordenadas del radar:** grilla de 10 cm aprendida con el usuario solo, caminando y girando 2–3 min en campo abierto y cerca de una pared. Las celdas con tasa de detección >2–5% de los frames pasan a zona de exclusión. Captura los brazos, la réplica y los ecos del propio cuerpo.
- (5) **Pantalla metálica trasera** (recomendación del manual) entre cada módulo y el cuerpo: corta el lóbulo trasero, que vería al propio usuario.
- (6) **Consistencia entre radares** (§2), solo contra el primer orden.
- (7) **Plausibilidad espacial:** en CQB no se conoce la geometría de las paredes, pero un candidato "más allá" de un eco estático persistente en el mismo rumbo (que sería una pared) es sospechoso. Requiere guardar los ecos estáticos de la sesión.

**Contexto de juego**
- En CQB (interiores, pasillos) cabe esperar mucho multitrayecto. En campo abierto domina el rebote en el suelo: con el radar a ~1 m de altura y sin medir elevación, el fantasma de suelo cae casi en el mismo rumbo y algo más lejos que el blanco, y probablemente el LD2450 lo funda con el real (no verificado).

### Gaps
- No encontré mediciones de fantasmas específicas del LD2450 (tasa, geometría), ni si su firmware suprime el multitrayecto.
- Faltan cifras cuantitativas sobre cuánto duran las pistas fantasma frente a las reales. El paper citado no las da.
- La afirmación del "reflection map" viene del resumen del buscador; no abrí el paper de ACM.

## 6. Metodología de evaluación con sesiones grabadas y reproducidas offline: métricas (OSPA/GOSPA, MOTA/MOTP, fragmentación) y ground truth barato (video de teléfono, marcas de cinta)

### Takeaway
Grabar el flujo crudo (bytes de cada trama + timestamp del ESP32 + id del radar, más las muestras crudas del IMU) y reproducirlo de forma determinista en la JVM con el mismo módulo Kotlin.
- **Métrica por frame:** GOSPA con α = 2, que se descompone en error de localización, objetivos perdidos y pistas falsas.
- **Métricas de pista:** cambios de ID, fragmentación, latencia de confirmación y, la más importante para la regla anti-fantasmas, **pistas falsas confirmadas por minuto según el estado de movimiento del usuario**.
- **Ground truth barato:** trayectorias guionadas sobre una grilla de cinta con metrónomo, verificadas con video de teléfono y homografía.
- **Sintonía sin ground truth:** consistencia del filtro vía NIS.

### Cited Findings
- **OSPA vs GOSPA vs T-GOSPA:**
  - OSPA penaliza que el número de objetos no coincida, pero no penaliza de forma intuitiva los perdidos ni los falsos;
  - GOSPA "penalizes localization errors for properly detected objects as well as missed and false detection errors", se descompone en "missed targets component" y "false tracks component", y evita el "spooky effect" de OSPA;
  - T-GOSPA, para trayectorias, suma los costos por cambio de pista (track switches).
  
  Fuentes: [GOSPA, ResearchGate (vía buscador)](https://www.researchgate.net/publication/291437227_Generalized_optimal_sub-pattern_assignment_metric); ["GOSPA and T-GOSPA quasi-metrics...", arXiv 2507.13706 (vía buscador)](https://arxiv.org/pdf/2507.13706); ["Probabilistic Trajectory GOSPA", arXiv 2506.15148 (vía buscador)](https://arxiv.org/html/2506.15148).
- **Código de GOSPA y T-GOSPA** (MATLAB, junto con filtros PMBM) — [Agarciafernandez/MTT](https://github.com/Agarciafernandez/MTT).
- **OSPA aplicado a pistas (OSPA(2))** — [ResearchGate (vía buscador)](https://www.researchgate.net/publication/321894862_OSPA_2_Using_the_OSPA_metric_to_evaluate_multi-target_tracking_performance). Introducción general a métricas de tracking — [MathWorks, Introduction to Tracking Metrics](https://www.mathworks.com/help/fusion/ug/introduction-to-tracking-metrics.html).

### Inferences
**Registro y replay**
- Qué guardar:
  - cada trama cruda (30 B) con el timestamp del ESP32 y el id del radar;
  - las muestras crudas del IMU;
  - la hora de llegada al Watch;
  - la versión de parámetros y de firmware.
- El replay debe ser determinista: tests de JVM con "golden files" y barridos de parámetros en CI. Como el tracker es Kotlin puro, el mismo código corre en el reloj y en el escritorio.

**Ground truth barato**
- (A) **Usuario quieto:** grilla de cinta en el piso (marcas cada 0.5–1 m, cubriendo −60° a +60° y 1–6 m). Un ayudante recorre rutas guionadas entre marcas al ritmo de un metrónomo (p. ej. 60 bpm, un paso a la marca siguiente), así que la trayectoria verdadera se conoce por guion.
  - Un teléfono en trípode, alto u oblicuo, graba para verificar tiempos y desvíos.
  - La homografía piso→imagen sale de ≥4 marcas (p. ej. `cv::findHomography` de OpenCV).
  - Anotar el punto medio entre los pies a 5–10 Hz basta.
- (B) **Usuario girando** sobre una marca con referencias de rumbo cada 45°: calibra el factor de escala, la latencia τ y la estabilidad del mundo en los giros.
- (C) **Usuario caminando** por una línea de cinta con metrónomo (pose del cuerpo conocida por guion) mientras el ayudante recorre otra ruta.
- (D) **Escenas sin nadie:** el usuario solo, caminando y girando en campo abierto, junto a una pared o pasillo y entre barricadas. Aquí toda pista confirmada es falsa, lo que mide directamente la regla anti-fantasmas.
- **Sincronizar video y ESP32:** un LED del ESP32 que parpadea con un código pseudoaleatorio registrado con timestamps, o una palmada/salto visible que deja un pico en el acelerómetro. A 30 fps la resolución es ±17 ms.

**Métricas**
- **Por frame, en el marco del cuerpo:** GOSPA con p = 2, c = 1 m, α = 2. Reportar por separado la RMSE de localización, el número de perdidos y el de falsos.
- **CLEAR MOT (MOTA/MOTP):** MOTA = 1 − Σ(FN + FP + IDSW)/ΣGT; MOTP = error medio de localización de los emparejados. Referencia canónica: [Bernardin & Stiefelhagen 2008](https://link.springer.com/article/10.1155/2008/246309); herramienta: [py-motmetrics](https://github.com/cheind/py-motmetrics). Ninguna de las dos se abrió en esta sesión.
- **Por pista:**
  - cambios de ID por cruce;
  - fragmentaciones;
  - MT/ML (mayormente seguidas / mayormente perdidas);
  - latencia de confirmación (primera aparición → confirmada);
  - error de posición al final del coasting (predicción vs reaparición);
  - **pistas falsas confirmadas por minuto**, separadas en quieto / girando / caminando;
  - **% de frames con los 3 slots ocupados**.
- **Consistencia del filtro:**
  - con ground truth, NEES;
  - sin ground truth, NIS: su media debería ser ≈ dim(z) = 2 para medición 2D. Un NIS medio ≫ 2 significa Q o R demasiado chicos; ≪ 2, demasiado grandes. (Criterio de libro de texto, Bar-Shalom; no verificado aquí.)
- **Primer experimento:** medir P_D y σ_r/σ_θ del LD2450 por celdas de rango × ángulo (0°, 30°, 45°, 60°; 1, 2, 4, 6 m). Alimenta directamente R(θ, r) y los umbrales de M-de-N.
- **Evitar sobreajuste:** separar las sesiones en sintonía y validación (hold-out), con al menos ~10–20 min por escenario.

### Gaps
- No verifiqué en esta sesión las URLs de CLEAR MOT ni de py-motmetrics (son referencias canónicas conocidas).
- No encontré protocolos publicados de evaluación de radares corporales (wearables) con ground truth barato; la receta es propia.

## 7. Valores por defecto recomendados (gates, Q/R, M/N, timeouts, umbrales) con justificación

### Takeaway
El pipeline propuesto es sólido. Los ajustes más importantes son:
- (1) meter la traslación del usuario en la predicción, para que "moverse en el mundo" signifique moverse respecto del suelo;
- (2) usar CWNA con q ≈ 0.4 m²/s³ y R polar con σ_θ ∝ 1/cosθ;
- (3) GNN con gate χ²₂ al 99% y tope euclidiano;
- (4) 3/5 contado por ventanas de 100 ms, más estricto para candidatos "en sombra" o mientras el usuario se mueve;
- (5) coasting según el contexto (quieto 6 s / en movimiento 1.5 s / saliendo 0.3 s y luego memoria de rumbo);
- (6) calibrar la latencia radar–IMU y el factor de escala del gyro.

Todos los valores son de arranque y hay que barrerlos en el replay.

### Cited Findings (anclas numéricas usadas en la tabla)
- **TI** (con deltaT de ejemplo de 120 ms):
  - `det2actThre` 3, `det2freeThre` 3, `active2freeThre` 6;
  - `static2freeThre` 20–500, `exit2freeThre` 3–5;
  - gate con ganancia 3 y límites de 2 m / 4 m/s;
  - `velocityThre` 0.05–0.1 m/s.
  
  Fuente: [TI Group Tracker Tuning Guide](https://e2e.ti.com/cfs-file/__key/communityserver-discussions-components-files/1023/3D_5F00_people_5F00_counting_5F00_tracker_5F00_layer_5F00_tuning_5F00_guide-_2800_1_2900_.pdf).
- **MATLAB `trackerGNN`:** confirmación `[2 3]`, borrado `[5 5]`, `AssignmentThreshold` 30 — [MathWorks](https://www.mathworks.com/help/fusion/ref/trackergnn-system-object.html).
- **LD2450:** 10 Hz, ≤3 objetivos, 6 m, ±60°, 250 MHz, 1T2R — [manual](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf).
- **MPU6050:** ZRO ±20 °/s, factor de escala ±3%, 0.005 °/s/√Hz — [PS-MPU-6000A-00](https://cdn.sparkfun.com/datasheets/Components/General%20IC/PS-MPU-6000A.pdf). ARW ≈ 0.0095 °/s/√Hz y bias instability ≈ 0.0013–0.0024 °/s — [Sensors 2020](https://pmc.ncbi.nlm.nih.gov/articles/PMC7506677/).
- **Fusion:** reposo < 3 °/s, rechazo de aceleración 10°, ganancia 0.5 — [xioTechnologies/Fusion](https://github.com/xioTechnologies/Fusion).
- **Detector de pasos de Android:** latencia < 2 s — [AOSP](https://source.android.com/docs/core/interaction/sensors/sensor-types).
- **Regla de ruido de proceso:** √W = 33–100% de a_max — ["Design of nearly constant velocity track filters..." (vía buscador)](https://www.researchgate.net/publication/4370081_Design_of_nearly_constant_velocity_track_filters_for_tracking_maneuvering_targets).

### Inferences
**Orden del pipeline propuesto**
1. Decodificar en signo-magnitud → descartar slots (0, 0, 0) → marcar los "rancios".
2. Exclusión: campo cercano + mapa de ruido propio.
3. Radar → cuerpo, con la pose calibrada.
4. Cuerpo → marco de tracking: rotar por yaw(t − τ) y aplicar la traslación del usuario como entrada de control.
5. GNN por trama de radar, en orden de timestamp.
6. Update del KF con R(θ, r), inflada en el solape.
7. Gestión de pistas: M-de-N por ventana, regla de sombra, regla de ego-movimiento, consistencia cruzada, coasting según contexto.
8. Render: predecir a "ahora", rotar al marco del cuerpo con el yaw más reciente y suavizar las correcciones.

**Tabla de valores de arranque** (Tipo: **F** = sale de una fuente; **I** = inferencia propia)

| Parámetro | Arranque | Rango a barrer | Justificación | Tipo |
|---|---|---|---|---|
| Decodificación X/Y/v | signo-magnitud: MSB=1 ⇒ +, magnitud = 15 bits bajos | — | Ejemplo del manual (0x86B1 → +1713 mm) | F |
| Slot válido | (x, y, v) ≠ (0, 0, 0) | — | Los slots vacíos llegan en 0x00 | F |
| Trama "rancia" | (x, y, v) idénticos ≥3 frames ⇒ no cuenta como HIT | 3–5 | Reporte de valores pegados en #10624 | I |
| Exclusión de campo cercano | r < 0.5 m | 0.4–0.8 m | Brazos, réplica y piernas; ajustar con el mapa de ruido | I |
| Mapa de ruido propio | celdas de 10 cm con tasa >3% de frames con el usuario solo ⇒ excluir | 2–5% | Precedente de la grilla de reflectores de la comunidad | I (F: precedente) |
| Pantalla trasera | placa metálica o cobre entre radar y cuerpo | — | Manual: el lóbulo trasero detecta movimiento detrás | F |
| Pose del radar | yaw ±30° nominal + palanca medida con cinta; yaw relativo calibrado con Umeyama | — | §2 | I |
| Latencia radar–IMU τ | calibrar; arrancar en 0 | 0–200 ms | Minimizar el NIS medio al girar; 50 ms ⇒ 9° a 180 °/s | I |
| Modelo de movimiento | CV 2D, estado [x, y, vx, vy] relativo al suelo | — | Pedestres; entrega X/Y directo | I |
| Ruido de proceso | CWNA, q = 0.4 m²/s³ (≈ σ_a 2 m/s² a 10 Hz) | 0.1–1.0 | Aceleraciones peatonales de 1.4–3 m/s²; regla 0.5–1× a_max; dt variable ⇒ CWNA | F + I |
| σ_r | 0.2 m | 0.1–0.3 | ΔR = 0.6 m (250 MHz), con interpolación; medir | I |
| σ_θ(θ) | 3.5°/cos θ, con cos θ ≥ cos 70° | 2–6° en boresight | Dependencia cosθ (TI); medir | F (forma) + I (valor) |
| Inflación de R en el solape | ×1.5 | 1–2 | Errores de los trackers internos correlados en el tiempo | I |
| Velocidad inicial | v0 = 0, σ_v0 = 1.5 m/s | 1–2.5 | Cubre caminar y trotar | I |
| Uso del campo velocidad | solo gating y clasificación hasta validarlo; si es Doppler, σ_vr = 0.15 m/s | 0.1–0.3 | Semántica no documentada | I |
| Asociación | GNN exacto (enumeración o Hungarian) | — | ≤3 × ≤8; mismo costo que greedy, sin cruces de ID | F (GNN) + I |
| Gate | d² ≤ 9.21 (χ²₂ al 99%) + tope euclidiano 1.5 m (confirmada) / 1.0 m (tentativa) | 9.21–13.8; 1–2 m | TI: ganancia 3 con límite de 2 m; el gate crece en coasting | F + I |
| Gate de velocidad (si se usa v_r) | abs(Δv_r) ≤ 1.0 m/s | 0.7–1.5 | TI limita a 4 m/s con nube de puntos; aquí hay centroides | I |
| Reordenamiento temporal | buffer de 150 ms; lo que llegue más tarde se descarta | 100–300 ms | Semántica "Neglect" de MATLAB | F + I |
| Confirmación | 3 HITs en las últimas 5 ventanas de 100 ms (ambos radares en la misma ventana = 1 HIT) | 2/3–4/6 | TI 3 frames; MATLAB 2/3; ≈0.3–0.5 s | F + I |
| Confirmación en sombra (detrás de una pista, ±20°, abs(Δv_r) < 0.3 m/s) | 5/7 | 4/6–6/8 | `snrThreObscured` de TI | F (idea) + I |
| Confirmación con el usuario en movimiento | 3/5 y velocidad respecto del suelo (estado del Kalman con la traslación compensada, donde lo estático queda en ≈ 0) superior a 0,6 m/s durante 5 ventanas | 0.4–0.8 m/s | −v_ego solo es la referencia para la velocidad aparente sin compensar | I |
| Borrado de tentativas | 2 ventanas vacías seguidas | 2–3 | TI `det2freeThre` 3 | F + I |
| Coasting de confirmada, perdida en movimiento | 1.5 s | 0.7–2 s | TI `active2free` = 6 × 120 ms ≈ 0.7 s; los slots se saturan | F + I |
| Coasting de confirmada, perdida estando quieta (abs(v) < 0.3 m/s) | 6 s, posición congelada | 3–10 s | Análogo a `static2freeThre`; el LD2450 deja de ver a quien está quieto | F (idea) + I |
| Coasting de confirmada, saliendo del FOV | 0.3 s, luego memoria "fuera de vista" de 5 s solo como rumbo | 0.2–0.6 s | Análogo a `exit2freeThre` (3–5 frames) | F (idea) + I |
| Protección del coasting | σ de gating ≤ 0.7 m; v ← v·e^(−dt/1 s) | τ 0.5–2 s | Tras 2 s de coasting σ ≈ 1.2 m | I |
| Consistencia cruzada | el otro radar con <3 objetivos, ≥10° dentro de su FOV y 3 frames sin respaldo ⇒ no confirmar | 2–5 frames | §2; no rechaza fantasmas de 2º orden | I |
| Herencia de ID de pantalla | pista nueva a <1 m de la predicción de una borrada hace <2 s ⇒ hereda ID | 0.7–1.5 m; 1–3 s | Menos fragmentación visible | I |
| Estado "girando" | abs(ω_v) > 20 °/s (filtrado 0.2 s) | 10–40 °/s | Separar el giro del balanceo postural | I |
| Estado "caminando" | desvío estándar de abs(a) en 1 s > 0.08 g, o un paso en los últimos 1.2 s (acelerómetro del cinturón) | 0.05–0.15 g | El detector del reloj tarda hasta 2 s | F (latencia) + I |
| Ego-velocidad | cadencia × L_paso (Weinberg con K calibrado); Q_ego anisotrópico: 0.3 m/s al frente, 0.9 m/s lateral | — | ±40% de variación entre personas sin calibrar; se camina de costado | F + I |
| Doppler ego (opcional) | usarlo solo con ≥3 detecciones consistentes y residuo < 0.2 m/s | — | Kellner; ≤6 detecciones | F (método) + I |
| Muestreo del IMU | 50 Hz (ideal: integrar a 100–200 Hz en el ESP32); gyro ±500 °/s; DLPF < 25 Hz | — | Nyquist; giros rápidos | I |
| Bias al arrancar | promedio de 2 s en reposo; se rechaza si la dispersión de ω supera ~0,5–1 °/s por eje, si la desviación de \|a\| > 0,02 g o si \|media\| > 45 °/s (sensor defectuoso); el umbral \|ω\| < 3 °/s se aplica solo a ω ya corregido por el sesgo | 1–5 s | ZRO inicial de hasta ±20 °/s; σ_bias ≈ 0.007 °/s | F + I |
| Recalibración en reposo | abs(ω) < 3 °/s por ≥3 s y desvío de abs(a) < 0.03 g ⇒ EMA con τ = 20 s | 10–60 s | Parámetros de Fusion + condición de acelerómetro | F + I |
| Gravedad | pasabajos τ = 1 s; saltar si abs(abs(a) − 1 g) > 0.15 g o > 10° | 0.5–2 s | Rechazo de aceleración de Fusion (10°) | F + I |
| Tasa de yaw | ω_v = ĝᵀω, integración trapezoidal; buffer de 3 s | — | Sensors 2019 | F |
| Factor de escala del gyro | calibrar una vez con 5 vueltas | — | ±3% ⇒ 2.7° cada 90° netos | F + I |
| Mapeo de relojes | mínimo móvil de (t_llegada − t_esp) en ventanas de 10 s + deriva lineal | 5–30 s | Retardo BLE variable | I |
| Render | 30 fps; predecir cada pista a "ahora", rotar con el yaw más reciente; suavizar correcciones con τ = 150 ms | 100–250 ms | Extrapolar no suma latencia; interpolar sumaría ~100 ms | I |
| Presentación | tentativas ocultas o tenues; coasting con alfa decreciente y círculo creciente; fuera de vista como flecha en el borde | — | Mostrar la incertidumbre | I |

**Notas de diseño relacionadas con la tabla**
- **La deriva de yaw a largo plazo no importa.** El render va en el marco del cuerpo, así que la rotación visible en la pantalla viene del IMU (50 Hz, interpolado) y se siente inmediata. El radar solo corrige la traslación de cada blip a 10 Hz. Esto separa la "suavidad" (IMU) de la "verdad" (radar).
- **La posición del reloj en la muñeca no importa.** El usuario orienta el reloj a mano y el "arriba" de la pantalla es el frente del cuerpo, así que no hace falta el giroscopio del Watch para el render.
- **Alerta de proximidad opcional.** Un nivel extra de salida: una alerta háptica cuando haya detecciones tentativas en movimiento a <2 m durante ≥2 ventanas, sin esperar a la confirmación. En el juego, llegar tarde cuesta más que una falsa alarma de cerca. Es una decisión de producto, no un resultado de la investigación.

### Gaps
- Todos los valores de σ_r, σ_θ0, τ, P_D, los umbrales de ego-movimiento y los coasting salen de razonamiento sin datos del LD2450 montado en el cinturón. Se decidirán en el primer barrido de parámetros con sesiones grabadas (§6).
- No pude verificar qué hace el LD2450 con su filtrado de zona interno (según la documentación de protocolo y ESPHome admite regiones). En particular, no sé si actúa ANTES de elegir los 3 objetivos: si fuera así, excluir en el propio sensor la zona del cuerpo liberaría slots, y eso importa mucho con la saturación.
- No encontré en las fuentes el modo de seguimiento "single target" vs "multi-target" del LD2450 ni su efecto en la latencia (no abrí el documento de protocolo V1.02/V1.03).
