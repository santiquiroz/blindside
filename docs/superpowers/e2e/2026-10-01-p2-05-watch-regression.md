# E2E de regresión del reloj (spec §7 paso 2), plan 05

- Fecha: 2026-10-01T15:38:04-05:00
- Rama: feat/android-shared @ 562980d (worktree C:/personal/blindside-shared)
- Firmware flasheado: main @ cd5be79
- Reloj: SM-L310

```
constexpr char kFirmwareVersion[] = "0.1.0";
Starting: Intent { cmp=io.github.santiquiroz.blindside/.wear.MainActivity }
[  5.0 s] diag up=25s radar0[alive=0 ok=0 bad=0 rst=0 baud=0 gap=0..0] radar1[alive=0 ok=0 bad=0 rst=0 baud=0 gap=0..0] imu0[ok=0 who=0x00 reads=0 fail=0 rep=5002 smp=250] imu1[ok=0 who=0x00 reads=0 fail=0 rep=5002 smp=250] link[conn=1 sub=1 trusted=1 mtu=23 itvl=40 lat=0 sup=400 disc=0] tx[sent=0 fail=0 dropped=0 skipped=0]
[ 10.0 s] diag up=30s radar0[alive=0 ok=0 bad=0 rst=0 baud=0 gap=0..0] radar1[alive=0 ok=0 bad=0 rst=0 baud=0 gap=0..0] imu0[ok=0 who=0x00 reads=0 fail=0 rep=6002 smp=250] imu1[ok=0 who=0x00 reads=0 fail=0 rep=6002 smp=250] link[conn=1 sub=1 trusted=1 mtu=255 itvl=24 lat=0 sup=500 disc=0] tx[sent=50 fail=0 dropped=0 skipped=0]
[ 15.0 s] diag up=35s radar0[alive=0 ok=0 bad=0 rst=1 baud=0 gap=0..0] radar1[alive=0 ok=0 bad=0 rst=1 baud=0 gap=0..0] imu0[ok=0 who=0x00 reads=0 fail=0 rep=7002 smp=250] imu1[ok=0 who=0x00 reads=0 fail=0 rep=7002 smp=250] link[conn=1 sub=1 trusted=1 mtu=255 itvl=24 lat=0 sup=500 disc=0] tx[sent=100 fail=0 dropped=0 skipped=0]
[ 20.0 s] diag up=40s radar0[alive=0 ok=0 bad=0 rst=1 baud=0 gap=0..0] radar1[alive=0 ok=0 bad=0 rst=1 baud=0 gap=0..0] imu0[ok=0 who=0x00 reads=0 fail=0 rep=8003 smp=250] imu1[ok=0 who=0x00 reads=0 fail=0 rep=8003 smp=250] link[conn=1 sub=1 trusted=1 mtu=255 itvl=24 lat=0 sup=500 disc=0] tx[sent=150 fail=0 dropped=0 skipped=0]
[ 25.0 s] diag up=45s radar0[alive=0 ok=0 bad=0 rst=1 baud=0 gap=0..0] radar1[alive=0 ok=0 bad=0 rst=1 baud=0 gap=0..0] imu0[ok=0 who=0x00 reads=0 fail=0 rep=9003 smp=250] imu1[ok=0 who=0x00 reads=0 fail=0 rep=9003 smp=250] link[conn=1 sub=1 trusted=1 mtu=255 itvl=24 lat=0 sup=500 disc=0] tx[sent=200 fail=0 dropped=0 skipped=0]
[ 30.0 s] diag up=50s radar0[alive=0 ok=0 bad=0 rst=1 baud=0 gap=0..0] radar1[alive=0 ok=0 bad=0 rst=1 baud=0 gap=0..0] imu0[ok=0 who=0x00 reads=0 fail=0 rep=10003 smp=250] imu1[ok=0 who=0x00 reads=0 fail=0 rep=10003 smp=250] link[conn=1 sub=1 trusted=1 mtu=255 itvl=24 lat=0 sup=500 disc=0] tx[sent=250 fail=0 dropped=0 skipped=0]
[ 35.0 s] diag up=55s radar0[alive=0 ok=0 bad=0 rst=1 baud=0 gap=0..0] radar1[alive=0 ok=0 bad=0 rst=1 baud=0 gap=0..0] imu0[ok=0 who=0x00 reads=0 fail=0 rep=11004 smp=251] imu1[ok=0 who=0x00 reads=0 fail=0 rep=11004 smp=251] link[conn=1 sub=1 trusted=1 mtu=255 itvl=24 lat=0 sup=500 disc=0] tx[sent=300 fail=0 dropped=0 skipped=0]
REGRESSION PASS trusted+sub at 5 s, sent 0 -> 50
```

Logcat del reloj durante la conexión (orden de configuración del enlace, con SET_ROLE 06 00 aceptado por el firmware 0.1.0):

```
10-01 15:35:14.462 13151 13151 I BeltGatt: DiscoverServices -> status 0
10-01 15:35:14.594 13151 13151 I BeltGatt: RequestMtu(mtu=517) -> status 0
10-01 15:35:14.825 13151 13151 I BeltGatt: ReadInfo -> status 0
10-01 15:35:14.927 13151 13151 I BeltGatt: WriteCommand(command=SetRole(role=WATCH)) -> status 0
10-01 15:35:15.058 13151 13151 I BeltGatt: EnableStreamNotify -> status 0
10-01 15:35:15.296 13151 13151 I BeltGatt: WriteSessionActive(active=true) -> status 0
```

Observación fuera del criterio del paso 2: en este banco los dos radares reportan `alive=0` y las dos IMU `ok=0 who=0x00`. El paso 2 solo mide el enlace del reloj; revisar el cableado y la alimentación de los sensores antes de una prueba de campo.
