# Servidor TAK propio (OpenTAKServer) para el equipo

Guía para montar el mapa compartido del equipo en un PC con Windows e IP pública fija. Lo usan ATAK (Android), iTAK (iPhone) y Blindside, que publica ahí los contactos del radar y los puntos tácticos. Diseño: [`superpowers/specs/2026-10-07-blindside-atak-design.md`](superpowers/specs/2026-10-07-blindside-atak-design.md).

Lo único que queda abierto a internet es el puerto **8089**. Está cifrado y solo entra quien tenga un certificado firmado por tu servidor.

## 1. Instalar OpenTAKServer en WSL2

En PowerShell (usuario normal):

```powershell
wsl --install -d Ubuntu-24.04 --no-launch
wsl -d Ubuntu-24.04 -u root -- bash -c "useradd -m -s /bin/bash ots; echo 'ots ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/ots; chmod 440 /etc/sudoers.d/ots; printf '[boot]\nsystemd=true\n[user]\ndefault=ots\n' > /etc/wsl.conf"
wsl --terminate Ubuntu-24.04
```

El instalador oficial pregunta por ZeroTier leyendo de la terminal. Para correrlo sin preguntas se le quita esa lectura y se le contesta "n":

```bash
# dentro de: wsl -d Ubuntu-24.04
curl -sL https://i.opentakserver.io/ubuntu_installer -o ~/ots_install.sh
sed -i 's#< /dev/tty##g' ~/ots_install.sh
printf 'n\nn\n' | bash ~/ots_install.sh 2>&1 | tee ~/ots_install.log
systemctl is-active opentakserver cot_parser eud_handler eud_handler_ssl rabbitmq-server nginx
```

Tarda unos 15 minutos. Al final todos los servicios dicen `active`.

**Arranque con Windows (aunque nadie inicie sesión).** WSL apaga la distro cuando no queda ningún proceso, y solo arranca con un usuario. [`tak/windows-autostart.ps1`](../tak/windows-autostart.ps1) (PowerShell como administrador) registra la tarea programada "OpenTAKServer WSL": al encender el PC lanza `wsl -d Ubuntu-24.04 --exec sleep infinity` en modo S4U (sin guardar la contraseña de Windows), así los servicios de OpenTAKServer arrancan solos. `-Test` lo comprueba apagando WSL y relanzando la tarea. Alternativa si solo te importa con sesión iniciada: [`tak/wsl-keepalive.vbs`](../tak/wsl-keepalive.vbs) en `shell:startup`.

Revisa también que el PC no se suspenda ni hiberne (Configuración → Energía): un PC dormido es un servidor caído.

## 2. Red

1. **Windows** (una vez, en PowerShell como administrador). Expone solo el 8089 de WSL en la IP LAN del PC y abre ese puerto en el firewall:
   ```powershell
   powershell -ExecutionPolicy Bypass -File tak\windows-network.ps1 -LanIp <IP LAN del PC>
   ```
   La IP LAN del PC tiene que ser fija: reservarla en el DHCP del router.
2. **Router.** Reenvío de puerto TCP **8089** externo → `<IP LAN del PC>:8089`. Nada más: ni 8088 (sin cifrar), ni 80/443/8080 (web), ni 8443/8446.
3. **Comprobar que no hay CGNAT.** La IP WAN que muestra el router tiene que ser la misma que da `curl https://api.ipify.org`. Si el router muestra una 100.64.x.x–100.127.x.x, el proveedor comparte la IP y el reenvío no sirve.

La interfaz web de administración queda solo en el PC: `https://localhost` (certificado propio, el navegador avisa). Ahí están el **mapa en vivo** de todos los conectados, usuarios, grupos, paquetes de datos, misiones, video, alertas/CasEvac y Meshtastic.

## 3. Administrador

El usuario inicial es `administrator` / `password`. Cámbialo antes de abrir el puerto:

```bash
NEW=$(python -c "import secrets;print(secrets.token_urlsafe(18))"); echo "$NEW" > ~/.blindside/tak-admin.txt
# luego, con sesión iniciada en https://localhost: Usuarios → administrator → cambiar contraseña,
# o por API: POST /api/user/password/reset {"username":"administrator","new_password":"..."} con el token CSRF del login.
```

`tak/ots-player.sh` lee esa contraseña de `~/.blindside/tak-admin.txt` (o de `OTS_ADMIN_PASSWORD`).

## 4. Jugadores

Un usuario por jugador. El script crea el usuario y su certificado, y copia sus dos paquetes a `~/.blindside/tak-packages/`:

```bash
OTS_PUBLIC_HOST=<nombre DNS o IP pública> bash tak/ots-player.sh jugador1
```

