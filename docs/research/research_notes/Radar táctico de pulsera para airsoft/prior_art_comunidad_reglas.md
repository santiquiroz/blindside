# Prior art, comunidad y reglas: radares corporales detectores de personas para airsoft/paintball/cosplay (contexto: proyecto "Blindside")

Convenciones de verificación usadas en todas las secciones: **[V]** = verificado leyendo la fuente (página, PDF o texto extraído); **[B]** = tomado del snippet/resumen del buscador sin haber leído la página completa (tratar con cautela); **[L30]** = dato del motor de la skill last30days v3.18.3 (ventana 2026-08-31 a 2026-09-30; los videos de YouTube llegaron sin fecha fiable, así que pueden ser más antiguos que la ventana). Fecha de corte de la investigación: 2026-09-30.

## 1. ¿Qué existe ya (DIY o comercial) tipo "heartbeat sensor" / motion tracker para airsoft, paintball, laser tag o cosplay? ¿Hay alguno de pulsera o smartwatch?

### Takeaway
Ya existen al menos tres "heartbeat sensors" estilo Call of Duty construidos con el mismo radar que Blindside (Hi-Link LD2450). El más documentado es el de ScienceShack en Hackaday.io: va montado al riel del arma con un XIAO RP2350 y una OLED. Hay además un repo en Raspberry Pi con licencia pendiente y un build "<50 USD" con ESP32 en YouTube. El referente de cosplay es el M314 de Rob Smith, con un radar de 60 GHz y unos 15 m de alcance. No encontré ningún producto comercial para airsoft ni ninguna variante de pulsera o smartwatch. La arquitectura de Blindside (radares en el cinturón + IMU + reloj por BLE con háptica) no aparece en el prior art encontrado.

### Cited Findings

