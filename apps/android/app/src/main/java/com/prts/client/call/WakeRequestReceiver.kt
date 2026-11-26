package com.prts.client.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.prts.client.ui.WakeCallActivity

/**
 * Wake hook for the companion wake app: a broadcast (not an activity start) so the L2D app
 * itself can raise the full-screen incoming-call notification, which is the only reliable way
 * to light up the screen over the keyguard on Android 13+.
 *
 * 2026-11-26：信任边界从「源码里的常量 token」换成「签名」。receiver 在 Manifest 里声明
 * `android:permission="com.prts.client.permission.WAKE_REQUEST"`（protectionLevel=signature），
 * 只有与本人同一把钥匙签名的 App 能投递；token 已删除——它随源码公开后不再是口令。
 */
class WakeRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        // 权限已由系统在投递前校验（signature 级）；这里不再比对任何可公开的常量。
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
