#!/usr/bin/env bash
# Spec §7 steps 3-7 without Santiago: install, hands-free pairing, two links, phone force-stop, a recording pulled from the watch.
set -u
export MSYS_NO_PATHCONV=1

# With MSYS_NO_PATHCONV Git Bash no longer rewrites /c/... for Windows tools (python, adb), so paths must already be native.
native_dir() {
    (cd "$1" && { pwd -W 2>/dev/null || pwd; })
}

HERE="$(native_dir "$(dirname "$0")")"
WATCH_DIR="$(native_dir "$HERE/../..")"
TOOLS=(python "$HERE/e2e_tools.py")
PKG=io.github.santiquiroz.blindside
PHONE_ACTIVITY="$PKG/io.github.santiquiroz.blindside.phone.MainActivity"
WATCH_ACTIVITY="$PKG/io.github.santiquiroz.blindside.wear.MainActivity"
PHONE_APK="$WATCH_DIR/phone-app/build/outputs/apk/debug/phone-app-debug.apk"
WEAR_APK="$WATCH_DIR/wear-app/build/outputs/apk/debug/wear-app-debug.apk"
BELT_PORT="${BELT_PORT:-COM6}"
PASSKEY="${PASSKEY:-799334}"
PHONE_IP="${PHONE_IP:-192.168.10.119}"
CONFIRM_PATTERN='(?i)(vincular|emparejar|ok|aceptar|pair)'
OUT="$WATCH_DIR/phone-app/build/e2e"
REPORT="$OUT/report.txt"
FAILED=0
WATCH=""
PHONE=""
WATCH_INSTALLED=0

mkdir -p "$OUT"
: > "$REPORT"

report() {
    printf 'PASO %s: %s — %s\n' "$1" "$2" "$3" | tee -a "$REPORT"
    if [ "$2" = "FALLA" ]; then FAILED=1; fi
}

connect_wifi_devices() {
    adb connect "$PHONE_IP" >/dev/null 2>&1
    adb mdns services 2>/dev/null | awk '/_adb-tls-connect/ {print $NF}' | while read -r target; do
        adb connect "$target" >/dev/null 2>&1
    done
}

online_serials() {
    adb devices | awk 'NR > 1 && $2 == "device" {print $1}'
}

is_watch() {
    adb -s "$1" shell getprop ro.build.characteristics 2>/dev/null | grep -q watch
}

find_devices() {
    local serial
    for serial in $(online_serials); do
        if is_watch "$serial"; then
            WATCH="${WATCH:-$serial}"
        elif [ -z "$PHONE" ] || [[ "$serial" == "$PHONE_IP"* ]]; then
            PHONE="$serial"
        fi
    done
}

ui_dump() {
    # uiautomator a veces escribe un XML vacío en pantallas Compose: reintentar hasta tener raíz válida.
    local attempt
    for attempt in 1 2 3 4; do
        adb -s "$1" shell uiautomator dump /sdcard/e2e-ui.xml >/dev/null 2>&1
        adb -s "$1" exec-out cat /sdcard/e2e-ui.xml > "$OUT/ui.xml" 2>/dev/null
        [ -s "$OUT/ui.xml" ] && head -c 1 "$OUT/ui.xml" | grep -q '<' && return 0
        sleep 0.5
    done
    return 0
}

tap() {
    local coords
    ui_dump "$1"
    coords=$("${TOOLS[@]}" tap-target "$OUT/ui.xml" "$2") || return 1
    adb -s "$1" shell input tap $coords
    sleep 1
}

wait_text() {
    local deadline=$((SECONDS + $3))
    while [ "$SECONDS" -lt "$deadline" ]; do
        ui_dump "$1"
        "${TOOLS[@]}" has-text "$OUT/ui.xml" "$2" && return 0
        sleep 2
    done
    return 1
}

screen_shows() {
    "${TOOLS[@]}" has-text "$OUT/ui.xml" "$1"
}

swipe_up() {
    local coords
    coords=$("${TOOLS[@]}" swipe-up "$OUT/ui.xml") || return 1
    adb -s "$1" shell input swipe $coords 300
    sleep 1
}

# uiautomator only dumps what is on screen, so a long Compose list is scrolled until the text shows up.
scroll_to() {
    local attempt
    for attempt in 1 2 3 4 5 6 7 8; do
        ui_dump "$1"
        screen_shows "$2" && return 0
        swipe_up "$1" || return 1
    done
    return 1
}

