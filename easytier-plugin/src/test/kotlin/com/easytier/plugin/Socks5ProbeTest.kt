package com.easytier.plugin

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket

class Socks5ProbeTest {

    @Test
    fun `accepts a listener that answers the socks5 greeting`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val port = server.localPort
            val thread = Thread {
                try {
                    server.accept().use { socket ->
                        val greeting = ByteArray(3)
                        var read = 0
                        val input = socket.getInputStream()
                        while (read < 3) {
                            val count = input.read(greeting, read, 3 - read)
                            if (count < 0) return@use
                            read += count
                        }
                        socket.getOutputStream().apply {
                            write(byteArrayOf(0x05, 0x00))
                            flush()
                        }
                    }
                } catch (_: Throwable) {
                    // The assertion below reports the failure.
                }
            }
            thread.isDaemon = true
            thread.start()

            assertTrue(Socks5Probe.verify("127.0.0.1", port, 1_000, 1_000))
            thread.join(2_000)
        }
    }

    @Test
    fun `rejects a listener that does not speak socks5`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val port = server.localPort
            val thread = Thread {
                try {
                    server.accept().use { socket ->
                        val greeting = ByteArray(3)
                        socket.getInputStream().read(greeting)
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 400 Bad Request".toByteArray())
                            flush()
                        }
                    }
                } catch (_: Throwable) {
                    // The assertion below reports the failure.
                }
            }
            thread.isDaemon = true
            thread.start()

            assertFalse(Socks5Probe.verify("127.0.0.1", port, 1_000, 1_000))
            thread.join(2_000)
        }
    }

    @Test
    fun `rejects a closed port`() {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

        assertFalse(Socks5Probe.verify("127.0.0.1", port, 200, 200))
    }
}