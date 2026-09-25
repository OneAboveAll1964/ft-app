import argparse
import hashlib
import json
import os
import socket
import ssl
import struct
import subprocess
import sys
import threading
import time
import queue

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from proto import decode, f_sint32, f_str, f_varint, first, split_nals

CH_CONTROL, CH_INPUT, CH_SENSOR, CH_VIDEO = 0, 1, 2, 3
VERSION_REQUEST, VERSION_RESPONSE, SSL_HANDSHAKE, AUTH_COMPLETE = 1, 2, 3, 4
SERVICE_DISCOVERY_REQUEST, SERVICE_DISCOVERY_RESPONSE = 5, 6
CHANNEL_OPEN_REQUEST, CHANNEL_OPEN_RESPONSE = 7, 8
PING_REQUEST, PING_RESPONSE = 11, 12
NAV_FOCUS_REQUEST, NAV_FOCUS_NOTIFICATION = 13, 14
BYEBYE_REQUEST, BYEBYE_RESPONSE = 15, 16
AUDIO_FOCUS_REQUEST, AUDIO_FOCUS_NOTIFICATION = 18, 19
AV_MEDIA_TS, AV_MEDIA_CONFIG = 0x0000, 0x0001
AV_SETUP_REQUEST, AV_START, AV_STOP, AV_SETUP_RESPONSE, AV_MEDIA_ACK = 0x8000, 0x8001, 0x8002, 0x8003, 0x8004
VIDEO_FOCUS_REQUEST, VIDEO_FOCUS_INDICATION = 0x8007, 0x8008
SENSOR_START_REQUEST, SENSOR_START_RESPONSE, SENSOR_EVENT = 0x8001, 0x8002, 0x8003
INPUT_EVENT, BINDING_REQUEST, BINDING_RESPONSE = 0x8001, 0x8002, 0x8003
MAX = 0x4000


def read_exact(sock, n):
    buf = bytearray()
    while len(buf) < n:
        c = sock.recv(n - len(buf))
        if not c:
            raise EOFError("closed")
        buf += c
    return bytes(buf)


