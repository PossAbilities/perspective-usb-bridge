# Camera and microphone bridge

Presents the Samsung tablet's cameras and microphone to Windows as an ordinary
webcam and microphone, so Teams, Zoom, Meet and anything else see them as normal
devices.

Target: **1080p30 video calls**, roughly 100–150 ms glass to glass on 5 GHz
Wi-Fi.

## Why this does not reuse the USB/IP bridge

The storage bridge works because mass storage is **bulk transfer**: request and
response, retried on error, tolerant of latency. A slow link only makes it slow.

Cameras (UVC) and microphones (UAC) use **isochronous** endpoints: fixed
bandwidth, no retries, packets due every 125 µs microframe. A missed deadline
means a dropped frame, and a network round trip cannot meet that schedule.

Two separate blockers, either of which is fatal:

1. Android's Java USB API cannot perform isochronous transfers at all.
   `bulkTransfer` and `UsbRequest` cover bulk and interrupt only. Native code
   driving usbfs through the file descriptor from
   `UsbDeviceConnection.getFileDescriptor()` is the only route, and that is a
   rewrite of the transfer layer.
2. Even with that solved, isochronous over USB/IP on Wi-Fi is unreliable, which
   is why `UsbIpServer` rejects isochronous URBs with `-EINVAL` today rather
   than pretending to serve them.

Since the source here is the tablet's own hardware, none of that applies. There
is no USB in the path: capture, encode, stream, present.

## Architecture

```
Android tablet                          Windows PC
--------------                          ----------
Camera2  ──► MediaCodec H.264 ─┐
                               ├─► TCP :32402 ─► helper service
AudioRecord ──► PCM ───────────┘                      │
                                                shared memory
                                                      │
                                          virtual camera media source
                                            (COM DLL in Frame Server)
                                                      │
                                        Teams / Zoom / Meet / Chrome
```

### Video

`Camera2` into a `MediaCodec` surface, hardware H.264, 2–4 Mbps at 1080p30.
Hardware encode keeps latency and battery cost down. Requested keyframe interval
is short so a client joining mid-stream renders quickly.

### Audio: uncompressed on purpose

48 kHz 16-bit mono PCM is 768 kbps. Next to a 2–4 Mbps video stream that is
noise, and it removes an encoder, a decoder and their algorithmic delay from a
path where latency is the whole game. AAC-LC would add 20–40 ms for a bandwidth
saving nobody needs on a LAN.

### Windows

`MFCreateVirtualCamera` (Windows 11 build 22000+) registers a virtual camera
system-wide with **no kernel driver and no WHQL**. Media Foundation backs camera
capture in Teams, Zoom, Meet and Chrome, so all of them see it.

The frame source must live in an in-proc COM DLL that the Windows Frame Server
service loads into *its* process, not ours. So the helper service that owns the
TCP connection hands frames across via shared memory with a ring buffer; the DLL
only reads.

## The microphone needs a signed driver

Windows has no user-mode virtual microphone API. Audio capture runs through
WASAPI/KS and requires a real endpoint, which means a kernel-mode audio driver.
Distributing one needs an EV code-signing certificate, a Microsoft Partner
Center account and attestation signing. That is the largest single cost in this
work, and it is why the phases below put it last.

### The remote desktop tool does not cover it

Worth ruling out before building, since a remote desktop tool that already
redirects the microphone would delete this whole item. Checked, and it does not.

This setup reaches Windows through **Parsec from the Android tablet**. Parsec
does have microphone passthrough, but its documentation is explicit that it
works from a **Windows or macOS client only**, and it additionally requires
Parsec's own virtual USB driver on the host. An Android client cannot use it.

Parsec's approach is worth noting all the same: it solves the problem by
installing a virtual USB device on the host, which is the same shape as the
architecture above. That is evidence the design is sound, not a shortcut we can
take.

Remaining options, in order of preference:

1. Depend on an installed virtual audio cable and have the user select it in the
   conferencing app. Works today; redistribution licensing needs checking.
2. Ship our own signed driver.

## Wire protocol

TCP on port **32402**. All integers big-endian, matching the USB/IP bridge.

### Handshake

Client sends 16 bytes:

| Offset | Size | Field |
| --- | --- | --- |
| 0 | 8 | magic `PSPMEDIA` |
| 8 | 2 | protocol version, currently 1 |
| 10 | 2 | requested width |
| 12 | 2 | requested height |
| 14 | 1 | requested frame rate |
| 15 | 1 | flags: bit 0 audio wanted, bit 1 front camera |

Server replies with 16 bytes: the same magic and version, then the width, height
and frame rate it actually chose, then a status byte (0 accepted, non-zero
refused). The client must use the returned geometry, not what it asked for.

### Frames

Every frame after the handshake carries a 20-byte header:

| Offset | Size | Field |
| --- | --- | --- |
| 0 | 4 | magic `PMF1` |
| 4 | 1 | type |
| 5 | 1 | flags: bit 0 keyframe |
| 6 | 2 | reserved, zero |
| 8 | 8 | presentation timestamp, microseconds since stream start |
| 16 | 4 | payload length |

Types: `1` video config (H.264 SPS/PPS), `2` video frame (Annex B), `3` audio
config, `4` audio frame (PCM). A config frame always precedes the first frame of
its kind, and is repeated when the encoder reconfigures.

Timestamps share one clock across both streams so the Windows side can
synchronise them.

## Phases

1. **Protocol and capture.** Frame codec with tests; Android capture, encode and
   serve. Verifiable without Windows.
2. **Windows helper.** Connect, decode, measure real latency on the target
   network. Decides whether the budget is achievable before any COM work.
   Decoding uses WebCodecs `VideoDecoder` inside the existing Electron client,
   which is hardware accelerated and needs no native code, so the latency
   question gets answered without writing a line of C++.
3. **Virtual camera.** COM media source DLL, shared-memory transport,
   `MFCreateVirtualCamera` registration. Camera usable in Teams at this point.
4. **Microphone.** The driver is unavoidable: see above. Sequenced last so the
   camera is usable long before the certificate and signing work completes.

Each phase is useful on its own, and phase 3 is the first that a conferencing
app can consume.
