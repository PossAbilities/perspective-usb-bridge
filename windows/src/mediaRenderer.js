'use strict';

/**
 * Phase 2: decode the tablet's stream and measure it.
 *
 * WebCodecs gives hardware-accelerated H.264 with no native code, so the
 * latency question gets answered before any COM work on the virtual camera.
 */

const $ = s => document.querySelector(s);
const canvas = $('#preview');
const context = canvas.getContext('2d');

const TYPE_VIDEO_CONFIG = 1;
const TYPE_VIDEO_FRAME = 2;
const TYPE_AUDIO_CONFIG = 3;
const TYPE_AUDIO_FRAME = 4;

let decoder = null;
let videoConfig = null;
let waitingForKeyframe = true;

let audio = null;
let audioFormat = { sampleRate: 48000, channels: 1, bitsPerSample: 16 };
let audioPlayhead = 0;

const stats = {
  frames: 0,
  decoded: 0,
  dropped: 0,
  bytes: 0,
  since: Date.now(),
  decodeTimes: [],
  arrivalGaps: [],
  lastArrival: 0,
  baseOffset: null,
  drift: 0
};

/**
 * state: 'idle' | 'busy' | 'ok' | 'live' | 'error', or a boolean for the
 * older error/not-error call sites.
 */
function setStatus(text, state = 'ok') {
  if (state === true) state = 'error';
  if (state === false) state = 'ok';
  const el = $('#mediaStatus');
  el.textContent = text;
  el.title = text;
  el.dataset.state = state;
}

function showPicture(on) {
  $('#stage').classList.toggle('empty', !on);
}

// ------------------------------------------------------------------ decoding

/**
 * Derive the WebCodecs codec string from the SPS. Mirrors avcCodecString in
 * mediaClient.js; duplicated because the renderer has no Node require.
 */
function codecStringFromConfig(bytes) {
  for (let i = 0; i + 4 < bytes.length; i++) {
    const short = bytes[i] === 0 && bytes[i + 1] === 0 && bytes[i + 2] === 1;
    const long = bytes[i] === 0 && bytes[i + 1] === 0 && bytes[i + 2] === 0 && bytes[i + 3] === 1;
    if (!short && !long) continue;
    const start = i + (long ? 4 : 3);
    if ((bytes[start] & 0x1f) !== 7) continue; // 7 = SPS
    if (start + 3 >= bytes.length) break;
    const hex = v => v.toString(16).padStart(2, '0');
    return `avc1.${hex(bytes[start + 1])}${hex(bytes[start + 2])}${hex(bytes[start + 3])}`;
  }
  return 'avc1.42e01f';
}

function hasParameterSets(bytes) {
  for (let i = 0; i + 4 < bytes.length; i++) {
    const short = bytes[i] === 0 && bytes[i + 1] === 0 && bytes[i + 2] === 1;
    const long = bytes[i] === 0 && bytes[i + 1] === 0 && bytes[i + 2] === 0 && bytes[i + 3] === 1;
    if (!short && !long) continue;
    if ((bytes[i + (long ? 4 : 3)] & 0x1f) === 7) return true;
  }
  return false;
}

async function startDecoder(config) {
  if (decoder) { try { decoder.close(); } catch { /* already closed */ } }
  videoConfig = config;
  waitingForKeyframe = true;

  decoder = new VideoDecoder({
    output: frame => {
      stats.decoded++;
      const submitted = pending.get(frame.timestamp);
      if (submitted !== undefined) {
        stats.decodeTimes.push(performance.now() - submitted);
        if (stats.decodeTimes.length > 120) stats.decodeTimes.shift();
        pending.delete(frame.timestamp);
      }
      if (canvas.width !== frame.displayWidth || canvas.height !== frame.displayHeight) {
        canvas.width = frame.displayWidth;
        canvas.height = frame.displayHeight;
      }
      context.drawImage(frame, 0, 0);
      frame.close();
      if (stats.decoded === 1) {
        showPicture(true);
        setStatus(`Live · ${canvas.width}×${canvas.height}`, 'live');
      }
    },
    error: e => setStatus(`Picture error: ${e.message}`, true)
  });

  const codec = codecStringFromConfig(config);
  const settings = { codec, optimizeForLatency: true };
  const support = await VideoDecoder.isConfigSupported(settings).catch(() => null);
  if (!support || !support.supported) {
    setStatus(`This computer cannot decode the tablet's video (${codec}).`, true);
    return;
  }
  decoder.configure(settings);
  setStatus('Starting picture…', 'busy');
}