class Phone:
    def __init__(self, sock, hu_cert, srv_cert, srv_key, log):
        self.s = sock
        self.log = log
        self.q = queue.Queue()
        self.lock = threading.Lock()
        self.bufs = {}
        self.touches = []
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(srv_cert, srv_key)
        ctx.verify_mode = ssl.CERT_REQUIRED
        ctx.load_verify_locations(hu_cert)
        ctx.verify_flags |= ssl.VERIFY_X509_PARTIAL_CHAIN
        ctx.verify_flags |= 0x200000
        ctx.minimum_version = ssl.TLSVersion.TLSv1_2
        ctx.maximum_version = ssl.TLSVersion.TLSv1_2
        ctx.check_hostname = False
        self.inb = ssl.MemoryBIO()
        self.outb = ssl.MemoryBIO()
        self.tls = ctx.wrap_bio(self.inb, self.outb, server_side=True)
        self.handshake_done = False
        self.peer_sha1 = None
        threading.Thread(target=self._reader, daemon=True).start()

    def _reader(self):
        try:
            while True:
                h = read_exact(self.s, 2)
                ch, flags = h[0], h[1]
                size = struct.unpack(">H", read_exact(self.s, 2))[0]
                if flags & 3 == 1:
                    read_exact(self.s, 4)
                payload = read_exact(self.s, size)
                chunk = self.decrypt(payload) if flags & 8 else payload
                t = flags & 3
                if t == 3:
                    self.q.put((ch, bool(flags & 4), chunk))
                    continue
                if t == 1:
                    self.bufs[ch] = bytearray()
                self.bufs.setdefault(ch, bytearray()).extend(chunk)
                if t == 2:
                    self.q.put((ch, bool(flags & 4), bytes(self.bufs.pop(ch))))
        except Exception as e:
            self.q.put((-1, False, str(e).encode()))

    def encrypt(self, plain):
        with self.lock:
            self.tls.write(plain)
            return self.outb.read()

    def decrypt(self, cipher):
        with self.lock:
            self.inb.write(cipher)
            out = bytearray()
            while True:
                try:
                    out += self.tls.read(65536)
                except ssl.SSLWantReadError:
                    break
            return bytes(out)

    def send(self, ch, mid, body=b"", enc=True, control=False):
        payload = struct.pack(">H", mid) + body
        base = (8 if enc else 0) | (4 if control else 0)
        frames = []
        if len(payload) < MAX:
            data = self.encrypt(payload) if enc else payload
            frames.append(bytes([ch, base | 3]) + struct.pack(">H", len(data)) + data)
        else:
            off = 0
            total = len(payload)
            while off < total:
                n = min(MAX, total - off)
                chunk = payload[off:off + n]
                t = 1 if off == 0 else (0 if total - off - n > 0 else 2)
                data = self.encrypt(chunk) if enc else chunk
                hdr = bytes([ch, base | t]) + struct.pack(">H", len(data))
                if t == 1:
                    hdr += struct.pack(">I", total)
                frames.append(hdr + data)
                off += n
        with self.lock:
            for f in frames:
                self.s.sendall(f)

    def recv(self, timeout=10.0):
        ch, control, payload = self.q.get(timeout=timeout)
        if ch == -1:
            raise RuntimeError("link error: %s" % payload.decode(errors="replace"))
        return ch, control, struct.unpack(">H", payload[:2])[0], payload[2:]

    def expect(self, ch, mid, timeout=10.0, allow=()):
        deadline = time.time() + timeout
        while time.time() < deadline:
            c, control, m, body = self.recv(max(0.05, deadline - time.time()))
            if c == ch and m == mid:
                return body
            if c == CH_CONTROL and m == PING_REQUEST:
                self.send(CH_CONTROL, PING_RESPONSE, f_varint(1, first(decode(body), 1, 0)))
                continue
            if c == CH_INPUT and m == INPUT_EVENT:
                self.touches.append(body)
                self.log("  (buffered touch from car)")
                continue
            if (c, m) in allow:
                continue
            self.log("  (unexpected ch%d msg 0x%04x, %d bytes)" % (c, m, len(body)))
        raise TimeoutError("timeout waiting ch%d 0x%04x" % (ch, mid))

    def handshake(self):
        while not self.handshake_done:
            ch, control, mid, body = self.recv()
            if mid != SSL_HANDSHAKE:
                raise RuntimeError("expected handshake, got 0x%04x" % mid)
            self.inb.write(body)
            try:
                with self.lock:
                    self.tls.do_handshake()
                self.handshake_done = True
            except ssl.SSLWantReadError:
                pass
            out = self.outb.read()
            if out:
                self.send(CH_CONTROL, SSL_HANDSHAKE, out, enc=False)
        der = self.tls.getpeercert(binary_form=True)
        self.peer_sha1 = hashlib.sha1(der).hexdigest().upper()


