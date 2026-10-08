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
$ADB shell am start -n $PKG/.MainActivity --ez off true >/dev/null 2>&1
sleep 2
$ADB shell appops set $PKG SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1
$ADB shell settings put secure enabled_accessibility_services $PKG/$PKG.FTTouchService >/dev/null 2>&1
$ADB shell settings put secure accessibility_enabled 1 >/dev/null 2>&1
$ADB shell appops set $PKG WRITE_SETTINGS allow >/dev/null 2>&1
$ADB shell am kill-all >/dev/null 2>&1
for p in com.android.settings com.android.chrome com.google.android.youtube com.google.android.apps.maps com.android.vending; do $ADB shell am force-stop $p >/dev/null 2>&1; done
$ADB shell settings put system accelerometer_rotation 0 >/dev/null 2>&1
$ADB shell settings put system user_rotation 0 >/dev/null 2>&1

echo "== test media on the phone =="
MEDIA="$OUT/media"; mkdir -p "$MEDIA"
if [ ! -s "$MEDIA/test_drive_clip.mp4" ]; then
  ffmpeg -y -loglevel error -f lavfi -i "mandelbrot=s=600x600" -frames:v 1 "$MEDIA/cover1.jpg"
  ffmpeg -y -loglevel error -f lavfi -i "gradients=s=600x600:c0=0xff8ac0:c1=0x26102e:x0=0:y0=0:x1=600:y1=600" -frames:v 1 "$MEDIA/cover2.jpg"
  ffmpeg -y -loglevel error -f lavfi -i "sine=frequency=440:duration=45:sample_rate=44100" -i "$MEDIA/cover1.jpg" -map 0:a -map 1:v -c:a libmp3lame -b:a 192k -c:v mjpeg -id3v2_version 3 -metadata title="Alpha Tone" -metadata artist="FT Band" -metadata album="Road Tests" -disposition:v attached_pic "$MEDIA/alpha_tone.mp3"
  ffmpeg -y -loglevel error -f lavfi -i "sine=frequency=660:duration=45:sample_rate=48000" -i "$MEDIA/cover2.jpg" -map 0:a -map 1:v -c:a libmp3lame -b:a 192k -c:v mjpeg -id3v2_version 3 -metadata title="Bravo Tone" -metadata artist="The Testers" -metadata album="Night Drive" -disposition:v attached_pic "$MEDIA/bravo_tone.mp3"
  ffmpeg -y -loglevel error -f lavfi -i "testsrc2=s=1280x720:r=30:d=14" -f lavfi -i "sine=frequency=880:duration=14" -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest -metadata title="Test Drive Clip" "$MEDIA/test_drive_clip.mp4"
fi
$ADB push "$MEDIA/alpha_tone.mp3" "$MEDIA/bravo_tone.mp3" /sdcard/Music/ >/dev/null 2>&1
$ADB push "$MEDIA/test_drive_clip.mp4" /sdcard/Movies/ >/dev/null 2>&1
for f in /sdcard/Music/alpha_tone.mp3 /sdcard/Music/bravo_tone.mp3 /sdcard/Movies/test_drive_clip.mp4; do
  $ADB shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$f" >/dev/null 2>&1
done
sleep 3
check "test songs are in the phone's library with their tags" "$ADB shell content query --uri content://media/external/audio/media --projection title:artist 2>/dev/null | grep -q 'title=Alpha Tone, artist=FT Band'"
for p in 5277 7240 8240 9240 9241 9242 9340; do $ADB forward tcp:$p tcp:$p >/dev/null; done

$ADB logcat -c
$ADB logcat -v time > "$OUT/logcat.txt" 2>&1 &
LOGPID=$!

echo "== screen-mirror consent =="
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
grant_mirror(){
  local before
  before=$(grep -c 'screen mirror permitted' "$OUT/logcat.txt" 2>/dev/null); before=${before:-0}
  $ADB shell am start -n $PKG/.MainActivity --ez mirror true --es pkgMaps com.android.settings >/dev/null 2>&1
  sleep 3
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
    local now
    now=$(grep -c 'screen mirror permitted' "$OUT/logcat.txt" 2>/dev/null); now=${now:-0}
    [ "$now" -gt "$before" ] && break
    [ $tapped = 0 ] && break
  done
  sleep 1
}
grant_mirror
check "mirror consent granted" "grep -q 'screen mirror permitted' '$OUT/logcat.txt'"
sleep 1; $ADB shell input swipe 700 2200 700 900 250 >/dev/null 2>&1; sleep 2; dump; cp "$OUT/consent_ui.xml" "$OUT/home_after_consent.xml"
check "Home shows the connection steps and no setup left to do" "grep -q 'text=\"On the car screen\"' '$OUT/home_after_consent.xml' && ! grep -q 'text=\"Allow\"' '$OUT/home_after_consent.xml'"
check "Home no longer asks for screen sharing up front" "! grep -q 'text=\"Screen mirror\"' '$OUT/home_after_consent.xml'"

echo "== start services (auto-connect, Android Auto auto-start on) =="
$ADB shell am start -n $PKG/.MainActivity --ei linkMode 0 --ei pictureSize 0 --ei aaCorner 0 --ez auto true --ez wifi true --es aaPkg com.android.settings --ez aaAuto true --es pkgMaps com.android.settings --ez carSongInfo true --ei guidance 2 \
  --es carTiles music,videos,youtube,maps,browser,apps --es carBackground deep --ei carAccent -10492992 --ez carClock24 true >/dev/null 2>&1
