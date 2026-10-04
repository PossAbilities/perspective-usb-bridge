# Tablet camera and microphone on a Mac, while using Parsec

Use the Samsung tablet's camera and microphone in Zoom, Teams, Meet or FaceTime
on your Mac while you drive the Mac from the tablet with Parsec.

Parsec cannot do this on its own: its microphone passthrough only works from a
Windows or macOS *client*, and it has no camera passthrough from Android. This
app carries both over your Wi-Fi instead.

```
Samsung tablet                                  Mac
--------------                                  ---
Parsec (foreground)  ◄── screen + sound ──────  Parsec host
Perspective app      ── camera + mic, TCP ───►  Perspective Camera Bridge
  (keeps running                                   │ video ──► OBS Virtual Camera
   behind Parsec)                                  │ audio ──► BlackHole 2ch
                                                   ▼
                                       Zoom / Teams / Meet / FaceTime
```

## One-time setup

### On the tablet

1. Install `PerspectiveUSBBridge-Android-…-TEST.apk` (allow installs from your
   browser or Files app when Android asks).
2. Stop One UI putting it to sleep behind Parsec: **Settings → Apps →
   Perspective USB Bridge → Battery → Unrestricted**.

### On the Mac

1. Install `PerspectiveCameraBridge-Mac-…-arm64.dmg` (Apple Silicon) or
   `…-x64.dmg` (Intel) and drag the app to Applications.

   The app is not yet signed with an Apple Developer ID, so the first time,
   right-click it and choose **Open**, then **Open** again. On macOS 15 or later,
   if there is no Open button, go to **System Settings → Privacy & Security** and
   click **Open Anyway**. When macOS asks to find devices on your local network,
   click **Allow**: that is how it reaches the tablet.

2. Install **BlackHole 2ch** (free, existential.audio/blackhole, or
   `brew install blackhole-2ch`). This becomes the "microphone" your call apps
   use.

3. Install **OBS Studio** (free, obsproject.com). Its built-in virtual camera is
   what call apps will see as a webcam.

## Every time

1. **Tablet:** open Perspective USB Bridge and tap **Share camera &
   microphone**. Allow camera and microphone when asked. Note the address on
   the card, for example `192.168.1.92`.
2. **Tablet:** switch to Parsec and connect to the Mac as normal. The camera
   bridge keeps running in the background; a notification shows it is active.
3. **Mac:** open Perspective Camera Bridge. The tablet address usually fills in
   by itself; otherwise type it. Choose **Front camera** for a selfie view and
   **720p** or **1080p**, then **Connect**. If the link drops it reconnects on
   its own.
4. **Mac:** under **Send the tablet microphone to**, choose **BlackHole 2ch**.
   This is picked automatically when BlackHole is installed.
5. **Mac, first time only:** in OBS, add a **macOS Screen Capture** source set to
   **Window → Perspective Camera Bridge**, resize it to fill the canvas, and
   click **Start Virtual Camera**. Allow OBS **Screen Recording** permission when
   macOS asks. OBS remembers this, so afterwards just start
   the virtual camera.
6. **Mac:** click **Camera output view** in Perspective Camera Bridge so the
   window shows only the picture. Double-click it, or press Esc, to get the
   controls back. Keep the window open (it may sit behind other windows, but
   do not minimise it, or OBS stops receiving frames).
7. **In your call app:** camera → **OBS Virtual Camera**, microphone →
   **BlackHole 2ch**.

## Why the microphone goes to BlackHole, not your speakers

Parsec sends everything the Mac plays back to the tablet. If the tablet
microphone were played through the Mac's speakers, you would hear yourself
through the tablet with a delay. Sending it to BlackHole keeps it inside the
Mac, where only the call app hears it. That is why the default is BlackHole
when it is installed, and **Nowhere** otherwise.

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| "connect ECONNREFUSED" | The bridge is not running on the tablet. Open the app and tap **Share camera & microphone**. |
| "connect ETIMEDOUT" / nothing happens | Check both devices are on the same Wi-Fi, and that macOS was allowed to use the local network (**System Settings → Privacy & Security → Local Network**). |
| Picture freezes after a few minutes behind Parsec | Set the tablet app's battery usage to **Unrestricted** (see above). |
| Call app shows a black OBS camera | Start the virtual camera in OBS, and make sure the Perspective window is not minimised. |
| Call app hears nothing | Microphone in the call app must be **BlackHole 2ch**, and the same device must be selected in Perspective Camera Bridge. |
| "Microphone permission was declined" | Android Settings → Apps → Perspective USB Bridge → Permissions → Microphone → Allow. |

## What would make this simpler

A native macOS camera extension and audio driver would remove OBS and BlackHole
from the picture: the tablet would appear directly as "Perspective Camera" and
"Perspective Microphone". Both need an Apple Developer ID ($99/year) to sign
and notarise, plus a Mac to build on, so they are deliberately left until the
pipeline above is proven on the real tablet.
