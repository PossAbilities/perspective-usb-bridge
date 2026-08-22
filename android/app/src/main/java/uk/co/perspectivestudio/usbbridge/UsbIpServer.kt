package uk.co.perspectivestudio.usbbridge

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * USB/IP server focused on Android USB-host storage devices.
 *
 * One server owns TCP/3240 and advertises every currently shared downstream
 * device. This is deliberately hub-friendly: a USB hub may expose several
 * independent drives, each with its own bus ID and import lock.
 */
class UsbIpServer(
    private val devices: () -> List<SharedUsbDevice>,
    private val onEvent: (String) -> Unit = {}
) {
    companion object {
        const val PORT = 3240

        private const val USBIP_VERSION = 0x0111
        private const val OP_REQ_IMPORT = 0x8003
        private const val OP_REP_IMPORT = 0x0003
        private const val OP_REQ_DEVLIST = 0x8005
        private const val OP_REP_DEVLIST = 0x0005

        private const val USBIP_CMD_SUBMIT = 0x00000001
        private const val USBIP_CMD_UNLINK = 0x00000002
        private const val USBIP_RET_SUBMIT = 0x00000003
        private const val USBIP_RET_UNLINK = 0x00000004

        private const val USBIP_DIR_OUT = 0
        private const val USBIP_DIR_IN = 1

        private const val STATUS_OK = 0
        private const val STATUS_ERROR = 1
        private const val ERR_IO = -5
        private const val ERR_CONNRESET = -104

        private const val HEADER_SIZE = 48
        private const val TRANSFER_TIMEOUT_MS = 30_000
        private const val MAX_TRANSFER_SIZE = 16 * 1024 * 1024
    }

    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        serverSocket = ServerSocket(PORT).apply { reuseAddress = true }
        onEvent("USB/IP host listening on TCP $PORT")
        pool.execute {
            while (running.get()) {
                try {
                    val socket = serverSocket?.accept() ?: break
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    pool.execute { handleClient(socket) }
                } catch (e: IOException) {
                    if (running.get()) onEvent("Accept error: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        pool.shutdownNow()
        onEvent("USB/IP host stopped")
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            val input = DataInputStream(BufferedInputStream(client.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(client.getOutputStream()))
            try {
                val version = input.readUnsignedShort()
                val command = input.readUnsignedShort()
                input.readInt()
                onEvent("${client.inetAddress.hostAddress}: op=0x${command.toString(16)} v=0x${version.toString(16)}")

                when (command) {
                    OP_REQ_DEVLIST -> {
                        writeDevList(output)
                        output.flush()
                    }
                    OP_REQ_IMPORT -> {
                        val requestedBusId = readFixedString(input, 32)
                        val exported = devices().firstOrNull { it.busId == requestedBusId }
                        if (exported == null || !exported.imported.compareAndSet(false, true)) {
                            writeOperationHeader(output, OP_REP_IMPORT, STATUS_ERROR)
                            output.flush()
                            return
                        }
                        try {
                            writeOperationHeader(output, OP_REP_IMPORT, STATUS_OK)
                            writeDevice(output, exported, includeInterfaces = false)
                            output.flush()
                            onEvent("Windows imported ${exported.displayName} (${exported.busId})")
                            handleUrbStream(exported, input, output)
                        } finally {
                            exported.imported.set(false)
                            onEvent("Windows released ${exported.displayName} (${exported.busId})")
                        }
                    }
                    else -> onEvent("Unsupported operation 0x${command.toString(16)}")
                }
            } catch (_: EOFException) {
                onEvent("Client disconnected")
            } catch (e: Exception) {
                onEvent("Client error: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun writeDevList(out: DataOutputStream) {
        val snapshot = devices()
        writeOperationHeader(out, OP_REP_DEVLIST, STATUS_OK)
        out.writeInt(snapshot.size)
        snapshot.forEach { writeDevice(out, it, includeInterfaces = true) }
        onEvent("Advertised ${snapshot.size} shared USB device${if (snapshot.size == 1) "" else "s"}")
    }

    private fun writeOperationHeader(out: DataOutputStream, code: Int, status: Int) {
        out.writeShort(USBIP_VERSION)
        out.writeShort(code)
        out.writeInt(status)
    }

    private fun writeDevice(out: DataOutputStream, exported: SharedUsbDevice, includeInterfaces: Boolean) {
        val device = exported.device
        writeFixedString(out, "/android/usb/${exported.busId}", 256)
        writeFixedString(out, exported.busId, 32)
        out.writeInt(exported.busNum)
        out.writeInt(exported.devNum)
        out.writeInt(usbSpeed())
        out.writeShort(device.vendorId)
        out.writeShort(device.productId)
        out.writeShort(bcdDevice(device))
        out.writeByte(device.deviceClass)
        out.writeByte(device.deviceSubclass)
        out.writeByte(device.deviceProtocol)
        out.writeByte(configurationValue(device))
        out.writeByte(device.configurationCount.coerceAtLeast(1))
        out.writeByte(device.interfaceCount)

        if (includeInterfaces) {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                out.writeByte(intf.interfaceClass)
                out.writeByte(intf.interfaceSubclass)
                out.writeByte(intf.interfaceProtocol)
                out.writeByte(0)
            }
        }
    }

    private fun handleUrbStream(exported: SharedUsbDevice, input: DataInputStream, output: DataOutputStream) {
        while (running.get()) {
            val header = ByteArray(HEADER_SIZE)
            try {
                input.readFully(header)
            } catch (_: EOFException) {
                return
            }
            val h = NetworkHeader(header)
            when (h.command) {
                USBIP_CMD_SUBMIT -> handleSubmit(exported, h, input, output)
                USBIP_CMD_UNLINK -> handleUnlink(h, output)
                else -> throw IOException("Unknown USB/IP command 0x${h.command.toString(16)}")
            }
            output.flush()
        }
    }

    private fun handleSubmit(exported: SharedUsbDevice, h: NetworkHeader, input: DataInputStream, output: DataOutputStream) {
        val transferLength = h.intAt(24)
        if (transferLength < 0 || transferLength > MAX_TRANSFER_SIZE) {
            throw IOException("Invalid USB transfer length: $transferLength")
        }
        val setup = h.bytesAt(40, 8)
        val outbound = if (h.direction == USBIP_DIR_OUT && transferLength > 0) {
            ByteArray(transferLength).also { input.readFully(it) }
        } else null

        val result = if (h.endpoint == 0) {
            controlTransfer(exported, h.direction, setup, transferLength, outbound)
        } else {
            endpointTransfer(exported, h.endpoint, h.direction, transferLength, outbound)
        }
        writeRetSubmit(output, h.seqNum, result.status, result.actualLength, result.data)
    }

    private fun controlTransfer(
        exported: SharedUsbDevice,
        direction: Int,
        setup: ByteArray,
        requestedLength: Int,
        outbound: ByteArray?
    ): TransferResult {
        val requestType = setup[0].toInt() and 0xFF
        val request = setup[1].toInt() and 0xFF
        val value = le16(setup, 2)
        val index = le16(setup, 4)
        val setupLength = le16(setup, 6)
        val size = minOf(requestedLength, setupLength).coerceAtLeast(0)
        val buffer = if (direction == USBIP_DIR_IN) ByteArray(size) else outbound ?: ByteArray(size)

        val transferred = synchronized(exported.ioLock) {
            exported.connection.controlTransfer(requestType, request, value, index, buffer, size, TRANSFER_TIMEOUT_MS)
        }
        if (transferred < 0) return TransferResult(ERR_IO, 0, null)
        return if (direction == USBIP_DIR_IN) TransferResult(0, transferred, buffer.copyOf(transferred))
        else TransferResult(0, transferred, null)
    }

    private fun endpointTransfer(
        exported: SharedUsbDevice,
        endpointNumber: Int,
        direction: Int,
        requestedLength: Int,
        outbound: ByteArray?
    ): TransferResult {
        val endpoint = findEndpoint(exported.device, endpointNumber, direction) ?: return TransferResult(ERR_IO, 0, null)
        val buffer = if (direction == USBIP_DIR_IN) ByteArray(requestedLength) else outbound ?: ByteArray(requestedLength)
        val transferred = synchronized(exported.ioLock) {
            exported.connection.bulkTransfer(endpoint, buffer, buffer.size, TRANSFER_TIMEOUT_MS)
        }
        if (transferred < 0) return TransferResult(ERR_IO, 0, null)
        return if (direction == USBIP_DIR_IN) TransferResult(0, transferred, buffer.copyOf(transferred))
        else TransferResult(0, transferred, null)
    }

    private fun handleUnlink(h: NetworkHeader, output: DataOutputStream) {
        writeRetUnlink(output, h.seqNum, ERR_CONNRESET)
    }

    private fun writeRetSubmit(out: DataOutputStream, seqNum: Int, status: Int, actualLength: Int, data: ByteArray?) {
        out.writeInt(USBIP_RET_SUBMIT)
        out.writeInt(seqNum)
        out.writeInt(0)
        out.writeInt(0)
        out.writeInt(0)
        out.writeInt(status)
        out.writeInt(actualLength)
        out.writeInt(0)
        out.writeInt(-1)
        out.writeInt(0)
        out.writeLong(0L)
        if (data != null && data.isNotEmpty()) out.write(data)
    }

    private fun writeRetUnlink(out: DataOutputStream, seqNum: Int, status: Int) {
        out.writeInt(USBIP_RET_UNLINK)
        out.writeInt(seqNum)
        out.writeInt(0)
        out.writeInt(0)
        out.writeInt(0)
        out.writeInt(status)
        repeat(6) { out.writeInt(0) }
    }

    private fun findEndpoint(device: UsbDevice, number: Int, direction: Int): UsbEndpoint? {
        val androidDirection = if (direction == USBIP_DIR_IN) UsbConstants.USB_DIR_IN else UsbConstants.USB_DIR_OUT
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            for (j in 0 until intf.endpointCount) {
                val endpoint = intf.getEndpoint(j)
                if (endpoint.endpointNumber == number && endpoint.direction == androidDirection) return endpoint
            }
        }
        return null
    }

    private fun configurationValue(device: UsbDevice): Int = if (device.configurationCount > 0) {
        runCatching { device.getConfiguration(0).id }.getOrDefault(1)
    } else 1

    private fun bcdDevice(device: UsbDevice): Int {
        val parts = (device.version ?: "1.00").split('.')
        val major = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 99) ?: 1
        val minor = parts.getOrNull(1)?.filter(Char::isDigit)?.take(2)?.padEnd(2, '0')?.toIntOrNull() ?: 0
        return ((major / 10) shl 12) or ((major % 10) shl 8) or ((minor / 10) shl 4) or (minor % 10)
    }

    private fun usbSpeed(): Int = 3

    private fun le16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readFixedString(input: DataInputStream, length: Int): String {
        val bytes = ByteArray(length)
        input.readFully(bytes)
        val end = bytes.indexOf(0).let { if (it < 0) bytes.size else it }
        return String(bytes, 0, end, StandardCharsets.US_ASCII)
    }

    private fun writeFixedString(out: DataOutputStream, value: String, length: Int) {
        val raw = value.toByteArray(StandardCharsets.US_ASCII)
        val count = minOf(raw.size, length - 1)
        out.write(raw, 0, count)
        repeat(length - count) { out.writeByte(0) }
    }

    private data class TransferResult(val status: Int, val actualLength: Int, val data: ByteArray?)

    private class NetworkHeader(private val bytes: ByteArray) {
        val command: Int get() = intAt(0)
        val seqNum: Int get() = intAt(4)
        val direction: Int get() = intAt(12)
        val endpoint: Int get() = intAt(16)

        fun intAt(offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)

        fun bytesAt(offset: Int, length: Int): ByteArray = bytes.copyOfRange(offset, offset + length)
    }
}
