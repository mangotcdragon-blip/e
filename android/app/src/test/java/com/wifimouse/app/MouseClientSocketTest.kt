package com.wifimouse.app

import com.wifimouse.app.net.MouseClient
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the bug that shipped in the first build: the socket was opened inside
 * a `DatagramSocket.apply { ... }` block, where `port` resolved to the socket's
 * own getPort() — which is -1 before it is connected — instead of the port the
 * caller asked for. Every connection died with "port out of range: -1".
 */
class MouseClientSocketTest {

    @Test
    fun `socket is connected to the port it was given`() {
        val socket = MouseClient.openSocket(InetAddress.getLoopbackAddress(), 7654)
        try {
            assertEquals(7654, socket.port)
            assertEquals(InetAddress.getLoopbackAddress(), socket.inetAddress)
            assertTrue(socket.isConnected)
        } finally {
            socket.close()
        }
    }

    @Test
    fun `a packet reaches a listener on that port`() {
        val listener = DatagramSocket(0, InetAddress.getLoopbackAddress())
        listener.soTimeout = 2000
        try {
            val sender = MouseClient.openSocket(InetAddress.getLoopbackAddress(), listener.localPort)
            try {
                val payload = "WM1 - 1 m 5.00 5.00".toByteArray()
                sender.send(DatagramPacket(payload, payload.size))

                val received = DatagramPacket(ByteArray(256), 256)
                listener.receive(received)
                assertEquals(
                    "WM1 - 1 m 5.00 5.00",
                    String(received.data, 0, received.length),
                )
            } finally {
                sender.close()
            }
        } finally {
            listener.close()
        }
    }

    @Test
    fun `an impossible port is refused rather than silently accepted`() {
        // -1 and 70000 fail the range check; 0 gets as far as connect() and is
        // refused there, so the type differs and only the refusal matters.
        for (port in listOf(-1, 0, 70000)) {
            try {
                MouseClient.openSocket(InetAddress.getLoopbackAddress(), port).close()
                assertTrue("port $port should not have been accepted", false)
            } catch (expected: Exception) {
                // Bad input is rejected, which is all this needs to establish.
            }
        }
    }
}
