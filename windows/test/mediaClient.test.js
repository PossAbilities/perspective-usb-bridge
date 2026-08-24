'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const mc = require('../src/mediaClient');

// --- helpers that build bytes the way the tablet does ------------------------

function accept({ width = 1280, height = 720, frameRate = 30, status = 0, version = 1 } = {}) {
  const b = Buffer.alloc(16);
  Buffer.from('PSPMEDIA', 'ascii').copy(b, 0);
  b.writeUInt16BE(version, 8);
  b.writeUInt16BE(width, 10);
  b.writeUInt16BE(height, 12);
  b.writeUInt8(frameRate, 14);
  b.writeUInt8(status, 15);
  return b;
}

function frame(type, timestampUs, payload, keyframe = false) {
  const header = Buffer.alloc(20);
  Buffer.from('PMF1', 'ascii').copy(header, 0);
  header.writeUInt8(type, 4);
  header.writeUInt8(keyframe ? 1 : 0, 5);
  header.writeUInt16BE(0, 6);
  header.writeBigUInt64BE(BigInt(timestampUs), 8);
  header.writeUInt32BE(payload.length, 16);
  return Buffer.concat([header, payload]);
}

const startCode = Buffer.from([0, 0, 0, 1]);
/** Constrained baseline 3.1 SPS header bytes, which is what phones emit. */
const sps = Buffer.concat([startCode, Buffer.from([0x67, 0x42, 0xe0, 0x1f, 0xaa])]);
const pps = Buffer.concat([startCode, Buffer.from([0x68, 0xce, 0x3c, 0x80])]);
const idr = Buffer.concat([startCode, Buffer.from([0x65, 0x88, 0x84, 0x00])]);

// --- handshake ---------------------------------------------------------------

test('handshake request is 16 bytes with the fields where the tablet expects them', () => {
  const request = mc.buildRequest({ width: 1920, height: 1080, frameRate: 30, audio: true, frontCamera: true });
  assert.equal(request.length, 16);
  assert.equal(request.subarray(0, 8).toString('ascii'), 'PSPMEDIA');
  assert.equal(request.readUInt16BE(8), 1);
  assert.equal(request.readUInt16BE(10), 1920);
  assert.equal(request.readUInt16BE(12), 1080);
  assert.equal(request.readUInt8(14), 30);
  assert.equal(request.readUInt8(15), 0x03, 'audio and front-camera flags');
});

test('request flags are independent', () => {
  assert.equal(mc.buildRequest({ audio: false, frontCamera: false }).readUInt8(15), 0x00);
  assert.equal(mc.buildRequest({ audio: true, frontCamera: false }).readUInt8(15), 0x01);
  assert.equal(mc.buildRequest({ audio: false, frontCamera: true }).readUInt8(15), 0x02);
});

test('the accepted geometry is reported, not the requested one', () => {
  const parser = new mc.MediaStreamParser();
  const events = parser.push(accept({ width: 640, height: 480, frameRate: 24 }));
  assert.deepEqual(events, [{ kind: 'accepted', width: 640, height: 480, frameRate: 24, status: 0 }]);
});

test('a refusal is surfaced as an error, not silently streamed', () => {
  const parser = new mc.MediaStreamParser();
  assert.throws(() => parser.push(accept({ status: 1 })), /refused/i);
});

test('a foreign or mismatched peer is rejected', () => {
  assert.throws(() => new mc.MediaStreamParser().push(Buffer.alloc(16)), /not a perspective media stream/i);
  assert.throws(() => new mc.MediaStreamParser().push(accept({ version: 9 })), /version 9/);
});

// --- framing over a real byte stream -----------------------------------------

test('frames are parsed with their flags, timestamps and payloads', () => {
  const parser = new mc.MediaStreamParser();
  parser.push(accept());
  const events = parser.push(Buffer.concat([
    frame(mc.TYPE_VIDEO_CONFIG, 0, Buffer.concat([sps, pps])),
    frame(mc.TYPE_VIDEO_FRAME, 33333, idr, true),
    frame(mc.TYPE_AUDIO_FRAME, 33333, Buffer.alloc(1920))
  ]));

  assert.equal(events.length, 3);
  assert.equal(events[0].type, mc.TYPE_VIDEO_CONFIG);
  assert.equal(events[1].type, mc.TYPE_VIDEO_FRAME);
  assert.equal(events[1].timestampUs, 33333);
  assert.equal(events[1].keyframe, true);
  assert.equal(events[2].keyframe, false);
  assert.equal(events[2].payload.length, 1920);
});

test('a frame split across every possible byte boundary still arrives intact', () => {
  const payload = Buffer.from('a realistic slice of an encoded picture'.repeat(4));
  const stream = Buffer.concat([accept(), frame(mc.TYPE_VIDEO_FRAME, 123456, payload, true)]);

  // TCP can break anywhere; walk the split point across the whole message.
  for (let split = 1; split < stream.length; split++) {
    const parser = new mc.MediaStreamParser();
    const events = [
      ...parser.push(stream.subarray(0, split)),
      ...parser.push(stream.subarray(split))
    ];
    const frames = events.filter(e => e.kind === 'frame');
    assert.equal(frames.length, 1, `split at ${split}`);
    assert.equal(frames[0].timestampUs, 123456, `split at ${split}`);
    assert.ok(frames[0].payload.equals(payload), `split at ${split}`);
  }
});

