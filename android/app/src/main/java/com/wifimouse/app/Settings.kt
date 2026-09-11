package com.wifimouse.app

import android.content.Context
import android.content.SharedPreferences
import com.wifimouse.app.net.Protocol

/** Everything the user can change, backed by SharedPreferences. */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("wifimouse", Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString(KEY_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOST, value.trim()).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, Protocol.DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value.coerceIn(1, 65535)).apply()

    /** Pairing code; empty means the server is running without one. */
    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    /** Pointer speed multiplier. */
    var sensitivity: Float
        get() = prefs.getFloat(KEY_SENSITIVITY, 1.6f)
        set(value) = prefs.edit().putFloat(KEY_SENSITIVITY, value.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)).apply()

    var scrollSpeed: Float
        get() = prefs.getFloat(KEY_SCROLL_SPEED, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_SCROLL_SPEED, value.coerceIn(MIN_SCROLL, MAX_SCROLL)).apply()

    /** Content follows the fingers, like a phone, rather than like a wheel. */
    var naturalScroll: Boolean
        get() = prefs.getBoolean(KEY_NATURAL_SCROLL, true)
        set(value) = prefs.edit().putBoolean(KEY_NATURAL_SCROLL, value).apply()

    /** Speed-dependent pointer gain, so small swipes stay precise. */
    var acceleration: Boolean
        get() = prefs.getBoolean(KEY_ACCELERATION, true)
        set(value) = prefs.edit().putBoolean(KEY_ACCELERATION, value).apply()

    var tapToClick: Boolean
        get() = prefs.getBoolean(KEY_TAP_TO_CLICK, true)
        set(value) = prefs.edit().putBoolean(KEY_TAP_TO_CLICK, value).apply()

    var haptics: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTICS, value).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    /**
     * Send clicks and keystrokes twice. UDP can drop a datagram, and a lost
     * click is very noticeable; the server throws the duplicate away.
     */
    var resendClicks: Boolean
        get() = prefs.getBoolean(KEY_RESEND, true)
        set(value) = prefs.edit().putBoolean(KEY_RESEND, value).apply()

    var autoConnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CONNECT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CONNECT, value).apply()

    val isConfigured: Boolean get() = host.isNotEmpty()

    companion object {
        const val MIN_SENSITIVITY = 0.4f
        const val MAX_SENSITIVITY = 4.0f
        const val MIN_SCROLL = 0.2f
        const val MAX_SCROLL = 4.0f

        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_SCROLL_SPEED = "scroll_speed"
        private const val KEY_NATURAL_SCROLL = "natural_scroll"
        private const val KEY_ACCELERATION = "acceleration"
        private const val KEY_TAP_TO_CLICK = "tap_to_click"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_RESEND = "resend_clicks"
        private const val KEY_AUTO_CONNECT = "auto_connect"
    }
}