const pending = new Map();

function decodeVideo(frame) {
  if (!decoder || decoder.state !== 'configured') return;
  // A decoder must start at a keyframe, so discard anything before the first.
  if (waitingForKeyframe && !frame.keyframe) { stats.dropped++; return; }
  waitingForKeyframe = false;

  let data = new Uint8Array(frame.payload);
  if (frame.keyframe && videoConfig && !hasParameterSets(data)) {
    const merged = new Uint8Array(videoConfig.length + data.length);
    merged.set(videoConfig, 0);
    merged.set(data, videoConfig.length);
    data = merged;
  }
  pending.set(frame.timestampUs, performance.now());
  try {
    decoder.decode(new EncodedVideoChunk({
      type: frame.keyframe ? 'key' : 'delta',
      timestamp: frame.timestampUs,
      data
    }));
  } catch (error) {
    setStatus(`Picture error: ${error.message}`, true);
    waitingForKeyframe = true;
  }
}

// --------------------------------------------------------------------- audio

/** Names of virtual audio devices that conferencing apps can use as a microphone. */
const VIRTUAL_SINK = /blackhole|loopback|soundflower|vb-?cable|cable input|virtual/i;
const SINK_NONE = 'none';
const SINK_DEFAULT = 'default';

function readSetting(key) {
  try { return localStorage.getItem(key); } catch { return null; }
}

function writeSetting(key, value) {
  try { localStorage.setItem(key, value); } catch { /* storage unavailable */ }
}

function selectedSink() {
  return $('#audioSink').value || SINK_NONE;
}

async function listSinks() {
  const select = $('#audioSink');
  const previous = select.value || readSetting('audioSink');
  let outputs = [];
  try {
    outputs = (await navigator.mediaDevices.enumerateDevices()).filter(d => d.kind === 'audiooutput');
  } catch { /* no device list: offer the fallbacks below */ }

  select.textContent = '';
  const add = (value, label) => {
    const option = document.createElement('option');
    option.value = value;
    option.textContent = label;
    select.append(option);
  };
  add(SINK_NONE, 'Nowhere (video only)');
  outputs
    .filter(d => d.deviceId && d.deviceId !== 'default' && d.deviceId !== 'communications')
    .forEach((d, i) => add(d.deviceId, d.label || `Audio output ${i + 1}`));
  add(SINK_DEFAULT, 'System default output (testing only)');

  const virtual = outputs.find(d => VIRTUAL_SINK.test(d.label));
  const keep = previous && [...select.options].some(o => o.value === previous);
  select.value = keep ? previous : (virtual ? virtual.deviceId : SINK_NONE);

  const hint = $('#audioHint');
  hint.dataset.original ??= hint.innerHTML;
  hint.classList.toggle('warn', !virtual);
  if (virtual) hint.innerHTML = hint.dataset.original;
  else {
    hint.textContent =
      'No virtual audio device found. Install BlackHole 2ch (Mac, free) or VB-CABLE (Windows, free) ' +
      'and it will appear here. Choose it here and as the microphone in your call app.';
  }
  await applySink();
}

async function applySink() {
  const sink = selectedSink();
  writeSetting('audioSink', sink);
  if (!audio) return;
  try {
    if (sink === SINK_NONE) await audio.suspend();
    else {
      if (typeof audio.setSinkId === 'function') {
        await audio.setSinkId(sink === SINK_DEFAULT ? '' : sink);
      }
      await audio.resume();
    }
    audioPlayhead = audio.currentTime;
  } catch (error) {
    setStatus(`Could not use that audio output: ${error.message}`, true);
  }
}

/** Never let the microphone run more than this far behind real time. */
const MAX_AUDIO_LEAD = 0.15;

