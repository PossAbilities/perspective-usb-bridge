package uk.co.perspectivestudio.usbbridge

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LAN discovery for the Windows UI. USB traffic itself remains TCP/3240.
 *
 * The beacon both announces itself periodically and answers directed probes.
 * Announcements alone are unreliable: plenty of Wi-Fi access points drop the
 * 255.255.255.255 limited broadcast, and Windows Firewall silently discards
 * unsolicited inbound UDP. Replying to a probe from Windows travels the path
 * the firewall has already opened, so the tablet is found either way.
 */
class DiscoveryBeacon(
    private val sharedCount: () -> Int,
    private val onEvent: (String) -> Unit = {}
) {
    companion object {
        const val PORT = 32401
        private const val MAGIC = "PERSPECTIVE_USB_BRIDGE_V2"
        private const val PROBE = "PERSPECTIVE_USB_BRIDGE_DISCOVER"
        private const val ANNOUNCE_INTERVAL_MS = 2_000L
    }

    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var announceThread: Thread? = null
    private var listenThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val bound = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(PORT))
            }
        } catch (e: Exception) {
            // Another process owns the port; fall back to announce-only on an
            // ephemeral port so at least broadcasts still work.
            onEvent("Discovery port $PORT unavailable (${e.message}); announcing only")
            runCatching { DatagramSocket().apply { broadcast = true } }.getOrNull()
        }
        if (bound == null) {
            running.set(false)
            onEvent("Could not open the discovery socket")
            return
        }
        socket = bound

        announceThread = Thread {
            while (running.get()) {
                runCatching { announce(bound) }
                try {
                    Thread.sleep(ANNOUNCE_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply {
            name = "PerspectiveUsbAnnounce"
            isDaemon = true
            start()
        }

        if (bound.localPort == PORT) {
            listenThread = Thread {
                val buffer = ByteArray(512)
                while (running.get()) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        bound.receive(packet)
                        val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8).trim()
                        if (!text.startsWith(PROBE)) continue
                        val reply = payload().toByteArray(Charsets.UTF_8)
                        bound.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                    } catch (_: Exception) {
                        if (!running.get()) break
                    }
                }
            }.apply {
                name = "PerspectiveUsbDiscovery"
                isDaemon = true
                start()
            }
        }
        onEvent("Discoverable on UDP $PORT")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { socket?.close() }
        socket = null
        announceThread?.interrupt()
        listenThread?.interrupt()
        announceThread = null
        listenThread = null
    }

    private fun payload(): String = "$MAGIC|${UsbIpServer.PORT}|${sharedCount()}"

    private fun announce(socket: DatagramSocket) {
        val bytes = payload().toByteArray(Charsets.UTF_8)
        for (target in broadcastTargets()) {
            runCatching { socket.send(DatagramPacket(bytes, bytes.size, target, PORT)) }
        }
    }

    /**
     * Subnet-directed broadcasts (e.g. 192.168.1.255) survive far more access
     * points than the limited broadcast, so send to both.
     */
    private fun broadcastTargets(): List<InetAddress> {
        val targets = mutableListOf<InetAddress>()
        runCatching {
            for (nic in NetworkInterface.getNetworkInterfaces()) {
                if (!nic.isUp || nic.isLoopback) continue
                for (address in nic.interfaceAddresses) {
                    address.broadcast?.let { targets += it }
                }
            }
        }
        runCatching { targets += InetAddress.getByName("255.255.255.255") }
        return targets.distinct()
    }
}
