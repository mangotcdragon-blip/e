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

    /** How far one notch of the wheel scrolls. */
    var scrollSpeed: Float
        get() = prefs.getFloat(KEY_SCROLL_SPEED, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_SCROLL_SPEED, value.coerceIn(MIN_SCROLL, MAX_SCROLL)).apply()

    /** Content follows the wheel, like a phone, rather than like a mouse. */
    var naturalScroll: Boolean
        get() = prefs.getBoolean(KEY_NATURAL_SCROLL, true)
        set(value) = prefs.edit().putBoolean(KEY_NATURAL_SCROLL, value).apply()

    /** Pointer speed for the camera. */
    var cameraSensitivity: Float
        get() = prefs.getFloat(KEY_CAMERA_SENSITIVITY, 1.0f)
        set(value) = prefs.edit()
            .putFloat(KEY_CAMERA_SENSITIVITY, value.coerceIn(MIN_CAMERA_SENSITIVITY, MAX_CAMERA_SENSITIVITY))
            .apply()

    /** For holding the phone the other way up, or aiming the camera forwards. */
    var cameraInvertY: Boolean
        get() = prefs.getBoolean(KEY_CAMERA_INVERT_Y, false)
        set(value) = prefs.edit().putBoolean(KEY_CAMERA_INVERT_Y, value).apply()

    /** Light the surface under the phone as soon as the app opens. */
    var cameraTorch: Boolean
        get() = prefs.getBoolean(KEY_CAMERA_TORCH, false)
        set(value) = prefs.edit().putBoolean(KEY_CAMERA_TORCH, value).apply()

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
        const val MIN_SCROLL = 0.2f
        const val MAX_SCROLL = 4.0f
        const val MIN_CAMERA_SENSITIVITY = 0.2f
        const val MAX_CAMERA_SENSITIVITY = 4.0f

        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
        private const val KEY_SCROLL_SPEED = "scroll_speed"
        private const val KEY_NATURAL_SCROLL = "natural_scroll"
        private const val KEY_CAMERA_SENSITIVITY = "camera_sensitivity"
        private const val KEY_CAMERA_INVERT_Y = "camera_invert_y"
        private const val KEY_CAMERA_TORCH = "camera_torch"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_RESEND = "resend_clicks"
        private const val KEY_AUTO_CONNECT = "auto_connect"
    }
}
