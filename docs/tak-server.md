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

**Mantener WSL vivo.** WSL apaga la distro cuando no queda ninguna terminal abierta. [`tak/wsl-keepalive.vbs`](../tak/wsl-keepalive.vbs) la deja corriendo sin ventana. Cópialo a la carpeta de inicio (`Win+R` → `shell:startup`) y ábrelo una vez.

## 2. Red

1. **Windows** (una vez, en PowerShell como administrador). Expone solo el 8089 de WSL en la IP LAN del PC y abre ese puerto en el firewall:
   ```powershell
   powershell -ExecutionPolicy Bypass -File tak\windows-network.ps1 -LanIp <IP LAN del PC>
   ```
   La IP LAN del PC tiene que ser fija: reservarla en el DHCP del router.
2. **Router.** Reenvío de puerto TCP **8089** externo → `<IP LAN del PC>:8089`. Nada más: ni 8088 (sin cifrar), ni 80/443/8080 (web), ni 8443/8446.
3. **Comprobar que no hay CGNAT.** La IP WAN que muestra el router tiene que ser la misma que da `curl https://api.ipify.org`. Si el router muestra una 100.64.x.x–100.127.x.x, el proveedor comparte la IP y el reenvío no sirve.

La interfaz web de administración queda solo en el PC: `https://localhost` (certificado propio, el navegador avisa).

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

Reglas:

- **Cada jugador con un callsign distinto.** OpenTAKServer identifica también por callsign. Un segundo dispositivo con un callsign repetido se conecta pero sus posiciones se descartan sin aviso (`ForeignKeyViolation cot_eud` en `~/ots/logs/cot_parser.log`).
- El paquete lleva la llave privada del jugador. Se manda por chat privado, nunca a un grupo ni a un repositorio. Para cortarle el acceso a alguien: interfaz web → Usuarios → desactivar.
- Todos quedan en el grupo por defecto `__ANON__`, así se ven entre todos.

## 5. Probar

[`tak/tak-probe.py`](../tak/tak-probe.py) se conecta con un paquete e imprime todo lo que llega; con `--at lat,lon` se anuncia como un compañero:

```bash
python tak/tak-probe.py ~/.blindside/tak-packages/prueba_CONFIG_iTAK.zip --host <IP LAN del PC> --callsign Toro --at 5.0689,-75.5174
python tak/tak-probe.py ~/.blindside/tak-packages/jugador1_CONFIG.zip --host <IP LAN del PC> --callsign Lince
```

Cada uno debe ver al otro. Desde afuera (celular con Wi-Fi apagado), ATAK debe quedar en verde con la IP pública.

## 6. Problemas

| Síntoma | Causa probable |
|---|---|
| ATAK/iTAK en rojo desde datos móviles, en verde en la Wi-Fi de la casa | Falta el reenvío del router, la IP LAN del PC cambió o hay CGNAT. |
| Conecta pero no ve a nadie | Callsign repetido (ver arriba) o usuarios en grupos distintos. |
| `SSL` / handshake rechazado | Paquete de otro servidor, usuario desactivado o CA regenerada. Generar paquete nuevo. |
| Blindside: "El truststore del paquete no trae certificados" | El paquete no trae `truststore-root.p12` legible; regenerarlo con `tak/ots-player.sh`. |
| Todo dejó de responder tras reiniciar el PC | WSL no arrancó: abrir `wsl-keepalive.vbs` o cualquier terminal de WSL. |

Registros: `~/ots/logs/` dentro de WSL (`eud_handler_ssl.log` conexiones, `cot_parser.log` mensajes).
