import argparse
import base64
import json
import os
import queue
import socket
import struct
import subprocess
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from proto import decode, f_bytes, f_str, f_varint, first, nal_types

CH_CMD, CH_VIDEO, CH_MEDIA, CH_TTS, CH_VR, CH_CTRL = 1, 2, 3, 4, 5, 6
HU_PROTOCOL_VERSION = 0x00018001
PROTOCOL_VERSION_MATCH_STATUS = 0x00010002
HU_INFO = 0x00018003
MD_INFO = 0x00010004
HU_BT_PAIR_INFO = 0x00018005
MD_BT_PAIR_INFO = 0x00010006
VIDEO_ENCODER_INIT = 0x00018007
VIDEO_ENCODER_INIT_DONE = 0x00010008
VIDEO_ENCODER_START = 0x00018009
VIDEO_ENCODER_PAUSE = 0x0001800A
SCREEN_ON = 0x00010018
SCREEN_USERPRESENT = 0x0001001A
FOREGROUND = 0x0001001B
LAUNCH_MODE_NORMAL = 0x0001801D
CARLIFE_DATA_SUBSCRIBE = 0x00018043
CARLIFE_DATA_SUBSCRIBE_DONE = 0x00010044
FRAME_RATE_CHANGE_DONE = 0x0001000D
GO_TO_FOREGROUND = 0x00018025
GO_TO_FOREGROUND_RESPONSE = 0x0001004C
MODULE_STATUS = 0x00010026
GO_TO_DESKTOP = 0x00010021
MEDIA_INFO = 0x00010035
MEDIA_PROGRESS_BAR = 0x00010036
PAUSE_MEDIA = 0x0001800E
FRAME_RATE_CHANGE = 0x0001800C
MODULE_CONTROL = 0x00018028
STATISTIC_INFO = 0x00018027
HU_AUTHEN_REQUEST = 0x00018048
MD_AUTHEN_RESPONSE = 0x00010049
MD_AUTHEN_RESULT = 0x0001004B
MD_FEATURE_CONFIG_REQUEST = 0x00010051
HU_FEATURE_CONFIG_RESPONSE = 0x00018052
MD_RSA_PUBLIC_KEY_REQUEST = 0x0001006A
HU_RSA_PUBLIC_KEY_RESPONSE = 0x0001806B
MD_AES_KEY_SEND_REQUEST = 0x0001006C
HU_AES_REC_RESPONSE = 0x0001806D
MD_ENCRYPT_READY = 0x0001006E
VIDEO_DATA = 0x00020001
VIDEO_HEARTBEAT = 0x00020002
MEDIA_INIT = 0x00030001
MEDIA_STOP = 0x00030002
MEDIA_PAUSE = 0x00030003
MEDIA_RESUME = 0x00030004
MEDIA_DATA = 0x00030006
TTS_INIT = 0x00040001
TTS_END = 0x00040002
TTS_DATA = 0x00040003
TOUCH_ACTION = 0x00068001
TOUCH_DOWN = 0x00068002
TOUCH_UP = 0x00068003
TOUCH_MOVE = 0x00068004
TOUCH_SINGLE_CLICK = 0x00068005
CAR_HARD_KEY_CODE = 0x00068008

NAMES = {v: k for k, v in list(globals().items()) if k.isupper() and isinstance(v, int) and v > 0xFFFF}


def name(sid):
    return NAMES.get(sid, "0x%08X" % sid)


def head_len(ch):
    return 8 if ch in (CH_CMD, CH_CTRL) else 12


def cmd_msg(sid, payload=b""):
    return struct.pack(">HHI", len(payload), 0, sid) + payload


def read_exact(sock, n):
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise EOFError("closed")
        buf += chunk
    return bytes(buf)


def parse_inner(ch, head, body):
    if head_len(ch) == 8:
        return struct.unpack(">I", head[4:8])[0], body
    return struct.unpack(">I", head[8:12])[0], body


def aes_ecb(key, data, decrypt=False):
    args = ["openssl", "enc", "-aes-128-ecb", "-K", key.encode("utf-8").hex(), "-nosalt"]
    if decrypt:
        args.append("-d")
    return subprocess.run(args, input=data, capture_output=True, check=True).stdout


class Rsa:
    def __init__(self, outdir):
        self.priv = os.path.join(outdir, "hu_rsa.pem")
        with open(self.priv, "wb") as f:
            f.write(subprocess.run(["openssl", "genrsa", "2048"], capture_output=True, check=True).stdout)
        self.pub_der = subprocess.run(["openssl", "rsa", "-in", self.priv, "-pubout", "-outform", "DER"], capture_output=True, check=True).stdout

    def public_b64(self):
        return base64.b64encode(self.pub_der).decode()

    def decrypt_b64(self, b64):
        raw = base64.b64decode(b64)
        return subprocess.run(["openssl", "pkeyutl", "-decrypt", "-inkey", self.priv, "-pkeyopt", "rsa_padding_mode:pkcs1"], input=raw, capture_output=True, check=True).stdout


