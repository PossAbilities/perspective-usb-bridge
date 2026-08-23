package android.hardware.usb

object UsbConstants {
    const val USB_DIR_IN = 0x80
    const val USB_DIR_OUT = 0
    const val USB_ENDPOINT_XFER_BULK = 2
    const val USB_CLASS_HUB = 9
    const val USB_CLASS_MASS_STORAGE = 8
    const val USB_CLASS_AUDIO = 1
}

open class UsbEndpoint(
    val endpointNumber: Int,
    val direction: Int,
    val type: Int,
    val maxPacketSize: Int
)

open class UsbInterface(
    val id: Int,
    val alternateSetting: Int,
    val interfaceClass: Int,
    val interfaceSubclass: Int,
    val interfaceProtocol: Int,
    private val endpoints: List<UsbEndpoint>
) {
    val endpointCount: Int get() = endpoints.size
    fun getEndpoint(index: Int): UsbEndpoint = endpoints[index]
}

open class UsbDevice(
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val deviceClass: Int,
    val deviceSubclass: Int,
    val deviceProtocol: Int,
    val configurationCount: Int,
    val productName: String?,
    val version: String?,
    private val interfaces: List<UsbInterface>
) {
    val interfaceCount: Int get() = interfaces.size
    fun getInterface(index: Int): UsbInterface = interfaces[index]
}

abstract class UsbDeviceConnection {
    abstract val rawDescriptors: ByteArray?
    abstract fun controlTransfer(
        requestType: Int, request: Int, value: Int, index: Int,
        buffer: ByteArray?, length: Int, timeout: Int
    ): Int
    abstract fun bulkTransfer(
        endpoint: UsbEndpoint, buffer: ByteArray?, offset: Int, length: Int, timeout: Int
    ): Int
    abstract fun setInterface(intf: UsbInterface): Boolean
    open fun claimInterface(intf: UsbInterface, force: Boolean): Boolean = true
    open fun releaseInterface(intf: UsbInterface): Boolean = true
    open fun close() {}
}
