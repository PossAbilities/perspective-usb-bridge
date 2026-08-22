package uk.co.perspectivestudio.usbbridge

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/** LAN discovery for the Windows UI. USB traffic itself remains TCP/3240. */
class DiscoveryBeacon(
    private val sharedCount: () -> Int
) {
    companion object {
        const val PORT = 32401
        private const val MAGIC = "PERSPECTIVE_USB_BRIDGE_V2"
    }

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                while (running.get()) {
                    val body = "$MAGIC|${UsbIpServer.PORT}|${sharedCount()}"
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    val packet = DatagramPacket(bytes, bytes.size, InetAddress.getByName("255.255.255.255"), PORT)
                    runCatching { socket.send(packet) }
                    try { Thread.sleep(1500) } catch (_: InterruptedException) { break }
                }
            }
        }.apply {
            name = "PerspectiveUsbDiscovery"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }
}