sleep 7
check "listening on the CarLife command port" "grep -q 'WIFI CMD listening on 7240' '$OUT/logcat.txt'"
check "no WiFi Direct nagging while the car is reachable on this network" "! grep -q 'discoverPeers failed' '$OUT/logcat.txt'"
check "hotspot route leaves bluetooth alone" "! grep -q 'FT/CarBT' '$OUT/logcat.txt'"
check "discovery beacon keeps calling on udp 7999 (4th tick seen)" "grep -q 'discovery beacon #4 to .*udp 7999 ([1-9]' '$OUT/logcat.txt'"
check "not marked connected before any head unit dialled in" "! grep -q 'head unit connected over' '$OUT/logcat.txt'"
udpcall(){ python3 - <<'PY2'
import socket
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.bind(("127.0.0.1", 0))
s.settimeout(3)
s.sendto(b"carlifehost probe", ("127.0.0.1", 18999))
try:
    print(s.recvfrom(1024)[0].decode(errors="replace"))
except socket.timeout:
    print("no answer")
PY2
}
$ADB emu redir add udp:18999:8999 >/dev/null 2>&1
check "a car looking for FT on udp 8999 gets the ready answer" "udpcall | grep -q '\"status\":\"ready\"'"

echo "== plain head unit (no content encryption, 1024x600) =="
python3 "$SIMDIR/carlife_hu_sim.py" --encrypt 0 --hold-init 2 --width 1024 --height 600 --fps 25 --seconds 2 --tail-seconds 1 --out "$OUT/plain" > "$OUT/plain_sim.log" 2>&1
PL=$?
check "plain head unit session passed" "[ $PL = 0 ]"
python3 -c "import json;d=json.load(open('$OUT/plain/result.json'));print('   checks:',all(d['checks'].values()),'| frames:',d.get('total_frames'),'| heartbeats before init:',d.get('heartbeats_before_init'),'| bt reply:',d.get('bt_pair_reply'))" 2>/dev/null
check "encoder followed Baidu's size for the plain head unit (1024x600 sends 1024x576)" "grep -q 'encoder started 1024x576' '$OUT/logcat.txt' && grep -q '1024x600 drawn into 1024x576' '$OUT/logcat.txt'"
check "content encryption reported off for the plain unit" "grep -q 'content encryption off on this head unit' '$OUT/logcat.txt'"
sleep 3

grant_mirror
$ADB shell am start -n $PKG/.MainActivity --ez auto true --ez wifi true >/dev/null 2>&1
sleep 3

echo "== run the head unit simulator =="
python3 "$SIMDIR/carlife_hu_sim.py" --encrypt 1 --hold-init 3 --width 1280 --height 720 --fps 15 --seconds 4 \
  --touches "282,621@0;40,40@2;670,192@4;450,171@6;765,138@8;key:87@11;40,40@14;140,219@15;904,192@16;334,224@18;fps:5@19.5;fps:15@23.5;40,40@24;904,528@26;40,40@34;670,528@36;69,651@40;156,651@42;417,651@44;40,40@54" \
  --tail-seconds 6 --out "$OUT/carlife" > "$OUT/carlife_sim.log" 2>&1
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
sim_check(){ python3 -c "import json;d=json.load(open('$OUT/$1/result.json'));assert d['checks']['$2'], d['checks']" 2>/dev/null; }
check "handshake in Baidu's order: foreground, device info and module list right after the match" "sim_check carlife foreground_after_match && sim_check carlife md_info_before_hu_info && sim_check carlife module_list_like_baidu && sim_check carlife md_info_sent_once"
check "feature request follows the car's statistics, like Baidu" "sim_check carlife feature_config_after_statistics"
check "the car's data subscription is answered (song info only)" "sim_check carlife subscribe_answered"
check "nothing is sent on the video channel before the car starts it (Baidu)" "sim_check carlife no_video_before_start"
check "INIT_DONE tells the car the real stream size" "sim_check carlife init_done_stream_size"
check "the first picture carries the codec header and the header is sent once" "sim_check carlife first_frame_carries_the_header && sim_check carlife header_sent_once"
check "video timestamps are in seconds like Baidu's" "sim_check carlife video_clock_in_seconds"
check "a request to come forward is left alone, like Baidu" "sim_check carlife go_to_foreground_left_alone"
check "encoder honours the rate the head unit asked for (15)" "grep -q 'encoder started 1280x720@15' '$OUT/logcat.txt'"
check "redraw clock follows the negotiated rate" "grep -qE 'redraw every 66ms|at most 15 frames a second' '$OUT/logcat.txt'"
check "projected stream paced to the negotiated rate, not flooded" "python3 -c \"import json;d=json.load(open('$OUT/carlife/result.json'));f=d['video_launcher']['frames'];assert 20<=f<=110, f\" 2>/dev/null"
check "Android Auto bridge starts after connection" "grep -q 'auto-starting Android Auto after connection' '$OUT/logcat.txt' && grep -q 'bridging this phone' '$OUT/logcat.txt'"
check "Android Auto is pointed at FT's own head unit port" "grep -qE 'asked Android Auto to project onto FT at 127.0.0.1:[0-9]+' '$OUT/logcat.txt'"
check "Android Auto is told to draw at the car's size" "grep -q \"Android Auto will draw at the car's own 1280x720\" '$OUT/logcat.txt'"
check "Android Auto stops when the car link drops" "grep -q 'Android Auto stopped because' '$OUT/logcat.txt'"
outruns(){ python3 - "$OUT/logcat.txt" <<'PY4'
import re, sys
bad = []
for line in open(sys.argv[1], errors="replace"):
    m = re.search(r"to the car (\d+) of 192000", line)
    if not m or int(m.group(1)) < 198000:
        continue
    phone = re.search(r"\| phone (\d+)", line)
    if phone and int(phone.group(1)) > 200000:
        print("   ignored, the phone's own capture ran faster than real time:", line.strip()[-120:])
        continue
    bad.append(line.strip())
for b in bad:
    print("  ", b)
sys.exit(1 if bad else 0)
PY4
}
check "car audio never outruns the car" "outruns"
check "car keeps showing the launcher while Android Auto is not projecting" "! grep -q 'android auto overlay on' '$OUT/logcat.txt'"
check "presentation shown on virtual display" "grep -q 'presentation shown' '$OUT/logcat.txt'"
check "encoder started 1280x720" "grep -q 'encoder started 1280x720' '$OUT/logcat.txt'"
check "projection started" "grep -q 'projection started' '$OUT/logcat.txt'"
check "built-in Browser screen opens from a car tile" "grep -q 'car screen: BROWSER' '$OUT/logcat.txt'"
check "tile launched an app and mirrored it to the car" "grep -q 'launched com.android.settings' '$OUT/logcat.txt'"
check "phone mirror started" "grep -qE 'mirror [0-9]+x[0-9]+ started' '$OUT/logcat.txt'"

