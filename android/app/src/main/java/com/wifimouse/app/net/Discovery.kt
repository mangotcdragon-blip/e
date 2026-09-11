package com.wifimouse.app.net

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Finds servers by broadcasting on the local network, so the user never has to
 * type an IP address.
 *
 * Broadcast addresses are read from the device's own interfaces rather than
 * assuming 255.255.255.255, which many Wi-Fi drivers quietly drop.
 */
object Discovery {

    private const val TAG = "Discovery"
    private const val SEARCH_MS = 1500L

    /**
     * Searches on a background thread and delivers the results on the main
     * thread. Cancelling is not needed: the thread always ends within
     * [SEARCH_MS].
     */
    fun search(port: Int = Protocol.DEFAULT_PORT, onResult: (List<Protocol.ServerInfo>) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        Thread({
            val found = runSearch(port)
            main.post { onResult(found) }
        }, "wifimouse-discover").apply { isDaemon = true }.start()
    }

    private fun runSearch(port: Int): List<Protocol.ServerInfo> {
        val servers = LinkedHashMap<String, Protocol.ServerInfo>()
        val socket = try {
            DatagramSocket().apply {
                broadcast = true
                soTimeout = 250
            }
        } catch (exc: Exception) {
            Log.w(TAG, "cannot open discovery socket", exc)
            return emptyList()
        }

        try {
            val request = Protocol.DISCOVER_REQUEST.toByteArray(Charsets.UTF_8)
            val targets = broadcastAddresses()
            val deadline = SystemClock.uptimeMillis() + SEARCH_MS
            var nextProbe = 0L

            val buffer = ByteArray(Protocol.MAX_PACKET)
            while (SystemClock.uptimeMillis() < deadline) {
                val now = SystemClock.uptimeMillis()
                if (now >= nextProbe) {
                    // Re-probe a few times: a single broadcast is easy to lose.
                    nextProbe = now + 400
                    for (target in targets) {
                        try {
                            socket.send(DatagramPacket(request, request.size, target, port))
                        } catch (exc: Exception) {
                            Log.d(TAG, "probe to $target failed: ${exc.message}")
                        }
                    }
                }

                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (exc: Exception) {
                    Log.d(TAG, "discovery receive failed: ${exc.message}")
                    break
                }

                val reply = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val host = packet.address?.hostAddress ?: continue
                Protocol.parseServerReply(reply, host)?.let { servers[host] = it }
            }
        } finally {
            socket.close()
        }
        return servers.values.toList()
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>()
        try {
            for (nic in NetworkInterface.getNetworkInterfaces()) {
                if (!nic.isUp || nic.isLoopback) continue
                for (entry in nic.interfaceAddresses) {
                    entry.broadcast?.let { addresses.add(it) }
                }
            }
        } catch (exc: Exception) {
            Log.w(TAG, "cannot list interfaces", exc)
        }
        // Keep the global broadcast as a fallback for odd network setups.
        try {
            addresses.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {
        }
        return addresses.distinct()
    }
}
