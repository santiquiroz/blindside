<div align="center">

# Blindside

**Un radar de muñeca de código abierto para airsoft.**

Dos radares de 24 GHz en tu cinturón. Contactos en tu Galaxy Watch.<br>
Una vibración distintiva en tu muñeca cuando alguien nuevo entra en tu campo de visión.

[English](README.md) | **Español**

</div>

> [!NOTE]
> **Estado: fase de diseño.** Actualmente este repositorio contiene el diseño v1 y la investigación detrás de él. Todavía no hay firmware ni aplicación para el reloj; esos son los siguientes hitos. Primera meta de campo: una partida de 5 horas el 11 de octubre de 2026. Dale una estrella o sigue el repositorio para estar al tanto.

---

## Qué hace

- **Dos radares de ondas milimétricas HLK-LD2450** van en la parte frontal de tu cinturón, uno sobre cada bolsillo delantero, orientados hacia afuera. Juntos cubren **aproximadamente 180° frente a ti, hasta unos 6 m**.
- **Un ESP32 en un pouch del cinturón** añade una marca de tiempo a las tramas crudas del radar y a las lecturas de dos sensores de movimiento MPU6050 (IMU), uno dentro de cada caja de radar, y luego transmite todo vía Bluetooth LE.
- **Una aplicación nativa de Wear OS en un Samsung Galaxy Watch 7** se encarga de todo el procesamiento:
  - combina lo que ven los dos radares;
  - sigue a cada contacto a lo largo del tiempo;
  - compensa tus propios giros y pasos;
  - dibuja los contactos en una pantalla de radar circular.
- **Los contactos recién confirmados hacen vibrar tu muñeca** con un ritmo diferente para izquierda, centro o derecha. No tienes que mirar. Las alertas van espaciadas: como mucho una vibración por segundo, así que los contactos que llegan juntos hacen fila y vibran uno tras otro (primero el del centro). Además hay una pausa por sector y un tope de 10 por minuto, para que un grupo de gente no se convierta en una sola vibración larga.
- Sin teléfono, sin Wi-Fi y sin nube: el cinturón solo se comunica con el reloj con el que lo emparejaste.

```
[caja izq: LD2450 + MPU6050] ─7 hilos─┐
                                      ├─ ESP32 (pouch del cinturón, retransmisor "tonto") ──BLE──▶ Galaxy Watch 7
[caja der: LD2450 + MPU6050] ─7 hilos─┘        power bank USB                                     radar-core: fusión · seguimiento ·
                                                                                                   ego-movimiento · postura de muñeca → pantalla + háptica
```

## En qué se diferencia

