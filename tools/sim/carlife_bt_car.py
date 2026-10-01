import argparse
import asyncio
import json
import os
import re
import subprocess
import sys
import time

os.environ.setdefault('GRPC_VERBOSITY', 'ERROR')
os.environ.setdefault('GRPC_ENABLE_FORK_SUPPORT', 'false')

from bumble import hci, rfcomm
from bumble.core import PhysicalTransport, UUID
from bumble.device import Device, DeviceConfiguration
from bumble.hci import Address
from bumble.keys import JsonKeyStore
from bumble.pairing import PairingConfig, PairingDelegate
from bumble.transport import open_transport

CARLIFE = UUID('a45bc7e5-bb50-4949-9de1-f78299cf6d78')
SPP = UUID('00001101-0000-1000-8000-00805F9B34FB')

NAMES = {
    0x0A: 'READY',
    0x100001: 'MD_WIRELESS_INFO_REQUEST',
    0x100004: 'MD_TARGET_INFO_REQUEST',
    0x100007: 'MD_WIFI_IP',
    0x100008: 'MD_STATUS',
    0x108002: 'HU_WIRELESS_INFO',
    0x108005: 'HU_TARGET_INFO',
    0x108006: 'HU_IP_REQUEST',
    0x108009: 'HU_STATUS',
}

started = time.monotonic()
events = []


def log(kind, text, **extra):
    t = time.monotonic() - started
    print(f'{t:7.2f}  {kind:<6} {text}', flush=True)
    events.append(dict(t=round(t, 3), kind=kind, text=text, **extra))


def varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def field_varint(no, value):
    return varint(no << 3) + varint(value)


def field_bytes(no, data):
    return varint((no << 3) | 2) + varint(len(data)) + data


def frame(kind, body=b''):
    return len(body).to_bytes(2, 'big') + b'\x00\x00' + kind.to_bytes(4, 'big') + body


def read_fields(body):
    fields = {}
    i = 0
    try:
        while i < len(body):
            key = 0
            shift = 0
            while True:
                b = body[i]
                i += 1
                key |= (b & 0x7F) << shift
                shift += 7
                if not b & 0x80:
                    break
            no, wire = key >> 3, key & 7
            if wire == 0:
                v = 0
                shift = 0
                while True:
                    b = body[i]
                    i += 1
                    v |= (b & 0x7F) << shift
                    shift += 7
                    if not b & 0x80:
                        break
                fields[no] = v
            elif wire == 2:
                n = 0
                shift = 0
                while True:
                    b = body[i]
                    i += 1
                    n |= (b & 0x7F) << shift
                    shift += 7
                    if not b & 0x80:
                        break
                raw = body[i:i + n]
                i += n
                try:
                    fields[no] = raw.decode()
                except UnicodeDecodeError:
                    fields[no] = raw.hex()
            else:
                break
    except IndexError:
        pass
    return fields


def adb(*args):
    return subprocess.run(['adb', *args], capture_output=True, text=True, timeout=20).stdout


def tap_text(xml, labels, contains=False):
    for node in re.finditer(r'<node [^>]*>', xml):
        n = node.group(0)
        t = re.search(r'text="([^"]*)"', n)
        if not t:
            continue
        text = t.group(1)
        hit = any((l.lower() in text.lower()) if contains else (text == l) for l in labels)
        if not hit:
            continue
        m = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if m:
            x = (int(m.group(1)) + int(m.group(3))) // 2
            y = (int(m.group(2)) + int(m.group(4))) // 2
            adb('shell', 'input', 'tap', str(x), str(y))
            return text
    return None


