package com.wifimouse.app

import com.wifimouse.app.net.Protocol
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server parses these packets with Python's `float()` and
 * `urllib.parse.unquote()`, so the encoding has to match exactly.
 */
class ProtocolTest {

    private val originalLocale: Locale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    private fun text(bytes: ByteArray) = String(bytes, Charsets.UTF_8)

    @Test
    fun `packet layout`() {
        assertEquals(
            "WM1 - 7 m 12.50 -4.25",
            text(Protocol.packet(null, 7, Protocol.move(12.5f, -4.25f))),
        )
    }

    @Test
    fun `token is included when set`() {
        assertEquals("WM1 hunter2 1 c l", text(Protocol.packet("hunter2", 1, Protocol.click('l'))))
    }

    @Test
    fun `an empty token is sent as none`() {
        assertTrue(text(Protocol.packet("", 1, Protocol.HELLO)).startsWith("WM1 - "))
    }

    @Test
    fun `numbers never use a locale decimal comma`() {
        // On a German phone the default formatter would produce "1,50", which
        // the server cannot parse.
        Locale.setDefault(Locale.GERMANY)
        assertEquals("m 1.50 -0.25", Protocol.move(1.5f, -0.25f))
        assertEquals("s 0.00 3.75", Protocol.scroll(0f, 3.75f))
    }

    @Test
    fun `text is percent encoded so spaces survive the wire format`() {
        assertEquals("t hello%20world", Protocol.text("hello world"))
        // Notably not "+", which Python's unquote would type literally.
        assertTrue(!Protocol.text("a b").contains('+'))
    }

    @Test
    fun `unicode is encoded as utf-8 bytes`() {
        assertEquals("t %C3%A9", Protocol.text("é"))
        assertEquals("t %F0%9F%98%80", Protocol.text("😀"))
    }

    @Test
    fun `unreserved characters are left alone`() {
        assertEquals("t azAZ09-_.~", Protocol.text("azAZ09-_.~"))
    }

    @Test
    fun `quote and unquote round trip`() {
        val original = "mixed — text 100% \"quoted\" & spaced"
        assertEquals(original, Protocol.unquote(Protocol.quote(original)))
    }

    @Test
    fun `server reply is parsed`() {
        val info = Protocol.parseServerReply("WM1 srv 7654 my%20desktop 1", "192.168.1.7")
        requireNotNull(info)
        assertEquals("192.168.1.7", info.host)
        assertEquals(7654, info.port)
        assertEquals("my desktop", info.name)
        assertTrue(info.needsToken)
    }

    @Test
    fun `unnamed server falls back to its address`() {
        val info = Protocol.parseServerReply("WM1 srv 7654  0", "192.168.1.7")
        assertEquals("192.168.1.7", info?.name)
    }

    @Test
    fun `junk replies are rejected`() {
        assertNull(Protocol.parseServerReply("HTTP/1.1 200 OK", "10.0.0.1"))
        assertNull(Protocol.parseServerReply("WM1 srv notaport name 0", "10.0.0.1"))
        assertNull(Protocol.parseServerReply("WM1 srv 99999 name 0", "10.0.0.1"))
        assertNull(Protocol.parseServerReply("", "10.0.0.1"))
    }

    @Test
    fun `verbs match the server vocabulary`() {
        assertEquals("d r", Protocol.buttonDown('r'))
        assertEquals("u m", Protocol.buttonUp('m'))
        assertEquals("k enter", Protocol.key("enter"))
        assertEquals("a 0.50 0.25", Protocol.moveAbsolute(0.5f, 0.25f))
    }
}
