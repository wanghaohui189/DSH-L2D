package com.prts.client.audio

import com.prts.client.net.EmotionIntent

/**
 * Per-reply emotion -> gentle playback tempo (speed/pitch multipliers on top of the user preset).
 *
 * The backend (relay) already ships `actions.emotion_intent` for the same reply that drives the
 * Live2D expressions, so the voice can follow without any protocol change. Levels intentionally
 * mirror the QQ side (whale2 `VOICE_MOOD_DELTAS`: +/-3%..+/-10% rate, +/-6..+/-18Hz pitch), where
 * +/-5% pitch is roughly one semitone. Playback only: the TTS text, sampling parameters and the
 * generated PCM are never altered, so this cannot change what she says.
 */
internal object SpeechProsody {
    data class Tempo(val speed: Float, val pitch: Float)

    val NEUTRAL = Tempo(1f, 1f)

    private val TABLE = mapOf(
        "happy" to Tempo(1.06f, 1.05f),
        "sad" to Tempo(0.92f, 0.95f),
        "angry" to Tempo(1.05f, 1.03f),
        "surprised" to Tempo(1.08f, 1.07f),
        "shy" to Tempo(0.96f, 1.03f),
        "thinking" to Tempo(0.94f, 1.00f),
    )

    /** Confidence floor so a flat "happy, 0.1" reply does not sound acted out. */
    private const val FLOOR = 0.35f

    fun of(intent: EmotionIntent?): Tempo {
        val name = intent?.emotion?.trim()?.lowercase()?.removePrefix("mobile:")
            ?.takeIf { it.isNotEmpty() } ?: return NEUTRAL
        val base = TABLE[name] ?: return NEUTRAL
        val strength = intent.intensity?.takeIf { it.isFinite() }?.toFloat()?.coerceIn(0f, 1f) ?: 0.6f
        val k = FLOOR + (1f - FLOOR) * strength
        return Tempo(1f + (base.speed - 1f) * k, 1f + (base.pitch - 1f) * k)
    }
}
