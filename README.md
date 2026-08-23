# Perspective USB Bridge

**Perspective Studio — USB over network for Samsung/Android to Windows.**

Current version: **v0.7.0 prototype**

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

The Android host uses a single USB/IP server on TCP port **3240** and can export multiple downstream USB devices at the same time. USB hubs themselves are not exported; devices connected through them are listed separately. Each exported device has its own bus ID.

A powered USB-C hub is recommended when using multiple SSDs/HDDs because the Samsung tablet may not be able to supply enough power for several high-draw devices.

## Discovery

The tablet is discoverable on UDP **32401** while the Android app is open, even before anything is shared. Discovery works two ways:

- The tablet announces itself every two seconds to the limited broadcast address **and** to each interface's subnet broadcast address.
- The Windows client also probes outwards; the tablet answers those probes with a unicast reply.

The probe path matters because plenty of access points drop `255.255.255.255`, and Windows Firewall discards unsolicited inbound UDP. The Windows installer adds inbound and outbound firewall rules for UDP/32401 on private and domain profiles.

If discovery still fails — for example on a guest network with client isolation — the Android app shows the tablet's IP address, and you can type it into the Windows client by hand.

## Windows runtime

The Windows client uses the open-source `usbip-win2` runtime. Release builds fetch the current upstream x64 installer, bundle its licence, and record the exact upstream release, asset and SHA-256 hash. The Perspective app asks for Administrator permission before installing the driver/runtime.

## Branding

Both apps use the Perspective Studio mark in `branding/ps-mark-primary.svg`.

On Android it is an adaptive launcher icon built from vector drawables, so it
stays sharp at any density, with a monochrome variant for themed icons and the
status bar. On Windows it becomes the executable, installer and uninstaller
icon, plus the wizard header and sidebar artwork.

The Windows raster formats and the legacy Android mipmaps are committed, so a
normal build needs no image tooling. To regenerate them after changing the mark:

```
npm --prefix tools/branding install
node tools/branding/build-icons.mjs
```

## Testing without hardware

```
cd tools/usbip-harness && gradle run     # USB/IP wire-protocol conformance
cd android && gradle testDebugUnitTest   # descriptor parsing
cd windows && npm test                   # usbip CLI output parsing
```

The harness runs the app's real USB/IP server against a simulated mass-storage device and asserts the bytes it puts on the wire. See `tools/usbip-harness/README.md`.

## Build outputs

Until Android release signing is configured, GitHub builds a clearly labelled test APK:

- `PerspectiveUSBBridge-Android-v0.7.0-TEST.apk`
- `PerspectiveUSBBridge-Windows-Setup-0.7.0.exe`

A tag such as `v0.7.0` triggers the combined release workflow. It refuses to publish if either platform artifact is missing.

> The `PerspectiveUSBBridge-GitHub-Ready-v0.6.zip` at the repository root is a snapshot of the older v0.6 code. Do not install from it.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Windows never finds the tablet | Broadcast blocked, or firewall | Type the IP shown in the Android app; check both devices are on the same Wi-Fi with client isolation off |
| "Tablet found, but no USB drives are currently shared" | Nothing shared yet | Tap **Share with Windows** on the tablet |
| "The USB interfaces could not be claimed" | Android has the drive mounted | Eject the drive in Android's Files app, then share it again |
| "Multiple instances of VHCI device interface found" | The USB/IP driver is installed more than once | Uninstall every "USBip" entry in Apps & features, remove leftover USBIP devices in Device Manager (View → Show hidden devices), reboot, install once. See below. |
| Connects, but no drive letter appears | The volume is offline or unformatted | Check Windows Disk Management |
| Transfers stall when the tablet screen sleeps | Wi-Fi power saving | The service holds a wake lock and a high-performance Wi-Fi lock; also disable battery optimisation for the app |

### One-command connect

`tools/windows/Connect-PerspectiveDrive.ps1` does the whole Windows side in one
go: checks for duplicate driver installs, installs the bundled runtime if it is
missing, finds the tablet, lists and attaches the shared drive, then brings the
disk online and gives it a drive letter. Right-click it and choose **Run with
PowerShell**, or:

```
powershell -ExecutionPolicy Bypass -File .\tools\windows\Connect-PerspectiveDrive.ps1
```

It elevates itself if needed, and takes `-TabletIp`, `-BusId`, `-NoMount` and
`-SkipInstall`. It never initialises, formats or partitions anything, so it
cannot destroy data on the drive.

### Removing a duplicate USB/IP driver

`usbip-win2` refuses to run when more than one VHCI root device is registered,
which happens if the driver is installed twice — for example once by hand and
once through the app's **Install USB driver** button. In an Administrator
Command Prompt:

```
pnputil /enum-devices /ids | findstr /i usbip
```

Every match prints an instance ID. Remove them all, then reboot and install the
driver once:

```
pnputil /remove-device "<instance ID>"
```

Also uninstall any "USBip" entries under Apps & features first, so nothing
reinstalls them on the next boot.

## Status

This is an early hardware prototype. CONTROL and BULK USB/IP forwarding are implemented and covered by an automated protocol harness, but the project still requires real-device validation with the target Samsung tablet, SSK 2 TB SSD, and a USB hub before it should be treated as production-ready.