echo "== music, videos and phone keys on the car =="
song(){ python3 - "$OUT/carlife/result.json" "$1" <<'PY3'
import json, sys, wave
import numpy as np
d = json.load(open(sys.argv[1]))
want = sys.argv[2]
if want == "info":
    s = [x for x in d.get("songs", []) if x["title"] == "Alpha Tone"]
    assert s, d.get("songs")
    x = s[0]
    print("   car was told:", x["title"], "/", x["artist"], "/", x["album"], "| cover", x["art_bytes"], "bytes")
    assert x["artist"] == "FT Band" and x["album"] == "Road Tests" and x["art_is_jpeg"] and 1000 < x["art_bytes"] <= 30000, x
    assert any(y["title"] == "Bravo Tone" for y in d["songs"]), d["songs"]
elif want == "fields":
    bad = [s for s in d.get("songs", []) if s.get("fields") != list(range(1, 10))]
    print("   song messages:", len(d.get("songs", [])), "| missing fields in:", [s["title"] for s in bad])
    assert d.get("songs") and not bad, bad
elif want == "position":
    p = d.get("positions", [])
    print("   song positions sent:", len(p))
    assert len(p) >= 4, p
    assert any(b[1] > a[1] for a, b in zip(p, p[1:])), p
elif want == "tones":
    w = wave.open(sys.argv[1].replace("result.json", "media.wav"))
    pcm = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).reshape(-1, 2)[:, 0].astype(np.float64)
    seen = []
    for i in range(0, len(pcm) - 9600, 9600):
        blk = pcm[i:i + 9600]
        if np.abs(blk).max() < 500:
            continue
        f = np.fft.rfftfreq(len(blk), 1 / 48000.0)[np.argmax(np.abs(np.fft.rfft(blk * np.hanning(len(blk)))))]
        tone = min((440, 660, 880), key=lambda t: abs(t - f))
        if abs(tone - f) < 15 and (not seen or seen[-1] != tone):
            seen.append(tone)
    print("   tones heard by the car, in order:", seen)
    assert seen[:3] == [440, 660, 880], seen
elif want == "landscape":
    import glob, os, subprocess, tempfile
    from PIL import Image
    taps = d.get("tap_clock", [])
    turn = [t for x, y, t in taps if (x, y) == (417, 651)]
    after = [t for x, y, t in taps if turn and t > turn[0]]
    clock = d.get("frame_clock", [])
    tmp = tempfile.mkdtemp()
    subprocess.run(["ffmpeg", "-y", "-loglevel", "quiet", "-i", sys.argv[1].replace("result.json", "all.h264"), "-fps_mode", "passthrough", tmp + "/f%05d.png"])
    frames = sorted(glob.glob(tmp + "/*.png"))
    off = len(clock) - len(frames)
    wide = 0
    for i, f in enumerate(frames):
        t = clock[i + off] if 0 <= i + off < len(clock) else 0
        if not turn or t < turn[0] or (after and t > after[0]):
            continue
        im = Image.open(f).convert("L")
        row = [im.getpixel((x, 360)) for x in range(0, im.size[0], 8)]
        bright = [i * 8 for i, v in enumerate(row) if v > 200]
        if bright and min(bright) < 120 and max(bright) > 1160:
            wide += 1
    print("   frames showing the turned phone across the car screen:", wide)
    assert wide >= 1
elif want == "rate":
    asks = d.get("rate_clock", [])
    clock = d.get("frame_clock", [])
    assert len(asks) >= 2, asks
    low_from, low_to = asks[0][1] + 1.0, asks[1][1]
    low = [t for t in clock if low_from <= t < low_to]
    back = [t for t in clock if asks[1][1] + 1.0 <= t < asks[1][1] + 4.0]
    low_fps = len(low) / (low_to - low_from)
    back_fps = len(back) / 3.0
    print("   car asked for %d fps while a video played: got %.1f fps; asked for %d again: got %.1f fps" % (asks[0][0], low_fps, asks[1][0], back_fps))
    assert low_fps >= asks[1][0] * 0.7, low_fps
    assert back_fps >= asks[1][0] * 0.7, back_fps
