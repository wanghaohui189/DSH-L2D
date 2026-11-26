package com.prts.client.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import android.util.Log
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Silero VAD v5 (16 kHz, MIT) on ONNX Runtime — decides whether the last 32 ms of capture is a
 * human voice.
 *
 * 为什么换掉能量门（2026-11-23 实测）：这台手机在房间里底噪 rms 0.018–0.02，而主人远场说话
 * 的 rms 只有 0.01–0.03，**两者重叠**。能量门 `gate = max(0.010, noiseFloor*3.5)` 于是永远是
 * 「底噪过门」→ `silenceMs` 攒不满 → 448 段里 422 段拖到 8 s 上限才发（`VOICE-RECEIVE-2026-11-23.md`）。
 * 能量阈值调高会吞掉远场人声，调低又让底噪当人声，单靠音量没有解；Silero 判的是「像不像人声」，
 * 底噪 prob≈0.05–0.2、人声 prob>0.9，两者不重叠。
 *
 * 输入/输出按官方 v5 模型：input[1,576]（64 上下文 + 512 新样本）、state[2,1,128]、sr[1]；
 * 输出 output[1,1]（概率）与 stateN[2,1,128]。全部单线程、30 ms 级，手机端 <1 ms/块。
 */
class SileroVad private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) {
    /** [2][1][128] 递归状态。 */
    private val state = FloatArray(2 * 128)
    /** 上一窗口最后 64 个样本，拼在新窗口前面（官方要求的 576 长度输入）。 */
    private val context = FloatArray(CONTEXT)
    private val window = FloatArray(WINDOW)
    private var fill = 0
    /** sr 的形状：0 = 标量 []（官方模型声明）；1 = [1]（个别导出版本只认一维）。 */
    private var srRank = 0

    /** 丢掉递归状态；一段话结束后必须调用，否则下一句的概率被上一句拖住。 */
    fun reset() {
        java.util.Arrays.fill(state, 0f)
        java.util.Arrays.fill(context, 0f)
        fill = 0
    }

    /**
     * 喂一段 16 kHz 单声道 PCM。返回刚凑满的 32 ms 窗口的人声概率（0..1）；
     * 样本还不够一个窗口时返回 null（调用方沿用上一次的概率）。
     */
    fun push(samples: ShortArray, count: Int): Float? {
        var prob: Float? = null
        var i = 0
        while (i < count) {
            val n = minOf(WINDOW - fill, count - i)
            for (k in 0 until n) window[fill + k] = samples[i + k] / 32768f
            fill += n
            i += n
            if (fill == WINDOW) {
                prob = infer() ?: return prob
                System.arraycopy(window, WINDOW - CONTEXT, context, 0, CONTEXT)
                fill = 0
            }
        }
        return prob
    }

    private fun infer(): Float? {
        val input = FloatArray(WINDOW + CONTEXT)
        System.arraycopy(context, 0, input, 0, CONTEXT)
        System.arraycopy(window, 0, input, CONTEXT, WINDOW)
        var inputTensor: OnnxTensor? = null
        var stateTensor: OnnxTensor? = null
        var sr: OnnxTensor? = null
        var out: OrtSession.Result? = null
        return try {
            inputTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(input), longArrayOf(1, WINDOW + CONTEXT.toLong()))
            stateTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(state), longArrayOf(2, 1, STATE_DIM.toLong()))
            sr = OnnxTensor.createTensor(
                env, LongBuffer.wrap(longArrayOf(SAMPLE_RATE)),
                if (srRank == 0) longArrayOf() else longArrayOf(1))
            out = session.run(
                mapOf("input" to inputTensor, "state" to stateTensor, "sr" to sr))
            @Suppress("UNCHECKED_CAST")
            val prob = (out[0].value as Array<FloatArray>)[0][0]
            @Suppress("UNCHECKED_CAST")
            val next = out[1].value as Array<Array<FloatArray>>
            for (s in 0 until 2) {
                System.arraycopy(next[s][0], 0, state, s * STATE_DIM, STATE_DIM)
            }
            prob
        } catch (t: Throwable) {
            if (srRank == 0) {
                // 极少数导出版本把 sr 声明成 [1]：换一维再试一次，别整条链路降级。
                srRank = 1
                return infer()
            }
            Log.w("PRTS-Call", "silero vad infer failed: " + t.message)
            null
        } finally {
            try { out?.close() } catch (_: Throwable) {}
            try { inputTensor?.close() } catch (_: Throwable) {}
            try { stateTensor?.close() } catch (_: Throwable) {}
            try { sr?.close() } catch (_: Throwable) {}
        }
    }

    fun close() {
        try { session.close() } catch (_: Throwable) {}
    }

    companion object {
        private const val SAMPLE_RATE = 16_000L
        private const val WINDOW = 512       // 32 ms @16 kHz
        private const val CONTEXT = 64
        private const val STATE_DIM = 128
        const val ASSET = "vad/silero_vad.onnx"

        /** 加载失败返回 null：调用方退回能量门（功能降级，不崩）。 */
        fun load(assets: AssetManager, path: String = ASSET): SileroVad? = try {
            val bytes = assets.open(path).use { it.readBytes() }
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
            }
            SileroVad(env, env.createSession(bytes, options))
        } catch (t: Throwable) {
            Log.w("PRTS-Call", "silero vad unavailable: " + t.message)
            // logcat 在这台 ROM 上被关掉了，失败原因只能落到 files/call.log：
            // 之前遇到「JNI 1.19.2 ↔ 运行时 1.18.0 版本不匹配」，这里就是唯一的证据来源。
            DiagLog.write("call.log", "silero vad unavailable: " +
                t.javaClass.simpleName + ": " + (t.message ?: ""))
            null
        }
    }
}