class WifiLink:
    def __init__(self, host, ports):
        self.socks = {}
        self.q = queue.Queue()
        self.aes = None
        self.plain_after_key = 0
        self.audio = []
        self.video_clock = []
        for ch, port in ports.items():
            s = socket.create_connection((host, port), timeout=10)
            s.settimeout(None)
            s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            self.socks[ch] = s
            threading.Thread(target=self._reader, args=(ch, s), daemon=True).start()

    def _unwrap(self, ch, payload):
        if self.aes is None or not payload or ch not in (CH_CMD, CH_VIDEO):
            return payload
        try:
            return aes_ecb(self.aes, payload, decrypt=True)
        except subprocess.CalledProcessError:
            self.plain_after_key += 1
            return payload

    def _deliver(self, ch, head, body):
        sid, payload = parse_inner(ch, head, body)
        if ch == CH_VIDEO:
            self.video_clock.append((time.time(), struct.unpack(">I", head[4:8])[0], sid))
        if ch in (CH_MEDIA, CH_TTS):
            self.audio.append((time.time(), ch, sid, payload, self.aes))
            if sid in (MEDIA_DATA, TTS_DATA):
                return
        self.q.put((ch, sid, self._unwrap(ch, payload)))

    def _reader(self, ch, s):
        hl = head_len(ch)
        try:
            while True:
                head = read_exact(s, hl)
                ln = struct.unpack(">H", head[:2])[0] if hl == 8 else struct.unpack(">I", head[:4])[0]
                body = read_exact(s, ln) if ln else b""
                self._deliver(ch, head, body)
        except Exception as e:
            self.q.put((ch, -1, str(e).encode()))

    def send(self, ch, sid, payload=b""):
        if self.aes is not None and payload and ch in (CH_CMD, CH_CTRL):
            payload = aes_ecb(self.aes, payload)
        self.socks[ch].sendall(cmd_msg(sid, payload))

    def close(self):
        for s in self.socks.values():
            try:
                s.close()
            except Exception:
                pass


class TcpPipe:
    def __init__(self, host, port):
        self.s = socket.create_connection((host, port), timeout=10)
        self.s.settimeout(None)
        self.s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

    def read(self):
        return self.s.recv(16384)

    def write(self, data):
        self.s.sendall(data)

    def close(self):
        try:
            self.s.close()
        except Exception:
            pass


class UsbPipe:
    STRINGS = ["Baidu", "CarLife", "Baidu CarLife", "1.0.0", "http://carlife.baidu.com/", "0720SerialNo."]
    ACCESSORY = (0x2D00, 0x2D01, 0x2D04, 0x2D05)

    def __init__(self, log, model="CarLife", wait=15.0):
        import usb.core
        import usb.util
        self.usb = usb
        self.log = log
        strings = list(self.STRINGS)
        strings[1] = model
        dev = self._accessory()
        if dev is None:
            phone = self._phone()
            if phone is None:
                raise RuntimeError("no Android phone on the Mac's USB")
            proto = phone.ctrl_transfer(0xC0, 51, 0, 0, 2)
            version = proto[0] | (proto[1] << 8)
            log("phone %04x:%04x speaks accessory protocol %d, switching it to %s" % (phone.idVendor, phone.idProduct, version, model))
            for i, s in enumerate(strings):
                phone.ctrl_transfer(0x40, 52, 0, i, s.encode() + b"\0")
            phone.ctrl_transfer(0x40, 53, 0, 0, None)
            usb.util.dispose_resources(phone)
            end = time.time() + wait
            while dev is None and time.time() < end:
                time.sleep(0.25)
                dev = self._accessory()
            if dev is None:
                raise RuntimeError("the phone did not come back as an accessory")
        log("phone is an accessory now (%04x:%04x)" % (dev.idVendor, dev.idProduct))
        self.dev = dev
        cfg = dev.get_active_configuration()
        intf = cfg[(0, 0)]
        try:
            usb.util.claim_interface(dev, intf.bInterfaceNumber)
        except usb.core.USBError as e:
            raise RuntimeError("could not claim the accessory interface: %s" % e)
        self.ep_in = usb.util.find_descriptor(intf, custom_match=lambda e: usb.util.endpoint_direction(e.bEndpointAddress) == usb.util.ENDPOINT_IN)
        self.ep_out = usb.util.find_descriptor(intf, custom_match=lambda e: usb.util.endpoint_direction(e.bEndpointAddress) == usb.util.ENDPOINT_OUT)
        self.max_packet = self.ep_out.wMaxPacketSize
        self.closed = False

    def _accessory(self):
        for pid in self.ACCESSORY:
            d = self.usb.core.find(idVendor=0x18D1, idProduct=pid)
            if d is not None:
                return d
        return None

    def _phone(self):
        for d in self.usb.core.find(find_all=True):
            try:
                for cfg in d:
                    for intf in cfg:
                        if (intf.bInterfaceClass, intf.bInterfaceSubClass, intf.bInterfaceProtocol) == (0xFF, 0x42, 0x01):
                            return d
            except Exception:
                continue
        return None

    def read(self):
        while not self.closed:
            try:
                return bytes(self.ep_in.read(16384, timeout=1000))
            except self.usb.core.USBTimeoutError:
                continue
        return b""

    def write(self, data):
        self.ep_out.write(data, timeout=5000)
        if data and len(data) % self.max_packet == 0:
            self.ep_out.write(b"", timeout=5000)

    def close(self):
        self.closed = True
        try:
            self.usb.util.dispose_resources(self.dev)
        except Exception:
            pass


