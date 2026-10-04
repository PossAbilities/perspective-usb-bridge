package uk.co.perspectivestudio.usbbridge

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A byte pipe to one viewer: a direct socket on the LAN, or the relay. */
interface MediaLink : Closeable {
    val input: InputStream
    val output: OutputStream
    val peer: String
}

class SocketLink(private val socket: Socket) : MediaLink {
    override val input: InputStream = socket.getInputStream()
    override val output: OutputStream = socket.getOutputStream()
    override val peer: String = socket.inetAddress.hostAddress ?: "client"
    override fun close() = socket.close()
}

/**
 * The tablet's end of the camera relay (relay/server.js).
 *
 * The tablet and the computer are often on different networks where neither
 * can accept a connection, so both dial out to the relay and are paired by a
 * code. Over the link the media protocol is byte-for-byte the same as on the
 * LAN: each WebSocket binary message is just a chunk of the stream.
 */
class RelayLink private constructor() : MediaLink {
    companion object {
        const val URL = "wss://camrelay.pixelhub.org.uk/relay"

        /** Close codes from relay/server.js. */
        const val CLOSE_PEER_LEFT = 4010

        /** OkHttp queues sends without limit; past this the viewer cannot keep up. */
        private const val MAX_QUEUED_BYTES = 4L * 1024 * 1024

        private const val PREFS = "relay"
        private const val KEY_CODE = "pairing_code"
        /** Crockford base32: no I, L, O or U, so a code read aloud is unambiguous. */
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

        private val client = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        /** This tablet's pairing code, created once and kept. 10 characters is 50 bits. */
        fun pairingCode(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.getString(KEY_CODE, null)?.let { return it }
            val random = SecureRandom()
            val code = (1..10).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
            prefs.edit().putString(KEY_CODE, code).apply()
            return code
        }

        /** Shown grouped, e.g. 7KQ2M-X9WPA; the desktop strips the dash. */
        fun displayCode(code: String): String =
            if (code.length == 10) "${code.substring(0, 5)}-${code.substring(5)}" else code

        /** Connects as the tablet for [code]; blocks until open, or throws. */
        fun connect(code: String): RelayLink {
            val link = RelayLink()
            val request = Request.Builder().url("$URL?role=tablet&code=$code").build()
            link.socket = client.newWebSocket(request, link.listener)
            if (!link.opened.await(15, TimeUnit.SECONDS) || link.failure != null) {
                link.close()
                throw IOException(link.failure ?: "The relay did not answer")
            }
            return link
        }
    }

    private lateinit var socket: WebSocket
    private val opened = CountDownLatch(1)
    @Volatile private var failure: String? = null
    private val incoming = LinkedBlockingQueue<ByteArray>()
    private val endOfStream = ByteArray(0)

    override val peer: String = "relay"

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = opened.countDown()

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (bytes.size > 0) incoming.put(bytes.toByteArray())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            finish()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish()

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            failure = t.message ?: t.javaClass.simpleName
            finish()
        }
    }

    private fun finish() {
        opened.countDown()
        incoming.put(endOfStream)
    }

    override val input: InputStream = object : InputStream() {
        private var current: ByteArray? = null
        private var position = 0

        /** Next chunk with bytes left, or null at end of stream. */
        private fun chunk(): ByteArray? {
            current?.let { if (position < it.size) return it }
            val next = incoming.take()
            if (next === endOfStream) {
                incoming.put(endOfStream) // stay at end for any later read
                return null
            }
            current = next
            position = 0
            return next
        }

        override fun read(): Int {
            val bytes = chunk() ?: return -1
            return bytes[position++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val bytes = chunk() ?: return -1
            val count = minOf(len, bytes.size - position)
            System.arraycopy(bytes, position, b, off, count)
            position += count
            return count
        }
    }

    /** Buffers writes and sends them as one message per flush, i.e. per media frame. */
    override val output: OutputStream = object : OutputStream() {
        private val buffer = ByteArrayOutputStream(64 * 1024)

        override fun write(b: Int) = buffer.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = buffer.write(b, off, len)

        override fun flush() {
            if (buffer.size() == 0) return
            if (socket.queueSize() > MAX_QUEUED_BYTES) {
                throw IOException("The connection through the relay is too slow for this picture size")
            }
            if (!socket.send(buffer.toByteArray().toByteString())) throw IOException("Relay connection closed")
            buffer.reset()
        }
    }

    override fun close() {
        if (::socket.isInitialized) socket.close(1000, null)
        finish()
    }
}
