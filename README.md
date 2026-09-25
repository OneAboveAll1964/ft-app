# FT

FT turns an Android phone into the phone side of Baidu CarLife over WiFi, so a Chinese-market head unit projects a dedicated FT launcher onto the car display. It embeds an Android Auto head-unit receiver so real Android Auto can render into that projected screen with one button, and it can mirror any phone app to the car with the car touchscreen driving it.

## How it connects

Four steps, all automatic:

1. **Bluetooth wakes the car.** When the phone's Bluetooth connects to the head unit, the unit raises its WiFi Direct group. FT starts itself on that Bluetooth connection.
2. **WiFi Direct.** FT finds the group, joins it (or lets the unit's own invitation complete), and brings up the CarLife listener on that link.
3. **Discovery beacon.** The vehicle side learns the phone's address from a UDP datagram on port 7999 and only then connects to the phone's six ports. FT sends that beacon to the group owner and to every interface broadcast every 1.5 s until the head unit connects.
4. **Handshake.** After the protocol match FT keeps the unit's video-channel watchdog fed with heartbeats, asks for the feature list, and if the unit wants content encryption it negotiates it: the unit's RSA public key wraps a fresh AES session key, and from then on commands, touch and video are AES-encrypted exactly as the vehicle library expects. Units that never announce encryption but withhold the video setup are probed for it after 2.5 s.

Nothing is shown on the phone beyond the ongoing notification.

- **Auto-connect** (the switch on Home) keeps a small foreground service that searches in short bursts, joins the car when it appears, and also starts at boot and when the phone's Bluetooth connects to the car.
- The first time, **Nearby** lists the WiFi Direct devices FT sees. Tap **Use** on the car once; it is remembered. Leaving the name blank matches anything with "CarLife" in its name.
- **Listen only** keeps the listener and the beacon up on the network the phone is already on, for head units that reach the phone over a normal WiFi or hotspot connection.
- **Forget** clears the remembered car and searches again.

## On the car screen

Tiles, an app drawer, and the **Android Auto** button. The FT pill in the top-left corner always returns to the launcher. Tiles open an app on the phone and mirror it to the car; grant **Screen mirror**, **Touch control**, and **Display over apps** on Home once to make that interactive.

## Android Auto

The head unit speaks CarLife, not Android Auto, so FT puts **your own phone's** Android Auto on the car by launching it and projecting it through the CarLife video channel with the car touchscreen driving it. Home has a single control: **Start Android Auto**, plus a switch to **start it automatically after connection**. It needs the same three grants as the app tiles.

## Diagnostics

Every protocol message is logged with hex on the Log tab, so a real head unit's behaviour is visible on the first connection.

## Test harness

`tools/sim/` has a CarLife head-unit simulator and an Android Auto phone simulator that speak the real wire protocols (the AA simulator is a TLS server that requires the head-unit certificate; the CarLife simulator can demand content encryption and does the RSA/AES exchange with the system `openssl`, holds the video setup back to require heartbeats first, and validates the Bluetooth pair reply). `tools/sim/e2e.sh [apk] [outdir]` installs the app on a connected emulator, drives consent, runs a plain and an encrypted head unit plus the AA phone through a touch sequence, checks the phone UI navigation, decodes the projected H.264 to PNGs, and prints PASS/FAIL per check.

## Building

Gradle wrapper, AGP 8.13, Kotlin 2.3, Compose Material 3 Expressive. `ft-release.jks` / `keystore.properties` sign debug and release. `./gradlew assembleRelease` produces `app/build/outputs/apk/release/app-release.apk`.