elif want == "pace":
    m = d["audio"]["media"]
    taps = d.get("tap_clock", [])
    video = [i for i, (x, y, t) in enumerate(taps) if (x, y) == (334, 224)]
    start = d["songs"][0]["t"]
    end = taps[video[0]][2] - d["audio_t0"]
    window = [q for t, q in m["delay_track"] if start <= t <= end]
    steady = window[1:]
    print("   car queue while FT's player plays: %.2f to %.2f s over %d samples" % (min(window), max(window), len(window)))
    assert len(window) >= 5 and max(steady) - min(steady) < 0.08 and max(window) < 0.6, window
PY3
}
check "the Music screen opens from its car tile" "grep -q 'car screen: MUSIC' '$OUT/logcat.txt'"
check "tapping a song plays it to the car" "grep -q \"playing 'Alpha Tone' by FT Band to the car\" '$OUT/logcat.txt'"
check "the steering wheel's next key moves FT's player on" "grep -q \"steering wheel next track for FT's player\" '$OUT/logcat.txt' && grep -q \"playing 'Bravo Tone'\" '$OUT/logcat.txt'"
check "the car is told the song, artist, album and cover" "song info"
check "the car gets the song position as it plays" "song position"
check "every song message carries all nine fields (a missing cover crashed the Corolla)" "song fields"
check "All songs goes back to the list from the now playing screen" "grep -q 'music: song list' '$OUT/logcat.txt'"
check "the Car screen button asks the car for its own screen and FT stays connected" "grep -q 'asked the car to show its own screen' '$OUT/logcat.txt' && python3 -c \"import json;d=json.load(open('$OUT/carlife/result.json'));assert d['go_to_desktop']>=1\""
check "the car hears each song and the video, in order (440, 660, 880 Hz)" "song tones"
check "FT's player never builds up delay in the car" "song pace"
check "phone sound is held back while FT's player plays" "grep -q 'phone sound held back' '$OUT/logcat.txt'"
check "the Videos screen plays a video to the car" "grep -q 'car screen: VIDEOS' '$OUT/logcat.txt' && grep -q \"playing 'Test Drive Clip' to the car\" '$OUT/logcat.txt'"
check "leaving Videos stops the video" "grep -qE 'FT/Player.*: stopped' '$OUT/logcat.txt'"
check "a request below 15 fps is answered but FT keeps its pace, like Baidu" "song rate && grep -q 'head unit asked for 5 fps, FT keeps' '$OUT/logcat.txt'"
check "frames to the car pass through the frame gate" "grep -q 'frames to the car follow the rate the head unit asks for' '$OUT/logcat.txt'"
check "the phone keys open from their handle on the car" "grep -q 'phone keys shown' '$OUT/logcat.txt'"
check "the phone's back key works from the car" "grep -q 'phone back' '$OUT/logcat.txt'"
check "rotate turns the phone to landscape" "grep -q 'phone turned to landscape' '$OUT/logcat.txt'"
check "the car shows the turned phone across its whole width" "song landscape"
check "the mirror follows the phone into landscape" "grep -E 'phone turned, mirror is now [0-9]+x[0-9]+' '$OUT/logcat.txt' | tail -1 | python3 -c \"import re,sys;w,h=map(int,re.search(r'now (\\d+)x(\\d+)',sys.stdin.read()).groups());assert w>h\""
check "the FT button still works on top of the mirror" "grep -q 'mirror stopped' '$OUT/logcat.txt'"
check "the phone's rotation is put back when the car goes" "grep -q 'phone rotation set back the way it was' '$OUT/logcat.txt' && [ \"\$($ADB shell settings get system user_rotation | tr -d '\\r')\" = 0 ]"

echo "== Corolla-shaped car (1920x720, protocol 1.0, no frame rate asked) =="
$ADB logcat -c
$ADB logcat -v time > "$OUT/corolla_logcat.txt" 2>&1 &
COROLLALOG=$!
$ADB shell am start -n $PKG/.MainActivity --ei linkMode 0 --ei pictureSize 1 --ez auto true --ez wifi true --ez aaAuto false >/dev/null 2>&1
sleep 3
python3 "$SIMDIR/carlife_hu_sim.py" --encrypt 0 --hold-init 1 --width 1920 --height 720 --fps 0 --seconds 3 --stream own \
  --touches "640,360@1;pause@3;start@7;points:320,180@10;click:960,540@11;pause@12;start@12.5" --tail-seconds 4 --out "$OUT/corolla" > "$OUT/corolla_sim.log" 2>&1
COR=$?
sleep 1
kill $COROLLALOG 2>/dev/null
corolla(){ python3 - "$OUT/corolla/result.json" "$1" <<'PY7'
import json, sys
d = json.load(open(sys.argv[1]))
want = sys.argv[2]
if want == "init":
    print("   INIT_DONE:", d.get("init_done"))
    assert d.get("init_done") == [1920, 720, 0], d.get("init_done")
elif want == "pause":
    p = d.get("pauses", [])
    print("   pauses:", p)
    assert p and p[0]["frames_while_paused"] == 0 and p[0]["heartbeats_while_paused"] >= 3, p
    assert p[0]["first_frame_after"] is not None and p[0]["first_frame_after"] <= 5.0, p
elif want == "quick":
    p = d.get("pauses", [])
    assert len(p) >= 2 and p[1]["first_frame_after"] is not None and p[1]["first_frame_after"] <= 5.0, p
PY7
}
check "Corolla-shaped car session passed" "[ $COR = 0 ]"
check "the Corolla gets its own 1920x720 and INIT_DONE says so" "corolla init"
check "on Hotspot FT sends the Corolla its full 1920x720 at 3 Mbps and 30 frames a second from the start" "grep -q 'sending 1920x720 at 30 fps, 3000 kbps' '$OUT/corolla_logcat.txt' && grep -q 'encoder started 1920x720@30' '$OUT/corolla_logcat.txt'"
check "on Hotspot nothing waits 8 seconds for the car to ask" "! grep -q 'the car has not asked for a frame rate' '$OUT/corolla_logcat.txt'"
check "a touch on the car's own size lands on the same spot on FT's screen (640,360)" "grep -q 'car touched 640,360 of its picture, 640,360 on FT' '$OUT/corolla_logcat.txt'"
check "touch down and up messages and single clicks reach FT too" "grep -q 'car touched 320,180 of its picture, 320,180 on FT' '$OUT/corolla_logcat.txt' && grep -q 'car touched 960,540 of its picture, 960,540 on FT' '$OUT/corolla_logcat.txt'"
check "while the car shows its own screen only heartbeats go out, then the next full picture brings it back" "corolla pause"
check "a quick trip to the car's own screen comes back too" "corolla quick"
check "coming back needs no forced picture and no second header" "sim_check corolla header_sent_once && ! grep -q 'key picture of' '$OUT/corolla_logcat.txt'"

