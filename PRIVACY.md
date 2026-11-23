# FT privacy policy

Effective 21 November 2026

FT is an app by Shko Maghdid ([OneAboveAll1964](https://github.com/OneAboveAll1964)) that shows your Android phone on your car's screen through Baidu CarLife or ICCOA Carlink, including your phone's Android Auto. This policy covers the Google Play edition (`com.shkomaghdid.ft`) and the GitHub edition (`app.ft`).

## The short version

- FT has no account, no ads, no analytics and no crash reporting, and it contains no third-party code that collects data.
- What FT reads on your phone is used on your phone, or sent over the local connection to your own car.
- Only two things ever leave your phone over the internet: a rounded location for the weather widget, and a diagnostic report if you choose to share one.
- Nothing is sold, and nothing is shared with advertisers or data brokers.

## What FT uses and why

| What | Why | Where it goes |
| --- | --- | --- |
| Nearby devices, Bluetooth and WiFi (including your car's Bluetooth name and address) | To find your car, connect to it over Bluetooth, WiFi Direct, the phone's hotspot or USB, and start FT when the car connects | Stays on your phone; the connection is to your own car |
| Location | Android requires it for WiFi Direct on Android 12 and older; the speed and weather widgets on the car screen use it | Stays on your phone, except the rounded location for the weather widget (see below) |
| Microphone and the phone's sound | To pass your voice to the assistant in Android Auto and share your phone's sound with the car | Sent only to your car or to Android Auto on your phone; never recorded or stored |
| Music and videos on your phone | To play them from the car screen | Read on your phone and played to your car |
| Calendar | To show your next event in the calendar widget, only if you add it | Stays on your phone and your car's screen |
| Screen sharing (media projection) | To show your phone screen or its sound on the car when you ask; Android asks you each time | Sent only to your car |
| List of installed apps | To show your apps on the car's launcher | Stays on your phone |
| Car profiles, settings and the Log | So FT remembers each car and can help diagnose connection problems | Stays on your phone |
| Security keys for ICCOA cars | To reconnect to a car you have paired | Stored in Android's protected key store on your phone |

## Accessibility services

FT includes two optional accessibility services. FT explains each one and asks for your agreement before you turn it on, and you can turn either off at any time in Android's accessibility settings.

- **FT car touch.** When your phone screen or a phone app is shown on the car, this service turns taps and swipes on the car's touchscreen into gestures on your phone, and lets the car's buttons press Back, Home and Recent apps and open notifications. It can see which window is on screen so touches land in the right place. It is used only while your phone is shown on the car.
- **FT — Android Auto server.** When you start Android Auto from FT, this service opens Android Auto's settings, reads that screen to find "Start head unit server", selects it and returns to FT. It works only inside the Android Auto app, only when you ask, and for at most eight seconds.

Neither service collects, stores or shares anything it sees, and nothing they see leaves your phone.

## What leaves your phone over the internet

- **Weather.** If you show the weather widget on the car screen, FT sends your location to [Open-Meteo](https://open-meteo.com/en/terms) over an encrypted connection to get the current temperature and conditions. The Google Play edition rounds the location to about one kilometre first. FT sends nothing else with it, and keeps nothing from the reply except the forecast shown on the car. Turn the widget off and nothing is sent.
- **Diagnostic reports.** The report button on the Log tab saves a zip with FT's log, your phone's model and Android version, and Android's system log. It stays on your phone until you choose to share it, with whoever you choose.
- **Links.** Links you open from FT, such as this policy or the source code, open in your browser.

## Your car

When FT is connected, it sends your car the picture, sound and information needed to show your phone on the car's screen, such as song titles and navigation directions, and it receives the car's touches, buttons, microphone, speed and position. This happens over the direct connection between your phone and your own car, not over the internet.

## Keeping and deleting data

Everything FT keeps is stored in its own private storage on your phone. You can clear the Log from the Log tab, delete car profiles in My cars, or remove everything by clearing FT's storage in Android settings or uninstalling FT. FT holds no copy anywhere else, so there is nothing else to delete.

## Children

FT is a tool for drivers and is not directed at children under 13.

## Changes

If this policy changes, the new version is published at this address with a new effective date. The full history is public in this repository.

## Contact

Questions about privacy: [open an issue](https://github.com/OneAboveAll1964/ft-app/issues) or use the contact email on FT's Google Play listing.