def make_h264(path, width, height, fps, seconds):
    if os.path.exists(path):
        return
    subprocess.check_call([
        "ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=size=%dx%d:rate=%d" % (width, height, fps),
        "-t", str(seconds), "-c:v", "libx264", "-profile:v", "baseline", "-level", "3.1", "-pix_fmt", "yuv420p",
        "-g", str(fps), "-bf", "0", "-bsf:v", "h264_mp4toannexb", "-f", "h264", path,
    ])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=5277)
    ap.add_argument("--hu-cert", required=True)
    ap.add_argument("--srv-cert", required=True)
    ap.add_argument("--srv-key", required=True)
    ap.add_argument("--h264", default="/tmp/ft_aa_testsrc.h264")
    ap.add_argument("--width", type=int, default=1280)
    ap.add_argument("--height", type=int, default=720)
    ap.add_argument("--fps", type=int, default=30)
    ap.add_argument("--seconds", type=float, default=3.0)
    ap.add_argument("--wait-touch", type=float, default=12.0)
    ap.add_argument("--out", default="/tmp/ft_aa")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    lines = []

    def log(m):
        lines.append(m)
        print(m, flush=True)

    result = {"ok": False, "checks": {}}
    sock = socket.create_connection((a.host, a.port), timeout=10)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    ph = Phone(sock, a.hu_cert, a.srv_cert, a.srv_key, log)
    try:
        ch, control, mid, body = ph.recv()
        result["checks"]["version_request"] = mid == VERSION_REQUEST and body[:4] == b"\x00\x01\x00\x01"
        log("HU version request %s" % body.hex())
        ph.send(CH_CONTROL, VERSION_RESPONSE, b"\x00\x01\x00\x01\x00\x00", enc=False)

        ph.handshake()
        result["hu_cert_sha1"] = ph.peer_sha1
        result["checks"]["hu_presented_google_cert"] = ph.peer_sha1 == "9A448A56E0D3F7545D5A8BC819E375064767D26B"
        log("TLS done, HU cert sha1=%s" % ph.peer_sha1)

        ch, control, mid, body = ph.recv()
        result["checks"]["auth_complete"] = mid == AUTH_COMPLETE and first(decode(body), 1) == 0

        ph.send(CH_CONTROL, SERVICE_DISCOVERY_REQUEST, f_str(4, "sim") + f_str(5, "FT Sim Phone"))
        body = ph.expect(CH_CONTROL, SERVICE_DISCOVERY_RESPONSE)
        sd = decode(body)
        services = {}
        for sb in sd.get(1, []):
            svc = decode(sb)
            services[first(svc, 1)] = svc
        video = services.get(CH_VIDEO, {})
        sink = decode(first(video, 3, b""))
        vcfg = decode(first(sink, 4, b""))
        result["services"] = sorted(services.keys())
        result["checks"]["discovery_video_h264_720p"] = first(sink, 1) == 3 and first(vcfg, 1) == 2 and first(vcfg, 2) == 2
        result["checks"]["discovery_input_touch"] = CH_INPUT in services and first(decode(first(decode(first(services[CH_INPUT], 4, b"")), 2, b"")), 1) == a.width
        result["checks"]["discovery_sensor"] = CH_SENSOR in services
        result["hu_name"] = first(sd, 14, b"").decode(errors="replace")

        for c in (CH_SENSOR, CH_INPUT, CH_VIDEO):
            ph.send(c, CHANNEL_OPEN_REQUEST, f_sint32(1, 0) + f_varint(2, c), control=True)
            body = ph.expect(c, CHANNEL_OPEN_RESPONSE)
            result["checks"]["open_ch%d" % c] = first(decode(body), 1) == 0

        ph.send(CH_SENSOR, SENSOR_START_REQUEST, f_varint(1, 13) + f_varint(2, 0))
        ph.expect(CH_SENSOR, SENSOR_START_RESPONSE)
        body = ph.expect(CH_SENSOR, SENSOR_EVENT)
        result["checks"]["driving_status_unrestricted"] = first(decode(first(decode(body), 13, b"")), 1) == 0

        ph.send(CH_INPUT, BINDING_REQUEST, f_varint(1, 3) + f_varint(1, 4))
        ph.expect(CH_INPUT, BINDING_RESPONSE)
        result["checks"]["binding"] = True

        ph.send(CH_VIDEO, AV_SETUP_REQUEST, f_varint(1, 0))
        body = ph.expect(CH_VIDEO, AV_SETUP_RESPONSE)
        result["checks"]["setup_ready"] = first(decode(body), 1) == 2
        body = ph.expect(CH_VIDEO, VIDEO_FOCUS_INDICATION)
        result["checks"]["focus_projected"] = first(decode(body), 1) == 1

        ph.send(CH_VIDEO, AV_START, f_varint(1, 7) + f_varint(2, 0))
        ph.expect(CH_VIDEO, VIDEO_FOCUS_INDICATION, allow=((CH_VIDEO, VIDEO_FOCUS_INDICATION),))

        make_h264(a.h264, a.width, a.height, a.fps, a.seconds)
        nals = split_nals(open(a.h264, "rb").read())
        cfg = b"".join(n for n in nals if (n[4] & 0x1F) in (7, 8) or (n[3] == 1 and (n[3 + 1] & 0x1F) in (7, 8)))
        frames = [n for n in nals if n[-1:] and ((n[4] & 0x1F) in (1, 5) if n[2] == 0 else (n[3] & 0x1F) in (1, 5))]
        ph.send(CH_VIDEO, AV_MEDIA_CONFIG, cfg)
        body = ph.expect(CH_VIDEO, AV_MEDIA_ACK)
        result["checks"]["config_acked"] = first(decode(body), 1) == 7
        ts = 0
        acked = 0
        t0 = time.time()
        for i, fr in enumerate(frames):
            ph.send(CH_VIDEO, AV_MEDIA_TS, struct.pack(">Q", ts) + fr)
            ts += 1000000 // a.fps
            try:
                ph.expect(CH_VIDEO, AV_MEDIA_ACK, timeout=3.0)
                acked += 1
            except TimeoutError:
                pass
            time.sleep(max(0.0, (i + 1) / a.fps - (time.time() - t0)))
        result["frames_sent"] = len(frames)
        result["frames_acked"] = acked
        result["checks"]["video_acked"] = acked >= len(frames) * 0.9

        ph.send(CH_CONTROL, PING_REQUEST, f_varint(1, 123456789))
        body = ph.expect(CH_CONTROL, PING_RESPONSE)
        result["checks"]["ping_echo"] = first(decode(body), 1) == 123456789

        ph.send(CH_CONTROL, AUDIO_FOCUS_REQUEST, f_varint(1, 1))
        body = ph.expect(CH_CONTROL, AUDIO_FOCUS_NOTIFICATION)
        result["checks"]["audio_focus_gain"] = first(decode(body), 1) == 1

        ph.send(CH_CONTROL, NAV_FOCUS_REQUEST, f_varint(1, 2))
        body = ph.expect(CH_CONTROL, NAV_FOCUS_NOTIFICATION)
        result["checks"]["nav_focus"] = first(decode(body), 1) == 2

        if a.wait_touch > 0:
            log("waiting up to %.0fs for a touch from the car..." % a.wait_touch)
            try:
                body = ph.touches.pop(0) if ph.touches else ph.expect(CH_INPUT, INPUT_EVENT, timeout=a.wait_touch)
                rep = decode(body)
                te = decode(first(rep, 3, b""))
                pt = decode(first(te, 1, b""))
                result["touch"] = {"x": first(pt, 1), "y": first(pt, 2), "action": first(te, 3)}
                result["checks"]["touch_received"] = first(pt, 1) is not None
                log("touch from car: %s" % result["touch"])
            except TimeoutError:
                result["checks"]["touch_received"] = False

        ph.send(CH_CONTROL, BYEBYE_REQUEST, f_varint(1, 1))
        ph.expect(CH_CONTROL, BYEBYE_RESPONSE)
        result["checks"]["byebye"] = True
        result["ok"] = all(result["checks"].values())
    except Exception as e:
        result["error"] = "%s: %s" % (type(e).__name__, e)
    finally:
        try:
            sock.close()
        except Exception:
            pass
    with open(os.path.join(a.out, "result.json"), "w") as f:
        json.dump(result, f, indent=2)
    print(json.dumps(result, indent=2))
    sys.exit(0 if result["ok"] else 1)


if __name__ == "__main__":
    main()
