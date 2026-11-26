package com.prts.client.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 2026-11-20（0.0.80）：开机 / App 更新后把「手机常驻」拉起来。
 *
 * 之前这个服务只在 App 启动时起一次；手机重启或 App 被清掉后，「她主动打来」就没耳朵了。
 * 这里只做一件事：把 PresenceService 重新挂上（它是前台服务，通知里有关掉常驻的按钮）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Log.i("PRTS-Presence", "boot receiver: $action, starting presence")
        PresenceService.start(context)
    }
}
