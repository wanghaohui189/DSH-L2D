package com.prts.client.audio

import android.content.Context

/** Playback only. The TTS input, sampling parameters and generated PCM remain unchanged. */
internal object SpeechTempo {
    const val SPEED = 0.65f
    const val FAST_SPEED = 1.0f
    const val PITCH = 1.0f
    const val ELEGANT = "elegant"
    const val FAST = "fast"
    private const val KEY = "speech_playback_mode"

    /** 语速固定 1×（主人 2026-11-13 定：语速设置已从设置页删掉，默认与缺省都走快速）。 */
    fun preset(context: Context): String =
        if (context.getSharedPreferences("prts", Context.MODE_PRIVATE).getString(KEY, FAST) == FAST) FAST else ELEGANT

    fun speed(context: Context): Float = if (preset(context) == FAST) FAST_SPEED else SPEED

    /** Emotion adds a few percent on top of the preset; beyond this range it stops sounding like her. */
    const val MIN_SPEED = 0.5f
    const val MAX_SPEED = 1.6f

    fun clampSpeed(value: Float): Float = if (!value.isFinite()) 1f else value.coerceIn(MIN_SPEED, MAX_SPEED)

    fun savePreset(context: Context, preset: String) {
        require(preset == ELEGANT || preset == FAST)
        context.getSharedPreferences("prts", Context.MODE_PRIVATE).edit().putString(KEY, preset).apply()
    }
}
