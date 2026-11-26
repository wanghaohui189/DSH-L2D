package com.prts.client.phone

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.provider.Settings
import android.view.KeyEvent
import com.prts.client.call.PermissionAccessibilityService

/**
 * 手机本地动作。口令由中继识别后以 phone-action 帧发来，执行完把一句话回执发回中继，
 * 中继再用语音念出来（和派活给电脑共用一条通道）。
 */
internal object PhoneActions {
    data class Result(val ok: Boolean, val message: String)

    fun run(context: Context, action: String, on: Boolean, app: String, value: Int,
            text: String = "", x: Int = 0, y: Int = 0, x2: Int = 0, y2: Int = 0, ms: Int = 0,
            text2: String = ""): Result = try {
        when (action) {
            "torch" -> torch(context, on)
            "volume" -> volume(context, value)
            "dnd" -> dnd(context, on)
            "media" -> media(context, value)
            "open" -> openApp(context, app)
            // control.phone：无障碍指令。兜底逻辑都在 PermissionAccessibilityService 里。
            "click" -> control { it.clickText(text) }
            // 2026-11-18：滚动查找（长列表/折叠线以下）+ 往输入框写字。
            "scrollfind" -> control { it.scrollFindText(text) }
            // 2026-11-18：整屏翻页（不需要坐标/目标文字），「往下滑 / 往上滑」直执走这两条。
            "scrolldown" -> control { it.scrollPage(down = true) }
            "scrollup" -> control { it.scrollPage(down = false) }
            "settext" -> control { it.setText(text, text2) }
            "tap" -> control { if (it.tap(x, y)) true to "点了($x,$y)" else false to "点按被系统拦了" }
            "swipe" -> control {
                if (it.swipe(x, y, x2, y2, ms.toLong())) true to "滑了一下" else false to "滑动被系统拦了"
            }
            "back" -> control { if (it.back()) true to "返回了" else false to "返回没成" }
            "home" -> control { if (it.home()) true to "回到桌面了" else false to "回桌面没成" }
            "nodes" -> control { it.visibleNodes() }
            "wifi", "bluetooth" -> Result(false, "WiFi/蓝牙的语音控制已经关掉了")
            else -> Result(false, "不支持的操作")
        }
    } catch (error: Exception) {
        Result(false, "没做成：${error.javaClass.simpleName}")
    }

    private fun control(block: (PermissionAccessibilityService) -> Pair<Boolean, String>): Result {
        val service = PermissionAccessibilityService.instance
            ?: return Result(false, "无障碍服务没开：权限页 → 手机控制 → 打开系统无障碍")
        return try {
            val (ok, message) = block(service)
            Result(ok, message)
        } catch (error: Exception) {
            Result(false, "执行出错：${error.javaClass.simpleName}")
        }
    }

    private fun torch(context: Context, on: Boolean): Result {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return Result(false, "这台手机没有闪光灯")
        return try {
            manager.setTorchMode(cameraId, on)
            Result(true, if (on) "手电筒打开了" else "手电筒关掉了")
        } catch (error: Exception) {
            Result(false, "手电筒打不开（相机可能被占用）")
        }
    }

    private fun volume(context: Context, direction: Int): Result {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (direction) {
            1 -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            -1 -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            0 -> audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
            2 -> audio.setStreamVolume(AudioManager.STREAM_MUSIC,
                audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), AudioManager.FLAG_SHOW_UI)
        }
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return Result(true, "音量现在是 $current（最大 $max）")
    }

    private fun dnd(context: Context, on: Boolean): Result {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!manager.isNotificationPolicyAccessGranted) {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return Result(false, "需要先允许勿扰权限，已经帮你打开设置")
        }
        manager.setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY
            else NotificationManager.INTERRUPTION_FILTER_ALL)
        return Result(true, if (on) "勿扰打开了" else "勿扰关掉了")
    }

    private fun media(context: Context, value: Int): Result {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val key = when (value) {
            1 -> KeyEvent.KEYCODE_MEDIA_NEXT
            2 -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        return Result(true, "好")
    }

    private fun openApp(context: Context, name: String): Result {
        if (name.isBlank()) return Result(false, "要打开哪个 App？")
        // 2026-11-18 真机：vivo 的「设置」是 com.android.settings，它没有 LAUNCHER activity，
        // getLaunchIntentForPackage 直接返回 null —— 真实通话日志里就是
        // `手机控制 open '设置' ok=False 「设置」打不开`。系统设置改走官方入口，确定能进。
        if (name.contains("设置")) {
            return try {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                // 2026-11-19：不抛异常 != 真打开。读前台包名核对后再回 ok。
                val seen = verifyForeground { pkg -> pkg.contains("settings", ignoreCase = true) }
                when {
                    seen == null -> Result(true, "打开了设置（无障碍没开，没能核对前台）")
                    seen.contains("settings", ignoreCase = true) -> Result(true, "打开了设置")
                    else -> Result(false, "点了设置，但前台还是 $seen")
                }
            } catch (error: Exception) {
                Result(false, "设置打不开：${error.javaClass.simpleName}")
            }
        }
        val pm = context.packageManager
        val info = runCatching {
            pm.getInstalledApplications(0).firstOrNull {
                pm.getApplicationLabel(it).toString().contains(name, ignoreCase = true)
            }
        }.getOrNull() ?: return Result(false, "没找到叫「$name」的 App")
        val intent = pm.getLaunchIntentForPackage(info.packageName)
        if (intent == null) {
            // 找到包但没有桌面入口（系统 App 常见）：退到应用详情页，至少能进去接着操作。
            return try {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.parse("package:" + info.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Result(false, "「$name」没有桌面入口，只打开了它的应用详情页")
            } catch (error: Exception) {
                Result(false, "「$name」打不开：${error.javaClass.simpleName}")
            }
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        // 2026-11-19（0.0.77/153）：startActivity 不抛异常只说明系统受理了，
        // 不等于目标真到了前台（锁屏/后台启动限制/目标进程崩都会被静默吞掉）。
        val label = pm.getApplicationLabel(info).toString()
        val target = info.packageName
        val seen = verifyForeground { pkg -> pkg == target || pkg.startsWith(target + ".") }
        return when {
            seen == null -> Result(true, "打开了$label（无障碍没开，没能核对前台）")
            seen == target || seen.startsWith(target + ".") -> Result(true, "打开了$label")
            else -> Result(false, "点了$label，但前台还是$seen")
        }
    }

    /**
     * 2026-11-19：读前台包名核对「真的打开了」。
     * - 后台线程：最多等 ~1s（startActivity 到前台刷新要几百毫秒）；
     * - 主线程：只读一次（activeRoot 要在主线程跑，主线程 sleep 会把自己憋死）。
     * 返回 null = 读不到（无障碍没开/权限被撤），按旧行为当作成功但注明未核对。
     */
    private fun verifyForeground(match: (String) -> Boolean): String? {
        val onMain = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        var tries = if (onMain) 1 else 10
        var last: String? = null
        while (tries-- > 0) {
            last = try {
                PermissionAccessibilityService.activePackageName()
            } catch (_: Exception) {
                null
            }
            if (last != null && match(last)) return last
            if (tries > 0) {
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        return last
    }
}
