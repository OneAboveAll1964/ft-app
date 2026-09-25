import argparse
import json
import os
import queue
import socket
import struct
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from proto import decode, f_str, f_varint, first, nal_types

CH_CMD, CH_VIDEO, CH_MEDIA, CH_TTS, CH_VR, CH_CTRL = 1, 2, 3, 4, 5, 6
HU_PROTOCOL_VERSION = 0x00018001
PROTOCOL_VERSION_MATCH_STATUS = 0x00010002
HU_INFO = 0x00018003
MD_INFO = 0x00010004
VIDEO_ENCODER_INIT = 0x00018007
VIDEO_ENCODER_INIT_DONE = 0x00010008
VIDEO_ENCODER_START = 0x00018009
VIDEO_ENCODER_PAUSE = 0x0001800A
FOREGROUND = 0x0001001B
GO_TO_FOREGROUND = 0x00018025
GO_TO_FOREGROUND_RESPONSE = 0x0001004C
MODULE_STATUS = 0x00010026
STATISTIC_INFO = 0x00018027
HU_AUTHEN_REQUEST = 0x00018048
MD_AUTHEN_RESPONSE = 0x00010049
MD_AUTHEN_RESULT = 0x0001004B
VIDEO_DATA = 0x00020001
VIDEO_HEARTBEAT = 0x00020002
MEDIA_INIT = 0x00030001
TOUCH_ACTION = 0x00068001
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


class WifiLink:
    def __init__(self, host, ports):
        self.socks = {}
        self.q = queue.Queue()
        for ch, port in ports.items():
            s = socket.create_connection((host, port), timeout=10)
            s.settimeout(None)
            s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            self.socks[ch] = s
            threading.Thread(target=self._reader, args=(ch, s), daemon=True).start()

    def _reader(self, ch, s):
        hl = head_len(ch)
        try:
            while True:
                head = read_exact(s, hl)
                ln = struct.unpack(">H", head[:2])[0] if hl == 8 else struct.unpack(">I", head[:4])[0]
                body = read_exact(s, ln) if ln else b""
                sid, payload = parse_inner(ch, head, body)
                self.q.put((ch, sid, payload))
        except Exception as e:
            self.q.put((ch, -1, str(e).encode()))

    def send(self, ch, inner):
        self.socks[ch].sendall(inner)

    def close(self):
        for s in self.socks.values():
            try:
                s.close()
            except Exception:
                pass


class MuxLink:
    def __init__(self, host, port):
        self.s = socket.create_connection((host, port), timeout=10)
        self.s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.q = queue.Queue()
        self.lock = threading.Lock()
        threading.Thread(target=self._reader, daemon=True).start()

    def _reader(self):
        try:
            while True:
                outer = read_exact(self.s, 8)
                ch = outer[3]
                ln = struct.unpack(">I", outer[4:8])[0]
                inner = read_exact(self.s, ln)
                hl = head_len(ch)
                sid, payload = parse_inner(ch, inner[:hl], inner[hl:])
                self.q.put((ch, sid, payload))
        except Exception as e:
            self.q.put((0, -1, str(e).encode()))

    def send(self, ch, inner):
        pkt = bytes([0, 0, 0, ch]) + struct.pack(">I", len(inner)) + inner
        with self.lock:
            self.s.sendall(pkt)

    def close(self):
        self.s.close()