class AoaLink(WifiLink):
    def __init__(self, pipe):
        self.socks = {}
        self.q = queue.Queue()
        self.aes = None
        self.plain_after_key = 0
        self.audio = []
        self.video_clock = []
        self.pipe = pipe
        self.wlock = threading.Lock()
        self.inner = {}
        threading.Thread(target=self._aoa_reader, daemon=True).start()

    def _aoa_reader(self):
        buf = bytearray()
        try:
            while True:
                chunk = self.pipe.read()
                if not chunk:
                    raise EOFError("closed")
                buf += chunk
                while len(buf) >= 8:
                    ch, ln = struct.unpack(">II", bytes(buf[:8]))
                    if len(buf) < 8 + ln:
                        break
                    payload = bytes(buf[8:8 + ln])
                    del buf[:8 + ln]
                    self._inner(ch, payload)
        except Exception as e:
            self.q.put((CH_CMD, -1, str(e).encode()))

    def _inner(self, ch, payload):
        s = self.inner.setdefault(ch, bytearray())
        s += payload
        hl = head_len(ch)
        while len(s) >= hl:
            ln = struct.unpack(">H", bytes(s[:2]))[0] if hl == 8 else struct.unpack(">I", bytes(s[:4]))[0]
            if len(s) < hl + ln:
                break
            head = bytes(s[:hl])
            body = bytes(s[hl:hl + ln])
            del s[:hl + ln]
            self._deliver(ch, head, body)

    def send(self, ch, sid, payload=b""):
        if self.aes is not None and payload and ch in (CH_CMD, CH_CTRL):
            payload = aes_ecb(self.aes, payload)
        msg = cmd_msg(sid, payload)
        with self.wlock:
            self.pipe.write(struct.pack(">II", ch, len(msg)))
            self.pipe.write(msg)

    def close(self):
        self.pipe.close()


