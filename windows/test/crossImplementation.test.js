'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const mc = require('../src/mediaClient');

/**
 * The tablet writes this protocol in Kotlin and Windows reads it in JavaScript,
 * so the two implementations could drift apart without either side's own tests
 * noticing. This fixture was produced by the real MediaProtocol writer via
 * tools/usbip-harness, and pins the contract between them.
 *
 * Regenerate it if the format changes deliberately; a surprise failure here
 * means the two ends no longer agree.
 */
const FIXTURE = path.join(__dirname, 'fixtures', 'media-stream.bin');

test('the JS client parses a stream written by the Kotlin server', () => {
  const bytes = fs.readFileSync(FIXTURE);
  const parser = new mc.MediaStreamParser();

  // Feed it in MTU-sized reads, as a socket would.
  const events = [];
  for (let i = 0; i < bytes.length; i += 1400) {
    events.push(...parser.push(bytes.subarray(i, i + 1400)));
  }

  const accepted = events.find(e => e.kind === 'accepted');
  assert.deepEqual(accepted, { kind: 'accepted', width: 1280, height: 720, frameRate: 30, status: 0 });

  const frames = events.filter(e => e.kind === 'frame');
  const videoConfig = frames.filter(f => f.type === mc.TYPE_VIDEO_CONFIG);
  const audioConfig = frames.filter(f => f.type === mc.TYPE_AUDIO_CONFIG);
  const video = frames.filter(f => f.type === mc.TYPE_VIDEO_FRAME);
  const audio = frames.filter(f => f.type === mc.TYPE_AUDIO_FRAME);

  assert.equal(videoConfig.length, 1);
  assert.equal(audioConfig.length, 1);
  assert.equal(video.length, 30);
  assert.equal(audio.length, 30);

  // The codec string the decoder will be configured with comes from real bytes.
  assert.equal(mc.avcCodecString(videoConfig[0].payload), 'avc1.42e01f');
  assert.deepEqual(mc.parseAudioConfig(audioConfig[0].payload), {
    sampleRate: 48000, channels: 1, bitsPerSample: 16
  });

  // Keyframes every 15 frames, exactly as written.
  assert.deepEqual(
    video.map(f => f.keyframe),
    Array.from({ length: 30 }, (_, i) => i % 15 === 0)
  );

  // Audio and video share one timeline; that is what makes them syncable.
  assert.deepEqual(video.map(f => f.timestampUs), audio.map(f => f.timestampUs));
  assert.equal(video[0].timestampUs, 0);
  assert.equal(video[29].timestampUs, 29 * 33333);

  // Payload sizes and content match what the writer emitted.
  assert.equal(audio[0].payload.length, 1920, '20 ms of 48 kHz mono 16-bit PCM');
  assert.equal(video[0].payload.length, 5 + 4000, 'keyframe');
  assert.equal(video[1].payload.length, 5 + 800, 'inter frame');
  assert.ok(mc.hasParameterSets(videoConfig[0].payload));
  assert.equal(mc.hasParameterSets(video[0].payload), false, 'keyframe needs config prepending');

  assert.equal(parser.buffer.length, 0, 'stream fully consumed, nothing left over');
});

test('the fixture is consumed identically when delivered a byte at a time', () => {
  const bytes = fs.readFileSync(FIXTURE);
  const parser = new mc.MediaStreamParser();
  let frames = 0;
  for (const byte of bytes) {
    for (const event of parser.push(Buffer.from([byte]))) {
      if (event.kind === 'frame') frames++;
    }
  }
  assert.equal(frames, 62, '1 video config + 1 audio config + 30 video + 30 audio');
});
