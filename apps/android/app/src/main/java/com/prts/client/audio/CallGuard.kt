package com.prts.client.audio

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock

/**
 * Watches the global audio mode and reports when a call takes over.
 *
 * Telephony switches the mode to MODE_IN_CALL and WeChat/QQ VoIP switch it to
 * MODE_IN_COMMUNICATION, so no READ_PHONE_STATE permission is needed. The callback fires once
 * when a call starts and once about 1.5 s after it ends; holding the microphone through a call
 * is exactly what makes the other party sound broken on some ROMs.
 */
class CallGuard(context: Context, private val onChanged: (Boolean) -> Unit) {
    private val audio = context.applicationContext
        .getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile
    var inCall: Boolean = false
        private set

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (thread != null) return
        running = true
        thread = Thread({ poll() }, "PRTS-CallGuard").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        running = false
        thread = null
    }

    private fun poll() {
        var active = false
        var normalSince = 0L
        while (running) {
            val mode = try {
                audio.mode
            } catch (_: Exception) {
                AudioManager.MODE_NORMAL
            }
            if (mode != AudioManager.MODE_NORMAL) {
                normalSince = 0L
                if (!active) {
                    active = true
                    inCall = true
                    onChanged(true)
                }
            } else if (active) {
                if (normalSince == 0L) normalSince = SystemClock.elapsedRealtime()
                if (SystemClock.elapsedRealtime() - normalSince > 1_500) {
                    active = false
                    inCall = false
                    onChanged(false)
                }
            }
            SystemClock.sleep(500)
        }
    }
}