class Sim:
    def __init__(self, link, log):
        self.link = link
        self.log = log
        self.video = []
        self.video_times = []
        self.heartbeats = 0
        self.pending = []
        self.seen = []
        self.songs = []
        self.positions = []
        self.modules = []
        self.marks = []
        self.crash_on_song = False

    def send_cmd(self, sid, payload=b""):
        self.log("HU -> %s %d bytes%s" % (name(sid), len(payload), " (encrypted)" if self.link.aes and payload else ""))
        self.link.send(CH_CMD, sid, payload)

    def send_ctrl(self, sid, payload=b""):
        self.link.send(CH_CTRL, sid, payload)

    def _next(self, timeout):
        try:
            return self.link.q.get(timeout=timeout)
        except queue.Empty:
            return None

    def _absorb(self, item):
        ch, s, p = item
        if s == -1:
            raise RuntimeError("link error: %s" % p.decode(errors="replace"))
        if ch == CH_VIDEO and s == VIDEO_DATA:
            if not p:
                self.marks.append((time.time(), "empty"))
                return None
            self.video.append(p)
            self.video_times.append(time.time())
            self.marks.append((time.time(), "frame"))
            return None
        if ch == CH_VIDEO and s == VIDEO_HEARTBEAT:
            self.heartbeats += 1
            self.marks.append((time.time(), "beat"))
            return None
        if ch == CH_CMD and s == MODULE_STATUS:
            d = decode(p)
            items = [(first(decode(m), 1, 0), first(decode(m), 2, 0)) for m in d.get(2, [])]
            self.modules.append((time.time(), first(d, 1, 0), items))
        if ch == CH_CMD and s == MEDIA_PROGRESS_BAR:
            self.positions.append((time.time(), first(decode(p), 1, 0)))
            return None
        if ch == CH_CMD and s == MEDIA_INFO and self.crash_on_song:
            self.log("HU crashes on song info, like the Corolla did on 4 October")
            raise RuntimeError("simulated head unit crash on song info")
        if ch == CH_CMD and s == MEDIA_INFO:
            d = decode(p)
            song = {
                "t": time.time(),
                "source": first(d, 1, b"").decode(errors="replace"),
                "title": first(d, 2, b"").decode(errors="replace"),
                "artist": first(d, 3, b"").decode(errors="replace"),
                "album": first(d, 4, b"").decode(errors="replace"),
                "art_bytes": len(first(d, 5, b"")),
                "art_is_jpeg": first(d, 5, b"")[:2] == b"\xff\xd8",
                "duration": first(d, 6, 0),
                "fields": sorted(d.keys()),
            }
            self.songs.append(song)
            self.log("MD -> song info %s" % {k: v for k, v in song.items() if k != "t"})
        self.seen.append(s)
        self.log("MD -> %s on ch%d %d bytes" % (name(s), ch, len(p)))
        return item

    def wait_for(self, sid, timeout=10.0):
        for i, item in enumerate(self.pending):
            if item[1] == sid:
                return self.pending.pop(i)
        deadline = time.time() + timeout
        while time.time() < deadline:
            item = self._next(max(0.05, deadline - time.time()))
            if item is None:
                continue
            kept = self._absorb(item)
            if kept is None:
                continue
            if kept[1] == sid:
                return kept
            self.pending.append(kept)
        raise TimeoutError("timeout waiting for %s" % name(sid))

    def drain(self, seconds):
        end = time.time() + seconds
        while time.time() < end:
            item = self._next(max(0.05, end - time.time()))
            if item is None:
                continue
            kept = self._absorb(item)
            if kept is not None:
                self.pending.append(kept)

    def tap(self, x, y):
        self.log("HU -> touch (%d,%d)" % (x, y))
        self.send_ctrl(TOUCH_ACTION, f_varint(1, 0) + f_varint(2, x) + f_varint(3, y))
        time.sleep(0.09)
        self.send_ctrl(TOUCH_ACTION, f_varint(1, 1) + f_varint(2, x) + f_varint(3, y))

    def tap_points(self, x, y):
        self.log("HU -> touch down/up messages (%d,%d)" % (x, y))
        self.send_ctrl(TOUCH_DOWN, f_varint(1, x) + f_varint(2, y))
        time.sleep(0.09)
        self.send_ctrl(TOUCH_UP, f_varint(1, x) + f_varint(2, y))

    def click(self, x, y):
        self.log("HU -> single click (%d,%d)" % (x, y))
        self.send_ctrl(TOUCH_SINGLE_CLICK, f_varint(1, x) + f_varint(2, y))


def video_stats(frames):
    counts = {}
    for f in frames:
        for t in nal_types(f):
            counts[t] = counts.get(t, 0) + 1
    return {"frames": len(frames), "bytes": sum(len(f) for f in frames), "sps": counts.get(7, 0), "pps": counts.get(8, 0), "idr": counts.get(5, 0), "p": counts.get(1, 0)}


def write_h264(path, frames):
    with open(path, "wb") as f:
        for fr in frames:
            f.write(fr)


