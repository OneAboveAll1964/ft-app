# FT166 XA60 — controlled OEM GPS activation + AA top-corner touch correction

Date: 2026-11-02. Commit is based directly on accepted signed-source FT165
`5ad963685dd73e8d3dfd842824020216148df9ae`. All FT164 and FT165 changes
are retained, including signed-package continuity, GPS privacy, strict ICCOA
port/speed parsing, and the mirror atomic-tap fix.

## Physical source evidence

Drive: `OpenIVI/FT-report-samsung-SM-S916B-20261102-112823.zip`,
SHA-256 `2f44d76de3816304396f50ecadf6a8ec07021750f9410dc3b240f62f8da63060`.

On 2026-11-02 the connected HU sent at least 256 GearInfo frames over
ICCOA sensor lane 57239, **zero GPS frames**, and received three
`NotifyNavigationInfo(isNavigating=false)` messages. AA requested sensor
types 13 (driving status) and 10 (night), but never LOCATION=1. No GPS
decoder change can manufacture data not transmitted.

The same FT163 car log has six touches `route=FT` near the upper-left
screen while AA was displayed; touch interception by FT's return pill is
a concrete failure mode distinct from global calibration error.

## Deliberate changes

1. OEM SensorMgr activation: only when AA reports a live connected
   projection, send existing ControlIndex method 11 with isNavigating=true
   as a bounded sensor activation **hypothesis**. Use the original actual
   guided-route NavigationInfo fields unmodified; on AA disconnect send
   its actual state again (typically false). Do not activate on plain
   phone mirroring or merely starting the FT launcher.
   The head unit might interpret this as a navigation indication even
   before actual route guidance; this is P1 experimental behavior.
2. Android Auto top-corner obstruction: relocate FT's existing return
   pill to the corresponding **bottom** corner in AA mode when its
   configured position was a top corner, leaving mirror configuration
   unchanged. Its tap zone follows the actual rendered control.
   Do not modify four-point calibration or change raw UIBC coordinates.
3. Add regression unit tests for reversible OEM activation and corner
   mapping. Preserve FT165 diagnostic warnings for absent OEM GPS packets
   and missing AA LOCATION subscription.

## Outstanding acceptance

- Unit tests + build + signing can establish release reproducibility.
- Only an owner HU field test can establish whether HU begins transmitting
  actual GPS and whether AA subscribes to LOCATION. With zero OEM GPS or
  no Android Auto subscription, GPS remains **not accepted**.
- Screen X/close in Android Auto, mirror apps, audible guidance, codec,
  reconnect/auth, and coordinate CRS remain open to the owner's combined
  physical acceptance.
- Do not claim PINless resume or phone-side GPS replacement as proven.
