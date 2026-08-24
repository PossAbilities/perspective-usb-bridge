package uk.co.perspectivestudio.usbbridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresPermission
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hardware H.264 encoder fed by a camera through its input surface.
 *
 * Surface input keeps frames on the GPU: no YUV copies through Dalvik, which is
 * what makes 1080p30 affordable in both latency and battery.
 */
class VideoEncoder(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    bitRate: Int,
    private val onFrame: (ByteArray, Boolean) -> Unit,
    private val onConfig: (ByteArray) -> Unit
) {
    companion object {
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        /** Short GOP so a client joining mid-stream renders quickly. */
        private const val KEYFRAME_INTERVAL_SECONDS = 1
    }

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MIME)
    private val running = AtomicBoolean(false)
    private var drainThread: Thread? = null

    val inputSurface: Surface

    init {
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_INTERVAL_SECONDS)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            // Baseline keeps decoder cost down and avoids B-frames, which would
            // reorder output and add a frame of latency.
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                setInteger(MediaFormat.KEY_LATENCY, 1)
            }
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
    }

    fun start(timestampUs: () -> Long) {
        if (!running.compareAndSet(false, true)) return
        codec.start()
        drainThread = Thread { drain(timestampUs) }.apply {
            name = "PerspectiveVideoEncoder"
            isDaemon = true
            start()
        }
    }

    private fun drain(timestampUs: () -> Long) {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            val index = try {
                codec.dequeueOutputBuffer(info, 100_000)
            } catch (_: IllegalStateException) {
                return
            }
            if (index < 0) continue
            val buffer = codec.getOutputBuffer(index) ?: continue
            if (info.size > 0) {
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                val bytes = ByteArray(info.size).also { buffer.get(it) }
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    // SPS/PPS: the decoder cannot start without these.
                    onConfig(bytes)
                } else {
                    onFrame(bytes, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                }
            }
            runCatching { codec.releaseOutputBuffer(index, false) }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }

    /** Ask for an immediate keyframe, e.g. when a new client attaches. */
    fun requestKeyframe() {
        runCatching {
            codec.setParameters(android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        drainThread?.interrupt()
        drainThread = null
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { inputSurface.release() }
    }
}

/**
 * 48 kHz 16-bit mono PCM, sent uncompressed.
 *
 * 768 kbps is nothing beside the video stream, and skipping a codec removes its
 * algorithmic delay from a path where latency is the entire point.
 */
class AudioCapture(private val onAudio: (ByteArray, Int) -> Unit) {
    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
        /** 20 ms chunks: small enough for conversation, large enough to be cheap. */
        private const val CHUNK_MS = 20
        private const val BYTES_PER_CHUNK = SAMPLE_RATE / 1000 * CHUNK_MS * 2
    }

    private val running = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (!running.compareAndSet(false, true)) return
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(BYTES_PER_CHUNK * 4)

        val recorder = AudioRecord(
            // VOICE_COMMUNICATION engages the platform's echo cancellation and
            // noise suppression, which is what a call needs.
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            running.set(false)
            throw IllegalStateException("Microphone could not be opened")
        }
        record = recorder
        recorder.startRecording()

        thread = Thread {
            val chunk = ByteArray(BYTES_PER_CHUNK)
            while (running.get()) {
                val read = recorder.read(chunk, 0, chunk.size)
                if (read > 0) onAudio(chunk, read) else if (read < 0) break
            }
        }.apply {
            name = "PerspectiveAudioCapture"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.interrupt()
        thread = null
        record?.let { runCatching { it.stop() }; runCatching { it.release() } }
        record = null
    }
}

/** Opens a camera and points it at a surface, nothing more. */
class CameraSource(
    private val context: Context,
    private val onError: (String) -> Unit
) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    fun cameraId(front: Boolean): String? {
        val wanted = if (front) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        return runCatching {
            manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == wanted
            } ?: manager.cameraIdList.firstOrNull()
        }.getOrNull()
    }

    /** Closest supported encoder-input size to the request, by pixel count. */
    fun chooseSize(id: String, width: Int, height: Int): Size {
        val fallback = Size(1280, 720)
        val map = runCatching {
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        }.getOrNull() ?: return fallback
        val sizes = map.getOutputSizes(MediaCodec::class.java) ?: return fallback
        if (sizes.isEmpty()) return fallback
        val target = width.toLong() * height
        return sizes.minByOrNull { kotlin.math.abs(it.width.toLong() * it.height - target) } ?: fallback
    }

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.CAMERA)
    @Suppress("DEPRECATION")
    fun start(id: String, surface: Surface, frameRate: Int) {
        thread = HandlerThread("PerspectiveCamera").apply { start() }
        handler = Handler(thread!!.looper)

        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                runCatching {
                    camera.createCaptureSession(
                        listOf(surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(configured: CameraCaptureSession) {
                                session = configured
                                val request = camera
                                    .createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                                    .apply {
                                        addTarget(surface)
                                        set(
                                            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                            Range(frameRate, frameRate)
                                        )
                                    }
                                    .build()
                                runCatching {
                                    configured.setRepeatingRequest(request, null, handler)
                                }.onFailure { onError("Camera request failed: ${it.message}") }
                            }

                            override fun onConfigureFailed(failed: CameraCaptureSession) {
                                onError("Camera could not be configured for streaming")
                            }
                        },
                        handler
                    )
                }.onFailure { onError("Camera session failed: ${it.message}") }
            }

            override fun onDisconnected(camera: CameraDevice) {
                onError("Camera disconnected")
                camera.close()
                device = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                onError("Camera error $error")
                camera.close()
                device = null
            }
        }, handler)
    }

    fun stop() {
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        thread?.quitSafely()
        thread = null
        handler = null
    }
}
