import android.hardware.usb.*
import uk.co.perspectivestudio.usbbridge.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/** SSK-style USB3 SATA bridge: one mass-storage interface, bulk in 0x81 / out 0x02. */
private fun rawDescriptors(): ByteArray = byteArrayOf(
    0x12, 0x01, 0x00, 0x03, 0x00, 0x00, 0x00, 0x09,
    0x09, 0x21, 0x15, 0x07, 0x00, 0x01, 0x01, 0x02, 0x03, 0x01,
    0x09, 0x02, 0x2C, 0x00, 0x01, 0x01, 0x00, 0x00.toByte(), 0x37,
    0x09, 0x04, 0x00, 0x00, 0x02, 0x08, 0x06, 0x50, 0x00,
    0x07, 0x05, 0x81.toByte(), 0x02, 0x00, 0x04, 0x00,
    0x06, 0x30, 0x0F, 0x00, 0x00, 0x00,
    0x07, 0x05, 0x02, 0x02, 0x00, 0x04, 0x00,
    0x06, 0x30, 0x0F, 0x00, 0x00, 0x00
)

private val bulkIn = UsbEndpoint(1, UsbConstants.USB_DIR_IN, UsbConstants.USB_ENDPOINT_XFER_BULK, 1024)
private val bulkOut = UsbEndpoint(2, UsbConstants.USB_DIR_OUT, UsbConstants.USB_ENDPOINT_XFER_BULK, 1024)
private val msc = UsbInterface(0, 0, 8, 6, 0x50, listOf(bulkIn, bulkOut))
private val device = UsbDevice(1002, 0x2109, 0x0715, 0, 0, 0, 1, "SSK Portable SSD", "1.00", listOf(msc))

class FakeConnection : UsbDeviceConnection() {
    val chunkSizes = mutableListOf<Int>()
    val controlCalls = mutableListOf<String>()
    @Volatile var blockNextBulkMs = 0L
    val concurrentControlDuringBulk = AtomicInteger(0)
    @Volatile var bulkInFlight = false

    override val rawDescriptors = rawDescriptors()

    override fun controlTransfer(
        requestType: Int, request: Int, value: Int, index: Int,
        buffer: ByteArray?, length: Int, timeout: Int
    ): Int {
        controlCalls += "%02x/%02x/%04x".format(requestType, request, value)
        if (bulkInFlight) concurrentControlDuringBulk.incrementAndGet()
        if (requestType == 0xA1 && request == 0xFE) { buffer!![0] = 0; return 1 } // GET_MAX_LUN
        if (request == 0x06 && (value shr 8) == 0x03) { // string descriptor
            val text = "SSK"
            buffer!![0] = (2 + text.length * 2).toByte(); buffer[1] = 3
            text.forEachIndexed { i, c -> buffer[2 + i * 2] = c.code.toByte(); buffer[3 + i * 2] = 0 }
            return minOf(length, 2 + text.length * 2)
        }
        return -1 // stall anything else, like a real device with claimed interfaces
    }

    override fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray?, offset: Int, length: Int, timeout: Int): Int {
        synchronized(chunkSizes) { chunkSizes += length }
        if (blockNextBulkMs > 0) {
            bulkInFlight = true
            Thread.sleep(blockNextBulkMs)
            bulkInFlight = false
            blockNextBulkMs = 0
        }
        if (endpoint.direction == UsbConstants.USB_DIR_IN) {
            for (i in 0 until length) buffer!![offset + i] = ((offset + i) and 0xFF).toByte()
        }
        return length
    }

    override fun setInterface(intf: UsbInterface) = true
}

// ------------------------------------------------------------------ tiny client

private class Client(host: String, port: Int) {
    val socket = Socket(host, port)
    val out = DataOutputStream(socket.getOutputStream())
    val inp = DataInputStream(socket.getInputStream())
    init { socket.tcpNoDelay = true }

    fun op(code: Int) { out.writeShort(0x0111); out.writeShort(code); out.writeInt(0); out.flush() }

    fun submit(seq: Int, dirIn: Boolean, ep: Int, len: Int, setup: ByteArray = ByteArray(8), payload: ByteArray? = null) {
        out.writeInt(1); out.writeInt(seq); out.writeInt(0)
        out.writeInt(if (dirIn) 1 else 0); out.writeInt(ep)
        out.writeInt(0); out.writeInt(len); out.writeInt(0); out.writeInt(0); out.writeInt(0)
        out.write(setup)
        if (payload != null) out.write(payload)
        out.flush()
    }

    fun unlink(seq: Int, victim: Int) {
        out.writeInt(2); out.writeInt(seq); out.writeInt(0); out.writeInt(0); out.writeInt(0)
        out.writeInt(victim); repeat(6) { out.writeInt(0) }
        out.flush()
    }

