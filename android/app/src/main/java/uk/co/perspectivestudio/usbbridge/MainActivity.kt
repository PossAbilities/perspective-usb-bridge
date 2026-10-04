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
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private const val ACTION_USB_PERMISSION = "uk.co.perspectivestudio.usbbridge.USB_PERMISSION"

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
    private var mediaStreaming = mutableStateOf(false)
    private val pairingCode by lazy { RelayLink.pairingCode(this) }
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
                    if (state != "relay") mediaStreaming.value = state == "streaming"
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
        // Draw under the system bars (Android 15 does this regardless); the top
        // bar paints the ink behind the status bar, as on the desktop.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
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
                    mediaStreaming = mediaStreaming.value,
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
        mediaStreaming: Boolean,
        mediaMessage: String
    ) {
        val (pillText, pillStatus) = when {
            mediaStreaming -> "Streaming to your computer" to Status.Live
            mediaRunning -> "Camera ready" to Status.Ok
            shared.isNotEmpty() -> "Sharing ${shared.size} drive${if (shared.size == 1) "" else "s"}" to Status.Ok
            else -> "Ready" to Status.Idle
        }
        Column(Modifier.fillMaxSize().background(Brand.Midnight)) {
            TopBar("USB Bridge", pillText, pillStatus)
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 840.dp
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 24.dp, vertical = 24.dp)
                ) {
                    if (wide) {
                        Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                            Column(Modifier.weight(1.1f)) {
                                CameraPanel(address, mediaRunning, mediaStreaming, mediaMessage)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                                DrivesSection(usb, devices, shared, state, message, permissionTick)
                                TabletSection(address)
                                ActivityLog(log)
                            }
                        }
                    } else {
                        CameraPanel(address, mediaRunning, mediaStreaming, mediaMessage)
                        Spacer(Modifier.height(32.dp))
                        DrivesSection(usb, devices, shared, state, message, permissionTick)
                        Spacer(Modifier.height(28.dp))
                        TabletSection(address)
                        Spacer(Modifier.height(20.dp))
                        ActivityLog(log)
                    }
                    Spacer(Modifier.height(28.dp))
                    Text(
                        "Perspective USB Bridge · v0.7 prototype",
                        style = Brand.hint.copy(fontSize = 12.sp),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    @Composable
    private fun CameraPanel(address: String?, running: Boolean, streaming: Boolean, message: String) {
        HeroPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Camera & microphone", style = Brand.heading.copy(fontSize = 18.sp), modifier = Modifier.weight(1f))
                if (running) StatusDot(if (streaming) Status.Live else Status.Ok, size = 10)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    streaming -> "Streaming to your computer. Switch to Parsec whenever you like."
                    running -> "Ready. Switch to Parsec any time; the camera keeps running in the background."
                    else -> "Share this tablet's camera and microphone with your Mac or PC, then carry on in Parsec."
                },
                style = Brand.body.copy(color = Brand.Dim)
            )
            if (running) {
                Spacer(Modifier.height(20.dp))
                Text("Anywhere: enter this code on the Mac", style = Brand.hint)
                Spacer(Modifier.height(4.dp))
                Text(
                    RelayLink.displayCode(pairingCode),
                    style = Brand.title.copy(fontSize = 34.sp, letterSpacing = 3.sp, color = Brand.Lime)
                )
                Spacer(Modifier.height(14.dp))
                LabelledValue(
                    "Same Wi-Fi: or use this address",
                    address ?: "Not on Wi-Fi"
                )
            }
            if (message.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    message,
                    style = Brand.hint.copy(color = if (message.startsWith("Problem")) Brand.Danger else Brand.Dim)
                )
            }
            Spacer(Modifier.height(20.dp))
            if (running) {
                SecondaryButton("Stop sharing camera", onClick = { stopMedia() })
            } else {
                PrimaryButton("Share camera & microphone", onClick = { requestMedia() }, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    @Composable
    private fun DrivesSection(
        usb: UsbManager,
        devices: List<UsbDevice>,
        shared: Set<Int>,
        state: String,
        message: String,
        permissionTick: Int
    ) {
        Section("USB drives") {
            val hubs = devices.filter(::isHub)
            val shareable = devices.filterNot(::isHub)
            when {
                devices.isEmpty() ->
                    Text("Plug a USB drive, or a USB hub with drives, into this tablet.", style = Brand.hint)
                shareable.isEmpty() ->
                    Text("The hub is connected, but no drives are visible through it yet.", style = Brand.hint)
                else -> Column(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, Brand.Line, RoundedCornerShape(14.dp))
                        .background(Brand.Panel, RoundedCornerShape(14.dp))
                ) {
                    shareable.forEachIndexed { index, device ->
                        if (index > 0) HorizontalDivider(color = Brand.Line)
                        DeviceRow(usb, device, device.deviceId in shared, permissionTick)
                    }
                }
            }
            if (hubs.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "${hubs.size} USB hub${if (hubs.size == 1) "" else "s"} connected; drives on it are listed separately.",
                    style = Brand.hint
                )
            }
            if (message.isNotBlank() && devices.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "${state.replaceFirstChar { it.uppercase() }} · $message",
                    style = Brand.hint.copy(color = if (state.contains("denied", true)) Brand.Danger else Brand.Dim)
                )
            }
        }
    }

    @Composable
    private fun TabletSection(address: String?) {
        Section("This tablet") {
            LabelledValue(
                "Drive sharing address (Windows)",
                address?.let { "$it · port ${UsbIpServer.PORT}" } ?: "Waiting for Wi-Fi…"
            )
        }
    }

    @Composable
    private fun ActivityLog(log: List<String>) {
        if (log.isEmpty()) return
        Disclosure("Activity") {
            log.forEach { Text(it, style = Brand.mono.copy(fontSize = 12.sp, color = Brand.Dim), modifier = Modifier.padding(vertical = 2.dp)) }
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
        mediaStreaming.value = false
        mediaMessage.value = "Camera and microphone sharing stopped."
    }

    @Composable
    private fun DeviceRow(usb: UsbManager, device: UsbDevice, isShared: Boolean, permissionTick: Int) {
        // permissionTick forces recomposition after a permission grant, otherwise
        // the button keeps reading the stale hasPermission() result.
        val hasPermission = remember(device.deviceId, permissionTick) { usb.hasPermission(device) }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        displayName(device),
                        style = Brand.heading.copy(fontSize = 15.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isShared) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Shared",
                            style = Brand.hint.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Brand.Lime),
                            modifier = Modifier
                                .background(Brand.Lime.copy(alpha = 0.14f), CircleShape)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(deviceTypeLabel(device), style = Brand.hint)
                Text(
                    "VID %04X · PID %04X".format(device.vendorId, device.productId),
                    style = Brand.mono.copy(fontSize = 12.sp, color = Brand.Dim)
                )
            }
            Spacer(Modifier.width(12.dp))
            if (isShared) {
                SecondaryButton("Stop", onClick = { stopBridge(device) })
            } else {
                PrimaryButton(
                    if (hasPermission) "Share" else "Allow access",
                    onClick = {
                        if (!usb.hasPermission(device)) {
                            pendingShareDeviceId = device.deviceId
                            requestUsbPermission(device)
                        } else startBridge(device)
                    }
                )
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
