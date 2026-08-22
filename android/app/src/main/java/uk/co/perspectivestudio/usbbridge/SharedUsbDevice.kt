package uk.co.perspectivestudio.usbbridge

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import java.util.concurrent.atomic.AtomicBoolean

/** A physical Android USB device exported through the single USB/IP host. */
data class SharedUsbDevice(
    val device: UsbDevice,
    val connection: UsbDeviceConnection,
    val claimedInterfaces: List<Int>,
    val busNum: Int = 1,
    val devNum: Int = (device.deviceId and 0xFFFF).coerceAtLeast(1),
    val imported: AtomicBoolean = AtomicBoolean(false),
    val ioLock: Any = Any()
) {
    val busId: String = "$busNum-$devNum"
    val displayName: String = device.productName ?: "USB device"
}