def play_model(packets, rate_bytes, prebuffer):
    if not packets:
        return None
    start = packets[0][0] + prebuffer
    clock = start
    level = 0.0
    last = packets[0][0]
    gaps = []
    delays = []
    for t, n in packets:
        if t > clock:
            played = (t - clock) * rate_bytes
            if played > level:
                short = (played - level) / rate_bytes
                if t > start:
                    gaps.append((round(clock - start + level / rate_bytes, 3), round(short, 3)))
                level = 0.0
            else:
                level -= played
            clock = t
        level += n
        delays.append((round(t - packets[0][0], 3), round(level / rate_bytes, 3)))
        last = t
    total = sum(n for _, n in packets)
    span = packets[-1][0] - packets[0][0]
    return {
        "packets": len(packets),
        "bytes": total,
        "audio_seconds": round(total / rate_bytes, 3),
        "wall_seconds": round(span, 3),
        "extra_seconds": round(total / rate_bytes - span, 3),
        "gaps": gaps,
        "gap_seconds": round(sum(g for _, g in gaps), 3),
        "max_delay": max(d for _, d in delays),
        "end_delay": delays[-1][1],
        "delay_track": delays[::max(1, len(delays) // 40)],
    }


def write_wav(path, pcm, rate, channels):
    import wave
    with wave.open(path, "wb") as w:
        w.setnchannels(channels)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm)


def clear_audio(audio):
    keyed = {}
    for i, (t, ch, sid, p, key) in enumerate(audio):
        if key and p and sid in (MEDIA_DATA, TTS_DATA) and len(p) % 16 == 0:
            keyed.setdefault(key, []).append(i)
    plain = [(t, ch, sid, p) for t, ch, sid, p, key in audio]
    for key, idx in keyed.items():
        blob = b"".join(audio[i][3] for i in idx)
        out = subprocess.run(["openssl", "enc", "-d", "-aes-128-ecb", "-K", key.encode("utf-8").hex(), "-nosalt", "-nopad"], input=blob, capture_output=True, check=True).stdout
        at = 0
        for i in idx:
            n = len(audio[i][3])
            chunk = out[at:at + n]
            at += n
            pad = chunk[-1] if chunk else 0
            if 1 <= pad <= 16 and chunk.endswith(bytes([pad]) * pad):
                chunk = chunk[:-pad]
            t, ch, sid, _ = plain[i]
            plain[i] = (t, ch, sid, chunk)
    return plain


def analyse_audio(audio, out):
    if not audio:
        return None
    audio = clear_audio(audio)
    t0 = audio[0][0]
    media = [(t, len(p)) for t, ch, sid, p in audio if ch == CH_MEDIA and sid == MEDIA_DATA]
    tts = [(t, len(p)) for t, ch, sid, p in audio if ch == CH_TTS and sid == TTS_DATA]
    events = [{"t": round(t - t0, 3), "ch": "media" if ch == CH_MEDIA else "tts", "msg": name(sid), "body": decode(p) if p else {}}
              for t, ch, sid, p in audio if sid not in (MEDIA_DATA, TTS_DATA)]
    tts_rate = 16000
    tts_channels = 1
    for e in events:
        if e["msg"] == "TTS_INIT":
            tts_rate = first(e["body"], 1, 16000)
            tts_channels = first(e["body"], 2, 1)
    for e in events:
        e["body"] = {k: v[0] if not isinstance(v[0], bytes) else v[0].hex() for k, v in e["body"].items()}
    write_wav(os.path.join(out, "media.wav"), b"".join(p for _, ch, sid, p in audio if ch == CH_MEDIA and sid == MEDIA_DATA), 48000, 2)
    if tts:
        write_wav(os.path.join(out, "tts.wav"), b"".join(p for _, ch, sid, p in audio if ch == CH_TTS and sid == TTS_DATA), tts_rate, tts_channels)
    return {
        "events": events,
        "media": play_model(media, 192000.0, 0.3),
        "tts": play_model(tts, tts_rate * tts_channels * 2.0, 0.15),
        "first_media_at": round(media[0][0] - t0, 3) if media else None,
        "first_tts_at": round(tts[0][0] - t0, 3) if tts else None,
    }


def baidu_size(w, h, new_vehicle=False):
    wide = new_vehicle and h > 0 and w / h >= 2.3
    if w < 800:
        return 768, 432
    if w < 1024:
        return (1024, 384) if wide else (848, 480)
    if w < 1280:
        return (1024, 384) if wide else (1024, 576)
    if w < 1920:
        return (1280, 480) if wide else (1280, 720)
    if new_vehicle:
        return (1920, 720) if wide else (1920, 1080)
    return 1280, 720


def pauses(marks, events):
    out = []
    for i, (kind, at) in enumerate(events):
        if kind != "pause":
            continue
        back = next((t for k, t in events[i + 1:] if k == "start"), None)
        if back is None:
            continue
        during = [k for t, k in marks if at + 0.3 <= t < back]
        after = [(t, k) for t, k in marks if t >= back]
        first_frame = next((t for t, k in after if k == "frame"), None)
        out.append({
            "paused_for": round(back - at, 2),
            "frames_while_paused": during.count("frame"),
            "heartbeats_while_paused": during.count("beat"),
            "first_frame_after": round(first_frame - back, 3) if first_frame else None,
            "heartbeats_before_first_frame": len([1 for t, k in after if k == "beat" and (first_frame is None or t < first_frame)]),
        })
    return out


def feature_list(encrypt):
    items = [("CONTENT_ENCRYPTION", 1 if encrypt else 0), ("BLUETOOTH_AUTO_PAIR", 1), ("FOCUS_UI", 0)]
    out = f_varint(1, len(items))
    for k, v in items:
        out += f_bytes(2, f_str(1, k) + f_varint(2, v))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--ports", default="7240,8240,9240,9241,9242,9340")
    ap.add_argument("--width", type=int, default=1280)
    ap.add_argument("--stream", choices=["baidu", "own"], default="baidu")
    ap.add_argument("--height", type=int, default=720)
    ap.add_argument("--fps", type=int, default=30)
    ap.add_argument("--seconds", type=float, default=4.0)
    ap.add_argument("--touches", default="")
    ap.add_argument("--tail-seconds", type=float, default=4.0)
    ap.add_argument("--hardkey", type=int, default=-1)
    ap.add_argument("--encrypt", type=int, default=1)
    ap.add_argument("--hold-init", type=float, default=3.0)
    ap.add_argument("--out", default="/tmp/ft_carlife")
    ap.add_argument("--listen-seconds", type=float, default=0.0)
    ap.add_argument("--crash-on-song", action="store_true")
    ap.add_argument("--protocol", default="1.0")
    ap.add_argument("--aoa-tcp", default="", help="host:port of FT's USB test cable (debug builds)")
    ap.add_argument("--usb", action="store_true", help="act as the car over a real USB cable to the phone")
    ap.add_argument("--usb-model", default="CarLife")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)

    def log(m):
        print("[carlife-sim] " + m, flush=True)

    if a.usb:
        link = AoaLink(UsbPipe(log, a.usb_model))
    elif a.aoa_tcp:
        h, port = a.aoa_tcp.rsplit(":", 1)
        link = AoaLink(TcpPipe(h, int(port)))
    else:
        p = [int(x) for x in a.ports.split(",")]
        link = WifiLink(a.host, {CH_CMD: p[0], CH_VIDEO: p[1], CH_MEDIA: p[2], CH_TTS: p[3], CH_VR: p[4], CH_CTRL: p[5]})
    sim = Sim(link, log)
    sim.crash_on_song = a.crash_on_song
    result = {"encrypt": bool(a.encrypt), "ok": False, "checks": {}, "segments": []}
    major, minor = [int(v) for v in a.protocol.split(".")]
    new_vehicle = major == 4 or (major == 3 and minor == 2)
    events = []
    try:
        sim.send_cmd(HU_PROTOCOL_VERSION, f_varint(1, major) + f_varint(2, minor))
        _, _, pl = sim.wait_for(PROTOCOL_VERSION_MATCH_STATUS)
        result["checks"]["version_match"] = first(decode(pl), 1) == 1
        t_match = time.time()
        sim.wait_for(FOREGROUND, timeout=3.0)
        result["checks"]["foreground_after_match"] = True
        _, _, pl = sim.wait_for(MD_INFO, timeout=3.0)
        d = decode(pl)
        result["checks"]["md_info_before_hu_info"] = first(d, 1, b"").decode() == "Android" and len(first(d, 14, b"")) > 0
        result["md_model"] = first(d, 14, b"").decode(errors="replace")
        _, _, pl = sim.wait_for(MODULE_STATUS, timeout=3.0)
        md = decode(pl)
        ids = sorted(first(decode(m), 1, 0) for m in md.get(2, []))
        result["module_list"] = ids
        result["checks"]["module_list_like_baidu"] = first(md, 1) == 5 and ids == [1, 2, 3, 4, 6]

        sim.send_cmd(STATISTIC_INFO, f_str(1, "cuid") + f_str(2, "1.0") + f_varint(3, 1) + f_str(4, "sim") + f_varint(5, 1) + f_varint(6, 1) + f_varint(7, 1))
        if not new_vehicle:
            _, _, pl = sim.wait_for(MD_AUTHEN_RESULT)
            result["checks"]["authen_result_true"] = first(decode(pl), 1) == 1
        sim.wait_for(MD_FEATURE_CONFIG_REQUEST, timeout=3.0)
        result["checks"]["feature_config_after_statistics"] = True
        sim.send_cmd(HU_FEATURE_CONFIG_RESPONSE, feature_list(a.encrypt))
        sim.send_cmd(HU_INFO, f_str(1, "FT-HU-SIM") + f_str(2, "desay") + f_str(14, "G6SA"))
        sim.send_cmd(CARLIFE_DATA_SUBSCRIBE, f_varint(1, 1) + f_bytes(2, f_varint(1, 0)))
        _, _, pl = sim.wait_for(CARLIFE_DATA_SUBSCRIBE_DONE, timeout=3.0)
        sd = decode(pl)
        items = [(first(decode(m), 1, -1), first(decode(m), 2, -1)) for m in sd.get(2, [])]
        result["subscribe_done"] = items
        result["checks"]["subscribe_answered"] = first(sd, 1) == 1 and items == [(0, 0)]

        sim.send_cmd(HU_BT_PAIR_INFO, f_str(1, "00:11:22:33:44:55") + f_str(5, "0000110a-0000-1000-8000-00805f9b34fb") + f_str(6, "FT-HU-SIM") + f_varint(7, 0))
        _, _, pl = sim.wait_for(MD_BT_PAIR_INFO)
        bt = decode(pl)
        result["checks"]["bt_pair_info_complete"] = all(k in bt for k in (1, 5, 6, 7)) and first(bt, 7) == 1
        result["bt_pair_reply"] = {k: (v[0].decode(errors="replace") if isinstance(v[0], bytes) else v[0]) for k, v in bt.items()}

        if a.encrypt:
            sim.wait_for(MD_RSA_PUBLIC_KEY_REQUEST, timeout=5.0)
            rsa = Rsa(a.out)
            sim.send_cmd(HU_RSA_PUBLIC_KEY_RESPONSE, f_str(1, rsa.public_b64()))
            _, _, pl = sim.wait_for(MD_AES_KEY_SEND_REQUEST)
            key = rsa.decrypt_b64(first(decode(pl), 1, b"").decode()).decode("utf-8")
            result["checks"]["aes_key_16_chars"] = len(key) == 16
            link.aes = key
            log("AES session key received (%d chars), encryption on" % len(key))
            sim.send_cmd(HU_AES_REC_RESPONSE)
            sim.wait_for(MD_ENCRYPT_READY)
            result["checks"]["encryption_negotiated"] = True
        else:
            sim.drain(a.hold_init)
            result["checks"]["no_rsa_when_plain"] = MD_RSA_PUBLIC_KEY_REQUEST not in sim.seen

        while time.time() - t_match < a.hold_init:
            sim.drain(0.2)
        result["video_before_start"] = len(sim.marks)
        result["checks"]["no_video_before_start"] = len(sim.marks) == 0
        result["md_info_count"] = sim.seen.count(MD_INFO)
        result["checks"]["md_info_sent_once"] = sim.seen.count(MD_INFO) == 1

        init = f_varint(1, a.width) + f_varint(2, a.height) + f_varint(3, a.fps)
        sim.send_cmd(VIDEO_ENCODER_INIT, init)
        _, _, pl = sim.wait_for(VIDEO_ENCODER_INIT_DONE)
        di = decode(pl)
        want = (a.width, a.height) if a.stream == "own" else baidu_size(a.width, a.height, new_vehicle)
        result["init_done"] = [first(di, 1, -1), first(di, 2, -1), first(di, 3, -1)]
        result["checks"]["init_done_stream_size"] = (first(di, 1, -1), first(di, 2, -1)) == want and first(di, 3, -1) == a.fps
        result["stream"] = list(want)
        sim.wait_for(FOREGROUND)

        sim.send_cmd(LAUNCH_MODE_NORMAL)
        sim.send_cmd(VIDEO_ENCODER_START)
        events.append(("start", time.time()))

        sim.send_cmd(HU_AUTHEN_REQUEST, f_str(1, "r4nd0m"))
        _, _, pl = sim.wait_for(MD_AUTHEN_RESPONSE)
        result["checks"]["authen_response"] = len(first(decode(pl), 1, b"")) > 0

        sim.drain(a.seconds)
        st = video_stats(sim.video)
        result["video_launcher"] = st
        result["checks"]["video_flowing"] = st["frames"] >= int(a.seconds * 5) and st["sps"] > 0 and st["pps"] > 0 and st["idr"] > 0
        result["first_frame_nals"] = nal_types(sim.video[0])[:4] if sim.video else []
        result["checks"]["first_frame_carries_the_header"] = bool(sim.video) and nal_types(sim.video[0])[:3] == [7, 8, 5]
        write_h264(os.path.join(a.out, "launcher.h264"), sim.video)
        log("launcher video: %s" % st)

        if a.touches:
            t0 = time.time()
            marks = []
            for spec in a.touches.split(";"):
                xy, at = spec.split("@")
                at = float(at)
                while time.time() - t0 < at:
                    sim.drain(min(0.25, at - (time.time() - t0)))
                if xy.startswith("key:"):
                    k = int(xy[4:])
                    sim.log("HU -> steering wheel key %d" % k)
                    sim.send_ctrl(CAR_HARD_KEY_CODE, f_varint(1, k))
                    continue
                if xy.startswith("fps:"):
                    n = int(xy[4:])
                    sim.log("HU -> asks for %d fps" % n)
                    result.setdefault("rate_clock", []).append([n, round(time.time(), 3)])
                    sim.send_cmd(FRAME_RATE_CHANGE, f_varint(1, n))
                    continue
                if xy.startswith("cmd:"):
                    sid = int(xy[4:], 16)
                    sim.send_cmd(sid, f_varint(1, 3) + f_varint(2, 1) if sid == MODULE_CONTROL else b"")
                    continue
                if xy == "pause":
                    events.append(("pause", time.time()))
                    sim.send_cmd(VIDEO_ENCODER_PAUSE)
                    continue
                if xy == "start":
                    events.append(("start", time.time()))
                    sim.send_cmd(LAUNCH_MODE_NORMAL)
                    sim.send_cmd(VIDEO_ENCODER_START)
                    continue
                if xy.startswith("music:"):
                    sim.send_cmd(MODULE_CONTROL, f_varint(1, 3) + f_varint(2, int(xy[6:])))
                    continue
                if xy.startswith("points:") or xy.startswith("click:"):
                    px, py = [int(v) for v in xy.split(":")[1].split(",")]
                    result.setdefault("tap_clock", []).append([px, py, round(time.time(), 3)])
                    if xy.startswith("points:"):
                        sim.tap_points(px, py)
                    else:
                        sim.click(px, py)
                    continue
                x, y = [int(v) for v in xy.split(",")]
                marks.append((x, y, len(sim.video)))
                result.setdefault("tap_clock", []).append([x, y, round(time.time(), 3)])
                sim.tap(x, y)
            sim.drain(a.tail_seconds)
            for i, (x, y, n0) in enumerate(marks):
                n1 = marks[i + 1][2] if i + 1 < len(marks) else len(sim.video)
                seg = sim.video[n0:n1]
                st = video_stats(seg)
                fn = "tap%d_%d_%d.h264" % (i + 1, x, y)
                write_h264(os.path.join(a.out, fn), seg)
                result["segments"].append({"tap": i + 1, "x": x, "y": y, "file": fn, "video": st})
                result["checks"]["video_after_tap%d" % (i + 1)] = st["frames"] >= 5
                log("after tap %d (%d,%d): %s" % (i + 1, x, y, st))

        if a.listen_seconds > 0:
            log("listening to the phone's sound for %.0fs" % a.listen_seconds)
            sim.drain(a.listen_seconds)

        if a.hardkey >= 0:
            sim.send_ctrl(CAR_HARD_KEY_CODE, f_varint(1, a.hardkey))
            sim.drain(1.0)

        sim.send_cmd(GO_TO_FOREGROUND)
        sim.drain(1.0)
        result["checks"]["go_to_foreground_left_alone"] = GO_TO_FOREGROUND_RESPONSE not in sim.seen
        result["heartbeats"] = sim.heartbeats
        result["pauses"] = pauses(sim.marks, events)
        sps = video_stats(sim.video)["sps"]
        result["sps_total"] = sps
        result["checks"]["header_sent_once"] = sps == 1
        clocks = [ts for t, ts, sid in link.video_clock]
        now_s = int(time.time())
        result["checks"]["video_clock_in_seconds"] = bool(clocks) and all(abs(ts - now_s) < 600 for ts in clocks)
        result["module_events"] = [[round(t - (link.audio[0][0] if link.audio else t), 3), cnt, items] for t, cnt, items in sim.modules]
        if a.encrypt:
            result["plain_after_key"] = link.plain_after_key
            result["checks"]["everything_encrypted_after_key"] = link.plain_after_key == 0
        result["total_frames"] = len(sim.video)
        t_audio0 = link.audio[0][0] if link.audio else 0
        result["songs"] = [dict(x, t=round(x["t"] - t_audio0, 3)) for x in sim.songs]
        result["audio_t0"] = round(t_audio0, 3)
        result["go_to_desktop"] = sim.seen.count(GO_TO_DESKTOP)
        result["frame_clock"] = [round(t, 3) for t in sim.video_times]
        write_h264(os.path.join(a.out, "all.h264"), sim.video)
        result["positions"] = [(round(t - t_audio0, 3), v) for t, v in sim.positions]
        result["ok"] = all(result["checks"].values())
    except Exception as e:
        result["error"] = "%s: %s" % (type(e).__name__, e)
    finally:
        link.close()
    try:
        heard = analyse_audio(link.audio, a.out)
        if heard is not None:
            result["audio"] = heard
    except Exception as e:
        result["audio_error"] = "%s: %s" % (type(e).__name__, e)
    with open(os.path.join(a.out, "result.json"), "w") as f:
        json.dump(result, f, indent=2)
    print(json.dumps(result, indent=2))
    sys.exit(0 if result["ok"] else 1)


if __name__ == "__main__":
    main()
