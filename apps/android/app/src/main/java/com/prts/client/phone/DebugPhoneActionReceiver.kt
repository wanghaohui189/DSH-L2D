package com.prts.client.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.prts.client.BuildConfig
import com.prts.client.audio.DiagLog

/**
 * 2026-11-18：debug 构建里的手机控制验收入口。正式包（BuildConfig.DEBUG=false）第一次判断就返回，
 * 不暴露任何东西。
 *
 * 存在的原因：`control.phone` 的指令平时只能从「通话中继 → CallActivity」那条路进来，验收
 * 「滚动查找 / 文字输入」就得先打通一次电话。这个接收器让验收可以一条 adb 命令复现：
 *
 * ```
 * adb shell am broadcast -a com.prts.client.DEBUG_PHONE_ACTION \
 *   --es action scrollfind --es text 电池
 * adb shell am broadcast -a com.prts.client.DEBUG_PHONE_ACTION \
 *   --es action settext --es text 搜索框 --es text2 你好世界
 * adb shell run-as com.prts.client cat files/phone-control.log
 * ```
 *
 * 结果只写 files/phone-control.log（`debug_action_result ... ok=true/false message=...`），不回广播，
 * 免得把无障碍读到的界面文字广播出去。
 */
class DebugPhoneActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val action = intent.getStringExtra("action").orEmpty().trim()
        if (action.isEmpty()) {
            DiagLog.write(LOG_FILE, "debug_action_ignored reason=no_action")
            return
        }
        val text = intent.getStringExtra("text").orEmpty()
        val text2 = intent.getStringExtra("text2").orEmpty()
        val app = intent.getStringExtra("app").orEmpty()
        val value = intent.getIntExtra("value", 0)
        val x = intent.getIntExtra("x", 0)
        val y = intent.getIntExtra("y", 0)
        val x2 = intent.getIntExtra("x2", 0)
        val y2 = intent.getIntExtra("y2", 0)
        val ms = intent.getIntExtra("ms", 0)
        DiagLog.write(LOG_FILE, "debug_action_start action=$action text=$text text2=$text2")
        val appContext = context.applicationContext
        // 无障碍查找/滚动要几秒，别占广播主线程。
        Thread({
            val result = PhoneActions.run(
                appContext, action, false, app, value, text, x, y, x2, y2, ms, text2)
            DiagLog.write(LOG_FILE, "debug_action_result action=$action ok=${result.ok} message=${result.message}")
        }, "PRTS-DebugPhoneAction").start()
    }

    companion object {
        private const val LOG_FILE = "phone-control.log"
    }
}
