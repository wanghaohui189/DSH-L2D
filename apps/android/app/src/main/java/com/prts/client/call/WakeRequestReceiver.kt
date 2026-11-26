package com.prts.client.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.prts.client.ui.WakeCallActivity

/**
 * Exported wake hook for the companion wake app: a broadcast (not an activity start)
 * so the L2D app itself can raise the full-screen incoming-call notification, which is
 * the only reliable way to light up the screen over the keyguard on Android 13+.
 */
class WakeRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.getStringExtra(WakeCallActivity.EXTRA_WAKE_TOKEN) != WakeCallActivity.WAKE_TOKEN) return
        val endpoint = intent.getStringExtra(CallKeepAliveService.EXTRA_ENDPOINT).orEmpty()
        val said = intent.getStringExtra(WakeCallActivity.EXTRA_WAKE_TEXT).orEmpty()
        // While the phone is unlocked Android turns a full-screen intent into a heads-up
        // notification, so the call screen never comes up. Try the activity directly first;
        // the notification below stays as the lock-screen fallback.
        try {
            context.startActivity(WakeCallActivity.intent(context, said))
            Log.i("PRTS-Call", "wake: direct activity start attempted")
        } catch (error: Exception) {
            Log.w("PRTS-Call", "wake: direct activity start blocked", error)
        }
        CallKeepAliveService.requestWake(context, endpoint)
    }
}
