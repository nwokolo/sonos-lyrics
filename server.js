// lrclib.net (and some lyrics hosts) publish AAAA records that are not
// routable from this network; Node's default DNS order tries IPv6 first and
// stalls before falling back to IPv4. Force IPv4-first so lyrics fetches don't
// eat a multi-second IPv6 stall on top of the already-high IPv4 latency.
require('dns').setDefaultResultOrder('ipv4first');

const path = require('path');
const express = require('express');
const { AsyncDeviceDiscovery, Sonos } = require('sonos');

const app = express();
const PORT = process.env.PORT || 3000;

// Race a promise against a timeout so a hung Sonos/UPnP call can never block
// a request forever (which would otherwise exhaust the browser's connection
// pool and make the page appear frozen).
function withTimeout(promise, ms, label = 'operation') {
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(`${label} timed out after ${ms}ms`)), ms);
  });
  return Promise.race([Promise.resolve(promise), timeout]).finally(() => clearTimeout(timer));
}

// Cache of discovered devices: host -> { name, host, uuid }
let deviceCache = [];

async function discoverDevices(timeout = 5000) {
  const discovery = new AsyncDeviceDiscovery();
  let found = [];
  try {
    found = await discovery.discoverMultiple({ timeout });
  } catch (err) {
    console.error('Discovery error:', err.message);
    return deviceCache; // fall back to whatever we already have
  }

  const results = [];
  const seen = new Set();
  for (const device of found) {
    if (seen.has(device.host)) continue;
    seen.add(device.host);
    let name = device.host;
    let uuid = null;
    try {
      name = await withTimeout(device.getName(), 3000, 'getName');
    } catch (_) { /* ignore */ }
    try {
      const desc = await withTimeout(device.deviceDescription(), 3000, 'deviceDescription');
      uuid = desc && desc.UDN ? desc.UDN.replace('uuid:', '') : null;
    } catch (_) { /* ignore */ }
    results.push({ name, host: device.host, uuid });
  }

  results.sort((a, b) => a.name.localeCompare(b.name));
  if (results.length) deviceCache = results;
  return deviceCache;
}

function albumArtUrl(host, track) {
  if (!track) return null;
  const uri = track.albumArtURI || track.albumArtURL;
  if (!uri) return null;
  if (/^https?:\/\//i.test(uri)) return uri;
  return `http://${host}:1400${uri.startsWith('/') ? '' : '/'}${uri}`;
}

// Extract the host/IP from a Sonos member Location URL.
function hostFromLocation(loc) {
  try {
    return new URL(loc).hostname;
  } catch {
    return null;
  }
}

// Build the current Sonos zone-group topology.
// Returns [{ host (coordinator), coordinatorUuid, name, memberCount, members }].
async function getGroups() {
  if (deviceCache.length === 0) await discoverDevices();
  if (deviceCache.length === 0) return [];

  let raw = null;
  for (const d of deviceCache) {
    try {
      raw = await withTimeout(new Sonos(d.host).getAllGroups(), 4000, 'getAllGroups');
      if (raw && raw.length) break;
    } catch (_) { /* try next device */ }
  }
  if (!raw || !raw.length) return [];

  const groups = raw
    .map((g) => {
      const members = (g.ZoneGroupMember || [])
        .map((m) => ({ uuid: m.UUID, name: m.ZoneName || 'Speaker', host: hostFromLocation(m.Location) }))
        .filter((m) => m.host);
      if (!members.length) return null;

      const coord = members.find((m) => m.uuid === g.Coordinator) || members[0];
      const others = members
        .filter((m) => m.uuid !== coord.uuid)
        .sort((a, b) => a.name.localeCompare(b.name));

      const label = others.length
        ? `${coord.name} + ${others.map((o) => o.name).join(' + ')}`
        : coord.name;

      return {
        host: coord.host,
        coordinatorUuid: coord.uuid,
        name: label,
        memberCount: members.length,
        members: [coord.name, ...others.map((o) => o.name)],
      };
    })
    .filter(Boolean);

  groups.sort((a, b) => a.name.localeCompare(b.name));
  return groups;
}

// Parse an LRC-formatted string into a sorted array of { time, text }.
function parseLrc(lrc) {
  if (!lrc) return null;
  const lines = lrc.replace(/\r\n/g, '\n').split('\n');
  const out = [];
  const tagRe = /\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?\]/g;
  for (const line of lines) {
    tagRe.lastIndex = 0;
    const stamps = [];
    let m;
    while ((m = tagRe.exec(line)) !== null) {
      const min = parseInt(m[1], 10);
      const sec = parseInt(m[2], 10);
      const frac = m[3] ? parseInt(m[3].padEnd(3, '0'), 10) / 1000 : 0;
      stamps.push(min * 60 + sec + frac);
    }
    if (!stamps.length) continue;
    const text = line.replace(tagRe, '').trim();
    for (const t of stamps) out.push({ time: t, text });
  }
  if (!out.length) return null;
  out.sort((a, b) => a.time - b.time);
  return out;
}

