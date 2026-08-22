package uk.co.perspectivestudio.usbbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

class UsbBridgeService : Service() {
    companion object {
        const val ACTION_START = "uk.co.perspectivestudio.usbbridge.START_SHARE"
        const val ACTION_STOP = "uk.co.perspectivestudio.usbbridge.STOP_SHARE"
        const val ACTION_STOP_ALL = "uk.co.perspectivestudio.usbbridge.STOP_ALL"
        const val ACTION_STATE = "uk.co.perspectivestudio.usbbridge.STATE"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_STATE = "state"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_SHARED_IDS = "shared_ids"
        private const val CHANNEL_ID = "usb_bridge"
        private const val NOTIFICATION_ID = 3240
    }

    private lateinit var usbManager: UsbManager
    private val shared = ConcurrentHashMap<Int, SharedUsbDevice>()
    private var usbIpServer: UsbIpServer? = null
    private var discoveryBeacon: DiscoveryBeacon? = null

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
        ContextCompat.registerReceiver(
            this,
            detachReceiver,
            IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> addDevice(intent.getIntExtra(EXTRA_DEVICE_ID, -1))
            ACTION_STOP -> removeDevice(intent.getIntExtra(EXTRA_DEVICE_ID, -1), "Stopped sharing")
            ACTION_STOP_ALL -> stopAll("Stopped all sharing")
        }
        return START_STICKY
    }

    private fun ensureHostRunning(): Boolean {
        if (usbIpServer != null) return true
        startForeground(NOTIFICATION_ID, notification("Starting USB host..."))
        return try {
            usbIpServer = UsbIpServer({ shared.values.sortedBy { it.busId } }) { event ->
                publish("sharing", event)
            }.also { it.start() }
            discoveryBeacon = DiscoveryBeacon { shared.size }.also { it.start() }
            true
        } catch (e: Exception) {
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

        val claimed = mutableListOf<Int>()
        for (i in 0 until device.interfaceCount) {
            if (opened.claimInterface(device.getInterface(i), true)) claimed += i
        }
        if (device.interfaceCount > 0 && claimed.isEmpty()) {
            opened.close()
            publish("error", "The USB interfaces on ${displayName(device)} could not be claimed.")
            return
        }

        val exported = SharedUsbDevice(device, opened, claimed)
        shared[device.deviceId] = exported
        publish("sharing", "${exported.displayName} shared as ${exported.busId}. ${shared.size} device${if (shared.size == 1) "" else "s"} available to Windows.")
        updateNotification()
    }

    private fun removeDevice(deviceId: Int, reason: String) {
        val exported = shared.remove(deviceId) ?: return
        exported.imported.set(false)
        exported.claimedInterfaces.forEach { index ->
            runCatching { exported.connection.releaseInterface(exported.device.getInterface(index)) }
        }
        runCatching { exported.connection.close() }
        publish("stopped", "$reason: ${exported.displayName}.")
        if (shared.isEmpty()) stopHostIfEmpty() else updateNotification()
    }

    private fun stopAll(reason: String) {
        shared.keys.toList().forEach { id -> removeDevice(id, reason) }
        stopHostIfEmpty()
    }

    private fun stopHostIfEmpty() {
        if (shared.isNotEmpty()) return
        discoveryBeacon?.stop()
        discoveryBeacon = null
        usbIpServer?.stop()
        usbIpServer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification() {
        val names = shared.values.take(2).joinToString { it.displayName }
        val suffix = if (shared.size > 2) " +${shared.size - 2} more" else ""
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            notification("${shared.size} shared: $names$suffix")
        )
    }

    private fun isHub(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_HUB) return true
        return (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_HUB }
    }

    private fun displayName(device: UsbDevice): String = device.productName ?: "USB device"

    private fun publish(state: String, message: String) {
        sendBroadcast(Intent(ACTION_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_MESSAGE, message)
            putIntegerArrayListExtra(EXTRA_SHARED_IDS, ArrayList(shared.keys))
        })
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "USB sharing", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_usb)
            .setContentTitle("Perspective USB Bridge")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun usbDeviceExtra(intent: Intent): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(detachReceiver) }
        shared.keys.toList().forEach { id ->
            val exported = shared.remove(id) ?: return@forEach
            exported.claimedInterfaces.forEach { index -> runCatching { exported.connection.releaseInterface(exported.device.getInterface(index)) } }
            runCatching { exported.connection.close() }
        }
        discoveryBeacon?.stop()
        usbIpServer?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
