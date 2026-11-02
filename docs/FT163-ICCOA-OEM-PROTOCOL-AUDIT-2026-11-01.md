# FT163 / FAW Toyota RAV4 XA60 — ICCOA / AA / Baidu source and protocol audit

Date: 2026-11-01. Status: **P1 static source audit; live HU acceptance P0 pending**.
No assumption that other ICCOA cars or GAC Wildlander have identical FAW Toyota transport.

## Authority and evidence

- Original owner HU: `/system/product/app/Carlink/Carlink.apk` from Drive
  `1cHL637kiF8dPseLyu3tQkiy5Czw5uB1_`; original APK SHA-256
  `6292f74524a5f7eecb84fe80d7b4c63acd81835912f03cf085ee78ae44c076d7`.
  Verified `ucar.proto` embedded in this exact APK.
- OEM static code-chain Nexus `f46360d7-bc05-49ea-8196-ec58237fd804`;
  GNSS/DR degradation Nexus `045cca41-3a9e-4f40-9295-b078ebaa18e5`;
  underground parking `Dimp4.zip` 2026-08-23: ~148.7s gap in HAL fused position while other
  log sources continued; **pure dead reckoning never proven**.
- Alliance public technical requirements
  <https://www.iccoa.cn/tech/68.html> and public SDK privacy declaration
  <https://www.iccoa.cn/suit_2.html>: phone-side CarLink can receive car GPS for navigation,
  seamless reconnect is a *system-level feature*. Public pages do NOT specify the
  Toyota's exact Bluetooth bonding / PIN-less resumption wire exchange.
- Independent Android Auto sensor wire documentation:
  <https://github.com/mrmees/open-android-auto/blob/main/docs/channels/sensor.md>.
  Cross-version 16.2 / 17.3 deep traces are documented there; phone-side version on
  owner's Samsung remains to be verified on physical connection.

## Protocol comparison

| Layer | Original OEM / public reference | Current FT source | Status |
| --- | --- | --- | --- |
| BLE bootstrap | Carlink GATT discovery and bootstrap metadata; rotating private advertisement addresses | User-selected peer + persisted synthetic ICCOA client ID, named unique-device fallback; local GATT notifications, no unsupported CCCD write | Partial: auto-select does not imply PIN-less auth |
| Pair/reauth | ICCOA supports automatic reconnection after first pairing in compatible official integrations | `IccoaSession.onServicesDiscovered` unconditionally `requestPin`. KeyStore persists ECDSA phone signing identity but **not** car trust grant; no proven resume branch | **P0 FAIL: six-digit PIN still required** |
| TCP auth | OEM sign-in lane; P-256, nonce, PIN-MAC, ECDH, session AES-GCM | IccoaAuth / IccoaControl bounds and verifies signature/MAC before deriving session key | P1 static match; P0 real HU repeat needed |
| ICCOA header | Length, sequence, flags, category, method, CRC16 | Bounded 4 MB frame, CRC16, per-frame cipher state, accepted HU flag variants | P1 static match; exact non-plaintext frame variants pending |
| Port selection | `ucar.proto ControlIndex.get_port_request=2, response=3`, ServerType SENSOR=1, AUDIO=2, UIBC=3 | TCP listeners 57219/29/39/49/59 already bound; FT163 only replies to verified SENSOR request with port 57239 | P1 partial. Other port requests intentionally not guessed |
| Vehicle control | ControlIndex methods 1 heartbeat, 4/5 foreground/background, 8 audio, 11 nav, 23 disconnect, 24/25 config, 26/27 app list | Supported methods listed; NotifyAudioPlayerState field5 encoding=PCM_16BIT=2; send navigation method11 newly added | P1 static; HU runtime pending |
| Sensor payload | `ucar.proto SensorIndex.gps=1, gear_info=6`, Gps(double latitude/longitude; uint32 speed/heading/sats; timestamp), GearInfo(gear=1,speed=2) | `IccoaGpsWire`, `IccoaGearWire` parse corresponding method on category 2 | P1 static. Actual ICCOA GPS frames and possible nested wrapper not yet observed |
| GPS producer activation | OEM `CarLinkAdapter.onNavigationInfoReceived` toggles `CarLinkSensorMgr` active; manager subscribes to Android Location and GNSS status | FT163 forwards real AA NavigationInfo.active via method11, retransmits when CONTROL listener becomes available | P1 source-backed hypothesis, not proved on HU |
| Android Auto sensors | Phone sends SENSOR_START_REQUEST 0x8001; HU responds 0x8002 and sends 0x8003; 0x8004 error. GPSLocationData fields 2/3 int32 microdegrees | FT advertises LOCATION=1; `AaCarGps` encodes 2/3; three-consistent-fix anti-jump gate, rejects 0,0 / stale; transient and recovery errors | P1 protocol shape, P0 AA Maps acceptance pending |
| Baidu CarLife | CMD_CAR_GPS=0x00018010; CMD_CAR_VELOCITY=0x0001800F; int32 coords (scale unspecified) | Adapter receives raw values into `VehicleTelemetry` but deliberately does not forward to AA pending scale/datum verification | P1 safely partial |
| WFD media | OEM expects H.264 over RTSP/RTP and audio; source signals possible `ovm_control_capability` | Video/AAC/RTSP/AA touch implemented; OVM capability not advertised without verified service/port | P0 performance and OVM remain open |
| Logging | Owner authorized 1 sample/10s; 1Hz maximum for 20 seconds around events | `VehicleLogSampler` integrated with normal FT ZIP; full live location stream unthrottled | Source and unit tests pass in FT162 baseline |

