package com.prts.client.ui

import android.content.Context

/**
 * 2026-11-20（0.0.79）：来电的响铃 / 震动开关，放在「权限 → 主动联系」里。
 *
 * 只影响手机本地：服务端照常发 wake，手机按这里的设置决定要不要响、要不要震。
 * 通知通道特意做成静音——否则关掉响铃后，系统还会替我们响一声。
 */
object CallAlertSettings {
    private const val PREFS = "prts"
    private const val RING = "wake_ring_enabled"
    private const val VIBRATE = "wake_vibrate_enabled"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ringEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(RING, true)

    fun vibrateEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(VIBRATE, true)

    fun setRing(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(RING, on).apply()
    }

    fun setVibrate(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(VIBRATE, on).apply()
    }
}
