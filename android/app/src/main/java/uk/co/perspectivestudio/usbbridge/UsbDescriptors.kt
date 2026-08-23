package uk.co.perspectivestudio.usbbridge

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice

/**
 * Cached view of a device's real USB descriptors.
 *
 * Windows re-enumerates the imported device from scratch, so it asks for the
 * device and configuration descriptors before it will ever create a disk. Those
 * answers must be byte-exact. Round-tripping GET_DESCRIPTOR to the hardware is
 * unreliable once interfaces are claimed, so the bytes Linux already cached for
 * us (via UsbDeviceConnection.getRawDescriptors) are replayed instead.
 */
class UsbDescriptors private constructor(
    val raw: ByteArray,
    val device: ByteArray,
    val configurations: List<ByteArray>
) {
    companion object {
        private const val DEVICE_DESCRIPTOR_LENGTH = 18

        fun parse(raw: ByteArray?): UsbDescriptors? {
            if (raw == null || raw.size < DEVICE_DESCRIPTOR_LENGTH) return null
            val device = raw.copyOfRange(0, DEVICE_DESCRIPTOR_LENGTH)
            if ((device[1].toInt() and 0xFF) != 0x01) return null

            val configurations = mutableListOf<ByteArray>()
            var offset = DEVICE_DESCRIPTOR_LENGTH
            while (offset + 4 <= raw.size) {
                val length = raw[offset].toInt() and 0xFF
                val type = raw[offset + 1].toInt() and 0xFF
                if (length < 9 || type != 0x02) break
                val total = (raw[offset + 2].toInt() and 0xFF) or ((raw[offset + 3].toInt() and 0xFF) shl 8)
                if (total < length || offset + total > raw.size) break
                configurations += raw.copyOfRange(offset, offset + total)
                offset += total
            }
            if (configurations.isEmpty()) return null
            return UsbDescriptors(raw, device, configurations)
        }

        /**
         * USB/IP speed codes, matching the kernel's `usb_device_speed` enum:
         * 1 = low, 2 = full, 3 = high, 5 = super.
         */
        fun speedOf(device: UsbDevice, descriptors: UsbDescriptors?): Int {
            var maxBulkPacket = 0
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                for (j in 0 until intf.endpointCount) {
                    val endpoint = intf.getEndpoint(j)
                    if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        maxBulkPacket = maxOf(maxBulkPacket, endpoint.maxPacketSize)
                    }
                }
            }
            // Bulk max-packet size is fixed by the negotiated link speed, so it is a
            // more truthful signal than bcdUSB (which reports what the device can do,
            // not what the tablet's port actually negotiated).
            when {
                maxBulkPacket >= 1024 -> return 5
                maxBulkPacket >= 512 -> return 3
                maxBulkPacket in 1..64 -> return 2
            }
            val bcdUsb = descriptors?.bcdUsb ?: 0x0200
            return when {
                bcdUsb >= 0x0300 -> 5
                bcdUsb >= 0x0200 -> 3
                else -> 2
            }
        }
    }

    val bcdUsb: Int get() = le16(device, 2)
    val bcdDevice: Int get() = le16(device, 12)
    val deviceClass: Int get() = device[4].toInt() and 0xFF
    val deviceSubclass: Int get() = device[5].toInt() and 0xFF
    val deviceProtocol: Int get() = device[6].toInt() and 0xFF
    val configurationCount: Int get() = (device[17].toInt() and 0xFF).coerceAtLeast(configurations.size)

    /** bConfigurationValue of the configuration Linux currently has selected. */
    val activeConfigurationValue: Int get() = configurations.firstOrNull()?.let { it[5].toInt() and 0xFF } ?: 1

    val activeInterfaceCount: Int get() = configurations.firstOrNull()?.let { it[4].toInt() and 0xFF } ?: 0

    fun configuration(index: Int): ByteArray? = configurations.getOrNull(index)

    fun configurationByValue(value: Int): ByteArray? =
        configurations.firstOrNull { (it[5].toInt() and 0xFF) == value }

    private fun le16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}