## High-confidence defects and bounded resolution

1. **P0 PIN-less reconnect absent**: saving BLE address, client ID, or generated ECDSA key
   is NOT proof of a trusted pairing. The current code still asks for a new PIN every
   connection; owner reports HU Bluetooth paired-device list full. Do not retry pairing
   automatically, persist six-digit codes, claim this feature passes, or create a
   different synthetic client identity. Investigate OEM vendor trust/resume path before
   changing auth.
2. **P1 missed GPS activation**: OEM CarLinkSensorMgr is navigation-driven.
   FT previously did not publish `NotifyNavigationInfo`; now sends only when Android
   Auto reports real navigation and when the authenticated control channel becomes ready.
   Plain Yandex Maps mirroring has no independently proven navigation-active callback.
3. **P1 missing SENSOR port response**: ControlIndex method2 was silently ignored.
   Implemented bounded response only for SENSOR/57239. AUDIO/UIBC are not invented.
4. **P1 dead reckoning not established**: OEM GNSS/DR fused output observed at ~10Hz
   during some degraded conditions, but Field underground recording shows 148.7s missing
   fused-location reports. ICCOA GPS `LocationManager` consumer may get no updates in
   prolonged GNSS loss. Do not promise accurate Moscow tunnel guidance until owner test.
5. **P1 AA location acceptance not established**: Android Auto GPS indication uses
   correct microdegrees/field numbers but actual app may ignore sensor changes or not
   switch to phone GPS from a transient SensorError. Physical AA Maps regression is required.
6. **P1 GPS CRS / speed units open**: exact OEM GPS uses `Location.getLatitude`,
   `getLongitude`, `getSpeed`, `getBearing`, but `Gps` protobuf fields speed/heading
   are uint32 and the conversion in vendor native codec is not independently verified.
   FT displays unknown units as raw and never feeds uncalibrated Baidu into AA.
   Decimal-degree Android API origin is provisionally labeled WGS84; compare HU
   with independent reference at rest and motion to exclude GCJ02/BD09 offsets.
7. **P1 BT limit risk**: first FT162 run with a persistent KeyStore identity could
   be treated as a new device by OEM even if synthetic client ID was preserved. Do not
   delete existing pairing or reset FT unless user controls registered devices and accepts consequences.
8. **P1 video/touch regressions**: AA vertical touch needs owner physical acceptance.
   Keep calibrated mappedX/mappedY; do not change existing H.264 sender during GPS work.
