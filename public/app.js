const deviceSelect = document.getElementById('deviceSelect');
const refreshBtn = document.getElementById('refreshBtn');
const albumArt = document.getElementById('albumArt');
const bgArt = document.getElementById('bgArt');
const titleEl = document.getElementById('title');
const artistEl = document.getElementById('artist');
const albumEl = document.getElementById('album');
const progressBar = document.getElementById('progressBar');
const timeEl = document.getElementById('time');
const lineIndicator = document.getElementById('lineIndicator');
const lyricsEl = document.getElementById('lyrics');
const lyricsViewport = document.getElementById('lyricsViewport');

let pollTimer = null;
let tickTimer = null;
let pollInFlight = false;
let currentTrackKey = null;
let localPosition = 0;
let localDuration = 0;
let isPlaying = false;
let userChoseDevice = false;

// Position interpolation anchor: true position ~= posAnchor + elapsed since timeAnchor
let posAnchor = 0;
let timeAnchor = 0;
// Sync offset (seconds) added when selecting the active lyric line.
// Compensates for Sonos flooring position to whole seconds + fetch delay.
// User-tunable with Left/Right arrows; persisted in localStorage.
let syncOffset = parseFloat(localStorage.getItem('syncOffset'));
if (isNaN(syncOffset)) syncOffset = 0.5;

// Synced lyrics state
let syncedLines = null;      // [{ time, text }]
let activeLineIndex = -1;

function hmsToSeconds(str) {
  if (typeof str === 'number') return str;
  if (!str) return 0;
  const parts = String(str).split(':').map(Number);
  return parts.reduce((acc, p) => acc * 60 + (isNaN(p) ? 0 : p), 0);
}

