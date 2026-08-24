'use strict';

/**
 * Client side of the camera and microphone protocol. See docs/CAMERA-BRIDGE.md.
 *
 * Pure functions and a stateful parser, deliberately free of Electron and Node
 * sockets so the whole thing can be tested without either.
 */

const HANDSHAKE_MAGIC = Buffer.from('PSPMEDIA', 'ascii');
const FRAME_MAGIC = Buffer.from('PMF1', 'ascii');

const HANDSHAKE_SIZE = 16;
const FRAME_HEADER_SIZE = 20;
const MAX_PAYLOAD = 8 * 1024 * 1024;

const PORT = 32402;
const VERSION = 1;

const TYPE_VIDEO_CONFIG = 1;
const TYPE_VIDEO_FRAME = 2;
const TYPE_AUDIO_CONFIG = 3;
const TYPE_AUDIO_FRAME = 4;

const FLAG_KEYFRAME = 0x01;
const REQUEST_AUDIO = 0x01;
const REQUEST_FRONT_CAMERA = 0x02;

const STATUS_OK = 0;

/** Build the 16-byte handshake the tablet expects as the first thing on the wire. */
function buildRequest({ width = 1280, height = 720, frameRate = 30, audio = true, frontCamera = false } = {}) {
  const buffer = Buffer.alloc(HANDSHAKE_SIZE);
  HANDSHAKE_MAGIC.copy(buffer, 0);
  buffer.writeUInt16BE(VERSION, 8);
  buffer.writeUInt16BE(width, 10);
  buffer.writeUInt16BE(height, 12);
  buffer.writeUInt8(frameRate, 14);
  buffer.writeUInt8((audio ? REQUEST_AUDIO : 0) | (frontCamera ? REQUEST_FRONT_CAMERA : 0), 15);
  return buffer;
}

/**
 * Incremental parser over a TCP byte stream.
 *
 * TCP hands over arbitrary chunk boundaries, so every field can arrive split
 * across reads. `push` accumulates and returns only whole events.
 */
class MediaStreamParser {
  constructor() {
    this.buffer = Buffer.alloc(0);
    this.accepted = null;
  }

  /** @returns {Array<{kind: string, ...}>} events completed by this chunk */
  push(chunk) {
    this.buffer = this.buffer.length === 0 ? Buffer.from(chunk) : Buffer.concat([this.buffer, chunk]);
    const events = [];

    if (!this.accepted) {
      if (this.buffer.length < HANDSHAKE_SIZE) return events;
      const header = this.buffer.subarray(0, HANDSHAKE_SIZE);
      if (!header.subarray(0, 8).equals(HANDSHAKE_MAGIC)) {
        throw new Error('Not a Perspective media stream');
      }
      const version = header.readUInt16BE(8);
      if (version !== VERSION) throw new Error(`Unsupported media protocol version ${version}`);
      this.accepted = {
        width: header.readUInt16BE(10),
        height: header.readUInt16BE(12),
        frameRate: header.readUInt8(14),
        status: header.readUInt8(15)
      };
      this.buffer = this.buffer.subarray(HANDSHAKE_SIZE);
      events.push({ kind: 'accepted', ...this.accepted });
      if (this.accepted.status !== STATUS_OK) {
        throw new Error('The tablet refused the stream. Is a camera available?');
      }
    }

    for (;;) {
      if (this.buffer.length < FRAME_HEADER_SIZE) break;
      if (!this.buffer.subarray(0, 4).equals(FRAME_MAGIC)) {
        // Resynchronising a corrupted stream is not worth the complexity.
        throw new Error('Lost media frame sync');
      }
      const length = this.buffer.readUInt32BE(16);
      if (length < 0 || length > MAX_PAYLOAD) {
        throw new Error(`Implausible media payload length ${length}`);
      }
      if (this.buffer.length < FRAME_HEADER_SIZE + length) break;

      const flags = this.buffer.readUInt8(5);
      events.push({
        kind: 'frame',
        type: this.buffer.readUInt8(4),
        keyframe: (flags & FLAG_KEYFRAME) !== 0,
        // Timestamps are microseconds and comfortably inside Number's exact
        // integer range for any realistic session, so no BigInt needed.
        timestampUs: Number(this.buffer.readBigUInt64BE(8)),
        payload: Buffer.from(this.buffer.subarray(FRAME_HEADER_SIZE, FRAME_HEADER_SIZE + length))
      });
      this.buffer = this.buffer.subarray(FRAME_HEADER_SIZE + length);
    }

    return events;
  }
}