El paquete apunta a la dirección con la que se pidió (OpenTAKServer la copia en `connectString`). Por eso el script la pide con la dirección pública y no con `localhost`.

Mejor un nombre que la IP: un registro **A** propio (p. ej. `tak.<tu-dominio>`, TTL 300) hacia la IP pública. Si el proveedor cambia la IP, se actualiza el DNS y nadie vuelve a importar nada. Volver a correr el script con otro `OTS_PUBLIC_HOST` reescribe los paquetes de un usuario existente (la API contesta "Certificate already exists" pero el zip sale con el host nuevo).

| Quién | Paquete | Cómo |
|---|---|---|
| ATAK (Android) | `<usuario>_CONFIG.zip` | Pasarlo al celular → ATAK → Importar → seleccionar el zip. |
| iTAK (iPhone) | `<usuario>_CONFIG_iTAK.zip` | Abrirlo desde Archivos con iTAK (Ajustes → Servidores → +, importar paquete). |
| Blindside | cualquiera de los dos | App del celular → pestaña **Equipo** → Importar paquete. Puede ser el mismo del ATAK de ese jugador. |

**Desde la interfaz web:** `windows-autostart.ps1 -LocalDomain <tu dominio>` agrega `127.0.0.1 <tu dominio>` al archivo hosts del PC. Abre la interfaz con **https://<tu dominio>** (no con localhost): Usuarios → crear usuario → **Generate Configuration Data Package**, y descárgalo en Data Packages. Los paquetes llevan la dirección con la que abriste la interfaz, por eso hay que usar el dominio.

**Después de descargarlo, bórralo del servidor** (Data Packages → borrar). OpenTAKServer deja cada paquete de conexión como paquete de datos público: cualquier usuario conectado podría bajar el de otro y entrar con su certificado. Borrarlo no le corta la conexión al jugador (probado): solo elimina la copia del servidor. Guarda tu copia en `~/.blindside/tak-packages/`.

Reglas:

- **Cada jugador con un callsign distinto.** OpenTAKServer identifica también por callsign. Un segundo dispositivo con un callsign repetido se conecta pero sus posiciones se descartan sin aviso (`ForeignKeyViolation cot_eud` en `~/ots/logs/cot_parser.log`).
- El paquete lleva la llave privada del jugador. Se manda por chat privado, nunca a un grupo ni a un repositorio. Para cortarle el acceso a alguien: interfaz web → Usuarios → desactivar o borrar. Sin usuario, el servidor rechaza su certificado al conectar.
- Todos quedan en el grupo por defecto `__ANON__`, así se ven entre todos.

### Compañeros sin cinturón (Android)

Un compañero sin cinturón ni reloj también usa Blindside: instala el APK del celular (release de GitHub), importa su paquete en la pestaña **Equipo**, pone un callsign único y conecta. Con el enlace prendido la pestaña muestra el **radar de equipo**:

- aliados en cian y contactos de los cinturones del equipo en amarillo, orientados con la brújula del celular; tocar el radar cambia la escala (50/100/250 m);
- **aviso por vibración** cuando un contacto del equipo está a 30 m o menos (funciona con la pantalla apagada);
- **"Publicar mi posición"** solo si no usa ATAK (si usa ATAK, aparecería dos veces en el mapa).

Los iPhone usan iTAK con su paquete `_CONFIG_iTAK.zip`: ven a los aliados y los contactos del radar en el mapa.

## 5. Mapas y capa del campo

Tres herramientas arman lo que se reparte antes de una partida:

