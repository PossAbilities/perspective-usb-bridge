# Perspective USB Bridge — Build progress

## v0.7.0 — repair pass

v0.6 could advertise a drive but Windows would not mount it, and often could not
find the tablet at all. This release fixes the defects behind both symptoms.

### Why the drive never appeared on Windows

Four independent faults, any one of which was enough:

1. **`SET_CONFIGURATION` was rejected.** The service claims every interface so
   Windows, not Android, owns the drive. Once interfaces are claimed, Linux
   usbfs answers `SET_CONFIGURATION` with `EBUSY`. The server forwarded that
   failure, so Windows abandoned enumeration before creating a disk. Standard
   control requests are now answered from the device's cached descriptors, and
   the configuration Linux has actually selected is accepted.
2. **Transfers over 16 KiB failed.** `bulkTransfer` maps onto usbfs, which
   refuses a single URB larger than 16 KiB. Windows routinely reads 64 KiB and
   more. Transfers are now split into usbfs-sized chunks and reassembled, with
   short packets correctly terminating a transfer.
3. **Everything was serialised.** One blocking bulk read stalled control traffic
   and unlink handling on the same device. Each USB pipe now has its own worker,
   with a single serialised writer.
4. **`USBIP_CMD_UNLINK` was mishandled.** It always claimed `-ECONNRESET` even
   for URBs that had already completed, and then sent a late `RET_SUBMIT` for
   the URB it had just said was gone — desynchronising the client.

Also corrected in the wire protocol: link speed is derived from the endpoints'
max packet size instead of being hard-coded to high speed; `bcdDevice`, device
class and configuration counts come from the real descriptors; the device-list
interface records are keyed to the active configuration's interface count so the
client cannot lose stream sync; failures report `-EPIPE`/`-ETIMEDOUT` rather than
a blanket `-EIO`, so Windows treats unsupported requests as stalls and continues.

### Why Windows often could not find the tablet

- The beacon only ran once a drive was shared, so there was nothing to find
  before then. The tablet is now discoverable whenever the Android app is open.
- Announcements went only to `255.255.255.255`, which many access points drop.
  They now also go to every interface's subnet broadcast address.
- Nothing opened the inbound path through Windows Firewall. The Windows client
  now probes outwards and the tablet answers with a unicast reply, and the
  installer adds UDP/32401 firewall rules.
- `reuseAddress` was set after `bind()`, where it does nothing, so restarting the
  service could fail to reclaim TCP/3240.

### Other Android fixes

- The service could be killed by `ForegroundServiceDidNotStartInTimeException`:
  `startForeground` was only reached after several early-return paths.
  It is now the first thing `onStartCommand` does.
- USB attach/detach broadcasts were registered `RECEIVER_NOT_EXPORTED`, which
  stops Android 14+ delivering system broadcasts. Unplugged drives stayed listed
  as shared. System and app broadcasts are now registered separately.
- `POST_NOTIFICATIONS` was declared but never requested, so the foreground
  notification was invisible on Android 13+.
- The share button read a stale `hasPermission()` result and did not update after
  a grant.
- A partial wake lock and a high-performance Wi-Fi lock are held while sharing,
  so transfers survive the screen sleeping.
- Hub interfaces are never claimed; alternate settings are tracked so endpoint
  lookup follows `SET_INTERFACE`.
- Bus IDs are allocated from a counter instead of derived from Android's device
  id, which could collide or exceed the usual range.

### Windows client fixes

- `detach --all` was invoked as `-all`, which usbip parses as three short flags.
  Disconnect now enumerates `usbip port` and detaches each port.
- A failed attach used to detach **every** device before retrying, disconnecting
  the user's other drives. It now detaches only the port for that bus id.
- The device-list parser accepted almost any `word: text` line, so error output
  became phantom drives. It now matches bus-id shaped records only, and
  understands the indented multi-line layout and hub bus ids such as `1-2.4`.
- The UI shows which drives are already connected, allows disconnecting one,
  re-scans periodically, and translates usbip's errors into actionable advice.
- `usbip` invocations have a timeout and a raised output buffer.
- Installer exit code 3010 (restart required) is no longer reported as failure.

### Automated tests, no hardware needed

- `tools/usbip-harness` compiles the app's real USB/IP sources against
  `android.hardware.usb` stubs and drives them over a socket with a simulated
  mass-storage device. 36 assertions cover the device list byte layout, import,
  descriptor replay, configuration/interface requests, >16 KiB chunking in both
  directions, pipe concurrency and unlink semantics.
- Android unit tests cover descriptor parsing.
- Windows tests cover `usbip list -r` and `usbip port` parsing.
- All three run in CI before either artifact is built.

## First successful end-to-end run

23 August 2026, on the target hardware: Samsung tablet, SSK USB3.2 SSD
(VIA Labs VL817 SATA bridge, 2109:0715), Windows 11 26200.

The drive mounted and its files were readable in Windows Explorer. The full
chain works: Android USB host, USB/IP over Wi-Fi on TCP 3240, usbip-win2 VHCI,
mounted NTFS volume.

Two things had to be true that the code could not fix by itself:

- **Exactly one USB/IP driver install.** usbip-win2 refuses to run when more
  than one VHCI root device is registered, failing with "Multiple instances of
  VHCI device interface found". The machine needed every USBip install removed
  and one clean reinstall. The app now detects an existing runtime via PATH and
  the registry and refuses to install a second copy.
- **The Android app open on screen.** The tablet only advertises itself, and
  only runs the USB/IP host, while the app is in the foreground.

Discovery, listing, attach and mount all succeeded through
`tools/windows/Connect-PerspectiveDrive.ps1`.

## Acceptance tests

| # | Test | Status |
| --- | --- | --- |
| 1 | SSK 2 TB SSD attaches three consecutive times without manual Device Manager work | One attach confirmed; repeat runs still to do |
| 2 | Windows reads the same data volume/files Android sees | **Confirmed** |
| 3 | Samsung + USB hub enumerates multiple downstream drives | Not tested |
| 4 | Drive A and Drive B can be shared independently | Not tested |
| 5 | Removing Drive A leaves Drive B online | Not tested |
| 6 | Windows can attach two exported storage devices concurrently | Not tested |
| 7 | Powered hub test with multiple high-draw SSD/HDD devices | Not tested |
| 8 | Sustained large-file copy both directions with the tablet screen off | Not tested |

Write performance and large-file integrity are the most important untested
areas: the run above proves reads work, not that sustained writes are safe.

## Important status
The bridge is proven end to end on real hardware for reading. It is still a
prototype: the Android artifact is a debug-signed TEST build until production
signing is configured, and the multi-drive, hub and sustained-write cases in the
table above have not been exercised. Do not trust it with the only copy of
anything until at least test 8 passes.