function secondsToHms(total) {
  total = Math.max(0, Math.floor(total));
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}:${String(s).padStart(2, '0')}`;
}

// Re-anchor interpolation to a freshly reported position.
function setAnchor(positionSeconds) {
  posAnchor = positionSeconds;
  timeAnchor = performance.now();
}

// Current interpolated playback position (seconds).
function currentPosition() {
  let pos = posAnchor;
  if (isPlaying) pos += (performance.now() - timeAnchor) / 1000;
  if (localDuration > 0) pos = Math.min(pos, localDuration);
  return Math.max(0, pos);
}

function updateProgressUI() {
  if (localDuration > 0) {
    progressBar.style.width = `${Math.min(100, (localPosition / localDuration) * 100)}%`;
    timeEl.textContent = `${secondsToHms(localPosition)} / ${secondsToHms(localDuration)}`;
  } else {
    progressBar.style.width = '0%';
    timeEl.textContent = '';
  }
}

function setBackground(url) {
  if (url) {
    bgArt.style.backgroundImage = `url("${url}")`;
  } else {
    bgArt.style.backgroundImage = '';
  }
}

// ---- Lyrics rendering ----
function renderSyncedLyrics(lines) {
  syncedLines = lines;
  activeLineIndex = -1;
  lyricsEl.classList.add('synced');
  lyricsEl.innerHTML = '';
  lines.forEach((line, i) => {
    const div = document.createElement('div');
    div.className = 'lyric-line';
    div.dataset.index = i;
    div.textContent = line.text || '\u266a';
    lyricsEl.appendChild(div);
  });
  lyricsViewport.scrollTop = 0;
}

function renderPlainLyrics(text) {
  syncedLines = null;
  activeLineIndex = -1;
  lineIndicator.textContent = '';
  lyricsEl.classList.remove('synced');
  lyricsEl.textContent = text;
  lyricsViewport.scrollTop = 0;
}

function centerLine(el) {
  const lineRect = el.getBoundingClientRect();
  const vpRect = lyricsViewport.getBoundingClientRect();
  const delta = (lineRect.top - vpRect.top) - (lyricsViewport.clientHeight / 2 - el.clientHeight / 2);
  lyricsViewport.scrollTo({ top: lyricsViewport.scrollTop + delta, behavior: 'smooth' });
}

function updateActiveLyricLine() {
  if (!syncedLines || syncedLines.length === 0) return;
  const t = localPosition + syncOffset;
  let idx = -1;
  for (let i = 0; i < syncedLines.length; i++) {
    if (syncedLines[i].time <= t) idx = i;
    else break;
  }
  if (idx === activeLineIndex) return;
  activeLineIndex = idx;

  const nodes = lyricsEl.children;
  for (let i = 0; i < nodes.length; i++) {
    const el = nodes[i];
    el.classList.remove('active', 'near');
    if (i === idx) el.classList.add('active');
    else if (Math.abs(i - idx) === 1) el.classList.add('near');
  }

  lineIndicator.textContent = idx >= 0 ? `line ${idx + 1} / ${syncedLines.length}` : '';

  if (idx >= 0 && nodes[idx]) centerLine(nodes[idx]);
}

function startTicking() {
  if (tickTimer) clearInterval(tickTimer);
  // High-frequency interpolation for low-latency, smooth highlighting.
  tickTimer = setInterval(() => {
    if (localDuration <= 0) return;
    localPosition = currentPosition();
    updateProgressUI();
    updateActiveLyricLine();
  }, 100);
}

async function loadDevices(refresh = false) {
  try {
    // Prefer zone groups; fall back to individual speakers.
    let entries = [];
    try {
      const gres = await fetch(`/api/groups${refresh ? '?refresh=1' : ''}`);
      const gdata = await gres.json();
      if (gdata.groups && gdata.groups.length) {
        entries = gdata.groups.map((g) => ({ host: g.host, name: g.name }));
      }
    } catch (_) { /* fall back below */ }

    if (entries.length === 0) {
      const res = await fetch(`/api/devices${refresh ? '?refresh=1' : ''}`);
      const data = await res.json();
      entries = (data.devices || []).map((d) => ({ host: d.host, name: d.name }));
    }

    const prev = deviceSelect.value;
    deviceSelect.innerHTML = '';
    if (entries.length === 0) {
      const opt = document.createElement('option');
      opt.textContent = 'No speakers found';
      opt.value = '';
      deviceSelect.appendChild(opt);
      renderPlainLyrics('No Sonos speakers found on this network. Click \u21bb to re-scan.');
      return;
    }
    for (const e of entries) {
      const opt = document.createElement('option');
      opt.value = e.host;
      opt.textContent = e.name;
      deviceSelect.appendChild(opt);
    }
    if (prev && entries.some((e) => e.host === prev)) {
      deviceSelect.value = prev;
    }
    if (!userChoseDevice) {
      await autoSelectPlaying();
    }
    startPolling();
  } catch (err) {
    renderPlainLyrics(`Error loading speakers: ${err.message}`);
  }
}

async function autoSelectPlaying() {
  try {
    const res = await fetch('/api/playing');
    const data = await res.json();
    if (data.host) {
      const has = Array.from(deviceSelect.options).some((o) => o.value === data.host);
      if (has) {
        deviceSelect.value = data.host;
        currentTrackKey = null;
      }
    }
  } catch (_) { /* ignore */ }
}

async function fetchLyrics(artist, title, album, duration) {
  renderPlainLyrics('Loading lyrics...');
  try {
    const params = new URLSearchParams({ artist, title });
    if (album) params.set('album', album);
    if (duration) params.set('duration', String(Math.round(duration)));
    const res = await fetch(`/api/lyrics?${params.toString()}`);
    const data = await res.json();

    if (data.synced && data.synced.length) {
      renderSyncedLyrics(data.synced);
      updateActiveLyricLine();
    } else if (data.plain) {
      renderPlainLyrics(data.plain);
    } else {
      renderPlainLyrics(`No lyrics found for "${title}" by ${artist}.`);
    }
  } catch (err) {
    renderPlainLyrics(`Error loading lyrics: ${err.message}`);
  }
}

function renderNothing() {
  titleEl.textContent = 'Nothing playing';
  artistEl.textContent = '';
  albumEl.textContent = '';
  albumArt.src = '/idle-art.svg';
  setBackground(null);
  localDuration = 0;
  localPosition = 0;
  isPlaying = false;
  setAnchor(0);
  updateProgressUI();
  if (currentTrackKey !== null) {
    renderPlainLyrics('Nothing playing on this speaker.');
    currentTrackKey = null;
  }
}

async function poll() {
  const host = deviceSelect.value;
  if (!host) return;
  if (pollInFlight) return; // never let requests pile up and exhaust the connection pool
  pollInFlight = true;
  try {
    const res = await fetch(`/api/nowplaying?host=${encodeURIComponent(host)}`, {
      signal: AbortSignal.timeout(8000),
    });
    const data = await res.json();
    if (data.error) return;

    isPlaying = data.state === 'playing';

    if (!data.track || (!data.track.title && !data.track.artist)) {
      renderNothing();
      return;
    }

    const t = data.track;
    titleEl.textContent = t.title || 'Unknown title';
    artistEl.textContent = t.artist || '';
    albumEl.textContent = t.album || '';

    if (t.albumArt) {
      if (albumArt.getAttribute('src') !== t.albumArt) {
        albumArt.src = t.albumArt;
        setBackground(t.albumArt);
      }
    } else {
      albumArt.src = '/idle-art.svg';
      setBackground(null);
    }

    localDuration = hmsToSeconds(t.duration);
    localPosition = hmsToSeconds(t.position);
    setAnchor(localPosition);
    updateProgressUI();

    const key = `${t.artist}||${t.title}`;
    if (key !== currentTrackKey && t.artist && t.title) {
      currentTrackKey = key;
      fetchLyrics(t.artist, t.title, t.album, localDuration);
    } else {
      updateActiveLyricLine();
    }
  } catch (err) {
    // transient network errors / timeouts are ignored; next poll retries
  } finally {
    pollInFlight = false;
  }
}

function startPolling() {
  if (pollTimer) clearInterval(pollTimer);
  poll();
  pollTimer = setInterval(poll, 3000);
}

deviceSelect.addEventListener('change', () => {
  userChoseDevice = true;
  currentTrackKey = null;
  startPolling();
});

refreshBtn.addEventListener('click', () => loadDevices(true));

// ---- Sync offset fine-tuning (Left/Right arrows) ----
let toastTimer = null;
function showSyncToast() {
  let toast = document.getElementById('syncToast');
  if (!toast) {
    toast = document.createElement('div');
    toast.id = 'syncToast';
    toast.className = 'sync-toast';
    document.body.appendChild(toast);
  }
  const sign = syncOffset >= 0 ? '+' : '';
  toast.textContent = `Lyric sync ${sign}${syncOffset.toFixed(1)}s`;
  toast.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toast.classList.remove('show'), 1400);
}

function nudgeSync(delta) {
  syncOffset = Math.round((syncOffset + delta) * 10) / 10;
  localStorage.setItem('syncOffset', String(syncOffset));
  activeLineIndex = -2; // force re-evaluation on next tick
  updateActiveLyricLine();
  showSyncToast();
}

document.addEventListener('keydown', (e) => {
  if (e.target && /^(INPUT|SELECT|TEXTAREA)$/.test(e.target.tagName)) return;
  if (e.key === 'ArrowRight') { nudgeSync(0.1); e.preventDefault(); }
  else if (e.key === 'ArrowLeft') { nudgeSync(-0.1); e.preventDefault(); }
  else if (e.key === '0') { syncOffset = 0.5; localStorage.setItem('syncOffset', '0.5'); activeLineIndex = -2; updateActiveLyricLine(); showSyncToast(); }
});

startTicking();
loadDevices();
