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

function setStatus(text, error = false) {
  const el = $('#mediaStatus');
  el.textContent = text;
  el.style.color = error ? '#F4592B' : '#CFE96A';
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
    },
    error: e => setStatus(`Decoder error: ${e.message}`, true)
  });

  const codec = codecStringFromConfig(config);
  const settings = { codec, optimizeForLatency: true };
  const support = await VideoDecoder.isConfigSupported(settings).catch(() => null);
  if (!support || !support.supported) {
    setStatus(`This PC cannot decode ${codec}.`, true);
    return;
  }
  decoder.configure(settings);
  setStatus(`Decoding ${codec}.`);
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
    setStatus(`Decode failed: ${error.message}`, true);
    waitingForKeyframe = true;
  }
}

// --------------------------------------------------------------------- audio

function playAudio(payload) {
  if (!audio) {
    audio = new AudioContext({ sampleRate: audioFormat.sampleRate, latencyHint: 'interactive' });
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
  const now = audio.currentTime;
  if (audioPlayhead < now + 0.02) audioPlayhead = now + 0.02;
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

  $('#stats').textContent = [
    `frames received   ${stats.frames}`,
    `frames decoded    ${stats.decoded}${stats.dropped ? `  (${stats.dropped} dropped before first keyframe)` : ''}`,
    `receive rate      ${seconds > 0 ? (stats.frames / seconds).toFixed(1) : '0.0'} fps`,
    `bitrate           ${seconds > 0 ? ((stats.bytes * 8) / seconds / 1e6).toFixed(2) : '0.00'} Mbps`,
    `decode time       ${mean(stats.decodeTimes).toFixed(1)} ms average`,
    `arrival gap       ${mean(gaps).toFixed(1)} ms average, jitter ${jitter.toFixed(1)} ms`,
    `drift since start ${stats.drift.toFixed(0)} ms   <- should stay near zero`
  ].join('\n');
}
setInterval(render, 500);

// ------------------------------------------------------------------- wiring

window.media.onAccepted(info => {
  setStatus(`Tablet accepted ${info.width}x${info.height} at ${info.frameRate} fps.`);
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

window.media.onError(message => setStatus(message, true));
window.media.onClosed(() => setStatus('Tablet disconnected.'));

$('#mediaConnect').addEventListener('click', async e => {
  const host = $('#mediaHost').value.trim();
  if (!host) return setStatus('Enter the tablet address shown in the Android app.', true);
  e.target.disabled = true;
  setStatus('Connecting…');
  try {
    await window.media.connect(host, { width: 1280, height: 720, frameRate: 30, audio: true });
  } catch (error) {
    setStatus(error.message, true);
  } finally {
    e.target.disabled = false;
  }
});

$('#mediaDisconnect').addEventListener('click', async () => {
  await window.media.disconnect();
  setStatus('Disconnected.');
});
