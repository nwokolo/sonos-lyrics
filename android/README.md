# Sonos Lyrics — Android app

A minimal, fullscreen **WebView wrapper** around the Sonos Lyrics web app. It
shows no browser chrome (no address bar, no buttons) — just the immersive
album-art + karaoke-lyrics UI. Perfect for wall-mounted tablets and spare
Android devices around the home.

It does **not** reimplement any Sonos/lyrics logic — it simply displays the
site served by your always-on Sonos Lyrics server (see the Proxmox deploy guide
in [`../deploy/DEPLOY.md`](../deploy/DEPLOY.md)).

## Features

- **Fullscreen immersive** (sticky) — system bars hidden, no browser UI.
- **Landscape-locked** (`sensorLandscape`) — optimized for tablets, works both
  ways up.
- **Keep screen on** while the app is in the foreground (toggleable).
- **Auto-reconnect** — if the server is briefly unreachable (reboot, network
  blip), it retries every 5 seconds until the page loads.
- **Per-device server URL** — a default is baked in
  (`http://192.168.86.127:3000`); override it on any device via the hidden
  settings dialog: **long-press the top-left corner** of the screen.
- No third-party dependencies; pure Android framework + WebView.

## Getting the APK

### Option A — GitHub Actions (no local tooling needed)

Every push that touches `android/` triggers the **Build Android APK** workflow
(`.github/workflows/android.yml`). You can also run it manually:

1. Go to the repo's **Actions** tab → **Build Android APK** → **Run workflow**.
2. When it finishes, download the **`sonos-lyrics-debug-apk`** artifact.
3. Unzip to get `app-debug.apk`.

### Option B — Build locally

Requires JDK 17 and the Android SDK (e.g. via Android Studio). From the
`android/` folder:

```bash
# If you have Android Studio, just open the android/ folder and Build > APK.
# Or from the command line with a Gradle install:
gradle assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Installing on a device (sideload)

1. Copy `app-debug.apk` to the device (USB, cloud drive, or `adb install`).
2. On the device, enable **Install unknown apps** for your file manager.
3. Tap the APK to install, then launch **Sonos Lyrics**.

Because this is a debug-signed APK, no Play Store or signing setup is required —
ideal for personal home deployment.

## Changing the server URL on a device

**Long-press the top-left corner** of the screen to open settings, enter the URL
of your Sonos Lyrics server (e.g. `http://192.168.86.127:3000`), and tap **Save**.
The same dialog has a **Keep screen on** toggle and a **Reload** button.

## Notes

- Cleartext HTTP is allowed so it can reach the server over your LAN.
- `minSdk 26` (Android 8.0+).
- The app must be on the same network as the server to reach it.
