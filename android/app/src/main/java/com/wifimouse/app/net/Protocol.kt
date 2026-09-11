package com.wifimouse.app.net

import java.util.Locale

/**
 * The WM1 wire format, kept byte-for-byte in step with `server/wifimouse/protocol.py`.
 *
 * One UDP datagram carries one command line:
 *
 *     WM1 <token> <seq> <verb> [args...]
 */
object Protocol {

    const val MAGIC = "WM1"
    const val DEFAULT_PORT = 7654
    const val NO_TOKEN = "-"
    const val MAX_PACKET = 1024

    /** Broadcast to find servers on the local network. */
    const val DISCOVER_REQUEST = "$MAGIC ? 0 discover"

    /** Replies the server can send back. */
    const val REPLY_OK = "$MAGIC ok"
    const val REPLY_ERROR = "$MAGIC err"
    const val REPLY_SERVER = "$MAGIC srv"

    fun packet(token: String?, seq: Long, body: String): ByteArray {
        val code = token?.takeIf { it.isNotEmpty() } ?: NO_TOKEN
        return "$MAGIC $code $seq $body".toByteArray(Charsets.UTF_8)
    }

    fun move(dx: Float, dy: Float) = "m ${num(dx)} ${num(dy)}"

    fun moveAbsolute(nx: Float, ny: Float) = "a ${num(nx)} ${num(ny)}"

    fun scroll(dx: Float, dy: Float) = "s ${num(dx)} ${num(dy)}"

    fun buttonDown(button: Char) = "d $button"

    fun buttonUp(button: Char) = "u $button"

    fun click(button: Char) = "c $button"

    fun key(name: String) = "k $name"

    fun text(value: String) = "t ${quote(value)}"

    const val HELLO = "h"
    const val BYE = "b"

    /**
     * Formats with [Locale.ROOT] on purpose: on a phone set to a locale that
     * uses a decimal comma, the default formatter would emit "1,5" and the
     * server would reject every packet.
     */
    private fun num(value: Float): String = String.format(Locale.ROOT, "%.2f", value)

    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"

    /**
     * Percent-encodes UTF-8 the same way Python's `urllib.parse.quote(safe="")`
     * does. `URLEncoder` is deliberately not used: it turns spaces into `+`,
     * which the server would type literally.
     */
    fun quote(value: String): String {
        val out = StringBuilder(value.length + 8)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val char = (byte.toInt() and 0xFF).toChar()
            if (UNRESERVED.indexOf(char) >= 0) {
                out.append(char)
            } else {
                out.append('%').append(String.format(Locale.ROOT, "%02X", byte.toInt() and 0xFF))
            }
        }
        return out.toString()
    }

    /** A discovered server, as announced by `WM1 srv <port> <name> <needsToken>`. */
    data class ServerInfo(
        val host: String,
        val port: Int,
        val name: String,
        val needsToken: Boolean,
    )

    fun parseServerReply(reply: String, host: String): ServerInfo? {
        val fields = reply.trim().split(" ")
        if (fields.size < 5 || fields[0] != MAGIC || fields[1] != "srv") return null
        val port = fields[2].toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return ServerInfo(
            host = host,
            port = port,
            name = unquote(fields[3]).ifBlank { host },
            needsToken = fields[4] == "1",
        )
    }

    fun unquote(value: String): String {
        if (!value.contains('%')) return value
        val bytes = java.io.ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '%' && index + 2 < value.length) {
                val hex = value.substring(index + 1, index + 3).toIntOrNull(16)
                if (hex != null) {
                    bytes.write(hex)
                    index += 3
                    continue
                }
            }
            bytes.write(char.code)
            index++
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }
}
