package uk.co.perspectivestudio.usbbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Descriptor replay is what makes Windows enumerate the imported disk, so the
 * parser is pinned against a real bulk-only mass-storage descriptor set.
 */
class UsbDescriptorsTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** SSK-style USB 3 SATA bridge: 18-byte device descriptor + one configuration. */
    private fun massStorageRaw(): ByteArray {
        val device = bytes(
            0x12, 0x01, 0x00, 0x03, 0x00, 0x00, 0x00, 0x09,
            0x09, 0x21, 0x15, 0x07, 0x00, 0x01, 0x01, 0x02,
            0x03, 0x01
        )
        val config = bytes(
            0x09, 0x02, 0x2C, 0x00, 0x01, 0x01, 0x00, 0x80, 0x37,
            0x09, 0x04, 0x00, 0x00, 0x02, 0x08, 0x06, 0x50, 0x00,
            0x07, 0x05, 0x81, 0x02, 0x00, 0x04, 0x00,
            0x06, 0x30, 0x0F, 0x00, 0x00, 0x00,
            0x07, 0x05, 0x02, 0x02, 0x00, 0x04, 0x00,
            0x06, 0x30, 0x0F, 0x00, 0x00, 0x00
        )
        return device + config
    }

    @Test
    fun `parses device and configuration descriptors`() {
        val parsed = UsbDescriptors.parse(massStorageRaw())
        assertNotNull(parsed)
        parsed!!
        assertEquals(0x0300, parsed.bcdUsb)
        assertEquals(0x0100, parsed.bcdDevice)
        assertEquals(0x00, parsed.deviceClass)
        assertEquals(1, parsed.configurations.size)
        assertEquals(0x2C, parsed.configuration(0)!!.size)
        assertEquals(1, parsed.activeConfigurationValue)
        assertEquals(1, parsed.activeInterfaceCount)
        assertEquals(1, parsed.configurationCount)
    }

    @Test
    fun `finds a configuration by its bConfigurationValue`() {
        val parsed = UsbDescriptors.parse(massStorageRaw())!!
        assertNotNull(parsed.configurationByValue(1))
        assertNull(parsed.configurationByValue(2))
    }

    @Test
    fun `rejects truncated or non-device descriptor blobs`() {
        assertNull(UsbDescriptors.parse(null))
        assertNull(UsbDescriptors.parse(ByteArray(4)))
        // Right length, wrong bDescriptorType.
        assertNull(UsbDescriptors.parse(ByteArray(18).also { it[1] = 0x02 }))
        // Device descriptor with no configuration behind it is unusable.
        assertNull(UsbDescriptors.parse(massStorageRaw().copyOfRange(0, 18)))
    }

    @Test
    fun `stops at a configuration whose wTotalLength overruns the blob`() {
        val raw = massStorageRaw()
        raw[18 + 2] = 0xFF.toByte() // wTotalLength low byte -> 0x00FF
        assertNull(UsbDescriptors.parse(raw))
    }
}