// Try lrclib.net for synced + plain lyrics; fall back to lyrics.ovh (plain).
// lrclib is geographically distant (~5-6s round trips from here), so results
// are cached per song and the fetch timeout is generous to avoid aborting a
// slow-but-successful synced-lyrics response.
const lyricsCache = new Map(); // key -> { synced, plain, source, at }
const LYRICS_TTL_MS = 60 * 60 * 1000; // 1 hour
const LYRICS_FETCH_MS = 12000;

async function getLyrics({ artist, title, album, duration }) {
  const key = [artist, title, album || '', duration ? Math.round(duration) : ''].join('|').toLowerCase();
  const cached = lyricsCache.get(key);
  if (cached && Date.now() - cached.at < LYRICS_TTL_MS && (cached.synced || cached.plain)) {
    return cached;
  }

  const result = await fetchLyrics({ artist, title, album, duration });
  if (result.synced || result.plain) {
    lyricsCache.set(key, { ...result, at: Date.now() });
  }
  return result;
}

async function fetchLyrics({ artist, title, album, duration }) {
  // 1) lrclib.net exact get (best when duration is known)
  try {
    const params = new URLSearchParams({ artist_name: artist, track_name: title });
    if (album) params.set('album_name', album);
    if (duration) params.set('duration', String(Math.round(duration)));
    const r = await fetch(`https://lrclib.net/api/get?${params.toString()}`, {
      headers: { 'User-Agent': 'sonos-lyrics (local home app)' },
      signal: AbortSignal.timeout(LYRICS_FETCH_MS),
    });
    if (r.ok) {
      const d = await r.json();
      const synced = parseLrc(d.syncedLyrics);
      const plain = (d.plainLyrics || '').trim() || null;
      if (synced || plain) return { synced, plain, source: 'lrclib' };
    }
  } catch (_) { /* ignore, try search */ }

  // 2) lrclib.net search fallback
  try {
    const params = new URLSearchParams({ track_name: title, artist_name: artist });
    const r = await fetch(`https://lrclib.net/api/search?${params.toString()}`, {
      headers: { 'User-Agent': 'sonos-lyrics (local home app)' },
      signal: AbortSignal.timeout(LYRICS_FETCH_MS),
    });
    if (r.ok) {
      const arr = await r.json();
      const hit = Array.isArray(arr) && arr.find((x) => x.syncedLyrics || x.plainLyrics);
      if (hit) {
        const synced = parseLrc(hit.syncedLyrics);
        const plain = (hit.plainLyrics || '').trim() || null;
        if (synced || plain) return { synced, plain, source: 'lrclib' };
      }
    }
  } catch (_) { /* ignore, try lyrics.ovh */ }

  // 3) lyrics.ovh plain fallback
  try {
    const url = `https://api.lyrics.ovh/v1/${encodeURIComponent(artist)}/${encodeURIComponent(title)}`;
    const r = await fetch(url, { signal: AbortSignal.timeout(LYRICS_FETCH_MS) });
    if (r.ok) {
      const d = await r.json();
      const plain = (d.lyrics || '').replace(/\r\n/g, '\n').trim() || null;
      if (plain) return { synced: null, plain, source: 'lyrics.ovh' };
    }
  } catch (_) { /* ignore */ }

  return { synced: null, plain: null, source: null };
}

app.use(express.static(path.join(__dirname, 'public')));