tap_scrolled() {
    scroll_to "$1" "$2" && tap "$1" "$2"
}

read_settings() {
    adb -s "$1" exec-out run-as "$PKG" cat files/datastore/blindside_settings.preferences_pb > "$OUT/settings-$2.pb" 2>/dev/null
}

stored_handedness() {
    read_settings "$1" "$2"
    "${TOOLS[@]}" handedness "$OUT/settings-$2.pb"
}

wait_handedness() {
    local deadline=$((SECONDS + $4))
    while [ "$SECONDS" -lt "$deadline" ]; do
        [ "$(stored_handedness "$1" "$2")" = "$3" ] && return 0
        sleep 2
    done
    return 1
}

diag_check() {
    "${TOOLS[@]}" check "$1" "$BELT_PORT" "$2" >> "$OUT/diag.log" 2>&1
}

# diag comes every 5 s, so each probe reads 12 s to see at least two lines.
wait_diag() {
    local deadline=$((SECONDS + $2))
    while [ "$SECONDS" -lt "$deadline" ]; do
        diag_check "$1" 12 && return 0
    done
    return 1
}

belt_firmware() {
    diag_check dual-firmware 12
    case $? in
        0) echo dual ;;
        1) echo single ;;
        *) echo unreachable ;;
    esac
}

crashed() {
    adb -s "$1" logcat -d -b crash 2>/dev/null | grep -q "$PKG"
}

preflight() {
    command -v adb >/dev/null || { report 0 "NO EJECUTADO" "adb no está en el PATH"; return 1; }
    python -c "import serial" 2>/dev/null || python -m pip install --quiet pyserial
    "${TOOLS[@]}" selftest >/dev/null || { report 0 "FALLA" "e2e_tools.py selftest"; return 1; }
    [ -f "$PHONE_APK" ] && [ -f "$WEAR_APK" ] || { report 0 "NO EJECUTADO" "faltan los APK: ./gradlew :phone-app:assembleDebug :wear-app:assembleDebug"; return 1; }
    connect_wifi_devices
    find_devices
    printf 'reloj=%s celular=%s cinturón=%s\n' "${WATCH:--}" "${PHONE:--}" "$BELT_PORT" | tee -a "$REPORT"
}

step3_install() {
    [ -n "$PHONE" ] || { report 3 "NO EJECUTADO" "no hay celular por adb ($PHONE_IP o mDNS)"; return 1; }
    adb -s "$PHONE" install -r "$PHONE_APK" >/dev/null || { report 3 "FALLA" "adb install del celular"; return 1; }
    for permission in BLUETOOTH_SCAN BLUETOOTH_CONNECT POST_NOTIFICATIONS; do
        adb -s "$PHONE" shell pm grant "$PKG" "android.permission.$permission" >/dev/null 2>&1
    done
    [ "$(adb -s "$PHONE" shell settings get global bluetooth_on | tr -d '\r')" = 1 ] || adb -s "$PHONE" shell svc bluetooth enable >/dev/null 2>&1
    adb -s "$PHONE" logcat -b crash -c >/dev/null 2>&1
    adb -s "$PHONE" shell am start -n "$PHONE_ACTIVITY" >/dev/null
    sleep 5
    if adb -s "$PHONE" shell pidof "$PKG" >/dev/null && ! crashed "$PHONE"; then
        report 3 "PASA" "instalada, permisos concedidos y abierta"
    else
        report 3 "FALLA" "la app no quedó abierta (adb logcat -b crash)"
        return 1
    fi
}

ensure_watch_app() {
    [ "$WATCH_INSTALLED" = 1 ] && return 0
    adb -s "$WATCH" install -r "$WEAR_APK" >/dev/null && WATCH_INSTALLED=1
}

start_watch_game() {
    ensure_watch_app || return 1
    adb -s "$WATCH" shell am start -n "$WATCH_ACTIVITY" >/dev/null
    wait_diag watch 36
}

enter_passkey() {
    local coords deadline=$((SECONDS + $2))
    while [ "$SECONDS" -lt "$deadline" ]; do
        ui_dump "$1"
        if coords=$("${TOOLS[@]}" edit-field "$OUT/ui.xml"); then
            adb -s "$1" shell input tap $coords
            adb -s "$1" shell input text "$PASSKEY"
            tap "$1" "$CONFIRM_PATTERN" || adb -s "$1" shell input keyevent KEYCODE_ENTER
            return 0
        fi
        sleep 2
    done
    return 1
}

