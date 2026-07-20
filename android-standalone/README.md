# Sonos Lyrics — Standalone Android app

A **fully self-contained** version of Sonos Lyrics that runs entirely on an
Android device — **no separate server required**. Unlike the thin WebView
wrapper in [`../android`](../android), this app embeds the whole backend:

- An on-device HTTP server (NanoHTTPD, bound to `127.0.0.1`) reimplements the
  Node backend's `/api/*` endpoints natively in Kotlin.
- **Sonos discovery** via SSDP multicast (using a Wi‑Fi `MulticastLock`), with
  the full zone-group topology read straight from the speakers' UPnP/SOAP
  interface (port `1400`).
- **Now-playing + lyrics** are fetched natively (lrclib.net synced lyrics with
  a lyrics.ovh plain fallback), exactly like the server version.
- The **existing web UI** (`../public`) is served verbatim from app assets, so
  the album-art + karaoke-lyrics experience is identical.

The device only needs to be on the **same Wi‑Fi network as the Sonos speakers**.

## How it works

```
WebView  →  http://127.0.0.1:<port>/           (UI + /api/*, same origin)
                     │
       EmbeddedServer (NanoHTTPD, Kotlin)
                     │
      ┌──────────────┼───────────────────────────┐
      │              │                            │
  SSDP discovery  Sonos SOAP (port 1400)     lrclib.net / lyrics.ovh
  (MulticastLock) GetZoneGroupState /         (synced + plain lyrics)
                  GetPositionInfo /
                  GetTransportInfo
```

Because the page and its API share the `127.0.0.1` origin, there are no CORS
issues. Album art loads cross-origin directly from each speaker (`<img>` tags
don't need CORS).

## Getting the APK

### Option A — GitHub Actions (no local tooling)

Pushes touching `android-standalone/` or `public/` trigger the **Build
Standalone Android APK** workflow
(`.github/workflows/android-standalone.yml`). You can also run it manually:

1. Repo **Actions** tab → **Build Standalone Android APK** → **Run workflow**.
2. When it finishes, download the **`sonos-lyrics-standalone-debug-apk`**
   artifact and unzip to get `app-debug.apk`.

### Option B — Build locally

Requires JDK 17 and the Android SDK. From the `android-standalone/` folder:

```bash
gradle assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

The build automatically copies `../public` into the app's assets
(`copyWebAssets` task), so the UI stays a single source of truth.

## Installing on a device (sideload)

1. Copy `app-debug.apk` to the device (USB, cloud drive, or `adb install`).
2. Enable **Install unknown apps** for your file manager.
3. Tap the APK to install, then launch **Sonos Lyrics Local**.

It installs alongside the WebView-wrapper app (different application ID), so you
can keep both.

## Settings

**Long-press the top-left corner** of the screen to open settings:

- **Speaker IPs (optional)** — comma-separated static speaker IPs. Leave blank
  to auto-discover via SSDP. Set these if multicast is unreliable on your
  network (the app will talk to the speakers directly over unicast instead).
- **Keep screen on** — hold the screen awake while in the foreground.
- **Reload** — reload the UI.

## Permissions

- `INTERNET` / `ACCESS_NETWORK_STATE` — talk to speakers + lyrics services.
- `ACCESS_WIFI_STATE` / `CHANGE_WIFI_MULTICAST_STATE` — acquire the
  `MulticastLock` needed for SSDP discovery.
- Cleartext HTTP is enabled (loopback + speakers use plain HTTP on the LAN).

## Notes / limitations

- `minSdk 26` (Android 8.0+).
- SSDP multicast can be filtered on some Wi‑Fi networks / AP isolation setups;
  if discovery finds nothing, enter your speaker IPs in settings.
- Only third-party dependency is NanoHTTPD; everything else is the Android
  framework + Kotlin.