// List discovered Sonos devices (cached; use ?refresh=1 to re-scan)
app.get('/api/devices', async (req, res) => {
  try {
    if (req.query.refresh || deviceCache.length === 0) {
      await discoverDevices();
    }
    res.json({ devices: deviceCache });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// List Sonos zone groups (grouped speakers collapsed into one entry).
app.get('/api/groups', async (req, res) => {
  try {
    if (req.query.refresh) await discoverDevices();
    const groups = await getGroups();
    res.json({ groups });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// Resolve a grouped follower to its coordinator's host.
// A follower's currentTrack URI looks like "x-rincon:RINCON_XXXX01400".
function resolveCoordinatorHost(track, requestHost) {
  const uri = track && track.uri ? String(track.uri) : '';
  const m = uri.match(/^x-rincon:(RINCON_[0-9A-Fa-f]+)/);
  if (!m) return requestHost;
  const uuid = m[1];
  const coord = deviceCache.find((d) => d.uuid === uuid);
  return coord ? coord.host : requestHost;
}

// What is playing on a given speaker (?host=192.168.x.x)
app.get('/api/nowplaying', async (req, res) => {
  const host = req.query.host;
  if (!host) return res.status(400).json({ error: 'Missing host query parameter' });

  try {
    let device = new Sonos(host);
    let [track, state] = await Promise.all([
      withTimeout(device.currentTrack(), 4000, 'currentTrack').catch(() => null),
      withTimeout(device.getCurrentState(), 4000, 'getCurrentState').catch(() => 'unknown'),
    ]);

    // If this speaker is a group follower, read metadata from the coordinator.
    let sourceHost = host;
    const coordHost = track ? resolveCoordinatorHost(track, host) : host;
    if (coordHost !== host) {
      sourceHost = coordHost;
      device = new Sonos(coordHost);
      [track, state] = await Promise.all([
        withTimeout(device.currentTrack(), 4000, 'currentTrack').catch(() => null),
        withTimeout(device.getCurrentState(), 4000, 'getCurrentState').catch(() => state),
      ]);
    }

    if (!track) {
      return res.json({ state, track: null });
    }

    res.json({
      state,
      grouped: sourceHost !== host,
      track: {
        title: track.title || null,
        artist: track.artist || null,
        album: track.album || null,
        duration: track.duration || 0,
        position: track.position || 0,
        albumArt: albumArtUrl(sourceHost, track),
      },
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// Fetch lyrics: lrclib.net (synced+plain) then lyrics.ovh (plain)
// ?artist=&title=&album=&duration=(seconds)
app.get('/api/lyrics', async (req, res) => {
  const { artist, title, album } = req.query;
  const duration = req.query.duration ? Number(req.query.duration) : undefined;
  if (!artist || !title) {
    return res.status(400).json({ error: 'Missing artist or title' });
  }
  try {
    const result = await getLyrics({ artist, title, album, duration });
    res.json({
      synced: result.synced,
      plain: result.plain,
      source: result.source,
      notFound: !result.synced && !result.plain,
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// Playback control removed — UI is focused on album art + lyrics.

// Find the group currently playing (returns coordinator host for auto-select)
app.get('/api/playing', async (req, res) => {
  try {
    const groups = await getGroups();
    if (groups.length) {
      const checks = await Promise.all(
        groups.map(async (g) => {
          try {
            const state = await withTimeout(new Sonos(g.host).getCurrentState(), 4000, 'getCurrentState');
            return { host: g.host, name: g.name, state };
          } catch (_) {
            return { host: g.host, name: g.name, state: 'unknown' };
          }
        })
      );
      const playing = checks.find((c) => c.state === 'playing');
      return res.json({ host: playing ? playing.host : null, name: playing ? playing.name : null });
    }

    // Fallback: individual devices
    if (deviceCache.length === 0) await discoverDevices();
    const checks = await Promise.all(
      deviceCache.map(async (d) => {
        try {
          const state = await withTimeout(new Sonos(d.host).getCurrentState(), 4000, 'getCurrentState');
          return { host: d.host, name: d.name, state };
        } catch (_) {
          return { host: d.host, name: d.name, state: 'unknown' };
        }
      })
    );
    const playing = checks.find((c) => c.state === 'playing');
    res.json({ host: playing ? playing.host : null, name: playing ? playing.name : null });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.listen(PORT, () => {
  console.log(`Sonos Lyrics running at http://localhost:${PORT}`);
  console.log('Scanning for Sonos devices on your network...');
  discoverDevices().then((d) => {
    console.log(`Found ${d.length} Sonos device(s):`, d.map((x) => x.name).join(', ') || '(none)');
  });
});

// Keep the server alive and observable if an unexpected async error slips through.
process.on('unhandledRejection', (reason) => {
  console.error('Unhandled promise rejection:', reason && reason.message ? reason.message : reason);
});
process.on('uncaughtException', (err) => {
  console.error('Uncaught exception:', err && err.message ? err.message : err);
});