Ya existen "sensores de latidos" caseros construidos con el mismo radar; consulta [Antecedentes y créditos](#antecedentes-y-créditos). El más documentado monta un radar y una pantalla pequeña en el rifle, y otro es una pantalla fija con Raspberry Pi. Blindside adopta un enfoque diferente:

- **Sensores en el cinturón, pantalla en la muñeca.** Los radares siguen tus caderas, la parte más estable de tu cuerpo al moverte. La pantalla está donde ya acostumbras mirar.
- **Dos radares fusionados.** Mayor cobertura y una zona central donde un contacto detectado por ambos radares obtiene mayor confianza.
- **Compensación de movimiento.** Un radar que llevas puesto ve "fantasmas" cada vez que te mueves. Los IMU de las cajas de radar rastrean tus giros y el conteo de pasos estima tu velocidad al caminar, lo que permite distinguir paredes y árboles de personas.
- **Diseñado según cómo sostienes un M4:**
  - La apertura de los radares se carga hacia tu lado de apoyo, hacia donde suele apuntar el cañón.
  - La aplicación detecta cuándo consultas el reloj en la cara interna de la muñeca de apoyo (porte "táctico", agarre con la palma arriba) y gira la pantalla para que "arriba" sea hacia donde apuntas. Se calibra en 10 segundos apuntando a un compañero (un "cero", como con la mira).
  - Con empuñadura vertical el reloj no se puede leer mientras apuntas, así que la vibración es la que habla.
- **El sigilo es prioridad.**
  - La pantalla permanece apagada por defecto y la vibración es el canal principal.
  - El cinturón no emite luz durante el juego: el LED de estado queda apagado, y los LED de encendido que siempre están prendidos se retiran o se tapan durante el armado.
  - El Bluetooth propio del módulo de radar se desactiva y, fuera de una breve ventana de emparejamiento que abres tú, solo tu reloj emparejado puede conectarse al cinturón.
- **Grabar y reproducir.** Se puede registrar una partida en crudo y reproducirla en una PC para ajustar los filtros con datos reales.

## Límites honestos

- **No detecta latidos del corazón.** Detecta personas en **movimiento** con un radar Doppler de 24 GHz (FMCW, del mismo tipo utilizado en sensores de presencia).
- **El alcance es de unos 6 m,** con 120° por radar.
- **Sin detección confiable a través de paredes.** No cuentes con que vea a través de paredes o coberturas. La señal sí atraviesa una cubierta de plástico delgada, seca y sin metal; así funciona la carcasa. Todavía no hemos probado follaje, tela (mojada o seca) ni lluvia. Se espera que los materiales mojados o metalizados delante del radar lo degraden.
- **Las personas que se quedan completamente quietas se desvanecen.** La aplicación retiene su última posición durante unos segundos.
- **Los compañeros de equipo también aparecen.** La v1 no puede distinguir amigos de enemigos; eso está en la hoja de ruta.
- **Aún pueden aparecer fantasmas.** Al llevarse en un cuerpo en movimiento, el radar capta ecos del entorno (clutter), y la v1 lucha contra esto en lugar de eliminarlo por completo. Rinde mejor cuando estás quieto o avanzas despacio.

## Hardware (v1)

| Parte | Precio orientativo (USD, varía por tienda) | Notas |
|---|---|---|
| ESP32-WROOM-32 DevKit (30 pines) | ~US$5–10 | Periférico NimBLE, 2 UART + 2 buses I2C |
| 2× HLK-LD2450 | ~US$6–15 cada uno | 24 GHz, hasta 3 objetivos, ±60°, ~6 m. El Ai-Thinker RD-03D usa el mismo formato de trama (sin probar). |
| 2× MPU6050 (GY-521) | ~US$1–3 cada uno | Uno dentro de cada caja de radar, rígido con su radar; no se necesita magnetómetro |
| Cables JST ZH 1.5 mm de 4 pines | ~US$5–10 por kit | El conector del LD2450 no es de 2.54 mm |
| Placa con terminales de tornillo para ESP32 | ~US$5–10 | Sin soldaduras y sin cables Dupont que se aflojen |
| Dos cables de 7 hilos, 50–80 cm | — | Del pouch a las cajas de radar. Sirve un cable Ethernet viejo (8 hilos) por caja, o dos cables USB viejos (4 hilos cada uno) por caja. |
| USB power bank | — | El cinturón consume unos 300 mA a 5 V, así que 10.000 mAh duran holgadamente más de 15 h |
| Samsung Galaxy Watch 7 | — | Wear OS 6 (API 36); otros relojes Wear OS sin probar |
| Cajas de radar impresas en 3D | — | Mantienen los ángulos, detienen balines y dejan pasar la señal de 24 GHz por una ventana maciza (sin metal frente al radar). Un trozo de papel aluminio o de lata entre cada radar y tu cuerpo, aislado de la electrónica, reduce el lóbulo trasero del radar, que si no captaría tus propios movimientos. |

## Cómo funciona (versión corta)

1. **El cinturón se mantiene simple.** El ESP32 nunca interpreta objetivos. Cada 100 ms empaqueta las tramas crudas del LD2450 y las lecturas de 50 Hz de los dos IMU, cada una con su propia marca de tiempo, en una sola notificación BLE, o en varias seguidas cuando no cabe.
2. **El reloj se encarga de pensar.** El procesamiento reside en `radar-core`, un módulo puro de Kotlin que puedes probar con pruebas unitarias en una PC. Para cada paquete:
   - decodifica las tramas del radar (su inusual formato de bit de signo se gestiona explícitamente);
   - descarta lecturas de tus propios brazos y rifle;
   - convierte todo a coordenadas de cadera, luego compensa tus giros (IMU) y tus pasos;
   - mantiene cada contacto como una pista con un filtro Kalman;
   - confirma una pista nueva solo después de que aparece en 3 de 5 ventanas;
   - aplica las reglas para fantasmas;
   - selecciona el marco de referencia que usa la pantalla a partir de la postura de tu muñeca;
   - en modo Vista (pantalla siempre encendida), redibuja el radar a 30 fps, prediciendo cada pista entre paquetes.
3. **Todo es reproducible.** Un archivo de grabación (`.bsrec`) almacena los datos BLE crudos más los propios sensores del reloj, de modo que cualquier sesión de campo puede reproducirse con exactitud para probar cambios.

El diseño completo (en español) está en [docs/superpowers/specs/2026-09-30-blindside-v1-design.md](docs/superpowers/specs/2026-09-30-blindside-v1-design.md).

## Hoja de ruta

- **v1:**
  - nodo de cinturón y aplicación para el reloj;
  - radar fusionado de 180° con compensación de movimiento;
  - señales hápticas de dirección;
  - modo sigilo y modo "eliminado";
  - asistentes de calibración;
  - grabación de partidas.
- **v1.5:**
  - motores de vibración en las cajas de radar, para que el propio cinturón indique la dirección por ubicación (requiere compra);
  - silenciamiento automático cuando estás apuntando;
  - tocar un contacto para marcarlo como amigo;
  - un perfil para jugar de rodilla;
  - un cambio rápido de hombro a mitad de partida;
  - un visor de reproducciones y mapa de calor;
  - un nodo centinela que vigila una puerta y alerta a tu muñeca;
  - un modo clásico estilo "COD" con una captura cada 4 segundos;
  - un modo torneo;
  - tal vez un sensor térmico, si las grabaciones demuestran que es necesario.
- **v2:**
  - identificación amigo-enemigo por UWB;
  - enlace de escuadrón vía ESP-NOW;
  - cobertura de 360°;
  - un nodo para riel de rifle;
  - una aplicación para teléfono y exportación a ATAK/CoT;
  - audio espacial por auricular.

## Estructura del repositorio

```
docs/
  superpowers/specs/     especificación de diseño v1 (español)
  research/reports/      informe de investigación (español)
  research/research_notes/  notas con fuentes: LD2450, biomecánica, Wear OS/BLE,
                            algoritmos de seguimiento, antecedentes y reglas, ideas de UX
firmware/                (próximamente) PlatformIO + NimBLE-Arduino
watch/                   (próximamente) radar-core (Kotlin puro) + wear-app (Wear OS)
```

## Juego limpio, aspectos legales y seguridad

- **Campos de airsoft:** ninguno de los reglamentos que encontramos menciona radares, pero muchos campos prohíben las miras térmicas considerándolas un "wallhack", y el mismo argumento aplica aquí.
  - Pregunta en tu campo antes de jugar, declara el dispositivo en el registro y asegúrate de que los demás jugadores lo sepan. Nunca lo uses para detectar o seguir personas fuera de una partida.
  - Blindside incluirá un **modo "eliminado"** que apaga las alertas del radar y la pantalla hasta que reaparezcas. Un modo torneo está planeado para la v1.5.
- **Radio:** consulta tus normativas locales antes de usar un radar de 24 GHz.
  - EE. UU.: FCC §15.249.
  - UE: la ERC 70-03 de la CEPT recoge 24.05–24.25 GHz para radiodeterminación y 24.00–24.25 GHz para dispositivos de corto alcance no específicos (100 mW e.i.r.p.; texto primario sin verificar).
  - Colombia: Resolución ANE 105/2020 cubre 24.05–24.25 GHz.
  - La hoja de datos del LD2450 especifica un barrido de 24.00–24.25 GHz, así que en Colombia los 50 MHz inferiores (24.00–24.05 GHz) quedan en una zona gris (una adición de 2024, la Res. ANE 153/2024, podría cubrirlos; sin verificar).
  - No modifiques el firmware, la antena ni la potencia del LD2450.
  - Publicar el código no tiene problema; vender kits armados puede requerir homologación, marcado CE o certificación FCC.
- **Seguridad:** el LD2450 viene de fábrica con su propio Bluetooth activo, de modo que cualquiera en las cercanías podría reconfigurarlo; el firmware de Blindside lo apaga. El cinturón acepta una única conexión. Solo admite un emparejamiento nuevo durante una ventana de 60 s, que se abre cuando aún no hay reloj emparejado o al mantener 3 s el botón BOOT del ESP32 durante su primer minuto encendido (mantenerlo al encender lo pone en modo descarga). El emparejamiento usa una clave aleatoria de 6 dígitos propia de cada cinturón. Fuera de esa ventana solo puede conectarse el reloj emparejado. Dentro de ella otro dispositivo podría conectarse, pero sin la clave no lee nada, así que conviene emparejar lejos de los demás jugadores. Mantener BOOT 10 s o más (también en el primer minuto) borra el emparejamiento; después hay que olvidar el cinturón en los ajustes Bluetooth del reloj y volver a emparejar.
- **No es un dispositivo de seguridad.** No confíes en él para proteger a nadie.
- **Sin afiliación** con Activision (Call of Duty), 20th Century Studios (Aliens) ni Hi-Link.

## Antecedentes y créditos

Blindside se apoya en el trabajo de estos proyectos. Dos no tienen licencia, y el código de Rob Smith usa una licencia no comercial que no es de código abierto y es incompatible con la AGPL, por lo que las ideas se acreditan aquí y no se copia código:

- **ScienceShack: [Heartbeat Sensor from Modern Warfare 2](https://hackaday.io/project/205879-heartbeat-sensor-from-modern-warfare-2)** ([código](https://github.com/jrtage/MW2-Heartbeat-Sensor)). LD2450, XIAO RP2350 y una pantalla OLED en una montura para riel M-LOK. Su apunte sobre la orientación del eje X del módulo nos ahorró dolores de cabeza.
- **[Bronsonalan/mw2-heartbeat-sensor](https://github.com/Bronsonalan/mw2-heartbeat-sensor).** Raspberry Pi y LD2450, con pantalla estilo fósforo y una capa de seguimiento.
- **Rob Smith: [a real working Aliens M314 motion tracker](https://hackaday.io/project/203817-a-real-working-alien-motion-tracker)** ([código](https://github.com/RobSmithDev/alienmotiontracker)). Radar de 60 GHz en una Raspberry Pi.

Referencias técnicas:
- El [protocolo serie V1.03](https://make.net.za/wp-content/datasheets/HLK%20LD2450%20Serial%20Communication%20Protocol%20v1.03.pdf), el [manual de instrucciones V1.00](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf) y la [guía de usuario](https://d.hlktech.net/download/HLK-LD2450/1/HLK-LD2450%20operation%20manual.doc..pdf) de Hi-Link. La guía rotula mal sus ejemplos de signo y de Bluetooth; el decodificador sigue el documento de protocolo.
- El [componente LD2450 de ESPHome](https://esphome.io/components/sensor/ld2450/), usado como referencia cruzada. Tiene licencia GPLv3, y el decodificador de Blindside está escrito a partir del documento de protocolo de Hi-Link.
- [Fusion](https://github.com/xioTechnologies/Fusion) de x-io Technologies, por sus valores predeterminados para la detección de reposo del IMU.
- La [guía de ajuste del group tracker mmWave](https://e2e.ti.com/cfs-file/__key/communityserver-discussions-components-files/1023/3D_5F00_people_5F00_counting_5F00_tracker_5F00_layer_5F00_tuning_5F00_guide-_2800_1_2900_.pdf) de TI, para ideas sobre seguimiento.

La bibliografía completa con fuentes se encuentra en [docs/research/](docs/research/).

## Contribuir

Las ideas y reportes de problemas son bienvenidos, especialmente:
- diseños de carcasas y monturas;
- pruebas con otros relojes Wear OS o con el RD-03D;
- reportes de campo y grabaciones crudas en `.bsrec`, una vez que exista la aplicación.

## Licencia

[GNU AGPL-3.0](LICENSE). Si distribuyes una versión modificada, comparte el código fuente.
