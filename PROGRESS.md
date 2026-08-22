# Perspective USB Bridge — Build progress

## v0.6.0 checkpoint

### Android
- Perspective Studio branded Compose UI.
- Detects attached USB devices and USB hubs.
- Treats hubs as infrastructure rather than exportable devices.
- Lists downstream USB devices individually.
- Per-device Android USB permission handling.
- Foreground service owns shared USB devices.
- Multiple devices can be shared concurrently through one USB/IP server on TCP 3240.
- Each exported device has its own bus ID, USB connection and I/O lock.
- CONTROL and BULK forwarding implemented for mass-storage-first testing.
- Device removal releases only that device; other shared devices stay online.
- LAN discovery beacon announces the Samsung and shared-device count.
- Diagnostics panel surfaces host/server activity.
- v0.6.0 test builds are explicitly named `-TEST.apk` until release signing is configured.

### Windows
- Perspective Studio branded Electron client.
- Automatic Samsung discovery on the LAN.
- Lists multiple exported devices and connects each independently.
- Uses usbip-win2 as the Windows USB/IP client/runtime.
- One automatic detach/retry path for a failed first attach.
- Checks for the USB/IP runtime before enabling normal use.
- Can install the bundled upstream runtime with an Administrator prompt.
- Reads and displays bundled runtime release metadata and shortened SHA-256.
- Windows installer bundles the runtime payload and upstream licence.

### GitHub automation
- Android test APK workflow validated.
- Windows installer workflow validated.
- Tag-based combined release workflow validated.
- Release workflow refuses to publish if either APK or EXE is missing.
- Runtime fetch now targets the current `vadimgrn/usbip-win2` upstream release and records exact asset/version/hash.

## Acceptance tests still required on real hardware
1. SSK 2 TB SSD attaches three consecutive times without manual Device Manager work.
2. Windows reads the same data volume/files Android sees.
3. Samsung + USB hub enumerates multiple downstream drives.
4. Drive A and Drive B can be shared independently.
5. Removing Drive A leaves Drive B online.
6. Windows can attach two exported storage devices concurrently.
7. Powered hub test with multiple high-draw SSD/HDD devices.

## Important status
The source is build-ready for GitHub Actions, but this workspace cannot download the Android SDK, so no APK has been compiled here. The first APK must be produced by a GitHub runner (or another machine with the Android SDK). The Android artifact remains a TEST build until production signing is configured.
