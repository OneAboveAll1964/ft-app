# FT167 XA60 — larger AA controls and actual mirrored tap completion receipts

Owner request on 2026-11-02: Android Auto icons/targets are difficult to hit while driving.
Target ~1.5x icon size. Retain all FT164/165/166 changes.

Existing FT166 uses service-discovery VideoConfig DPI=160 by default while displaying
about 1920x1008 on the Toyota XA60. Android Auto renders its own UI from the
advertised logical density. FT167 negotiates **240 DPI** by default for this
large screen without resizing or cropping decoded video, and without changing
AA viewport touch mapping or the previous HU four-point calibration.

On existing installations, a one-time migration upgrades *only* legacy
160 DPI to 240 DPI; customized DPI values are preserved. The user can
manually restore 160 DPI with no future automatic override. UI documents
the new setting and the need to disconnect/reconnect Android Auto.
For lower-resolution 800x480 screens, effective DPI is capped at 170 to
avoid losing most logical screen space; 1280x720 and 1920x1008 use 240.

The mirrored tap injector is now diagnosed at the Android Accessibility
callback boundary. It logs bounded aggregate counts for dispatch acceptance,
successful gesture completion, cancellation and rejection (no coordinates
or profile information). The FT166 owner field evidence proved 105 mirror
touches were forwarded, but did not prove that the Android accessibility
layer completed the gestures on Google Maps profile/close buttons.
This addresses the missing diagnostic evidence **without inventing** a
touch coordinate offset or claiming that third-party Google Maps reacted.

Current road-test statuses:
- OEM GPS producer: LIVE CONFIRMED on FT166 (512+ valid frames; OEM nav activation reversible).
- Android Auto vehicle LOCATION subscription: NOT CONFIRMED; three phone sessions
  requested driving-status/night only. No unsupported message injection.
- Mirrored Google Maps profile/close: not accepted; requires callback telemetry
  and physical re-test.
- AA Yandex Maps long press: known unsupported Android Auto application gesture,
  not a proven FT UIBC defect.
- AA Yandex keyboard while P: need real driving/Park state agreement, do not
  falsify safety state or bypass driver distraction enforcement.

Build gate: existing GitHub Actions owner-signed release, JUnit regression tests
for AA service-discovery DPI field, one-time migration, viewport safety and
retained sensor advertisement. APK is an experimental field candidate, not
vehicle P0 acceptance. Both Nexus plans remain distinct, combined HU acceptance
at end of approved work, without advancing protected CURRENT_ITEM/NEXT_EDGE.
