package com.prts.client.ui

import android.content.Context
import com.prts.client.audio.AutoListener
import org.json.JSONObject
import java.io.File

/**
 * Tunables for hands-free listening. Everything is user-adjustable in the call sheet; a
 * `files/auto-listen.json` file overrides the stored values so the endpoints can be iterated
 * over adb without reinstalling:
 *
 *     adb shell "run-as com.prts.client sh -c 'cat > files/auto-listen.json'"
 *     {"gateMul":2.6,"silenceMs":900,"minSpeechMs":500}
 */
internal object AutoListenSettings {
    private const val PREFS = "prts"
    private const val ENABLED = "auto_listen"
    private const val SCREEN_OFF = "auto_listen_screen_off"
    private const val GATE_MUL = "auto_listen_gate_mul"
    private const val SILENCE = "auto_listen_silence_ms"
    private const val MIN_SPEECH = "auto_listen_min_speech_ms"
    private const val RESUME_DELAY = "auto_listen_resume_delay_ms"
    private const val BARGE_IN = "auto_listen_barge_in"
    /** 「她说话时也继续收音（不打断她）」：2026-11-24 主人拍板方案 A。 */
    private const val LISTEN_DURING_PLAYBACK = "auto_listen_during_playback"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(ENABLED, true)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(ENABLED, value).apply()
    }

    // Opt-in, not opt-out: a fresh install must never keep recording behind the keyguard, or it
    // fights the wake-word app for the microphone. Users who want screen-off listening turn it on
    // in the call sheet ("锁屏时继续收音").
    fun screenOff(context: Context): Boolean = prefs(context).getBoolean(SCREEN_OFF, false)

    fun setScreenOff(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(SCREEN_OFF, value).apply()
    }

    fun gateMul(context: Context): Float = prefs(context).getFloat(GATE_MUL, 2.2f)
    fun silenceMs(context: Context): Int = prefs(context).getInt(SILENCE, 600)
    fun minSpeechMs(context: Context): Int = prefs(context).getInt(MIN_SPEECH, 250)

    /**
     * 说话打断已于 2026-11-23 由主人拍板取消：不再实现，也不再读偏好或
     * `files/auto-listen.json` 里的旧开关——旧文件里写了 true 也要忽略。
     */
    fun bargeIn(context: Context): Boolean = false

    /**
     * 「她在说时也继续收音（不打断她）」（2026-11-24 主人拍板方案 A，默认开）。
     *
     * 和上面已取消的「说话打断」是两件事：这里**只收音、不发打断**——她说到一半主人插的话
     * 先进队列，等她说完再按顺序送去 ASR。原来她出声那几秒麦克风是直接关掉的（AudioRecord
     * 关闭），主人说的话连日志都没有（11-23 用户反馈「她说话的时候我说的话没进去」）。
     */
    fun listenDuringPlayback(context: Context): Boolean =
        // 2026-11-24 主人拍板：这个功能不要了。她说话时会把旁人、以及她自己的回声一起收进来；
        // 声纹只能保证「不回」，但 ASR 次数、中继 ambient 前缀污染、回声漏网都不划算。
        // 代码保留、默认 false = 0.0.97 行为；要临时试就 setListenDuringPlayback(true)。
        prefs(context).getBoolean(LISTEN_DURING_PLAYBACK, false)

    fun setListenDuringPlayback(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(LISTEN_DURING_PLAYBACK, value).apply()
    }

    fun save(context: Context, gateMul: Float, silenceMs: Int, minSpeechMs: Int) {
        prefs(context).edit()
            .putFloat(GATE_MUL, gateMul.coerceIn(1.1f, 6f))
            .putInt(SILENCE, silenceMs.coerceIn(400, 1_500))
            .putInt(MIN_SPEECH, minSpeechMs.coerceIn(120, 1_500))
            .apply()
    }

    fun config(context: Context): AutoListener.Config {
        val base = AutoListener.Config(
            gateMul = gateMul(context),
            silenceMs = silenceMs(context),
            minSpeechMs = minSpeechMs(context),
            resumeDelayMs = prefs(context).getInt(RESUME_DELAY, 200),
        )
        return try {
            val file = File(context.filesDir, "auto-listen.json")
            if (!file.isFile) base else {
                val json = JSONObject(file.readText())
                base.copy(
                    gateAbs = json.optDouble("gateAbs", base.gateAbs.toDouble()).toFloat(),
                    gateMul = json.optDouble("gateMul", base.gateMul.toDouble()).toFloat(),
                    silenceMs = json.optInt("silenceMs", base.silenceMs),
                    minSpeechMs = json.optInt("minSpeechMs", base.minSpeechMs),
                    maxUtteranceMs = json.optInt("maxUtteranceMs", base.maxUtteranceMs),
                    prerollMs = json.optInt("prerollMs", base.prerollMs),
                    resumeDelayMs = json.optInt("resumeDelayMs", base.resumeDelayMs),
                    bargeGateAbs = json.optDouble("bargeGateAbs", base.bargeGateAbs.toDouble()).toFloat(),
                    bargeGateMul = json.optDouble("bargeGateMul", base.bargeGateMul.toDouble()).toFloat(),
                    bargeSilenceMs = json.optInt("bargeSilenceMs", base.bargeSilenceMs),
                    bargeMinSpeechMs = json.optInt("bargeMinSpeechMs", base.bargeMinSpeechMs),
                )
            }
        } catch (_: Exception) {
            base
        }
    }
}
