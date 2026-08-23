package uk.co.perspectivestudio.usbbridge

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import java.util.concurrent.atomic.AtomicBoolean

/** A physical Android USB device exported through the single USB/IP host. */
class SharedUsbDevice(
    val device: UsbDevice,
    val connection: UsbDeviceConnection,
    val claimedInterfaces: List<UsbInterface>,
    val descriptors: UsbDescriptors?,
    val busNum: Int,
    val devNum: Int
) {
    val imported: AtomicBoolean = AtomicBoolean(false)
    val busId: String = "$busNum-$devNum"
    val displayName: String = device.productName?.takeIf { it.isNotBlank() } ?: "USB device"

    /** Speed code handed to the Windows virtual host controller. */
    val speed: Int = UsbDescriptors.speedOf(device, descriptors)

    /** Currently selected alternate setting per interface number. */
    private val alternateSettings = HashMap<Int, Int>()

    @Synchronized
    fun alternateSetting(interfaceNumber: Int): Int = alternateSettings[interfaceNumber] ?: 0

    @Synchronized
    fun setAlternateSetting(interfaceNumber: Int, alternate: Int) {
        alternateSettings[interfaceNumber] = alternate
    }
}
