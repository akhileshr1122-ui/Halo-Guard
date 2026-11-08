# Halo Guard

A personal Android privacy guard (Halo Apps) with three tabs:

**Apps:** which apps can read your photos, how much each one uploads (and how much of that
happens while you aren't using it), and which tracking/ad SDKs are built in. You get an hourly
background check with a "Stop using X?" alert when an app that can see your photos uploads a lot
in the background.

**Photo trail:** where your data ends up. A local, DNS-only VPN records which servers each app
contacts (e.g. `rupload.facebook.com → Meta (USA)`). It matches those servers against each app's
upload bursts, so you can see which companies received an app's uploads. It can also block
known tracker domains (it replaces Hush, because Android allows only one VPN at a time).

**Photo lock:** for apps you choose, photo/video/all-files access is removed a few seconds
after you leave the app. Removing the permission also stops the app, so background uploads stop too.
When you open the app again it either asks you again or gets access back automatically (you choose).
This needs [Shizuku](https://shizuku.rikka.app/) because Android doesn't let one app change another
app's permissions without root.

Everything stays on the phone. The INTERNET permission is used only to forward the phone's own
DNS lookups while Photo trail tracking is on.

## Build
Push to `main` → Actions → "Build APK" → download the `HaloGuard-apk` artifact.

## Setup on the phone
1. Install the APK. Open it → allow **Usage access** and notifications.
2. Photo trail → **Start tracking** → accept the VPN prompt. In Settings → Connections → More
   connection settings: set Private DNS to Off/Automatic. Under VPN → Halo Guard ⚙, turn on Always-on.
3. Photo lock → install Shizuku → start it via Wireless debugging → allow Halo Guard → lock apps.
   After every restart, open Shizuku and tap Start again.
4. Battery → Halo Guard → Unrestricted.

## Limits
Android encrypts app traffic, so Halo Guard can show which company and server received an
app's uploads, but not the photos themselves. Upload amounts come from Android's counters
in about 2-hour blocks. Lookups an app makes over its own encrypted DNS (DoH) aren't seen.
