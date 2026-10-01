# Ideas creativas y diseño UX para Blindside: radar táctico de pulsera para airsoft (con extensiones de equipo / IFF)

Alcance: UX de la pantalla (Galaxy Watch 7, Wear OS), disciplina de luz, lenguaje háptico, funciones de equipo/IFF, otras ideas creativas y priorización v1 / v1.5 / v2. Convenciones: **[V]** = verificado leyendo la fuente (página o PDF); **[S]** = tomado del snippet/resumen del buscador sin abrir la página (usar con cautela). Todo lo marcado **IDEA** en "Inferences" es diseño propio, no un hecho con fuente. Investigación cerrada el 2026-09-30.

## 1. ¿Cómo hacer una UI de radar legible de un vistazo en un reloj redondo pequeño? (simbología de juegos, radar/ATC, guías Wear OS y One UI Watch, sol vs oscuridad, tamaños mínimos)

### Takeaway
Las guías de Google/Samsung, la simbología ATC de la FAA y las mecánicas de COD apuntan a lo mismo: fondo negro, pocos símbolos del mismo tamaño, un atributo visual por variable (posición = dónde, relleno = confianza, opacidad = edad), la edad del contacto siempre visible ("coasting") y una metáfora de 180° al frente que los jugadores ya conocen del Heartbeat Sensor. Hay una restricción dura de plataforma: en ambient mode Wear OS solo actualiza una vez por minuto. Por eso el modo "always on" con radar en vivo tiene que ser pantalla interactiva mantenida encendida (`keepScreenOn`), y eso cuesta mucha batería.

