package com.prts.client.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Short local voice for character touch. The clips are pre-rendered with the same voice profile as
 * the call TTS, so a pet never waits for a network round trip and never consumes model tokens.
 *
 * 2026-11-16 (0.0.50) rework, after the owner reported "poking her makes no sound":
 *  - the old hard gap was 4 s, so every poke after the first one in a row was silently dropped;
 *    a 1 s rhythm of pokes therefore produced at most one grunt, which feels like no sound at all.
 *  - the old `blocked` flag swallowed the clip whenever her TTS was busy; on the call screen that is
 *    very often true, so even the first poke could be muted. A short interjection is now *ducked*
 *    (played quietly over her sentence) instead of being dropped.
 *  - playback went only to logcat, which this ROM disables. Every start / skip / failure now goes to
 *    `files/touch_debug.log` through [trace], so the ground truth survives on the device.
 */
internal class TouchVoicePlayer(
    private val context: Context,
    private val trace: (String) -> Unit = {},
) : AutoCloseable {
    private val cacheDir = File(context.filesDir, "touch-voices")
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var player: MediaPlayer? = null
    private var lastClip: String? = null
    private var lastPlayedAt = -60_000L

    /**
     * @param clip asset base name under `touch-voices/`; blank means this reaction has no voice.
     * @param duck true while her own speech is playing: the grunt is mixed in at [DUCK_VOLUME]
     *   instead of being skipped, because a silent tap reads as a broken touch screen.
     */
    fun play(clip: String, region: String, kind: String, duck: Boolean,
             onStarted: ((durationMs: Int) -> Unit)? = null) {
        if (clip.isBlank()) {
            trace("voice skip reason=no_clip region=$region kind=$kind")
            return
        }
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastPlayedAt
        // Repeating the very same clip is the machine-gun case, so it gets the longer gap.
        val gap = if (clip == lastClip) REPEAT_GAP_MS else MIN_GAP_MS
        if (elapsed < gap) {
            trace("voice skip reason=gap clip=$clip region=$region kind=$kind dt=$elapsed gap=$gap")
            return
        }
        lastPlayedAt = now
        lastClip = clip
        val volume = if (duck) DUCK_VOLUME else 1f
        val startedAt = now
        val systemVolume = runCatching {
            audioManager?.let { "${it.getStreamVolume(AudioManager.STREAM_MUSIC)}/" +
                "${it.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}" }
        }.getOrNull() ?: "?"
        runCatching {
            val file = cached(clip)
            player?.release()
            player = null
            val media = MediaPlayer()
            // Register before any throwing call: a failing setDataSource must not orphan the player.
            player = media
            media.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            media.setVolume(volume, volume)
            media.setDataSource(file.absolutePath)
            media.setOnPreparedListener { prepared ->
                val prepMs = SystemClock.elapsedRealtime() - startedAt
                // Explicit start on the prepared callback: prepareAsync keeps the UI thread free,
                // and a failed start is logged instead of vanishing into a caught exception.
                runCatching { prepared.start() }
                    .onSuccess {
                        trace("voice play clip=$clip region=$region kind=$kind vol=$volume " +
                            "duck=$duck prep_ms=$prepMs dur_ms=${prepared.duration} " +
                            "sys_vol=$systemVolume")
                        Log.i("PRTS-Touch", "voice play clip=$clip vol=$volume prepMs=$prepMs")
                        runCatching { onStarted?.invoke(prepared.duration) }
                    }
                    .onFailure { trace("voice error clip=$clip start=${it.javaClass.simpleName} " +
                        "msg=${it.message}") }
            }
            media.setOnCompletionListener { done ->
                trace("voice stop clip=$clip ms=${SystemClock.elapsedRealtime() - startedAt}")
                done.release()
                if (player === done) player = null
            }
            media.setOnErrorListener { failed, what, extra ->
                trace("voice error clip=$clip what=$what extra=$extra")
                failed.release()
                if (player === failed) player = null
                true
            }
            media.prepareAsync()
        }.onFailure {
            runCatching { player?.release() }
            player = null
            trace("voice error clip=$clip prepare=${it.javaClass.simpleName} msg=${it.message}")
            Log.w("PRTS-Touch", "voice clip unavailable: $clip", it)
        }
    }

    private fun cached(clip: String): File {
        cacheDir.mkdirs()
        val target = File(cacheDir, "$clip.mp3")
        if (!target.isFile || target.length() == 0L) {
            context.assets.open("touch-voices/$clip.mp3").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return target
    }

    override fun close() {
        player?.release()
        player = null
    }

    private companion object {
        /** Owner asked for 500-800 ms so a normal poke rhythm still speaks. */
        const val MIN_GAP_MS = 700L
        /** Same clip twice in a row: a little longer, so triple-poking is not a burst of grunts. */
        const val REPEAT_GAP_MS = 900L
        /** While her TTS is speaking the grunt is mixed in quietly rather than dropped. */
        const val DUCK_VOLUME = 0.35f
    }
}
