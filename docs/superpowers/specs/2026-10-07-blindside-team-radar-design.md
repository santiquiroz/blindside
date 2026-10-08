# Blindside — radar de equipo sin cinturón (Android) — diseño

Fecha: 2026-10-07 · Estado: aprobado por Santiago · Meta: partida del 2026-10-11. Base: [integración ATAK](2026-10-07-blindside-atak-design.md).

## 1. Objetivo

Que un compañero **sin cinturón y sin reloj** saque provecho de Blindside en su celular Android: ver a los aliados y los contactos que publican los cinturones del equipo, y recibir un aviso háptico cuando un contacto del equipo está cerca. Los iPhone usan iTAK el 11; el port a iOS tiene su propio spec después.

Criterios de éxito:

- Con el enlace TAK prendido, la pestaña **Equipo** muestra un radar circular orientado con la brújula del celular: aliados en cian, contactos del equipo en amarillo, a escala 50/100/250 m.
- Un contacto publicado por otro cinturón a ≤ 30 m del compañero hace vibrar su celular (en el bolsillo, pantalla apagada) y deja una notificación "Contacto a 22 m al NE · Radar Santi 1".
- Un compañero que no usa ATAK puede publicar su propia posición desde Blindside.

Fuera de alcance: este modo en el reloj sin cinturón, un mapa tipo ATAK, iOS.

## 2. Datos

Todo sale del enlace TAK que ya existe (`TakLinkService`): no hay mensajes nuevos entre celular y reloj.

- **Aliados:** `TeamRoster` (ya existe).
- **Contactos del equipo:** `ContactBoard` nuevo. Acepta eventos `a-h-*` (hostil) y `a-u-*` (desconocido) con punto válido, que no estén vencidos (`stale`), y que no sean del propio dispositivo (uid que empieza por `BLINDSIDE-<deviceId>-`). Un contacto vence en su `stale` (o 30 s después de recibido si no trae `stale`). `t-x-d-d` lo borra. Así entran los contactos de cualquier cinturón Blindside y los marcadores hostiles que un jugador de ATAK ponga a mano.
- **Posición propia:** GPS del celular (`TakLocation`), fresca ≤ 15 s.
- **Rumbo propio:** brújula del celular (`TYPE_ROTATION_VECTOR`). Con el celular plano se usa el eje de las 12 en punto; con el celular levantado (más vertical que horizontal) se usa hacia donde apunta la parte de atrás. Se toma el eje con mayor componente horizontal.

## 3. Radar de equipo (pestaña Equipo)

Con el enlace prendido, la pestaña empieza con el radar (cuadrado, ancho completo) y debajo el estado y los ajustes. Sin enlace, la pestaña queda como hoy.

- Centro = el jugador; arriba = hacia donde mira el celular. Letra **N** que gira con el rumbo.
- Anillos a 1/3, 2/3 y 3/3 de la escala, con su distancia en metros. Tocar el radar alterna la escala 50 → 100 → 250 m.
- **Aliados:** punto cian + "TO 45 m" (iniciales + distancia).
- **Contactos:** punto amarillo; se desvanece con la edad (opaco hasta 5 s, tenue a los 30 s).
- Lo que queda fuera de escala se dibuja como triángulo en el borde, en su dirección.
- Brújula sin calibrar → texto "Brújula: calibra (mueve en 8)". Sin GPS → texto "Sin GPS: esperando posición" y no se dibujan marcas.
- La pantalla queda encendida mientras el radar está visible.

## 4. Avisos hápticos

- En el servicio (funciona con pantalla apagada), cada segundo: contactos del equipo a **≤ 30 m** de la posición propia → vibración (dos pulsos) + notificación en el canal "Avisos del equipo" (importancia alta): "Contacto a 22 m al NE · Radar Santi 1". El rumbo es cardinal desde el norte (no depende de la brújula).
- Como mucho un aviso por contacto cada 30 s.
- Interruptor "Avisos por vibración" (encendido por defecto).

## 5. Publicar mi posición

Interruptor "Publicar mi posición (solo si no usas ATAK)", apagado por defecto. Encendido y con GPS fresco: cada 5 s un evento `a-f-G-U-C`, uid `BLINDSIDE-<deviceId>-SA`, callsign = el del jugador, `stale` +30 s. Con ATAK prendido el jugador aparecería dos veces, por eso va apagado.

## 6. Distribución

Release v1.1.0 en GitHub con el APK del celular (y el del reloj). Cada compañero Android: instala el APK, importa su `jugadorN_CONFIG.zip` en la pestaña Equipo, pone un callsign único y conecta. Guía en `docs/tak-server.md`.

## 7. Pruebas

- **JVM:** `ContactBoard` (acepta/rechaza tipos, vencidos, propios; borrado; vencimiento), avisos (distancia, límite por contacto, texto), geometría del radar (ángulo relativo al rumbo, fracción de radio, fuera de escala, ciclo de escalas), rumbo del celular (plano al norte, plano al este, levantado mirando al este), `selfEvent`.
- **En el celular de Santiago:** `tak-probe.py --contact` simula un contacto de otro cinturón a 20 m → vibra y notifica; un aliado simulado aparece en el radar en la dirección correcta.
