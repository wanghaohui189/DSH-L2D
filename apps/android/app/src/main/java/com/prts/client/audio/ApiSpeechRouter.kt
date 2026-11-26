package com.prts.client.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.prts.client.net.DisplayText
import com.prts.client.net.InAudio
import com.prts.client.net.VoiceProfile
import okhttp3.Call
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One HTTP synthesis at a time; next segment can generate while the previous WAV plays. */
internal class ApiSpeechRouter(context: Context, profile: VoiceProfile,
    private val credentials: SpeechApiCredentials?, private val configurationError: String?,
    private val onPayload: (InAudio) -> Unit, private val onLocalPayload: ((LocalSpeechAudio) -> Unit)?,
    private val onNotice: (String) -> Unit, private val onBusyChanged: () -> Unit,
    private val onProgress: (LocalSpeechProgress) -> Unit,
    private val client: SpeechApiClient = SpeechApiClient(),
    private val moss: MossWavSynthesizer? = null,
) : AutoCloseable {
    private class Work(val generation: Int, val message: InAudio, val text: String, val segment: Int) {
        val cancelled = AtomicBoolean(false)
        @Volatile var call: Call? = null
    }
    private val cache = File(context.cacheDir, "tts-api").apply { mkdirs() }
    private val language = LocalVoiceSettings.outputLanguage(profile)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "PRTS-SpeechApi") }
    private val generation = AtomicInteger()
    private val closed = AtomicBoolean(false)
    private val queue = ArrayDeque<Work>()
    private var active: Work? = null
    /** 2026-11-23（D）：只用来记「本 generation 是否已弹过失败提示」，不再拦后续请求。
     *  原先一次失败就把整轮 generation 毒化，后面每条 reply 都静音，只能靠发文字 cancel 复位。 */
    private var notifiedGeneration = -1
    @Volatile private var busy = false
    val isBusy: Boolean get() = busy
    val available: Boolean get() = !closed.get() && language != null &&
        if (moss == null) credentials != null else moss.available
    private fun main(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() } }
    private fun valid(work: Work) = !closed.get() && !work.cancelled.get() && work.generation == generation.get() && active === work
    private fun changed() {
        val value = active != null || queue.isNotEmpty()
        if (value != busy) { busy = value; runCatching(onBusyChanged) }
    }

    /** 2026-11-23 主人：「先做个能记录找出这个问题的」——「请求根本没到中继」那几次手机侧
     *  一行日志都没有，所以把 enqueue 的每个出口、每次请求的起/成/败都写进 files/speech.log。
     *  只记事件与数值，不记 URL / API key / 音频；写盘失败被 DiagLog 吞掉，不影响播放行为。 */
    private fun log(line: String) { runCatching { DiagLog.write("speech.log", line) } }

    /** 一行化 + 截断，避免台词里的换行把日志记录切碎。 */
    private fun brief(value: String?, max: Int = 60): String =
        value.orEmpty().replace(Regex("[\\r\\n\\t]+"), " ").trim().take(max)

    fun enqueue(message: InAudio) {
        val ticket = generation.get()
        main {
            log("enq gen=$ticket avail=$available")
            if (closed.get() || ticket != generation.get()) {
                log("skip closed_or_stale gen=$ticket now=${generation.get()} closed=${closed.get()}")
                return@main
            }
            if (!available) {
                log("skip unavailable gen=$ticket")
                fail(ticket, configurationError ?: "语音API配置尚未就绪"); return@main
            }
            val original = message.display_text
            val plan = BilingualSpeechPolicy.select(language, original?.text, original?.transcript,
                original?.translation, original?.translation_language, original?.spoken_text)
            if (plan.missingSpeechNotice != null) {
                log("skip no_speech_text gen=$ticket")
                fail(ticket, plan.missingSpeechNotice); return@main
            }
            val text = plan.spokenText.orEmpty()
            if (text.isBlank()) { log("skip blank_text gen=$ticket"); return@main }
            if (!text.codePoints().anyMatch { Character.isLetterOrDigit(it) }) { log("skip no_alnum gen=$ticket"); return@main }
            // 云端 TTS 同样分段：首段 18 字先出声，后续边播边合成，长回复不再等整段。
            // 动作标记跟着它所修饰的那句走（见 LocalSpeechText.splitWithControls）。
            val slices = LocalSpeechText.splitWithControls(BilingualSpeechPolicy.sourceForSplitting(plan, original?.text), maximum = 48,
                firstMaximum = if (moss != null) 48 else 18, eagerFirst = moss == null)
            var queued = 0
            var firstSpeak = ""
            slices.forEachIndexed { index, slice ->
                val part = slice.text
                // 整片都是（动作描写）时朗读文本为空：只保留字幕，不合成、不念。
                val speak = LocalSpeechText.forSpeechSpoken(part)
                if (speak.isBlank()) return@forEachIndexed
                if (queued == 0) firstSpeak = brief(speak, 20)
                queued++
                val caption = BilingualSpeechPolicy.captionForSlice(plan, part, index)
                val display = (original ?: DisplayText()).copy(text = caption.text, transcript = caption.transcript,
                    translation = caption.translation, translation_language = caption.translationLanguage, spoken_text = caption.spokenText)
                val motions = slice.gestures.ifEmpty {
                    if (index == 0) message.actions?.motions else null
                }
                val actions = message.actions?.copy(motions = motions)
                queue.addLast(Work(ticket, message.copy(audio = null, volumes = emptyList(), display_text = display, actions = actions),
                    speak, index + 1))
            }
            // n = 真正交给合成的片数（与中继的 tts 行一一对应）；0 片说明整条只剩括号/表情。
            if (queued == 0) log("skip all_slices_blank gen=$ticket slices=${slices.size}")
            else log("queued gen=$ticket n=$queued first=$firstSpeak")
            changed(); pump()
        }
    }

    private fun pump() {
        if (closed.get() || active != null) return
        val work = queue.pollFirst() ?: run { changed(); return }
        active = work; changed()
        runCatching { onProgress(LocalSpeechProgress("synthesizing", work.segment, queue.size)) }
        worker.execute {
            log("req start seg=${work.segment} len=${work.text.length}")
            var payload: LocalSpeechAudio? = null
            val result = runCatching {
                val target = File.createTempFile("speech-", ".wav", cache)
                try {
                    if (moss != null) moss.synthesize(work.text, target, work.cancelled::get)
                    else client.synthesize(checkNotNull(credentials), work.text, target, work.cancelled) { call ->
                        work.call = call
                        if (work.cancelled.get() || work.generation != generation.get() || closed.get()) call.cancel()
                    }
                    val volumes = LocalSpeechWav.levels(target.readBytes())
                    log("req ok seg=${work.segment} bytes=${target.length()}")
                    LocalSpeechAudio(work.message.copy(volumes = volumes, slice_length = 20), target) { target.delete() }
                        .also { payload = it }
                } catch (error: Exception) { target.delete(); throw error }
            }
            val posted = main.post {
                if (!valid(work)) { payload?.close(); return@post }
                result.fold(onSuccess = { audio ->
                    try {
                        if (onLocalPayload != null) onLocalPayload.invoke(audio)
                        else {
                            onPayload(audio.message.copy(audio = Base64.encodeToString(audio.wav.readBytes(), Base64.NO_WRAP)))
                            audio.close()
                        }
                        active = null; changed(); pump()
                    } catch (_: Exception) { audio.close(); fail(work.generation, "语音未能交给播放器，本轮没有重复播放") }
                }, onFailure = { error ->
                    val message = if (moss != null) "MOSS 未能生成完整语音" else "语音服务未能完成本轮音频，已停止"
                    val safe = (error as? SpeechApiClient.Failure)?.safeMessage ?: message
                    // 传输层异常在 SpeechApiClient 被统一包成 Failure（源码刻意不传播原串，防止带出 URL/key），
                    // 所以 class 只会是 Failure；能区分的是 msg（无法连接 / 未返回音频 / HTTP 错误 / 未授权）。
                    log("req fail seg=${work.segment} class=${error.javaClass.simpleName} msg=${brief(safe)}")
                    fail(work.generation, safe)
                })
            }
            if (!posted) payload?.close()
        }
    }

    private fun fail(ticket: Int, message: String) {
        if (closed.get() || ticket != generation.get()) return
        log("gen_fail gen=$ticket reason=${brief(message)}")
        val first = notifiedGeneration != ticket; notifiedGeneration = ticket
        active?.let { it.cancelled.set(true); it.call?.cancel() }; active = null
        queue.clear(); changed()
        runCatching { onProgress(LocalSpeechProgress("failed", 0, 0)) }
        if (first) runCatching { onNotice(message) }
    }
    fun cancel() {
        val ticket = generation.incrementAndGet()
        main {
            log("cancel gen_new=$ticket")
            queue.removeAll { it.generation < ticket }
            active?.takeIf { it.generation < ticket }?.let { it.cancelled.set(true); it.call?.cancel(); active = null }
            notifiedGeneration = -1; changed(); pump()
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        main {
            queue.clear(); active?.let { it.cancelled.set(true); it.call?.cancel() }; active = null
            if (moss != null) { worker.execute { moss.close() }; worker.shutdown() } else worker.shutdownNow()
            changed()
        }
    }
}
