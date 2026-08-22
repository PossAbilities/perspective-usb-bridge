# Perspective USB Bridge

**Perspective Studio — USB over network for Samsung/Android to Windows.**

Current version: **v0.6.0 prototype**

## Goal

Plug a USB drive — or a USB hub containing several drives — into a Samsung/Android tablet and make selected devices available to a Windows PC over the local network.

Intended experience:

1. Install Perspective USB Bridge on Android.
2. Install Perspective USB Bridge on Windows.
3. Plug a drive or hub into the Samsung tablet.
4. Tap **Share with Windows** beside the desired drive(s).
5. The Windows app discovers the tablet automatically.
6. Tap **Connect drive**.
7. Windows mounts the remote USB device through the USB/IP runtime.

## Hub support

The Android host uses a single USB/IP server on TCP port **3240** and can export multiple downstream USB devices at the same time. USB hubs themselves are not exported; devices connected through them are listed separately. Each exported device has its own bus ID and transfer lock.

A powered USB-C hub is recommended when using multiple SSDs/HDDs because the Samsung tablet may not be able to supply enough power for several high-draw devices.

## Windows runtime

The Windows client uses the open-source `usbip-win2` runtime. Release builds fetch the current upstream x64 installer, bundle its licence, and record the exact upstream release, asset and SHA-256 hash. The Perspective app asks for Administrator permission before installing the driver/runtime.

## Build outputs

Until Android release signing is configured, GitHub builds a clearly labelled test APK:

- `PerspectiveUSBBridge-Android-v0.6.0-TEST.apk`
- `PerspectiveUSBBridge-Windows-Setup-0.6.0.exe`

A tag such as `v0.6.0` triggers the combined release workflow. It refuses to publish if either platform artifact is missing.

## Status

This is an early hardware prototype. CONTROL and BULK USB/IP forwarding are implemented for mass-storage-first testing, but the project still requires real-device validation with the target Samsung tablet, SSK 2 TB SSD, and a USB hub before it should be treated as production-ready.
