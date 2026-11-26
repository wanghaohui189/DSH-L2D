package com.prts.client.audio

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.prts.client.net.InAudio
import com.prts.client.audio.audio8.OfflineAudio8TtsEngine
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.roundToLong

/** Sequential WAV playback; queue state and callbacks belong to the main thread. */
class SpeechPlayer(
    private val onStart: (InAudio) -> Unit,
    private val onAudioStarted: (InAudio) -> Unit,
    private val onMouth: (Float) -> Unit,
    private val onIdle: () -> Unit,
    private val onError: (String) -> Unit,
    private val onAudioEnded: (InAudio, Boolean) -> Unit = { _, _ -> },
    private val onPerformanceAudio: (Boolean, Long) -> Unit = { _, _ -> },
    private val onPlaybackProgress: (InAudio, Long, Long, Boolean) -> Unit = { _, _, _, _ -> },
    private val playbackSpeed: () -> Float = { SpeechTempo.SPEED },
) {
    private val handler = Handler(Looper.getMainLooper())
    private data class QueuedClip(val message: InAudio, val local: LocalSpeechAudio? = null)
    private val queue = ArrayDeque<QueuedClip>()
    private var player: MediaPlayer? = null
    private var current: QueuedClip? = null
    private var audioStarted = false
    /** Sequence number for `files/playback.log`; lets a caption stop be traced to one clip. */
    private var currentSeq = 0
    private var currentStartedAt = 0L
    @Volatile private var closed = false
    private val streaming = PcmSpeechPlayer(onStart, onAudioStarted, onMouth, onPlaybackProgress,
        onAudioEnded, onIdle, onError, onPerformanceAudio, playbackSpeed = playbackSpeed)
    val isBusy: Boolean get() = current != null || queue.isNotEmpty() || streaming.isBusy
    private val mouthTick = object : Runnable {
        override fun run() {
            val active = player ?: return
            val clip = current?.message ?: return
            val position = try { active.currentPosition } catch (_: Exception) { return }
            onPlaybackProgress(clip, position.toLong(), runCatching { active.duration.toLong() }.getOrDefault(0L), true)
            val index = position / clip.slice_length.coerceAtLeast(1)
            onMouth(clip.volumes.getOrNull(index)?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f)
            // Visual mouth updates match the renderer's active 20 Hz ceiling.
            // PCM playback and its sample rate are unaffected.
            handler.postDelayed(this, 50)
        }
    }

    fun enqueue(clip: InAudio) {
        if (closed) return
        DiagLog.write("playback.log", "clip_queue source=remote queued=${queue.size + 1}")
        queue.addLast(QueuedClip(clip))
        if (current == null) next()
    }

    fun enqueueLocal(clip: LocalSpeechAudio) {
        if (closed || clip.isClosed) { clip.close(); return }
        DiagLog.write("playback.log", "clip_queue source=local queued=${queue.size + 1}")
        queue.addLast(QueuedClip(clip.message, clip))
        if (current == null) next()
    }

    fun enqueuePcm(message: InAudio, chunk: OfflineAudio8TtsEngine.PcmChunk): Boolean =
        !closed && streaming.accept(message, chunk)

    fun completeStreaming(message: InAudio, result: Result<OfflineAudio8TtsEngine.Output>) {
        DiagLog.write("playback.log", "pcm_complete ok=${result.isSuccess} reason=${result.exceptionOrNull()?.javaClass?.simpleName ?: "none"}")
        streaming.finish(message, result.isSuccess)
        result.getOrNull()?.close()
    }

    private fun next() {
        if (closed) return
        current = queue.pollFirst()
        val queued = current ?: run { onMouth(0f); onIdle(); return }
        val clip = queued.message
        currentSeq += 1
        val seq = currentSeq
        currentStartedAt = 0L
        DiagLog.write("playback.log", "clip_start seq=$seq source=${if (queued.local == null) "remote" else "local"} queued_left=${queue.size}")
        try {
            onStart(clip)
            val encoded = clip.audio
            if (queued.local == null && encoded.isNullOrBlank()) {
                current = null
                next()
                return
            }
            val local = queued.local
            val bytes = if (local == null) {
                Base64.decode(requireNotNull(encoded), Base64.DEFAULT).also {
                    require(it.size >= 12 && String(it, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                        String(it, 8, 4, Charsets.US_ASCII) == "WAVE") { "not_wav" }
                }
            } else null
            val media = MediaPlayer()
            player = media
            media.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            if (local != null) {
                require(!local.isClosed && local.wav.isFile && local.wav.length() >= 44)
                media.setDataSource(local.wav.absolutePath)
            } else media.setDataSource(ByteArraySource(requireNotNull(bytes)))
            media.setOnPreparedListener {
                if (closed || player !== media) return@setOnPreparedListener
                try {
                    // Emotion of this very reply (same intent that drives the expressions) -> tempo.
                    val intent = clip.actions?.emotion_intent
                    val prosody = SpeechProsody.of(intent)
                    val preset = playbackSpeed()
                    val requestedSpeed = SpeechTempo.clampSpeed(preset * prosody.speed)
                    val requestedPitch = SpeechTempo.PITCH * prosody.pitch
                    media.start()
                    // Keep the original start boundary: nonzero PlaybackParams can itself start a prepared player.
                    val actualSpeed = applySpeechTempo(media, requestedSpeed, requestedPitch)
                    Log.i("PRTS-SpeechTempo", "wav_tempo emotion=${intent?.emotion ?: "none"} " +
                        "intensity=${intent?.intensity} preset=$preset requested_speed=$requestedSpeed " +
                        "actual_speed=$actualSpeed requested_pitch=$requestedPitch")
                    audioStarted = true
                    currentStartedAt = SystemClock.elapsedRealtime()
                    val duration = runCatching { media.duration.toLong().coerceAtLeast(0L) }.getOrDefault(0L)
                    val wallDuration = (duration.toDouble() / actualSpeed.toDouble()).roundToLong().coerceAtLeast(0L)
                    DiagLog.write("playback.log", "clip_playing seq=$seq dur_ms=$duration speed=$actualSpeed")
                    runCatching { onPerformanceAudio(true, wallDuration) }
                    onAudioStarted(clip)
                    handler.post(mouthTick)
                } catch (error: Exception) {
                    failClip("这段语音没播出来，文字仍然保留", "start_${error.javaClass.simpleName}")
                }
            }
            media.setOnCompletionListener { if (player === media) finishClip(completed = true, reason = "ended") }
            media.setOnErrorListener { _, what, extra ->
                if (player === media) failClip("这段语音没播出来，文字仍然保留", "player_error_${what}_$extra")
                true
            }
            media.prepareAsync()
        } catch (error: Exception) {
            failClip("这段语音格式不对，文字仍然保留", "bad_clip_${error.javaClass.simpleName}")
        }
    }

    private fun applySpeechTempo(media: MediaPlayer, requestedSpeed: Float, requestedPitch: Float): Float {
        fun applyAndRead(speed: Float, pitch: Float): Float {
            media.playbackParams = PlaybackParams().allowDefaults()
                .setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_FAIL)
                .setSpeed(speed).setPitch(pitch)
            val actual = media.playbackParams
            check(actual.speed.isFinite() && actual.speed > 0f && actual.pitch.isFinite() &&
                abs(actual.speed - speed) <= .01f && abs(actual.pitch - pitch) <= .01f) { "speech_tempo_not_applied" }
            Log.i("PRTS-SpeechTempo", "file_playback_tempo requested_speed=$requestedSpeed actual_speed=${actual.speed} " +
                "requested_pitch=$requestedPitch actual_pitch=${actual.pitch}")
            return actual.speed
        }
        return try {
            check(requestedSpeed.isFinite() && requestedSpeed > 0f) { "speech_tempo_invalid" }
            applyAndRead(requestedSpeed, requestedPitch)
        } catch (error: Exception) {
            // Never request the platform's mute fallback or alter the source sample rate to slow speech.
            // Pitch is the softer knob: keep it if the device accepts it, else play plainly.
            val normalSpeed = try { applyAndRead(1f, requestedPitch) }
            catch (_: Exception) { applyAndRead(1f, 1f) }
            Log.w("PRTS-SpeechTempo", "file_playback_tempo requested_speed=$requestedSpeed fallback=normal_speed actual_speed=$normalSpeed cause=${error.javaClass.simpleName}")
            runCatching { onError("这段语音改用正常速度播放") }
            normalSpeed
        }
    }

    private fun failClip(message: String, reason: String = "unknown") {
        DiagLog.write("playback.log", "clip_fail seq=$currentSeq reason=$reason started=$audioStarted")
        runCatching { onError(message) }
        finishClip(completed = false, reason = "fail_$reason")
    }

    private fun finishClip(completed: Boolean = true, reason: String = "ended") {
        val previous = current
        val wasStarted = audioStarted
        val playedMs = if (currentStartedAt > 0L) SystemClock.elapsedRealtime() - currentStartedAt else 0L
        DiagLog.write("playback.log", "clip_end seq=$currentSeq completed=$completed reason=$reason started=$wasStarted played_ms=$playedMs")
        audioStarted = false
        handler.removeCallbacks(mouthTick)
        runCatching { player?.release() }
        player = null
        current = null
        previous?.local?.close()
        onMouth(0f)
        if (wasStarted) runCatching { onPerformanceAudio(false, 0L) }
        if (wasStarted && previous != null) runCatching { onAudioEnded(previous.message, completed) }
        next()
    }

    fun cancel() {
        DiagLog.write("playback.log", "clip_cancel seq=$currentSeq started=$audioStarted queued=${queue.size}")
        streaming.cancel()
        val previous = current
        val wasStarted = audioStarted
        audioStarted = false
        handler.removeCallbacksAndMessages(null)
        queue.forEach { it.local?.close() }
        queue.clear()
        runCatching { player?.release() }
        player = null
        current?.local?.close()
        current = null
        onMouth(0f)
        if (wasStarted) runCatching { onPerformanceAudio(false, 0L) }
        if (wasStarted && previous != null) runCatching { onAudioEnded(previous.message, false) }
    }

    fun close() { closed = true; cancel(); streaming.close() }

    private class ByteArraySource(private val bytes: ByteArray) : MediaDataSource() {
        override fun getSize() = bytes.size.toLong()
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position < 0 || position >= bytes.size) return -1
            val count = size.coerceAtMost(bytes.size - position.toInt())
            bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            return count
        }
        override fun close() = Unit
    }
}
