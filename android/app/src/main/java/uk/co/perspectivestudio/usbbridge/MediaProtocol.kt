package uk.co.perspectivestudio.usbbridge

import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

/**
 * Wire format for the camera and microphone bridge. See docs/CAMERA-BRIDGE.md.
 *
 * Deliberately separate from the USB/IP bridge: that carries USB transfers, this
 * carries encoded media. They share only the big-endian convention and a habit
 * of being explicit about byte layout.
 */
object MediaProtocol {
    const val PORT = 32402
    const val VERSION = 1

    private val HANDSHAKE_MAGIC = "PSPMEDIA".toByteArray(StandardCharsets.US_ASCII)
    private val FRAME_MAGIC = "PMF1".toByteArray(StandardCharsets.US_ASCII)

    const val HANDSHAKE_SIZE = 16
    const val FRAME_HEADER_SIZE = 20

    /** Payload can be large on a keyframe, but a sane ceiling stops a bad peer eating memory. */
    const val MAX_PAYLOAD = 8 * 1024 * 1024

    const val TYPE_VIDEO_CONFIG = 1
    const val TYPE_VIDEO_FRAME = 2
    const val TYPE_AUDIO_CONFIG = 3
    const val TYPE_AUDIO_FRAME = 4

    const val FLAG_KEYFRAME = 0x01

    const val REQUEST_AUDIO = 0x01
    const val REQUEST_FRONT_CAMERA = 0x02

    const val STATUS_OK = 0
    const val STATUS_REFUSED = 1

    data class Request(
        val version: Int,
        val width: Int,
        val height: Int,
        val frameRate: Int,
        val wantsAudio: Boolean,
        val wantsFrontCamera: Boolean
    )

    data class Accept(
        val width: Int,
        val height: Int,
        val frameRate: Int,
        val status: Int = STATUS_OK
    )

    data class FrameHeader(
        val type: Int,
        val flags: Int,
        val timestampUs: Long,
        val length: Int
    ) {
        val isKeyframe: Boolean get() = (flags and FLAG_KEYFRAME) != 0
    }

    class ProtocolException(message: String) : Exception(message)

    // ------------------------------------------------------------- handshake

    fun writeRequest(out: DataOutputStream, request: Request) {
        out.write(HANDSHAKE_MAGIC)
        out.writeShort(request.version)
        out.writeShort(request.width)
        out.writeShort(request.height)
        out.writeByte(request.frameRate)
        var flags = 0
        if (request.wantsAudio) flags = flags or REQUEST_AUDIO
        if (request.wantsFrontCamera) flags = flags or REQUEST_FRONT_CAMERA
        out.writeByte(flags)
        out.flush()
    }

    fun readRequest(input: DataInputStream): Request {
        val magic = ByteArray(HANDSHAKE_MAGIC.size)
        input.readFully(magic)
        if (!magic.contentEquals(HANDSHAKE_MAGIC)) {
            throw ProtocolException("Not a Perspective media client")
        }
        val version = input.readUnsignedShort()
        val width = input.readUnsignedShort()
        val height = input.readUnsignedShort()
        val frameRate = input.readUnsignedByte()
        val flags = input.readUnsignedByte()
        return Request(
            version = version,
            width = width,
            height = height,
            frameRate = frameRate,
            wantsAudio = (flags and REQUEST_AUDIO) != 0,
            wantsFrontCamera = (flags and REQUEST_FRONT_CAMERA) != 0
        )
    }

    fun writeAccept(out: DataOutputStream, accept: Accept) {
        out.write(HANDSHAKE_MAGIC)
        out.writeShort(VERSION)
        out.writeShort(accept.width)
        out.writeShort(accept.height)
        out.writeByte(accept.frameRate)
        out.writeByte(accept.status)
        out.flush()
    }

    fun readAccept(input: DataInputStream): Accept {
        val magic = ByteArray(HANDSHAKE_MAGIC.size)
        input.readFully(magic)
        if (!magic.contentEquals(HANDSHAKE_MAGIC)) {
            throw ProtocolException("Not a Perspective media server")
        }
        val version = input.readUnsignedShort()
        if (version != VERSION) throw ProtocolException("Unsupported media protocol version $version")
        val width = input.readUnsignedShort()
        val height = input.readUnsignedShort()
        val frameRate = input.readUnsignedByte()
        val status = input.readUnsignedByte()
        return Accept(width, height, frameRate, status)
    }

    // ---------------------------------------------------------------- frames

    fun writeFrame(
        out: DataOutputStream,
        type: Int,
        timestampUs: Long,
        payload: ByteArray,
        offset: Int = 0,
        length: Int = payload.size,
        keyframe: Boolean = false
    ) {
        require(length >= 0 && offset >= 0 && offset + length <= payload.size) {
            "payload slice out of bounds"
        }
        out.write(FRAME_MAGIC)
        out.writeByte(type)
        out.writeByte(if (keyframe) FLAG_KEYFRAME else 0)
        out.writeShort(0) // reserved
        out.writeLong(timestampUs)
        out.writeInt(length)
        if (length > 0) out.write(payload, offset, length)
        out.flush()
    }

    fun readFrameHeader(input: DataInputStream): FrameHeader {
        val magic = ByteArray(FRAME_MAGIC.size)
        input.readFully(magic)
        if (!magic.contentEquals(FRAME_MAGIC)) {
            // Recovering from a desynchronised stream is not worth the
            // complexity; the caller should drop the connection.
            throw ProtocolException("Lost frame sync")
        }
        val type = input.readUnsignedByte()
        val flags = input.readUnsignedByte()
        input.readUnsignedShort() // reserved
        val timestampUs = input.readLong()
        val length = input.readInt()
        if (length < 0 || length > MAX_PAYLOAD) {
            throw ProtocolException("Implausible media payload length $length")
        }
        return FrameHeader(type, flags, timestampUs, length)
    }

    fun readPayload(input: DataInputStream, header: FrameHeader): ByteArray {
        val payload = ByteArray(header.length)
        input.readFully(payload)
        return payload
    }
}