echo "== FT button in another corner, over a mirrored app =="
$ADB logcat -c
$ADB logcat -v time > "$OUT/corner_logcat.txt" 2>&1 &
CORNERLOG=$!
grant_mirror
$ADB shell am start -n $PKG/.MainActivity --ei aaCorner 3 --ez aaAuto false >/dev/null 2>&1
sleep 3
python3 "$SIMDIR/carlife_hu_sim.py" --encrypt 0 --hold-init 1 --width 1280 --height 720 --fps 15 --seconds 2 \
  --touches "670,528@1;1208,666@6" --tail-seconds 3 --out "$OUT/corner" > "$OUT/corner_sim.log" 2>&1
sleep 1
kill $CORNERLOG 2>/dev/null
check "a bottom-right FT button is pressable over a mirrored app" "grep -q 'launched com.android.settings' '$OUT/corner_logcat.txt' && grep -q 'mirror stopped' '$OUT/corner_logcat.txt'"
$ADB shell am start -n $PKG/.MainActivity --ei aaCorner 0 >/dev/null 2>&1

echo "== a car that crashes when it is told the song =="
$ADB logcat -c
$ADB logcat -v time > "$OUT/crash_logcat.txt" 2>&1 &
CRASHLOG=$!
sleep 2
python3 "$SIMDIR/carlife_hu_sim.py" --encrypt 0 --hold-init 1 --width 1280 --height 720 --fps 15 --seconds 2 --crash-on-song \
  --touches "670,192@1;450,171@3" --tail-seconds 3 --out "$OUT/crash1" > "$OUT/crash1.log" 2>&1
sleep 5
python3 "$SIMDIR/carlife_hu_sim.py" --encrypt 0 --hold-init 1 --width 1280 --height 720 --fps 15 --seconds 6 --tail-seconds 1 --out "$OUT/crash2" > "$OUT/crash2.log" 2>&1
CR2=$?
sleep 1
kill $CRASHLOG 2>/dev/null
check "after the car drops right after a song message, FT stops sending song info" "grep -q 'stops sending song info' '$OUT/crash_logcat.txt'"
check "the next connection stays up and gets no song message" "[ $CR2 = 0 ] && python3 -c \"import json;d=json.load(open('$OUT/crash2/result.json'));assert not d.get('songs'), d.get('songs')\""

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

echo "== sound: music with a voice prompt on top =="
$ADB forward tcp:5288 tcp:5288 >/dev/null
$ADB shell am force-stop $PKG
$ADB logcat -c
$ADB logcat -v time > "$OUT/sound_logcat.txt" 2>&1 &
SNDLOG=$!
$ADB shell am start -n $PKG/.MainActivity --ei linkMode 0 --ez auto true --ez aa true --ez aaAuto false >/dev/null 2>&1
sleep 5
python3 "$SIMDIR/carlife_hu_sim.py" --encrypt 0 --hold-init 1 --width 1280 --height 720 --fps 15 --seconds 2 --listen-seconds 16 --out "$OUT/sound_car" > "$OUT/sound_car.log" 2>&1 &
SNDCAR=$!
sleep 6
python3 "$SIMDIR/aa_phone_sim.py" --port 5288 --hu-cert "$ROOT/app/src/main/assets/aa_hu_cert.pem" --srv-cert "$CERTDIR/sim_srv.pem" --srv-key "$CERTDIR/sim_srv.key" --seconds 1 --wait-touch 0 --audio-seconds 8 --speech-at 2 --speech-seconds 2.5 --out "$OUT/sound_phone" > "$OUT/sound_phone.log" 2>&1
wait $SNDCAR
kill $SNDLOG 2>/dev/null
sound(){ python3 - "$OUT/${2:-sound_car}/result.json" "$1" <<'PY2'
import json, sys
a = json.load(open(sys.argv[1]))["audio"]
m, t = a["media"], a.get("tts")
tr = m["delay_track"]
def avg(lo, hi):
    v = [d for x, d in tr if lo <= x < hi]
    return sum(v) / len(v) if v else 0.0
want = sys.argv[2]
if want == "music":
    assert abs(m["audio_seconds"] - 8.0) < 0.05, m["audio_seconds"]
elif want == "voice":
    names = [e["msg"] for e in a["events"]]
    assert "TTS_INIT" in names and "TTS_END" in names, names
    assert t and abs(t["audio_seconds"] - 2.5) < 0.05, t
elif want == "undipped":
    import wave
    import numpy as np
    w = wave.open(sys.argv[1].replace("result.json", "media.wav"))
    pcm = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).reshape(-1, 2)[:, 0].astype(np.float64)
    n = 2400
    win = np.hanning(n)
    tone = np.array([np.abs(np.fft.rfft(pcm[i:i + n] * win))[22] for i in range(0, len(pcm) - n, n)])
    full = np.median(tone)
    low = np.median(np.sort(tone)[:40])
    print("   music under the directions at %.2f of its level (Android Auto's own dip in the sim is 0.30)" % (low / full))
    assert low / full >= 0.2, low / full
