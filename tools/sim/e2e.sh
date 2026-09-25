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
sleep 1; dump; cp "$OUT/consent_ui.xml" "$OUT/home_after_consent.xml"
check "consent reflected on Home (no Allow button left)" "grep -q 'text=\"Screen mirror\"' '$OUT/home_after_consent.xml' && ! grep -q 'text=\"Allow\"' '$OUT/home_after_consent.xml'"

echo "== start services (auto-connect, Android Auto auto-start on) =="
$ADB shell am start -n $PKG/.MainActivity --ez auto true --ez wifi true --es aaPkg com.android.settings --ez aaAuto true --es pkgMaps com.android.settings >/dev/null 2>&1
sleep 7
check "listening on the CarLife command port" "grep -q 'WIFI CMD listening on 7240' '$OUT/logcat.txt'"
check "no WiFi Direct nagging while the car is reachable on this network" "! grep -q 'discoverPeers failed' '$OUT/logcat.txt'"
check "bluetooth trigger runs to raise the car's WiFi Direct" "grep -q 'FT/CarBT' '$OUT/logcat.txt'"
check "discovery beacon keeps calling on udp 7999 (4th tick seen)" "grep -q 'discovery beacon #4 to .*udp 7999 ([1-9]' '$OUT/logcat.txt'"
check "not marked connected before any head unit dialled in" "! grep -q 'head unit connected over' '$OUT/logcat.txt'"

echo "== plain head unit (no content encryption, 1024x600) =="
python3 "$SIMDIR/carlife_hu_sim.py" --encrypt 0 --hold-init 2 --width 1024 --height 600 --fps 25 --seconds 2 --tail-seconds 1 --out "$OUT/plain" > "$OUT/plain_sim.log" 2>&1
PL=$?
check "plain head unit session passed" "[ $PL = 0 ]"
python3 -c "import json;d=json.load(open('$OUT/plain/result.json'));print('   checks:',all(d['checks'].values()),'| frames:',d.get('total_frames'),'| heartbeats before init:',d.get('heartbeats_before_init'),'| bt reply:',d.get('bt_pair_reply'))" 2>/dev/null
check "encoder followed the plain head unit size" "grep -q 'encoder started 1024x600' '$OUT/logcat.txt'"
check "content encryption reported off for the plain unit" "grep -q 'content encryption off on this head unit' '$OUT/logcat.txt'"
sleep 3

echo "== run the head unit simulator =="
python3 "$SIMDIR/carlife_hu_sim.py" --encrypt 1 --hold-init 3 --width 1280 --height 720 --fps 15 --seconds 4 \
  --touches "284,606@0;40,40@3;672,195@6;40,40@10;668,530@13" --tail-seconds 8 --out "$OUT/carlife" > "$OUT/carlife_sim.log" 2>&1
CL=$?
sleep 2
kill $LOGPID 2>/dev/null

echo "== carlife (per-channel TCP, content encryption on) =="
check "carlife sim passed" "[ $CL = 0 ]"
python3 -c "import json;d=json.load(open('$OUT/carlife/result.json'));print('   checks:',all(d['checks'].values()),'| frames:',d.get('total_frames'),'| launcher:',d.get('video_launcher'),'| heartbeats:',d.get('heartbeats'),'| before init:',d.get('heartbeats_before_init'),'| plaintext leaks after key:',d.get('plain_after_key'))" 2>/dev/null
check "head unit connected to the cmd channel" "grep -q 'WIFI CMD connected from' '$OUT/logcat.txt'"
check "Linked only when a head unit really connected (once per simulated unit)" "[ \$(grep -c 'head unit connected over WiFi' '$OUT/logcat.txt') = 2 ]"
check "feature config requested after the version match" "grep -q 'TX MD_FEATURE_CONFIG_REQUEST' '$OUT/logcat.txt'"
check "head unit asked for encryption and FT negotiated it (RSA/AES)" "grep -q 'head unit requires content encryption' '$OUT/logcat.txt' && grep -q 'AES session key sent' '$OUT/logcat.txt' && grep -q 'content encryption on' '$OUT/logcat.txt'"
check "bluetooth pair info answered with the complete schema" "python3 -c \"import json;d=json.load(open('$OUT/carlife/result.json'));assert d['checks']['bt_pair_info_complete']\" 2>/dev/null"
check "video heartbeats flowed before VIDEO_ENCODER_INIT (watchdog rule)" "python3 -c \"import json;d=json.load(open('$OUT/carlife/result.json'));assert d['checks']['heartbeat_before_init']\" 2>/dev/null"
check "encoder honours the rate the head unit asked for (15)" "grep -q 'encoder started 1280x720@15' '$OUT/logcat.txt'"
check "redraw clock follows the negotiated rate" "grep -q 'redraw every 66ms' '$OUT/logcat.txt'"
check "projected stream paced to the negotiated rate, not flooded" "python3 -c \"import json;d=json.load(open('$OUT/carlife/result.json'));f=d['video_launcher']['frames'];assert 30<=f<=110, f\" 2>/dev/null"
check "Android Auto bridge starts after connection" "grep -q 'auto-starting Android Auto after connection' '$OUT/logcat.txt' && grep -q 'looking for Android Auto on this phone' '$OUT/logcat.txt'"
check "car keeps showing the launcher while Android Auto is not projecting" "! grep -q 'android auto overlay on' '$OUT/logcat.txt'"
check "presentation shown on virtual display" "grep -q 'presentation shown' '$OUT/logcat.txt'"
check "encoder started 1280x720" "grep -q 'encoder started 1280x720' '$OUT/logcat.txt'"
check "projection started" "grep -q 'projection started' '$OUT/logcat.txt'"
check "built-in Browser screen opens from a car tile" "grep -q 'car screen: BROWSER' '$OUT/logcat.txt'"
check "tile launched an app and mirrored it to the car" "grep -q 'launched com.android.settings' '$OUT/logcat.txt'"
check "phone mirror started" "grep -qE 'mirror [0-9]+x[0-9]+ started' '$OUT/logcat.txt'"

echo "== phone ui =="
$ADB shell am start -n $PKG/.MainActivity >/dev/null 2>&1; sleep 2
$ADB exec-out screencap -p > "$OUT/frames/phone_home.png" 2>/dev/null
$ADB shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $ADB pull /sdcard/ui.xml "$OUT/home_ui.xml" >/dev/null 2>&1
layout(){ python3 - "$OUT/home_ui.xml" <<'PY'
import sys, xml.etree.ElementTree as ET
root=ET.parse(sys.argv[1]).getroot()
parent={c:p for p in root.iter() for c in p}
def b(n):
    s=n.get('bounds','')
    return tuple(int(v) for v in s.replace('][',',').strip('[]').split(',')) if s else None
scroll=[b(n) for n in root.iter() if n.get('scrollable')=='true' and b(n)]
if not scroll: sys.exit(1)
content=max(scroll,key=lambda r:(r[3]-r[1])*(r[2]-r[0]))
label=next((n for n in root.iter() if n.get('text')=='Home'),None)
if label is None: sys.exit(1)
item=parent.get(label,label)
navtop=b(item)[1]
print('   content bottom',content[3],'nav item top',navtop)
sys.exit(0 if abs(content[3]-navtop)<=12 else 1)
PY
}
check "content reaches the navigation bar (no dead strip)" "layout"
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