def accept_pairing_on_phone(stop):
    opened = False
    begun = time.monotonic()
    while not stop.is_set():
        adb('shell', 'uiautomator', 'dump', '/sdcard/car_ui.xml')
        xml = adb('shell', 'cat', '/sdcard/car_ui.xml')
        hit = tap_text(xml, ('Pair', 'PAIR', 'Pair & connect'))
        if hit:
            log('phone', f'tapped "{hit}" on the phone')
            return
        if stop.is_set():
            return
        if not opened and time.monotonic() - begun > 2:
            adb('shell', 'cmd', 'statusbar', 'expand-notifications')
            time.sleep(1)
            adb('shell', 'uiautomator', 'dump', '/sdcard/car_ui.xml')
            xml = adb('shell', 'cat', '/sdcard/car_ui.xml')
            hit = tap_text(xml, ('Pair', 'PAIR')) or tap_text(xml, ('pairing request', 'tap to pair', 'pair with'), contains=True)
            if hit:
                log('phone', f'opened "{hit}" from the notification shade')
                opened = True
        time.sleep(0.7)


class Car:
    def __init__(self, args, dlc):
        self.args = args
        self.dlc = dlc
        self.buffer = bytearray()
        self.got_ip = asyncio.get_running_loop().create_future()
        self.asked_ip = False

    def send(self, kind, body=b''):
        data = frame(kind, body)
        self.dlc.write(data)
        log('car>', f'{NAMES.get(kind, hex(kind))} {body.hex() if body else ""}'.strip(), raw=data.hex())

    def feed(self, data):
        log('rx', data.hex(), raw=data.hex())
        self.buffer += data
        while len(self.buffer) >= 8:
            if self.buffer[0] == 0xFF:
                log('phone>', 'vendor frame ' + self.buffer.hex())
                self.buffer.clear()
                return
            n = int.from_bytes(self.buffer[0:2], 'big')
            if len(self.buffer) < 8 + n:
                return
            kind = int.from_bytes(self.buffer[4:8], 'big')
            body = bytes(self.buffer[8:8 + n])
            del self.buffer[:8 + n]
            self.handle(kind, body)

    def handle(self, kind, body):
        name = NAMES.get(kind, hex(kind))
        fields = read_fields(body) if body else {}
        log('phone>', f'{name} {fields if fields else ""}'.strip(), type=hex(kind), body=body.hex())
        a = self.args
        if kind == 0x0A:
            if a.answer_ready:
                self.send(0x0A)
            else:
                log('car', 'a car on the CL2AA flow does not answer READY, so nothing is sent back')
        elif kind == 0x100001:
            self.send(0x108002, field_varint(1, a.wireless_type) + field_varint(2, a.freq))
        elif kind == 0x100004:
            body = field_bytes(1, a.p2p_name.encode()) + field_bytes(4, a.p2p_info.encode())
            self.send(0x108005, body)
            asyncio.get_running_loop().call_later(a.ip_delay, self.ask_ip)
        elif kind == 0x100007:
            ip = fields.get(1, '')
            log('car', f'phone says it is at {ip}')
            if not self.got_ip.done():
                self.got_ip.set_result(ip)
        elif kind == 0x100008:
            log('car', f'phone status {fields}')

    def ask_ip(self):
        self.asked_ip = True
        self.send(0x108006)


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--transport', default='android-netsim:name=COROLLA')
    ap.add_argument('--name', default='COROLLA')
    ap.add_argument('--address', default='F0:F1:F2:F3:F4:F5')
    ap.add_argument('--phone', default='sdk_gphone')
    ap.add_argument('--phone-address', default='')
    ap.add_argument('--uuid', default=str(CARLIFE))
    ap.add_argument('--p2p-name', default='DIRECT-COROLLA')
    ap.add_argument('--p2p-info', default='')
    ap.add_argument('--wireless-type', type=int, default=2)
    ap.add_argument('--freq', type=int, default=5745)
    ap.add_argument('--ip-delay', type=float, default=1.0)
    ap.add_argument('--answer-ready', action='store_true')
    ap.add_argument('--seconds', type=float, default=25)
    ap.add_argument('--no-adb', action='store_true')
    ap.add_argument('--out', default='')
    ap.add_argument('--keys', default=os.path.join(os.path.dirname(os.path.abspath(__file__)), '.car_bt_keys.json'))
    a = ap.parse_args()

    async with await open_transport(a.transport) as (source, sink):
        config = DeviceConfiguration()
        config.name = a.name
        config.address = Address(a.address)
        config.classic_enabled = True
        config.class_of_device = 0x240408
        device = Device.from_config_with_hci(config, source, sink)
        device.classic_enabled = True
        device.keystore = JsonKeyStore(None, a.keys)
        device.pairing_config_factory = lambda connection: PairingConfig(
            sc=True, mitm=False, bonding=True,
            delegate=PairingDelegate(io_capability=PairingDelegate.IoCapability.NO_OUTPUT_NO_INPUT),
        )
        await device.power_on()
        await device.set_discoverable(True)
        await device.set_connectable(True)
        log('car', f'{a.name} is on the air as {a.address}')

        target = a.phone_address
        if not target:
            found = asyncio.get_running_loop().create_future()

            def on_inquiry(address, class_of_device, data, rssi):
                name = ''
                try:
                    for ad_type, value in data.ad_structures:
                        if ad_type in (0x08, 0x09):
                            name = value.decode(errors='replace')
                except Exception:
                    pass
                log('scan', f'{address} {name!r} cod={class_of_device:06x}')
                if a.phone.lower() in name.lower() and not found.done():
                    found.set_result(str(address))

            device.on('inquiry_result', on_inquiry)
            await device.start_discovery()
            try:
                target = await asyncio.wait_for(found, 30)
            finally:
                await device.stop_discovery()
        target = target.replace('/P', '')
        log('car', f'calling the phone at {target}')
        connection = await device.connect(target, transport=PhysicalTransport.BR_EDR)
        log('car', f'linked: {connection}')

        stop = None
        if not a.no_adb:
            import threading
            stop = threading.Event()
            threading.Thread(target=accept_pairing_on_phone, args=(stop,), daemon=True).start()
        try:
            await asyncio.wait_for(connection.authenticate(), 40)
            await connection.encrypt()
            log('car', 'paired and encrypted')
        except Exception as e:
            log('car', f'pairing did not finish: {e!r}')
        finally:
            if stop:
                stop.set()
                adb('shell', 'cmd', 'statusbar', 'collapse')

        channels = await rfcomm.find_rfcomm_channels(connection)
        for ch, uuids in sorted(channels.items()):
            log('sdp', f'channel {ch}: ' + ', '.join(str(u) for u in uuids))
        want = UUID(a.uuid)
        channel = next((ch for ch, uuids in channels.items() if want in uuids), None)
        if channel is None:
            channel = next((ch for ch, uuids in channels.items() if SPP in uuids), None)
            if channel is not None:
                log('car', f'no {want} record, falling back to SPP on channel {channel}')
        if channel is None:
            log('car', 'the phone offers no CarLife or SPP channel, nothing to call')
            result = dict(ok=False, reason='no channel', events=events)
        else:
            mux = await rfcomm.Client(connection).start()
            dlc = await mux.open_dlc(channel)
            log('car', f'rfcomm channel {channel} open')
            car = Car(a, dlc)
            dlc.sink = car.feed
            ip = None
            try:
                ip = await asyncio.wait_for(car.got_ip, a.seconds)
            except asyncio.TimeoutError:
                log('car', f'no address from the phone after {a.seconds:.0f}s')
            await asyncio.sleep(1.5)
            first = next((e for e in events if e['kind'] == 'phone>'), None)
            result = dict(
                ok=ip is not None,
                channel=channel,
                first_from_phone=first['text'] if first else None,
                phone_ip=ip,
                events=events,
            )
        if a.out:
            os.makedirs(a.out, exist_ok=True)
            with open(os.path.join(a.out, 'car_bt.json'), 'w') as f:
                json.dump(result, f, indent=1)
        try:
            await connection.disconnect()
        except Exception:
            pass
        print('RESULT', json.dumps({k: v for k, v in result.items() if k != 'events'}))
        return 0 if result.get('ok') else 1


if __name__ == '__main__':
    sys.exit(asyncio.run(main()))
