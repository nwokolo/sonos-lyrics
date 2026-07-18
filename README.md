# Sonos Lyrics

A lightweight local web app that shows the album art and **synced, karaoke-style lyrics** for whatever is currently playing on your Sonos speakers. Point it at your network, open it in a browser, and it auto-selects the speaker (or group) that's playing.

![Sonos Lyrics](public/idle-art.svg)

## Features

- **Auto-discovery** of all Sonos speakers on your local network.
- **Group awareness** — grouped speakers are collapsed into a single entry (e.g. *"Living Room + Kitchen + Hallway"*); standalone rooms are listed individually.
- **Auto-select the playing speaker/group** on load, so you rarely have to pick manually.
- **Synced lyrics** from [lrclib.net](https://lrclib.net) (LRC time-tagged) with a plain-text fallback from [lyrics.ovh](https://lyrics.ovh).
- **Immersive karaoke UI** — large centered album art, blurred ambient background, active-line highlight, smooth auto-scroll, and a "line X / Y" indicator.
- **Low-latency lyric sync** — 100 ms position interpolation with a user-tunable offset (arrow keys `←` / `→` to nudge ±0.1 s, `0` to reset; persisted in `localStorage`).
- **Idle state** shows a clean Sonos logo when nothing is playing.

## Requirements

- [Node.js](https://nodejs.org/) 18+ (uses the built-in `fetch` and `AbortSignal.timeout`).
- A Sonos system on the same local network as the machine running the app.

## Getting started

```bash
git clone https://github.com/nwokolo/sonos-lyrics.git
cd sonos-lyrics
npm install
npm start
```

Then open **http://localhost:3000** in your browser.

To reach it from other machines on your LAN, use the host machine's IP, e.g. `http://192.168.1.50:3000` (make sure your firewall allows inbound TCP on port 3000).

### Configuration

| Env var | Default | Description        |
| ------- | ------- | ------------------ |
| `PORT`  | `3000`  | HTTP port to serve on |

## How it works

The backend (`server.js`, Express) discovers speakers with the [`sonos`](https://www.npmjs.com/package/sonos) package and exposes a small JSON API:

| Endpoint              | Description                                                                 |
| --------------------- | --------------------------------------------------------------------------- |
| `GET /api/devices`    | All discovered speakers (`?refresh=1` to re-scan).                          |
| `GET /api/groups`     | Zone-group topology (grouped speakers collapsed into one coordinator entry).|
| `GET /api/playing`    | The coordinator host of whichever group is currently playing.               |
| `GET /api/nowplaying?host=` | Current track (title/artist/album/art/position) for a speaker; resolves group followers to their coordinator. |
| `GET /api/lyrics?artist=&title=&album=&duration=` | Synced + plain lyrics.               |

All Sonos and external calls are wrapped with timeouts so a slow/flaky speaker can never hang a request. The frontend polls `/api/nowplaying` every 3 s with an in-flight guard so requests never pile up.

## Running it in the background (Windows)

To keep the server always available, register it as a logon task that auto-restarts on failure. A hidden launcher (`start-sonos-hidden.vbs`) runs `node server.js` with no console window; a Windows Scheduled Task with an *At log on* trigger and restart-on-failure settings keeps it alive.

## Always-on deployment (Proxmox / Linux)

For a reliable always-on setup that survives reboots (recommended over a laptop/desktop that may sleep), run it as a systemd service in a Proxmox LXC container. See [`deploy/DEPLOY.md`](deploy/DEPLOY.md) for step-by-step instructions and the [`deploy/sonos-lyrics.service`](deploy/sonos-lyrics.service) unit file.

> Sonos discovery uses SSDP multicast, so the container must share the same L2 network/VLAN as your speakers.

## Android app

A fullscreen **Android WebView app** (no browser chrome) is available in [`android/`](android/) for easy deployment on wall-mounted tablets and spare devices. It's landscape-optimized, keeps the screen on, auto-reconnects, and points at your Sonos Lyrics server (per-device URL via a hidden long-press settings dialog). A GitHub Actions workflow builds the installable APK for you — see [`android/README.md`](android/README.md).

## Tech

- Node.js + Express (CommonJS)
- [`sonos`](https://www.npmjs.com/package/sonos) for discovery and UPnP control
- Vanilla HTML/CSS/JS frontend (no build step)
- Lyrics: lrclib.net (synced) → lyrics.ovh (plain)

## License

MIT
