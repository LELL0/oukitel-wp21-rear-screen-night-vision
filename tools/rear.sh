#!/bin/bash
# Developer helper for the WP21 rear screen over adb.
#   tools/rear.sh off                 emergency: panel off without the app
#   tools/rear.sh wake | sleep        panel on / off through the app
#   tools/rear.sh goto <page>         show a page (watch, music, camera, tools, ...)
#   tools/rear.sh dump [file.png]     save the last pushed frame as PNG
#   tools/rear.sh tap X Y             synthetic tap at logical coordinates
#   tools/rear.sh swipe X1 Y1 X2 Y2   synthetic swipe
#   tools/rear.sh set KEY VALUE       change a setting (true/false, number or text)
#   tools/rear.sh log                 follow the app and daemon log
set -euo pipefail
PKG=com.lello.wp21rear
ADB=${ADB:-adb}
bc() { $ADB shell am broadcast -n $PKG/.service.DebugReceiver -a "$PKG.$1" "${@:2}" >/dev/null; }
touch_ev() { bc TOUCH --ei a "$1" --ei x "$2" --ei y "$3"; }
case "${1:-}" in
  off)   $ADB shell su -c "'am force-stop $PKG; CLASSPATH=\$(pm path $PKG | cut -d: -f2) app_process /system/bin $PKG.daemon.RearDaemon off'" ;;
  wake)  bc WAKE ;;
  sleep) bc SLEEP ;;
  goto)  bc GOTO --es page "$2" ;;
  dump)
    out=${2:-last_frame.png}
    path=$($ADB shell am broadcast -n $PKG/.service.DebugReceiver -a $PKG.DUMP_FRAME | sed -n 's/.*data="\(.*\)".*/\1/p')
    case "$path" in
      /storage/*|/sdcard/*) $ADB pull "$path" "$out" >/dev/null ;;
      /data/*) $ADB exec-out su -c "cat '$path'" > "$out" ;;
      *) echo "no frame: $path" >&2; exit 1 ;;
    esac
    echo "$out" ;;
  tap)   touch_ev 0 "$2" "$3"; touch_ev 2 "$2" "$3" ;;
  swipe)
    touch_ev 0 "$2" "$3"
    for i in 1 2 3 4 5 6; do
      touch_ev 1 $(( $2 + ($4 - $2) * i / 6 )) $(( $3 + ($5 - $3) * i / 6 ))
    done
    touch_ev 2 "$4" "$5" ;;
  set)
    case "$3" in
      true|false) bc SET --es key "$2" --ez bool "$3" ;;
      *[!0-9]*|'') bc SET --es key "$2" --es value "$3" ;;
      *) bc SET --es key "$2" --ei int "$3" ;;
    esac ;;
  log)   $ADB logcat -s WP21Rear:* WP21RearDaemon:* AndroidRuntime:E ;;
  *)     sed -n '2,11p' "$0"; exit 1 ;;
esac
