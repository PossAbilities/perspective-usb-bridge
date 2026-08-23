import uk.co.perspectivestudio.usbbridge.MediaProtocol
import java.io.*

private var mediaFailures = 0
private fun mediaCheck(name: String, ok: Boolean, detail: String = "") {
    if (ok) println("  PASS  $name") else { mediaFailures++; println("  FAIL  $name $detail") }
}

fun runMediaProtocolChecks(): Int {
    // Exact byte layout, since the Windows decoder is a separate implementation.
    run {
        val sink = ByteArrayOutputStream()
        MediaProtocol.writeRequest(
            DataOutputStream(sink),
            MediaProtocol.Request(1, 1920, 1080, 30, wantsAudio = true, wantsFrontCamera = false)
        )
        val b = sink.toByteArray()
        mediaCheck("handshake is 16 bytes", b.size == 16, "got ${b.size}")
        mediaCheck("magic PSPMEDIA", String(b, 0, 8) == "PSPMEDIA")
        mediaCheck("version big-endian at 8", ((b[8].toInt() and 0xFF) shl 8 or (b[9].toInt() and 0xFF)) == 1)
        mediaCheck("width 1920 at 10", ((b[10].toInt() and 0xFF) shl 8 or (b[11].toInt() and 0xFF)) == 1920)
        mediaCheck("height 1080 at 12", ((b[12].toInt() and 0xFF) shl 8 or (b[13].toInt() and 0xFF)) == 1080)
        mediaCheck("fps at 14", (b[14].toInt() and 0xFF) == 30)
        mediaCheck("audio flag only at 15", (b[15].toInt() and 0xFF) == MediaProtocol.REQUEST_AUDIO)
    }
    run {
        val sink = ByteArrayOutputStream()
        val payload = ByteArray(1000) { (it and 0xFF).toByte() }
        MediaProtocol.writeFrame(
            DataOutputStream(sink), MediaProtocol.TYPE_VIDEO_FRAME,
            0x0102030405060708L, payload, keyframe = true
        )
        val b = sink.toByteArray()
        mediaCheck("frame header is 20 bytes", b.size == 20 + payload.size, "got ${b.size}")
        mediaCheck("magic PMF1", String(b, 0, 4) == "PMF1")
        mediaCheck("type at 4", (b[4].toInt() and 0xFF) == MediaProtocol.TYPE_VIDEO_FRAME)
        mediaCheck("keyframe flag at 5", (b[5].toInt() and 0xFF) == MediaProtocol.FLAG_KEYFRAME)
        mediaCheck("reserved zero at 6..7", b[6].toInt() == 0 && b[7].toInt() == 0)
        var pts = 0L; for (i in 8..15) pts = (pts shl 8) or (b[i].toLong() and 0xFF)
        mediaCheck("timestamp big-endian at 8", pts == 0x0102030405060708L)
        var len = 0; for (i in 16..19) len = (len shl 8) or (b[i].toInt() and 0xFF)
        mediaCheck("length at 16", len == payload.size)
    }
    // Real streaming over a socket pair, the way it will actually be used.
    run {
        val server = java.net.ServerSocket(0)
        val received = java.util.concurrent.LinkedBlockingQueue<Pair<MediaProtocol.FrameHeader, ByteArray>>()
        Thread {
            server.accept().use { s ->
                val input = DataInputStream(BufferedInputStream(s.getInputStream()))
                MediaProtocol.readRequest(input)
                repeat(120) {
                    val h = MediaProtocol.readFrameHeader(input)
                    received.put(h to MediaProtocol.readPayload(input, h))
                }
            }
        }.apply { isDaemon = true }.start()

        java.net.Socket("127.0.0.1", server.localPort).use { s ->
            val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            MediaProtocol.writeRequest(out, MediaProtocol.Request(1, 1920, 1080, 30, true, false))
            // 4 seconds of 30fps video interleaved with 20ms audio, sized like the real thing.
            for (i in 0 until 60) {
                val key = i % 30 == 0
                val video = ByteArray(if (key) 90_000 else 12_000) { ((i + it) and 0xFF).toByte() }
                MediaProtocol.writeFrame(out, MediaProtocol.TYPE_VIDEO_FRAME, i * 33_333L, video, keyframe = key)
                val audio = ByteArray(1920) { (it and 0xFF).toByte() } // 20ms 48k mono 16-bit
                MediaProtocol.writeFrame(out, MediaProtocol.TYPE_AUDIO_FRAME, i * 33_333L, audio)
            }
            out.flush()
            val frames = (1..120).map { received.poll(10, java.util.concurrent.TimeUnit.SECONDS)!! }
            mediaCheck("all 120 frames arrived intact", frames.size == 120)
            mediaCheck("keyframes land where expected",
                frames.filter { it.first.type == MediaProtocol.TYPE_VIDEO_FRAME }
                      .filterIndexed { i, _ -> i % 30 == 0 }.all { it.first.isKeyframe })
            mediaCheck("large keyframe payload is byte-exact",
                frames[0].second.size == 90_000 && frames[0].second[89_999] == ((0 + 89_999) and 0xFF).toByte())
            mediaCheck("audio and video share a timeline",
                frames[2].first.timestampUs == 33_333L && frames[3].first.timestampUs == 33_333L)
        }
        server.close()
    }
    return mediaFailures
}
