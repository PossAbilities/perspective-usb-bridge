package uk.co.perspectivestudio.usbbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class UsbBridgeService : Service() {
    companion object {
        const val ACTION_START = "uk.co.perspectivestudio.usbbridge.START_SHARE"
        const val ACTION_STOP = "uk.co.perspectivestudio.usbbridge.STOP_SHARE"
        const val ACTION_STOP_ALL = "uk.co.perspectivestudio.usbbridge.STOP_ALL"
        const val ACTION_START_HOST = "uk.co.perspectivestudio.usbbridge.START_HOST"
        const val ACTION_RELEASE_HOST = "uk.co.perspectivestudio.usbbridge.RELEASE_HOST"
        const val ACTION_STATE = "uk.co.perspectivestudio.usbbridge.STATE"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_STATE = "state"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_SHARED_IDS = "shared_ids"
        const val EXTRA_HOST_ADDRESS = "host_address"
        private const val CHANNEL_ID = "usb_bridge"
        private const val NOTIFICATION_ID = 3240
    }

    private lateinit var usbManager: UsbManager
    private val shared = ConcurrentHashMap<Int, SharedUsbDevice>()
    // Only used when Android will not tell us the real USB address. Starts high
    // so a synthesised address can never collide with a real one.
    private val nextDevNum = AtomicInteger(101)
    private var usbIpServer: UsbIpServer? = null
    private var discoveryBeacon: DiscoveryBeacon? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var foregroundStarted = false
    private var hostRequested = false

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val device = usbDeviceExtra(intent) ?: return
            if (shared.containsKey(device.deviceId)) removeDevice(device.deviceId, "USB device disconnected")
        }
    }

    override fun onCreate() {
        super.onCreate()
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        createNotificationChannel()
        // ACTION_USB_DEVICE_DETACHED is a system broadcast. Registering it as
        // NOT_EXPORTED stops it being delivered on Android 14+, which is why
        // unplugged drives used to stay listed as shared.
        ContextCompat.registerReceiver(
            this,
            detachReceiver,
            IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() gives us a few seconds to post a notification or
        // Android kills the process. Do it before any work that might bail out.
        enterForeground("Perspective USB Bridge is running")

        when (intent?.action) {
            ACTION_START -> {
                hostRequested = true
                addDevice(intent.getIntExtra(EXTRA_DEVICE_ID, -1))
            }
            ACTION_START_HOST -> {
                hostRequested = true
                if (ensureHostRunning()) publish("ready", "Tablet discoverable on ${localAddress() ?: "this network"}.")
            }
            ACTION_RELEASE_HOST -> {
                hostRequested = false
                if (shared.isEmpty()) { shutdownHost(); return START_NOT_STICKY }
            }
            ACTION_STOP -> removeDevice(intent.getIntExtra(EXTRA_DEVICE_ID, -1), "Stopped sharing")
            ACTION_STOP_ALL -> {
                hostRequested = false
                stopAll("Stopped all sharing")
                if (shared.isEmpty()) { shutdownHost(); return START_NOT_STICKY }
            }
            else -> {
                // Restarted by the system: nothing is shared any more, so stand down.
                if (shared.isEmpty() && !hostRequested) { shutdownHost(); return START_NOT_STICKY }
            }
        }
        return START_STICKY
    }

    private fun enterForeground(text: String) {
        if (foregroundStarted) return
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(text),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        )
        foregroundStarted = true
    }

    private fun ensureHostRunning(): Boolean {
        if (usbIpServer != null) return true
        return try {
            usbIpServer = UsbIpServer({ shared.values.sortedBy { it.devNum } }) { event ->
                publish("sharing", event)
            }.also { it.start() }
            discoveryBeacon = DiscoveryBeacon({ shared.size }) { event -> publish("sharing", event) }
                .also { it.start() }
            acquireLocks()
            true
        } catch (e: Exception) {
            usbIpServer = null
            publish("error", "Could not start USB/IP on port ${UsbIpServer.PORT}: ${e.message}")
            false
        }
    }

    private fun addDevice(deviceId: Int) {
        if (deviceId < 0) return
        if (shared.containsKey(deviceId)) {
            publish("sharing", "That USB device is already shared.")
            return
        }
        val device = usbManager.deviceList.values.firstOrNull { it.deviceId == deviceId }
        if (device == null) {
            publish("error", "USB device is no longer connected.")
            return
        }
        if (isHub(device)) {
            publish("hub", "USB hub detected. Share the drives connected through it, not the hub itself.")
            return
        }
        if (!usbManager.hasPermission(device)) {
            publish("error", "USB permission has not been granted for ${displayName(device)}.")
            return
        }
        if (!ensureHostRunning()) return

        val opened = usbManager.openDevice(device)
        if (opened == null) {
            publish("error", "Android could not open ${displayName(device)}.")
            return
        }

        val descriptors = UsbDescriptors.parse(runCatching { opened.rawDescriptors }.getOrNull())
        if (descriptors == null) {
            opened.close()
            publish("error", "Android could not read the USB descriptors for ${displayName(device)}.")
            return
        }

        // force=true detaches any kernel driver (usb-storage) so Windows, not
        // Android, owns the drive. Hub interfaces are never claimed.
        val claimed = mutableListOf<UsbInterface>()
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_HUB) continue
            if (intf.alternateSetting != 0) continue
            if (opened.claimInterface(intf, true)) claimed += intf
        }
        if (claimed.isEmpty()) {
            opened.close()
            publish("error", "The USB interfaces on ${displayName(device)} could not be claimed. Close any app using the drive and try again.")
            return
        }

        val (busNum, devNum) = busAddress(device)
        val exported = SharedUsbDevice(
            device = device,
            connection = opened,
            claimedInterfaces = claimed,
            descriptors = descriptors,
            busNum = busNum,
            devNum = devNum
        )
        shared[device.deviceId] = exported
        publish(
            "sharing",
            "${exported.displayName} shared as ${exported.busId} on ${localAddress() ?: "this network"}. " +
                "${shared.size} device${if (shared.size == 1) "" else "s"} available to Windows."
        )
        updateNotification()
    }

    private fun removeDevice(deviceId: Int, reason: String) {
        val exported = shared.remove(deviceId) ?: return
        exported.imported.set(false)
        exported.claimedInterfaces.forEach { intf ->
            runCatching { exported.connection.releaseInterface(intf) }
        }
        runCatching { exported.connection.close() }
        publish("stopped", "$reason: ${exported.displayName}.")
        if (shared.isEmpty() && !hostRequested) shutdownHost() else updateNotification()
    }

    private fun stopAll(reason: String) {
        shared.keys.toList().forEach { id -> removeDevice(id, reason) }
        if (!hostRequested) shutdownHost()
    }

    private fun shutdownHost() {
        if (shared.isNotEmpty()) return
        discoveryBeacon?.stop()
        discoveryBeacon = null
        usbIpServer?.stop()
        usbIpServer = null
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PerspectiveUsbBridge::transfer")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock == null) {
            // Wi-Fi power saving otherwise stalls bulk transfers when the screen sleeps.
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PerspectiveUsbBridge::wifi")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun updateNotification() {
        if (!foregroundStarted) return
        val names = shared.values.take(2).joinToString { it.displayName }
        val suffix = if (shared.size > 2) " +${shared.size - 2} more" else ""
        val text = if (shared.isEmpty()) {
            "Discoverable on ${localAddress() ?: "this network"}"
        } else {
            "${shared.size} shared: $names$suffix"
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    /**
     * Bus ID for the USB/IP export.
     *
     * Android names devices after their real Linux path, /dev/bus/usb/BBB/DDD,
     * so the bus and address can be reused verbatim. That keeps the bus ID
     * stable when a drive is unshared and shared again; a plain counter handed
     * out a new ID every time, which left Windows attaching a bus ID the tablet
     * had already retired.
     */
    private fun busAddress(device: UsbDevice): Pair<Int, Int> {
        val match = Regex("/dev/bus/usb/(\\d+)/(\\d+)").find(device.deviceName.orEmpty())
        val bus = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val address = match?.groupValues?.get(2)?.toIntOrNull() ?: 0
        val taken = shared.values.any { it.busNum == bus && it.devNum == address }
        return if (bus > 0 && address > 0 && !taken) bus to address
        else 1 to nextDevNum.getAndIncrement()
    }

    private fun isHub(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_HUB) return true
        return (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_HUB }
    }

    private fun displayName(device: UsbDevice): String =
        device.productName?.takeIf { it.isNotBlank() } ?: "USB device"

    /** Best-effort IPv4 address so the user can type it into Windows manually. */
    private fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLinkLocalAddress }
            ?.hostAddress
    }.getOrNull()

    private fun publish(state: String, message: String) {
        sendBroadcast(Intent(ACTION_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_HOST_ADDRESS, localAddress())
            putIntegerArrayListExtra(EXTRA_SHARED_IDS, ArrayList(shared.keys))
        })
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "USB sharing", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Leaving the app no longer stops the bridge, so the notification has to
        // carry the way to stop it.
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, UsbBridgeService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Perspective USB Bridge")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(null, "Stop sharing", stop).build()
            )
            .setOngoing(true)
            .build()
    }

    private fun usbDeviceExtra(intent: Intent): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(detachReceiver) }
        shared.keys.toList().forEach { id ->
            val exported = shared.remove(id) ?: return@forEach
            exported.claimedInterfaces.forEach { intf -> runCatching { exported.connection.releaseInterface(intf) } }
            runCatching { exported.connection.close() }
        }
        discoveryBeacon?.stop()
        usbIpServer?.stop()
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