pair_or_reuse() {
    if enter_passkey "$PHONE" 60; then echo "clave escrita"; return 0; fi
    wait_diag two-links 24 && { echo "el celular ya estaba emparejado"; return 0; }
    return 1
}

step4_pair() {
    [ -n "$WATCH" ] || { report 4 "NO EJECUTADO" "sin reloj por adb: empareja a mano con BOOT 3 s (lista de verificación)"; return 1; }
    case "$(belt_firmware)" in
        unreachable) report 4 "NO EJECUTADO" "no se pudo leer $BELT_PORT (¿monitor serie abierto?)"; return 1 ;;
        single) report 4 "NO EJECUTADO" "el cinturón tiene el firmware 0.1.0: emparejar el celular desemparejaría el reloj"; return 1 ;;
    esac
    start_watch_game || { report 4 "FALLA" "el reloj no transmite en 36 s (regresión del paso 2)"; return 1; }
    # El botón queda bajo el fold de la pestaña Cinturón, y los ítems fuera de pantalla no salen en el dump de Compose: hay que desplazar.
    { tap "$PHONE" "Cintur.n" && tap_scrolled "$PHONE" "Pedir al reloj que abra la ventana"; } || { report 4 "FALLA" "no se encontró el botón en el celular"; return 1; }
    wait_text "$PHONE" "Pedido enviado.*" 15 || { report 4 "FALLA" "el reloj no respondió REQUESTED (ver $OUT/ui.xml)"; return 1; }
    # Con el cinturón ya emparejado el celular autoarranca el radar: tocar "Iniciar radar" es best-effort, no un requisito.
    tap "$PHONE" "Radar"; tap_scrolled "$PHONE" "Iniciar radar" || echo "el celular ya estaba transmitiendo (autoarranque)"
    local detail
    detail=$(pair_or_reuse) || { report 4 "FALLA" "no apareció el diálogo de clave ni conectó en 84 s"; return 1; }
    report 4 "PASA" "ventana abierta desde el reloj (05); $detail"
}

step5_two_links() {
    wait_diag two-links 60 || { report 5 "FALLA" "no hay dos enlaces transmitiendo con dropped(watch)=0 (ver $OUT/diag.log)"; return 1; }
    diag_check phone-interval 12 || { report 5 "FALLA" "el intervalo del celular no está en 60-100 ms (itvl 48-80)"; return 1; }
    report 5 "PASA" "reloj y celular trusted y sub, sent subiendo en ambos, dropped(watch)=0, itvl del celular en 60-100 ms"
}

phone_sets_handedness() {
    local label
    label=$("${TOOLS[@]}" phone-label "$1") || return 1
    tap "$PHONE" "Cintur.n" && tap_scrolled "$PHONE" "$label"
}

# The watch game shows the radar; one swipe back lands on the home, where Ajustes lives.
watch_cycles_handedness() {
    local tapped
    ui_dump "$WATCH"
    screen_shows "Ajustes" || { adb -s "$WATCH" shell input keyevent KEYCODE_BACK; sleep 1; }
    tap_scrolled "$WATCH" "Ajustes" && tap_scrolled "$WATCH" "Mano.*"
    tapped=$?
    adb -s "$WATCH" shell input keyevent KEYCODE_BACK
    return "$tapped"
}

# Changing the hand clears custom yaw angles, so the probe only runs when there are none, and it puts the hand back.
handedness_round_trip() {
    local original edited cycled
    read_settings "$WATCH" watch
    read_settings "$PHONE" phone
    if "${TOOLS[@]}" has-yaw "$OUT/settings-watch.pb" || "${TOOLS[@]}" has-yaw "$OUT/settings-phone.pb"; then
        echo "mano sin probar: hay ángulos personalizados y cambiar la mano los borraría"
        return 0
    fi
    original=$("${TOOLS[@]}" handedness "$OUT/settings-watch.pb")
    edited=$("${TOOLS[@]}" next-handedness "$original")
    cycled=$("${TOOLS[@]}" next-handedness "$edited")
    { phone_sets_handedness "$edited" && wait_handedness "$WATCH" watch "$edited" 20; } || { echo "la mano $edited elegida en el celular no llegó al reloj"; return 1; }
    { watch_cycles_handedness && wait_handedness "$PHONE" phone "$cycled" 20; } || { echo "la mano $cycled elegida en el reloj no llegó al celular"; return 1; }
    { phone_sets_handedness "$original" && wait_handedness "$WATCH" watch "$original" 20; } || { echo "la mano no volvió a $original en el reloj"; return 1; }
    echo "la mano viajó celular→reloj ($edited) y reloj→celular ($cycled), y volvió a $original"
}