class Sim:
    def __init__(self, link, log):
        self.link = link
        self.log = log
        self.video = []
        self.heartbeats = 0
        self.pending = []

    def send_cmd(self, sid, payload=b""):
        self.log("HU -> %s %d bytes" % (name(sid), len(payload)))
        self.link.send(CH_CMD, cmd_msg(sid, payload))

    def send_ctrl(self, sid, payload=b""):
        self.link.send(CH_CTRL, cmd_msg(sid, payload))

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
            self.video.append(p)
            return None
        if ch == CH_VIDEO and s == VIDEO_HEARTBEAT:
            self.heartbeats += 1
            return None
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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["usb", "wifi"], default="usb")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--mux-port", type=int, default=7250)
    ap.add_argument("--ports", default="7240,8240,9240,9241,9242,9340")
    ap.add_argument("--width", type=int, default=1280)
    ap.add_argument("--height", type=int, default=720)
    ap.add_argument("--fps", type=int, default=30)
    ap.add_argument("--seconds", type=float, default=4.0)
    ap.add_argument("--touches", default="")
    ap.add_argument("--tail-seconds", type=float, default=4.0)
    ap.add_argument("--hardkey", type=int, default=-1)
    ap.add_argument("--out", default="/tmp/ft_carlife")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)

    def log(m):
        print("[carlife-sim] " + m, flush=True)

    if a.mode == "usb":
        link = MuxLink(a.host, a.mux_port)
    else:
        p = [int(x) for x in a.ports.split(",")]
        link = WifiLink(a.host, {CH_CMD: p[0], CH_VIDEO: p[1], CH_MEDIA: p[2], CH_TTS: p[3], CH_VR: p[4], CH_CTRL: p[5]})
    sim = Sim(link, log)
    result = {"mode": a.mode, "ok": False, "checks": {}, "segments": []}
    try:
        sim.send_cmd(HU_PROTOCOL_VERSION, f_varint(1, 1) + f_varint(2, 0))
        _, _, p = sim.wait_for(PROTOCOL_VERSION_MATCH_STATUS)
        result["checks"]["version_match"] = first(decode(p), 1) == 1

        sim.send_cmd(HU_INFO, f_str(1, "FT-HU-SIM") + f_str(2, "desay") + f_str(14, "G6SA"))
        _, _, p = sim.wait_for(MD_INFO)
        d = decode(p)
        result["checks"]["md_info"] = first(d, 1, b"").decode() == "Android" and len(first(d, 14, b"")) > 0
        result["md_model"] = first(d, 14, b"").decode(errors="replace")

        init = f_varint(1, a.width) + f_varint(2, a.height) + f_varint(3, a.fps)
        sim.send_cmd(VIDEO_ENCODER_INIT, init)
        _, _, p = sim.wait_for(VIDEO_ENCODER_INIT_DONE)
        result["checks"]["init_done_echo"] = p == init
        sim.wait_for(FOREGROUND)
        _, _, p = sim.wait_for(MODULE_STATUS)
        result["checks"]["module_status"] = first(decode(p), 1) == 6

        sim.send_cmd(VIDEO_ENCODER_START)
        _, _, p = sim.wait_for(MEDIA_INIT)
        dm = decode(p)
        result["checks"]["media_init"] = first(dm, 1) == 48000 and first(dm, 2) == 2 and first(dm, 3) == 16

        sim.send_cmd(STATISTIC_INFO, f_str(1, "cuid") + f_str(2, "1.0") + f_varint(3, 1) + f_str(4, "sim") + f_varint(5, 1) + f_varint(6, 1) + f_varint(7, 1))
        _, _, p = sim.wait_for(MD_AUTHEN_RESULT)
        result["checks"]["authen_result_true"] = first(decode(p), 1) == 1

        sim.send_cmd(HU_AUTHEN_REQUEST, f_str(1, "r4nd0m"))
        _, _, p = sim.wait_for(MD_AUTHEN_RESPONSE)
        result["checks"]["authen_response"] = len(first(decode(p), 1, b"")) > 0

        sim.drain(a.seconds)
        st = video_stats(sim.video)
        result["video_launcher"] = st
        result["checks"]["video_flowing"] = st["frames"] >= int(a.seconds * 5) and st["sps"] > 0 and st["pps"] > 0 and st["idr"] > 0
        write_h264(os.path.join(a.out, "launcher.h264"), sim.video)
        log("launcher video: %s" % st)

        if a.touches:
            t0 = time.time()
            marks = []
            for spec in a.touches.split(";"):
                xy, at = spec.split("@")
                x, y = [int(v) for v in xy.split(",")]
                at = float(at)
                while time.time() - t0 < at:
                    sim.drain(min(0.25, at - (time.time() - t0)))
                marks.append((x, y, len(sim.video)))
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

        if a.hardkey >= 0:
            sim.send_ctrl(CAR_HARD_KEY_CODE, f_varint(1, a.hardkey))
            sim.drain(1.0)

        sim.send_cmd(GO_TO_FOREGROUND)
        sim.wait_for(GO_TO_FOREGROUND_RESPONSE)
        result["checks"]["go_to_foreground"] = True
        result["heartbeats"] = sim.heartbeats
        result["checks"]["heartbeat"] = sim.heartbeats >= 1
        result["total_frames"] = len(sim.video)
        result["ok"] = all(result["checks"].values())
    except Exception as e:
        result["error"] = "%s: %s" % (type(e).__name__, e)
    finally:
        link.close()
    with open(os.path.join(a.out, "result.json"), "w") as f:
        json.dump(result, f, indent=2)
    print(json.dumps(result, indent=2))
    sys.exit(0 if result["ok"] else 1)


if __name__ == "__main__":
    main()
