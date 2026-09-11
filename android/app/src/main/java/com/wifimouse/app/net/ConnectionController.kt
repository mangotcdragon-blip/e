package com.wifimouse.app.net

import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.wifimouse.app.R
import com.wifimouse.app.Settings

/**
 * Owns the connection for one screen: the socket, the status dot and text, and
 * the buttons currently held down.
 *
 * Both the trackpad and the camera mouse need exactly this, and a held button
 * that never gets released is the worst failure this app has, so the tracking
 * lives in one place rather than in each screen.
 */
class ConnectionController(
    private val activity: AppCompatActivity,
    private val settings: Settings,
    private val statusDot: View,
    private val statusText: TextView,
) {

    val client = MouseClient()

    /** Extra per-screen reaction to a state change; the shared parts are done here. */
    var onState: ((MouseClient.State) -> Unit)? = null

    private var connecting = false
    private val held = mutableSetOf<Char>()

    /** Connected, or trying to be. */
    val isLive: Boolean get() = client.isConnected || connecting

    /**
     * How many copies of a discrete event to send. UDP may drop one, and a lost
     * click is far more noticeable than a duplicate the server throws away.
     */
    private val copies: Int get() = if (settings.resendClicks) 2 else 1

    init {
        client.onState = ::render
        render(MouseClient.State.Idle)
    }

    // -- lifecycle --------------------------------------------------------- #

    fun resume() {
        if (settings.autoConnect && settings.isConfigured && !client.isConnected) connect()
    }

    fun pause() {
        releaseAll()
        client.stop()
    }

    fun destroy() {
        client.stop()
    }

    /** Returns false when there is no PC configured yet, so the caller can search. */
    fun connect(): Boolean {
        if (!settings.isConfigured) return false
        client.start(settings.host, settings.port, settings.token)
        return true
    }

    fun disconnect() {
        releaseAll()
        client.stop()
    }

    // -- input ------------------------------------------------------------- #

    fun move(dx: Float, dy: Float) = client.move(dx, dy)

    fun scroll(dx: Float, dy: Float) = client.scroll(dx, dy)

    fun click(button: Char) = client.click(button, copies)

    fun press(button: Char) {
        held.add(button)
        client.buttonDown(button, copies)
    }

    fun release(button: Char) {
        if (held.remove(button)) client.buttonUp(button, copies)
    }

    fun isHeld(button: Char) = button in held

    fun key(name: String) = client.key(name, copies)

    fun type(text: String) = client.type(text, copies)

    fun releaseAll() {
        for (button in held.toList()) release(button)
    }

    // -- status ------------------------------------------------------------ #

    private fun render(state: MouseClient.State) {
        connecting = state is MouseClient.State.Connecting

        val (text, color) = when (state) {
            is MouseClient.State.Idle -> (
                if (settings.isConfigured) activity.getString(R.string.status_offline)
                else activity.getString(R.string.status_no_host)
                ) to R.color.status_offline

            is MouseClient.State.Connecting ->
                activity.getString(R.string.status_connecting, state.host) to R.color.status_pending

            is MouseClient.State.Connected ->
                activity.getString(R.string.status_connected, state.serverName, state.rttMs) to
                    R.color.status_connected

            is MouseClient.State.Failed -> state.reason to R.color.status_error
        }

        statusText.text = text
        statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(activity, color)
        )

        if (settings.keepScreenOn && state is MouseClient.State.Connected) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        onState?.invoke(state)
    }
}