    /** @return command, seqnum, status, actual_length, data */
    fun readReply(dirIn: Boolean = true): Reply {
        val h = ByteArray(48); inp.readFully(h)
        fun i(o: Int) = ((h[o].toInt() and 0xFF) shl 24) or ((h[o+1].toInt() and 0xFF) shl 16) or
            ((h[o+2].toInt() and 0xFF) shl 8) or (h[o+3].toInt() and 0xFF)
        val cmd = i(0); val seq = i(4); val status = i(20); val actual = i(24)
        // Only IN transfers carry a payload after the RET_SUBMIT header.
        val data = if (dirIn && cmd == 3 && status == 0 && actual > 0) ByteArray(actual).also { inp.readFully(it) } else ByteArray(0)
        return Reply(cmd, seq, status, actual, data)
    }
}
private data class Reply(val command: Int, val seq: Int, val status: Int, val actualLength: Int, val data: ByteArray)

// ------------------------------------------------------------------------ checks

private var failures = 0
private fun check(name: String, condition: Boolean, detail: String = "") {
    if (condition) println("  PASS  $name") else { failures++; println("  FAIL  $name ${detail}") }
}

fun main() {
    val connection = FakeConnection()
    val descriptors = UsbDescriptors.parse(connection.rawDescriptors)!!
    val shared = SharedUsbDevice(device, connection, listOf(msc), descriptors, 1, 1)
    val server = UsbIpServer({ listOf(shared) }, { })
    server.start()
    Thread.sleep(300)

    println("Speed negotiated for Windows: ${shared.speed} (5 = SuperSpeed)")
    check("SuperSpeed inferred from 1024-byte bulk endpoints", shared.speed == 5)

    // ---- OP_REQ_DEVLIST -------------------------------------------------
    run {
        val c = Client("127.0.0.1", UsbIpServer.PORT)
        c.op(0x8005)
        check("devlist version", c.inp.readUnsignedShort() == 0x0111)
        check("devlist reply code 0x0005", c.inp.readUnsignedShort() == 0x0005)
        check("devlist status ok", c.inp.readInt() == 0)
        val count = c.inp.readInt()
        check("devlist exports one device", count == 1, "got $count")

        val path = ByteArray(256).also { c.inp.readFully(it) }
        val busid = ByteArray(32).also { c.inp.readFully(it) }
        check("path is NUL terminated and 256 bytes", path[255].toInt() == 0)
        check("busid is 1-1", String(busid, 0, busid.indexOf(0)) == "1-1")
        check("busnum", c.inp.readInt() == 1)
        check("devnum", c.inp.readInt() == 1)
        check("speed", c.inp.readInt() == 5)
        check("idVendor", c.inp.readUnsignedShort() == 0x2109)
        check("idProduct", c.inp.readUnsignedShort() == 0x0715)
        check("bcdDevice from real descriptor", c.inp.readUnsignedShort() == 0x0100)
        c.inp.readByte(); c.inp.readByte(); c.inp.readByte()
        check("bConfigurationValue", c.inp.readByte().toInt() == 1)
        check("bNumConfigurations", c.inp.readByte().toInt() == 1)
        val ifaces = c.inp.readByte().toInt()
        check("bNumInterfaces", ifaces == 1, "got $ifaces")
        val record = ByteArray(4 * ifaces).also { c.inp.readFully(it) }
        check("interface record is mass storage 08/06/50",
            record[0].toInt() == 8 && record[1].toInt() == 6 && record[2].toInt() == 0x50)
        // Stream must now be exactly exhausted: any drift means the client sees garbage.
        c.socket.soTimeout = 400
        val trailing = runCatching { c.inp.read() }.getOrDefault(-1)
        check("no trailing bytes after devlist", trailing == -1, "got $trailing")
        c.socket.close()
    }

    // ---- OP_REQ_IMPORT + URB stream -------------------------------------
    val c = Client("127.0.0.1", UsbIpServer.PORT)
    c.op(0x8003)
    c.out.write(ByteArray(32).also { "1-1".toByteArray().copyInto(it) }); c.out.flush()
    c.inp.readUnsignedShort(); c.inp.readUnsignedShort()
    check("import accepted", c.inp.readInt() == 0)
    c.inp.readFully(ByteArray(312)) // device record, no interface list

    fun setup(bm: Int, req: Int, value: Int, index: Int, len: Int) = byteArrayOf(
        bm.toByte(), req.toByte(), (value and 0xFF).toByte(), (value shr 8).toByte(),
        (index and 0xFF).toByte(), (index shr 8).toByte(), (len and 0xFF).toByte(), (len shr 8).toByte()
    )

    // GET_DESCRIPTOR(DEVICE) — must be served from the cached descriptors.
    c.submit(1, true, 0, 18, setup(0x80, 0x06, 0x0100, 0, 18))
    var r = c.readReply()
    check("GET_DESCRIPTOR(DEVICE) succeeds", r.status == 0, "status ${r.status}")
    check("device descriptor is 18 bytes", r.actualLength == 18, "got ${r.actualLength}")
    check("device descriptor matches hardware", r.data.contentEquals(connection.rawDescriptors.copyOfRange(0, 18)))

    // GET_DESCRIPTOR(CONFIGURATION) full 44-byte tree.
    c.submit(2, true, 0, 44, setup(0x80, 0x06, 0x0200, 0, 44))
    r = c.readReply()
    check("GET_DESCRIPTOR(CONFIG) returns whole tree", r.status == 0 && r.actualLength == 44, "len ${r.actualLength}")

    // SET_CONFIGURATION(1) — the request that used to kill enumeration, because
    // usbfs answers EBUSY once interfaces are claimed.
    c.submit(3, false, 0, 0, setup(0x00, 0x09, 0x0001, 0, 0))
    r = c.readReply(dirIn = false)
    check("SET_CONFIGURATION(1) accepted", r.status == 0, "status ${r.status}")

    c.submit(4, true, 0, 1, setup(0x80, 0x08, 0, 0, 1))
    r = c.readReply()
    check("GET_CONFIGURATION returns 1", r.status == 0 && r.data[0].toInt() == 1)

    // SET_INTERFACE(0, alt 0)
    c.submit(5, false, 0, 0, setup(0x01, 0x0B, 0x0000, 0, 0))
    r = c.readReply(dirIn = false)
    check("SET_INTERFACE accepted", r.status == 0, "status ${r.status}")

    // Class request still goes to the hardware.
    c.submit(6, true, 0, 1, setup(0xA1, 0xFE, 0, 0, 1))
    r = c.readReply()
    check("GET_MAX_LUN reaches the device", r.status == 0 && r.actualLength == 1)

    // Unsupported standard request stalls (EPIPE) instead of EIO, so Windows carries on.
    c.submit(7, true, 0, 10, setup(0x80, 0x06, 0x0F00, 0, 10)) // BOS
    r = c.readReply()
    check("unsupported descriptor stalls with -EPIPE", r.status == -32, "status ${r.status}")

    // 128 KiB bulk read: must be split into 16 KiB usbfs chunks.
    connection.chunkSizes.clear()
    val big = 128 * 1024
    c.submit(8, true, 1, big)
    r = c.readReply()
    check("128 KiB bulk IN completes", r.status == 0 && r.actualLength == big, "len ${r.actualLength}")
    check("split into 16 KiB usbfs chunks", connection.chunkSizes.all { it <= 16384 } && connection.chunkSizes.size == 8,
        "chunks ${connection.chunkSizes}")
    check("payload bytes are intact", r.data.size == big && r.data[70000] == (70000 and 0xFF).toByte())

    // Bulk OUT of 40 KiB.
    connection.chunkSizes.clear()
    val payload = ByteArray(40 * 1024) { (it and 0x7F).toByte() }
    c.submit(9, false, 2, payload.size, ByteArray(8), payload)
    r = c.readReply(dirIn = false)
    check("40 KiB bulk OUT completes", r.status == 0 && r.actualLength == payload.size, "len ${r.actualLength}")
    check("OUT also chunked", connection.chunkSizes.all { it <= 16384 })

    // A slow bulk read must not block control traffic on the same device.
    connection.concurrentControlDuringBulk.set(0)
    connection.blockNextBulkMs = 1200
    c.submit(10, true, 1, 512)
    Thread.sleep(300)
    c.submit(11, true, 0, 1, setup(0xA1, 0xFE, 0, 0, 1))
    val first = c.readReply()
    val second = c.readReply()
    check("control answered before the slow bulk read", first.seq == 11 && second.seq == 10,
        "order ${first.seq} then ${second.seq}")
    check("control ran while bulk was in flight", connection.concurrentControlDuringBulk.get() > 0)

    // UNLINK of an unknown seqnum reports 0 ("already completed").
    c.unlink(12, 999)
    r = c.readReply()
    check("unlink of completed URB returns 0", r.command == 4 && r.status == 0, "cmd ${r.command} status ${r.status}")

    // UNLINK of an in-flight URB reports -ECONNRESET and suppresses its RET_SUBMIT.
    connection.blockNextBulkMs = 900
    c.submit(13, true, 1, 512)
    Thread.sleep(200)
    c.unlink(14, 13)
    r = c.readReply()
    check("unlink of in-flight URB returns -ECONNRESET", r.command == 4 && r.status == -104,
        "cmd ${r.command} status ${r.status}")
    c.socket.soTimeout = 2000
    val late = runCatching { c.readReply() }.getOrNull()
    check("no late RET_SUBMIT for the unlinked URB", late == null, "got ${late?.command}/${late?.seq}")

    c.socket.close()
    server.stop()

    println("\n=== Media bridge protocol ===")
    val mediaFailures = runMediaProtocolChecks()

    val total = failures + mediaFailures
    println(if (total == 0) "\nALL CHECKS PASSED" else "\n$total CHECK(S) FAILED")
    if (total > 0) kotlin.system.exitProcess(1)
}
