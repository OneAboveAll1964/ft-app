# FT168 — GPS on FT launcher and separate GearInfo telemetry

Parent signed FT167: 7093022070d8c0d59b2b7fd8733f64487a9bc35d.
Owner HU report 2026-11-02 demonstrated 1024 valid OEM GPS packets while
Android Auto projection was active; car's FT home still displayed NO FIX.

Confirmed cause in FT167 code: the OEM Control11 navigation/sensor activation
is issued only if Android Auto is connected or actual AA guidance is active.
ICCOA GearInfo is separately decoded, but its gear enum was previously
discarded, and its raw speed overwrote GPS speedRaw.

Changes:
* Reversible OEM sensor activation when the real ICCOA FT video projection
  starts; turn off/restore actual navigation state when the ICCOA session ends.
  This permits reception of car GPS while FT's own widgets are displayed.
* Preserve and log the raw OEM GearInfo enum and independent raw speed with
  fresh timestamp. Do NOT assert unverified enum-to-P mapping or assume
  a speed multiplier; do NOT label raw speed km/h.
* Distinguish raw gear speed from GPS speed and preserve recent GearInfo
  across incoming GPS frames; regression unit tests added.
* No spoofing stationary / permanent P while moving. This cannot safely
  remove Android Auto/third-party driver-distraction limits.
* The Android Auto phone-side LOCATION sensor subscription remains missing.
  The AA GPS source problem is not claimed as solved.
* All FT164–FT167 video, touch, audio, identity and DPI changes retained.

Physical acceptance still required for HU live FT-home GPS, exact OEM speed
unit, gear enum P value, Android Auto navigation GPS and mirror audio.
Owner should compare FT home before AA and after opening AA; report
ICCOA GPS count, GPS last-fix age, raw GearInfo and the car's indicated
speed with a saved FT-report ZIP. Do not log precise coordinates by default.

No plan edge advance / no new hardware access / no HU modification.
