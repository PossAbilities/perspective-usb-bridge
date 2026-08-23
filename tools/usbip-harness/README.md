# USB/IP protocol harness

Runs the Android app's real USB/IP server on a plain JVM, against a simulated
USB mass-storage device, and asserts the exact bytes it puts on the wire.

```
cd tools/usbip-harness
gradle run
```

No Android SDK, emulator, tablet or drive is needed. `src/main/kotlin/android/`
contains just enough of `android.hardware.usb` to compile; everything under
`uk.co.perspectivestudio.usbbridge` is compiled straight from
`android/app/src/main/java`, so the harness cannot drift from the shipping code.

What it pins down:

- `OP_REP_DEVLIST` field-by-field, including that the stream ends exactly where
  the client expects it to. A single byte of drift here makes `usbip list -r`
  print nonsense or nothing at all.
- `OP_REP_IMPORT` and the device record that follows it.
- `GET_DESCRIPTOR` for the device and configuration descriptors is answered from
  the descriptors Linux cached, byte for byte.
- `SET_CONFIGURATION`, `GET_CONFIGURATION`, `SET_INTERFACE` and `GET_INTERFACE`
  succeed. These are the requests usbfs rejects with `EBUSY` once interfaces are
  claimed, which used to abort Windows enumeration before a disk ever appeared.
- Unsupported requests return `-EPIPE` (a stall) rather than `-EIO`, so Windows
  treats them as "not supported" and carries on.
- Bulk transfers larger than usbfs's 16 KiB limit are split into chunks and
  reassembled intact, in both directions.
- A slow bulk read does not block control traffic on the same device.
- `USBIP_CMD_UNLINK` reports `-ECONNRESET` for an in-flight URB and `0` for one
  that already finished, and no late `RET_SUBMIT` follows an unlinked URB.
