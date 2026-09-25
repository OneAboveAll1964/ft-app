# FT

FT turns an Android phone into the "phone side" of Baidu CarLife so a Chinese-market head unit projects a dedicated FT launcher onto the car display, and it embeds an Android Auto head-unit receiver so real Android Auto can render into that projected screen with one button.

## What it does

- Speaks the Baidu CarLife phone protocol over **wired USB** (AOA accessory, one multiplexed pipe) and **WiFi** (the phone listens on the CarLife command/video/media/TTS/VR/touch ports; the head unit connects).
- Renders the car UI on a **private virtual display** the head unit's negotiated size, encodes it to H.264 and streams it. The phone screen stays free.
- Routes head-unit touches back into that UI, into Android Auto, or into a mirrored phone app.
- **Android Auto**: FT is a full head-unit receiver (aasdk-compatible wire format, TLS with the Google-issued head-unit certificate, service discovery, sensors, video, input, ping). Tap **Android Auto** on the car screen; a phone running Android Auto connects over TCP 5277 (wireless flow with Bluetooth handoff for a second phone).
- **Phone apps on the car**: tiles open an app on the phone and mirror it to the car; the accessibility touch injector makes the car touchscreen control it.
- **Diagnostics**: every protocol message with hex, live in the app, so a real head unit's behaviour is visible on the first connection.

## Using it

1. Install the APK, open FT once, grant the notification/Bluetooth prompts.
2. Tap **Allow mirror** and **Enable touch** (Accessibility → FT car touch) if you want phone apps on the car screen. **Display over apps** lets tiles launch apps while FT is in the background.
3. Wired: plug the phone into the head unit's USB-C port; when the unit switches the phone into CarLife accessory mode FT opens and projects. Wireless: join the same network, tap **Listen on WiFi**; the phone IP and ports are shown for the head unit.
4. On the car screen: tiles, **All apps**, the **Android Auto** button. The **FT** pill in the top-left corner always returns to the launcher.

## Settings

Ports, forced resolution/fps, Android Auto video size and port, tile packages, auto-start, and a developer "USB simulator link" used by the test harness.

## Test harness

`tools/sim/` contains a CarLife head-unit simulator and an Android Auto phone simulator that speak the real wire protocols (the AA simulator is a TLS server that requires the head-unit certificate). `tools/sim/e2e.sh [apk] [outdir]` installs the app on a connected emulator, runs both simulators against it, drives a touch sequence, decodes the projected H.264 to PNGs, and prints PASS/FAIL per check.

## Building

Gradle wrapper, AGP 8.13, Kotlin 2.3, Compose Material 3 Expressive. `ft-release.jks` / `keystore.properties` sign both debug and release builds. `./gradlew assembleRelease` produces `app/build/outputs/apk/release/app-release.apk`.
