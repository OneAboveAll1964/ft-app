# FT

FT turns an Android phone into the phone side of Baidu CarLife over WiFi, so a Chinese-market head unit projects a dedicated FT launcher onto the car display. It embeds an Android Auto head-unit receiver so real Android Auto can render into that projected screen with one button, and it can mirror any phone app to the car with the car touchscreen driving it.

## How it connects

The head unit advertises a WiFi Direct group. FT finds it, joins it (push button, or a PIN if the unit asks for one), and brings up the CarLife listener on that link; the head unit then connects to the phone and the session starts. Nothing is shown on the phone beyond the ongoing notification.

- **Auto-connect** (the switch on Home) keeps a small foreground service that searches in short bursts, joins the car when it appears, and also starts at boot and when the phone's Bluetooth connects to the car.
- The first time, **Nearby** lists the WiFi Direct devices FT sees. Tap **Use** on the car once; it is remembered. Leaving the name blank matches anything with "CarLife" in its name.
- **Listen only** keeps the listener up on the network the phone is already on, for head units that reach the phone over a normal WiFi or hotspot connection.

## On the car screen

Tiles, an app drawer, and the **Android Auto** button. The FT pill in the top-left corner always returns to the launcher. Tiles open an app on the phone and mirror it to the car; grant **Screen mirror**, **Touch control**, and **Display over apps** on Home once to make that interactive.

## Android Auto

FT is a full head-unit receiver (aasdk-compatible wire format, TLS with the Google-issued head-unit certificate, service discovery, sensors, video, input, ping). A phone running Android Auto connects over TCP 5277; the Bluetooth handoff advertises this head unit to a second phone.

## Diagnostics

Every protocol message is logged with hex on the Log tab, so a real head unit's behaviour is visible on the first connection.

## Test harness

`tools/sim/` has a CarLife head-unit simulator and an Android Auto phone simulator that speak the real wire protocols (the AA simulator is a TLS server that requires the head-unit certificate). `tools/sim/e2e.sh [apk] [outdir]` installs the app on a connected emulator, drives consent, runs both simulators through a touch sequence, checks the phone UI navigation, decodes the projected H.264 to PNGs, and prints PASS/FAIL per check.

## Building

Gradle wrapper, AGP 8.13, Kotlin 2.3, Compose Material 3 Expressive. `ft-release.jks` / `keystore.properties` sign debug and release. `./gradlew assembleRelease` produces `app/build/outputs/apk/release/app-release.apk`.