elif want == "mixed":
    import wave
    import numpy as np
    names = [e["msg"] for e in a["events"]]
    assert "TTS_INIT" not in names, names
    w = wave.open(sys.argv[1].replace("result.json", "media.wav"))
    pcm = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).reshape(-1, 2)[:, 0].astype(np.float64)
    n = 2400
    win = np.hanning(n)
    mags = []
    for i in range(0, len(pcm) - n, n):
        f = np.abs(np.fft.rfft(pcm[i:i + n] * win))
        mags.append((f[22], f[50]))
    tone = np.array([m[0] for m in mags])
    voice = np.array([m[1] for m in mags])
    on = np.where(voice > 0.3 * voice.max())[0]
    first, last = on[0], on[-1]
    secs = (last - first + 1) * 0.05
    before = np.median(tone[max(0, first - 30):max(1, first - 6)])
    during = np.median(tone[first + 3:last - 2])
    after = np.median(tone[last + 12:last + 30])
    print("   directions heard inside the music for %.2fs from %.2fs; music %.0f before, %.0f under them, %.0f after" % (secs, first * 0.05, before, during, after))
    assert 2.3 <= secs <= 2.75, secs
    assert during <= 0.5 * before, (during, before)
    assert after >= 0.85 * before, (after, before)
elif want == "stream":
    ev = [e for e in a["events"] if e["ch"] == "media"]
    names = [e["msg"] for e in ev]
    print("   media channel:", names[:6], "...")
    assert names[:3] == ["MEDIA_INIT", "MEDIA_STOP", "MEDIA_INIT"], names
    assert ev[0]["body"].get("1") == 48000 or ev[0]["body"].get(1) == 48000, ev[0]
    mods = json.load(open(sys.argv[1])).get("module_events", [])
    on = [t for t, cnt, items in mods if [3, 1] in items]
    assert on and on[0] <= a["first_media_at"] + 0.05, (on, a["first_media_at"])
elif want == "stopped":
    names = [e["msg"] for e in a["events"] if e["ch"] == "media"]
    mods = json.load(open(sys.argv[1])).get("module_events", [])
    off = [t for t, cnt, items in mods if [3, 0] in items and cnt == 1]
    print("   media channel ends with:", names[-2:], "| music module off at:", off)
    assert "MEDIA_PAUSE" in names and off, (names, off)
elif want == "navi":
    mods = json.load(open(sys.argv[1])).get("module_events", [])
    tts = [e for e in a["events"] if e["ch"] == "tts"]
    start = [e["t"] for e in tts if e["msg"] == "TTS_INIT"]
    end = [e["t"] for e in tts if e["msg"] == "TTS_END"]
    up = [t for t, cnt, items in mods if [2, 1] in items]
    down = [t for t, cnt, items in mods if [2, 0] in items and cnt == 1]
    print("   voice from %s to %s, navigation module up at %s, down at %s" % (start, end, up, down))
    assert start and end and up and down, (start, end, up, down)
    assert abs(up[0] - start[0]) < 0.5 and down[-1] >= end[-1] - 0.05, (up, down)
elif want == "delay":
    grow = avg(5.0, 8.0) - avg(0.5, 2.0)
    print("   car delay before %.3fs, after %.3fs" % (avg(0.5, 2.0), avg(5.0, 8.0)))
    assert grow < 0.08, grow
PY2
}
check "the car gets exactly the music that played, nothing extra" "sound music"
check "like Baidu (default), directions go to the car's voice channel, start to end" "sound voice"
check "like Baidu (default), FT adds no dip of its own to the music under directions" "sound undipped"
check "music starts the Baidu way: INIT, STOP, INIT and the music module on before any sound" "sound stream"
check "music stopping pauses the stream and switches the music module off" "sound stopped"
check "the navigation module is raised around the spoken directions" "sound navi"
measured(){ python3 - "$OUT/sound_logcat.txt" <<'PY6'
import re, sys
vals = [float(m.group(1)) for m in re.finditer(r"music coming in under the directions: ([+-][0-9.]+) dB", open(sys.argv[1], errors="replace").read())]
print("   measured:", vals)
assert vals and all(-12.0 < v < -9.0 for v in vals), vals
PY6
}
check "FT logs how far Android Auto lowered its own music under the prompt" "measured"
check "the car's sound does not fall behind after a prompt" "sound delay"

echo "== sound: directions mixed in step (chosen in settings) =="
$ADB shell am start -n $PKG/.MainActivity --ei linkMode 0 --ez auto true --ez aa true --ez aaAuto false --ei guidance 0 >/dev/null 2>&1
sleep 3
python3 "$SIMDIR/carlife_hu_sim.py" --encrypt 0 --hold-init 1 --width 1280 --height 720 --fps 15 --seconds 2 --listen-seconds 16 --out "$OUT/untouched_car" > "$OUT/untouched_car.log" 2>&1 &
UNTCAR=$!
sleep 6
python3 "$SIMDIR/aa_phone_sim.py" --port 5288 --hu-cert "$ROOT/app/src/main/assets/aa_hu_cert.pem" --srv-cert "$CERTDIR/sim_srv.pem" --srv-key "$CERTDIR/sim_srv.key" --seconds 1 --wait-touch 0 --audio-seconds 8 --speech-at 2 --speech-seconds 2.5 --out "$OUT/untouched_phone" > "$OUT/untouched_phone.log" 2>&1
wait $UNTCAR
$ADB shell am start -n $PKG/.MainActivity --ei guidance 2 >/dev/null 2>&1
check "in step: the car gets exactly the music's own length" "sound music untouched_car"
check "in step: directions over music are mixed in and the music dips only while they play" "sound mixed untouched_car"

