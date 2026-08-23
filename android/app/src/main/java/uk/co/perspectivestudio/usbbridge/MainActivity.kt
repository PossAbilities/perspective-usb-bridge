package uk.co.perspectivestudio.usbbridge

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

private const val ACTION_USB_PERMISSION = "uk.co.perspectivestudio.usbbridge.USB_PERMISSION"
private val Midnight = Color(0xFF1A1546)
private val Panel = Color(0xFF241D57)
private val PanelRaised = Color(0xFF2C2466)
private val TextPrimary = Color(0xFFF3F1FB)
private val TextDim = Color(0xFFB3ABD6)
private val Lime = Color(0xFFCFE96A)
private val Orange = Color(0xFFF4592B)

class MainActivity : ComponentActivity() {
    private lateinit var usb: UsbManager
    private var devicesState = mutableStateOf<List<UsbDevice>>(emptyList())
    private var sharedIds = mutableStateOf<Set<Int>>(emptySet())
    private var bridgeState = mutableStateOf("Idle")
    private var bridgeMessage = mutableStateOf("Plug in a USB drive to begin.")
    private var bridgeLog = mutableStateOf<List<String>>(emptyList())
    private var hostAddress = mutableStateOf<String?>(null)
    private var permissionTick = mutableStateOf(0)
    private var pendingShareDeviceId: Int? = null
    private var receiversRegistered = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Our own broadcasts: app-private, so they stay NOT_EXPORTED. */
    private val appReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = intent.usbDeviceExtra()
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    refreshDevices()
                    if (granted && device != null) {
                        bridgeState.value = "Ready"
                        bridgeMessage.value = "USB access granted for ${displayName(device)}."
                        if (pendingShareDeviceId == device.deviceId) {
                            pendingShareDeviceId = null
                            startBridge(device)
                        }
                    } else {
                        pendingShareDeviceId = null
                        bridgeState.value = "Permission denied"
                        bridgeMessage.value = "USB access is required before the drive can be shared."
                    }
                }
                UsbBridgeService.ACTION_STATE -> {
                    bridgeState.value = intent.getStringExtra(UsbBridgeService.EXTRA_STATE) ?: "Idle"
                    bridgeMessage.value = intent.getStringExtra(UsbBridgeService.EXTRA_MESSAGE) ?: ""
                    sharedIds.value = intent.getIntegerArrayListExtra(UsbBridgeService.EXTRA_SHARED_IDS)?.toSet()
                        ?: sharedIds.value
                    intent.getStringExtra(UsbBridgeService.EXTRA_HOST_ADDRESS)?.let { hostAddress.value = it }
                    if (bridgeMessage.value.isNotBlank()) {
                        bridgeLog.value = (listOf(bridgeMessage.value) + bridgeLog.value).take(12)
                    }
                }
            }
        }
    }

    /**
     * System broadcasts. Android 14+ drops these entirely for a receiver declared
     * NOT_EXPORTED, which is why attach/detach never refreshed the drive list.
     */
    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshDevices()
            if (devicesState.value.isEmpty()) {
                sharedIds.value = emptySet()
                bridgeState.value = "Idle"
                bridgeMessage.value = "Plug in a USB drive to begin."
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        refreshDevices()
        registerBridgeReceivers()
        requestNotificationPermission()
        setContent {
            MaterialTheme {
                App(
                    usb = usb,
                    devices = devicesState.value,
                    shared = sharedIds.value,
                    state = bridgeState.value,
                    message = bridgeMessage.value,
                    address = hostAddress.value,
                    log = bridgeLog.value,
                    permissionTick = permissionTick.value
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        refreshDevices()
        // Keep the tablet discoverable while the app is open so the Windows client
        // can find it before anything has been shared.
        sendToService(UsbBridgeService.ACTION_START_HOST)
    }

    override fun onStop() {
        super.onStop()
        if (sharedIds.value.isEmpty()) sendToService(UsbBridgeService.ACTION_RELEASE_HOST)
    }

    override fun onDestroy() {
        if (receiversRegistered) {
            runCatching { unregisterReceiver(appReceiver) }
            runCatching { unregisterReceiver(systemReceiver) }
            receiversRegistered = false
        }
        super.onDestroy()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun refreshDevices() {
        devicesState.value = usb.deviceList.values
            .sortedWith(compareBy<UsbDevice>({ isHub(it) }, { displayName(it) }))
        permissionTick.value++
    }

    private fun registerBridgeReceivers() {
        if (receiversRegistered) return
        ContextCompat.registerReceiver(
            this,
            appReceiver,
            IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbBridgeService.ACTION_STATE)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            systemReceiver,
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            ContextCompat.RECEIVER_EXPORTED
        )
        receiversRegistered = true
    }

    private fun Intent.usbDeviceExtra(): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION") getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    @Composable
    private fun App(
        usb: UsbManager,
        devices: List<UsbDevice>,
        shared: Set<Int>,
        state: String,
        message: String,
        address: String?,
        log: List<String>,
        permissionTick: Int
    ) {
        Surface(color = Midnight, modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
            ) {
                Text("PERSPECTIVE STUDIO", color = Orange, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("USB Bridge.", color = TextPrimary, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold)
                Text("Your USB. Anywhere.", color = TextDim)
                Spacer(Modifier.height(18.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = PanelRaised),
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Text("THIS TABLET", color = Lime, fontWeight = FontWeight.Bold)
                        Text(
                            address?.let { "$it · USB/IP port ${UsbIpServer.PORT}" }
                                ?: "Waiting for a Wi-Fi connection…",
                            color = TextPrimary
                        )
                        Text("Type this address into Windows if it is not found automatically.", color = TextDim)
                    }
                }

                Spacer(Modifier.height(18.dp))

                if (devices.isEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                        Text("Plug a USB drive — or a USB hub with drives — into this Samsung tablet.", color = TextDim, modifier = Modifier.padding(20.dp))
                    }
                } else {
                    val hubs = devices.filter(::isHub)
                    val shareable = devices.filterNot(::isHub)

                    if (hubs.isNotEmpty()) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = PanelRaised),
                            shape = RoundedCornerShape(22.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                        ) {
                            Column(Modifier.padding(18.dp)) {
                                Text("USB HUB DETECTED", color = Lime, fontWeight = FontWeight.Bold)
                                Text(
                                    "${hubs.size} hub${if (hubs.size == 1) "" else "s"} connected. Drives attached through the hub appear separately below.",
                                    color = TextDim
                                )
                            }
                        }
                    }

                    if (shareable.isEmpty()) {
                        Text("The hub is connected, but no downstream USB drives are visible yet.", color = TextDim)
                    } else {
                        shareable.forEach { DeviceCard(usb, it, it.deviceId in shared, permissionTick) }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(state.replaceFirstChar { it.uppercase() }, color = Lime, fontWeight = FontWeight.Bold)
                Text(message, color = TextDim)

                if (log.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                        Column(Modifier.padding(18.dp)) {
                            Text("DIAGNOSTICS", color = Orange, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            log.forEach { Text(it, color = TextDim, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }

                Spacer(Modifier.height(30.dp))
                Text("v0.7 multi-drive + hub prototype", color = TextDim)
            }
        }
    }

    @Composable
    private fun DeviceCard(usb: UsbManager, device: UsbDevice, isShared: Boolean, permissionTick: Int) {
        // permissionTick forces recomposition after a permission grant, otherwise
        // the button keeps reading the stale hasPermission() result.
        val hasPermission = remember(device.deviceId, permissionTick) { usb.hasPermission(device) }
        Card(
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(displayName(device), color = TextPrimary, fontWeight = FontWeight.Bold)
                Text(deviceTypeLabel(device), color = Lime)
                Text("VID %04X · PID %04X".format(device.vendorId, device.productId), color = TextDim)
                Spacer(Modifier.height(14.dp))

                if (isShared) {
                    Text("Shared with Windows", color = Lime, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { stopBridge(device) }) { Text("Stop sharing", color = TextPrimary) }
                } else {
                    Button(
                        onClick = {
                            if (!usb.hasPermission(device)) {
                                pendingShareDeviceId = device.deviceId
                                requestUsbPermission(device)
                            } else startBridge(device)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Orange),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text(if (hasPermission) "Share with Windows" else "Allow access")
                    }
                }
            }
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            device.deviceId,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        usb.requestPermission(device, pendingIntent)
        bridgeState.value = "Waiting"
        bridgeMessage.value = "Waiting for Android USB permission for ${displayName(device)}."
    }

    private fun startBridge(device: UsbDevice) {
        bridgeState.value = "Preparing"
        bridgeMessage.value = "Taking ownership of ${displayName(device)}."
        val intent = Intent(this, UsbBridgeService::class.java).apply {
            action = UsbBridgeService.ACTION_START
            putExtra(UsbBridgeService.EXTRA_DEVICE_ID, device.deviceId)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopBridge(device: UsbDevice) {
        val intent = Intent(this, UsbBridgeService::class.java).apply {
            action = UsbBridgeService.ACTION_STOP
            putExtra(UsbBridgeService.EXTRA_DEVICE_ID, device.deviceId)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun sendToService(action: String) {
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, UsbBridgeService::class.java).setAction(action)
            )
        }
    }

    private fun displayName(device: UsbDevice): String =
        device.productName?.takeIf { it.isNotBlank() } ?: "USB device"

    private fun isHub(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_HUB) return true
        return (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_HUB }
    }

    private fun deviceTypeLabel(device: UsbDevice): String {
        val classes = (0 until device.interfaceCount).map { device.getInterface(it).interfaceClass }.toSet()
        return when {
            UsbConstants.USB_CLASS_MASS_STORAGE in classes -> "Storage device"
            UsbConstants.USB_CLASS_AUDIO in classes -> "USB audio device · experimental"
            else -> "USB device · experimental"
        }
    }
}
