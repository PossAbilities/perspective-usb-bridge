package uk.co.perspectivestudio.usbbridge

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbEndpoint
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * USB/IP server focused on Android USB-host storage devices.
 *
 * One server owns TCP/3240 and advertises every currently shared downstream
 * device. This is deliberately hub-friendly: a USB hub may expose several
 * independent drives, each with its own bus ID.
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

        // Negative errno values, exactly as the Linux USB/IP stack reports them.
        private const val ERR_EIO = -5
        private const val ERR_EINVAL = -22
        private const val ERR_EPIPE = -32
        private const val ERR_ECONNRESET = -104
        private const val ERR_ETIMEDOUT = -110

        private const val HEADER_SIZE = 48
        private const val ISO_PACKET_DESCRIPTOR_SIZE = 16

        private const val CONTROL_TIMEOUT_MS = 5_000
        private const val DATA_TIMEOUT_MS = 20_000

        /**
         * Linux usbfs refuses a single bulk URB larger than 16 KiB, and Android's
         * bulkTransfer maps straight onto it. Windows routinely asks for 64 KiB and
         * larger reads, so every transfer is split into usbfs-sized chunks.
         */
        private const val MAX_BULK_CHUNK = 16 * 1024
        private const val MAX_TRANSFER_SIZE = 32 * 1024 * 1024

        // Standard USB requests handled locally rather than on the wire.
        private const val REQ_GET_STATUS = 0x00
        private const val REQ_CLEAR_FEATURE = 0x01
        private const val REQ_SET_FEATURE = 0x03
        private const val REQ_SET_ADDRESS = 0x05
        private const val REQ_GET_DESCRIPTOR = 0x06
        private const val REQ_GET_CONFIGURATION = 0x08
        private const val REQ_SET_CONFIGURATION = 0x09
        private const val REQ_GET_INTERFACE = 0x0A
        private const val REQ_SET_INTERFACE = 0x0B

        private const val DESC_DEVICE = 0x01
        private const val DESC_CONFIGURATION = 0x02
    }

    private val running = AtomicBoolean(false)
    private val accepting = Executors.newCachedThreadPool()
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val socket = ServerSocket()
        socket.reuseAddress = true // must be set before bind() to have any effect
        try {
            socket.bind(InetSocketAddress(PORT), 16)
        } catch (e: IOException) {
            running.set(false)
            runCatching { socket.close() }
            throw e
        }
        serverSocket = socket
        onEvent("USB/IP host listening on TCP $PORT")
        accepting.execute {
            while (running.get()) {
                try {
                    val client = socket.accept()
                    client.tcpNoDelay = true
                    client.keepAlive = true
                    accepting.execute { handleClient(client) }
                } catch (e: IOException) {
                    if (running.get()) onEvent("Accept error: ${e.message}")
                    if (socket.isClosed) break
                }
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        accepting.shutdownNow()
        onEvent("USB/IP host stopped")
    }

    // ---------------------------------------------------------------- handshake

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            val input = DataInputStream(BufferedInputStream(client.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(client.getOutputStream()))
            val peer = client.inetAddress.hostAddress ?: "client"
            try {
                val version = input.readUnsignedShort()
                val command = input.readUnsignedShort()
                input.readInt() // status field of the request, always zero
                onEvent("$peer: op=0x${command.toString(16)} v=0x${version.toString(16)}")

                when (command) {
                    OP_REQ_DEVLIST -> {
                        writeDevList(output)
                        output.flush()
                    }
                    OP_REQ_IMPORT -> handleImport(input, output, readFixedString(input, 32))
                    else -> {
                        onEvent("Unsupported operation 0x${command.toString(16)}")
                        writeOperationHeader(output, OP_REP_IMPORT, STATUS_ERROR)
                        output.flush()
                    }
                }
            } catch (_: EOFException) {
                onEvent("$peer disconnected")
            } catch (e: Exception) {
                onEvent("Client error: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun handleImport(input: DataInputStream, output: DataOutputStream, requestedBusId: String) {
        val exported = devices().firstOrNull { it.busId == requestedBusId }
        if (exported == null) {
            onEvent("Windows asked for $requestedBusId, which is not shared")
            writeOperationHeader(output, OP_REP_IMPORT, STATUS_ERROR)
            output.flush()
            return
        }
        if (!exported.imported.compareAndSet(false, true)) {
            onEvent("${exported.displayName} is already connected to another PC")
            writeOperationHeader(output, OP_REP_IMPORT, STATUS_ERROR)
            output.flush()
            return
        }
        try {
            writeOperationHeader(output, OP_REP_IMPORT, STATUS_OK)
            writeDevice(output, exported, includeInterfaces = false)
            output.flush()
            onEvent("Windows imported ${exported.displayName} (${exported.busId})")
            UrbSession(exported, input, output).run()
        } finally {
            exported.imported.set(false)
            onEvent("Windows released ${exported.displayName} (${exported.busId})")
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
        val descriptors = exported.descriptors
        val interfaceCount = descriptors?.activeInterfaceCount?.takeIf { it > 0 } ?: device.interfaceCount

        writeFixedString(out, "/sys/devices/platform/usb/${exported.busId}", 256)
        writeFixedString(out, exported.busId, 32)
        out.writeInt(exported.busNum)
        out.writeInt(exported.devNum)
        out.writeInt(exported.speed)
        out.writeShort(device.vendorId)
        out.writeShort(device.productId)
        out.writeShort(descriptors?.bcdDevice ?: 0x0100)
        out.writeByte(descriptors?.deviceClass ?: device.deviceClass)
        out.writeByte(descriptors?.deviceSubclass ?: device.deviceSubclass)
        out.writeByte(descriptors?.deviceProtocol ?: device.deviceProtocol)
        out.writeByte(descriptors?.activeConfigurationValue ?: 1)
        out.writeByte((descriptors?.configurationCount ?: device.configurationCount).coerceAtLeast(1))
        out.writeByte(interfaceCount)

        if (!includeInterfaces) return
        // usbip's device list carries one 4-byte record per interface of the
        // active configuration. The count above must match exactly or the client
        // loses stream sync and reports garbage devices.
        val written = mutableListOf<Triple<Int, Int, Int>>()
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.alternateSetting != 0) continue
            written += Triple(intf.interfaceClass, intf.interfaceSubclass, intf.interfaceProtocol)
        }
        for (i in 0 until interfaceCount) {
            val record = written.getOrNull(i) ?: Triple(0, 0, 0)
            out.writeByte(record.first)
            out.writeByte(record.second)
            out.writeByte(record.third)
            out.writeByte(0) // padding
        }
    }

    // -------------------------------------------------------------- URB session

    /**
     * Pumps USB/IP URBs for one imported device.
     *
     * Reading is single-threaded (payloads are inline on the socket, so header
     * order matters), but each USB pipe is serviced by its own worker. Without
     * that, a blocking 20-second bulk read would also stall control traffic and
     * unlink handling, which is exactly what makes Windows give up on the disk.
     */
    private inner class UrbSession(
        private val exported: SharedUsbDevice,
        private val input: DataInputStream,
        private val output: DataOutputStream
    ) {
        private val active = AtomicBoolean(true)
        private val writeLock = Any()
        private val pipes = ConcurrentHashMap<Int, ExecutorService>()
        private val inFlight = ConcurrentHashMap<Int, AtomicBoolean>()

        fun run() {
            try {
                loop()
            } finally {
                active.set(false)
                pipes.values.forEach { it.shutdown() }
                pipes.values.forEach { runCatching { it.awaitTermination(3, TimeUnit.SECONDS) } }
                pipes.values.forEach { it.shutdownNow() }
                pipes.clear()
            }
        }

        private fun loop() {
            val header = ByteArray(HEADER_SIZE)
            while (running.get() && active.get()) {
                try {
                    input.readFully(header)
                } catch (_: EOFException) {
                    return
                } catch (_: IOException) {
                    return
                }
                val h = NetworkHeader(header.copyOf())
                when (h.command) {
                    USBIP_CMD_SUBMIT -> if (!readAndDispatchSubmit(h)) return
                    USBIP_CMD_UNLINK -> handleUnlink(h)
                    else -> {
                        onEvent("Unknown USB/IP command 0x${h.command.toString(16)}; closing link")
                        return
                    }
                }
            }
        }

        /** @return false when the stream can no longer be trusted and must close. */
        private fun readAndDispatchSubmit(h: NetworkHeader): Boolean {
            val transferLength = h.transferBufferLength
            val numberOfPackets = h.numberOfPackets

            if (transferLength < 0 || transferLength > MAX_TRANSFER_SIZE) {
                onEvent("Rejecting implausible USB transfer length $transferLength")
                return false
            }

            // Isochronous URBs carry per-packet descriptors we cannot service on
            // Android's synchronous API. Drain them so the stream stays in sync,
            // then fail just that URB instead of dropping the whole device.
            if (numberOfPackets > 0) {
                runCatching {
                    input.skipFully(numberOfPackets.toLong() * ISO_PACKET_DESCRIPTOR_SIZE)
                    if (h.direction == USBIP_DIR_OUT && transferLength > 0) input.skipFully(transferLength.toLong())
                }.onFailure { return false }
                respondSubmit(h.seqNum, ERR_EINVAL, 0, null)
                return true
            }

            val outbound = if (h.direction == USBIP_DIR_OUT && transferLength > 0) {
                val payload = ByteArray(transferLength)
                try {
                    input.readFully(payload)
                } catch (_: IOException) {
                    return false
                }
                payload
            } else null

            val cancelled = AtomicBoolean(false)
            inFlight[h.seqNum] = cancelled
            val setup = h.setup
            val isControl = h.endpoint == 0

            pipe(h.endpoint, h.direction).execute {
                val result = try {
                    if (isControl) {
                        controlTransfer(setup, transferLength, outbound)
                    } else {
                        dataTransfer(h.endpoint, h.direction, transferLength, outbound)
                    }
                } catch (e: Exception) {
                    TransferResult(ERR_EIO, 0, null)
                }
                inFlight.remove(h.seqNum)
                // If Windows already unlinked this URB it has been told the URB is
                // gone; sending a late RET_SUBMIT would desynchronise the client.
                if (!cancelled.get()) respondSubmit(h.seqNum, result.status, result.actualLength, result.data)
            }
            return true
        }

        private fun handleUnlink(h: NetworkHeader) {
            val victim = h.unlinkSeqNum
            val pending = inFlight.remove(victim)
            // -ECONNRESET means "unlinked"; 0 means "already completed, nothing to do".
            val status = if (pending != null) {
                pending.set(true)
                ERR_ECONNRESET
            } else {
                0
            }
            synchronized(writeLock) {
                output.writeInt(USBIP_RET_UNLINK)
                output.writeInt(h.seqNum)
                output.writeInt(0)
                output.writeInt(0)
                output.writeInt(0)
                output.writeInt(status)
                repeat(6) { output.writeInt(0) }
                output.flush()
            }
        }

        private fun respondSubmit(seqNum: Int, status: Int, actualLength: Int, data: ByteArray?) {
            try {
                synchronized(writeLock) {
                    output.writeInt(USBIP_RET_SUBMIT)
                    output.writeInt(seqNum)
                    output.writeInt(0) // devid
                    output.writeInt(0) // direction
                    output.writeInt(0) // ep
                    output.writeInt(status)
                    output.writeInt(actualLength)
                    output.writeInt(0)  // start_frame
                    output.writeInt(-1) // number_of_packets: 0xffffffff for non-isochronous
                    output.writeInt(0)  // error_count
                    output.writeLong(0L) // setup padding
                    if (data != null && data.isNotEmpty()) output.write(data, 0, minOf(actualLength, data.size))
                    output.flush()
                }
            } catch (_: IOException) {
                active.set(false)
            }
        }

        private fun pipe(endpoint: Int, direction: Int): ExecutorService {
            val key = if (endpoint == 0) 0 else (endpoint shl 1) or direction
            return pipes.getOrPut(key) {
                Executors.newSingleThreadExecutor { r ->
                    Thread(r, "usbip-${exported.busId}-ep$key").apply { isDaemon = true }
                }
            }
        }

        // ------------------------------------------------------------ transfers

        private fun controlTransfer(setup: ByteArray, requestedLength: Int, outbound: ByteArray?): TransferResult {
            val requestType = setup[0].toInt() and 0xFF
            val request = setup[1].toInt() and 0xFF
            val value = le16(setup, 2)
            val index = le16(setup, 4)
            val length = le16(setup, 6)
            val dirIn = (requestType and 0x80) != 0
            val isStandard = ((requestType shr 5) and 0x03) == 0
            val wanted = minOf(requestedLength, length).coerceAtLeast(0)

            if (isStandard) {
                emulateStandardRequest(requestType, request, value, index, wanted)?.let { return it }
            }
            return rawControlTransfer(requestType, request, value, index, dirIn, wanted, outbound)
        }

        /** @return a result when the request is answered locally, null to go to the wire. */
        private fun emulateStandardRequest(
            requestType: Int,
            request: Int,
            value: Int,
            index: Int,
            wanted: Int
        ): TransferResult? {
            val descriptors = exported.descriptors
            when (request) {
                REQ_GET_DESCRIPTOR -> {
                    if (descriptors == null) return null
                    val type = (value shr 8) and 0xFF
                    val descIndex = value and 0xFF
                    val bytes = when (type) {
                        DESC_DEVICE -> descriptors.device
                        DESC_CONFIGURATION -> descriptors.configuration(descIndex) ?: return TransferResult(ERR_EPIPE, 0, null)
                        else -> return null // strings, BOS, qualifiers: ask the hardware
                    }
                    val size = minOf(wanted, bytes.size)
                    return TransferResult(0, size, bytes.copyOf(size))
                }
                REQ_SET_CONFIGURATION -> {
                    val requested = value and 0xFF
                    // Interfaces are already claimed, so usbfs would answer EBUSY.
                    // Accept the configuration Linux has actually selected.
                    val current = descriptors?.activeConfigurationValue ?: 1
                    return if (requested == 0 || requested == current || descriptors?.configurationByValue(requested) != null) {
                        TransferResult(0, 0, null)
                    } else {
                        TransferResult(ERR_EPIPE, 0, null)
                    }
                }
                REQ_GET_CONFIGURATION -> {
                    val current = descriptors?.activeConfigurationValue ?: 1
                    return TransferResult(0, minOf(wanted, 1), byteArrayOf(current.toByte()))
                }
                REQ_SET_INTERFACE -> {
                    val device = exported.device
                    val target = (0 until device.interfaceCount)
                        .map { device.getInterface(it) }
                        .firstOrNull { it.id == index && it.alternateSetting == value }
                    if (target == null) return TransferResult(ERR_EPIPE, 0, null)
                    val ok = runCatching { exported.connection.setInterface(target) }.getOrDefault(false)
                    return if (ok || value == exported.alternateSetting(index)) {
                        exported.setAlternateSetting(index, value)
                        TransferResult(0, 0, null)
                    } else {
                        TransferResult(ERR_EPIPE, 0, null)
                    }
                }
                REQ_GET_INTERFACE -> {
                    val alt = exported.alternateSetting(index)
                    return TransferResult(0, minOf(wanted, 1), byteArrayOf(alt.toByte()))
                }
                REQ_SET_ADDRESS -> return TransferResult(0, 0, null) // owned by the real host
                REQ_GET_STATUS, REQ_CLEAR_FEATURE, REQ_SET_FEATURE -> return null // usbfs handles these
                else -> return null
            }
        }

        private fun rawControlTransfer(
            requestType: Int,
            request: Int,
            value: Int,
            index: Int,
            dirIn: Boolean,
            wanted: Int,
            outbound: ByteArray?
        ): TransferResult {
            val buffer = if (dirIn) ByteArray(wanted) else (outbound ?: ByteArray(0))
            val size = if (dirIn) wanted else buffer.size
            val started = System.currentTimeMillis()
            val transferred = runCatching {
                exported.connection.controlTransfer(
                    requestType, request, value, index, buffer, size, CONTROL_TIMEOUT_MS
                )
            }.getOrDefault(-1)
            if (transferred < 0) {
                val elapsed = System.currentTimeMillis() - started
                // A stall is a legitimate "not supported" answer; reporting EPIPE lets
                // Windows continue enumerating instead of abandoning the device.
                return TransferResult(if (elapsed >= CONTROL_TIMEOUT_MS) ERR_ETIMEDOUT else ERR_EPIPE, 0, null)
            }
            return if (dirIn) {
                TransferResult(0, transferred, buffer.copyOf(transferred.coerceIn(0, buffer.size)))
            } else {
                TransferResult(0, transferred, null)
            }
        }

        private fun dataTransfer(
            endpointNumber: Int,
            direction: Int,
            requestedLength: Int,
            outbound: ByteArray?
        ): TransferResult {
            val dirIn = direction == USBIP_DIR_IN
            val endpoint = findEndpoint(endpointNumber, dirIn)
                ?: return TransferResult(ERR_EPIPE, 0, null)
            val connection = exported.connection
            val started = System.currentTimeMillis()

            if (dirIn) {
                val buffer = ByteArray(requestedLength)
                var offset = 0
                while (offset < requestedLength) {
                    val chunk = minOf(MAX_BULK_CHUNK, requestedLength - offset)
                    val n = connection.bulkTransfer(endpoint, buffer, offset, chunk, DATA_TIMEOUT_MS)
                    if (n < 0) {
                        if (offset > 0) break // partial data is still useful to the host
                        return TransferResult(timeoutOrStall(started, DATA_TIMEOUT_MS), 0, null)
                    }
                    offset += n
                    if (n < chunk) break // short packet terminates a USB transfer
                }
                return TransferResult(0, offset, buffer.copyOf(offset))
            }

            val payload = outbound ?: ByteArray(0)
            if (payload.isEmpty()) {
                // Zero-length packet.
                val n = connection.bulkTransfer(endpoint, payload, 0, 0, DATA_TIMEOUT_MS)
                return if (n < 0) TransferResult(timeoutOrStall(started, DATA_TIMEOUT_MS), 0, null)
                else TransferResult(0, 0, null)
            }
            var offset = 0
            while (offset < payload.size) {
                val chunk = minOf(MAX_BULK_CHUNK, payload.size - offset)
                val n = connection.bulkTransfer(endpoint, payload, offset, chunk, DATA_TIMEOUT_MS)
                if (n < 0) {
                    if (offset > 0) break
                    return TransferResult(timeoutOrStall(started, DATA_TIMEOUT_MS), 0, null)
                }
                offset += n
                if (n < chunk) break
            }
            return TransferResult(0, offset, null)
        }

        private fun timeoutOrStall(started: Long, timeout: Int): Int =
            if (System.currentTimeMillis() - started >= timeout) ERR_ETIMEDOUT else ERR_EPIPE

        private fun findEndpoint(number: Int, dirIn: Boolean): UsbEndpoint? {
            val device = exported.device
            val wanted = if (dirIn) UsbConstants.USB_DIR_IN else UsbConstants.USB_DIR_OUT
            var fallback: UsbEndpoint? = null
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                for (j in 0 until intf.endpointCount) {
                    val endpoint = intf.getEndpoint(j)
                    if (endpoint.endpointNumber != number || endpoint.direction != wanted) continue
                    if (intf.alternateSetting == exported.alternateSetting(intf.id)) return endpoint
                    if (fallback == null) fallback = endpoint
                }
            }
            return fallback
        }
    }

    // ------------------------------------------------------------------- helpers

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

    private fun DataInputStream.skipFully(count: Long) {
        var remaining = count
        val scratch = ByteArray(4096)
        while (remaining > 0) {
            val n = read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            if (n < 0) throw EOFException()
            remaining -= n
        }
    }

    private data class TransferResult(val status: Int, val actualLength: Int, val data: ByteArray?)

    /**
     * `struct usbip_header`: a 20-byte basic header followed by the command body,
     * padded to 48 bytes. All fields are network byte order.
     */
    private class NetworkHeader(private val bytes: ByteArray) {
        val command: Int get() = intAt(0)
        val seqNum: Int get() = intAt(4)
        val direction: Int get() = intAt(12)
        val endpoint: Int get() = intAt(16)

        // cmd_submit body
        val transferBufferLength: Int get() = intAt(24)
        val numberOfPackets: Int get() = intAt(32)
        val setup: ByteArray get() = bytes.copyOfRange(40, 48)

        // cmd_unlink body
        val unlinkSeqNum: Int get() = intAt(20)

        fun intAt(offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
    }
}
