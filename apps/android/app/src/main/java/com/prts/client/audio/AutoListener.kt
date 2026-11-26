package com.prts.client.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Continuous capture with a local energy VAD, so a call can be talked to without pressing the
 * microphone button.
 *
 * The caller owns the reply lifecycle: [setMuted] closes the recorder while an answer is being
 * generated or played, so the speaker never feeds back into the endpoint detector. Endpointing
 * is deliberately structural (40 ms chunks, [Config.silenceMs] of quiet ends a sentence) rather
 * than model based: this keeps the cost near zero and leaves no new native dependency.
 *
 * All callbacks arrive on the listener thread; the UI must post them to the main looper.
 */
class AutoListener(
    /**
     * Second argument: whether this capture *started* while [setBarge] monitoring was active.
     * A sentence that began as a normal listen and merely crossed into her playback must not be
     * treated as an interruption (`CallActivity.onAutoUtterance`).
     */
    private val onUtterance: (FloatArray, Boolean) -> Unit,
    private val onState: (String) -> Unit,
    /** Diagnostic sink (barge tuning); written off the audio thread by the caller. */
    private val onDebug: (String) -> Unit = {},
    /**
     * Barge 模式下刚确认「有人在盖着她说话」就立刻回调，不等端点。
     * 端点要等 200ms 静音才成立，实测旧逻辑打断延迟 3.3s（2026-11-16 12:30 日志），
     * 而停播只需要「她说话时有明显人声」这一条证据。参数=已累计的过门限语音时长。
     */
    private val onBargeStart: (Int) -> Unit = {},
    /**
     * Silero VAD（2026-11-23 0.0.97）：判「人声结束」替代能量门。null = 模型加载失败，
     * 退回能量门（降级，不崩）。
     */
    private val vad: SileroVad? = null,
) {
    /** 最后一次算出的 Silero 人声概率（0..1）；负值 = 还没凑满一个 32ms 窗口。 */
    @Volatile private var vadProb = -1f

    data class Config(
        /** Absolute RMS floor, for rooms quieter than the adaptive floor can track. */
        val gateAbs: Float = 0.010f,
        /** Multiplier over the tracked room noise. Higher = less sensitive. */
        val gateMul: Float = 3.5f,
        /** Quiet time that closes a sentence. 2026-11-23：800→650（不用 500）——
         *  500ms 会把「换气停顿」切成两段、半句送出去；650ms 是实测「说完」与「换气」的分界。 */
        val silenceMs: Int = 600,
        /** Shorter bursts are discarded as noise. 700→250：VAD 已经负责判人声，
         *  原来的 700ms 会让「嗯」「对」这种短句永远收不了尾（拖到 8s 上限）。 */
        val minSpeechMs: Int = 250,
        /** Safety cap only: 8s 只留作「一句到底没有静音」的保险丝，正常句不再走到这里。 */
        val maxUtteranceMs: Int = 8_000,
        /** Audio kept before the first voiced chunk, so the first syllable is not clipped. */
        val prerollMs: Int = 300,
        /** Quiet time the caller should wait after playback before unmuting.
         *  800→200：她的回声尾巴由 ECHO_GUARD 兜，没必要再多关 0.6s 的麦。 */
        val resumeDelayMs: Int = 200,
        /** Barge-in mode: while her voice plays the recorder stays open, but only speech clearly
         *  above the echo floor is accepted. This is the *minimum* gate; the real gate follows the
         *  measured echo (bargeFloor × 1.6, capped at 2× this value). */
        val bargeGateAbs: Float = 0.03f,
        val bargeGateMul: Float = 3.0f,
        /** Shorter endpointing so an interruption does not have to wait out a full sentence. */
        val bargeSilenceMs: Int = 200,
        val bargeMinSpeechMs: Int = 150,
    ) {
        fun sanitized(): Config = copy(
            gateAbs = gateAbs.coerceIn(0.0005f, 0.05f),
            gateMul = gateMul.coerceIn(1.1f, 6f),
            silenceMs = silenceMs.coerceIn(450, 1_500),
            minSpeechMs = minSpeechMs.coerceIn(120, 1_500),
            maxUtteranceMs = maxUtteranceMs.coerceIn(3_000, 25_000),
            prerollMs = prerollMs.coerceIn(0, 800),
            resumeDelayMs = resumeDelayMs.coerceIn(0, 1_500),
            bargeGateAbs = bargeGateAbs.coerceIn(0.005f, 0.15f),
            bargeGateMul = bargeGateMul.coerceIn(1.5f, 8f),
            bargeSilenceMs = bargeSilenceMs.coerceIn(250, 1_200),
            bargeMinSpeechMs = bargeMinSpeechMs.coerceIn(200, 1_200),
        )
    }

    @Volatile private var running = false
    @Volatile private var muted = true
    /** While [muted] and her voice plays, keep capturing but gate much higher (barge-in). */
    @Volatile private var barge = false
    @Volatile private var echoSafe = false
    @Volatile private var config = Config()
    private var thread: Thread? = null
    private var aec: AcousticEchoCanceler? = null

    @Volatile private var record: AudioRecord? = null
    private var noiseFloor = 0.002f
    /** Loudest echo chunk seen while barge-monitoring her own playback (drives the barge gate). */
    private var bargeFloor = 0.004f
    private var openedAt = 0L

    fun updateConfig(value: Config) {
        config = value.sanitized()
    }

    @Synchronized
    fun start() {
        if (thread != null) return
        running = true
        muted = false
        thread = Thread({ loop() }, "PRTS-AutoListen").also {
            it.isDaemon = true
            it.start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        muted = true
        barge = false
        closeRecord()
        val worker = thread
        thread = null
        try { worker?.join(400) } catch (_: InterruptedException) {}
        closeRecord()
        onState("")
    }

    /** Muting takes effect on the audio thread within one 40 ms chunk; never block the UI here. */
    fun setMuted(value: Boolean) {
        muted = value
    }

    /**
     * Barge-in: while [setMuted] is true keep the recorder open but only accept speech that is
     * clearly louder than the speaker echo. Callers enable this only while her voice plays.
     */
    fun setBarge(value: Boolean) {
        if (value != barge) onDebug("setBarge=$value")
        barge = value
    }

    /** VOICE_COMMUNICATION routes capture through the phone's voice path, where the platform
     *  echo canceller has a proper reference. Needed for barge-in; MIC alone leaves ~0.04 echo. */
    fun setEchoSafeSource(value: Boolean) {
        echoSafe = value
    }

    val isRunning: Boolean get() = thread != null

    // ------------------------------------------------------------------ audio loop

    private fun loop() {
        val chunk = ShortArray(CHUNK)
        val parts = ArrayList<ShortArray>()
        val preRoll = ArrayDeque<ShortArray>()

        var inSpeech = false
        // Which mode accepted this utterance: barge monitoring (her voice playing) or a plain
        // listen. Reported with the samples so the caller can decide about interrupting playback.
        var utteranceBarge = false
        // A plain listen that ran into her playback: the capture now carries her own voice, so
        // it is dropped at the endpoint instead of being sent (and it must never interrupt).
        var crossedIntoBarge = false
        /** Already told the caller to stop playback for this barge/plain capture. */
        var bargeNotified = false
        // Loudest / quietest chunk of the utterance: stationary noise (fan, hum) has a flat
        // envelope, speech does not. Drops the 8 s captures that ambient noise produced.
        var loudMax = 0f
        var loudMin = Float.MAX_VALUE
        var speechMs = 0
        var silenceMs = 0
        var totalMs = 0
        var zeroChunks = 0
        var prevCapture = false
        var prevBargeMode = false
        var bargePeak = 0f
        var bargeGate = 0f
        var peakRms = 0f
        /** 收音看门狗：最近一次 recordingState 检查的时间。 */
        var lastWatchAt = 0L
        /** 刚做过 capture-reset：重开成功后把界面的「麦克风重启中…」清掉。 */
        var resetPending = false

        while (running) {
            val capture = !muted || barge
            if (!capture) {
                closeRecord()
                parts.clear()
                preRoll.clear()
                inSpeech = false
                speechMs = 0
                silenceMs = 0
                totalMs = 0
                zeroChunks = 0
                crossedIntoBarge = false
                bargeNotified = false
                prevCapture = false
                vad?.reset()
                SystemClock.sleep(80)
                continue
            }
            if (!prevCapture) {
                // Entering capture (normal listen or barge): start from a clean state.
                parts.clear()
                preRoll.clear()
                inSpeech = false
                speechMs = 0
                silenceMs = 0
                totalMs = 0
                crossedIntoBarge = false
                bargeNotified = false
                prevCapture = true
                vad?.reset()
            }

            var rec = record
            if (rec == null) {
                rec = openRecord()
                if (rec == null) {
                    SystemClock.sleep(300)
                    continue
                }
                record = rec
                openedAt = SystemClock.elapsedRealtime()
                if (resetPending) {
                    resetPending = false
                    onState("")
                }
            }

            val n = try {
                rec.read(chunk, 0, chunk.size)
            } catch (_: Exception) {
                -1
            }
            if (n <= 0) {
                onDebug("capture-reset reason=read n=%d ms=%d".format(n, totalMs))
                closeRecord()
                SystemClock.sleep(100)
                continue
            }
            // The first ~120 ms after opening carries the device's start-up click/zeros.
            if (SystemClock.elapsedRealtime() - openedAt < 120) continue

            var any = false
            for (i in 0 until n) {
                if (chunk[i].toInt() != 0) { any = true; break }
            }
            zeroChunks = if (any) 0 else zeroChunks + 1
            if (zeroChunks > ZERO_CHUNKS_LIMIT) {
                // AudioRecord can die after a long sleep; reopen instead of listening to silence.
                onDebug("capture-reset reason=zeros ms=%d".format(totalMs))
                closeRecord()
                zeroChunks = 0
                continue
            }
            // 收音看门狗（2026-11-23 0.0.97）：AudioRecord 会在长时间通话后悄悄死掉
            // （15:39:15 之后 70 分钟没有任何 rec start，界面还显示「等你说」）。
            // 每 2 s 查一次 recordingState，断了就关掉重开，并留一行 capture-reset。
            val watchNow = SystemClock.elapsedRealtime()
            if (watchNow - lastWatchAt >= 2_000) {
                lastWatchAt = watchNow
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    onDebug("capture-reset reason=state state=%d ms=%d"
                        .format(rec.recordingState, totalMs))
                    onState("麦克风重启中…")
                    resetPending = true
                    closeRecord()
                    SystemClock.sleep(60)
                    continue
                }
            }

            var sum = 0.0
            for (i in 0 until n) {
                val v = chunk[i] / 32768.0
                sum += v * v
            }
            val rms = sqrt(sum / n).toFloat()

            val bargeMode = muted && barge
            // 端点判定（2026-11-23 0.0.97）：Silero VAD 判「像不像人声」，替代能量门。
            // 这台手机房间里底噪 0.018-0.02 rms、主人远场人声 0.01-0.03，两者重叠，
            // 能量门要么把底噪当人声（永远攒不满静音 → 拖到 8s），要么把远场人声拦掉。
            // barge 模式仍走能量门：那时要跟「她自己的回声」比，回声本来就是人声，VAD 分不出来。
            if (!bargeMode && vad != null) {
                val p = vad.push(chunk, n)
                if (p != null) vadProb = p
            }
            // 她一开始播（barge 打开）就立刻废掉在途的普通捕获：否则这句会一直吃到 8 秒上限，
            // 把主人喊的「停」整个吞掉（10:43 实测：utter-end dropped crossed_into_barge ms=8000）。
            if (bargeMode && !prevBargeMode && inSpeech && !utteranceBarge) {
                onDebug("capture-reset barge-entry ms=%d speech=%d".format(totalMs, speechMs))
                parts.clear()
                preRoll.clear()
                inSpeech = false
                speechMs = 0
                silenceMs = 0
                totalMs = 0
                crossedIntoBarge = false
                bargeNotified = false
            }
            // 普通监听跨进播放窗口：这句话里已经有她自己的声音，端点后整句丢弃，
            // 既不打断播放，也不把回声/她的台词当成用户输入发出去。
            if (inSpeech && bargeMode && !utteranceBarge) crossedIntoBarge = true
            if (!inSpeech && !bargeMode) {
                // Slow to rise, quick to fall: speech must not drag the floor above itself.
                // 2026-11-23：上升 0.001/帧 → 0.05/帧（约 0.8 s 追上环境噪声）。原来的 0.001
                // 要 40 s 才追得上底噪，于是房间里 0.018-0.02 的底噪一直高于 0.010 的门，
                // silenceMs 永远攒不满 —— 448 段里 422 段拖到 8 s 上限（VOICE-RECEIVE-2026-11-23.md）。
                noiseFloor = if (rms < noiseFloor) noiseFloor * 0.80f + rms * 0.20f
                             else noiseFloor * 0.95f + rms * 0.05f
                noiseFloor = noiseFloor.coerceIn(0.0005f, 0.03f)
            }
            // 打断门限跟着她自己的回声走（错题本 11-13：固定 0.045 正好卡在回声峰值 0.0442 上，
            // 永远打不断；降到 0.02 又让回声自己触发）。门限 = 实测回声 ×1.6，下限 0.03；
            // 上限取 max(3 倍下限, 回声×1.3) —— 12:30 实测回声 0.0585 被 2 倍上限 0.06 压住，
            // 只差 1.02 倍就自触发（`speech-start rms=0.0630 gate=0.0600`）。任何音量下门限都不低于回声×1.3。
            if (bargeMode && !inSpeech) bargeFloor = max(bargeFloor * 0.995f, rms)
            // 2026-11-24 方案 B：VOICE_COMMUNICATION/AEC 在这台 vivo 上是死的（探针 0.0001），
            // 实测她的回声 0.11-0.26 比主人说话 0.09-0.18 还响，按回声比例设门限永远收不到主人。
            // 所以给她说话这段窗口一个绝对上限：门限最多 0.055，主人的声音能进来；她的回声也会
            // 进来，但会被中继 isEcho() 按「她刚说过的台词」丢掉——宁可多花几次 ASR，不能丢主人的话。
            val bargeUpper = max(config.bargeGateAbs * 3f, bargeFloor * 1.3f)
            val bargeGateNow = (bargeFloor * BARGE_ECHO_MARGIN)
                .coerceIn(config.bargeGateAbs, min(bargeUpper, BARGE_GATE_CAP))
            val gate = if (bargeMode) bargeGateNow
                       else max(config.gateAbs, noiseFloor * config.gateMul)
            // 这一段算不算「有人声」：有 VAD 就听 VAD（概率过 0.5），没有才退回音量门。
            val voiced = if (!bargeMode && vadProb >= 0f) vadProb >= VAD_SPEECH_PROB
                         else rms > gate
            if (bargeMode) {
                if (rms > bargePeak) bargePeak = rms
                bargeGate = gate
            } else if (prevBargeMode) {
                onDebug("barge-window peak=%.4f gate=%.4f echo=%.4f".format(bargePeak, bargeGate, bargeFloor))
                bargePeak = 0f
            }
            prevBargeMode = bargeMode

            if (!inSpeech) {
                preRoll.addLast(chunk.copyOf(n))
                while (preRoll.size > max(1, config.prerollMs / CHUNK_MS)) preRoll.removeFirst()
                if (voiced && rms > MIN_VOICE_RMS) {
                    loudMax = rms
                    loudMin = rms
                    peakRms = rms
                    inSpeech = true
                    parts.clear()
                    for (part in preRoll) parts.add(part)
                    preRoll.clear()
                    speechMs = CHUNK_MS
                    silenceMs = 0
                    totalMs = parts.size * CHUNK_MS
                    if (bargeMode) onDebug("speech-start rms=%.4f gate=%.4f".format(rms, gate))
                    else onDebug("speech-start-normal rms=%.4f gate=%.4f vad=%.3f"
                        .format(rms, gate, vadProb))
                    utteranceBarge = bargeMode
                    bargeNotified = false
                    onState("在听你说…")
                }
                continue
            }

            parts.add(chunk.copyOf(n))
            totalMs += CHUNK_MS
            if (rms > peakRms) peakRms = rms
            if (rms > loudMax) loudMax = rms
            if (rms < loudMin) loudMin = rms
            if (voiced) {
                speechMs += CHUNK_MS
                silenceMs = 0
            } else {
                silenceMs += CHUNK_MS
            }

            if (bargeMode && !bargeNotified && speechMs >= BARGE_INTERRUPT_MS) {
                bargeNotified = true
                onDebug("barge-interrupt-now speech=%d gate=%.4f".format(speechMs, gate))
                onBargeStart(speechMs)
            }

            val endSilence = if (bargeMode) config.bargeSilenceMs else config.silenceMs
            val endMin = if (bargeMode) config.bargeMinSpeechMs else config.minSpeechMs
            // 2026-11-23：原条件要求 speechMs >= endMin（默认 700ms）才收尾，短句永远等不满，
            // 只能拖到 8s 上限再被 finish() 丢掉——「说短了收不到、说完要等好几秒」。
            // 改成：静音够了就收尾（最短时长用总时长判），真正保留门槛交给 MIN_KEEP_MS。
            val ended = (silenceMs >= endSilence && totalMs >= endMin) ||
                totalMs >= config.maxUtteranceMs
            if (!ended) continue

            if (crossedIntoBarge) {
                onDebug("utter-end dropped crossed_into_barge ms=%d speech=%d".format(totalMs, speechMs))
                parts.clear()
                inSpeech = false
                speechMs = 0
                silenceMs = 0
                totalMs = 0
                crossedIntoBarge = false
                bargeNotified = false
                onState("")
                continue
            }

            if (totalMs >= 2_000 && loudMin > 1e-4f && loudMax / loudMin < 1.8f) {
                // Flat envelope for 2 s or more: that is the room, not a voice.
                onDebug("utter-end dropped flat ms=%d barge=%s min=%.4f max=%.4f"
                    .format(totalMs, utteranceBarge, loudMin, loudMax))
                parts.clear()
                inSpeech = false
                speechMs = 0
                silenceMs = 0
                totalMs = 0
                crossedIntoBarge = false
                bargeNotified = false
                onState("")
                continue
            }

            if (bargeMode) {
                onDebug("utter-end ms=%d speech=%d peak=%.4f gate=%.4f".format(totalMs, speechMs, peakRms, gate))
            }
            val samples = finish(parts, speechMs, min(endMin, MIN_KEEP_MS))
            val bargeStarted = utteranceBarge
            parts.clear()
            inSpeech = false
            speechMs = 0
            silenceMs = 0
            totalMs = 0
            onState("")
            if (samples != null) {
                // The caller also mutes; doing it here removes the race with slow callers.
                muted = true
                onUtterance(samples, bargeStarted)
            }
        }
    }

    private fun finish(parts: List<ShortArray>, speechMs: Int, minSpeechMs: Int): FloatArray? {
        if (speechMs < minSpeechMs || parts.isEmpty()) return null
        var count = 0
        for (part in parts) count += part.size
        if (count < RATE / 10) return null

        val out = FloatArray(count)
        var offset = 0
        var peak = 0f
        for (part in parts) {
            for (sample in part) {
                val value = sample / 32768f
                out[offset++] = value
                val magnitude = abs(value)
                if (magnitude > peak) peak = magnitude
            }
        }
        // Far-field speech on this phone arrives at 0.01-0.05 peak; lift it for the ASR step.
        if (peak in 0.002f..0.20f) {
            val gain = min(6f, 0.55f / peak)
            for (i in out.indices) out[i] = (out[i] * gain).coerceIn(-1f, 1f)
        }
        return out
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(): AudioRecord? {
        // 2026-11-24 0.0.99：她说话时（echoSafe）先试 VOICE_COMMUNICATION —— 这个源才会让平台
        // AEC 拿到播放参考；MIC 上挂 AcousticEchoCanceler 实测是个空壳（她说话时麦克风收到
        // rms 0.23-0.26 的回声，跟主人 0.09-0.18 的说话同一个量级，能量门分不开）。
        // 代价：这台 vivo 在 11-13 实测过 VOICE_COMMUNICATION 有时给 0 电平，所以开完先做 500ms
        // 电平探针，没电平就退回 MIC —— 只影响「她在说」这个窗口，普通收音仍走 MIC。
        val primary = openWithSource(
            if (echoSafe) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC)
        if (primary == null) return if (echoSafe) openWithSource(MediaRecorder.AudioSource.MIC) else null
        if (!echoSafe) return primary
        val level = probeLevel(primary)
        if (level >= VOICE_COMM_PROBE_MIN) {
            onDebug("record source=voice_comm probe=%.4f aec=%s".format(level, aec != null))
            return primary
        }
        onDebug("record source=voice_comm-silent probe=%.4f fallback=mic".format(level))
        try { primary.stop() } catch (_: Exception) {}
        try { primary.release() } catch (_: Exception) {}
        record = null
        return openWithSource(MediaRecorder.AudioSource.MIC)
    }

    @SuppressLint("MissingPermission")
    private fun openWithSource(source: Int): AudioRecord? {
        val minimum = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return null
        val audio = try {
            AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minimum * 2, CHUNK * 8))
        } catch (_: Exception) {
            return null
        }
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            return null
        }
        return try {
            audio.startRecording()
            attachEchoCancel(audio)
            audio
        } catch (_: Exception) {
            audio.release()
            null
        }
    }

    /** 开完录音的 500 ms 电平探针（只看峰值，不做判定）。返回峰值（0..1）。 */
    private fun probeLevel(audio: AudioRecord): Float {
        val buf = ShortArray(CHUNK)
        val deadline = SystemClock.elapsedRealtime() + 500
        var peak = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val n = try { audio.read(buf, 0, buf.size) } catch (_: Exception) { -1 }
            if (n > 0) {
                for (i in 0 until n) {
                    val v = abs(buf[i].toInt())
                    if (v > peak) peak = v
                }
            }
        }
        return peak / 32768f
    }

    /**
     * Best effort only: the platform AEC needs the output mix as reference and may be a no-op on
     * some ROMs. Barge gating must not rely on it, but when it works it removes most of her echo.
     */
    private fun attachEchoCancel(audio: AudioRecord) {
        releaseEchoCancel()
        try {
            if (!AcousticEchoCanceler.isAvailable()) return
            aec = AcousticEchoCanceler.create(audio.audioSessionId)?.also { it.enabled = true }
            Log.i("PRTS-Call", "auto-listen aec=" + (aec != null))
        } catch (_: Exception) {
            aec = null
        }
    }

    private fun releaseEchoCancel() {
        try { aec?.release() } catch (_: Exception) {}
        aec = null
    }

    private fun closeRecord() {
        releaseEchoCancel()
        val audio = record ?: return
        record = null
        try { audio.stop() } catch (_: Exception) {}
        try { audio.release() } catch (_: Exception) {}
    }

    companion object {
        private const val RATE = 16_000
        private const val CHUNK = 640 // 40 ms
        private const val CHUNK_MS = 40
        private const val ZERO_CHUNKS_LIMIT = 125 // 5 s of digital silence
        /** Barge gate = measured echo × this margin. 11-13 的 0.045 恰好等于回声峰值，留不出余量。 */
        private const val BARGE_ECHO_MARGIN = 1.6f
        /** 打断不等端点：barge 模式下超过门限这么久就立刻停播（4 个 40ms chunk）。 */
        private const val BARGE_INTERRUPT_MS = 160
        /** 端点保留门槛：短于这个长度的语音直接丢（「嗯」「对」约 200-300ms）。 */
        private const val MIN_KEEP_MS = 160
        /** Silero 人声概率 ≥ 这个值算「有人声」；0.5 是官方语音/非语音分界。 */
        private const val VAD_SPEECH_PROB = 0.5f
        /** 起点还要有最低能量，避免把纯数字静音喂给 VAD 后偶发的高分。 */
        private const val MIN_VOICE_RMS = 0.0025f
        /** VOICE_COMMUNICATION 探针下限：峰值低于这个值就当这个源是死的（0 电平）。 */
        private const val VOICE_COMM_PROBE_MIN = 0.003f
        /** 她说话时的门限上限（方案 B）：主人的声音 0.09+ 能过；她的回声也会过，交给中继 isEcho 丢。 */
        private const val BARGE_GATE_CAP = 0.055f
    }
}