echo "== sound: a voice prompt with no music =="
$ADB shell am start -n $PKG/.MainActivity --ei linkMode 0 --ez auto true --ez aa true --ez aaAuto false >/dev/null 2>&1
sleep 3
$ADB logcat -c
$ADB logcat -v time > "$OUT/voice_logcat.txt" 2>&1 &
VOICELOG=$!
python3 "$SIMDIR/carlife_hu_sim.py" --encrypt 0 --hold-init 1 --width 1280 --height 720 --fps 15 --seconds 2 --listen-seconds 12 --out "$OUT/voice_car" > "$OUT/voice_car.log" 2>&1 &
VOICECAR=$!
sleep 6
python3 "$SIMDIR/aa_phone_sim.py" --port 5288 --hu-cert "$ROOT/app/src/main/assets/aa_hu_cert.pem" --srv-cert "$CERTDIR/sim_srv.pem" --srv-key "$CERTDIR/sim_srv.key" --seconds 1 --wait-touch 0 --audio-seconds 6 --music 0 --speech-at 2 --speech-seconds 2.5 --out "$OUT/voice_phone" > "$OUT/voice_phone.log" 2>&1
wait $VOICECAR
kill $VOICELOG 2>/dev/null
voice_alone(){ python3 - "$OUT/voice_car/result.json" <<'PY5'
import json, sys
a = json.load(open(sys.argv[1]))["audio"]
names = [e["msg"] for e in a["events"]]
t = a.get("tts")
print("   voice channel:", names.count("TTS_INIT"), "start,", names.count("TTS_END"), "end,", t and t["audio_seconds"], "s")
assert "TTS_INIT" in names and "TTS_END" in names, names
assert t and abs(t["audio_seconds"] - 2.5) < 0.05, t
PY5
}
check "with no music, directions go to the car's voice channel, start to end" "voice_alone"

echo "== sharing without asking (screen sharing allowed ahead with appops) =="
$ADB shell am force-stop $PKG
$ADB shell appops set $PKG PROJECT_MEDIA allow >/dev/null 2>&1
$ADB logcat -c
$ADB logcat -v time > "$OUT/quiet_logcat.txt" 2>&1 &
QUIETLOG=$!
$ADB shell am start -n $PKG/.MainActivity --ei linkMode 0 --ez auto true --ez wifi true --ez aaAuto false --es pkgMaps com.android.settings >/dev/null 2>&1
sleep 5
$ADB shell input keyevent KEYCODE_HOME >/dev/null 2>&1
sleep 1
python3 "$SIMDIR/carlife_hu_sim.py" --encrypt 0 --hold-init 1 --width 1280 --height 720 --fps 15 --seconds 2 --touches "670,528@1" --tail-seconds 6 --out "$OUT/quiet" > "$OUT/quiet_sim.log" 2>&1
sleep 1
kill $QUIETLOG 2>/dev/null
$ADB shell appops set $PKG PROJECT_MEDIA default >/dev/null 2>&1
check "with sharing allowed ahead, a car tile mirrors an app and nothing is asked on the phone" "grep -q 'screen sharing allowed without asking' '$OUT/quiet_logcat.txt' && grep -q 'launched com.android.settings' '$OUT/quiet_logcat.txt' && ! grep -q 'asking on the phone for screen sharing' '$OUT/quiet_logcat.txt'"

echo "== phone screen off and on during a drive =="
$ADB logcat -c
$ADB logcat -v time > "$OUT/screen_logcat.txt" 2>&1 &
SCREENLOG=$!
$ADB shell am start -n $PKG/.MainActivity --ei linkMode 0 --ez auto true --ez wifi true --ez aaAuto false >/dev/null 2>&1
sleep 3
( sleep 5; $ADB shell input keyevent KEYCODE_SLEEP; sleep 2; $ADB shell input keyevent KEYCODE_WAKEUP ) &
python3 "$SIMDIR/carlife_hu_sim.py" --encrypt 0 --hold-init 1 --width 1280 --height 720 --fps 15 --seconds 10 --tail-seconds 1 --out "$OUT/screen" > "$OUT/screen_sim.log" 2>&1
SCR=$?
sleep 1
kill $SCREENLOG 2>/dev/null
$ADB shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
check "the phone screen going off and on mid-drive keeps the car connected" "[ $SCR = 0 ] && [ \$(grep -c 'TX SCREEN_ON' '$OUT/screen_logcat.txt') -ge 2 ] && ! grep -q 'NetworkOnMainThreadException' '$OUT/screen_logcat.txt'"

