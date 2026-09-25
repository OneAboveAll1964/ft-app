#!/bin/bash
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
APK="${1:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
OUT="${2:-/tmp/ft_e2e}"
SIMDIR="$ROOT/tools/sim"
CERTDIR="${CERTDIR:-$OUT/certs}"
unset ADB_VENDOR_KEYS
ADB="adb"
PKG="app.ft"
mkdir -p "$OUT/carlife" "$OUT/aa" "$OUT/frames" "$CERTDIR"
pass=0; fail=0
ok(){ echo "PASS  $1"; pass=$((pass+1)); }
ko(){ echo "FAIL  $1"; fail=$((fail+1)); }
check(){ if eval "$2"; then ok "$1"; else ko "$1"; fi; }

echo "== device =="
$ADB devices | sed -n '2p'
[ -f "$CERTDIR/sim_srv.pem" ] || openssl req -x509 -newkey rsa:2048 -nodes -keyout "$CERTDIR/sim_srv.key" -out "$CERTDIR/sim_srv.pem" -days 365 -subj "/CN=FT AA phone sim" >/dev/null 2>&1

echo "== install =="
$ADB install -r -g "$APK" >/dev/null 2>&1 && ok "apk installed" || ko "apk install"
$ADB shell appops set $PKG SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1
$ADB shell settings put secure enabled_accessibility_services $PKG/$PKG.FTTouchService >/dev/null 2>&1
$ADB shell settings put secure accessibility_enabled 1 >/dev/null 2>&1
for p in 5277 7240 8240 9240 9241 9242 9340; do $ADB forward tcp:$p tcp:$p >/dev/null; done

$ADB logcat -c
$ADB logcat -v time > "$OUT/logcat.txt" 2>&1 &
LOGPID=$!