9. **P2 RTSP OVM**: `ovm_control_capability` omitted because no trusted port/service
   was recovered. OEM formerly logged missing capability; do not fabricate a port.

## Existing verification and next owner-only acceptance

- FT162 signed build run `41343783565` terminal SUCCESS; released under tag
  `xa60-ft162-11916b0cfbf8`, before Android Auto GPS forwarding.
- FT163 incremental changes require a fresh signed successful run and verified asset.
- When testing at the car: **do not create additional Bluetooth registrations**.
  Observe whether saved car is detected, whether PIN is still required (currently
  expected), and whether Android Auto navigation turns on OEM SENSOR traffic.
- In a SHORT stationary then driving session collect FT's manual ZIP.
  Look for control method11 navigation, inbound SENSOR/1 GPS and SENSOR/6 GearInfo,
  and AA sensor subscribe / frame counts. GPS values in logs are sampled every 10s,
  with 1Hz event bursts; avoid storing exact personal routes beyond necessary acceptance.
- Underground parking repeat is NOT required: previously recorded Field evidence
  already establishes the 148.7s gap. Only perform new physical test if car->AA
  forwarding is confirmed and owner explicitly chooses a suitable environment.

Conclusion: **FT ICCOA is a partial, manually reimplemented Toyota-specific transport,
not yet a complete official ICCOA CarLink SDK implementation**. Its basic H.264/touch
and AES-GCM protocols have working-source and P1 observations, but GPS/DR and
no-PIN reconnect still have P0 acceptance blockers.

## 2026-11-02 — exact OEM proto readback and AA anti-teleport regression

APK independently re-opened and SHA-256 verified as
`6292f74524a5f7eecb84fe80d7b4c63acd81835912f03cf085ee78ae44c076d7`.
The APK contains plaintext `ucar.proto` with these exact declarations:

| Source | OEM field/type | FT mapping | Assessment |
| --- | --- | --- | --- |
| `GetPortRequest` | `ServerType type=1` (`SENSOR=1,AUDIO=2,UIBC=3`) | `IccoaPortNegotiation.responseFor` | MATCH for known SENSOR request |
| `GetPortResponse` | `Result result=1`, `int32 port=2` | SENSOR=success, 57239 | MATCH; other ports unverified |
| `NotifyNavigationInfo` | `bool isNavigating=1`, `string distance=3`, `string distanceUnit=4`, `string where=6` | `IccoaNavigation.encode` | MATCH for fields used |
| `Gps` | `double altitude=1,latitude=2,longitude=3`; `uint32 antenna_state=4,pdop=5,speed=6,heading=7,sats_used=8,sats_visible=9`; `uint64 timestamp=10` | `IccoaGpsWire` | MATCH direct protobuf fields; units/CRS not HU-accepted |
| `GearInfo` | `GearState gear=1`, `uint32 speed=2` | `IccoaGearWire` | MATCH direct protobuf fields; speed unit unknown |
| `SensorIndex` | `Gps gps=1`, `GearInfo gear_info=6` | ICCOA category=2 method=1/6 | **Runtime envelope unverified**; do not claim OEM sends the nested SensorIndex wrapper, nor fabricate another framing |

**Proven FT behavioral defect (not a proto mismatch):**
`AaGpsGate` previously reset its consecutive counter on a teleport, then
delivered the third wrong but locally consistent fix, without comparing it
against the last position actually transmitted to Android Auto. It could
relocate navigation despite impossible motion. The fix retains the last
delivered position as a separate anchor and rejects even sustained
far-away clusters during a continuous stream. A genuinely stale source
still resets the filter and supports cautious reacquisition after three
valid fixes; the existing transient SensorError path remains unchanged.

No change to ICCOA auth, pairing, WFD/H.264, RTP, audio or UIBC. OEM sensor
frame nesting and trusted PIN-less resume still need P0 car evidence/OEM
DEX confirmation. This readback is P1 static, not owner physical acceptance.
