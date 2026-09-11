package com.wifimouse.app.net

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Sends pointer events to the desktop server over UDP.
 *
 * UDP is the right transport here: a dropped movement packet is irrelevant
 * (the next one, 8 ms later, carries the newer position) and there is no
 * head-of-line blocking to add lag. Reliability is only ever needed for
 * discrete events, and those are simply sent twice — the server de-duplicates
 * them by sequence number.
 *
 * All socket work happens on two daemon threads; callbacks are delivered on the
 * main thread.
 */
class MouseClient {

    sealed class State {
        object Idle : State()
        data class Connecting(val host: String) : State()
        data class Connected(val host: String, val serverName: String, val rttMs: Int) : State()
        data class Failed(val reason: String) : State()
    }

    /** Called on the main thread whenever the connection state changes. */
    var onState: ((State) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayBlockingQueue<Outgoing>(QUEUE_CAPACITY)
    private val seq = AtomicLong(1)

    /**
     * Bumped by every start and stop. A worker whose generation is no longer
     * the current one exits quietly, so a reconnect can never end up with two
     * sockets fighting over the same state.
     */
    private val generation = AtomicInteger(0)

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var running = false
    @Volatile private var sender: Thread? = null
    @Volatile private var receiver: Thread? = null

    @Volatile private var host: String = ""
    @Volatile private var port: Int = Protocol.DEFAULT_PORT
    @Volatile private var token: String? = null

    @Volatile private var lastHelloAt = 0L
    @Volatile private var lastReplyAt = 0L
    @Volatile private var state: State = State.Idle

    private data class Outgoing(val body: String, val copies: Int)

    val isConnected: Boolean get() = state is State.Connected

    fun start(host: String, port: Int, token: String?) {
        stop()
        val era = generation.incrementAndGet()
        this.host = host
        this.port = port
        this.token = token?.takeIf { it.isNotEmpty() }
        queue.clear()
        lastReplyAt = 0L
        lastHelloAt = 0L
        running = true
        publish(State.Connecting(host))
        sender = Thread({ sendLoop(era) }, "wifimouse-send").apply { isDaemon = true; start() }
    }

    /**
     * Asks the worker threads to finish. The socket is deliberately closed by
     * the sender thread rather than here: sending the farewell packet is
     * network I/O, which Android forbids on the main thread.
     */
    fun stop() {
        running = false
        generation.incrementAndGet()
        val worker = sender
        sender = null
        receiver = null
        worker?.interrupt() // wakes the queue poll immediately
        publish(State.Idle)
    }

    // -- sending ---------------------------------------------------------- #

    /**
     * Queues one command. Motion is dropped when the queue backs up, because a
     * stale position is worth less than a fresh one; clicks never are.
     */
    fun send(body: String, copies: Int = 1) {
        if (!running) return
        val item = Outgoing(body, copies)
        if (queue.offer(item)) return
        if (body.startsWith("m ") || body.startsWith("s ")) return
        queue.poll()
        queue.offer(item)
    }

    fun move(dx: Float, dy: Float) = send(Protocol.move(dx, dy))

    fun scroll(dx: Float, dy: Float) = send(Protocol.scroll(dx, dy))

    fun click(button: Char, copies: Int) = send(Protocol.click(button), copies)

    fun buttonDown(button: Char, copies: Int) = send(Protocol.buttonDown(button), copies)

    fun buttonUp(button: Char, copies: Int) = send(Protocol.buttonUp(button), copies)

    fun key(name: String, copies: Int) = send(Protocol.key(name), copies)

    fun type(text: String, copies: Int) = send(Protocol.text(text), copies)

    /** True while this worker is still the connection the app wants. */
    private fun current(era: Int) = running && generation.get() == era

    private fun sendLoop(era: Int) {
        val address = try {
            InetAddress.getByName(host)
        } catch (exc: Exception) {
            if (current(era)) {
                publish(State.Failed("Cannot find $host"))
                running = false
            }
            return
        }

        val live = try {
            DatagramSocket().apply {
                // Connecting pins the peer, which makes sends cheaper and lets
                // the socket surface "nothing is listening" as an exception.
                connect(InetSocketAddress(address, port))
                soTimeout = RECEIVE_TIMEOUT_MS
            }
        } catch (exc: Exception) {
            if (current(era)) {
                publish(State.Failed("Cannot open socket: ${exc.message}"))
                running = false
            }
            return
        }
        if (!current(era)) {
            live.close()
            return
        }
        socket = live

        receiver = Thread({ receiveLoop(live, era) }, "wifimouse-recv")
            .apply { isDaemon = true; start() }

        try {
            while (current(era)) {
                val item = try {
                    queue.poll(POLL_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                }
                val now = SystemClock.uptimeMillis()

                if (item != null) {
                    val payload = Protocol.packet(token, seq.getAndIncrement(), item.body)
                    var copies = item.copies.coerceIn(1, MAX_COPIES)
                    while (copies-- > 0 && transmit(live, payload, era)) {
                        // Duplicates share a sequence number, so the server
                        // applies whichever arrives first and drops the rest.
                    }
                }

                if (now - lastHelloAt >= HELLO_INTERVAL_MS) {
                    lastHelloAt = now
                    transmit(live, Protocol.packet(token, seq.getAndIncrement(), Protocol.HELLO), era)
                }

                // Heartbeats are answered; silence means the link is gone.
                val current = state
                if (current is State.Connected && now - lastReplyAt > REPLY_TIMEOUT_MS) {
                    publish(State.Connecting(host))
                }
            }
        } finally {
            farewell(live)
            live.close()
            // A newer connection may already own the field; leave that one alone.
            if (socket === live) socket = null
        }
    }

    /** Tells the server to release anything this phone is still holding. */
    private fun farewell(live: DatagramSocket) {
        if (live.isClosed) return
        try {
            val payload = Protocol.packet(token, seq.getAndIncrement(), Protocol.BYE)
            live.send(DatagramPacket(payload, payload.size))
        } catch (_: IOException) {
            // Not fatal: the desktop releases held buttons on its own after a
            // couple of seconds of silence.
        }
    }

    private fun transmit(live: DatagramSocket, payload: ByteArray, era: Int): Boolean {
        return try {
            live.send(DatagramPacket(payload, payload.size))
            true
        } catch (_: PortUnreachableException) {
            // The host is up but nothing is listening: keep trying so the app
            // recovers by itself once the server is started.
            if (current(era)) publish(State.Failed("No server on $host:$port"))
            false
        } catch (exc: IOException) {
            if (current(era)) {
                Log.w(TAG, "send failed", exc)
                publish(State.Failed(exc.message ?: "Network error"))
            }
            false
        }
    }

    // -- receiving -------------------------------------------------------- #

    private fun receiveLoop(live: DatagramSocket, era: Int) {
        val buffer = ByteArray(Protocol.MAX_PACKET)
        while (current(era) && !live.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                live.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: PortUnreachableException) {
                continue
            } catch (exc: IOException) {
                if (current(era)) Log.d(TAG, "receive ended: ${exc.message}")
                return
            }

            val reply = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
            val now = SystemClock.uptimeMillis()
            when {
                !current(era) -> return

                reply.startsWith(Protocol.REPLY_OK) -> {
                    lastReplyAt = now
                    val name = reply.split(" ").getOrNull(2)?.let { Protocol.unquote(it) } ?: host
                    val rtt = (now - lastHelloAt).coerceIn(0, 9999).toInt()
                    publish(State.Connected(host, name, rtt))
                }

                reply.startsWith(Protocol.REPLY_ERROR) -> {
                    val reason = reply.split(" ").getOrNull(2)
                    publish(
                        State.Failed(
                            if (reason == "auth") "Pairing code rejected" else "Server said: $reason"
                        )
                    )
                }
            }
        }
    }

    // -- state ------------------------------------------------------------ #

    private fun publish(next: State) {
        if (next == state) return
        state = next
        main.post { onState?.invoke(next) }
    }

    companion object {
        private const val TAG = "MouseClient"
        private const val QUEUE_CAPACITY = 512
        private const val POLL_MS = 100L
        private const val HELLO_INTERVAL_MS = 1000L
        private const val REPLY_TIMEOUT_MS = 3500L
        private const val RECEIVE_TIMEOUT_MS = 1000
        private const val MAX_COPIES = 3
    }
}