step5b_bridge() {
    local detail
    tap "$PHONE" "Cintur.n" || { report 5b "FALLA" "no se encontró la pestaña Cinturón"; return 1; }
    wait_text "$PHONE" "Reloj: partida activa.*" 20 || { report 5b "FALLA" "el celular no ve la partida del reloj por /status (ver $OUT/ui.xml)"; return 1; }
    detail=$(handedness_round_trip) || { report 5b "FALLA" "$detail (ver $OUT/settings-*.pb)"; return 1; }
    report 5b "PASA" "Cinturón muestra Reloj: partida activa; $detail"
}

step6_force_stop() {
    adb -s "$PHONE" shell am force-stop "$PKG"
    wait_diag phone-gone 36 || { report 6 "FALLA" "la ranura del celular no se liberó o el reloj perdió datos (ver $OUT/diag.log)"; return 1; }
    report 6 "PASA" "ranura del celular libre (el cinturón vuelve a anunciarse) y el reloj sigue con sent subiendo y dropped=0"
}

download_first_listed() {
    tap "$PHONE" "Traer" || return 0
    wait_text "$PHONE" "(Tra.da|No se pudo traer).*" 900 || return 1
    screen_shows "Tra.da.*"
}

step7_pull_recording() {
    { [ -n "$PHONE" ] && [ -n "$WATCH" ]; } || { report 7 "NO EJECUTADO" "hace falta el reloj y el celular por adb"; return 1; }
    ensure_watch_app || { report 7 "FALLA" "adb install del reloj"; return 1; }
    adb -s "$PHONE" shell am start -n "$PHONE_ACTIVITY" >/dev/null
    sleep 3
    { tap "$PHONE" "Grabaciones" && tap "$PHONE" "Traer del reloj"; } || { report 7 "FALLA" "no se encontró Traer del reloj"; return 1; }
    wait_text "$PHONE" "Traer|Ya est.|El reloj no tiene grabaciones terminadas\." 20 || { report 7 "FALLA" "el reloj no respondió a la lista en 20 s"; return 1; }
    if screen_shows "El reloj no tiene grabaciones terminadas\."; then
        report 7 "NO EJECUTADO" "el reloj no tiene grabaciones terminadas: detén una partida en el reloj y repite"
        return 1
    fi
    download_first_listed || { report 7 "FALLA" "la descarga no terminó bien (ver $OUT/ui.xml)"; return 1; }
    tap "$PHONE" "Cerrar"
    tap "$PHONE" "Reloj .+" || { report 7 "FALLA" "la grabación del reloj no aparece en Grabaciones"; return 1; }
    wait_text "$PHONE" "Resumen|No se pudo abrir.*" 180 || { report 7 "FALLA" "el Visor no terminó el análisis en 3 min"; return 1; }
    if screen_shows "No se pudo abrir.*" || crashed "$PHONE"; then
        report 7 "FALLA" "el Visor no abrió la grabación (logcat -b crash)"
        return 1
    fi
    report 7 "PASA" "grabación del reloj traída por el Data Layer y abierta en el Visor"
}

step_id() {
    local id="${1#step}"
    echo "${id%%_*}"
}

# The bridge probe needs both games running, but no later step needs the probe, so its failure blocks nothing.
blocks_later_steps() {
    [ "$1" != step5b_bridge ]
}

run_chain() {
    local blocked="" step
    for step in step3_install step4_pair step5_two_links step5b_bridge step6_force_stop; do
        if [ -n "$blocked" ]; then
            report "$(step_id "$step")" "NO EJECUTADO" "depende del paso $blocked"
            continue
        fi
        "$step" || ! blocks_later_steps "$step" || blocked="$(step_id "$step")"
    done
}

main() {
    if preflight; then
        run_chain
        step7_pull_recording
    fi
    printf '\nInforme: %s\n' "$REPORT"
    exit "$FAILED"
}

main
