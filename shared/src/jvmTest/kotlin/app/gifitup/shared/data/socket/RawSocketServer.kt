package app.gifitup.shared.data.socket

import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import kotlin.concurrent.thread

/**
 * A minimal WebSocket server for the connector tests (#27): one connection at a time, handshake by hand, so a test decides
 * byte by byte what the "server" does (a redirect, a huge frame, a close code). Test code only.
 */
internal class RawSocketServer(
    private val script: (Peer) -> Unit,
) : AutoCloseable {
    private val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort
    val url: String get() = "ws://127.0.0.1:$port/ws"

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { script(Peer(socket)) }.also { runCatching { socket.close() } } }
            }
        }
    }

    override fun close() = server.close()

    class Peer(
        private val socket: Socket,
    ) {
        private val input: InputStream = socket.getInputStream()
        private val output: OutputStream = socket.getOutputStream()

        /** Reads the upgrade request and returns its headers (lower-case names). */
        fun readRequest(): Map<String, String> {
            val lines = mutableListOf<String>()
            val line = StringBuilder()
            while (true) {
                val b = input.read()
                check(b >= 0)
                if (b == '\n'.code) {
                    val text = line.toString().trimEnd('\r')
                    line.clear()
                    if (text.isEmpty()) break
                    lines += text
                } else {
                    line.append(b.toChar())
                }
            }
            return lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        }

        fun accept(headers: Map<String, String>) {
            val key = checkNotNull(headers["sec-websocket-key"])
            val digest = MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray())
            val accept = Base64.getEncoder().encodeToString(digest)
            write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n")
        }

        fun redirect() = write("HTTP/1.1 302 Found\r\nLocation: ws://127.0.0.1:1/other\r\nContent-Length: 0\r\n\r\n")

        private fun write(text: String) {
            output.write(text.toByteArray())
            output.flush()
        }

        /** The text of the next (masked, client) text frame. */
        fun readText(): String {
            val first = input.read()
            check(first and 0x0F == 1)
            val second = input.read()
            var length = second and 0x7F
            if (length == 126) length = (input.read() shl 8) or input.read()
            val mask = ByteArray(4).also { input.readNBytes(it, 0, 4) }
            val payload = input.readNBytes(length)
            return String(ByteArray(length) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() })
        }

        fun sendText(text: String) = sendFrame(0x1, text.toByteArray())

        fun sendClose(code: Int) = sendFrame(0x8, byteArrayOf((code shr 8).toByte(), code.toByte()))

        private fun sendFrame(
            opcode: Int,
            payload: ByteArray,
        ) {
            output.write(0x80 or opcode)
            when {
                payload.size < 126 -> {
                    output.write(payload.size)
                }

                payload.size < 65536 -> {
                    output.write(byteArrayOf(126, (payload.size shr 8).toByte(), payload.size.toByte()))
                }

                else -> {
                    output.write(
                        byteArrayOf(
                            127,
                            0,
                            0,
                            0,
                            0,
                            (payload.size shr 24).toByte(),
                            (payload.size shr 16).toByte(),
                            (
                                payload.size shr
                                    8
                            ).toByte(),
                            payload.size.toByte(),
                        ),
                    )
                }
            }
            output.write(payload)
            output.flush()
        }

        /** Blocks until the client closes the connection. */
        fun awaitEnd() {
            while (input.read() >= 0) Unit
        }
    }

    private companion object {
        const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }
}
