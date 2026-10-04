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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
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
    private var mediaRunning = mutableStateOf(MediaBridgeService.isRunning)
    private var mediaMessage = mutableStateOf(
        "Share this tablet's camera and microphone with your Mac or PC, then switch to Parsec."
    )

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val mediaPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val camera = results[Manifest.permission.CAMERA] == true || granted(Manifest.permission.CAMERA)
            val mic = results[Manifest.permission.RECORD_AUDIO] == true || granted(Manifest.permission.RECORD_AUDIO)
            if (!camera) {
                mediaMessage.value =
                    "Camera permission is needed. Allow it in Settings → Apps → Perspective USB Bridge → Permissions."
                return@registerForActivityResult
            }
            startMedia()
            if (!mic) mediaMessage.value = "Microphone permission was declined, so only video will be shared."
        }

    /** Our own broadcasts: app-private, so they stay NOT_EXPORTED. */
    private val appReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    // EXTRA_DEVICE can be missing: the framework fills the result extras
                    // in through PendingIntent.send(), which an immutable PendingIntent
                    // discards. Fall back to the device we asked about, and treat
                    // hasPermission() rather than the extras as the source of truth.
                    val device = intent.usbDeviceExtra() ?: pendingShareDeviceId?.let(::deviceById)
                    refreshDevices()
                    if (device != null && usb.hasPermission(device)) {
                        bridgeState.value = "Ready"
                        bridgeMessage.value = "USB access granted for ${displayName(device)}."
                        if (pendingShareDeviceId == null || pendingShareDeviceId == device.deviceId) {
                            pendingShareDeviceId = null
                            startBridge(device)
                        }
                    } else {
                        pendingShareDeviceId = null
                        bridgeState.value = "Permission denied"
                        bridgeMessage.value =
                            "Android did not grant USB access. Tap Allow access again and choose OK, " +
                                "then keep this app open."
                    }
                }
                MediaBridgeService.ACTION_STATE -> {
                    val state = intent.getStringExtra(MediaBridgeService.EXTRA_STATE) ?: ""
                    val text = intent.getStringExtra(MediaBridgeService.EXTRA_MESSAGE) ?: ""
                    mediaRunning.value = MediaBridgeService.isRunning
                    // Keep a startup error on screen rather than the "stopped" that follows it.
                    val keepError = state == "stopped" && mediaMessage.value.startsWith("Problem:")
                    if (text.isNotBlank() && !keepError) {
                        mediaMessage.value = if (state == "error") "Problem: $text" else text
                        bridgeLog.value = (listOf("Camera: $text") + bridgeLog.value).take(12)
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
        handleAttachIntent(intent)
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
                    permissionTick = permissionTick.value,
                    mediaRunning = mediaRunning.value,
                    mediaMessage = mediaMessage.value
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAttachIntent(intent)
    }

    /**
     * Android launches us through the manifest's USB_DEVICE_ATTACHED filter when a
     * drive is plugged in, and that route grants USB permission for the device
     * without a dialog. Pick it up so the card is immediately shareable.
     */
    private fun handleAttachIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device = intent.usbDeviceExtra() ?: return
        refreshDevices()
        if (isHub(device) || !usb.hasPermission(device)) return
        bridgeState.value = "Ready"
        bridgeMessage.value = "${displayName(device)} is connected and ready to share."
    }

    override fun onStart() {
        super.onStart()
        refreshDevices()
        mediaRunning.value = MediaBridgeService.isRunning
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
                addAction(MediaBridgeService.ACTION_STATE)
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
        permissionTick: Int,
        mediaRunning: Boolean,
        mediaMessage: String
    ) {
        Surface(color = Midnight, modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.ic_ps_mark),
                        contentDescription = null,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            "USB Bridge",
                            color = TextPrimary,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text("Perspective Studio · drives, camera and microphone", color = TextDim)
                    }
                }
                Spacer(Modifier.height(18.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = PanelRaised),
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(18.dp)) {
                        SectionTitle("This tablet")
                        Text(
                            address?.let { "$it · USB/IP port ${UsbIpServer.PORT}" }
                                ?: "Waiting for a Wi-Fi connection…",
                            color = TextPrimary
                        )
                        Text("Type this address into Windows if it is not found automatically.", color = TextDim)
                    }
                }

                Spacer(Modifier.height(18.dp))
                MediaCard(address, mediaRunning, mediaMessage)
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
                                SectionTitle("USB hub connected")
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
                            SectionTitle("Activity")
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
    private fun SectionTitle(text: String) {
        Text(
            text,
            color = TextPrimary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp)
        )
    }

    @Composable
    private fun MediaCard(address: String?, running: Boolean, message: String) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp)) {
                SectionTitle("Camera & microphone")
                Text(
                    address?.let { "On your Mac or PC, connect to $it (camera port ${MediaProtocol.PORT})." }
                        ?: "Connect this tablet to Wi-Fi to share its camera.",
                    color = TextPrimary
                )
                Spacer(Modifier.height(6.dp))
                Text(message, color = TextDim)
                Spacer(Modifier.height(14.dp))
                if (running) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(Lime, CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Text("Live — you can switch to Parsec now", color = Lime, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { stopMedia() }, shape = RoundedCornerShape(999.dp)) {
                        Text("Stop sharing camera", color = TextPrimary)
                    }
                } else {
                    Button(
                        onClick = { requestMedia() },
                        colors = ButtonDefaults.buttonColors(containerColor = Orange),
                        shape = RoundedCornerShape(999.dp)
                    ) { Text("Share camera & microphone") }
                }
            }
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun requestMedia() {
        val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).filterNot(::granted)
        if (missing.isEmpty()) startMedia() else mediaPermissions.launch(missing.toTypedArray())
    }

    /**
     * Must run while this activity is on screen: Android only lets a camera and
     * microphone service keep using them in the background if it was started from
     * the foreground. That is what keeps them live behind Parsec.
     */
    private fun startMedia() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, MediaBridgeService::class.java).setAction(MediaBridgeService.ACTION_START)
        )
        mediaRunning.value = true
        mediaMessage.value = "Starting camera bridge…"
    }

    private fun stopMedia() {
        // stopService rather than a STOP intent: startForegroundService would
        // oblige the service to post a camera notification just to shut down.
        stopService(Intent(this, MediaBridgeService::class.java))
        mediaRunning.value = false
        mediaMessage.value = "Camera and microphone sharing stopped."
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
        // The PendingIntent must be MUTABLE. UsbManager reports its answer by
        // filling EXTRA_DEVICE and EXTRA_PERMISSION_GRANTED into the intent it
        // sends back, and an immutable PendingIntent silently drops them, so
        // every request came back looking like a denial no matter what the user
        // tapped. setPackage keeps the intent explicit, which is what Android 14+
        // requires of a mutable PendingIntent.
        val mutability =
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            device.deviceId,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            mutability or PendingIntent.FLAG_UPDATE_CURRENT
        )
        usb.requestPermission(device, pendingIntent)
        bridgeState.value = "Waiting"
        bridgeMessage.value = "Waiting for Android USB permission for ${displayName(device)}."
    }

    private fun deviceById(deviceId: Int): UsbDevice? =
        usb.deviceList.values.firstOrNull { it.deviceId == deviceId }

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