BUMBLE_PY="${BUMBLE_PY:-}"
PHONE_BT="${PHONE_BT:-$($ADB shell settings get secure bluetooth_address 2>/dev/null | tr -d '\r')}"
if [ -n "$BUMBLE_PY" ] && [ -x "$BUMBLE_PY" ] && [ -n "$PHONE_BT" ] && [ "$PHONE_BT" != "null" ]; then
  echo "== bluetooth car (WiFi + BL) =="
  $ADB logcat -c
  $ADB logcat -v time > "$OUT/bt_logcat.txt" 2>&1 &
  BTLOG=$!
  $ADB shell am start -n $PKG/.MainActivity --ei linkMode 1 --ez auto true >/dev/null 2>&1
  sleep 4
  "$BUMBLE_PY" "$SIMDIR/carlife_bt_car.py" --phone-address "$PHONE_BT" --seconds 6 --out "$OUT/bt1" > "$OUT/bt1.log" 2>&1
  "$BUMBLE_PY" "$SIMDIR/carlife_bt_car.py" --phone-address "$PHONE_BT" --seconds 4 --out "$OUT/bt2" > "$OUT/bt2.log" 2>&1
  $ADB shell "echo hello | nc -w 2 \$(ip -4 -o addr show | grep -v ' lo ' | head -1 | sed -E 's/.*inet ([0-9.]+).*/\\1/') ${CMD_PORT:-7240}" >/dev/null 2>&1
  sleep 2
  check "the car's first word from FT is the wireless info request" "python3 -c \"import json;d=json.load(open('$OUT/bt1/car_bt.json'));assert d['first_from_phone']=='MD_WIRELESS_INFO_REQUEST'\" 2>/dev/null"
  check "FT asks for the car's WiFi Direct name after the wireless info" "grep -q 'MD_TARGET_INFO_REQUEST' '$OUT/bt1.log'"
  check "FT reads the name and starts looking for it" "grep -q \"Joining the car's WiFi Direct 'DIRECT-COROLLA'\" '$OUT/bt_logcat.txt'"
  check "no address is sent before WiFi Direct is up" "python3 -c \"import json;d=json.load(open('$OUT/bt1/car_bt.json'));assert d['phone_ip'] is None\" 2>/dev/null"
  check "a second call from the car is answered too" "python3 -c \"import json;d=json.load(open('$OUT/bt2/car_bt.json'));assert d['first_from_phone']=='MD_WIRELESS_INFO_REQUEST'\" 2>/dev/null"
  check "WiFi + BL refuses a car that is not on WiFi Direct" "grep -q 'but FT is set to WiFi + BL' '$OUT/bt_logcat.txt'"
  check "WiFi + BL ignores discovery from the plain network" "udpcall | grep -q 'no answer'"
  cp "$OUT/bt_logcat.txt" "$OUT/bt_direct_only.txt"
  check "WiFi + BL sends no beacon until WiFi Direct is up" "! grep -q 'discovery beacon' '$OUT/bt_direct_only.txt'"
  echo "== bluetooth car (Hotspot) =="
  $ADB shell am start -n $PKG/.MainActivity --ei linkMode 0 --ez auto true >/dev/null 2>&1
  sleep 3
  "$BUMBLE_PY" "$SIMDIR/carlife_bt_car.py" --phone-address "$PHONE_BT" --seconds 3 --out "$OUT/bt3" > "$OUT/bt3.log" 2>&1
  kill $BTLOG 2>/dev/null
  check "Hotspot offers no CarLife bluetooth record" "python3 -c \"import json;d=json.load(open('$OUT/bt3/car_bt.json'));assert d.get('reason')=='no channel'\" 2>/dev/null"
  check "switching to Hotspot closes the bluetooth side" "grep -q 'switching to Hotspot, the other way is closed' '$OUT/bt_logcat.txt'"
fi

echo "== USB cable =="
$ADB logcat -c
$ADB logcat -v time > "$OUT/usb_logcat.txt" 2>&1 &
USBLOG=$!
$ADB shell am start -n $PKG/.MainActivity --ei linkMode 0 --ez auto true --ez wifi true --ez aaAuto false >/dev/null 2>&1
sleep 4
$ADB forward tcp:7300 tcp:7300 >/dev/null 2>&1
python3 "$SIMDIR/carlife_hu_sim.py" --aoa-tcp 127.0.0.1:7300 --encrypt 1 --hold-init 2 --width 1280 --height 720 --fps 30 --seconds 4 --touches "640,360@1" --tail-seconds 2 --out "$OUT/usb" > "$OUT/usb_sim.log" 2>&1
USB=$?
sleep 4
kill $USBLOG 2>/dev/null
$ADB forward --remove tcp:7300 >/dev/null 2>&1
check "a car on the USB cable gets the whole CarLife session (handshake, encryption, picture, touch)" "[ $USB = 0 ]"
check "the cable takes over from the hotspot and the session runs over USB" "grep -q 'USB: a car came in on the test cable' '$OUT/usb_logcat.txt' && grep -q 'session start via USB' '$OUT/usb_logcat.txt' && grep -q 'head unit connected over USB' '$OUT/usb_logcat.txt'"
check "the picture flows over the cable after a tap" "python3 -c \"import json;d=json.load(open('$OUT/usb/result.json'));assert d['checks']['video_flowing'] and d['checks']['video_after_tap1']\" 2>/dev/null"
check "when the cable link ends FT goes back to the hotspot" "awk '/USB: the cable link ended/{e=1} e && /Waiting for the car to join the hotspot/{f=1} END{exit !f}' '$OUT/usb_logcat.txt'"

echo "== crashes =="
check "no FT crash in logcat" "! grep -A3 'FATAL EXCEPTION' '$OUT/logcat.txt' | grep -q 'app.ft'"
grep -E 'FT/' "$OUT/logcat.txt" | sed -E 's/^.*FT\//FT\//' > "$OUT/ft_log.txt"

echo
echo "RESULT: $pass passed, $fail failed  (artifacts in $OUT)"
[ $fail = 0 ]