test('byte-at-a-time delivery works', () => {
  const stream = Buffer.concat([accept(), frame(mc.TYPE_VIDEO_FRAME, 7, Buffer.alloc(40), true)]);
  const parser = new mc.MediaStreamParser();
  const events = [];
  for (const byte of stream) events.push(...parser.push(Buffer.from([byte])));
  assert.equal(events.filter(e => e.kind === 'frame').length, 1);
});

test('several frames arriving in one read are all returned', () => {
  const parser = new mc.MediaStreamParser();
  parser.push(accept());
  const batch = Buffer.concat(
    Array.from({ length: 50 }, (_, i) => frame(mc.TYPE_VIDEO_FRAME, i * 33333, Buffer.alloc(200), i % 30 === 0))
  );
  const frames = parser.push(batch).filter(e => e.kind === 'frame');
  assert.equal(frames.length, 50);
  assert.equal(frames[0].keyframe, true);
  assert.equal(frames[1].keyframe, false);
  assert.equal(frames[49].timestampUs, 49 * 33333);
});

test('a large keyframe survives fragmentation', () => {
  const big = Buffer.alloc(200_000, 0xab);
  const stream = Buffer.concat([accept(), frame(mc.TYPE_VIDEO_FRAME, 1, big, true)]);
  const parser = new mc.MediaStreamParser();
  const events = [];
  for (let i = 0; i < stream.length; i += 1400) { // roughly an ethernet MTU
    events.push(...parser.push(stream.subarray(i, i + 1400)));
  }
  const frames = events.filter(e => e.kind === 'frame');
  assert.equal(frames.length, 1);
  assert.ok(frames[0].payload.equals(big));
});

test('timestamps stay exact across a long session', () => {
  const parser = new mc.MediaStreamParser();
  parser.push(accept());
  const eightHoursUs = 8 * 3600 * 1_000_000;
  const [f] = parser.push(frame(mc.TYPE_VIDEO_FRAME, eightHoursUs, Buffer.alloc(1)));
  assert.equal(f.timestampUs, eightHoursUs);
  assert.ok(Number.isSafeInteger(f.timestampUs));
});

test('corruption and absurd lengths are rejected rather than guessed at', () => {
  const parser = new mc.MediaStreamParser();
  parser.push(accept());
  const bad = frame(mc.TYPE_VIDEO_FRAME, 0, Buffer.alloc(4));
  bad[1] = 0x58;
  assert.throws(() => parser.push(bad), /lost media frame sync/i);

  const parser2 = new mc.MediaStreamParser();
  parser2.push(accept());
  const huge = frame(mc.TYPE_VIDEO_FRAME, 0, Buffer.alloc(4));
  huge.writeUInt32BE(0x7fffffff, 16);
  assert.throws(() => parser2.push(huge), /implausible/i);
});

// --- H.264 helpers -----------------------------------------------------------

test('the codec string comes from the SPS', () => {
  assert.equal(mc.avcCodecString(Buffer.concat([sps, pps])), 'avc1.42e01f');
});

test('a high-profile SPS is reported as such', () => {
  const high = Buffer.concat([startCode, Buffer.from([0x67, 0x64, 0x00, 0x28, 0xac])]);
  assert.equal(mc.avcCodecString(high), 'avc1.640028');
});

test('config without an SPS falls back rather than throwing', () => {
  assert.equal(mc.avcCodecString(Buffer.alloc(0)), 'avc1.42e01f');
  assert.equal(mc.avcCodecString(pps), 'avc1.42e01f');
});

test('three-byte start codes are understood as well as four', () => {
  const short = Buffer.from([0, 0, 1, 0x67, 0x4d, 0x40, 0x1e]);
  assert.equal(mc.avcCodecString(short), 'avc1.4d401e');
});

test('parameter sets are prepended only when the keyframe lacks them', () => {
  const config = Buffer.concat([sps, pps]);
  const bare = idr;
  const inlined = Buffer.concat([sps, pps, idr]);

  assert.equal(mc.hasParameterSets(bare), false);
  assert.equal(mc.hasParameterSets(inlined), true);
  assert.ok(mc.prepareKeyframe(config, bare).equals(Buffer.concat([config, bare])));
  assert.ok(mc.prepareKeyframe(config, inlined).equals(inlined), 'no duplication');
  assert.ok(mc.prepareKeyframe(null, bare).equals(bare), 'no config yet');
});

test('audio config is read, with a sane fallback', () => {
  const payload = Buffer.alloc(6);
  payload.writeUInt32BE(48000, 0);
  payload.writeUInt8(1, 4);
  payload.writeUInt8(16, 5);
  assert.deepEqual(mc.parseAudioConfig(payload), { sampleRate: 48000, channels: 1, bitsPerSample: 16 });
  assert.deepEqual(mc.parseAudioConfig(Buffer.alloc(2)), { sampleRate: 48000, channels: 1, bitsPerSample: 16 });
});