### Cited Findings
**Guías Wear OS / Samsung**
- [V] Google: "People wearing watches are regularly in situations where their attention is limited, whether they are talking, gesturing, or running to catch a bus"; recomienda "Test your designs in situations that involve user movement and distraction to make sure the design is usable at a glance." — [Design for wearables (Android Developers)](https://developer.android.com/design/ui/wear/guides/get-started/design-for-wearables)
- [V] Las pantallas redondas tienen "22% less UI space than square screens" y "require larger margins to create more legible text"; recomendación: "Design for round devices first". — [Design for wearables](https://developer.android.com/design/ui/wear/guides/get-started/design-for-wearables)
- [V] Touch targets: "48dp x 48dp. Due to the small screen size on Wear OS, there are some situations where 40dp x 40dp is allowed"; para TalkBack, los ítems deben medir al menos 32dp de alto. — [Accessibility on Wear OS](https://developer.android.com/training/wearables/accessibility)
- [S] One UI Watch de Samsung (guía de la era Tizen) define el principio "Scannable": las interacciones con el reloj "take place in a split second", así que la app debe mostrar la información "at a glance". También "Easy to follow": evitar diseños complicados. — [One UI Watch – Design Principles](https://developer.samsung.com/one-ui-watch-tizen/principle.html)
- [V] Ambient mode: "The `AmbientTickEffect` callback is invoked only periodically -- typically once per minute -- to conserve power". Para datos que cambian rápido, Google pide reemplazar los valores en vivo por "--" y así no mostrar información engañosa. — [Always-on apps and system ambient mode](https://developer.android.com/training/wearables/always-on)
- [V] En la misma página: con `isLowBitAmbientSupported` hay que desactivar el anti-aliasing y evitar gradientes. Con `isBurnInProtectionRequired`: "avoid solid white areas and place critical UI elements at least 10 pixels away from the edge of the screen". — [Always-on](https://developer.android.com/training/wearables/always-on)
- [V] En la misma página: `keepScreenOn` impide entrar en ambient, pero "Use this with extreme caution as it severely impacts battery life". En Wear OS 5+ la Ongoing Activity API mantiene la app visible mientras dura una tarea en curso (el ejemplo es un workout). — [Always-on](https://developer.android.com/training/wearables/always-on)
- [V] "The Wear OS App Quality guidelines specify that only 15% of pixels are illuminated in ambient mode"; "Well designed ambient displays contain only essential information, and they minimize the number of pixels that are illuminated." — [Save power using ambient mode requirements](https://developer.android.com/training/wearables/wff/ambient)

**Hardware del reloj**
- [S] Galaxy Watch7: AMOLED circular de 1,3" (40 mm) o 1,5" (44 mm), 2.000 nits de brillo pico y cristal de zafiro. Conectividad: Bluetooth 5.3, Wi-Fi 2,4/5 GHz, NFC y GPS de doble frecuencia. No se menciona UWB. — [Android Central](https://www.androidcentral.com/wearables/samsung-galaxy-watch-7)

**Mecánicas de juego (Call of Duty)**
- [V] Heartbeat Sensor en MW2019/Warzone:
  - Hace un ping cada 4 s y cubre "a 180-degree area" al frente.
  - Alcance: 50 m en Warzone y 30 m en multijugador.
  - Muestra posición y distancia, pero cada lectura es "a snapshot at the time of the ping". La distancia aparece antes de que el ping termine.
  - No da información de altura.
  - El perk Ghost lo contrarresta; Cold-Blooded no.
  — [Mein-MMO](https://mein-mmo.de/en/best-tactical-item-in-cod-warzone-heres-why-you-should-use-it-this-is-what-makes-it-strong,496689/)
- [S] El sensor pita al detectar a un enemigo y el tono sube cuanto más cerca está. Este dato viene de un resumen del buscador que incluye la wiki de Fandom; al abrirla, la wiki devolvió HTTP 402, así que no está verificado. — [Call of Duty Wiki – Heartbeat Sensor](https://callofduty.fandom.com/wiki/Heartbeat_Sensor)
- [S] Minimapa de COD: históricamente, disparar sin supresor ponía un punto rojo en el minimapa del rival. MW2019 y MW2 (2022) lo quitaron y solo el UAV muestra enemigos. Según la nota, los desarrolladores "do not want to punish players for firing" y quieren que el jugador busque el origen del disparo en vez de ir directo al punto. — [Charlie INTEL](https://www.charlieintel.com/call-of-duty-modern-warfare-2/modern-warfare-2-minimap-will-not-reveal-enemy-red-dots-198697/)
- [S] El cambio fue polémico: [Kotaku](https://kotaku.com/call-of-duty-modern-warfare-minimap-infinity-ward-fps-1849563125). Según un blog, Black Ops 7 volvió al sistema tradicional: [mitchcactus.co](https://mitchcactus.co/blog/call-of-duty/bo7-minimap-explained/).

**Aviación / ATC (edad del contacto, trails, simbología)**
- [S] En el Traffic Information Service (TIS) de la FAA, la información pasa a "coasting" cuando tiene más de 6 s. A los 12 s se muestra "No Traffic". — [FAA AIM, cap. 4 secc. 5](https://www.faa.gov/air_traffic/publications/atpubs/aim_html/chap4_section_5.html)
- [V] Estudio de simbología de la FAA (Friedman-Berg, Allendoerfer, Deshmukh, 2010), resultados:
  - "On a static STARS display, the sole source for heading information is history trails".
  - Cuando los símbolos y los trails apuntan en direcciones distintas, el display se ve "more cluttered" y la información resulta inconsistente.
  - Más de la mitad de los controladores dijo que codificar la dirección con triángulos les ayudaba.
  — [DOT/FAA/TC-TN-10/04](https://hf.tc.faa.gov/publications/2010-moving-toward-an-air-traffic-control-display-standard/full_text.pdf)
- [V] Recomendaciones del Apéndice F del mismo informe:
  - "Code only the most tactically useful information".
  - "Make use of existing symbol sets unless there is a defined advantage to creating novel symbols" (positive transfer).
  - Factores de legibilidad a medir: resolución, contraste, tamaño del display, distancia y ángulo de visión, arco visual del símbolo, iluminación y fondo.
  - "The minimum size of the symbol must preserve the key features that define it".
  - "Use a single size to represent all aircraft": a los observadores les cuesta más detectar la colisión de dos objetos de distinto tamaño.
  - El rumbo se representa "using the point of an arrow (e.g., triangle, chevron)".
  - "Avoid using similar features to represent different information parameters" y "Avoid using a combination of features to represent a single information parameter".
  - Hay que definir qué símbolos deben destacar sobre el resto (alertas).
  - El estudio codificó estados con rojo/amarillo/verde, porque "matched standard color coding conventions".
  — [FAA 2010](https://hf.tc.faa.gov/publications/2010-moving-toward-an-air-traffic-control-display-standard/full_text.pdf)

**Antecedentes de UI táctica en reloj**
- [S] WearTAK lleva un subconjunto de ATAK a relojes Wear OS standalone (por ejemplo, Galaxy Watch Ultra). Ofrece mapas tácticos, "MIL-STD-2525D point-dropping", alertas fisiológicas y chat rápido. Transmite los datos como Cursor-on-Target (CoT) XML/Protobuf. Su función "Bloodhound" muestra rumbo y distancia a un ítem del mapa. — [weartak.com](https://weartak.com/)
- [S] TAKWatch es un plugin open source de ATAK que se comunica con relojes Garmin. — [GitHub TDF-PL/TAKWatch](https://github.com/TDF-PL/TAKWatch)

### Inferences
- **Consecuencia de plataforma.** Como ambient mode actualiza unas 1 vez por minuto, el modo "always on" de Blindside no puede ser ambient con radar en vivo. Tiene que ser modo interactivo con `keepScreenOn` + Ongoing Activity y una UI negra y muy dispersa para compensar la batería. El modo "ahorro" sería pantalla apagada, vibración como canal principal y encendido de pantalla a demanda. Esta es mi inferencia a partir de la guía Always-on.
- **IDEA: geometría de "abanico frontal".** Semicírculo de 180° con el jugador (el origen) unos 20% del radio R por debajo del centro de la pantalla. A esa altura el ancho útil es √(1−0,04)·R ≈ 0,98R, así que el abanico puede tener un radio de unos 0,95R sin recortarse en los costados. Queda libre un segmento inferior para estado: enlace, batería, modo y distancia del contacto más cercano. Reutiliza la metáfora de 180° del Heartbeat Sensor, lo que da positive transfer según la FAA.
- **IDEA: escala fija, cero interacción.** Borde = 6 m, anillos a 2 m y 4 m con líneas finas y tenues (se apagan en modo noche). Sin zoom. Nada de interacción táctil durante la partida, por los guantes, el sudor y la atención limitada; corona o bisel solo para cambiar de modo.
- **IDEA: un atributo por variable (regla FAA).**
  - Posición = dónde está el contacto.
  - Relleno = confianza: sólido si lo ven ambos radares, contorno si lo ve uno, contorno punteado si es "last seen".
  - Opacidad = edad.
  - Mini-trail de 2–3 puntos = movimiento (los history trails dan rumbo en ATC).
  - Tamaño constante para todos los blips.
  - Color: monocromo de alto contraste. Un único color de alerta (rojo o ámbar) reservado para "contacto nuevo que se acerca" o "<2 m al frente".
  - Los contornos, además, son seguros frente al burn-in.
- **IDEA: envejecimiento de contactos.** Inspirado en TIS: pasa a "coasting" tras unos 1–2 s sin detección (contorno punteado que se desvanece) y se borra a los 6–10 s. Los 6/12 s de TIS están pensados para aviones; una persona a 1–5 m/s en un radio de 6 m cambia mucho más rápido, así que hay que calibrarlo en campo.
- **IDEA: indicador de vida del enlace.** Un barrido (sweep) o "latido" tenue del borde que solo se anima si llegan datos frescos. Si el enlace BLE se corta más de 1 s, todo pasa a gris y aparece "--", al estilo del "No Traffic" de TIS y el "--" que pide Google. Un radar congelado que parece vivo es el peor fallo de confianza posible.
- **IDEA: números mínimos.** Mostrar en dígitos grandes (segmento inferior) solo la distancia del contacto más cercano, como hace COD con la distancia. Si hay más contactos de los que caben, mostrar "+N".
- **IDEA: legibilidad al sol.** Con 2.000 nits de pico, el sol es manejable con fondo negro, trazos gruesos (≥3–4 px) y blips de ≥~30 px. Suponiendo 432 px en ~33 mm (modelo de 40 mm), 30 px son ≈2,3 mm, que a ~40 cm de distancia subtienden ≈0,33° (~20 arcmin). El cálculo es mío. Los mínimos de 16–24 arcmin que recuerdo de estándares de factores humanos (MIL-STD-1472 / FAA HF-STD-001) no los verifiqué en esta sesión.
- **IDEA: IFF manual (v1.5).** Tocar un blip lo marca como "amigo" (contorno hueco en otro tono) mientras el radar mantenga ese track. Mitiga la falta de IFF sin hardware nuevo. Depende de que el LD2450 conserve bien la identidad de cada objetivo (ver Gaps).
- **IDEA: "modo sector".** Cuantizar en 3 sectores × 2 bandas de distancia y encender sectores en vez de dibujar puntos. Es aún más legible de un vistazo y más "justo", en línea con el razonamiento de Infinity Ward de no regalar la ubicación exacta.
- **IDEA: la altura no importa.** El Heartbeat Sensor tampoco da altura y los jugadores lo aceptan. Blindside no necesita disculparse por ser 2D.

### Gaps
- No encontré un mínimo oficial de tamaño de texto o símbolo en Wear OS. La guía solo dice "large and glanceable" y la página de accesibilidad no da ratios de contraste.
- No verifiqué la resolución exacta del Galaxy Watch7. Supuse 432×432 (40 mm) y 480×480 (44 mm); falta confirmarlo.
- La wiki de COD devolvió HTTP 402, así que el detalle del pitido y su tono queda sin verificar.
- Hay que probar en el dispositivo si una app de Wear OS en el Galaxy Watch7 puede encender la pantalla por sí sola al detectar un contacto. El modo "ahorro: despierta con la vibración" depende de eso.
- La guía de Samsung que encontré es la de One UI Watch para Tizen. No hallé una guía de Samsung con cifras específica para apps Wear OS.
- No sé si el LD2450 mantiene la identidad de cada objetivo entre tramas (slots estables); otros investigadores cubren el hardware.

## 2. Disciplina de luz nocturna: ¿cómo evitar que el reloj revele la posición (modos tenues, rojos, compatibles con NVG, auto-dim)?

### Takeaway
De noche la pantalla es una firma visible: a través de visión nocturna una luz pequeña se ve "like a flare", y el movimiento del brazo la hace parpadear como una linterna. El rojo no es invisible para la visión nocturna de tubo Gen III, que es sensible al rojo y al NIR. Lo que hacen los productos tácticos es atenuar al mínimo usable (modo NVG de Garmin), permitir apagar toda la iluminación (Samsung Tactical Edition) y llevar el reloj en la cara interna de la muñeca. Para Blindside: pantalla apagada por defecto, háptica como canal principal y un "flash" breve y muy tenue solo al consultar.

### Cited Findings
- [V] "A tritium watch face or compass moved up and down will look like a bright flashlight when viewed through any night vision device. A little LED key chain light will look like a flare." Sobre IR: "Whenever you emit IR...YOU are visible to any foe equipped with a night vision device." — [The Modern Minuteman](https://themodernminuteman.substack.com/p/you-cant-use-cheap-night-vision)
- [V] Doctrina del Ejército de EE. UU. (curso IN0202): "Blackout lights and filtered lights are visible through vision blocks and can be detected from great distances with passive night vision devices or the naked eye." — [GlobalSecurity – Limited Visibility Techniques](https://www.globalsecurity.org/military/library/policy/army/accp/in0202/le2m.htm)
- [S] Las gafas Gen III amplifican luz roja y NIR, con mayor sensibilidad aproximadamente entre 610 y 930 nm. — [Tech Briefs – Night Vision Compatible LED Indicators](https://www.techbriefs.com/component/content/article/14199-40431-304)
- [S] Los rojos (de luces de aviso y de displays a color) son problemáticos porque las gafas "see" hasta el rojo. Por eso existe un "NVIS Red" más anaranjado. — [Lumitron – Application Notes](https://lumitron.com/application-notes/)
- [V] "NVIS Class A is defined as using a 625 nm minus blue filter which does not allow the use of red cockpit lighting. NVIS Class B is defined as using a 665nm minus blue filter which does allow for the use of properly designed NVIS red lighting." Colores MIL-STD-3009: Green A, Green B, Yellow, White, Red, además de NVIS Blue. — [Applied Avionics – NVIS Colors](https://www.appliedavionics.com/techguides/Content/TG-LPBS-21/1.3.2_NVIS%20Colors.htm)
- [V] Garmin tactix 7:
  - "When night-vision mode is activated, the backlight settings of the screen will reduce to a level that won't interfere with the function of night-vision goggles".
  - Stealth mode que "collects distances traveled and biometric data without recording location data".
  - Borrado de todos los datos "in a matter of seconds".
  - Linterna "white or a dedicated green".
  — [Garmin – tactix 7](https://www.garmin.com/en-US/newsroom/press-release/outdoor/garmin-announces-tactix-7-a-premium-tactical-smartwatch-built-for-the-most-demanding-situations/)
- [S] Según un resumen de búsqueda sobre el tactix 8 (página primaria no abierta): en modo NVG el display "dims to levels functional to the primary user (including those wearing night optic devices) but nearly invisible to anyone else". — [Garmin – tactix 8](https://www.garmin.com/en-US/newsroom/press-release/outdoor/garmin-unveils-tactix-8-smartwatch-stay-active-stay-mission-ready/). Una nota de 2026 destaca "kill switch" y "stealth mode" en el último reloj táctico de Garmin: [Android Headlines](https://www.androidheadlines.com/2026/01/garmins-latest-watch-has-a-kill-switch-and-a-stealth-mode.html)
- [S] Samsung Tactical Edition incluye un Night Vision mode para ver la pantalla a través de gafas NV y permite encender/apagar toda la iluminación al usarlas. Su Stealth Mode desactiva LTE, e911 y toda emisión RF. — [Tom's Guide – S20 Tactical Edition](https://www.tomsguide.com/news/galaxy-s20-tactical-edition-looks-indestructible-and-has-night-vision-mode); [Samsung – Tactical Edition](https://www.samsung.com/us/es/business/solutions/industries/government/tactical-edition)
- [S] Motivos para llevar el reloj en la cara interna de la muñeca: evita reflejos (el cristal actúa como un espejo), el tritio es muy visible con NVG y así la luz se dirige hacia el usuario, y la esfera queda visible con las manos en el arma. Fuente: blog comercial, autoridad baja. — [Watches of Espionage](https://www.watchesofespionage.com/blogs/woe-dispatch/military-watches-inside-wrist)
- [V] El límite de 15% de píxeles encendidos en ambient viene de la guía de calidad de Wear OS (ver sección 1). — [Wear OS ambient](https://developer.android.com/training/wearables/wff/ambient)
- [S] En airsoft, algunos eventos prohíben la optoelectrónica avanzada (térmicas/NV), aunque en otros juegos se usa. — [WMASG – Thermovision in airsoft](https://wmasg.com/en/articles/view/21511)

### Inferences
- **El dilema del color.** El consejo de "luz roja de noche" sirve para proteger la adaptación a la oscuridad del propio usuario, no para esconderse de NV. Frente a NV de tubo Gen III, el rojo de 620–700 nm cae dentro de la banda sensible (inferencia a partir de la sensibilidad de 610–930 nm y de que NVIS Class A prohíbe el rojo). Un verde de ~530 nm queda por debajo de esa banda y se ve menos por el tubo. En cambio, la NV digital de consumo (sensor CMOS) ve casi todo el espectro visible. Además, recuerdo que el ojo desnudo adaptado a la oscuridad es más sensible al azul-verde (~507 nm), lo que haría el verde más visible a simple vista; no lo verifiqué en esta sesión. Conclusión: la palanca principal es brillo × área × tiempo encendido, no el tono.
- **IDEA: modo "Sigilo" (noche).**
  - Pantalla apagada siempre.
  - Un toque o gesto la enciende 1,5–2 s: solo blips, sin anillos, texto ni sweep, a brillo mínimo. Luego se apaga sola.
  - Objetivo: menos del ~2–5% de píxeles encendidos, muy por debajo del 15% de ambient.
  - Color seleccionable por el usuario: "rojo" (contra rivales a ojo desnudo) o "verde tipo NVIS" (contra NV de tubo).
- **IDEA: encendido solo en pose táctica.** Con el reloj en la cara interna, la luz se dirige al usuario, como en la práctica militar citada. En pose normal y modo noche, no encender la pantalla, o exigir un gesto deliberado.
- **IDEA: auto-noche.** Usar el sensor de luz ambiente del reloj para sugerir o activar el modo noche al atardecer. Añadir una calibración estilo Samsung/Garmin: el usuario ajusta el brillo mínimo mirando a través de su propia NV.
- **IDEA: disciplina RF.** El mismo principio aplica a la radio. Durante la partida, el enlace BLE cinturón-reloj debe estar conectado sin advertising continuo, con potencia TX baja y nombre de dispositivo genérico. Las emisiones ESP-NOW de equipo, en ráfagas cortas. Ver en la sección 4 la anécdota de Meshtastic: basta con saber desde dónde emites.
- **IDEA: accesorio.** Tapa abatible o protector mate antirreflejo contra los reflejos de día.
- **Consecuencia.** De noche la háptica pasa a ser el canal principal, así que la calidad del vocabulario háptico (sección 3) importa aún más que de día.

### Gaps
- No encontré mediciones de a qué distancia se ve la pantalla AMOLED de un reloj a brillo mínimo, ni a ojo desnudo ni con NV digital de consumo.
- No encontré el espectro de emisión de los subpíxeles del Galaxy Watch7.
- Falta probar si una app puede bajar el brillo por debajo del mínimo del sistema en el Galaxy Watch7 (override de `screenBrightness`), y cuál es ese mínimo.
- No sé qué tan común es la NV entre los jugadores de Medellín, ni si es de tubo o digital.
- Las afirmaciones sobre adaptación a la oscuridad y visión escotópica no tienen fuente verificada en esta sesión.

## 3. ¿Qué lenguaje háptico funciona con un solo actuador de muñeca (dirección, distancia, urgencia) y cómo evitar la fatiga de alertas?

### Takeaway
Con un solo actuador no se puede codificar la dirección por ubicación. Esa es justamente la codificación más intuitiva: en cinturones tácticos funciona sin entrenamiento. Queda el ritmo, que se reconoce en más del 90–97% de los casos con 3 ritmos. La intensidad o "rugosidad" es poco fiable (50–80%). No conviene codificar más de 2 dimensiones: con 3 parámetros la tasa de acierto cae al 48%. Recomendación:
- 3 ritmos para izquierda / centro / derecha.
- Urgencia por repetición, no por intensidad.
- Nada de distancia en la háptica: en el estudio del cinturón, codificar la distancia no mejoró el rendimiento.
- Al menos 1 s entre alertas, con agregación y cooldown por sector.

Para dirección precisa, el camino es un cinturón con 3–4 motores (v1.5).

### Cited Findings
- [V] Brown, Brewster y Purchase (MobileHCI 2006), usando actuadores C2 Tactor de Engineering Acoustics (voice-coil de alta gama, no un motor de reloj):
  - Tactons de 3 parámetros: 47,8% de acierto global. Por parámetro: ritmo 96,7%, "roughness" (modulación de amplitud) 50,2%, ubicación de la vibración en el antebrazo (varios actuadores) 95,5%.
  - Reduciendo la rugosidad a 2 niveles, el acierto global sube de 48,8% a 80,56%, y la rugosidad pasa de 59,47% a 82,4%.
  - En el estudio previo de 2 parámetros: 71% de acierto global, ritmo 93%, rugosidad 80%.
  - "Rhythm has been identified as a successful parameter in Tacton design, with recognition rates of over 90% achieved when three different rhythms are used".
  — [Multidimensional Tactons (PDF)](http://www.cs.columbia.edu/~coms6998-11/papers/Brown_MobHCI06.pdf)
- [V] Hong et al. (Graphics Interface 2016), pulseras de 4 y 8 motores, 20 participantes con los ojos vendados:
  - Duplicar los motores reduce el error "but not to the extent expected", apenas "an extra two degrees of accuracy".
  - Hallaron un límite práctico de precisión de ~23–25°.
  - Recomiendan usar solo 4 motores en la mayoría de los casos.
  — [Hong et al. 2016 (PDF)](https://makeabilitylab.cs.washington.edu/media/publications/Hong_EvaluatingAngularAccuracyOfWristBasedHapticDirectionalGuidanceForHandMovement_GI2016.pdf)
- [S] Van Erp et al. 2005, cinturón de 8 tactores con dirección por ubicación y distancia por ritmo. Mapear la dirección a la ubicación de la vibración "is an effective coding scheme that requires no training". Codificar la distancia "does not improve performance" frente a no dar información de distancia. — [Waypoint navigation with a vibrotactile waist belt (ACM TAP)](https://dl.acm.org/doi/10.1145/1060581.1060585)
- [S] Guía de ruta con cinturón vibrotáctil: con ~380 ms de retardo, y luego ~250 ms, los resultados fueron buenos. Con ~750 ms los participantes se pasaban del objetivo y daban vueltas a su alrededor. — [Route Guidance with a Vibro-tactile Waist Belt](https://www.researchgate.net/publication/233942836_Route_Guidance_with_a_Vibro-tactile_Waist_Belt)
- [S] El Tactile Situation Awareness System (TSAS) de EE. UU. es un chaleco que da información de altitud, ubicación y navegación al piloto mediante vibración en el tronco. — [NASA TM-20210017508](https://ntrs.nasa.gov/api/citations/20210017508/downloads/NASA:TM20210017508.pdf)
- [S] Van Erp probó un instrumento táctil de cabina con 64 vibradores en torso, hombros y muslos. — [Human Factors 2006](https://journals.sagepub.com/doi/10.1518/001872006777724435)
- [S] Marcos de referencia para señales táctiles: el marco "allocentric" redujo la carga cognitiva (~38%) y el tiempo de reacción (~240 ms) frente al centrado en la muñeca, sin diferencia significativa de precisión. — [Where Should I Look? (ACM 2021)](https://dl.acm.org/doi/fullHtml/10.1145/3460421.3478822)
- [S] En la muñeca, las direcciones codificadas con estímulos en movimiento (80,2%) se distinguen mejor que las de estímulo puntual (69,6%). — [Springer HCII 2020](https://link.springer.com/chapter/10.1007/978-3-030-49788-0_13)
- [V] Yale-NUS/SUTD: la distracción que causa una vibración "lasts only for about one second". Recomiendan que "multiple alerts are separated by at least one second" y retrasar las notificaciones hasta que haya un hueco adecuado. — [ScienceDaily 2020](https://www.sciencedaily.com/releases/2020/06/200602151321.htm)
- [S] Guía de haptics de Android: reemplazar "buzzy vibration" por "clear haptics". Preferir los efectos predefinidos y las primitivas de `VibrationEffect.Composition`, más consistentes en los dispositivos que las soportan. Evitar vibraciones legacy como `createOneShot` y `vibrate(long)`. — [Haptics design principles](https://developer.android.com/develop/ui/views/haptics/haptics-principles); [Custom haptic effects](https://developer.android.com/develop/ui/views/haptics/custom-haptic-effects)

### Inferences
- **Un solo actuador = solo canal temporal.** El ritmo es la dimensión robusta y la intensidad no sirve para distancia ni urgencia. Máximo 2 dimensiones: dirección por ritmo y urgencia por número de repeticiones.
- **IDEA: vocabulario v1 (validar en campo con A/B).**
  - Set A: izquierda "ta-ta" (2 cortos), centro "taaa" (1 largo), derecha "ta-ta-ta" (3 cortos).
  - Set B (espejo): izquierda "taaa-ta", derecha "ta-taaa", centro "ta-ta".
  - Urgencia (se acerca rápido o está a <2 m al frente): el mismo patrón repetido 2× con intervalo corto.
  - Sistema (enlace caído o batería baja): un patrón exclusivo "largo-largo", poco frecuente e inconfundible.
  - Contacto perdido: sin vibración.
- **IDEA: qué dispara una vibración.**
  - Solo contactos "confirmados" (ambos radares), o de un solo radar que persisten más de ~300–500 ms, para no vibrar con clutter.
  - Cooldown por sector de 5–8 s, salvo que aumente el número de contactos en ese sector.
- **IDEA: rate limiter.**
  - Hueco global de al menos 1 s (dato Yale-NUS).
  - Ventana de agregación de ~300 ms que fusiona eventos simultáneos en un solo mensaje, con prioridad centro > lados y el más cercano primero.
  - Tope por minuto (por ejemplo, 10). Al saturarse, pasar a solo visual con un indicador de "overflow".
  - "Silencio de combate" al detectar que el jugador apunta (sección 5).
- **IDEA: presupuesto de latencia.** De radar a vibración, ≲300 ms. Con 250–380 ms los cinturones funcionaron bien y con 750 ms hubo sobrecorrección. Esto condiciona el intervalo de conexión BLE y los filtros de confirmación.
- **IDEA: marco de referencia.** La dirección siempre relativa a las caderas, el mismo marco que los radares y la pantalla front-up. Nunca relativa a la orientación de la muñeca, porque las señales centradas en la muñeca cuestan más carga cognitiva según el snippet ACM 2021.
- **IDEA: cinturón háptico (v1.5).** 3 motores coin (cadera izquierda, frente, cadera derecha) manejados directamente por el ESP32. La dirección por ubicación no requiere entrenamiento (van Erp) y encaja con tener los radares en el mismo cinturón. El reloj queda para confirmar y leer. Según Hong et al., más motores aportan poco, así que 3–4 bastan.
- **IDEA: implementación en Android.**
  - Usar `VibrationEffect.Composition` con primitivas si `areAllPrimitivesSupported()` lo permite; si no, un waveform con amplitudes.
  - Evitar `createOneShot` largos y "buzzy".
  - Perfil de intensidad ajustable por el usuario (guantes, manga, correa).
- **IDEA: modo práctica.** Una pantalla de entrenamiento que reproduce cada patrón junto con su dibujo, más un mini-quiz. Los estudios de tactons suponen un entrenamiento previo.

### Gaps
- No encontré pruebas de identificación de ritmos en el actuador de un Galaxy Watch, ni si el Watch7 expone control de amplitud o primitivas de composición. Los porcentajes de tactons se midieron con un C2 Tactor de alta gama, así que en el motor del reloj podrían ser peores, sobre todo los de rugosidad o intensidad.
- No encontré estudios de habituación háptica en sesiones de 2–4 h con esfuerzo físico, adrenalina o la vibración propia de una réplica (gearbox, recoil).
- Varias cifras (ACM 2021, Springer 2020, van Erp 2005, TSAS) vienen solo de snippets.

## 4. Funciones de equipo (compartir detecciones) y opciones de IFF: ESP-NOW, UWB (DW3000), Bluetooth Channel Sounding, RSSI, IR y asociación con los tracks del radar

### Takeaway
Para compartir detecciones, lo natural es ESP-NOW, que ya está en el ESP32: latencia de 20–30 ms. En bosque la entrega cae por debajo del 50% a ~125 m (ESP-NOW) o ~400 m (modo LR), suficiente para una escuadra a menos de 50 m. El riesgo técnico es que comparte la única radio de 2,4 GHz con el BLE hacia el reloj.

Para IFF:
- **UWB (Qorvo DW3000/DW3220)** es hoy lo más maduro y asequible: ±10 cm en distancia y AoA de ±5° con 2 antenas (PDoA), con ambigüedad frente/atrás. Para la precisión AoA de ±5° se necesita un ancla DW3220 (PDoA, doble antena) en el cinturón propio; el módulo MaUWB_ESP32S3 (DW3000, ~US$55) solo mide distancia y sirve como tag para los compañeros. El kit AoA de Makerfabs tiene un precio que no figura en las fuentes.
- **Bluetooth Channel Sounding** (BT 6.0) es muy barato (XIAO nRF54L15 ~US$10), pero solo da distancia, con precisión típica de 10–50 cm. El Galaxy Watch7 (BT 5.3) no lo soporta.
- **RSSI** no sirve: errores de metros y el cuerpo atenúa decenas de dB.
- **IR**: la doctrina militar lo usa dentro de sistemas IR+RF de interrogación, pero choca con la disciplina de luz.

Para asociar un tag con un track del radar hacen falta gating por distancia y ángulo y consistencia temporal. La literatura radar+UWB/IMU lo respalda.

### Cited Findings
**ESP-NOW y coexistencia de radio**
- [V] Prueba de Espressif con ESP32-C6-DevKitM-1 y antena PCB (no ESP32-WROOM-32):
  - ESP-NOW: latencia "under 20 ms even at distances up to 300 meters" en campo abierto y ~30 ms a 150 m en bosque. Entrega casi del 100% hasta 150 m en campo abierto, bajando al 60% a 300 m; en bosque, "below 50% at 125 meters".
  - ESP-NOW-LR: "under 25 ms even at 900 meters" en campo abierto y "around 30 ms up to 600 meters" en bosque. Entrega casi del 100% hasta 450 m en campo abierto, bajando al 40% a 900 m; en bosque, por debajo del 50% a 400 m.
  - Throughput LR: de 100 kbps a 150 m a 10 kbps a 900 m.
  — [Espressif Developer Portal](https://developer.espressif.com/blog/esp-now-for-outdoor-applications/)
- [S] Otra fuente cita un alcance típico de ESP-NOW de ~220 m en condiciones ideales; es algo menor que el test de Espressif, que es la fuente primaria. — [ThinkRobotics](https://thinkrobotics.com/blogs/learn/esp-now-tutorial-for-long-range-communication-building-robust-wireless-networks-in-2025)
- [S] El modo LR usa PHY de 512 o 256 kbps. — [ESP32 Forum](https://esp32.com/viewtopic.php?t=33291). Activarlo empeora un poco la latencia en aplicaciones sensibles. — [Electric UI](https://electricui.com/blog/latency-comparison)
- [S] Wi-Fi (incluido ESP-NOW) y Bluetooth comparten una sola radio de 2,4 GHz y se turnan por multiplexación temporal (TDM). La coexistencia se habilita en menuconfig. — [ESP-FAQ – Coexistence](https://docs.espressif.com/projects/esp-faq/en/latest/software-framework/coexistence.html); [ESP-IDF coexist guide](https://github.com/espressif/esp-idf/blob/401816e/docs/en/api-guides/coexist.rst)
- [S] Un usuario del foro reporta ESP-NOW + Wi-Fi + BLE funcionando a la vez tras usar `WIFI_MODE_APSTA`, con lo que desapareció la pérdida de paquetes ESP-NOW. — [ESP32 Forum](https://esp32.com/viewtopic.php?t=23406)

**UWB (Qorvo DW3000 / DW3220, Makerfabs)**
- [S] DW3220: posicionamiento "within 10 cm for ranging and +/-5 degrees for angular measurement". La especificación de PDoA es ±10°, y la AoA resultante suele ser unas 2× mejor (±5°). — [Qorvo DW3220](https://www.qorvo.com/products/p/DW3220)
- [S] ETH Zürich, con DW3220 de doble antena: precisión centimétrica en distancia y 2,4° de error angular medio dentro de ±45° del centro. Con PDoA sola no se distingue frente de atrás ("front-back ambiguity"), así que la medida absoluta queda limitada a 2D y ±90°. — [arXiv 2312.13672](https://arxiv.org/html/2312.13672v1)
- [V] Makerfabs MaUWB_ESP32S3:
  - Precio: US$54,80. ESP32-S3 + DW3000 con PA.
  - "Precision 0.5M(in range 100m)" y "10 cm accuracy with callibration". Alcance de 500 m.
  - "Support max 8 Anchors + 64 tags". Controlado por comandos AT (firmware STM32).
  - La AoA requiere un kit aparte (MaUWB_STM32 AOA).
  — [Makerfabs MaUWB_ESP32S3](https://www.makerfabs.com/mauwb-esp32s3-uwb-module.html)
- [S] ESP32 UWB DW3000 de Makerfabs en Tindie: ~US$43,80 (precio no verificado). — [Tindie](https://www.tindie.com/products/makerfabs/esp32-uwb-dw3000ultra-wideband/)

**Bluetooth Channel Sounding (BT 6.0)**
- [V] "Bluetooth Channel Sounding is a key feature of the Bluetooth core specification 6.0". Soporte cualificado en la serie Nordic nRF54L (nRF54L15/L10/L05/LM20A). Ofrece "robust protection against man-in-the-middle and relay attacks". "Software support for Channel Sounding was made publicly available with the release of Android 16, which introduced the RangingManager API." La app nRF Toolbox funciona con el Pixel 10. — [Nordic – Channel Sounding](https://www.nordicsemi.com/Products/Wireless/Bluetooth-Low-Energy/Channel-Sounding)
- [S] El Pixel 10 (28-ago-2025) fue el primer teléfono con Channel Sounding. En nRF Toolbox funciona en Pixel 9 y 10 con Android 16 actualizado. — [Nordic DevZone](https://devzone.nordicsemi.com/nordic/nordic-blog/b/blog/posts/evaluating-bluetooth-channel-sounding-with-our-open_2d00_source-android-app-on-google-pixel-10)
- [S] Samsung solo lo soporta en Galaxy Z Fold8 Ultra, Z Fold8, S26 Ultra y S26 Plus (no en el S26 base). El SmartTag3 usa Channel Sounding para guiar hasta a 60 m. — [SamMobile](https://www.sammobile.com/news/galaxy-smarttag-3-compass-view-works-only-four-phones); [Neowin](https://www.neowin.net/news/samsung-galaxy-smarttag3-takes-on-pixel-tag-with-iphone-support-and-3x-longer-finding-range/)
- [S] Disponible en iPhones con chip N1, según un resumen de terceros de WWDC 2026. — [wwdc.ai](https://wwdc.ai/2026/369)
- [S] Silicon Labs anunció el BG2B (4-ago-2026), un SoC Bluetooth 6 con Channel Sounding completo para funcionar años con pila de botón. — [TechTimes](https://www.techtimes.com/articles/323437/20260806/silicon-labs-bg2b-claims-channel-sounding-lead-apple-google-spec-compliance.htm)
- [S] Seeed XIAO nRF54L15: US$9,90 (versión Sense US$15,99), con BLE 6.0 y Channel Sounding. — [CNX Software](https://www.cnx-software.com/2025/07/31/seeed-studio-xiao-nrf54l15-usb-c-boards-support-matter-thread-zigbee-and-amazon-sidewalk/)
- [S] Combina phase-based ranging y round-trip time. La precisión típica es de 10–50 cm, y lo más citado es 30–50 cm. El RTT protege contra ataques de relay. — [Minew](https://www.minew.com/bluetooth-channel-sounding-overview/); [Bluetooth SIG (PDF)](https://www.bluetooth.com/wp-content/uploads/2024/12/mrn-channel-sounding.pdf)
- [S] El Galaxy Watch7 tiene Bluetooth 5.3. — [Android Central](https://www.androidcentral.com/wearables/samsung-galaxy-watch-7)

**Por qué RSSI no basta**
- [S] El cuerpo humano puede bajar el RSSI decenas de dB, con gran variabilidad según distancia y canal. Una distorsión de 5 dBm por sombra corporal degradó ~67% el posicionamiento. Con un dispositivo en el pecho, el error mediano fue de 3,48 m (2,99 m con compensación). — [PMC8398681](https://pmc.ncbi.nlm.nih.gov/articles/PMC8398681/)
- [S] Con ESP32 BLE, la estimación es razonable hasta ~4 m (<25% de error con línea de vista). Más allá de 5 m el RSSI fluctúa mucho y la distancia se sobreestima. — [ResearchGate](https://www.researchgate.net/publication/396705094_Evaluation_of_RSSI-Based_Distance_Estimation_with_ESP32_BLE_Modules_for_Indoor_Asset_Tracking)

**IR / identificación de combate**
- [S] El programa CIDDS de EE. UU. busca un paquete interrogador/transpondedor por soldado. Los sistemas IR-RF montan el interrogador en el arma y el transpondedor en el soldado amigo. Con imagen IR sola no es factible identificar a soldados individuales. — [ResearchGate – CIDDS](https://www.researchgate.net/publication/252254797_Combat_identification_system_for_the_dismounted_soldier); [Patente US20090058712](https://patents.google.com/patent/US20090058712); [Boyd et al. 2005](https://shoalgroup.com/wp-content/uploads/2017/06/Boyd-et-al-2005-Characterisation-of-Combat-Identification-Technologies-IEEE-2005.pdf)

**Asociar identidad con tracks de radar**
- [S] El radar mmWave detecta presencia con precisión pero no reconoce individuos. Un despliegue mínimo de UWB (tags vestibles + anclas) permite identificarlos. — [ScienceDirect 2025](https://www.sciencedirect.com/science/article/pii/S0952197625008723)
- [S] PmTrack usa las IMU de relojes y teléfonos como "identity indicators" para personalizar el tracking con mmWave. — [ACM IMWUT](https://dl.acm.org/doi/10.1145/3631433)
- [S] Los radares mmWave comerciales tienen un alcance limitado (6–8 m) y sufren oclusión. — [ORACLE, arXiv](https://arxiv.org/pdf/2208.14199)

**Antecedentes de equipo en airsoft**
- [S] Meshtastic + ATAK comparten por LoRa, sin celular ni internet, la posición de cada miembro del equipo en un mapa offline, además de mensajes y marcadores. — [Meshtastic – TAK Integration](https://meshtastic.org/docs/software/apple/user/tak/)
- [S] Hay airsofters que lo usan, y se advierte que la emisión RF al arrancar un nodo puede delatarte: "it's enough to know where you're saying it from". — [X / @CarterNimrod](https://x.com/CarterNimrod/status/1888330062186889587)
- [S] WearTAK transmite sus datos en CoT, sin cambios en el servidor, y tiene "Bloodhound" (rumbo y distancia). — [weartak.com](https://weartak.com/)

### Inferences
- **Sin referencia común no hay imagen compartida.** Una detección de un compañero no se puede dibujar en mi radar si no sé dónde está él respecto de mí (UWB o, más gruesamente, GPS + rumbo). Sin eso, compartir detecciones se reduce a anunciarlas: "Juan: contacto frente-izquierda". Es útil, pero no es un mapa.
- **IDEA: Squad link (v1.5 / v2) por ESP-NOW cinturón a cinturón.**
  - Paquetes compactos a 2–5 Hz: ID del emisor, rumbo IMU y lista de tracks relativos.
  - En v1.5 solo como alertas de texto o hápticas del tipo "compañero X tiene contacto".
  - Con posiciones relativas de UWB (v2), fusionarlas en el radar propio.
  - Riesgo: la coexistencia BLE + ESP-NOW en el ESP32 clásico. Probar la latencia real con la radio compartida.
- **IDEA: UWB como IFF (v2).**
  - Un ancla de doble antena (DW3220, PDoA) en el frente del cinturón y tags en los compañeros.
  - Da distancia de ±10 cm y rumbo de ±5° en ±90°, justo el hemisferio que cubren los radares.
  - Asociación:
    - Para cada track, un gate con |r_radar − r_uwb| < ~0,5 m y |θ_radar − θ_uwb| < ~15°.
    - Asignación GNN/húngara si hay varios candidatos.
    - Histéresis: la etiqueta "amigo" sigue pegada al track N segundos después de perder el UWB.
- **Riesgo clave: "falso amigo" por ambigüedad frente/atrás.** Un compañero justo detrás, a 3 m y 30° atrás a la izquierda, aparecería como 30° adelante a la izquierda. Si hay un enemigo real ahí, se marcaría como amigo, que es el peor fallo posible de un IFF.
  - Pedir consistencia de distancia y velocidad radial durante ≥1 s.
  - Usar la atenuación del propio cuerpo del usuario (un tag detrás llega con menos potencia en el primer camino) como pista de "atrás".
  - En la duda, nunca ocultar el blip: marcarlo como "probable amigo" con contorno hueco.
  - Preferir "amigo → desconocido" antes que "enemigo → amigo".
- **IDEA: IFF "lite" por Channel Sounding (v2 alternativo).** Un XIAO nRF54L15 (~US$10) en cada compañero y otro en mi cinturón, hablando con el ESP32 por UART. Solo da distancia, así que la asociación es ambigua si hay dos objetivos a distancias parecidas; se resuelve con la correlación temporal de la distancia (se mueven distinto). El reloj no participa, porque el Watch7 es BT 5.3.
- **IDEA: IFF por firma de movimiento (investigación, v2).** Inspirado en PmTrack: cada cinturón de compañero emite por ESP-NOW sus eventos IMU (pasos, velocidad), y se correlacionan con la velocidad de cada track del radar. No requiere UWB, pero falla con compañeros quietos.
- **RSSI: descartado para IFF.** Solo serviría para una presencia gruesa del tipo "compañero cerca (<~4–5 m)". No sirve para asociar tracks.
- **IR: no recomendado.** Un beacon IR con fotodiodos da rumbo sin distancia, depende de línea de vista y es visible para cualquier NV, en contra de la sección 2.
- **IDEA: exportar a ATAK por CoT (v2).** Para equipos que ya usan ATAK/Meshtastic, publicar los contactos de Blindside como marcadores CoT, al estilo WearTAK. Advertirlo en la documentación como riesgo de emisión RF.

### Gaps
- No encontré mediciones de ESP-NOW con ESP32-WROOM-32 llevado en el cuerpo (sombra corporal) en bosque, ni de la latencia real con coexistencia BLE + ESP-NOW.
- No encontré ningún smartwatch con Channel Sounding confirmado a septiembre de 2026.
- No verifiqué el precio en Tindie del Makerfabs ESP32 UWB DW3000 ni la disponibilidad o envío a Colombia.
- No encontré datos de UWB en NLOS a través del cuerpo, que es el caso real de un tag en la espalda de un compañero.
- No verifiqué la regulación de UWB en Colombia (ANE).
- No encontré una implementación open source de asociación radar ↔ UWB para dispositivos vestibles; solo trabajos académicos.

## 5. Otras ideas creativas: grabación y replay/heatmap, modo centinela, audio por auricular, auto-dim / auto-silencio al apuntar, estado y batería, modos de juego

### Takeaway
Las ideas con mejor relación valor/esfuerzo sobre el hardware actual son:
1. Grabación de partida con replay/heatmap. El registro es casi gratis, el reloj tiene GPS y además sirve para depurar el radar.
2. Contactos "last seen" estabilizados al mundo con el giroscopio del MPU6050.
3. Modo centinela: un nodo ESP32 + LD2450 enlazado por ESP-NOW.
4. Auto-silencio y atenuación al apuntar, con la IMU del reloj.
5. Mini-HUD de estado.
6. Modos de juego: ping "clásico" cada 4 s al estilo COD y un modo torneo/fair play.

El audio espacial por auricular es potente para la dirección, pero depende de la latencia Bluetooth y de no tapar el sonido del entorno (v2). Hay que considerar las reglas de cada campo: algunos eventos prohíben la optoelectrónica avanzada.

### Cited Findings
- [V] Ongoing Activity (Wear OS 5+) mantiene visible una app de tarea en curso; `keepScreenOn` "severely impacts battery life". — [Always-on](https://developer.android.com/training/wearables/always-on)
- [S] El Galaxy Watch7 tiene GPS de doble frecuencia. — [Android Central](https://www.androidcentral.com/wearables/samsung-galaxy-watch-7)
- [V] ESP-NOW-LR mantiene ~30 ms de latencia hasta 600 m en bosque, con entrega casi del 100% en distancias cortas. — [Espressif](https://developer.espressif.com/blog/esp-now-for-outdoor-applications/)
- [S] Una sola emisión RF puede delatar la posición (anécdota Meshtastic en airsoft). — [X / @CarterNimrod](https://x.com/CarterNimrod/status/1888330062186889587)
- [V] Heartbeat Sensor: ping cada 4 s, 180°, snapshot, contrarrestado por Ghost. — [Mein-MMO](https://mein-mmo.de/en/best-tactical-item-in-cod-warzone-heres-why-you-should-use-it-this-is-what-makes-it-strong,496689/)
- [S] Infinity Ward quitó los puntos rojos del minimapa para no "punish players for firing" y obligar a buscar el origen del disparo. — [Charlie INTEL](https://www.charlieintel.com/call-of-duty-modern-warfare-2/modern-warfare-2-minimap-will-not-reveal-enemy-red-dots-198697/)
- [V] Una vibración distrae durante ~1 s. — [ScienceDaily](https://www.sciencedaily.com/releases/2020/06/200602151321.htm)
- [S] En la cara interna de la muñeca, la esfera se ve con las manos en el arma. — [Watches of Espionage](https://www.watchesofespionage.com/blogs/woe-dispatch/military-watches-inside-wrist)
- [S] WearTAK anuncia detección de disparos y UAS con el micrófono del reloj (UAS "coming soon"). — [weartak.com](https://weartak.com/)
- [S] Algunos eventos de airsoft prohíben la optoelectrónica avanzada. — [WMASG](https://wmasg.com/en/articles/view/21511)
- [S] El beep del Heartbeat Sensor sube de tono con la cercanía (no verificado). — [COD Wiki](https://callofduty.fandom.com/wiki/Heartbeat_Sensor)

### Inferences
Todas son **IDEA** propia. Formato: valor para el jugador / esfuerzo / factibilidad con el hardware actual.

1. **Grabación de partida + replay y heatmap.**
   - Qué es: registrar a ~10 Hz los tracks (x, y, confianza, radar), los eventos (vibraciones, cambios de modo), el GPS del reloj y el rumbo. Después, en el teléfono o PC: línea de tiempo, "desde dónde me entraron" y heatmap de contactos. Mi estimación para el log BLE crudo con sello (ambos radares e IMU): ~11–12 MB cada 2 h; los tracks procesados serían unos 3,5 MB, pero el log crudo es lo que permite depurar el radar.
   - Valor: alto, para aprender, compartir y depurar el radar.
   - Esfuerzo: bajo el registro, medio el visor.
   - Factibilidad: alta.
   - Versión: registro en v1, visor en v1.5.
2. **Contactos "last seen" estabilizados al mundo.**
   - Qué es: si giro las caderas 90°, un contacto visto a la izquierda debe pasar al centro. Se integra el yaw del giroscopio del MPU6050, cuya deriva es aceptable en ventanas de ~10 s aunque no tenga magnetómetro.
   - Valor: alto, porque sin esto los fantasmas "last seen" engañan en cuanto el jugador se mueve.
   - Esfuerzo: medio.
   - Factibilidad: alta.
   - Versión: v1.5, o v1 si alcanza el tiempo.
3. **Modo centinela.**
   - Qué es: un nodo con otro ESP32 (~US$5) + un LD2450 + power bank, dejado vigilando una puerta o pasillo. Alerta por ESP-NOW al cinturón, que la reenvía al reloj por BLE con un patrón háptico propio ("centinela"). Alternativa sin comprar nada: desmontar uno de los dos radares del cinturón, perdiendo ~90° de cobertura.
   - Valor: alto, es táctico y divertido.
   - Esfuerzo: medio.
   - Riesgo: la emisión RF del nodo puede delatarlo; transmitir solo al detectar.
   - Versión: v1.5.
4. **Auto-silencio y atenuación al apuntar.**
   - Qué es: detectar con la IMU del reloj la pose de la mano de apoyo en el guardamanos. Mientras dura, se suprime la háptica salvo lo crítico (<2 m al frente) y la pantalla se atenúa o apaga para evitar reflejos. Motivo: una vibración distrae ~1 s justo cuando se dispara.
   - Valor: medio-alto.
   - Esfuerzo: medio (calibración por jugador).
   - Factibilidad: alta.
   - Versión: v1.5.
5. **Mini-HUD de estado** en el segmento inferior.
   - Qué es: calidad del enlace BLE, "vida" de cada LD2450, batería del reloj, tiempo desde el arranque del cinturón (un power bank no reporta su carga, salvo que se mida el voltaje) y calibración de la IMU.
   - Valor: medio (sube la confianza en el sistema).
   - Esfuerzo: bajo.
   - Versión: v1.
6. **Modos de juego.**
   - Continuo, el modo por defecto.
   - "Clásico COD": un snapshot cada 4 s. Menos información, menos distracción, menos batería y más "justo".
   - Torneo o fair play: solo háptica, sectores en vez de puntos, o retardo de la información; para campos que lo exijan.
   - "Árbitro" (v2): los organizadores ven los radares de todos.
   - Valor: medio. Esfuerzo: bajo (v1.5).
   - Ninguno puede incluir interferencia activa (jamming) del radar rival: sería ilegal y poco ético.
7. **Modo práctica y test de equipo antes de la partida.**
   - Qué es: el quiz háptico de la sección 3, más una prueba de "compañeros": caminan frente al jugador para verificar la detección y ajustar el filtro de confirmación.
   - Valor: medio. Esfuerzo: bajo. Versión: v1.
8. **Latido de verdad.**
   - Qué es: el sweep o el borde pulsa al ritmo cardíaco medido por el reloj. Es un guiño a COD y a la vez indica el nivel de estrés del jugador. Costo: batería del sensor de pulso.
   - Valor: bajo-medio (factor "wow"). Esfuerzo: bajo.
   - Versión: v1.5.
9. **Audio por auricular.**
   - Qué es: pitidos panoramizados izquierda/derecha según el sector, que es un canal de dirección natural, con el tono subiendo con la cercanía como en el Heartbeat Sensor.
   - Pegas: la latencia Bluetooth del reloj al auricular (desconocida), tapar el oído en un juego donde se escucha al rival, y la convivencia con el auricular de radio PTT que muchos jugadores ya usan.
   - Valor: medio. Esfuerzo: medio.
   - Versión: v2 o experimento.
10. **Bitácora de contactos.**
    - Qué es: una lista de los últimos 5 contactos con sector y "hace N s", útil tras un intercambio de disparos.
    - Valor: medio. Esfuerzo: bajo.
    - Versión: v1.5.
11. **Transparencia y reglas.**
    - Qué es: una sección en el README con el modo torneo y una recomendación explícita de declarar el dispositivo a los organizadores (algunos eventos prohíben la optoelectrónica avanzada).
    - Valor: alto para la reputación del proyecto. Esfuerzo: mínimo.

### Gaps
- No conozco la latencia del audio Bluetooth del Galaxy Watch7 a auriculares.
- No encontré reglas de campos de airsoft en Medellín o Colombia sobre dispositivos electrónicos de detección.
- No verifiqué en esta sesión la lista de sensores del Galaxy Watch7 (sensor de luz, magnetómetro).
- Detectar "apuntando" con la IMU del reloj no tiene validación publicada que yo haya encontrado; habría que prototiparlo.
- No sé si el LD2450 detecta de forma fiable a personas quietas, lo que afecta al centinela y a los "last seen"; lo cubre otro investigador.

## 6. Priorización final por valor y esfuerzo: v1 / v1.5 / v2

### Takeaway
- **v1**: hacer excelente lo básico, todo en software y sobre el hardware actual. Abanico legible, confianza y edad bien codificadas, estado de enlace honesto, 3 ritmos hápticos con rate limiting, modo noche casi sin luz y registro de partidas.
- **v1.5**: software avanzado y hardware de menos de ~US$20. "Last seen" estabilizado, cinturón háptico, centinela, replay/heatmap, silencio al apuntar, IFF manual y modos de juego.
- **v2**: equipo e IFF. UWB DW3220 de doble antena como vía principal, squad link por ESP-NOW, exportación CoT/ATAK, Channel Sounding como alternativa barata solo de distancia, y audio espacial.

Descartados: IFF por RSSI, beacons IR y cualquier forma de jamming.

### Cited Findings
- [V] El ambient mode actualiza "typically once per minute", y `keepScreenOn` "severely impacts battery life". Condiciona el diseño de los modos de pantalla desde v1. — [Always-on](https://developer.android.com/training/wearables/always-on)
- [V] El ritmo se reconoce en más del 90% con 3 ritmos, la rugosidad o intensidad mucho peor, y los tactons de 3 parámetros solo un 47,8%. Justifica el vocabulario de 3 ritmos en v1. — [Brown et al. 2006](http://www.cs.columbia.edu/~coms6998-11/papers/Brown_MobHCI06.pdf)
- [S] La dirección por ubicación en un cinturón funciona sin entrenamiento y codificar distancia no ayudó. Justifica el cinturón háptico en v1.5. — [van Erp et al. 2005](https://dl.acm.org/doi/10.1145/1060581.1060585)
- [V] Separar las alertas al menos 1 s. — [ScienceDaily](https://www.sciencedaily.com/releases/2020/06/200602151321.htm)
- [V] ESP-NOW ofrece <20–30 ms, pero en bosque la entrega cae por debajo del 50% a ~125 m (LR: ~400 m). — [Espressif](https://developer.espressif.com/blog/esp-now-for-outdoor-applications/)
- [V] MaUWB_ESP32S3 cuesta US$54,80 y da 10 cm calibrado. — [Makerfabs](https://www.makerfabs.com/mauwb-esp32s3-uwb-module.html)
- [S] DW3220 da ±10 cm y ±5°. — [Qorvo](https://www.qorvo.com/products/p/DW3220)
- [S] XIAO nRF54L15 cuesta US$9,90 y trae Channel Sounding. — [CNX](https://www.cnx-software.com/2025/07/31/seeed-studio-xiao-nrf54l15-usb-c-boards-support-matter-thread-zigbee-and-amazon-sidewalk/)
- [S] El Galaxy Watch7 es BT 5.3 y no tiene UWB. — [Android Central](https://www.androidcentral.com/wearables/samsung-galaxy-watch-7)
- [S] El RSSI sufre la sombra del cuerpo, con caídas de decenas de dB y errores de metros. — [PMC8398681](https://pmc.ncbi.nlm.nih.gov/articles/PMC8398681/)
- [V] Una luz pequeña se ve "like a flare" a través de NV. — [Modern Minuteman](https://themodernminuteman.substack.com/p/you-cant-use-cheap-night-vision)

### Inferences
Todo es **IDEA** o priorización propia. Escalas: Valor para el jugador A/M/B; Esfuerzo B/M/A; "HW actual" indica si basta con lo que ya se tiene.

| # | Función | Valor | Esfuerzo | ¿HW actual? | Versión |
|---|---|---|---|---|---|
| 1 | Abanico 180° front-up con el origen bajo el centro, escala fija de 6 m y anillos a 2/4 m | A | B | Sí | v1 |
| 2 | Confianza por relleno (sólido/contorno/punteado), edad por opacidad (coasting ~1–2 s, borrado ~6–10 s) y tamaño constante | A | B | Sí | v1 |
| 3 | Estado honesto del enlace (sweep solo con datos frescos; gris y "--" si se corta >1 s) | A | B | Sí | v1 |
| 4 | Vocabulario háptico de 3 ritmos (izq/centro/der) + urgencia por repetición + patrón de sistema | A | B | Sí | v1 |
| 5 | Rate limiter (≥1 s global, agregación ~300 ms, cooldown por sector, tope por minuto) | A | B | Sí | v1 |
| 6 | Vibrar solo con contactos confirmados, o de un solo radar que persisten >300–500 ms | A | B | Sí | v1 |
| 7 | Modo noche "Sigilo" (pantalla apagada, flash de 1,5–2 s a brillo mínimo, <5% de píxeles, solo en pose táctica) | A | B | Sí | v1 |
| 8 | "Always on" = `keepScreenOn` + Ongoing Activity con UI mínima; "ahorro" = pantalla apagada + háptica | M | B | Sí (probar encendido programático) | v1 |
| 9 | Mini-HUD de estado (enlace, radares vivos, baterías) | M | B | Sí | v1 |
| 10 | Registro de partida (paquetes BLE crudos con sello —ambos radares e IMU—, eventos, sensores y GPS del reloj; ~11–12 MB cada 2 h) | M (A para el desarrollo) | B | Sí | v1 |
| 11 | Modo práctica háptica + test de compañeros previo a la partida | M | B | Sí | v1 |
| 12 | "Last seen" estabilizado al mundo con el giroscopio del MPU6050 | A | M | Sí | v1.5 |
| 13 | Visor de replay / heatmap en teléfono o PC | A | M | Sí | v1.5 |
| 14 | Cinturón háptico de 3 motores (dirección por ubicación) | A | B-M | +~US$5 en motores | v1.5 |
| 15 | Modo centinela (nodo ESP32 + LD2450 + ESP-NOW) | A | M | +1 ESP32 (~US$5), o reusar un radar | v1.5 |
| 16 | Auto-silencio y atenuación al apuntar (IMU del reloj) | M-A | M | Sí | v1.5 |
| 17 | IFF manual (tocar un blip lo marca amigo mientras dure el track) | M | M | Sí (según la estabilidad de IDs del LD2450) | v1.5 |
| 18 | Modos "Clásico COD" (snapshot cada 4 s), "sector" y torneo/fair play | M | B | Sí | v1.5 |
| 19 | Pulso del sweep al ritmo cardíaco | B-M | B | Sí | v1.5 |
| 20 | Bitácora de últimos contactos | M | B | Sí | v1.5 |
| 21 | IFF por UWB con protección contra "falso amigo" (ancla DW3220 de doble antena en el cinturón propio + tags DW3000 para compañeros, gating con histéresis) | A (muy alto) | A | Tag: ~US$55 (MaUWB_ESP32S3; ~US$44 en Tindie, sin verificar); ancla AoA: precio por verificar | v2 |
| 22 | Squad link ESP-NOW: primero alertas, después fusión con posiciones relativas | A | A | Parcial (hace falta un cinturón por jugador) | v2 |
| 23 | Exportación CoT/ATAK y puente con Meshtastic | M | M | Sí (teléfono) | v2 |
| 24 | IFF "lite" por Channel Sounding con nRF54L15 (solo distancia) | M | M | No (~US$10 por nodo; el reloj no participa) | v2 |
| 25 | IFF por firma de movimiento IMU (tipo PmTrack) | M | A | Parcial | v2 / investigación |
| 26 | Audio espacial por auricular | M | M | Sí (verificar latencia) | v2 |
| 27 | Modo árbitro / espectador y replay combinado del equipo | M | A | No | v2 |
| — | IFF por RSSI; beacons IR; jamming | — | — | — | Descartado |

- **Secuencia sugerida.** En v1, primero la confianza del sistema: estado honesto (3), edad de contactos (2) y rate limiter (5). Un radar que miente o que vibra de más se abandona en la primera partida. Lo "wow" viene después.
- **Mayor retorno por dólar.** El cinturón háptico (14) cuesta unos US$5 y resuelve la limitación física de un solo actuador en la muñeca. Es la mejor inversión de hardware antes de meterse con IFF.
- **IFF (v2).** Empezar con 2 nodos UWB (uno propio y el tag de un compañero) para validar la asociación y el riesgo de "falso amigo" antes de escalar a la escuadra.

### Gaps
- Los valores y esfuerzos de la tabla son estimaciones propias. No hay datos de usuarios de airsoft que los validen; conviene una encuesta rápida o una prueba en campo con 3–5 jugadores.
- La factibilidad de las filas 8, 15, 17 y 26 depende de pruebas en el dispositivo que no se hicieron: encendido programático de la pantalla en el Galaxy Watch7, detección de objetivos quietos y estabilidad de IDs en el LD2450, y latencia del audio Bluetooth.
- Falta confirmar precios y envío a Colombia de los módulos UWB y nRF54L15; los precios citados son en USD de tiendas extranjeras.
