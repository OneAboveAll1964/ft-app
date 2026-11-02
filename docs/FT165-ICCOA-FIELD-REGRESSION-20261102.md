# FT165 XA60 — FT163 field feedback, preserving FT164 fixes

Date: 2026-11-02. Candidate built on full FT164 commit
`bde98de1b53b4077ee3bf4f6ededd59a6ec5ff32`, not FT163.
Package remains `app.ft.xa60`; versionCode is **165**.
The approved owner-signed, non-debuggable build workflow remains in place.

## Physical FT163 evidence

- ZIP: `FT-report-samsung-SM-S916B-20261102-112823.zip` in OpenIVI Drive,
  SHA-256 `2f44d76de3816304396f50ecadf6a8ec07021750f9410dc3b240f62f8da63060`.
- ICCOA auth and TCP SENSOR lane completed.
- OEM GearInfo sensor count reached 256; GPS packet count remained zero.
- FT forwarded OEM navigation-active=false three times.
- AA SENSOR channel opened but subscribed only to sensor 13 and 10,
  not LOCATION=1. It cannot be assumed to consume unsolicited car GPS.
- User reports vertical touch now usable even for small AA icons; X/close
  did not work in AA Yandex Maps or mirrored Google Maps.
- In mirroring, many top/right DOWN events were mapped and forwarded.
  Existing accessibility implementation dispatched DOWN and UP as
  separate asynchronous, continuable gestures: a rapid click could
  cancel the first gesture before the target received a completed press.

## FT165 changes

1. **Kept FT164 in full**: strict OEM GetPort SENSOR request parsing,
   strict GearInfo parser, prior GPS and AA anti-jump processing, privacy
   default-off coordinate diagnostic logging, signing identity, and
   FT video/audio/AA touch calibration.
2. **Mirrored taps**: stationary single-finger DOWN/UP is batched into
   one completed Android Accessibility gesture (60-1200 ms); real drags
   remain DOWN/MOVE/UP. No arbitrary global X/Y offset, no modification
   to the working 4-point calibration or AA's independent touch mapping.
3. **AA SDP**: when ICCOA car GPS is offered, put LOCATION first in
   advertised sensor list while retaining driving-status and night-mode.
   This is a non-destructive interoperability hypothesis. No claim that
   the phone will subscribe without a directed GPS request.
4. **Diagnostics**: report when the AA phone did not request LOCATION
   after 12 seconds, and when the OEM sensor lane delivered GearInfo but
   no GPS after 20 seconds. No raw coordinates, PIN or phone location
   stored by these new messages.

## Still unverified

- HU OEM CarLinkSensorMgr may only emit GPS when **actual guided
  navigation** is active. FT165 does not forge navigation=true or
  invent a hardware GPS feed. This causal path requires P0 evidence.
- Android Auto 17.9 may continue to rely on the phone GPS and never
  subscribe to vehicle LOCATION; an SDP reordering cannot force it.
- AA Yandex Maps X/close needs physical retest separately from mirror
  small-target fix.
- Trusted PIN-less pairing and OEM real-world GPS CRS are open.

Owner-approved combined vehicle acceptance is deferred until the
software work is ready; do not mark P0 GPS as passing based on CI.