/** Iterate the NAL units of an Annex B buffer, yielding {type, start, end}. */
function* annexBNalUnits(buffer) {
  let i = 0;
  let start = -1;
  while (i + 2 < buffer.length) {
    const isShort = buffer[i] === 0 && buffer[i + 1] === 0 && buffer[i + 2] === 1;
    const isLong = i + 3 < buffer.length &&
      buffer[i] === 0 && buffer[i + 1] === 0 && buffer[i + 2] === 0 && buffer[i + 3] === 1;
    if (isShort || isLong) {
      const payloadStart = i + (isLong ? 4 : 3);
      if (start >= 0) yield { type: buffer[start] & 0x1f, start, end: i };
      start = payloadStart;
      i = payloadStart;
      continue;
    }
    i++;
  }
  if (start >= 0 && start < buffer.length) {
    yield { type: buffer[start] & 0x1f, start, end: buffer.length };
  }
}

/**
 * Derive the WebCodecs codec string from the SPS in an Annex B config blob.
 *
 * `avc1.PPCCLL` is profile_idc, the constraint flags byte and level_idc, which
 * are the three bytes straight after the SPS NAL header.
 */
function avcCodecString(config) {
  for (const nal of annexBNalUnits(config)) {
    if (nal.type !== 7) continue; // 7 = sequence parameter set
    if (nal.start + 3 >= config.length) break;
    const profile = config[nal.start + 1];
    const constraints = config[nal.start + 2];
    const level = config[nal.start + 3];
    const hex = value => value.toString(16).padStart(2, '0');
    return `avc1.${hex(profile)}${hex(constraints)}${hex(level)}`;
  }
  // Constrained baseline 3.1: a safe guess that decodes most phone output.
  return 'avc1.42e01f';
}

/** True when the buffer already carries its own SPS, so config need not be prepended. */
function hasParameterSets(frame) {
  for (const nal of annexBNalUnits(frame)) {
    if (nal.type === 7) return true;
  }
  return false;
}

/**
 * WebCodecs in Annex B mode wants parameter sets in front of a keyframe. The
 * encoder may or may not have inlined them, so only prepend when missing.
 */
function prepareKeyframe(config, frame) {
  if (!config || config.length === 0 || hasParameterSets(frame)) return frame;
  return Buffer.concat([config, frame]);
}

/** Interpret the six-byte audio config: sample rate, channels, bits per sample. */
function parseAudioConfig(payload) {
  if (!payload || payload.length < 6) return { sampleRate: 48000, channels: 1, bitsPerSample: 16 };
  return {
    sampleRate: payload.readUInt32BE(0),
    channels: payload.readUInt8(4),
    bitsPerSample: payload.readUInt8(5)
  };
}

module.exports = {
  PORT,
  VERSION,
  HANDSHAKE_SIZE,
  FRAME_HEADER_SIZE,
  TYPE_VIDEO_CONFIG,
  TYPE_VIDEO_FRAME,
  TYPE_AUDIO_CONFIG,
  TYPE_AUDIO_FRAME,
  STATUS_OK,
  buildRequest,
  MediaStreamParser,
  annexBNalUnits,
  avcCodecString,
  hasParameterSets,
  prepareKeyframe,
  parseAudioConfig
};
