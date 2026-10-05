# FT

FT turns an Android phone into the phone side of Baidu CarLife over WiFi, so a Chinese-market head unit projects FT's own launcher onto the car display. It embeds an Android Auto head-unit receiver so the phone's real Android Auto can be shown on that screen with one button, and it can mirror any phone app to the car with the car's touchscreen driving it.

FT speaks CarLife the way the official Baidu CarLife app does: the same handshake order, the same picture size, frame rate and data rate for each car, the same way of starting, pausing and resuming the picture, and the same media and navigation messages around sound. Where Baidu's choices can be changed, the defaults are Baidu's and the settings can change them.

## How it connects

Two ways, picked on Home:

- **Hotspot** (default). The car joins the phone's hotspot. FT listens on the CarLife ports and sends a discovery beacon on UDP 7999 until the car connects.
- **WiFi + BL**. Bluetooth wakes the car, the car raises its WiFi Direct group, FT joins it and the car connects over WiFi Direct.

Then the CarLife handshake, as Baidu does it:

1. FT answers the protocol version, tells the car it is in front and whether the phone screen is on, sends its device info and, half a second later, the module list.
2. The car's statistics are answered with the authentication result and a request for its feature list. If the car wants content encryption, its RSA key wraps a fresh AES session key and everything after that is encrypted.
3. The car's data subscription is answered: FT offers song info.
4. On `VIDEO_ENCODER_INIT` FT picks the stream size from Baidu's table (1280×720 for a 1920×720 car like the Corolla) and replies with the size it really sends, so touches land where they should. FT itself is drawn at the car's full size and squeezed into the stream, so it is not stretched on the car.

## Picture

- 20 frames a second to start, then whatever the car asks for between 3 and 30.
- H.264 Baseline, a full picture every second, variable bit rate from Baidu's table (2.56 Mbps for 1280×720), Baidu's per-chip encoder limits.
- The codec header goes out once, with the first full picture.
- While the car shows its own screens, only heartbeats go out. When it comes back, FT waits for the next full picture after at least ten dropped ones instead of forcing a new one.
- Video timestamps are in seconds, as Baidu sends them.

## Sound

- Car sound goes out as 48 kHz stereo on the media channel. FT's own player, Android Auto's media and other apps' sound are mixed together.
- When sound starts FT tells the car the way Baidu does: `MEDIA_INIT`, `MEDIA_STOP`, `MEDIA_INIT` and the music module switched on. After 750 ms of quiet it sends `MEDIA_PAUSE` and switches the music module off.
- Spoken directions go to the car's own voice channel and the navigation module is raised around them, so the car decides how the music sits underneath (Baidu's way). **In step** and **On time** in Settings make FT mix or dip the music itself instead.
- Sound sockets are marked for the WiFi voice queue and the picture for the video queue, so sound goes first.

## Touch

Every CarLife touch message is understood: touch actions, down, up and move, single and double clicks, long presses and two-finger gestures. Moves are merged so the car screen never falls behind a finger, and touches are mapped from the stream to FT's full-size screen.

## On the car screen

Tiles for Music, Videos, YouTube, Maps, the browser and all apps, plus the **Android Auto** button. The FT button in a corner always returns to the launcher. Tiles that open phone apps mirror them to the car; allow screen sharing, **Touch control** and display over apps once on Home. The browser keeps its page, history and full-screen video for the whole drive, and the steering wheel's back key walks back through it.

## Android Auto

The head unit speaks CarLife, not Android Auto, so FT shows **your own phone's** Android Auto on the car: it runs an Android Auto head unit inside FT, asks Android Auto to project into it, and draws that picture on the car screen with the car's touchscreen driving it. Home starts it, and it can start by itself after the car connects.

## Settings

Six pages: car screen, picture, sound, car and connection, Android Auto, and advanced (the CarLife ports). **Picture** can send the car's own size or a custom size, fix the frame rate, set the data rate and the quality floor, and put everything back to the defaults.

## Diagnostics

Every protocol message is logged with its bytes on the Log tab, so a head unit's behaviour is visible on the first connection.

## Test harness

`tools/sim/` has a CarLife head-unit simulator and an Android Auto phone simulator that speak the real wire protocols. The CarLife simulator checks Baidu's handshake order, the stream size in `INIT_DONE`, the codec header, pause and resume, the media and navigation modules, and it can demand content encryption. `tools/sim/e2e.sh [apk] [outdir]` installs FT on a connected emulator and runs plain, encrypted and Corolla-shaped head units and the Android Auto phone through touches, music, video, directions and screen sharing, then prints PASS or FAIL for each check.

## Building

Gradle wrapper, AGP 8.13, Kotlin 2.3, Compose Material 3 Expressive. `./gradlew assembleRelease` builds `app/build/outputs/apk/release/app-release.apk`, signed with the keystore named in `keystore.properties`.

## Credit and license

FT is made by [OneAboveAll1964](https://github.com/OneAboveAll1964).

It is released under the [Apache License, Version 2.0](LICENSE). You may use, change and share it, including in your own apps, as long as you keep the [NOTICE](NOTICE) file and credit OneAboveAll1964 with a link to https://github.com/OneAboveAll1964 in any fork, copy or app built from it, and say what you changed.
