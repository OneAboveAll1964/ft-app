# FT164 XA60 — ICCOA request and telemetry hardening

Date: 2026-11-02. This is a **P1 source regression candidate** built on FT163
`6695ca7a50d3dd60e412ee86d251f2c9c3249c9f`.

Plan linkage (Nexus canonical, do not conflate items):
- Existing FT plan `3c69bbd8-dea1-4d0d-9b7a-dfead4ecd789`
- Deep ICCOA rework `f82aca0e-e041-43b0-a87b-2db2b5c19469`
- Owner physical acceptance remains a **single combined end-of-work P0 gate**.

## Reproduced source-level faults and fix

| Layer | Before | FT164 |
|---|---|---|
| CONTROL GetPortRequest method 2 | General `ProtoReader` silently stops on truncated fields, possibly leaving valid `type=1` and replying to a corrupt request | Check the exact canonical byte sequence `08 01` for OEM `ServerType.SENSOR=1`; all unknown and damaged requests receive no response |
| SENSOR GearInfo method 6 | Lenient `ProtoReader` may accept valid speed preceding damaged/duplicate trailing fields and publish it | Full bounded varint scan with duplicate/wire/tag/range checks before telemetry mutation; unit tests confirm no state pollution |
| APK upgrade | Previous versionCode 163 | versionCode 164, applicationId `app.ft.xa60`, existing owner signing key checked in CI |
| Media/touch/auth | Previously working FT163 | Unchanged. No new BT pairing, PIN persistence, speculative resume, H264 or UIBC rewrite |

Only canonical `GetPortRequest` sensor type is accepted, as emitted by the
declared vendor proto encoder. Other port types remain unknown/unimplemented,
not guessed. Unknown gear speed units remain raw; BAIDU coordinates are not
converted to WGS84. ICCOA SensorIndex GPS wrapper stays an optional strict
decoder, not an assertion about physical HU traffic.

FT164 acceptance requires terminal CI test/build/sign/release proof and then
the owner's combined HU test after all remaining approved software work. A
signed APK alone does not validate PINless reconnect, GPS CRS, Android Auto
sensor delivery, audio or touchscreen calibration.
