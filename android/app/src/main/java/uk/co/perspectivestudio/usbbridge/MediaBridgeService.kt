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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serves the tablet's camera and microphone to a Mac or Windows PC over TCP.
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

        /** True while the server is listening, so the UI can show the right button on reopen. */
        @Volatile
        var isRunning = false
            private set

        /** ~0.1 bits per pixel per frame, which is a reasonable H.264 call quality. */
        private fun bitRateFor(width: Int, height: Int, frameRate: Int): Int =
            (width.toLong() * height * frameRate / 10).toInt().coerceIn(800_000, 8_000_000)
    }

    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()
    private var serverSocket: ServerSocket? = null
    /** Open client sockets, closed on shutdown so their camera and mic are released. */
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
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
        // Android 14+ throws if a type is claimed without its runtime permission,
        // so only claim what has been granted. Starting with these types while the
        // activity is on screen is also what lets the camera and microphone keep
        // working once the user switches to Parsec.
        var type = 0
        if (Build.VERSION.SDK_INT >= 30) {
            if (hasPermission(Manifest.permission.CAMERA)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (hasPermission(Manifest.permission.RECORD_AUDIO)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Sharing camera and microphone"), type)
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
        isRunning = true
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
        clients.add(socket)
        socket.use { client ->
            val input = DataInputStream(BufferedInputStream(client.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(client.getOutputStream(), 256 * 1024))
            val peer = client.inetAddress.hostAddress ?: "client"
            var session: Session? = null
            try {
                val request = MediaProtocol.readRequest(input)
                publish("streaming", "$peer asked for ${request.width}x${request.height}@${request.frameRate}")
                // Recorded before start() so a failure halfway through still
                // releases whatever camera or encoder was already opened.
                val current = Session(request, output, client)
                session = current
                current.start()
                // The client has nothing more to say; block until it hangs up.
                while (running.get() && current.alive.get()) {
                    if (input.read() < 0) break
                }
            } catch (e: MediaProtocol.ProtocolException) {
                publish("error", "Rejected $peer: ${e.message}")
            } catch (_: IOException) {
                // Client disconnected.
            } catch (e: Exception) {
                val reason = "Stream failed: ${e.message ?: e.javaClass.simpleName}"
                publish("error", reason)
                session?.report(reason) ?: runCatching {
                    MediaProtocol.writeAccept(output, MediaProtocol.Accept(0, 0, 0, MediaProtocol.STATUS_REFUSED))
                    MediaProtocol.writeFrame(output, MediaProtocol.TYPE_ERROR, 0, reason.toByteArray())
                }
            } finally {
                session?.stop()
                clients.remove(client)
                if (running.get()) publish("ready", "$peer disconnected")
            }
        }
    }

    /** One client's camera, encoder and microphone, plus the writer they share. */
    private inner class Session(
        request: MediaProtocol.Request,
        private val output: DataOutputStream,
        private val socket: Socket
    ) {
        val alive = AtomicBoolean(true)
        /** Whether the handshake reply has gone out; an error before it needs a refusal first. */
        @Volatile private var accepted = false
        private val writeLock = Any()
        private val startedAtNanos = System.nanoTime()

        private val camera = CameraSource(this@MediaBridgeService) { message -> fail(message) }
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
            // Locals, because smart casts on these properties do not reach
            // into the fallback lambda below.
            val id = cameraId
            val requested = size
            if (id == null || requested == null) {
                MediaProtocol.writeAccept(
                    output,
                    MediaProtocol.Accept(0, 0, 0, MediaProtocol.STATUS_REFUSED)
                )
                accepted = true
                report("No usable camera on this tablet")
                alive.set(false)
                publish("error", "No usable camera on this tablet")
                return
            }

            // Build the encoder before accepting, so the geometry the client is
            // told is one the encoder actually took. Fall back to 720p if the
            // requested size is refused.
            val video = runCatching { encoderFor(requested) }.getOrElse { first ->
                val fallback = camera.chooseSize(id, DEFAULT_WIDTH, DEFAULT_HEIGHT)
                if (fallback == requested) throw first
                encoderFor(fallback)
            }
            encoder = video

            synchronized(writeLock) {
                MediaProtocol.writeAccept(output, MediaProtocol.Accept(video.width, video.height, frameRate))
                accepted = true
            }
            video.start(::nowUs)
            camera.start(id, video.inputSurface, frameRate)

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
                "Streaming ${video.width}x${video.height}@${frameRate}" +
                    if (wantsAudio) " with audio" else " (video only)"
            )
        }

        private fun encoderFor(size: android.util.Size) = VideoEncoder(
            width = size.width,
            height = size.height,
            frameRate = frameRate,
            bitRate = bitRateFor(size.width, size.height, frameRate),
            onFrame = { bytes, keyframe -> send(MediaProtocol.TYPE_VIDEO_FRAME, bytes, keyframe) },
            onConfig = { bytes -> send(MediaProtocol.TYPE_VIDEO_CONFIG, bytes, false) }
        )

        /** Tell the client why the stream is ending; best effort. */
        fun report(message: String) {
            runCatching {
                synchronized(writeLock) {
                    if (!accepted) {
                        MediaProtocol.writeAccept(output, MediaProtocol.Accept(0, 0, 0, MediaProtocol.STATUS_REFUSED))
                        accepted = true
                    }
                    MediaProtocol.writeFrame(output, MediaProtocol.TYPE_ERROR, nowUs(), message.toByteArray())
                }
            }
        }

        /**
         * A failure after the stream started, e.g. the camera erroring. Closing
         * the socket wakes the serve loop, which is blocked reading, so the
         * client sees a clean disconnect instead of a frozen picture.
         */
        private fun fail(message: String) {
            publish("error", message)
            if (!alive.getAndSet(false)) return
            report(message)
            runCatching { socket.close() }
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

    private fun closeClients() {
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }

    private fun shutdown() {
        running.set(false)
        isRunning = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        closeClients()
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
        isRunning = false
        runCatching { serverSocket?.close() }
        // A blocked socket read ignores thread interrupts; closing is what ends it.
        closeClients()
        pool.shutdownNow()
        releaseLocks()
        publish("stopped", "Camera and microphone sharing stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