echo "== screen-mirror consent =="
$ADB shell am start -n $PKG/.MainActivity --ez mirror true --es pkgMaps com.android.settings >/dev/null 2>&1
sleep 3
dump(){ $ADB shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $ADB pull /sdcard/ui.xml "$OUT/consent_ui.xml" >/dev/null 2>&1; }
findnode(){ python3 - "$OUT/consent_ui.xml" "$1" "$2" <<'PY'
import re,sys
xml=open(sys.argv[1],encoding="utf-8",errors="replace").read(); want=sys.argv[2]; mode=sys.argv[3]
for node in re.finditer(r'<node [^>]*>', xml):
    n=node.group(0); t=re.search(r'text="([^"]*)"',n); t=t.group(1) if t else ''
    hit=(t==want) if mode=='eq' else (want.lower() in t.lower())
    if hit:
        m=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if m: print((int(m.group(1))+int(m.group(3)))//2,(int(m.group(2))+int(m.group(4)))//2); sys.exit(0)
sys.exit(1)
PY
}
for round in 1 2 3 4; do
  dump
  if xy=$(findnode "Share one app" eq); then
    $ADB shell input tap $xy; sleep 1; dump
    if xy2=$(findnode "entire screen" sub); then $ADB shell input tap $xy2; sleep 1; dump; fi
  fi
  tapped=0
  for label in "Share screen" "Start now" "Start sharing" "Start" "Next" "Share" "Allow"; do
    if xy=$(findnode "$label" eq); then $ADB shell input tap $xy; tapped=1; sleep 2; break; fi
  done
  grep -q 'screen mirror permitted' "$OUT/logcat.txt" && break
  [ $tapped = 0 ] && break
done
sleep 1
check "mirror consent granted" "grep -q 'screen mirror permitted' '$OUT/logcat.txt'"

echo "== start services (auto-connect + AA head unit) =="
$ADB shell am start -n $PKG/.MainActivity --ez auto true --ez aa true --es pkgMaps com.android.settings >/dev/null 2>&1
sleep 4
check "listening on the CarLife command port" "grep -q 'WIFI CMD listening on 7240' '$OUT/logcat.txt'"
check "wifi direct finder started without crash" "grep -q 'FT/P2P' '$OUT/logcat.txt'"
check "AA head unit listening" "grep -q 'head unit listening on tcp:5277' '$OUT/logcat.txt'"

echo "== run simulators =="
python3 "$SIMDIR/aa_phone_sim.py" --hu-cert "$ROOT/app/src/main/assets/aa_hu_cert.pem" --srv-cert "$CERTDIR/sim_srv.pem" --srv-key "$CERTDIR/sim_srv.key" \
  --h264 "$OUT/aa_testsrc8.h264" --seconds 8 --wait-touch 20 --out "$OUT/aa" > "$OUT/aa_sim.log" 2>&1 &
AAPID=$!
sleep 1
python3 "$SIMDIR/carlife_hu_sim.py" --mode wifi --width 1280 --height 720 --fps 30 --seconds 4 \
  --touches "284,606@0;640,360@4;40,40@8;672,195@11" --tail-seconds 6 --out "$OUT/carlife" > "$OUT/carlife_sim.log" 2>&1
CL=$?
wait $AAPID; AA=$?
sleep 2
kill $LOGPID 2>/dev/null

echo "== carlife (per-channel TCP, the wireless path) =="
check "carlife sim passed" "[ $CL = 0 ]"
python3 -c "import json;d=json.load(open('$OUT/carlife/result.json'));print('   checks:',all(d['checks'].values()),'| frames:',d.get('total_frames'),'| launcher:',d.get('video_launcher'),'| heartbeats:',d.get('heartbeats'))" 2>/dev/null
check "head unit connected to the cmd channel" "grep -q 'WIFI CMD connected from' '$OUT/logcat.txt'"
check "presentation shown on virtual display" "grep -q 'presentation shown' '$OUT/logcat.txt'"
check "encoder started 1280x720" "grep -q 'encoder started 1280x720' '$OUT/logcat.txt'"
check "projection started" "grep -q 'projection started' '$OUT/logcat.txt'"
check "AA button touch turned overlay on" "grep -q 'android auto overlay on' '$OUT/logcat.txt'"
check "FT pill touch turned overlay off" "grep -q 'android auto overlay off' '$OUT/logcat.txt'"
check "tile launched an app" "grep -q 'launched com.android.settings' '$OUT/logcat.txt'"
check "phone mirror started" "grep -qE 'mirror [0-9]+x[0-9]+ started' '$OUT/logcat.txt'"

echo "== android auto (aasdk framing + TLS) =="
check "aa phone sim passed" "[ $AA = 0 ]"
python3 -c "import json;d=json.load(open('$OUT/aa/result.json'));print('   checks:',all(d['checks'].values()),'| cert:',d.get('hu_cert_sha1','')[:12],'| acked:',d.get('frames_acked'),'/',d.get('frames_sent'),'| touch:',d.get('touch'))" 2>/dev/null
check "TLS handshake + auth complete" "grep -q 'TLS handshake complete' '$OUT/logcat.txt'"
check "video session started" "grep -q 'video start session' '$OUT/logcat.txt'"
check "AA video decoded to car overlay" "grep -q 'decoder started' '$OUT/logcat.txt'"
check "session closed by bye-bye" "grep -q 'bye-bye' '$OUT/logcat.txt'"

echo "== phone ui =="
$ADB shell am start -n $PKG/.MainActivity >/dev/null 2>&1; sleep 2
$ADB exec-out screencap -p > "$OUT/frames/phone_home.png" 2>/dev/null
$ADB shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $ADB pull /sdcard/ui.xml "$OUT/home_ui.xml" >/dev/null 2>&1
tapnav(){ python3 - "$OUT/home_ui.xml" "$1" <<'PY'
import re,sys
xml=open(sys.argv[1],encoding="utf-8",errors="replace").read()
for node in re.finditer(r'<node [^>]*>', xml):
    n=node.group(0)
    if re.search(r'text="%s"'%re.escape(sys.argv[2]), n):
        m=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if m: print((int(m.group(1))+int(m.group(3)))//2,(int(m.group(2))+int(m.group(4)))//2); sys.exit(0)
sys.exit(1)
PY
}
if xy=$(tapnav "Settings"); then $ADB shell input tap $xy; sleep 1; $ADB exec-out screencap -p > "$OUT/frames/phone_settings.png" 2>/dev/null; fi
$ADB shell input keyevent KEYCODE_BACK; sleep 1
check "system back returns to Home instead of leaving the app" "$ADB shell dumpsys activity activities 2>/dev/null | grep -q 'app.ft/.MainActivity'"
if xy=$(tapnav "Log"); then $ADB shell input tap $xy; sleep 1; $ADB exec-out screencap -p > "$OUT/frames/phone_log.png" 2>/dev/null; fi
$ADB shell input keyevent KEYCODE_BACK; sleep 1
check "back from Log keeps the app open" "$ADB shell dumpsys activity activities 2>/dev/null | grep -q 'app.ft/.MainActivity'"

echo "== frames (visual proof) =="
for f in "$OUT"/carlife/*.h264; do
  b=$(basename "$f" .h264); png="$OUT/frames/$b.png"; rm -f "$png"
  for n in 10 2 0; do
    ffmpeg -y -loglevel error -i "$f" -vf "select=gte(n\,$n)" -frames:v 1 -update 1 "$png" 2>/dev/null
    [ -s "$png" ] && break
  done
  echo "   $b  $( [ -s "$png" ] && echo png-ok || echo no-png )"
done
check "launcher frame decoded to PNG" "[ -s '$OUT/frames/launcher.png' ]"

echo "== crashes =="
check "no FT crash in logcat" "! grep -A3 'FATAL EXCEPTION' '$OUT/logcat.txt' | grep -q 'app.ft'"
grep -E 'FT/' "$OUT/logcat.txt" | sed -E 's/^.*FT\//FT\//' > "$OUT/ft_log.txt"

echo
echo "RESULT: $pass passed, $fail failed  (artifacts in $OUT)"
[ $fail = 0 ]