function playAudio(payload) {
  if (selectedSink() === SINK_NONE) return;
  if (!audio) {
    const sink = selectedSink();
    // Pass the device at construction so not even the first chunk reaches the
    // speakers, where Parsec would carry it back to the tablet as an echo.
    audio = new AudioContext({
      sampleRate: audioFormat.sampleRate,
      latencyHint: 'interactive',
      ...(sink === SINK_DEFAULT ? {} : { sinkId: sink })
    });
    audioPlayhead = audio.currentTime;
  }
  const samples = payload.byteLength / 2;
  if (samples === 0) return;
  const view = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);
  const buffer = audio.createBuffer(audioFormat.channels, samples, audioFormat.sampleRate);
  const channel = buffer.getChannelData(0);
  for (let i = 0; i < samples; i++) channel[i] = view.getInt16(i * 2, true) / 32768;

  const source = audio.createBufferSource();
  source.buffer = buffer;
  source.connect(audio.destination);
  // Keep a small lead so scheduling jitter does not cause gaps.
  // The tablet's and this computer's clocks differ slightly, so drop back to
  // real time rather than let lip-sync delay build up over a long call.
  const now = audio.currentTime;
  if (audioPlayhead < now + 0.02 || audioPlayhead > now + MAX_AUDIO_LEAD) audioPlayhead = now + 0.02;
  source.start(audioPlayhead);
  audioPlayhead += buffer.duration;
}

// ---------------------------------------------------------------- statistics

function recordArrival(frame) {
  stats.frames++;
  stats.bytes += frame.payload.byteLength;

  if (stats.lastArrival) {
    stats.arrivalGaps.push(frame.receivedAt - stats.lastArrival);
    if (stats.arrivalGaps.length > 120) stats.arrivalGaps.shift();
  }
  stats.lastArrival = frame.receivedAt;

  // The two clocks share no epoch, so the absolute offset is meaningless. Its
  // *change* is not: a rising figure means the pipeline is accumulating delay.
  const offset = frame.receivedAt - frame.timestampUs / 1000;
  if (stats.baseOffset === null) stats.baseOffset = offset;
  stats.drift = offset - stats.baseOffset;
}

function mean(values) {
  return values.length ? values.reduce((a, b) => a + b, 0) / values.length : 0;
}

function render() {
  const seconds = (Date.now() - stats.since) / 1000;
  const gaps = stats.arrivalGaps;
  const jitter = gaps.length > 1
    ? Math.sqrt(mean(gaps.map(g => (g - mean(gaps)) ** 2)))
    : 0;

  const set = (id, text, alert = false) => {
    const el = $(id);
    el.textContent = text;
    el.classList.toggle('alert', alert);
  };
  const live = stats.frames > 0;
  set('#mSize', live ? `${canvas.width}×${canvas.height}` : '—');
  set('#mFps', live && seconds > 0 ? `${(stats.frames / seconds).toFixed(1)} fps` : '—');
  set('#mRate', live && seconds > 0 ? `${((stats.bytes * 8) / seconds / 1e6).toFixed(2)} Mbps` : '—');
  set('#mDecode', stats.decodeTimes.length ? `${mean(stats.decodeTimes).toFixed(1)} ms` : '—');
  set('#mJitter', gaps.length > 1 ? `${jitter.toFixed(1)} ms` : '—');
  set('#mDrift', live ? `${stats.drift >= 0 ? '+' : ''}${stats.drift.toFixed(0)} ms` : '—', Math.abs(stats.drift) > 250);
  set('#mDropped', live ? String(stats.dropped) : '—');
}

setInterval(render, 500);

// ------------------------------------------------------------------- wiring

window.media.onAccepted(info => {
  setStatus(`Connected · ${info.width}×${info.height} at ${info.frameRate} fps`, 'busy');
  canvas.width = info.width;
  canvas.height = info.height;
  Object.assign(stats, {
    frames: 0, decoded: 0, dropped: 0, bytes: 0, since: Date.now(),
    decodeTimes: [], arrivalGaps: [], lastArrival: 0, baseOffset: null, drift: 0
  });
});

window.media.onFrame(frame => {
  const payload = frame.payload instanceof Uint8Array ? frame.payload : new Uint8Array(frame.payload);
  const normalised = { ...frame, payload };
  switch (frame.type) {
    case TYPE_VIDEO_CONFIG:
      startDecoder(payload);
      break;
    case TYPE_VIDEO_FRAME:
      recordArrival(normalised);
      decodeVideo(normalised);
      break;
    case TYPE_AUDIO_CONFIG:
      if (payload.byteLength >= 6) {
        const view = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);
        audioFormat = {
          sampleRate: view.getUint32(0, false),
          channels: view.getUint8(4),
          bitsPerSample: view.getUint8(5)
        };
      }
      break;
    case TYPE_AUDIO_FRAME:
      playAudio(payload);
      break;
  }
});