#### Construcciones con LD2450 (mismo sensor que Blindside)
- [V] "Heartbeat Sensor from Modern Warfare 2", de J (ScienceShack): réplica funcional para airsoft con Hi-Link LD2450 (24 GHz), Seeed Studio XIAO RP2350 y una OLED de 2,42" SSD1306/SSD1309 azul. Sigue hasta 3 personas a 6 m máximo en un cono de 120°, con una UI de ~33 FPS porque el sondeo del sensor va desacoplado del bucle de dibujo. Va en una carcasa impresa en 3D con "friction hinge and M-LOK mount, allowing it to bolt directly to the side of a rifle's handguard". La página dice "Date Created: 06/09/2026" (formato MM/DD; equivale al 9 de junio de 2026; al 30/09/2026 figuraba "last updated 4 months ago") y enlaza el código en GitHub como "MW2-Heartbeat-Sensor (jrtage)". En el contenido leído no figura la licencia ni ninguna mención a legalidad, fair play o seguridad — [Hackaday.io](https://hackaday.io/project/205879-heartbeat-sensor-from-modern-warfare-2)
- [V] Problemas que reporta ese build:
  - El UART tuvo que ir por los pines de Serial1 (D4/D5) y no por GPIO 0.
  - "The Y-axis (distance) tracked perfectly, but the X-axis refused to update".
  - Tuvo que rotar el LD2450 90° mecánicamente.
  - Lo montó al revés y lo corrigió invirtiendo ejes por software.
  - Fuente: [Hackaday.io](https://hackaday.io/project/205879-heartbeat-sensor-from-modern-warfare-2)
- [L30] Video del mismo autor (canal Science Shack), "Building a REAL Heartbeat Sensor from Modern Warfare 2": "Now, I should be clear this doesn't detect heartbeats. You'd need some much more sophisticated hardware for that. But tracking presence, close enough for government work. And by government work, I mean airsoft." — [YouTube](https://www.youtube.com/watch?v=_ymsSmxXcHQ)
- [V] Repo `Bronsonalan/mw2-heartbeat-sensor`: LD2450 por serial sobre Raspberry Pi (Raspberry Pi OS Bookworm 64-bit), una "MW2-inspired phosphor radar HUD" con opción fullscreen y un modo replay para desarrollar sin hardware. La restricción de diseño es "Use Python standard library plus apt packages only". Sobre la licencia dice: "License will be added after the copyright owner is confirmed." Es una instalación de pantalla fija, no un wearable — [GitHub](https://github.com/Bronsonalan/mw2-heartbeat-sensor)
- [L30] PR #2 de ese repo (2026-09-01, cuerpo generado por un agente de Cursor):
  - Parseo de tramas del LD2450 "including sign-flag decoding and empty-slot dropping".
  - Tracking "nearest-neighbor ... with orientation, smoothing, confirmation, fading, and monotonic IDs".
  - Modos live, demo y replay.
  - Fuente: [GitHub PR](https://github.com/Bronsonalan/mw2-heartbeat-sensor/pull/2)
- [L30] Video "I make the COD heartbeat sensor for less than 50$". El proveedor entregó la transcripción traducida automáticamente al árabe. Según ella, el build usa un sensor mmWave "como el LD2450", un microcontrolador "como ESP32", pantalla, cableado y alimentación, y luego una carcasa realista impresa en 3D — [YouTube](https://www.youtube.com/watch?v=uvS8ywiVKRY)
- [L30] Video "I am Making a Heart Beat Sensor for Airsoft!!": es un vlog y el extracto no identifica el hardware — [YouTube](https://www.youtube.com/watch?v=RZBmTqI4rx4)
- [L30] Video "This $10 Radar Tracks You Through a Wall — LD2450 + ESP32-S3" (transcripción auto-traducida al árabe; el texto también menciona un "LD2415", así que el modelo exacto es ambiguo). El autor montó la antena en horizontal siguiendo publicaciones en línea en vez de la hoja de datos. Al reimprimir la carcasa para montarla en vertical, el rendimiento cambió "completamente". Lo probó con patinadores rápidos: no está pensado para largo alcance, pero a corta distancia responde con mucha precisión — [YouTube](https://www.youtube.com/watch?v=hhtvjLvmlTY)
- [L30] Alternativa de sensor: video "ESP32 + Rd-03D: mmWave Radar Multi-Human Tracking with Distance, Speed & Positioning!" — [YouTube](https://www.youtube.com/watch?v=cSI9vedf870)

#### Utilería de cosplay con radar real (Aliens M314 motion tracker)
- [B] Rob Smith (RobSmithDev) construyó un M314 funcional. El titular de Tom's Hardware dice: "...DreamHAT+ Radar module and Raspberry Pi is effective up to 15 meters, complete with sounds and a display" — [Tom's Hardware](https://www.tomshardware.com/maker-stem/microcontrollers-projects/hardcore-fan-celebrates-alien-earth-by-building-a-fully-working-m314-motion-tracker-replica-dreamhat-radar-module-and-raspberry-pi-is-effective-up-to-15-meters-complete-with-sounds-and-a-display)
- [B] Según los resúmenes del buscador:
  - Hardware: Raspberry Pi 4 Model B, LCD Waveshare de 2,4" (ILI9341) y el HAT DreamHAT+ Radar con el chip Infineon BGT60TR13C de 60 GHz, más un acelerómetro.
  - A diferencia de réplicas previas que solo reproducían video, muestra movimiento real y "can see through walls", con sonidos sintetizados.
  - Lo motivó el estreno de la serie Alien: Earth.
  - Fuentes: [Hackaday.io](https://hackaday.io/project/203817-a-real-working-alien-motion-tracker); [GitHub RobSmithDev/alienmotiontracker](https://github.com/RobSmithDev/alienmotiontracker); [lista de partes](https://alien.robsmithdev.co.uk/parts); [Raspberry Pi Official Magazine](https://magazine.raspberrypi.com/articles/m314-alien-motion-tracker)
- [B] Otros trackers M314 "funcionales" que aparecen en búsqueda (no leídos): [The RPF: "Aliens Motion tracker with video, sound and RADAR"](https://www.therpf.com/forums/threads/aliens-motion-tracker-with-video-sound-and-radar.304670/) y [Hackster: "This Aliens-Style Motion Tracker Replica Actually Works"](https://www.hackster.io/news/this-aliens-style-motion-tracker-replica-actually-works-009b600c18e8)

#### Réplicas no funcionales y contexto de "latido real"
- [B] Existen réplicas "mock" (por el título, solo estéticas): [Instructables: "How to Make an Airsoft Mock Heartbeat Sensor"](https://www.instructables.com/How-to-Make-an-Airsoft-Mock-Heartbeat-Sensor/) y [L30] el video "Airsoft Mock Heartbeat Sensor With RIS System" ([YouTube](https://www.youtube.com/watch?v=6WGQkMkJRro))
- [B] Hay un TikTok "Heartbeat sensor in real life" (@g4mingw0rld); no revisé el contenido — [TikTok](https://www.tiktok.com/@g4mingw0rld/video/7252774235997850907)
- [B] Existen módulos de radar que sí miden respiración y latido, pero en sujetos estáticos (Seeed MR60BHA1, "60GHz mmWave Static Breathing and Heartbeat"; solo leí el título) — [Seeed Wiki](https://wiki.seeedstudio.com/Radar_MR60BHA1/)

#### Pulsera / smartwatch
- [B] La búsqueda de radares de muñeca para airsoft solo devolvió el build de riel de ScienceShack y proyectos no relacionados de pulso cardíaco en smartwatch. No apareció ningún radar de pulsera para airsoft — [Hackaday.io](https://hackaday.io/project/205879-heartbeat-sensor-from-modern-warfare-2); [Core Electronics forum](https://forum.core-electronics.com.au/t/smartwatch-with-heart-rate-monitor-for-school-project/21304)
- [B] IEEE Spectrum, "Radar for Your Wrist": el snippet describe un radar de investigación con hasta 10 m de alcance y 15 mm de resolución, capaz de detectar respiración y latido. Es investigación, no airsoft; no lo leí completo — [IEEE Spectrum](https://spectrum.ieee.org/radar-for-your-wrist)

### Inferences
- Blindside no sería el primer "heartbeat sensor" con LD2450. Conviene acreditar explícitamente a ScienceShack/jrtage (Hackaday), al repo Bronsonalan (como idea, no como código, por su licencia pendiente) y a Rob Smith como referente del género.
- Lo que no encontré en ningún build existente:
  - dos radares en el cinturón para ampliar el FOV,
  - la compensación de orientación con IMU,
  - la visualización y háptica en un smartwatch vía BLE.
- Eso parece diferencial, pero la búsqueda fue acotada y X/Twitter no se pudo consultar.
- La orientación de montaje del LD2450 es un modo de fallo recurrente: el eje X muerto hasta rotar el módulo en ScienceShack, y la antena horizontal vs vertical en el video "$10 Radar". Para un montaje en el cinturón conviene documentar la orientación correcta y permitir invertir o voltear ejes por configuración. El proyecto `sextant` también ofrece un "left/right flip" (ver sección 6).
- La capa de tracking del PR de Bronsonalan (confirmación, desvanecimiento e IDs monótonos) es exactamente lo que necesita la función "vibrar cuando aparece una persona nueva", que sería un evento de nuevo ID confirmado. Solo se puede tomar como idea, porque el repo no tiene licencia.
- El M314 de Rob Smith también combina radar con acelerómetro, un paralelo con el MPU6050 de Blindside. No verifiqué para qué usa el acelerómetro.

### Gaps
- No encontré ningún producto comercial (tienda, Kickstarter, Etsy) de radar o heartbeat sensor funcional para airsoft, paintball o laser tag. Tampoco builds específicos de laser tag o paintball.
- No abrí el repo `jrtage/MW2-Heartbeat-Sensor`: desconozco su licencia y su estado actual.
- Los detalles del M314 (acelerómetro, rendimiento a través de paredes) vienen solo de resúmenes de búsqueda.
- X/Twitter no se pudo consultar (sin autenticación en la skill).
- No encontré evidencia pública del rendimiento del LD2450 montado en una persona en movimiento (ego-motion). Es una incógnita técnica clave que quedó fuera del alcance de esta nota.

## 2. Radares portátiles militares/policiales (contexto: Camero Xaver, L3Harris Range-R)

### Takeaway
Los equipos profesionales ven a través de paredes a decenas de metros y detectan personas quietas por la respiración: Range-R llega a 50+ ft en formato de mano, y el Xaver 1000 a 42 m pesando ~15-16 kg. El LD2450 (6 m, orientado a blancos en movimiento) juega en otra liga. Aun así, la narrativa de "ver a través de paredes" ya generó controversia de privacidad con Range-R.

### Cited Findings
- [V] Camero Xaver 1000 (artículo de EDR Magazine, 09/06/2022):
  - FOV de 120° en azimut y elevación, contra 80° del Xaver 800.
  - Alcance de 42 m, contra 20 m del 800.
  - 15,5 kg, 1 kg más que el 800.
  - Distingue "arms, legs, head" y mide la altura para diferenciar adultos, niños y animales. Según el resumen de la página, detecta personas estáticas por su latido ("heartbeats of stationary humans").
  - Ofrece vistas 3D y 2D, y penetra "cement, plaster, brick, concrete, reinforced concrete, adobe, drywall and other standard building materials".
  - La familia incluye Xaver 100 (1D), 400 (2D) y 800 (3D).
  - Fuente: [EDR Magazine](https://www.edrmagazine.eu/much-improved-through-the-wall-situational-awareness-with-cameros-xaver-1000)
- [B] Según resúmenes de la ficha de Camero y de un distribuidor: 16,5 kg con baterías, pantalla táctil de 10,1", 3 h de batería, Ethernet/USB/HDMI. **El peso contradice a EDR (15,5 kg).** Fuentes: [ficha Camero (PDF)](https://camero-tech.com/wp-content/uploads/2022/06/Camero-Xave-1000.pdf); [Mallory](https://www.mallory.com/4146093/product/camero-xaver1000)
- [V] Range-R (L-3 Communications):
  - Radar de mano que detecta movimiento a través de paredes sólidas, incluidos movimientos "as small as human breathing", a "50 feet or more".
  - Lo usaron el FBI y el U.S. Marshals Service; se vendieron ~200 unidades a 50 agencias de EE. UU.
  - Su uso salió a la luz en enero de 2015 (USA Today, Brad Heath) sin divulgación previa, lo que generó preocupaciones sobre "Fourth Amendment abuses".
  - Fuente: [Wikipedia](https://en.wikipedia.org/wiki/Range-R)
- [B] Specs de Range-R según los resultados del buscador: 3,18-3,42 GHz, 26 mW, radar SFCW, cono de 160°, 4 pilas AA, 1,2 lb, FCC ID YKD-25TWD3000 — [fccid.io](https://fccid.io/YKD-25TWD3000); [Wikipedia](https://en.wikipedia.org/wiki/Range-R)
- [B] El NIJ publicó un market survey de "Through-the-Wall Sensors for Law Enforcement" (no lo leí) — [OJP/NIJ PDF](https://www.ojp.gov/pdffiles1/nij/nlectc/240729.pdf)
- [V] El propio fabricante titula el manual del LD2450 "Motion target detection and tracking module": está orientado a blancos en movimiento, con 6 m y ±60° — [manual HLK-LD2450 (PDF)](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf)

### Inferences
- Para el README de Blindside conviene fijar expectativas: no ve a través de paredes de forma fiable, no detecta latidos y su alcance es de ~6 m. El propio ScienceShack lo aclara, y un post de r/embedded busca "better through-wall range than HLK-LD2450" (ver sección 6). Así se evita que el proyecto se confunda con un dispositivo de vigilancia tipo Range-R.

### Gaps
- No revisé el Xaver 100/400 de mano en detalle, ni los productos actuales de L3Harris (el Range-R se atribuye a L-3; la marca L3Harris es posterior).
- No verifiqué directamente la ficha PDF de Camero.

## 3. Reglas de campos/eventos y opinión de la comunidad sobre dispositivos electrónicos de detección (radar, térmica, visión nocturna, drones, sensores)

### Takeaway
No encontré ningún reglamento que mencione radares o "heartbeat sensors" explícitamente, así que el estatus más probable es **depende del campo/evento**. Los análogos más cercanos:
- La térmica está permitida en milsims grandes como American Milsim, pero se prohíbe o restringe en muchos campos pequeños por el argumento de que es un "wallhack".
- Los drones se permiten con aprobación y con límites al flujo de información.
- La guerra electrónica ofensiva y las cámaras para disparar tras esquinas se prohíben.

La única conversación reciente y viral (r/airsoftcirclejerk) reaccionó con humor y escepticismo sobre la utilidad, no con indignación.

### Cited Findings
#### EE. UU.
- [V] Reglamento 2026 de American Milsim:
  - "Use of Night Vision, Thermal, or other types of NOD is allowed (dummy cord that gear!)".
  - "Electronic warfare: jamming or listening in the opposition's radio frequency is strictly prohibited (unless cleared by the administration)."
  - "All private UAV / UAG(s) MUST be approved by American Milsim staff PRIOR TO DEPLOYMENT."
  - Fuente: [American Milsim Ruleset](https://americanmilsim.com/ruleset/)
- [B] MilSim City permite escanear canales de radio enemigos pero prohíbe otras formas de guerra electrónica como el jamming — [MilSim City](https://www.milsimcityairsoft.com/rules-of-engagement)
- [B] Flash Point Milsim (Bing Field): "Devices that allow you to shoot around corners, such as weapon mounted cameras and mirrors are not permitted" — [Bing Field](https://bingfield.com/index.php/2021/10/12/flash-point-milsim-rule-set/)
- [B] El TACSOP 3.3 de MilSim West (2021) restringe las radios FRS/GMRS a líderes de escuadra y pelotón, y enumera baterías de repuesto para NVG, GPS y ópticas como equipo normal — [MSW TACSOP PDF](https://milsimwest.squarespace.com/s/MSW-TACSOP-33.pdf)
- [V] Blog de Airsoft Station sobre políticas de campo (datos compilados hacia mayo de 2026):
  - Los campos permisivos con la térmica imponen:
    - distancia mínima de enfrentamiento de 50-75 ft para quien la usa,
    - sesiones "thermal-only",
    - límites de aumento de 2x-4x,
    - declaración obligatoria al registrarse ("Required declaration at check-in so opponents know you're running thermal").
  - Los campos pequeños o con mucha rotación de jugadores la prohíben.
  - Argumentos en contra: se "feels like wallhacks in video games", y la "Arms Race Dynamics: Once thermals enter a field's player base, pressure builds for everyone to get one".
  - Argumentos a favor: realismo y precio accesible.
  - El artículo no nombra campos concretos, solo regiones (Texas, Florida, California).
  - Fuente: [Airsoft Station](https://airsoftstation.com/airsoft-station-blog/thermal-optics-in-airsoft-field-policies-fairness-and-top-picks-2026/)

#### Europa (España / Reino Unido)
- [V] Normativa 2024 de Airsoft Castilla (España), extraída del PDF:
  - Regla 21: "Se permite la utilización de drones para los siguientes usos: elemento de avistamiento de enemigos y para grabar contenido audiovisual".
  - Quien usa el dron para avistar debe alejarse 40 m de su zona de reaparición y, "si es eliminada, deberá dejar de trasmitir la posición del enemigo a sus compañeros".
  - El dron de grabación "no podrá facilitar información relacionada con el juego a otros participantes".
  - Regla 15: "En ningún momento, los muertos podrán comunicarse con los vivos y transmitirle información relacionada con la partida, tanto de viva voz como por radio."
  - Fuente: [Airsoft Castilla PDF](https://airsoftcastilla.com/area_privada/documents/Normativa_Airsoft_Castilla_2024.pdf)
- [B] En el Reino Unido no encontré reglas de sitio que prohíban la térmica. Los resultados hablan de la legalidad de poseerla y de que cada sitio UKARA fija sus reglas. Hay un hilo comunitario "thermal sight do or dont??" que no leí — [Airsoft Forums UK](https://airsoft-forums.uk/topic/12403-thermal-sight-do-or-dont/); [Land Warrior Airsoft](https://www.landwarriorairsoft.com/blog/airsoft-in-the-uk-the-players-handbook)

#### Colombia / Latinoamérica
- [B] Un comercio colombiano afirma que el airsoft "no está clasificado como deporte oficial ni existe una ley específica que lo regule". Según ese sitio, se enmarca en la legislación general sobre armas, y los campos tienen normas internas (FPS, distancias, réplicas). Es una fuente comercial de baja autoridad — [Armas No Letales Colombia](https://armasnoletalescolombia.com/airsoft/)
- [B] Los drones en Colombia se rigen por el RAC 100 de la Aerocivil, vigente desde octubre de 2023, con operación en línea de vista — [iaerocol](https://iaerocol.co/blog/leyes-para-volar-drones-en-colombia/); [Aerocivil](https://www.aerocivil.gov.co/publicaciones/4081/informacion-importante-sobre-la-regulacion-de-la-aviacion-no-tripulada-uas/)

#### Opinión de la comunidad
- [L30] r/airsoftcirclejerk (subreddit satírico), "would a cardiac sensor be field legal" (2026-09-08; 2.487 puntos; 218 comentarios). Comentarios destacados que capturó el motor:
  - "You cant convince me thats not just a raspberry pi with a cheap chinese radar on it bruh 💀"
  - "I dont think that'll detect much of anything actually helpful."
  - "Def not cheats, that's sick af damn cod didn't lie"
  - No pude ver el post, porque WebFetch no puede leer Reddit — [Reddit](https://www.reddit.com/r/airsoftcirclejerk/comments/1wawd33/would_a_cardiac_sensor_be_field_legal/)
- [B] La búsqueda "heartbeat sensor airsoft allowed cheating radar milsim rules" no devolvió ningún reglamento que mencione radares o heartbeat sensors. Solo aparecieron reglas genéricas contra hacer trampa y contra la guerra electrónica — [American Milsim](https://americanmilsim.com/ruleset/); [MilSim City](https://www.milsimcityairsoft.com/rules-of-engagement)

### Inferences
- Un radar de pulsera es un dispositivo de ventaja informativa, en la misma categoría argumental que la térmica ("wallhack"). Lo esperable es:
  - Prohibición o rechazo en campos de skirmish casual.
  - Aceptación posible en milsim si se declara al registrarse.
  - Aceptación como "gadget" de escenario con límites: uno por equipo, rol de reconocimiento, alcance ya limitado a ~6 m.
- Regla práctica sugerida para el README: pedir autorización al organizador y declararlo antes de jugar.
- Las reglas de Castilla ("los muertos no transmiten información", drones que no pasan información) sugieren diseñar Blindside como herramienta de un solo jugador: sin compartir detecciones con el equipo y con un modo "eliminado" que apague la pantalla. Eso probablemente facilita su aceptación.
- La reacción de r/airsoftcirclejerk apunta más a curiosidad y escepticismo sobre la utilidad que a hostilidad. Es un solo hilo satírico, así que es evidencia débil.
- El post de r/airsoftcirclejerk (2026-09-08) llegó una semana después del PR del repo Bronsonalan (Raspberry Pi + LD2450), y el comentario "raspberry pi with a cheap chinese radar" encaja con ese build. Podría ser el mismo proyecto, pero no está confirmado.

### Gaps
- No encontré ningún reglamento de campos colombianos ni latinoamericanos sobre térmica, radar o sensores, ni una federación colombiana con reglamento publicado.
- No encontré ninguna regla explícita sobre radares en ningún país.
- No revisé reglamentos de paintball (ligas como NXL) ni de laser tag.
- Opinión en X/Twitter no disponible.

## 4. Regulación radio a 24 GHz (Colombia, EE. UU., UE): ¿es legal usar un módulo tipo LD2450 como hobby?

### Takeaway
Los tres marcos tienen banda de uso libre o sin licencia para radares de detección de movimiento de corto alcance cerca de 24 GHz:
- **Colombia (ANE Res. 105/2020, Anexo 1):** 24,05-24,25 GHz para "radiodeterminación", hasta 100 mW de P.I.R.E. **[V]**
- **EE. UU.:** FCC 15.249, 24,0-24,25 GHz, 250 mV/m a 3 m. **[V]**
- **UE:** ERC 70-03 / Decisión SRD, 24,05-24,25 GHz, 100 mW e.i.r.p., norma EN 300 440. **[B]**

El uso hobby de un módulo pequeño parece encajar en uso libre. Hay dos matices: el LD2450 barre 24,00-24,25 GHz, y ese tramo inferior queda fuera de la fila colombiana (y de la de radiodeterminación de la UE). Además, el fabricante no publica la P.I.R.E.

### Cited Findings
#### Colombia
- [V] Texto extraído del PDF compilatorio de la Resolución ANE 105 de 2020, Anexo 1, Tabla 1.2 ("Condiciones técnicas y operativas de las aplicaciones ... espectro de uso libre"). Filas de "Aplicaciones para radiodeterminación":
  - "10.5 - 10.6 GHz — P.I.R.E. máxima de 500 mW"
  - "13.4 - 14 GHz — P.I.R.E. máxima de 25 mW"
  - "**24.05 - 24.25 GHz — P.I.R.E. máxima de 100 mW**"
  - Fuente: [ANE, Res. 105/2020 compilatoria (PDF)](https://www.ane.gov.co/Sliders/archivos/Normatividad/2.%20DE%20LA%20ANE/Resoluciones%20ANE/Planeaci%C3%B3n%20de%20Espectro/Resoluci%C3%B3n_ANE_105_de_2020_Compilatori.pdf)
- [V] Definiciones del mismo anexo, §1.4: "Las aplicaciones para radiodeterminación incluyen dispositivos de corto alcance para la detección de movimiento y generación de alerta." Y: "Radiodeterminación se define como la determinación de la posición, velocidad u otras características de un objeto ... mediante las propiedades de propagación de las ondas radioeléctricas." — [ANE PDF](https://www.ane.gov.co/Sliders/archivos/Normatividad/2.%20DE%20LA%20ANE/Resoluciones%20ANE/Planeaci%C3%B3n%20de%20Espectro/Resoluci%C3%B3n_ANE_105_de_2020_Compilatori.pdf)
- [V] La Tabla 1.1 "Bandas ICM" incluye 24000-24250 MHz. Las ICM se definen como bandas "designadas para la operación de aplicaciones que producen y utilizan energía radioeléctrica en espacios reducidos con fines industriales, científicos y médicos". Por separado hay una fila de "Enlaces fijos punto a punto 24.05 - 24.25 GHz" con 2500 mV/m a 3 m; su §3.9.1 exige una antena fija de al menos 33 dBi y un haz de no más de 3,5° — [ANE PDF](https://www.ane.gov.co/Sliders/archivos/Normatividad/2.%20DE%20LA%20ANE/Resoluciones%20ANE/Planeaci%C3%B3n%20de%20Espectro/Resoluci%C3%B3n_ANE_105_de_2020_Compilatori.pdf)
- [V] Articulado de la Res. 105/2020 en el normograma de MinTIC:
  - Art. 4.1.2: el uso libre no requiere permiso de espectro.
  - Art. 4.1.3: "Los aparatos deben funcionar de conformidad con los estándares técnicos ... o contar con los certificados de homologación" que determine la CRC. El PDF completa el texto con "en los casos a que haya lugar".
  - La banda 24,25-27,5 GHz está reservada a IMT (art. 1.7.2).
  - La Tabla 1.2 muestra una adición de la Res. 153 de 2024 ("Aplicaciones RCA no específicas").
  - Fuentes: [Normograma MinTIC](https://normograma.mintic.gov.co/mintic/compilacion/docs/resolucion_ane_0105_2020.htm); [ANE PDF](https://www.ane.gov.co/Sliders/archivos/Normatividad/2.%20DE%20LA%20ANE/Resoluciones%20ANE/Planeaci%C3%B3n%20de%20Espectro/Resoluci%C3%B3n_ANE_105_de_2020_Compilatori.pdf)

#### EE. UU.
- [V] 47 CFR §15.245: en 24075-24175 MHz se permiten "intentional radiators used as field disturbance sensors, excluding perimeter protection systems", con un fundamental de 2500 mV/m y armónicos de 25,0 mV/m, medidos a 3 m — [Cornell LII](https://www.law.cornell.edu/cfr/text/47/15.245)
- [V] 47 CFR §15.249: en 24,0-24,25 GHz el fundamental es de 250 mV/m y los armónicos de 2500 µV/m, a 3 m. "Fixed, point-to-point operation" en 24,05-24,25 GHz exige una antena de al menos 33 dBi o un haz de no más de 3,5° — [Cornell LII](https://www.law.cornell.edu/cfr/text/47/15.249)

#### Unión Europea
- [B] ERC Recommendation 70-03 (CEPT):
  - Anexo 6 (radiodeterminación): 24,05-24,25 GHz, 100 mW e.i.r.p., sin requisitos de acceso ni mitigación.
  - Anexo 1, banda m (SRD no específicos): 24,00-24,25 GHz con los mismos parámetros.
  - La Decisión de Ejecución (UE) 2022/180, que modifica la 2006/771/CE, recoge 24,05-24,25 GHz a 100 mW e.i.r.p. para radiodeterminación.
  - Existe una modificación posterior, la (UE) 2025/105 del 22-01-2025.
  - La norma armonizada es ETSI EN 300 440 (V2.2.1, 2018-07).
  - No pude leer los textos primarios: el servidor de CEPT dio error de certificado y el PDF no se pudo parsear.
  - Fuentes: [ERC Rec 70-03 (CEPT)](https://docdb.cept.org/download/4635); [Decisión (UE) 2025/105 (CEPT docdb)](https://docdb.cept.org/download/4604); [ETSI EN 300 440 V2.2.1](https://www.etsi.org/deliver/etsi_en/300400_300499/300440/02.02.01_60/en_300440v020201p.pdf)
- [V] Wikipedia ("Short-range device") lista 24,00-24,25 GHz como ISM / SRD no específico (Anexo 1) y 24,05-24,25 GHz en el Anexo 6 (TLPR), pero sin límites de potencia — [Wikipedia](https://en.wikipedia.org/wiki/Short-range_device)

#### El módulo HLK-LD2450
- [V] Manual del HLK-LD2450 (texto extraído):
  - "Operating frequency band 24GHz~ 24.25GHz" y "Comply with FCC, CE, Commission-free certification standards".
  - Modulación FMCW, "Sweep bandwidth 250MHz", antena microstrip "one-transmitter-two-receiver".
  - Hasta 3 objetivos a 6 m, ±60°, refresco de 10 Hz.
  - Alimentación DC 5 V (>200 mA), consumo promedio de 120 mA.
  - No encontré ningún valor de P.I.R.E. o EIRP en el texto.
  - Fuente: [manual HLK-LD2450 (PDF)](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf)

### Inferences
- **Equivalencias de potencia (cálculo propio con E·d = √(30·EIRP)):**
  - Los 250 mV/m a 3 m de §15.249 equivalen a ~18,75 mW EIRP (~12,7 dBm).
  - Los 2500 mV/m de §15.245 equivalen a ~1,9 W EIRP (~32,7 dBm), pero solo en 24,075-24,175 GHz. Un FMCW que barre 250 MHz (24,00-24,25) no cabe en esa banda de 100 MHz, así que al LD2450 le aplicaría §15.249.
  - Los 100 mW de Colombia y de la UE equivalen a 20 dBm.
- **Colombia:** la fila de radiodeterminación (detección de movimiento, 100 mW) cubre el caso de uso de Blindside, pero solo de 24,05 a 24,25 GHz. El manual declara que el LD2450 opera desde 24,00 GHz. El tramo 24,00-24,05 está dentro de la banda ICM de la Tabla 1.1, pero ICM no es por sí misma una habilitación de uso libre para radiocomunicaciones. En una lectura estricta, el cumplimiento queda en zona gris.
  - Para uso hobby personal de un módulo de 6 m, el riesgo práctico parece bajo. Esta afirmación no tiene fuente; es mi estimación.
  - Lo defendible en el README es: "verifique la normativa local; en Colombia ver Res. ANE 105/2020, Anexo 1 (24,05-24,25 GHz, radiodeterminación, ≤100 mW P.I.R.E.)".
- La afirmación "Comply with FCC, CE" del fabricante es una declaración de cumplimiento de estándares, no prueba de una certificación otorgada al módulo. "Commission-free" probablemente alude a la exención china SRRC; es una inferencia.
- Publicar el código abierto no plantea problema radioeléctrico. La responsabilidad recae en quien construye y opera el dispositivo. Vender kits armados sí podría activar obligaciones de homologación o conformidad (art. 4.1.3 en Colombia, marcado CE, FCC). Tampoco verifiqué esto a fondo.
- Recomendación de diseño derivada: no modificar el firmware ni la antena del módulo, y no añadir amplificación. Los límites se refieren a la potencia radiada.

### Gaps
- No se encontró la P.I.R.E. real del LD2450 ni un FCC ID o certificado CE público del módulo (no hice búsqueda específica en la base de FCC).
- No leí de primera mano los textos UE (ERC 70-03, Decisión 2006/771/CE consolidada).
- No revisé si la CRC exige homologación para dispositivos de corto alcance de uso libre importados por particulares.
- No revisé las reglas de "marketing" de FCC Part 15 para dispositivos no certificados, ni las de Reino Unido (Ofcom IR 2030).
- No investigué la exposición RF (MPE/SAR) de un radar de 24 GHz llevado en el cuerpo.

## 5. Seguridad, ética y privacidad: encuadre para un radar vestible open source detector de personas, y cómo redactan sus descargos proyectos similares

### Takeaway
Los proyectos cercanos casi no traen descargos: ScienceShack no menciona legalidad ni fair play, y el repo Bronsonalan ni siquiera tiene licencia. El mejor modelo encontrado es RuView, que advierte explícitamente que no es dispositivo médico, de emergencia ni de seguridad. El antecedente Range-R muestra que "ver personas a través de paredes" dispara preocupaciones de privacidad. Blindside debería ser honesto sobre sus capacidades (no mide latidos, ~6 m, falla), declararse accesorio de juego y no herramienta de vigilancia, y exigir consentimiento y reglas del campo.

### Cited Findings
- [V] RuView (sensado WiFi/CSI con ESP32 para presencia, signos vitales y pose sin cámaras, licencia MIT) advierte: "these are research and prototype applications, not medical devices, emergency systems, or safety-certified controls". Etiqueta su modelo de pose como "first-cut" y se presenta "camera-free by design", con compuertas de privacidad para evitar fugas de identidad. No encontré en el README ningún descargo sobre la legalidad de desplegarlo en vigilancia o uso militar — [GitHub RuView](https://github.com/ruvnet/RuView)
- [L30] ScienceShack encuadra la capacidad con honestidad: "I should be clear this doesn't detect heartbeats ... But tracking presence, close enough for government work. And by government work, I mean airsoft." — [YouTube](https://www.youtube.com/watch?v=_ymsSmxXcHQ)
- [V] La página de Hackaday del mismo build no contiene declaraciones sobre legalidad, fair play, seguridad ni descargos — [Hackaday.io](https://hackaday.io/project/205879-heartbeat-sensor-from-modern-warfare-2)
- [V] El repo Bronsonalan publica código sin licencia: "License will be added after the copyright owner is confirmed." — [GitHub](https://github.com/Bronsonalan/mw2-heartbeat-sensor)
- [V] Range-R: se desplegó sin divulgación pública hasta 2015, y la privacidad generó preocupaciones de "Fourth Amendment abuses" — [Wikipedia](https://en.wikipedia.org/wiki/Range-R)
- [L30] Reacción en r/homeassistant al pabellón con 8 radares mmWave: "Crazy if these are available at a consumer level what do governments have" — [Reddit](https://www.reddit.com/r/homeassistant/comments/1wtf81i/an_art_studio_built_a_radartracked_pavilion_with/)
- [B] La academia estudia riesgos de privacidad del sensado inalámbrico de latido. Solo leí el título: "PrivyWave: Privacy-Aware Wireless Sensing of Heartbeat" — [arXiv 2511.02993](https://arxiv.org/pdf/2511.02993)
- [V] Las reglas de campo tratan la seguridad como innegociable: "La utilización de gafas de protección será requisito indispensable para poder participar" — [Airsoft Castilla PDF](https://airsoftcastilla.com/area_privada/documents/Normativa_Airsoft_Castilla_2024.pdf)

### Inferences
- Elementos de descargo recomendados para Blindside. Es una síntesis mía a partir de los patrones anteriores, no un texto copiado:
  1. Accesorio de juego / proyecto educativo. No es dispositivo de seguridad, médico, táctico real ni de vigilancia.
  2. Capacidades honestas: detecta movimiento de personas a ~6 m en ±60° por radar; no detecta latidos; puede fallar con coberturas, quietud u orientación incorrecta.
  3. Usar solo en partidas, con conocimiento del organizador y de los participantes. Declararlo al registrarse, sin excepciones. Prohibido usarlo para detectar o seguir personas sin su consentimiento fuera del juego.
  4. Cumplir la normativa local de espectro (ANE / FCC / UE) y no modificar la potencia ni la antena.
  5. Privacidad por diseño: procesamiento local, sin cámara, sin nube, sin guardar ni compartir detecciones (enlace BLE solo con el reloj propio).
  6. Marcas: "Call of Duty", "Modern Warfare" y "Aliens/M314" pertenecen a sus dueños; sin afiliación; no redistribuir assets del juego. El repo Bronsonalan incluye assets "phosphor" con licencia sin confirmar, y ScienceShack presume de una "game-accurate UI".
  7. Sin garantía, según la licencia elegida.
- Para el modo de juego, la regla "los muertos no transmiten información" sugiere añadir un modo "eliminado" que apague el radar o la pantalla hasta reaparecer. Es un gesto de fair play que puede facilitar que los organizadores lo acepten.

### Gaps
- No investigué la aplicabilidad de la ley colombiana de protección de datos (Ley 1581 de 2012) a datos de posición anónimos de radar.
- No investigué la seguridad RF de un emisor de 24 GHz llevado en el cuerpo.
- No encontré ningún proyecto de radar para airsoft con descargo publicado que sirva de modelo directo.
- No revisé los textos de privacidad de sensores comerciales como Everything Presence o Aqara FP2.

## 6. last30days (OBLIGATORIO): qué se dijo en los últimos 30 días sobre (1) LD2450 / mmWave, (2) radar o heartbeat sensor DIY para airsoft, (3) apps Wear OS / Galaxy Watch con sensores BLE propios

### Takeaway
La skill **sí se ejecutó** (last30days v3.18.3, tres corridas, ventana 2026-08-31 a 2026-09-30). Lo que muestra:
- **LD2450:** plataforma hobby muy activa, sobre todo en Home Assistant y ESPHome, con PRs casi semanales en GitHub.
- **Airsoft:** el "heartbeat sensor" es un meme visible, con un hilo de 2.487 puntos y un repo nuevo con radar LD2450.
- **Wear OS:** casi no hay conversación pública sobre apps que consuman sensores BLE propios; solo desarrollo indie de apps y caras de reloj, y sideloading.

X/Twitter no quedó cubierto, e Instagram falló parcialmente.

### Cited Findings

#### Metodología y cobertura
- Pasos de la ejecución [L30]:
  - Gate de primera ejecución superado (`SETUP_COMPLETE=true`) y Python 3.12.
  - Plan de consultas propio (`--plan`), con subreddits inferidos por tema: esp32, homeassistant, Esphome, arduino, embedded, homeautomation / airsoft, paintball, lasertag, milsim, CallOfDuty, cosplayprops, Aliens / WearOS, GalaxyWatch, androiddev, smartwatch.
  - Se corrió en modo compacto y otra vez en JSON para recuperar todos los ítems.
- Estado de las fuentes [L30]:
  - Activas: Reddit, YouTube (por proveedor de respaldo, sin yt-dlp local, así que las fechas de video son desconocidas), TikTok, GitHub, Pinterest, HN, Polymarket y Threads.
  - Instagram devolvió HTTP 404 (parcial).
  - **X no disponible** por falta de autenticación.
  - HN: 0 resultados en las corridas 1 y 2; 6 ítems no pertinentes en la 3.
- Totales del motor [L30]:
  - Corrida 1: 19 hilos de Reddit (2.762 upvotes, 329 comentarios), 11 videos de YouTube, 6 TikTok (1,32 M vistas, mayormente off-topic), 21 pins, 13 ítems de GitHub.
  - Corrida 2: 14 hilos de Reddit (4.773 upvotes, 448 comentarios), 7 YouTube, 13 TikTok (6,6 M vistas, clips genéricos de airsoft sin radar), 26 pins, 2 GitHub, 1 reel de Instagram.
  - Corrida 3: 20 hilos de Reddit (1.151 upvotes, 342 comentarios), 11 YouTube, 8 TikTok, 6 HN, 7 GitHub.

#### (1) LD2450 / radares mmWave
- [L30] GitHub `ruvnet/RuView` PR #2052 (2026-09-29): "Adds HLK-LD2450 (24 GHz, multi-target x/y) support to the ESP32 mmWave driver, a per-frame radar targets packet so hosts can pair radar positions with CSI frames (e.g. as localisation training labels)" — [GitHub](https://github.com/ruvnet/RuView/pull/2052)
- [L30] `esphome/esphome.io` PR #7461 (2026-09-28): receta de "coordinate zone editor", "a self-contained web component that maps radar targets and edits rectangular zones ... for 2D sensors (LD2450, X/Y in millimetres...)" — [GitHub](https://github.com/esphome/esphome.io/pull/7461)
- [L30] `Makabakamanihong/ETEC306` (2026-09-30):
  - Raspberry Pi 4B + LD2450 (UART) + pan/tilt PCA9685.
  - "Implement LD2450 UART parsing (~10 Hz), target association, and Pan/Tilt conversion ... Measure software path latency toward the ≤250 ms coarse-align goal."
  - Fuentes: [issue 1](https://github.com/Makabakamanihong/ETEC306/issues/1); [issue 2](https://github.com/Makabakamanihong/ETEC306/issues/2)
- [L30] `davidcoulson/sextant` PR #10 (2026-09-28): "LD2450-type radars (Everything Presence Pro/Lite, Apollo R PRO-1) are placed on the plan with a facing and an optional left/right flip" — [GitHub](https://github.com/davidcoulson/sextant/pull/10)
- [L30] Otros PRs del mes:
  - `brunohorta82/EasyIot` #138 (2026-09-04): soporte LD2410/LD2450/LD2460 y "2D presence visualizer" — [GitHub](https://github.com/brunohorta82/EasyIot/pull/138)
  - `Matysh/houseplan-card` #485 (2026-09-07, 30 comentarios): radares de presencia en plano, calibración, blancos vivos, zonas, nube de puntos — [GitHub](https://github.com/Matysh/houseplan-card/issues/485)
  - `irfanyus/IhbarBot` #1 (2026-09-08): sketch ESP32 y cableado del LD2450 — [GitHub](https://github.com/irfanyus/IhbarBot/pull/1)
- [L30] Reddit r/embedded (2026-09-27; cross-post en r/ArduinoProjects), "Any DIY-accessible radar modules with 2D tracking and better through-wall range than HLK-LD2450?". Cita del post: "I need actual target position data — ideally angle + distance / X-Y coordinates ... My target is roughly 10 m through a wall" — [Reddit](https://www.reddit.com/r/embedded/comments/1wre3ox/any_diyaccessible_radar_modules_with_2d_tracking/)
- [L30] Reddit r/homeassistant (2026-09-29; 265 pts; 14 comentarios), "An art studio built a radar-tracked pavilion with 8 mmWave sensors and open sourced the 3D printable mount!". Comentario destacado: "Not sure I understand a use case for multiple radar sensors? ... Crazy if these are available at a consumer level what do governments have" — [Reddit](https://www.reddit.com/r/homeassistant/comments/1wtf81i/an_art_studio_built_a_radartracked_pavilion_with/)
- [L30] Otros hilos de Reddit (el contenido no se pudo leer; solo título y métricas):
  - r/esp32 "mmWave Radar w/Captive Portal" (2026-09-06; 162 pts; 20 com.) — [Reddit](https://www.reddit.com/r/esp32/comments/1w8hx5a/mmwave_radar_wcaptive_portal/)
  - r/arduino "Portable mmWave Radar" (2026-09-08; 67 pts; 23 com.) — [Reddit](https://www.reddit.com/r/arduino/comments/1waymm8/portable_mmwave_radar/)
- [L30] Videos de YouTube sobre LD2450 (fecha desconocida):
  - "Beginner DIY ESPHome mmWave Presence Sensor | HLK-LD2450 + ESP32" (fixtSE): "every sensor you wanted the aara [Aqara] fp2 to have for just a fraction of the price" — [YouTube](https://www.youtube.com/watch?v=cPac-9K2xEc)
  - "JMT HLK-LD2450: Best 24GHz Radar Sensor for Smart Home?" — [YouTube](https://www.youtube.com/watch?v=HDmzBFgNEys)
  - "Ultimate DIY mmWave Presence Sensor! ESP32 + LD2450" — [YouTube](https://www.youtube.com/watch?v=44gf2heXDG0)
  - "This $10 Radar Tracks You Through a Wall — LD2450 + ESP32-S3" — [YouTube](https://www.youtube.com/watch?v=hhtvjLvmlTY)

#### (2) Radar o heartbeat sensor DIY para airsoft
- [L30] r/airsoftcirclejerk "would a cardiac sensor be field legal" (2026-09-08; 2.487 pts; 218 com.). Comentarios citados en la sección 3 — [Reddit](https://www.reddit.com/r/airsoftcirclejerk/comments/1wawd33/would_a_cardiac_sensor_be_field_legal/)
- [L30] `Bronsonalan/mw2-heartbeat-sensor` PR #2 (2026-09-01), la capa de datos radar LD2450 con tracking (detalle en la sección 1) — [GitHub](https://github.com/Bronsonalan/mw2-heartbeat-sensor/pull/2)
- [L30] Cuatro videos de YouTube sobre heartbeat sensors (fecha no fiable; pueden ser anteriores a la ventana):
  - Science Shack, "Building a REAL Heartbeat Sensor from Modern Warfare 2" — [YouTube](https://www.youtube.com/watch?v=_ymsSmxXcHQ)
  - "I make the COD heartbeat sensor for less than 50$" — [YouTube](https://www.youtube.com/watch?v=uvS8ywiVKRY)
  - "I am Making a Heart Beat Sensor for Airsoft!!" — [YouTube](https://www.youtube.com/watch?v=RZBmTqI4rx4)
  - "Airsoft Mock Heartbeat Sensor With RIS System" — [YouTube](https://www.youtube.com/watch?v=6WGQkMkJRro)
- [L30] Pinterest mostró interés estético o de cosplay: arte del heartbeat sensor en ArtStation y un post "Roach check your heartbeat sensor" — [ArtStation](https://www.artstation.com/artwork/BmnPn8); [Instagram](https://www.instagram.com/p/C_lJwTIO-Ex/)
- [L30] TikTok y r/airsoft y r/MilSim en la ventana fueron ruido genérico, sin radares. Ejemplos: "A knife in airsoft", 1.867 pts ([Reddit](https://www.reddit.com/r/airsoft/comments/1wq64va/a_knife_in_airsoft/)), y clips de TikTok de hasta 3,7 M de vistas sin relación con detección ([TikTok](https://www.tiktok.com/@valiant_airsoft/video/7690701378821344525))

#### (3) Galaxy Watch / Wear OS con sensores BLE propios
- [L30] No apareció ninguna discusión directa sobre apps Wear OS que consuman periféricos BLE propios (ESP32, sensores caseros). Lo más cercano:
  - Video "Connect Any Cycling Sensor to Your Samsung Galaxy Watch (Setup Guide)". Su transcripción, auto-traducida al árabe, dice que en el Galaxy Watch no se puede conectar un medidor de potencia o un sensor de cadencia "directamente, no de forma nativa", así que hacen falta apps de terceros — [YouTube](https://www.youtube.com/watch?v=lsRAtmTazuc)
- [L30] Desarrollo indie en Wear OS durante la ventana:
  - r/WearOS, "I made a phone game that uses your Wear OS watch as a motion controller" (2026-09-23; 33 pts) — [Reddit](https://www.reddit.com/r/WearOS/comments/1wo1oq3/i_made_a_phone_game_that_uses_your_wear_os_watch/)
  - r/WearOS, "Ever get dizzy when you stand up? I made a free Wear OS app to measure what your heart is doing (POTS Syndrome)" (2026-09-17; 81 pts) — [Reddit](https://www.reddit.com/r/WearOS/comments/1wiz87m/ever_get_dizzy_when_you_stand_up_i_made_a_free/)
  - r/WearOS, "Just got a Galaxy Watch 7 and noticed the distinct lack of Wear OS games, so I made one" (2026-09-23; 27 pts; 18 com.) — [Reddit](https://www.reddit.com/r/WearOS/comments/1wo54bt/just_got_a_galaxy_watch_7_and_noticed_the/)
- [L30] PRs de GitHub con compañeros Wear OS:
  - `galaxyssi/GalaxySSI` #3046, "add Wear OS companion with Android protocol reuse" (2026-09-13) — [GitHub](https://github.com/galaxyssi/GalaxySSI/pull/3046)
  - `iflyhere/enroute` #1, "Companion device support for Wear OS" (2026-09-02) — [GitHub](https://github.com/iflyhere/enroute/pull/1)
  - `GhostCodeByte/Runback` #1, "Add Android and Wear OS apps with automatic test APK releases" (2026-09-06) — [GitHub](https://github.com/GhostCodeByte/Runback/pull/1)
- [L30] El sideloading en Galaxy Watch sigue siendo tema de tutoriales, por ejemplo "How to Install APKs on Galaxy Watch 4/5/6 with ADB Debugging over Wi-Fi on Wear OS 4" y "How to Install Any App on Samsung Watch 6/7/8" (fechas desconocidas) — [YouTube](https://www.youtube.com/watch?v=Oab9GabAuxc); [YouTube](https://www.youtube.com/watch?v=_hVm96ar3Uk)
- [L30] Un post en r/GalaxyWatch se titula "Wear os 9 beta 3 on galaxy watch 8 44mm bluetooth variant. US" (2026-09-22). Solo es el título; la nomenclatura de la versión no está verificada — [Reddit](https://www.reddit.com/r/GalaxyWatch/comments/1wmym3s/wear_os_9_beta_3_on_galaxy_watch_8_44mm_bluetooth/)

### Inferences
- El ecosistema LD2450 está maduro para el uso fijo en domótica: zonas, visualizadores, flips de orientación, fusión con CSI. Los patrones de "orientación + flip" (sextant) y "asociación de blancos + latencia" (ETEC306) son reutilizables conceptualmente para Blindside.
- El nicho de radar para airsoft tiene tracción cultural (el meme de CoD, hilos virales) pero pocos builds serios, y ninguno vestible con reloj.
- En Wear OS, la falta de conversación sobre periféricos BLE propios sugiere que Blindside tendrá poco apoyo comunitario directo. El soporte nativo de sensores BLE de terceros en Galaxy Watch es limitado, como muestra el caso del ciclismo, lo que refuerza la necesidad de una app nativa propia.

### Gaps
- X/Twitter no se consultó (sin autenticación en la skill), e Instagram devolvió errores 404.
- Los videos de YouTube llegaron sin fecha fiable, y varias transcripciones vinieron traducidas automáticamente al árabe por el proveedor. Las citas de esos videos son paráfrasis de traducciones.
- No pude leer el contenido de los hilos de Reddit (WebFetch bloqueado para Reddit). Solo tengo título, métricas y los comentarios que el motor capturó.
- No ejecuté el paso 2.5 de la skill (anexar resultados web al archivo raw) para respetar la restricción de no escribir otros archivos. La salida cruda del motor quedó solo en el scratchpad temporal de la sesión.
