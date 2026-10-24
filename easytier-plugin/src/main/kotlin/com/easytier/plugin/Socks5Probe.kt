package com.easytier.plugin

import java.net.InetSocketAddress
import java.net.Socket

/**
 * Verifies that a TCP listener on the loopback interface really speaks SOCKS5.
 *
 * A plain TCP connect is not enough: any process on the device can bind the
 * configured port before EasyTier does (or after it failed to start), and a
 * successful connect would then be mistaken for "EasyTier is ready".  Xray
 * would hand mesh-bound traffic to that foreign process.  Answering the
 * protocol greeting proves that the listener is a SOCKS5 server.
 *
 * The probe deliberately does not send a CONNECT request: it only negotiates
 * the greeting and closes the socket, so it cannot be used to reach anything.
 *
 * This class has no Android dependency so it can be covered by JVM tests.
 */
object Socks5Probe {

    /** SOCKS5 version byte. */
    private const val SOCKS5_VERSION: Byte = 0x05

    /** "No authentication required" method. */
    private const val METHOD_NO_AUTH: Byte = 0x00

    /** "No acceptable methods" - still proves the peer speaks SOCKS5. */
    private const val METHOD_NONE_ACCEPTABLE: Byte = 0xFF.toByte()

    private val greeting = byteArrayOf(SOCKS5_VERSION, 0x01, METHOD_NO_AUTH)

    /**
     * @return true when the listener answered the SOCKS5 greeting with a valid
     *   version byte and a defined method selection.
     */
    fun verify(
        host: String,
        port: Int,
        connectTimeoutMs: Int = 200,
        readTimeoutMs: Int = 300
    ): Boolean {
        if (port !in 1..65535) return false
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                socket.soTimeout = readTimeoutMs
                socket.getOutputStream().apply {
                    write(greeting)
                    flush()
                }
                val reply = readFully(socket, 2) ?: return false
                reply[0] == SOCKS5_VERSION &&
                    (reply[1] == METHOD_NO_AUTH || reply[1] == METHOD_NONE_ACCEPTABLE)
            }
        } catch (e: Throwable) {
            false
        }
    }

    private fun readFully(socket: Socket, length: Int): ByteArray? {
        val buffer = ByteArray(length)
        var read = 0
        val input = socket.getInputStream()
        while (read < length) {
            val count = input.read(buffer, read, length - read)
            if (count < 0) return null
            read += count
        }
        return buffer
    }
}