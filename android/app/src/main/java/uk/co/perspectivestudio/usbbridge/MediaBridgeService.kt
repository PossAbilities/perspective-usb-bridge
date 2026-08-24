package uk.co.perspectivestudio.usbbridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serves the tablet's camera and microphone to Windows over TCP.
 *
 * One client at a time: the camera can only be pointed at one encoder, and a
 * second viewer would have no way to ask for different geometry anyway.
 */
class MediaBridgeService : Service() {
    companion object {
        const val ACTION_START = "uk.co.perspectivestudio.usbbridge.START_MEDIA"
        const val ACTION_STOP = "uk.co.perspectivestudio.usbbridge.STOP_MEDIA"
        const val ACTION_STATE = "uk.co.perspectivestudio.usbbridge.MEDIA_STATE"
        const val EXTRA_STATE = "state"
        const val EXTRA_MESSAGE = "message"
        private const val CHANNEL_ID = "media_bridge"
        private const val NOTIFICATION_ID = 32402

        private const val DEFAULT_WIDTH = 1280
        private const val DEFAULT_HEIGHT = 720
        private const val DEFAULT_FRAME_RATE = 30

        /** ~0.1 bits per pixel per frame, which is a reasonable H.264 call quality. */
        private fun bitRateFor(width: Int, height: Int, frameRate: Int): Int =
            (width.toLong() * height * frameRate / 10).toInt().coerceIn(800_000, 8_000_000)
    }

    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()
    private var serverSocket: ServerSocket? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var foregroundStarted = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        enterForeground()
        when (intent?.action) {
            ACTION_STOP -> { shutdown(); return START_NOT_STICKY }
            else -> startServer()
        }
        return START_STICKY
    }

    private fun enterForeground() {
        if (foregroundStarted) return
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 30) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Camera ready for Windows"), type)
        foregroundStarted = true
    }

    private fun startServer() {
        if (!running.compareAndSet(false, true)) return
        if (!hasPermission(Manifest.permission.CAMERA)) {
            publish("error", "Camera permission has not been granted.")
            running.set(false)
            shutdown()
            return
        }
        val socket = ServerSocket()
        socket.reuseAddress = true
        try {
            socket.bind(InetSocketAddress(MediaProtocol.PORT), 4)
        } catch (e: IOException) {
            running.set(false)
            runCatching { socket.close() }
            publish("error", "Could not listen on ${MediaProtocol.PORT}: ${e.message}")
            shutdown()
            return
        }
        serverSocket = socket
        acquireLocks()
        publish("ready", "Camera bridge listening on TCP ${MediaProtocol.PORT}")

        pool.execute {
            while (running.get()) {
                try {
                    val client = socket.accept()
                    client.tcpNoDelay = true
                    pool.execute { serve(client) }
                } catch (e: IOException) {
                    if (socket.isClosed) break
                    if (running.get()) publish("error", "Accept failed: ${e.message}")
                }
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.use { client ->
            val input = DataInputStream(BufferedInputStream(client.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(client.getOutputStream(), 256 * 1024))
            val peer = client.inetAddress.hostAddress ?: "client"
            var session: Session? = null
            try {
                val request = MediaProtocol.readRequest(input)
                publish("streaming", "$peer asked for ${request.width}x${request.height}@${request.frameRate}")
                session = Session(request, output).also { it.start() }
                // The client has nothing more to say; block until it hangs up.
                while (running.get() && session.alive.get()) {
                    if (input.read() < 0) break
                }
            } catch (e: MediaProtocol.ProtocolException) {
                publish("error", "Rejected $peer: ${e.message}")
            } catch (_: IOException) {
                // Client disconnected.
            } catch (e: Exception) {
                publish("error", "Stream failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                session?.stop()
                publish("ready", "$peer disconnected")
            }
        }
    }

    /** One client's camera, encoder and microphone, plus the writer they share. */
    private inner class Session(
        request: MediaProtocol.Request,
        private val output: DataOutputStream
    ) {
        val alive = AtomicBoolean(true)
        private val writeLock = Any()
        private val startedAtNanos = System.nanoTime()

        private val camera = CameraSource(this@MediaBridgeService) { message ->
            publish("error", message)
            alive.set(false)
        }
        private val cameraId = camera.cameraId(request.wantsFrontCamera)
        private val size = cameraId?.let {
            camera.chooseSize(
                it,
                request.width.takeIf { w -> w > 0 } ?: DEFAULT_WIDTH,
                request.height.takeIf { h -> h > 0 } ?: DEFAULT_HEIGHT
            )
        }
        private val frameRate = request.frameRate.takeIf { it in 1..60 } ?: DEFAULT_FRAME_RATE
        private val wantsAudio = request.wantsAudio && hasPermission(Manifest.permission.RECORD_AUDIO)

        private var encoder: VideoEncoder? = null
        private var audio: AudioCapture? = null

        /**
         * Both streams are stamped from one monotonic clock taken at session
         * start, so the Windows side can line them up. Video is stamped at
         * encoder output, so it carries a few milliseconds of encode delay;
         * measuring and correcting that belongs with the latency work in
         * phase 2, not guessed at here.
         */
        private fun nowUs(): Long = (System.nanoTime() - startedAtNanos) / 1_000

        fun start() {
            if (cameraId == null || size == null) {
                MediaProtocol.writeAccept(
                    output,
                    MediaProtocol.Accept(0, 0, 0, MediaProtocol.STATUS_REFUSED)
                )
                alive.set(false)
                publish("error", "No usable camera on this tablet")
                return
            }

            MediaProtocol.writeAccept(output, MediaProtocol.Accept(size.width, size.height, frameRate))

            val video = VideoEncoder(
                width = size.width,
                height = size.height,
                frameRate = frameRate,
                bitRate = bitRateFor(size.width, size.height, frameRate),
                onFrame = { bytes, keyframe ->
                    send(MediaProtocol.TYPE_VIDEO_FRAME, bytes, keyframe)
                },
                onConfig = { bytes -> send(MediaProtocol.TYPE_VIDEO_CONFIG, bytes, false) }
            )
            encoder = video
            video.start(::nowUs)
            camera.start(cameraId, video.inputSurface, frameRate)

            if (wantsAudio) {
                // Describes the PCM the client is about to receive, so the
                // Windows side never has to assume a format.
                send(
                    MediaProtocol.TYPE_AUDIO_CONFIG,
                    byteArrayOf(
                        (AudioCapture.SAMPLE_RATE shr 24).toByte(),
                        (AudioCapture.SAMPLE_RATE shr 16).toByte(),
                        (AudioCapture.SAMPLE_RATE shr 8).toByte(),
                        AudioCapture.SAMPLE_RATE.toByte(),
                        AudioCapture.CHANNELS.toByte(),
                        AudioCapture.BITS_PER_SAMPLE.toByte()
                    ),
                    false
                )
                audio = AudioCapture { chunk, length ->
                    sendSlice(MediaProtocol.TYPE_AUDIO_FRAME, chunk, length)
                }.also {
                    runCatching { it.start() }
                        .onFailure { error -> publish("error", "Microphone unavailable: ${error.message}") }
                }
            }

            publish(
                "streaming",
                "Streaming ${size.width}x${size.height}@${frameRate}" +
                    if (wantsAudio) " with audio" else " (video only)"
            )
        }

        private fun send(type: Int, payload: ByteArray, keyframe: Boolean) =
            sendSlice(type, payload, payload.size, keyframe)

        private fun sendSlice(type: Int, payload: ByteArray, length: Int, keyframe: Boolean = false) {
            if (!alive.get()) return
            val timestamp = nowUs()
            try {
                synchronized(writeLock) {
                    MediaProtocol.writeFrame(output, type, timestamp, payload, 0, length, keyframe)
                }
            } catch (_: IOException) {
                // Client went away mid-frame; unwind the session.
                alive.set(false)
            }
        }

        fun stop() {
            alive.set(false)
            audio?.stop()
            camera.stop()
            encoder?.stop()
        }
    }

    // ------------------------------------------------------------------ misc

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PerspectiveUsbBridge::media")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock == null) {
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PerspectiveUsbBridge::mediaWifi")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun shutdown() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
    }

    private fun publish(state: String, message: String) {
        sendBroadcast(Intent(ACTION_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_MESSAGE, message)
        })
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Camera sharing", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Perspective USB Bridge")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running.set(false)
        runCatching { serverSocket?.close() }
        pool.shutdownNow()
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