// Keep reconnecting while the user wants a connection: the tablet drops the
// stream if Wi-Fi blips or Android briefly pauses the service behind Parsec.
let wantConnected = false;
let reconnectTimer = null;

function scheduleReconnect() {
  if (!wantConnected || reconnectTimer) return;
  reconnectTimer = setTimeout(() => { reconnectTimer = null; connect(); }, 3000);
}

/** Socket errors are terse; say what the person can do about them. */
function explain(message) {
  const text = String(message || '');
  if (/ECONNREFUSED/i.test(text)) return 'Tablet not sharing. Tap Share camera & microphone on it.';
  if (/ETIMEDOUT|EHOSTUNREACH|ENETUNREACH|in time/i.test(text)) return 'No reply from the tablet. Same Wi-Fi?';
  if (/ENOTFOUND|EINVAL/i.test(text)) return 'That address does not look right.';
  return text;
}

function updateConnectButton() {
  const button = $('#mediaConnect');
  button.textContent = wantConnected ? 'Disconnect' : 'Connect';
  button.classList.toggle('secondary', wantConnected);
}

window.media.onError(message => {
  setStatus(wantConnected ? `${explain(message)} Retrying…` : explain(message), 'error');
  scheduleReconnect();
});
window.media.onClosed(() => {
  showPicture(false);
  $('#placeholderTitle').textContent = wantConnected ? 'Reconnecting to your tablet…' : 'Waiting for your tablet';
  setStatus(wantConnected ? 'Reconnecting…' : 'Disconnected', wantConnected ? 'busy' : 'idle');
  scheduleReconnect();
});
window.media.onDiscovered(host => {
  const input = $('#mediaHost');
  if (!input.value.trim()) input.value = host;
  $('#mediaHint').textContent = `Tablet found on your Wi-Fi at ${host}.`;
});

const selectedSize = () => document.querySelector('input[name="size"]:checked').value;

async function connect() {
  const host = $('#mediaHost').value.trim();
  if (!host) {
    wantConnected = false;
    updateConnectButton();
    $('#mediaHost').focus();
    return setStatus('Enter the address shown on the tablet', 'error');
  }
  writeSetting('mediaHost', host);
  const [width, height] = selectedSize().split('x').map(Number);
  writeSetting('mediaSize', selectedSize());
  writeSetting('mediaFront', $('#mediaFront').checked ? '1' : '0');
  $('#mediaConnect').disabled = true;
  setStatus('Connecting…', 'busy');
  try {
    await window.media.connect(host, {
      width, height, frameRate: 30, audio: true, frontCamera: $('#mediaFront').checked
    });
  } catch (error) {
    setStatus(wantConnected ? `${explain(error.message)} Retrying…` : explain(error.message), 'error');
    scheduleReconnect();
  } finally {
    $('#mediaConnect').disabled = false;
  }
}

async function disconnect() {
  wantConnected = false;
  updateConnectButton();
  clearTimeout(reconnectTimer);
  reconnectTimer = null;
  await window.media.disconnect();
  showPicture(false);
  $('#placeholderTitle').textContent = 'Waiting for your tablet';
  setStatus('Not connected', 'idle');
}

$('#mediaConnect').addEventListener('click', () => {
  if (wantConnected) return disconnect();
  wantConnected = true;
  updateConnectButton();
  clearTimeout(reconnectTimer);
  reconnectTimer = null;
  connect();
});
$('#mediaHost').addEventListener('keydown', e => {
  if (e.key === 'Enter' && !wantConnected) $('#mediaConnect').click();
});

$('#audioSink').addEventListener('change', applySink);
navigator.mediaDevices.addEventListener('devicechange', listSinks);

// Camera output view: only the picture, so OBS can capture this window and
// present it to Zoom, Teams or FaceTime through OBS Virtual Camera.
function setCleanView(on) {
  document.body.classList.toggle('clean', on);
}
$('#cleanView').addEventListener('click', () => setCleanView(true));
$('#preview').addEventListener('dblclick', () => setCleanView(!document.body.classList.contains('clean')));
document.addEventListener('keydown', e => { if (e.key === 'Escape') setCleanView(false); });

$('#mediaHost').value = readSetting('mediaHost') || '';
{
  const size = document.querySelector(`input[name="size"][value="${readSetting('mediaSize')}"]`);
  if (size) size.checked = true;
  if (readSetting('mediaFront') === '0') $('#mediaBack').checked = true;
}
listSinks();
