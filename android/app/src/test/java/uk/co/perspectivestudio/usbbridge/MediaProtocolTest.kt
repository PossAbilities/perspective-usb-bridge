package uk.co.perspectivestudio.usbbridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * The Windows side is a separate codebase, so the byte layout is the contract
 * between them. These tests pin it down rather than just round-tripping.
 */
class MediaProtocolTest {

    private fun sink() = ByteArrayOutputStream()
    private fun out(sink: ByteArrayOutputStream) = DataOutputStream(sink)
    private fun input(bytes: ByteArray) = DataInputStream(ByteArrayInputStream(bytes))

    @Test
    fun `handshake request round-trips`() {
        val sink = sink()
        val sent = MediaProtocol.Request(
            version = MediaProtocol.VERSION,
            width = 1920,
            height = 1080,
            frameRate = 30,
            wantsAudio = true,
            wantsFrontCamera = true
        )
        MediaProtocol.writeRequest(out(sink), sent)
        assertEquals(MediaProtocol.HANDSHAKE_SIZE, sink.size())
        assertEquals(sent, MediaProtocol.readRequest(input(sink.toByteArray())))
    }

    @Test
    fun `handshake flags are independent`() {
        for (audio in listOf(true, false)) {
            for (front in listOf(true, false)) {
                val sink = sink()
                MediaProtocol.writeRequest(
                    out(sink),
                    MediaProtocol.Request(MediaProtocol.VERSION, 1280, 720, 30, audio, front)
                )
                val read = MediaProtocol.readRequest(input(sink.toByteArray()))
                assertEquals("audio flag", audio, read.wantsAudio)
                assertEquals("front camera flag", front, read.wantsFrontCamera)
            }
        }
    }

    @Test
    fun `accept round-trips and is fixed size`() {
        val sink = sink()
        MediaProtocol.writeAccept(out(sink), MediaProtocol.Accept(1280, 720, 24))
        assertEquals(MediaProtocol.HANDSHAKE_SIZE, sink.size())

        val accept = MediaProtocol.readAccept(input(sink.toByteArray()))
        assertEquals(1280, accept.width)
        assertEquals(720, accept.height)
        assertEquals(24, accept.frameRate)
        assertEquals(MediaProtocol.STATUS_OK, accept.status)
    }

    @Test
    fun `frame header is exactly twenty bytes and carries the payload`() {
        val sink = sink()
        val payload = ByteArray(300) { (it and 0xFF).toByte() }
        MediaProtocol.writeFrame(
            out(sink),
            MediaProtocol.TYPE_VIDEO_FRAME,
            timestampUs = 1_234_567L,
            payload = payload,
            keyframe = true
        )
        assertEquals(MediaProtocol.FRAME_HEADER_SIZE + payload.size, sink.size())

        val stream = input(sink.toByteArray())
        val header = MediaProtocol.readFrameHeader(stream)
        assertEquals(MediaProtocol.TYPE_VIDEO_FRAME, header.type)
        assertEquals(1_234_567L, header.timestampUs)
        assertEquals(payload.size, header.length)
        assertTrue(header.isKeyframe)
        assertArrayEquals(payload, MediaProtocol.readPayload(stream, header))
    }

    @Test
    fun `non-keyframes are not flagged`() {
        val sink = sink()
        MediaProtocol.writeFrame(out(sink), MediaProtocol.TYPE_VIDEO_FRAME, 0L, ByteArray(4))
        assertFalse(MediaProtocol.readFrameHeader(input(sink.toByteArray())).isKeyframe)
    }

    @Test
    fun `a slice of a larger buffer can be sent without copying it first`() {
        val sink = sink()
        val backing = ByteArray(100) { it.toByte() }
        MediaProtocol.writeFrame(
            out(sink), MediaProtocol.TYPE_AUDIO_FRAME, 42L, backing, offset = 10, length = 20
        )
        val stream = input(sink.toByteArray())
        val header = MediaProtocol.readFrameHeader(stream)
        assertEquals(20, header.length)
        assertArrayEquals(backing.copyOfRange(10, 30), MediaProtocol.readPayload(stream, header))
    }

    @Test
    fun `several frames stream back to back`() {
        val sink = sink()
        val out = out(sink)
        MediaProtocol.writeFrame(out, MediaProtocol.TYPE_VIDEO_CONFIG, 0L, byteArrayOf(0, 0, 0, 1, 0x67))
        MediaProtocol.writeFrame(out, MediaProtocol.TYPE_VIDEO_FRAME, 33_000L, ByteArray(64), keyframe = true)
        MediaProtocol.writeFrame(out, MediaProtocol.TYPE_AUDIO_FRAME, 33_000L, ByteArray(1920))

        val stream = input(sink.toByteArray())
        val types = mutableListOf<Int>()
        repeat(3) {
            val header = MediaProtocol.readFrameHeader(stream)
            types += header.type
            MediaProtocol.readPayload(stream, header)
        }
        assertEquals(
            listOf(
                MediaProtocol.TYPE_VIDEO_CONFIG,
                MediaProtocol.TYPE_VIDEO_FRAME,
                MediaProtocol.TYPE_AUDIO_FRAME
            ),
            types
        )
        assertEquals("stream fully consumed", 0, stream.available())
    }

    @Test
    fun `zero length frames are legal`() {
        val sink = sink()
        MediaProtocol.writeFrame(out(sink), MediaProtocol.TYPE_AUDIO_CONFIG, 0L, ByteArray(0))
        val stream = input(sink.toByteArray())
        assertEquals(0, MediaProtocol.readFrameHeader(stream).length)
    }

    @Test(expected = MediaProtocol.ProtocolException::class)
    fun `a foreign client is rejected`() {
        MediaProtocol.readRequest(input(ByteArray(MediaProtocol.HANDSHAKE_SIZE)))
    }

    @Test(expected = MediaProtocol.ProtocolException::class)
    fun `a desynchronised stream is rejected rather than guessed at`() {
        val sink = sink()
        MediaProtocol.writeFrame(out(sink), MediaProtocol.TYPE_VIDEO_FRAME, 0L, ByteArray(8))
        val corrupted = sink.toByteArray().also { it[1] = 'X'.code.toByte() }
        MediaProtocol.readFrameHeader(input(corrupted))
    }

    @Test(expected = MediaProtocol.ProtocolException::class)
    fun `an implausible payload length is rejected before allocating`() {
        val sink = sink()
        val out = out(sink)
        out.write("PMF1".toByteArray())
        out.writeByte(MediaProtocol.TYPE_VIDEO_FRAME)
        out.writeByte(0)
        out.writeShort(0)
        out.writeLong(0L)
        out.writeInt(Int.MAX_VALUE)
        out.flush()
        MediaProtocol.readFrameHeader(input(sink.toByteArray()))
    }

    @Test(expected = MediaProtocol.ProtocolException::class)
    fun `a mismatched protocol version is refused`() {
        val sink = sink()
        val out = out(sink)
        out.write("PSPMEDIA".toByteArray())
        out.writeShort(99)
        out.writeShort(1920); out.writeShort(1080); out.writeByte(30); out.writeByte(0)
        out.flush()
        MediaProtocol.readAccept(input(sink.toByteArray()))
    }
}