- [`tak/map-sources/`](../tak/map-sources/): fuentes de mapa para ATAK (Bing Aerial, Esri World Imagery, Esri World Topo, OpenTopoMap, Google). Google se ve borroso en zonas rurales donde Bing/Esri (Maxar) siguen nítidos; compara antes de elegir.
- [`tak/geojson2kml.py`](../tak/geojson2kml.py): convierte un GeoJSON con estilos [simplestyle](https://github.com/mapbox/simplestyle-spec) (`stroke`, `fill`, `fill-opacity`, `marker-color`, más `folder` para agrupar) en KML/KMZ para ATAK, iTAK y Google Earth.
- [`tak/datapackage.py`](../tak/datapackage.py): empaqueta archivos en un paquete de datos TAK (`MANIFEST/manifest.xml`). El uid sale del nombre, así que reconstruirlo con el mismo nombre reemplaza al anterior en vez de duplicarlo.

```bash
python tak/geojson2kml.py campo.geojson campo.kmz --name "Campo"
python tak/datapackage.py --name "Campo" --out campo-paquete.zip campo.kmz tak/map-sources/bing-aerial.xml tak/map-sources/esri-world-imagery.xml
```

Para que cada ATAK lo reciba solo: interfaz web → Data Packages → subir el zip → activar **Install on connection**. ATAK pide su perfil de dispositivo al conectarse (`/Marti/api/device/profile/connection` en el puerto 8443) y el servidor le incluye el paquete. iTAK no lo pide: a los iPhone se les manda el zip por chat (no trae llaves) y lo importan en Data Packages.

### Capa táctica en vivo

[`tak/tak-overlay.py`](../tak/tak-overlay.py) se conecta al servidor como un cliente más ("Mapa") y transmite el GeoJSON como objetos nativos de ATAK/iTAK: polígonos y líneas (`u-d-f`) y marcadores (`b-m-p-s-m`). Todos los conectados los reciben sin importar nada. Repite el envío cada `--period` segundos (120 por defecto) para quien se conecte tarde, porque OpenTAKServer no reenvía formas a clientes nuevos. Si se edita el GeoJSON, el siguiente envío actualiza los objetos y borra los que ya no están. Las curvas de nivel se omiten (`--skip-folder`); para esas sigue el paquete del campo.

```bash
OTS_PUBLIC_HOST=<dominio> bash tak/ots-player.sh mapa      # usuario propio para el publicador; luego borrar sus paquetes del servidor
python tak/tak-overlay.py ~/.blindside/tak-packages/mapa_CONFIG.zip campo.geojson --callsign "Mapa" --once   # prueba
# servicio permanente dentro de WSL (arranca con OpenTAKServer):
sudo bash tak/overlay-service.sh /mnt/c/Users/<usuario>/.blindside/tak-packages/mapa_CONFIG.zip /mnt/c/Users/<usuario>/campo.geojson "Mapa"
journalctl -u blindside-overlay -f
```

## 6. Replay después de la partida

[`tak/replay.py`](../tak/replay.py) saca las posiciones guardadas en el servidor y arma una página con el recorrido animado de cada jugador, sus distancias, velocidad máxima y los contactos del radar:

```bash
python tak/replay.py --since "2026-10-10 15:30" --until "2026-10-10 23:59" --overlay campo.geojson --title "Partida del sábado" --out replay.html
```

Las horas son locales (`--utc-offset`, por defecto -5). La página trae las posiciones reales de cada jugador: compártela solo con el equipo. OpenTAKServer tiene una tarea `delete_old_data` que, si está activa, borra lo que tenga más de una semana.

## 7. Probar

[`tak/tak-probe.py`](../tak/tak-probe.py) se conecta con un paquete e imprime todo lo que llega; con `--at lat,lon` se anuncia como un compañero:

```bash
python tak/tak-probe.py ~/.blindside/tak-packages/<usuario>_CONFIG_iTAK.zip --host <IP LAN del PC> --callsign Toro --at 5.0689,-75.5174
python tak/tak-probe.py ~/.blindside/tak-packages/<otro>_CONFIG.zip --host <IP LAN del PC> --callsign Lince
# contacto de radar simulado (como si lo publicara otro cinturón), útil para probar los avisos:
python tak/tak-probe.py ~/.blindside/tak-packages/<usuario>_CONFIG.zip --callsign Puma --contact <lat>,<lon>
```

Cada uno debe ver al otro. La sonda registra un dispositivo `tak-probe-<callsign>` en el servidor; usa un callsign que no sea de nadie. Desde afuera (celular con Wi-Fi apagado), ATAK debe quedar en verde con la IP pública.

## 8. Problemas

| Síntoma | Causa probable |
|---|---|
| ATAK/iTAK en rojo desde datos móviles, en verde en la Wi-Fi de la casa | Falta el reenvío del router, la IP LAN del PC cambió o hay CGNAT. |
| Conecta pero no ve a nadie | Callsign repetido (ver arriba) o usuarios en grupos distintos. |
| `SSL` / handshake rechazado | Paquete de otro servidor, usuario desactivado o CA regenerada. Generar paquete nuevo. |
| Blindside: "El truststore del paquete no trae certificados" | El paquete no trae `truststore-root.p12` legible; regenerarlo con `tak/ots-player.sh`. |
| `https://tak.<tu dominio>` no carga en el navegador de un compañero | Normal: hacia afuera solo está abierto el 8089 (ATAK/iTAK). La interfaz web solo se abre desde el PC del servidor. |
| Todo dejó de responder tras reiniciar el PC | WSL no arrancó: revisar la tarea "OpenTAKServer WSL" (`windows-autostart.ps1 -Test`) o abrir cualquier terminal de WSL. |

Registros: `~/ots/logs/` dentro de WSL (`eud_handler_ssl.log` conexiones, `cot_parser.log` mensajes).
