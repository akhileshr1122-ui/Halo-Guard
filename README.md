# Halo Guard

A personal Android privacy monitor (Halo Apps). It shows which apps can read your photos,
which ones upload data off your phone (especially in the background), and which have
tracking/advertising SDKs built in. It runs a background check every hour and sends you an
alert ("Stop using X?") when an app that can see your photos uploads a lot while you aren't
using it.

- No INTERNET permission, no third-party libraries. Nothing it sees can leave the phone.
- No VPN needed, so it works alongside Hush (Android allows only one VPN at a time).
- Min Android 10, targets Android 15.

## Build
Push to a GitHub repo → Actions → "Build APK" → download the `HaloGuard-apk` artifact.

## First run on the phone
1. Install the APK (allow install from unknown sources).
2. Open Halo Guard → tap **Open Usage access settings** → switch Halo Guard on.
3. Allow notifications.
4. Tip: lock Halo Guard in Recents / set Battery to "Unrestricted" so Samsung doesn't stop the hourly check.

## Limits
Android encrypts each app's traffic and isolates apps from each other, so no app can see
*what* another app uploads. Halo Guard shows how much, when, what the app is allowed to read,
and which trackers it contains. Tracker detection is based on declared components.
