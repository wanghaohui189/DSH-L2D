package com.prts.client.live2d

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import android.view.TextureView
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import com.prts.client.ui.Prefs
import com.prts.client.audio.DiagLog

data class CallGesture(val id: String, val title: String, val durationMillis: Long)
data class CallExpression(val id: String, val title: String, val description: String = "")
private data class FaceEmotionWeights(val happy: Float, val sad: Float, val angry: Float, val surprised: Float, val shy: Float)

/** Transparent GPU producer: no per-frame Bitmap allocation or GL readback. */
class Live2DView(context: Context, private val modelDirName: String = "ellen") : TextureView(context), TextureView.SurfaceTextureListener {
    companion object {
        private const val TAG = "PRTS-Live2D"
        private const val ACTIVE_FRAME_MS = 50L
        private const val IDLE_FRAME_MS = 83L
        /**
         * 2026-11-18锛歱ending buffer 鏈€澶氱┖鏀捐繖涔堜箙锛堜笉鐢讳笉 swap锛夈€?
         * 鐪熸満瀹炴祴锛圴2307A锛屽垏椤甸潰鍥炴潵锛夛細**buffer 鍑犱綍鍙湪鎴戜滑鐪熺敾瀹屼竴甯э紙eglSwapBuffers锛変箣鍚庢墠鏇存柊**锛?
         * 鎵€浠ョ┖鏀炬湡闂存棤璁烘€庝箞閲嶆柊 setDefaultBufferSize锛宔glQuerySurface 閮借繕鏄?view 灏哄 鈥斺€?涓€鐩寸瓑
         * 姘歌繙绛変笉鍒帮紝绛夋弧涓婇檺鐢荤殑閭ｄ竴甯ф墠鏄Е鍙戝垏鎹㈢殑閭ｄ竴甯э紙鏃ュ織閲?adopt 鍚?85ms 灏卞洖 1440p锛夈€?
         * 鍥犳杩欓噷鍙敤鏉ャ€岀洊浣忓垏鎹㈠姩鐢婚偅鍑犲抚銆嶏紙鍘熸潵 1440p 閾烘弧鐢婚潰涓婂睆 ~1 绉掞級锛屽埌鐐瑰氨浜ょ粰 adopt锛?
         * 鎷変几鐢婚潰浠?~1000ms 缂╁埌涓€甯э紙~85ms锛夈€?
         */
        private const val PENDING_HOLD_MS = 300L
        /** 绌烘斁鏈熼棿閲嶅璇锋眰 setDefaultBufferSize 鐨勮妭娴侀棿闅斻€?*/
        private const val REBUFFER_REQUEST_MS = 200L
        /** Persistent render diagnostics: this ROM drops logcat, so a vanished model must leave a file. */
        private const val LOG_FILE = "render.log"
        /** A real producer/render error is retried this many times before the UI is told to offer reset. */
        private const val AUTO_RETRY_LIMIT = 2
        private const val AUTO_RETRY_DELAY_MS = 2000L
        /** Bound for the post-reset retry loop so a genuinely broken model cannot loop forever. */
        private const val RESET_ATTEMPT_LIMIT = 8
        private val nativeLock = Any() // One owner for JNI model / Cubism shader cache at a time.
        private val callGestures = listOf(
            CallGesture("call:nod", "轻点头", 1400),
            CallGesture("call:shake", "轻摇头", 1800),
            CallGesture("call:bow", "微微倾身", 2000),
            CallGesture("call:sway", "轻轻摇摆", 2500),
            // 2026-11-20: model has 509 registered parameters; the rig only ever drove 6 of them.
            // These six are authored on the same rig drivers and baked by
            // whale/l2d/gen_whale_gestures.py (manifest entries included, gate check_trained_rigs.py).
            CallGesture("call:tilt", "歪头", 1500),
            CallGesture("call:nod-twice", "连点两下头", 1600),
            CallGesture("call:shiver", "抖一抖", 900),
            CallGesture("call:look-around", "张望", 2400),
            CallGesture("call:lean-in", "凑近", 1800),
            CallGesture("call:look-up", "抬头", 1500),
            // 2026-11-21 (owner: 害羞不是只有脸红): a shy body, not only a blush
            // texture. Head tilt + look away + small duck, wired to the shy emotion
            // and usable as a [gesture:shy] tag from the conversation side.
            CallGesture("call:shy", "害羞", 1800),
        )
        // 2026-11-23 owner: 手部动作不要了 —— 右臂手势（挥手/招手/伸手/害羞抬手/手臂探针）和
        // 左臂 native raise/lower overlay 全部不再注册；`call:shy` 作为唯一的害羞姿势保留。
        /** The author's own long `Manual` performance; touch-only (owner 2026-11-20: C 鍙寕鍦ㄨЕ灞忎簰鍔?. */
        private const val TOUCH_PERFORMANCE_ID = "touch:tuobo"
        private const val TOUCH_PERFORMANCE_FILE = "脱捕.motion3.json"
        /** Extra author faces exposed on top of the six emotion aliases (tap in the action panel). */
        private val extraFaceExpressions = listOf(
            "圈圈眼" to "晕乎乎", "爱心眼" to "爱心眼", "兔牙" to "兔牙",
            "流冷汗" to "冷汗", "嫌弃眼" to "不屑",
        )
        val available = try { System.loadLibrary("prts_live2d"); true }
            catch (error: Throwable) { Log.e(TAG, "Live2D library unavailable", error); false }

        // 2026-11-23 owner: 手部动作永久删除（以后不加回）——举手保持/竖手指/狗尾草等
        // 保持姿势连同资产一起移除，不再有标题表。
        private val touchTitles = mapOf(TOUCH_PERFORMANCE_ID to "脱捕（3秒）")
        private val emotionTitles = mapOf(
            "neutral" to "自然", "happy" to "开心", "sad" to "难过",
            "angry" to "生气", "surprised" to "惊讶", "thinking" to "思考", "shy" to "害羞",
        )

        /** Chinese name of any gesture id the touch table can name; unknown ids fall back to the id. */
        internal fun gestureTitle(id: String): String =
            callGestures.firstOrNull { it.id == id }?.title
                ?: touchTitles[id] ?: id

        /** Chinese name of a `mobile:<emotion>` / `face:<file>` / bare emotion id. */
        internal fun faceTitle(id: String): String {
            val bare = id.removePrefix("mobile:").removePrefix("face:").lowercase(Locale.ROOT)
            return emotionTitles[bare] ?: extraFaceExpressions.firstOrNull { it.first == bare }?.second ?: id
        }
    }

    @Volatile private var renderThread: RenderThread? = null
    @Volatile private var wantsRendering = false
    /** Keeps EGL/model/JS alive while drawing nothing: call-grade pause that survives screen-off. */
    @Volatile private var renderPaused = false
    @Volatile private var canvasQuality = Prefs.getCanvasQuality(context)
    private class SurfaceOwner(val texture: SurfaceTexture) {
        @Volatile var retired = false
        private val released = AtomicBoolean(false)
        private val producerClaimed = AtomicBoolean(false)
        fun claimProducer() { check(!retired && producerClaimed.compareAndSet(false, true)) }
        fun releaseProducer() { producerClaimed.set(false); releaseIfRetired() }
        fun releaseIfRetired() {
            if (retired && !producerClaimed.get() && released.compareAndSet(false, true)) texture.release()
        }
    }
    @Volatile private var surfaceOwner: SurfaceOwner? = null
    private data class RenderExtent(val width: Int, val height: Int)
    private data class VerifiedSurfaceExtent(val owner: SurfaceOwner, val extent: RenderExtent)
    private data class TouchBounds(val owner: SurfaceOwner, val extent: RenderExtent, val sampledAt: Long,
        val bounds: FloatArray)
    private val touchBounds = AtomicReference<TouchBounds?>(null)
    private val verifiedSurfaceExtent = AtomicReference<VerifiedSurfaceExtent?>(null)
    private val viewExtent = AtomicReference(RenderExtent(0, 0))
    private val surfaceRevision = AtomicLong(0)
    private val activeUntilNanos = AtomicLong(0)
    private val presentedFrames = AtomicLong(0)
    @Volatile private var maximumTextureSize = 0
    @Volatile private var decodedTextureBytes = 0L
    @Volatile private var lastRenderFailure: String? = null
    private val pendingExpression = AtomicReference<String?>(null)
    private val pendingMotion = AtomicReference<String?>(null)
    /** 2026-11-21 diagnostic window: log live parameter values right after a motion is played. */
    @Volatile private var probeUntil = 0L
    @Volatile private var probeNext = 0L
    private val pendingArmPose = AtomicReference<Boolean?>(null)
    private val pendingMotionReset = AtomicBoolean(false)
    private val pendingDrag = AtomicReference<Pair<Float, Float>?>(null)
    private val emotionState = AtomicReference(FaceEmotionWeights(0f, 0f, 0f, 0f, 0f))
    private val performanceOwner = AtomicLong(0)
    private val latestJsParameters = AtomicReference<FloatArray?>(null)
    private val performanceIntent = AtomicReference<JSONObject?>(null)
    private val nativePerformance = AtomicReference("{\"active\":false}")
    private val performanceState = AtomicReference("{\"state\":\"new\",\"jsActive\":false}")
    private val manualPerformanceFace = AtomicBoolean(false)
    @Volatile private var performanceEngine: SoullinkJsEngine? = null
    @Volatile private var performanceSpeaking = false
    @Volatile private var performanceSpeechDuration = 0L
    private val presentation = AtomicReference(PresentationTransform())
    @Volatile var isModelReady: Boolean = false
        private set
    @Volatile var continuousEmotionAvailable: Boolean = false
        private set
    @Volatile private var mouth = 0f
    @Volatile private var expressionNames: List<String> = emptyList()
    @Volatile private var motionNames: Set<String> = emptySet()
    @Volatile private var trainedCallMotionCount = 0
    /** Duration of the author's touch-only long performance (`touch:tuobo`), 0 when unavailable. */
    @Volatile private var touchPerformanceMillis: Long = 0L
    @Volatile var currentArmState: String = "down"
        private set
    val isArmPoseBusy: Boolean get() = currentArmState != "down" || pendingArmPose.get() != null
    /** 2026-11-23 owner: 手/腿动作永久删除（以后不加回）——arm pair、右臂手势、pose 保持姿势、
     *  walk/legprobe 全部不再注册，相关状态机与资产已移除。 */
    /** Separate from the maintenance handler so no pose reset can cancel the recovery ticks. */
    private val maintenanceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val resetCount = AtomicLong(0)
    private val resetAttempts = AtomicLong(0)
    private val autoRetryAttempts = AtomicLong(0)
    private val autoRetryWaits = AtomicLong(0)
    /** UI-thread hook invoked only after every automatic retry failed; the screen offers 銆屾ā鍨嬪浣嶃€? */
    @Volatile var onRenderFailure: ((String) -> Unit)? = null
    @Volatile private var lastFailureNoticeAt = 0L
    @Volatile private var emotionAliases: Map<String, String> = emptyMap()
    @Volatile private var blockedExpressions: Set<String> = emptySet()
    init { isOpaque = false; surfaceTextureListener = this }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); startRender() }
    override fun onDetachedFromWindow() { stopRender(); super.onDetachedFromWindow() }
    private fun animateFor(milliseconds: Long = 1500) {
        val until = System.nanoTime() + milliseconds * 1_000_000
        while (true) {
            val old = activeUntilNanos.get()
            if (old >= until || activeUntilNanos.compareAndSet(old, until)) return
        }
    }
    fun setExpression(name: String) { pendingExpression.set(name); animateFor() }
    fun clearExpression() { pendingExpression.set(""); animateFor() }

    /** Visual framing only; fractions of the viewport, +X right / +Y down. Safe to persist per character. */
    fun setPresentationTransform(scale: Float, offsetX: Float, offsetY: Float) {
        val value = PresentationPolicy.transform(scale, offsetX, offsetY)
        presentation.set(value)
        animateFor(1500)
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        canvasQuality = Prefs.getCanvasQuality(context)
        texture.setDefaultBufferSize(canvasQuality.width, canvasQuality.height)
        surfaceOwner = SurfaceOwner(texture)
        viewExtent.set(RenderExtent(width, height))
        surfaceRevision.incrementAndGet()
        fitSurface(width, height)
        if (wantsRendering) startRender()
    }
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        texture.setDefaultBufferSize(canvasQuality.width, canvasQuality.height)
        viewExtent.set(RenderExtent(width, height))
        surfaceRevision.incrementAndGet()
        fitSurface(width, height)
    }
    /** 閲嶆柊鎸夊綋鍓嶈鍥惧昂瀵稿榻愮敾闈紙浠庡埆鐨勯〉闈㈠洖鏉ャ€乿iew 灏哄鎮勬倓鍙樹簡浣嗘病鍥炶皟鏃剁敤锛夈€?*/
    fun refitSurface() {
        val w = width
        val h = height
        if (w > 0 && h > 0) fitSurface(w, h)
    }

    private fun fitSurface(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val source = renderExtent()
        val viewport = PresentationPolicy.fitViewport(width.toFloat(), height.toFloat(), source.width.toFloat(), source.height.toFloat())
        if (viewport.width <= 0f || viewport.height <= 0f) return
        // 2026-11-17 淇細TextureView 榛樿鎶?buffer **鎷変几閾烘弧** view锛屽畠鐨?setTransform 浠?view 鍍忕礌
        // 涓哄崟浣嶏紝鎵€浠ユ妸銆岄摵婊＄殑鐢婚潰銆嶇缉鍒?viewport 鏃跺垎姣嶅繀椤绘槸 view 灏哄锛岃€屼笉鏄?buffer 鍍忕礌銆?
        // 涓婁竴鐗堢敤 vp.width/source.width 鍋氱瓑姣旂缉鏀撅細view=1260x2667銆乥uffer=1440x2560 鏃朵細鎶婄敾闈?
        // 缂╂垚 1102x2334 骞跺乏瀵归綈锛坮ender.log: scale=0.8750 pos=0.0,213.5锛夛紝鐪熸満涓婂氨鏄€屾ā鍨嬪彉褰€嶃€?
        // 涓よ酱姣斾緥涓嶇浉绛夋槸**蹇呴渶鐨?*锛氶摵婊℃湰韬凡鎶?buffer 闈炵瓑姣旀媺浼歌繃锛屼箻鍥炲幓鎵嶅緱鍒扮瓑姣?viewport銆?
        val sx = viewport.width / width
        val sy = viewport.height / height
        setTransform(Matrix().apply {
            setScale(sx, sy, width / 2f, height / 2f)
        })
        DiagLog.write(LOG_FILE, "fit view=${width}x$height extent=${source.width}x${source.height} " +
            "sx=%.4f sy=%.4f vp=%.1fx%.1f".format(sx, sy, viewport.width, viewport.height))
    }

    private fun renderExtent(): RenderExtent = verifiedSurfaceExtent.get()
        ?.takeIf { it.owner === surfaceOwner && !it.owner.retired }?.extent
        ?: RenderExtent(canvasQuality.width, canvasQuality.height)

    /** View-local fitted bounds, shared by placement editing on Start and Call. */
    fun presentationViewport(): android.graphics.RectF {
        val source = renderExtent()
        val viewport = PresentationPolicy.fitViewport(width.toFloat(), height.toFloat(), source.width.toFloat(), source.height.toFloat())
        return android.graphics.RectF(viewport.left, viewport.top, viewport.left + viewport.width, viewport.top + viewport.height)
    }

    /** View-local pointer, matched to a recent projection of the built-in model's visible head/torso.
     * Bounds are rectangular approximations of the named mesh regions, not per-pixel alpha tests.
     */
    fun characterTouchRegion(localX: Float, localY: Float): String? {
        if (!isModelReady) return null
        val snapshot = touchBounds.get()?.takeIf {
            it.owner === surfaceOwner && !it.owner.retired && it.extent == renderExtent() &&
                android.os.SystemClock.elapsedRealtime() - it.sampledAt in 0L..650L
        } ?: return null
        val viewport = PresentationPolicy.fitViewport(width.toFloat(), height.toFloat(),
            snapshot.extent.width.toFloat(), snapshot.extent.height.toFloat())
        return PresentationPolicy.touchRegion(viewport, localX, localY, snapshot.bounds)
    }

    /** Read-only diagnostic: the projected head/torso boxes as viewport fractions. */
    fun touchBoundsSnapshot(): String {
        val snapshot = touchBounds.get() ?: return "none"
        return snapshot.bounds.joinToString(",") {
            String.format(java.util.Locale.ROOT, "%.3f", it)
        }
    }

    /** Actual renderer observations only; no readback or claim about visible mouth shape. */
    fun renderingDiagnostics(): JSONObject {
        val verified = verifiedSurfaceExtent.get()?.takeIf { it.owner === surfaceOwner && !it.owner.retired }
        return JSONObject().put("model", modelDirName).put("modelReady", isModelReady)
            .put("selectedQuality", canvasQuality.key).put("requestedWidth", canvasQuality.width)
            .put("requestedHeight", canvasQuality.height).put("actualWidth", verified?.extent?.width ?: JSONObject.NULL)
            .put("actualHeight", verified?.extent?.height ?: JSONObject.NULL)
            .put("surfaceVerifiedForCurrentOwner", verified != null).put("presentedFrames", presentedFrames.get())
            .put("renderThreadAlive", renderThread?.isAlive == true).put("requestedMouthLevel", mouth)
            .put("trainedCallMotionCount", trainedCallMotionCount)
            .put("performanceRuntime", JSONObject(performanceState.get()))
            .put("performanceNative", JSONObject(nativePerformance.get()))
            .put("glMaximumTextureSize", maximumTextureSize).put("decodedTextureBytes", decodedTextureBytes)
            .put("renderFailure", lastRenderFailure ?: JSONObject.NULL)
            .put("renderResets", resetCount.get()).put("autoRetries", autoRetryAttempts.get())
    }
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        val thread = renderThread
        val owner = surfaceOwner?.takeIf { it.texture === texture }
            ?: thread?.owner?.takeIf { it.texture === texture }
        if (owner == null) { texture.release(); return false }
        owner.retired = true
        if (surfaceOwner === owner) surfaceOwner = null
        if (thread?.owner === owner) { thread.running = false; thread.interrupt() }
        owner.releaseIfRetired()
        // The render thread releases it only after disconnecting its EGL producer.
        return false
    }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    /** Automatic state only. Manual expressions temporarily take priority without discarding this snapshot. */
    fun setEmotionWeights(happy: Float, sad: Float, angry: Float, surprised: Float, shy: Float) {
        fun safe(value: Float) = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
        emotionState.set(FaceEmotionWeights(safe(happy), safe(sad), safe(angry), safe(surprised), safe(shy)))
    }
    fun clearEmotionState() {
        setEmotionWeights(0f, 0f, 0f, 0f, 0f)
        setPerformanceEmotion("neutral",0.0,1.0,0.0,0.0,0.0)
    }
    fun setPerformanceEmotion(emotion: String, intensity: Double, confidence: Double,
                              valence: Double?, arousal: Double?, dominance: Double?) {
        val event=JSONObject().put("type","emotion").put("emotion",emotion.take(40))
            .put("intensity",if(intensity.isFinite())intensity.coerceIn(0.0,1.0) else 0.0)
            .put("confidence",if(confidence.isFinite())confidence.coerceIn(0.0,1.0) else 0.0)
        listOf("valence" to valence,"arousal" to arousal,"dominance" to dominance).forEach { (name,value) ->
            if(value?.isFinite()==true)event.put(name,value.coerceIn(-1.0,1.0))
        }
        performanceIntent.set(event)
        performanceEngine?.sendEvent(event)
        animateFor(3500)
    }
    fun setPerformanceSpeech(active: Boolean, durationMs: Long) {
        performanceSpeaking=active;performanceSpeechDuration=if(active)durationMs.coerceIn(0,120000) else 0
        performanceEngine?.sendEvent(JSONObject().put("type",if(active)"speech_start" else "speech_end")
            .put("durationMs",performanceSpeechDuration).put("nowMs",android.os.SystemClock.elapsedRealtime()))
        if(active)animateFor(performanceSpeechDuration+250)
    }
    /** GL owner binds real parameters; callbacks only publish immutable snapshots. */
    private fun createPerformanceEngine(token: Long): SoullinkJsEngine? {
        if(modelDirName !in setOf("whale","ellen")) return null
        return try {
            val composite=context.assets.open("soullink/profiles/$modelDirName.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            val parameters=composite.getJSONObject("native").getJSONArray("parameters")
            val ids=Array(parameters.length()){parameters.getJSONObject(it).getString("id")}
            val defaults=FloatArray(ids.size){parameters.getJSONObject(it).getDouble("default").toFloat()}
            val bounds=FloatArray(ids.size*3){index ->
                parameters.getJSONObject(index/3).getDouble(arrayOf("min","default","max")[index%3]).toFloat()
            }
            check(nativeConfigureJsPerformance(ids,bounds)){"PerformanceProfileDoesNotMatchLoadedRig"}
            val engine=SoullinkJsEngine(context,
                onSnapshot={ snapshot ->
                    if(performanceOwner.get()==token) {
                        val values=snapshot.optJSONObject("parameters")
                        if(values!=null&&values.length()>0) {
                            val targets=FloatArray(ids.size){index ->values.optDouble(ids[index],defaults[index].toDouble()).toFloat()}
                            if(targets.all{it.isFinite()}) latestJsParameters.set(targets)
                        }
                    }
                },onState={ status ->
                    if(performanceOwner.get()==token) {
                        performanceState.set(status.toString())
                        if(!status.optBoolean("jsActive",false))latestJsParameters.set(null)
                        Log.i("PRTS-Soullink",status.toString())
                    }
                },callbackExecutor=java.util.concurrent.Executor{it.run()})
            performanceEngine=engine
            engine.initialize("soullink/performance.bundle.js",JSONObject().put("profile",composite.getJSONObject("profile"))
                .put("parameterMetadata",parameters).put("character",modelDirName)
                .put("seed",android.os.SystemClock.elapsedRealtime().and(0x7fffffffL)))
            performanceIntent.get()?.let{engine.sendEvent(it)}
            if(performanceSpeaking)engine.sendEvent(JSONObject().put("type","speech_start").put("durationMs",performanceSpeechDuration)
                .put("nowMs",android.os.SystemClock.elapsedRealtime()))
            engine
        } catch(error: Exception) {
            val status=JSONObject().put("state","binding_failed").put("jsActive",false).put("reason",error.message?:error.javaClass.simpleName)
            performanceState.set(status.toString());Log.e("PRTS-Soullink",status.toString());null
        }
    }
    fun playMotion(name: String) { pendingMotion.set(name); animateFor(3500) }
    /** Panel list: 只有头/身体动作（手/腿已永久删除）。 */
    fun availableGestures(): List<CallGesture> = callGestures.filter {
        it.id in motionNames && it.id != "call:shy"
    }

    /** Reset only application-owned posture; retain the selected facial expression. */
    fun resetMotionState() {
        pendingMotion.set(null)
        pendingArmPose.set(null)
        pendingMotionReset.set(true)
    }

    /** Only reviewed emotion aliases; model-specific prop / costume toggles stay private. */
    fun availableExpressions(): List<CallExpression> {
        if (expressionNames.isEmpty()) return emptyList()
        val result = mutableListOf(CallExpression("mobile:neutral", "自然", "恢复自然表情"))
        for ((tag, title) in listOf("happy" to "开心", "sad" to "难过", "angry" to "生气",
                "surprised" to "惊讶", "thinking" to "思考", "shy" to "害羞")) {
            val target = emotionAliases[tag]
            if (!target.isNullOrBlank() && target in expressionNames && target !in blockedExpressions)
                result.add(CallExpression("mobile:$tag", title))
        }
        // 2026-11-20: the model ships 23 expression files but only six emotion aliases were exposed.
        // These are reviewed extra faces 鈥?no costume / prop / loading switches.
        for ((name, title) in extraFaceExpressions) {
            if (name in expressionNames && name !in blockedExpressions)
                result.add(CallExpression("face:$name", title, "换一个表情"))
        }
        return result
    }

    fun playGesture(name: String, extended: Boolean = false): Boolean {
        val raw = name.trim()
        // 2026-11-23 owner: pose:*（举手/竖手指/狗尾草）与 call:walk/call:legprobe 已永久删除，
        // 这些名字到这里只会被判成「不认识」。
        if (raw.startsWith("pose:", ignoreCase = true)) return false
        val value = raw.lowercase(Locale.ROOT)
        val canonical = if (value.startsWith("call:")) value else "call:$value"
        if (callGestures.none { it.id == canonical }) return false
        val target = canonical + if (extended) "-wide" else ""
        if (target !in motionNames) {
            DiagLog.write("motion.log", "play id=$target ok=false reason=not-registered call=${motionNames.count { it.startsWith("call:") }}")
            return false
        }
        DiagLog.write("motion.log", "play id=$target ok=true")
        pendingMotion.set(target)
        animateFor(3500)
        return true
    }

    /** Prefer the rig's wide authored motion for automatic gestures. */
    fun playPerformanceGesture(id: String): Boolean =
        playGesture(id, extended = true) || playGesture(id)

    /** Suggestions only: the caller decides cadence and whether a reply needs motion. */
    fun suggestedGestureForEmotion(emotion: String): String? {
        val tag = emotion.trim().lowercase(Locale.ROOT).removePrefix("mobile:")
        val suggestion = when (tag) {
            "happy", "joy" -> "call:nod"
            "angry", "anger", "disgust" -> "call:shake"
            // 2026-11-23: shy no longer carries a raised hand; the rig-only pose is the whole gesture.
            "shy" -> "call:shy"
            "smirk" -> "call:sway"
            else -> null
        }
        return suggestion?.takeIf { it in motionNames }
    }
    fun setDrag(x: Float, y: Float) { pendingDrag.set(x to y); animateFor() }
    fun setMouth(value: Float) {
        mouth = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
        if (mouth > .01f) animateFor(750)
    }

    /** Apply the shared preference to a loaded preview as well as a new call. */
    fun refreshCanvasQuality() {
        val next = Prefs.getCanvasQuality(context)
        if (next == canvasQuality) return
        val shouldRender = wantsRendering
        renderThread?.let { it.running = false; it.interrupt() }
        isModelReady = false
        canvasQuality = next
        verifiedSurfaceExtent.set(null)
        surfaceOwner?.takeUnless { it.retired }?.texture?.setDefaultBufferSize(next.width, next.height)
        surfaceRevision.incrementAndGet()
        val view = viewExtent.get()
        fitSurface(view.width, view.height)
        Log.i(TAG, "canvas selection model=$modelDirName quality=${next.key} requested=${next.width}x${next.height}")
        if (shouldRender) startRender()
    }

    /** Stop drawing but keep the GL surface, model and JS runtime alive: screen-off calls need this. */
    fun pauseRendering() {
        renderPaused = true
        mouth = 0f
    }

    fun startRender() {
        renderPaused = false
        wantsRendering = true
        refreshCanvasQuality()
        if (!available || !isAttachedToWindow || renderThread?.isAlive == true) return
        val owner = surfaceOwner?.takeUnless { it.retired } ?: return
        // Re-attaching the view (e.g. coming back from the call screen) makes the system reset the
        // TextureView's default buffer size to the view size, so the next EGL window surface comes
        // back smaller than the chosen quality and the frame check fails -> blank model. Re-apply
        // our buffer size before the render thread creates the surface.
        owner.texture.setDefaultBufferSize(canvasQuality.width, canvasQuality.height)
        animateFor(2000)
        lastRenderFailure = null
        owner.claimProducer()
        RenderThread(owner).also {
            renderThread = it
            try { it.start() }
            catch (error: Throwable) {
                renderThread = null; wantsRendering = false; owner.releaseProducer()
                Log.e(TAG, "render thread could not start", error)
            }
        }
    }

    fun stopRender() {
        renderPaused = false
        wantsRendering = false
        mouth = 0f
        pendingMotion.set(null)
        pendingArmPose.set(null)
        pendingMotionReset.set(true)
        currentArmState = "down"
        continuousEmotionAvailable = false
        isModelReady = false
        resetAttempts.set(0)
        autoRetryAttempts.set(0)
        maintenanceHandler.removeCallbacksAndMessages(null)
        renderThread?.let { it.running = false; it.interrupt() }
        // No UI-thread join: a slow GL driver must not freeze navigation.
    }

    /**
     * 妯″瀷澶嶄綅锛堛€屾ā鍨嬪浣嶃€嶆寜閽殑鍞竴鍏ュ彛锛夛細涓㈡帀褰撳墠 EGL 涓婁笅鏂?/ 宸蹭笂浼犵殑妯″瀷锛屾竻绌烘寕璧风殑
     * 琛ㄦ儏銆佸姩浣溿€佸槾鍨嬪拰濮挎€侊紝鐒跺悗瀹屾暣閲嶈浇涓€閬嶃€?
     *
     * 杩欐潯璺緞瀛樺湪鐨勭悊鐢憋細娓叉煋绾跨▼鐪熼亣鍒?producer/GL/璧勬簮閿欒鏃朵細 `wantsRendering = false`
     * 鍋滀笅绛夋樉寮忛噸璇曪紙瑙?[RenderThread.run] 鐨?catch锛夛紝鍦ㄩ偅涔嬪墠鍙湁銆岄€€鍒板悗鍙板啀鍥炴潵銆嶈兘鏁戯紝
     * 灞忓箷涓婂氨鏄竴涓┖鎺夌殑妯″瀷銆?
     *
     * @return true 琛ㄧず宸茬粡寮€濮嬮噸杞斤紱false 琛ㄧず褰撳墠娌℃湁鍙敤鐨?GL 搴撴垨瑙嗗浘鏈檮鐫€銆?
     */
    fun resetModel(reason: String = "manual"): Boolean {
        if (!available) return false
        val thread = renderThread
        val snapshot = "model=$modelDirName reason=$reason ready=$isModelReady " +
            "presented=${presentedFrames.get()} failure=${lastRenderFailure ?: "-"} threadAlive=${thread?.isAlive == true}"
        Log.i(TAG, "reset $snapshot")
        DiagLog.write(LOG_FILE, "reset $snapshot")
        resetCount.incrementAndGet()
        resetAttempts.set(0)
        autoRetryAttempts.set(0)
        clearPendingState()
        lastRenderFailure = null
        // A paused (screen-off) call must come back to drawing when the user asks for a reset.
        renderPaused = false
        wantsRendering = true
        if (!isAttachedToWindow) return false
        if (thread == null) { startRender(); return true }
        thread.running = false
        thread.interrupt()
        // The dying thread's finally block restarts rendering; this tick is the fallback when it cannot.
        maintenanceHandler.removeCallbacks(restoreTick)
        maintenanceHandler.postDelayed(restoreTick, 300)
        return true
    }

    /** Drop every input the old GL/model instance would otherwise replay into the new one. */    private fun clearPendingState() {
        pendingExpression.set(null)
        pendingMotion.set(null)
        pendingArmPose.set(null)
        pendingDrag.set(null)
        pendingMotionReset.set(true)
        emotionState.set(FaceEmotionWeights(0f, 0f, 0f, 0f, 0f))
        performanceIntent.set(null)
        latestJsParameters.set(null)
        touchBounds.set(null)
        manualPerformanceFace.set(false)
        performanceSpeaking = false
        performanceSpeechDuration = 0L
        currentArmState = "down"
        mouth = 0f
        isModelReady = false
    }

    /** Tell the screen once per few seconds that the renderer gave up, so a retry storm cannot spam toasts. */
    private fun notifyRenderFailure(message: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastFailureNoticeAt < 8000L) return
        lastFailureNoticeAt = now
        post { onRenderFailure?.invoke(message) }
    }

    /** Keeps trying to bring the renderer back after a manual reset, bounded by [RESET_ATTEMPT_LIMIT]. */
    private val restoreTick = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || !wantsRendering || renderPaused || isModelReady) {
                resetAttempts.set(0)
                return
            }
            if (renderThread?.isAlive == true) {
                maintenanceHandler.postDelayed(this, 600)
                return
            }
            if (resetAttempts.getAndIncrement() >= RESET_ATTEMPT_LIMIT) {
                maintenanceHandler.removeCallbacks(this)
                DiagLog.write(LOG_FILE, "reset gave up model=$modelDirName failure=${lastRenderFailure ?: "no-frames"}")
                notifyRenderFailure(lastRenderFailure ?: "no-frames")
                return
            }
            Log.i(TAG, "reset retry model=$modelDirName attempt=${resetAttempts.get()}")
            startRender()
            maintenanceHandler.postDelayed(this, 600)
        }
    }

    /** Automatic recovery for an intermittent producer/render failure: no user action needed. */
    private val autoRetryTick = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || renderPaused || isModelReady) return
            if (renderThread?.isAlive == true) {
                if (autoRetryWaits.incrementAndGet() > 6) { autoRetryWaits.set(0); return }
                maintenanceHandler.postDelayed(this, 800)
                return
            }
            autoRetryWaits.set(0)
            val attempt = autoRetryAttempts.get()
            Log.i(TAG, "auto retry model=$modelDirName attempt=$attempt failure=${lastRenderFailure ?: "-"}")
            DiagLog.write(LOG_FILE, "auto-retry model=$modelDirName attempt=$attempt failure=${lastRenderFailure ?: "-"}")
            wantsRendering = true
            startRender()
        }
    }

    private inner class RenderThread(val owner: SurfaceOwner) : Thread("PRTS-Live2D-GL") {
        @Volatile var running = true
        /** pending buffer 鐨勮捣绠楁椂鍒伙紙0 = 褰撳墠涓嶆槸 pending锛夈€?*/
        private var pendingSinceMs = 0L
        /** pending 瓒呰繃 PENDING_HOLD_MS 鍚庡凡鍏滃簳閲囩敤杩囷紝鍒啀鍙嶅绌烘斁銆?*/
        private var pendingCommitted = false
        private var lastRebufferAtMs = 0L

        /** EGL surface 鐨勭湡瀹炲儚绱犲昂瀵革紙涓嶅惈浠讳綍闄嶇骇/鐚滄祴锛夈€?*/
        private fun rawExtent(display: android.opengl.EGLDisplay, surface: EGLSurface): RenderExtent {
            val queriedWidth = IntArray(1)
            val queriedHeight = IntArray(1)
            check(EGL14.eglQuerySurface(display, surface, EGL14.EGL_WIDTH, queriedWidth, 0)
                && EGL14.eglQuerySurface(display, surface, EGL14.EGL_HEIGHT, queriedHeight, 0)
                && queriedWidth[0] > 0 && queriedHeight[0] > 0) { "EGL surface has no valid drawing extent" }
            return RenderExtent(queriedWidth[0], queriedHeight[0])
        }

        /**
         * TextureView 鐨?buffer 灏哄鏄?*寮傛鐢熸晥**鐨勶細鏂板缓 surface 鎴?view 灏哄鍙樺寲鍚庯紝绗竴娆?
         * eglQuerySurface 甯稿父杩樿繑鍥炴棫鐨?buffer 灏哄锛堥粯璁?buffer = view 灏哄锛夈€?
         * 2026-11-17 鍓嶇殑鑰侀€昏緫涓€鐪嬪埌姣?1440p 灏忓氨姘镐箙闄嶅埌 1080p锛氱敾闈㈤殢鍚庢寜 1260x2667锛?:19锛?
         * 閾烘弧銆佹ā鍨嬭鎷夐暱鍑犵锛岀瓑涓嬩竴娆?surface 閲嶅缓鍙堣烦鍥?1440p 鈥斺€?灏辨槸銆屽彉褰?璺充竴涓嬨€嶃€?
         * 鎵€浠ュ彧鏈夈€宑reated 涓旀槑鏄句笉鏄粯璁?buffer銆嶆墠褰撶湡闄嶇骇锛涘叾浣欐寜瀹為檯灏哄鐢伙紝绛?probe 鑷剤銆?
         */
        private fun isDefaultBuffer(extent: RenderExtent): Boolean {
            val view = viewExtent.get()
            return view.width > 0 && view.height > 0 &&
                extent.width == view.width && extent.height == view.height
        }

        private fun adoptExtent(extent: RenderExtent, phase: String) {
            val requested = canvasQuality
            val short = extent.width < requested.width || extent.height < requested.height
            if (short && phase == "created" && !isDefaultBuffer(extent)) {
                // A surface smaller than the chosen quality used to be a hard failure that left the
                // screen empty. Degrade to the largest quality that actually fits instead: the frame
                // is drawn at the real extent, so nothing gets upscaled.
                val fallback = CanvasQuality.values().lastOrNull {
                    it.width <= extent.width && it.height <= extent.height
                }
                if (fallback != null && fallback != requested) {
                    Log.w(TAG, "canvas fallback model=$modelDirName requested=${requested.key} actual=${extent.width}x${extent.height} using=${fallback.key}")
                    DiagLog.write(LOG_FILE, "canvas-fallback model=$modelDirName requested=${requested.key} actual=${extent.width}x${extent.height} using=${fallback.key}")
                    canvasQuality = fallback
                }
            }
            val effective = if (extent.width >= canvasQuality.width && extent.height >= canvasQuality.height) canvasQuality
                else CanvasQuality.values().lastOrNull { it.width <= extent.width && it.height <= extent.height } ?: canvasQuality
            CanvasRenderPolicy.requireActualExtent(effective, extent.width, extent.height)
            verifiedSurfaceExtent.set(VerifiedSurfaceExtent(owner, extent))
            val view = viewExtent.get()
            Log.i(TAG, "surface extent model=$modelDirName phase=$phase quality=${canvasQuality.key} requested=${canvasQuality.width}x${canvasQuality.height} actual=${extent.width}x${extent.height} view=${view.width}x${view.height}")
            if (short && effective != canvasQuality) {
                // 杩樻病鎷垮埌璇锋眰鐨勫昂瀵革細鐓у疄闄呭昂瀵哥敾锛屼笉闄嶇骇锛岀瓑涓嬩竴杞?probe銆?
                DiagLog.write(LOG_FILE, "canvas-pending model=$modelDirName phase=$phase requested=${canvasQuality.key} actual=${extent.width}x${extent.height} effective=${effective.key}")
            }
            post {
                if (surfaceOwner === owner && !owner.retired) {
                    val currentView = viewExtent.get()
                    fitSurface(currentView.width, currentView.height)
                }
            }
        }

        /** 宸茬粡鏈変竴甯ф寜 adopt 杩囩殑灏哄鏄剧ず鍦ㄥ睆涓婂悧锛? 绌烘斁鏃惰繕鐣欑潃鍙湅鐨勭敾闈級銆?*/
        private fun hasScreenFrame(): Boolean =
            verifiedSurfaceExtent.get()?.let { it.owner === owner && !it.owner.retired } == true

        /** 榛樿 buffer锛? view 灏哄锛変笖灏忎簬璇锋眰鐨勭敾甯?= buffer 杩樻病鎹㈡垚璇锋眰灏哄銆?*/
        private fun isPendingExtent(extent: RenderExtent): Boolean {
            val requested = canvasQuality
            return isDefaultBuffer(extent) &&
                (extent.width < requested.width || extent.height < requested.height)
        }

        /**
         * 鍐嶅悜 TextureView 瑕佷竴娆¤姹傚昂瀵哥殑 buffer銆?
         * 2026-11-18 鐪熸満锛圴2307A锛?.0.71 棣栫増锛夛細杩欐鍘熸潵 `post` 缁欎富绾跨▼锛屽垏鏉冮檺椤靛洖鏉ユ椂涓荤嚎绋?
         * 姝ｅ繖鍦ㄩ〉闈㈠垏鎹㈠姩鐢婚噷锛屽嚑娆¤姹傚叏鎺掗槦鍒?~1.5s 鎵嶆墽琛?鈥斺€?绌烘斁绛変笉鍒?buffer 灏辩櫧绛変簡銆?
         * SurfaceTexture.setDefaultBufferSize 鏄?producer 渚ц皟鐢紙鐩告満绾跨▼涔熻繖涔堢敤锛夛紝鐩存帴鍦ㄦ覆鏌?
         * 绾跨▼璋冿紝钀藉湴灏变粠 ~1.6s 闄嶅埌涓€涓ゅ抚銆?
         */
        private fun requestRequestedBuffer(nowMs: Long) {
            if (nowMs - lastRebufferAtMs < REBUFFER_REQUEST_MS) return
            lastRebufferAtMs = nowMs
            if (surfaceOwner !== owner || owner.retired) return
            try {
                owner.texture.setDefaultBufferSize(canvasQuality.width, canvasQuality.height)
                DiagLog.write(LOG_FILE, "canvas-rebuffer model=$modelDirName requested=${canvasQuality.key}")
            } catch (_: Throwable) {
                // 鎷夸笉鍒板氨闈犱笅涓€甯х殑 probe 缁х画璇曪紱鍒璇婃柇鎶婃覆鏌撶嚎绋嬪甫宕┿€?
            }
        }

        /**
         * 2026-11-18锛歍extureView 鐨?buffer 灏哄鏄?*寮傛**鐢熸晥鐨勩€傚脊閿洏 / 鍒囨潈闄愰〉 / view 灏哄鍙樺寲
         * 锛坥nSurfaceTextureSizeChanged锛変箣鍚庣殑涓€灏忔鏃堕棿閲岋紝eglQuerySurface 杩斿洖鐨勪粛鏄€岄粯璁?buffer =
         * view 灏哄銆嶏紙瀹炴祴 view=1260x2667銆佽姹?1440x2560锛夈€傝€侀€昏緫褰撳満 adopt 瀹冿細杩欎竴绉掗噷鐢婚潰鎸?
         * 9:19 灏哄箙缁樺埗銆乫it 鍙堟寜 extent==view 绠楀嚭 sx=1.0 sy=1.0锛岀湡浜虹湅鍒扮殑灏辨槸妯″瀷琚媺闀垮啀璺冲洖鏉ャ€?
         * 鐜板湪锛歱ending 鏈熼棿**涓?adopt銆佷笉鐢讳笉 swap**锛圱extureView 缁х画鏄剧ず涓婁竴甯с€乼ransform 涓嶅彉锛夛紝
         * 鍙噸澶嶈姹?setDefaultBufferSize锛涜繛缁?pending 瓒呰繃 [PENDING_HOLD_MS] 鎵嶅厹搴?adopt锛?
         * 鍏嶅緱涓婃父鐪熺殑鏀句笉涓嬫椂姘镐箙鍋滃湪鏃у抚銆?
         * @return true = 杩欎竴甯у厛鍒敤杩欎釜 extent锛堣皟鐢ㄦ柟淇濇寔涓婁竴甯х殑 drawingExtent / transform锛?
         */
        private fun holdPendingExtent(extent: RenderExtent, nowMs: Long, source: String): Boolean {
            if (!isPendingExtent(extent)) { pendingSinceMs = 0L; pendingCommitted = false; return false }
            if (!hasScreenFrame() || pendingCommitted) return false
            if (pendingSinceMs == 0L) {
                pendingSinceMs = nowMs
                DiagLog.write(LOG_FILE, "canvas-hold-start model=$modelDirName source=$source requested=${canvasQuality.key} pending=${extent.width}x${extent.height} view=${viewExtent.get().width}x${viewExtent.get().height}")
                Log.i(TAG, "canvas hold model=$modelDirName source=$source pending=${extent.width}x${extent.height} requested=${canvasQuality.width}x${canvasQuality.height}")
            }
            val waited = nowMs - pendingSinceMs
            if (waited >= PENDING_HOLD_MS) {
                pendingCommitted = true
                DiagLog.write(LOG_FILE, "canvas-hold-adopt model=$modelDirName source=$source waited=${waited}ms pending=${extent.width}x${extent.height}")
                Log.w(TAG, "canvas hold gave up, adopting model=$modelDirName source=$source waited=${waited}ms pending=${extent.width}x${extent.height}")
                return false
            }
            requestRequestedBuffer(nowMs)
            return true
        }

        /** @return 閲囩敤鍚庣殑 extent锛沶ull = 杩樺湪 pending锛岃皟鐢ㄦ柟淇濇寔涓婁竴甯х殑 extent銆?*/
        private fun queryExtent(display: android.opengl.EGLDisplay, surface: EGLSurface, phase: String): RenderExtent? {
            var extent = rawExtent(display, surface)
            // 缁?setDefaultBufferSize 鏈€澶?~120ms 钀藉湴锛圱extureView 涓嬩竴甯ф墠鎹?buffer锛夛紝
            // 閬垮厤鎶娿€岃繕娌＄敓鏁堛€嶅啓鎴愩€岃澶囨斁涓嶄笅銆嶃€?
            var waits = 0
            while (waits < 4 && isPendingExtent(extent)) {
                try { Thread.sleep(30) } catch (_: InterruptedException) { break }
                waits++
                extent = rawExtent(display, surface)
            }
            if (holdPendingExtent(extent, android.os.SystemClock.elapsedRealtime(), phase)) return null
            adoptExtent(extent, phase)
            return extent
        }
        override fun run() {
            try { synchronized(nativeLock) {
                if (!running || owner.retired) return
                val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                var surface: EGLSurface = EGL14.EGL_NO_SURFACE
                var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
                var current = false
                var window: Surface? = null
                val performanceToken = performanceOwner.incrementAndGet()
                var jsRuntime: SoullinkJsEngine? = null
                try {
                    check(EGL14.eglInitialize(display, IntArray(1), 0, IntArray(1), 0)) { "EGL initialize failed" }
                    val configurations = arrayOfNulls<EGLConfig>(1)
                    val count = IntArray(1)
                    val attributes = intArrayOf(
                        EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                        EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE)
                    check(EGL14.eglChooseConfig(display, attributes, 0, configurations, 0, 1, count, 0) && count[0] > 0) { "No EGL window config" }
                    window = Surface(owner.texture)
                    surface = EGL14.eglCreateWindowSurface(display, configurations[0], window,
                        intArrayOf(EGL14.EGL_NONE), 0)
                    check(surface != EGL14.EGL_NO_SURFACE) { "Window surface creation failed" }
                    eglContext = EGL14.eglCreateContext(display, configurations[0], EGL14.EGL_NO_CONTEXT,
                        intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
                    check(eglContext != EGL14.EGL_NO_CONTEXT && EGL14.eglMakeCurrent(display, surface, surface, eglContext)) { "EGL context failed" }
                    current = true
                    var drawingExtent = queryExtent(display, surface, "created")
                        ?: RenderExtent(canvasQuality.width, canvasQuality.height)
                    EGL14.eglSwapInterval(display, 1)
                    nativeInit(ensureShaders().absolutePath)
                    loadModel()
                    latestJsParameters.set(null)
                    jsRuntime = createPerformanceEngine(performanceToken)
                    if (!running || owner.retired) return
                    isModelReady = true
                    autoRetryAttempts.set(0)
                    Log.i(TAG, "ready model=$modelDirName render=${drawingExtent.width}x${drawingExtent.height} quality=${canvasQuality.key} requested=${canvasQuality.width}x${canvasQuality.height} mode=direct-gpu activeFps=20 idleFps=12 readback=false")
                    DiagLog.write(LOG_FILE, "ready model=$modelDirName render=${drawingExtent.width}x${drawingExtent.height} resets=${resetCount.get()}")
                    // created 闃舵鍙兘宸茬粡璁拌繃涓€娆?pending 璧风畻鐐癸紙閭ｆ鏃堕棿鑺卞湪 nativeInit/loadModel 涓婏級锛?
                    // 杩涘惊鐜墠娓呴浂锛岃銆岀┖鏀剧瓑 buffer銆嶄粠鐪熸寮€濮嬬敾鐨勯偅涓€甯ц鏃讹紝鍚﹀垯涓€杩涘惊鐜氨瓒呮椂鏀惧純銆?
                    pendingSinceMs = 0L
                    pendingCommitted = false
                    var previousTime = System.nanoTime()
                    var statsAt = previousTime
                    var frames = 0L
                    var workNanos = 0L
                    var swapNanos = 0L
                    var performanceSampleAt = 0L
                    var touchSampleAt = 0L
                    while (running && !owner.retired) {
                        if (renderPaused && !owner.retired) {
                            // Idle without tearing down EGL/model/JS; teardown on screen-off kills the call socket.
                            try { sleep(150) } catch (_: InterruptedException) { if (!running) break }
                            continue
                        }
                        // 2026-11-18锛氭瘡甯у涓€娆?surface 鐨勭湡瀹炲昂瀵革紙eglQuerySurface锛屾棤鍚屾寮€閿€锛夈€?
                        // 寮归敭鐩?/ 鍒囨潈闄愰〉鍚?buffer 鏄紓姝ユ崲鐨勶紝鏃ч€昏緫闈?1 绉掍竴娆＄殑 probe 鑷剤锛涚幇鍦?
                        // 灏哄涓€鍙樺氨褰撳満鍒ゆ柇锛歱ending锛堥粯璁?buffer 涓斿皬浜庤姹傚€硷級鈫?杩欎竴甯х┖鏀句笉鐢伙紝
                        // 绛?TextureView 鎹笂璇锋眰灏哄鐨?buffer锛沺ending 瓒呮椂 鈫?鍏滃簳 adopt銆?
                        val nowMs = android.os.SystemClock.elapsedRealtime()
                        var pendingHold = false
                        val actual = try { rawExtent(display, surface) } catch (_: Throwable) { null }
                        if (actual != null) {
                            if (actual != drawingExtent) {
                                val probed = actual
                                if (holdPendingExtent(probed, nowMs, "probe")) {
                                    pendingHold = true
                                } else {
                                    if (isPendingExtent(probed)) requestRequestedBuffer(nowMs)
                                    adoptExtent(probed, "probe")
                                    drawingExtent = probed
                                }
                            } else if (pendingSinceMs != 0L) {
                                // buffer 宸茬粡鎹㈡垚鎴戜滑姝ｅ湪鐢荤殑灏哄锛氭竻鎺?pending 璁℃椂
                                pendingSinceMs = 0L
                                pendingCommitted = false
                            }
                        }
                        val started = System.nanoTime()
                        val dt = ((started - previousTime) / 1e9).toFloat().coerceIn(0f, .1f)
                        previousTime = started
                        if (pendingMotionReset.getAndSet(false)) nativeResetMotionState()
                        presentation.get().let { nativeSetPresentationTransform(it.scale, it.offsetX, it.offsetY) }
                        pendingExpression.getAndSet(null)?.let { value ->
                            val name = resolveExpression(value)
                            if (name.isEmpty()) {
                                manualPerformanceFace.set(false)
                                nativeClearExpression()
                                Log.i(TAG, "expression cleared: model=$modelDirName request=$value")
                            } else {
                                manualPerformanceFace.set(true)
                                nativeSetExpression(name)
                            }
                        }
                        pendingMotion.getAndSet(null)?.let { name ->
                            // 2026-11-23 owner: walk / legprobe branches removed with the leg gestures.
                            if (name in motionNames) {
                                nativePlayMotion(name)
                                probeUntil = android.os.SystemClock.uptimeMillis() + 2200
                                probeNext = 0L
                            } else {
                                Log.w(TAG, "motion ignored, not registered: model=$modelDirName name=$name")
                            }
                        }
                        val probeNow = android.os.SystemClock.uptimeMillis()
                        if (probeNow < probeUntil && probeNow >= probeNext) {
                            DiagLog.write("motion.log", "params ${nativeProbeParams()}")
                            probeNext = probeNow + 300
                        }
                        pendingArmPose.getAndSet(null)?.let { nativeRequestArmPose(it) }
                        pendingDrag.getAndSet(null)?.let { nativeSetDrag(it.first, it.second) }
                        if (continuousEmotionAvailable) emotionState.get().let {
                            nativeSetEmotionWeights(it.happy, it.sad, it.angry, it.surprised, it.shy)
                        }
                        nativeSetMouth(mouth)
                        jsRuntime?.offerFrame(JSONObject().put("nowMs",android.os.SystemClock.elapsedRealtime())
                            .put("dtMs",dt*1000.0).put("mouthLevel",mouth)
                            .put("speaking",performanceSpeaking).put("manualFace",manualPerformanceFace.get())
                            .put("armBusy",isArmPoseBusy))
                        latestJsParameters.getAndSet(null)?.let{nativeSetJsParameters(it)}
                        currentArmState = when (nativeUpdate(dt)) {
                            1 -> "raising"
                            2 -> "holding"
                            3 -> "lowering"
                            else -> "down"
                        }
                        val sampleNow=android.os.SystemClock.elapsedRealtime()
                        if(sampleNow>=performanceSampleAt) {
                            nativePerformance.set(nativeJsDiagnostics())
                            performanceSampleAt=sampleNow+1000
                        }
                        if (!pendingHold) {
                            check(nativeDrawSurface(drawingExtent.width, drawingExtent.height)) { "Live2D surface draw failed" }
                            if (!running || owner.retired) break
                            val drawnAt = System.nanoTime()
                            check(EGL14.eglSwapBuffers(display, surface)) { "Live2D surface swap failed" }
                            if (sampleNow >= touchSampleAt && modelDirName in setOf("whale", "ellen")) {
                                nativeTouchBounds(drawingExtent.width, drawingExtent.height)?.let { bounds ->
                                    touchBounds.set(TouchBounds(owner, drawingExtent, sampleNow, bounds))
                                }
                                touchSampleAt = sampleNow + 250L
                            }
                            presentedFrames.incrementAndGet()
                            val swappedAt = System.nanoTime()
                            frames++; workNanos += drawnAt - started; swapNanos += swappedAt - drawnAt
                            if (swappedAt - statsAt >= 10_000_000_000L && frames > 0) {
                                val fps = frames * 1_000_000_000.0 / (swappedAt - statsAt)
                                Log.i(TAG, "render stats mode=direct-gpu frames=$frames fps=${String.format(Locale.ROOT, "%.1f", fps)} work_ms=${workNanos / frames / 1_000_000} swap_ms=${swapNanos / frames / 1_000_000} bitmap_frames=0 readback_frames=0")
                                statsAt = swappedAt; frames = 0; workNanos = 0; swapNanos = 0
                            }
                        }
                        // pendingHold锛氳繖涓€甯т笉鐢讳笉 swap 鈥斺€?鐢讳簡灏辨槸鎶婃寜 view 灏哄箙缁樺埗鐨勬媺浼稿抚鎹笂鍙伴潰銆?
                        // TextureView 缁х画鏄剧ず涓婁竴甯э紝绛?buffer 鎹㈡垚璇锋眰灏哄锛堟垨 PENDING_HOLD_MS 鍏滃簳锛夊啀鎭㈠銆?
                        val frameMs = if (started < activeUntilNanos.get() || currentArmState != "down") ACTIVE_FRAME_MS else IDLE_FRAME_MS
                        val delay = frameMs - (System.nanoTime() - started) / 1_000_000
                        if (delay > 0) try { sleep(delay) } catch (_: InterruptedException) { if (!running) break }
                    }
                } catch (error: Throwable) {
                    if (running && !owner.retired) {
                        wantsRendering = false // Explicit retry only after a real producer/render failure.
                        lastRenderFailure = error.message ?: error.javaClass.simpleName
                        Log.e(TAG, "model=$modelDirName stopped: ${error.message}", error)
                        DiagLog.write(LOG_FILE, "fail model=$modelDirName presented=${presentedFrames.get()} failure=$lastRenderFailure")
                        val attempt = autoRetryAttempts.getAndIncrement()
                        if (attempt < AUTO_RETRY_LIMIT) {
                            val message = lastRenderFailure
                            val resetsAtFailure = resetCount.get()
                            post {
                                // A manual reset already took over: let its own retry loop own the restart.
                                if (resetCount.get() != resetsAtFailure) return@post
                                DiagLog.write(LOG_FILE, "schedule auto-retry model=$modelDirName attempt=$attempt failure=$message")
                                autoRetryWaits.set(0)
                                maintenanceHandler.removeCallbacks(autoRetryTick)
                                maintenanceHandler.postDelayed(autoRetryTick, AUTO_RETRY_DELAY_MS)
                            }
                        } else {
                            notifyRenderFailure(lastRenderFailure ?: "unknown")
                        }
                    }
                } finally {
                    if(performanceOwner.compareAndSet(performanceToken,performanceToken+1)) {
                        performanceEngine=null
                        latestJsParameters.set(null)
                        performanceState.set("{\"state\":\"stopped\",\"jsActive\":false}")
                        nativePerformance.set("{\"active\":false}")
                    }
                    jsRuntime?.close()
                    isModelReady = false
                    if (current) nativeDispose()
                    if (display != EGL14.EGL_NO_DISPLAY) {
                        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
                        EGL14.eglTerminate(display)
                    }
                    EGL14.eglReleaseThread()
                    window?.release()
                }
            } } finally {
                owner.releaseProducer()
                post {
                        if (renderThread === this@RenderThread) {
                            renderThread = null
                            if (wantsRendering && isAttachedToWindow && surfaceOwner != null) startRender()
                        }
                    }
            }
        }
    }

    private class ModelFiles(val diskRoot: File?, val assetRoot: String, val context: Context) {
        fun open(relative: String): InputStream {
            require(!relative.startsWith('/') && relative.split('/').none { it == ".." }) { "Invalid model path" }
            return if (diskRoot != null) File(diskRoot, relative).inputStream() else context.assets.open("$assetRoot/$relative")
        }
        fun bytes(relative: String): ByteArray = open(relative).use { it.readBytes() }
    }

    private fun loadModel() {
        val disk = File(context.filesDir, "live2d/$modelDirName")
        val modelFile = File(disk, "model.model3.json").takeIf { it.isFile }
            ?: disk.listFiles()?.firstOrNull { it.name.endsWith(".model3.json") }
        val assetRoot = "live2d/$modelDirName"
        val modelName = modelFile?.name ?: context.assets.list(assetRoot)?.firstOrNull { it.endsWith(".model3.json") }
            ?: error("No model3 configuration for $modelDirName")
        val files = ModelFiles(if (modelFile != null) disk else null, assetRoot, context)
        val modelBytes = files.bytes(modelName)
        val references = JSONObject(String(modelBytes, Charsets.UTF_8)).getJSONObject("FileReferences")
        val physics = references.optString("Physics").takeIf { it.isNotBlank() }?.let { files.bytes(it) } ?: ByteArray(0)
        val profile = File(disk, "profile.json").takeIf { modelFile != null && it.isFile }?.let { JSONObject(it.readText()) }
        val hidden = profile?.optString("watermarkPartId", "") ?: if (modelDirName == "ellen") "Part17" else ""
        check(nativeSetup(files.bytes(references.getString("Moc")), modelBytes, physics, hidden)) { "Native setup failed" }
        if (modelDirName in setOf("whale", "ellen")) nativeConfigureTouchRegions(modelDirName == "whale")
        val callRigDescription = nativePrepareCallGestureRig(modelDirName == "whale")
        val actualCallRig = callRigDescription.takeIf { it.isNotBlank() }?.let(::JSONObject)
        val callRig = actualCallRig?.takeIf { modelDirName == "whale" }
        val textures = references.getJSONArray("Textures")
        val glMaximum = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, glMaximum, 0)
        maximumTextureSize = glMaximum[0]
        val textureLimit = CanvasRenderPolicy.textureLimit(maximumTextureSize)
        decodedTextureBytes = 0L
        Log.i(TAG, "texture limit model=$modelDirName requestedCap=4096 effectiveCap=$textureLimit glMaximum=$maximumTextureSize")
        var uploadBuffer: ByteBuffer? = null
        for (index in 0 until textures.length()) {
            val path = textures.getString(index)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            files.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
            check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Texture $index unreadable: $path" }
            val sample = CanvasRenderPolicy.textureSample(bounds.outWidth, bounds.outHeight, maximumTextureSize)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample; inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888; inPremultiplied = true
            }
            val bitmap = files.open(path).use { BitmapFactory.decodeStream(it, null, options) }
                ?: error("Texture $index decode failed: $path")
            try {
                check(bitmap.width <= textureLimit && bitmap.height <= textureLimit) { "Decoded texture exceeds the verified OpenGL texture limit" }
                decodedTextureBytes += bitmap.byteCount.toLong()
                Log.i(TAG, "texture decode model=$modelDirName index=$index source=${bounds.outWidth}x${bounds.outHeight} sample=$sample decoded=${bitmap.width}x${bitmap.height} bytes=${bitmap.byteCount} effectiveCap=$textureLimit")
                if ((uploadBuffer?.capacity() ?: 0) < bitmap.byteCount) {
                    uploadBuffer = ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.nativeOrder())
                }
                val pixels = uploadBuffer!!
                pixels.clear()
                bitmap.copyPixelsToBuffer(pixels); pixels.rewind()
                check(nativeBindTexture(index, pixels, bitmap.width, bitmap.height)) { "Texture upload failed index=$index" }
            } finally { bitmap.recycle() }
        }
        Log.i(TAG, "texture upload complete model=$modelDirName count=${textures.length()} decodedBytes=$decodedTextureBytes reusableUploadBufferBytes=${uploadBuffer?.capacity() ?: 0}")
        uploadBuffer = null // No Java pixel array collection is retained after sequential upload.
        val expressions = references.optJSONArray("Expressions")
        val names = ArrayList<String>()
        var reviewedFaceMask = 0
        for (index in 0 until (expressions?.length() ?: 0)) {
            val item = expressions!!.getJSONObject(index)
            val name = item.getString("Name")
            val data = files.bytes(item.getString("File"))
            nativeAddExpressionFile(name, data); names.add(name)
            if (modelDirName == "whale") reviewedFaceMask = reviewedFaceMask or reviewedFaceOverlay(name, data)
        }
        expressionNames = names
        loadEmotionAliases(disk.takeIf { modelFile != null }, assetRoot)
        continuousEmotionAvailable = nativeConfigureContinuousFace(modelDirName == "whale", reviewedFaceMask)
        if (modelDirName == "ellen" && continuousEmotionAvailable) {
            // Native registers these recipes only after validating the actual rig parameter ranges.
            val emotions = listOf("happy", "sad", "angry", "surprised", "thinking", "shy")
            expressionNames = (expressionNames + emotions.map { "app-$it" }).distinct()
            emotionAliases = emotionAliases + emotions.associateWith { "app-$it" }
            Log.i(TAG, "Ellen dynamic facial expressions enabled: ${emotions.joinToString { "app-$it" }}")
        }
        var motionCount = 0
        val loadedMotions = linkedSetOf<String>()
        val manualCandidates = mutableMapOf<String, ByteArray>()
        // 2026-11-23 owner: 手/腿动作永久删除（以后不加回）——arm pair、右臂手势、pose 保持姿势、
        // walk/legprobe 都不再注册。下面的循环只收 Manual 组给 touch 表演用。
        references.optJSONObject("Motions")?.let { groups ->
            for (group in groups.keys()) {
                val entries = groups.getJSONArray(group)
                for (index in 0 until entries.length()) {
                    val item = entries.getJSONObject(index)
                    val path = item.getString("File")
                    val name = item.optString("Name").ifBlank { path.substringAfterLast('/').removeSuffix(".motion3.json") }
                    val data = files.bytes(path)
                    if (modelDirName == "whale" && group.equals("Manual", ignoreCase = true))
                        manualCandidates[path.substringAfterLast('/')] = data
                    if (nativeAddMotionFile(name, data, group.equals("Idle", ignoreCase = true))) {
                        loadedMotions.add(name); motionCount++
                    }
                }
            }
        }
        // Shared gestures are opt-in, never Idle; imported rigs keep only their own motions.
        trainedCallMotionCount = 0
        val trainedCallMotions = loadTrainedCallMotions(actualCallRig)
        if (modelDirName == "ellen" || modelDirName == "whale" && callRig != null) {
            for (gesture in callGestures) {
                for (suffix in listOf("", "-wide")) {
                    val id = gesture.id + suffix
                    val asset = "call-motions/${id.substringAfter(':')}.motion3.json"
                    try {
                        val trained = trainedCallMotions[id]
                        val data = trained ?: context.assets.open(asset).use { it.readBytes() }.let { original ->
                            if (callRig != null) remapCallGesture(original, callRig, id) else original
                        }
                        check(nativeAddMotionFile(id, data, false)) { "Native SDK rejected application motion $id" }
                        loadedMotions.add(id)
                        motionCount++
                        if (trained != null) trainedCallMotionCount++
                        DiagLog.write("motion.log", "register id=$id ok=true trained=${trained != null} " +
                            "sdk_duration=${nativeProbeMotion(data)}")
                    } catch (error: Exception) {
                        DiagLog.write("motion.log", "register id=$id ok=false error=${error.javaClass.simpleName}: ${error.message}")
                        Log.w(TAG, "call gesture unavailable: model=$modelDirName gesture=$id", error)
                    }
                }
            }
        }
        touchPerformanceMillis = 0L
        if (modelDirName == "whale") {
            touchPerformanceMillis = registerTouchPerformance(manualCandidates, loadedMotions)
        }
        motionNames = loadedMotions
        if (trainedCallMotionCount > 0) Log.i(TAG, "trained rig motions registered model=$modelDirName count=$trainedCallMotionCount remap=false runtime=baked-curves")
        Log.i(TAG, "resources model=$modelDirName textures=${textures.length()} expressions=${expressionNames.joinToString()} motions=$motionCount hiddenPart=$hidden")
    }

    /** Only built-in identities, plus exact loaded-rig ID/range agreement; imported rigs never opt in. */
    private fun loadTrainedCallMotions(actualRig: JSONObject?): Map<String, ByteArray> {
        if (modelDirName !in setOf("ellen", "whale")) return emptyMap()
        return try {
            check(actualRig != null) { "No actual rig ranges" }
            val manifest = context.assets.open("trained-rigs/manifest.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            check(manifest.getString("schema") == "dsh-trained-rigs-1") { "Unknown trained rig schema" }
            val model = manifest.getJSONObject("models").getJSONObject(modelDirName)
            val expected = model.getJSONObject("channels")
            check(expected.length() == 6 && actualRig.length() == 6) { "Incomplete trained rig channels" }
            val ranges = mutableMapOf<String, JSONObject>()
            for (source in expected.keys()) {
                val wanted = expected.getJSONObject(source)
                val loaded = actualRig.getJSONObject(source)
                val id = wanted.getString("id")
                check(id == loaded.getString("id")) { "Trained rig parameter mismatch: $source" }
                for (field in listOf("minimum", "default", "maximum")) {
                    val value = loaded.getDouble(field)
                    check(value.isFinite() && kotlin.math.abs(value - wanted.getDouble(field)) <= .0001) { "Trained rig range mismatch: $source/$field" }
                }
                check(loaded.getDouble("minimum") <= loaded.getDouble("default") && loaded.getDouble("default") <= loaded.getDouble("maximum") && loaded.getDouble("minimum") < loaded.getDouble("maximum"))
                ranges[id] = loaded
            }
            val motions = model.getJSONObject("motions")
            val result = linkedMapOf<String, ByteArray>()
            for (gesture in callGestures) for (suffix in listOf("", "-wide")) {
                val id = gesture.id + suffix
                val entry = motions.getJSONObject(id)
                val path = "$modelDirName/${id.substringAfter(':')}.motion3.json"
                check(entry.getString("file") == path) { "Trained rig asset identity mismatch" }
                val data = context.assets.open("trained-rigs/$path").use { it.readBytes() }
                check(data.size.toLong() == entry.getLong("bytes")) { "Trained rig asset size mismatch" }
                validateTrainedCallMotion(data, entry, ranges, gesture.durationMillis)
                result[id] = data
            }
            Log.i(TAG, "trained rig contract verified model=$modelDirName revision=${manifest.getString("revision")} channels=${ranges.keys.joinToString()} motions=${result.size}")
            result
        } catch (error: Exception) {
            Log.w(TAG, "trained rig unavailable: model=$modelDirName; keeping prior gesture policy", error)
            emptyMap()
        }
    }

    private fun validateTrainedCallMotion(data: ByteArray, entry: JSONObject, ranges: Map<String, JSONObject>, durationMillis: Long) {
        val document = JSONObject(String(data, Charsets.UTF_8))
        val meta = document.getJSONObject("Meta")
        val duration = meta.getDouble("Duration")
        check(document.getInt("Version") == 3 && duration.isFinite() && kotlin.math.abs(duration * 1000 - durationMillis) < .01 && !meta.getBoolean("Loop"))
        val expected = entry.getJSONArray("parameter_ids").let { ids -> (0 until ids.length()).map { ids.getString(it) }.toSet() }
        val curves = document.getJSONArray("Curves")
        check(curves.length() == expected.size && meta.getInt("CurveCount") == curves.length())
        val seen = mutableSetOf<String>()
        var totalSegments = 0
        var totalPoints = 0
        for (index in 0 until curves.length()) {
            val curve = curves.getJSONObject(index)
            val id = curve.getString("Id")
            check(curve.getString("Target") == "Parameter" && id in expected && seen.add(id))
            val range = ranges.getValue(id)
            val segments = curve.getJSONArray("Segments")
            // Cubism linear segments are [t0, v0, 1, t1,v1, t2,v2, t3,v3, ...]: the type
            // marker 1 plus three points. Segment type 0 is Bezier, not linear.
            check(segments.length() >= 7 && (segments.length() - 2) % 7 == 0 && segments.getDouble(0) == 0.0)
            val count = (segments.length() - 2) / 7
            var previousTime = 0.0
            val initial = segments.getDouble(1)
            check(initial.isFinite() && initial >= range.getDouble("minimum") - .000001 && initial <= range.getDouble("maximum") + .000001)
            for (segmentIndex in 0 until count) {
                val base = 2 + 7 * segmentIndex
                check(segments.getInt(base) == 1) { "Trained rig requires linear segments" }
                for (point in 0..2) {
                    val timeIndex = base + 1 + 2 * point
                    val time = segments.getDouble(timeIndex)
                    val value = segments.getDouble(timeIndex + 1)
                    check(time.isFinite() && time >= previousTime && time <= duration + .000001)
                    check(value.isFinite() && value >= range.getDouble("minimum") - .000001 && value <= range.getDouble("maximum") + .000001)
                    previousTime = time
                }
            }
            check(kotlin.math.abs(previousTime - duration) < .000001)
            totalSegments += count
            totalPoints += 1 + 3 * count
        }
        check(seen == expected && meta.getInt("TotalSegmentCount") == totalSegments && meta.getInt("TotalPointCount") == totalPoints)
    }


    /** The author's own 13s `Manual` performance (鑴辨崟): touch-only, one shot, loop/fades stripped.
     *  It drives the same rig drivers a call gesture does, so the caller must gate on idle. */
    private fun registerTouchPerformance(manual: Map<String, ByteArray>, loaded: MutableSet<String>): Long {
        val original = manual[TOUCH_PERFORMANCE_FILE] ?: return 0L
        return try {
            val document = JSONObject(String(original, Charsets.UTF_8))
            val meta = document.getJSONObject("Meta")
            val duration = meta.getDouble("Duration")
            check(duration > 0 && duration <= 20 && meta.optInt("UserDataCount") == 0) { "performance shape changed" }
            meta.put("Loop", false)
            meta.put("FadeInTime", 0.0)
            meta.put("FadeOutTime", 0.0)
            val data = (document.toString(2) + "\n").toByteArray(Charsets.UTF_8)
            check(nativeAddMotionFile(TOUCH_PERFORMANCE_ID, data, false)) { "Native SDK rejected performance" }
            loaded.add(TOUCH_PERFORMANCE_ID)
            val millis = Math.round(duration * 1000)
            Log.i(TAG, "touch performance registered: id=$TOUCH_PERFORMANCE_ID duration=${millis}ms " +
                "curves=${document.getJSONArray("Curves").length()}")
            millis
        } catch (error: Exception) {
            Log.w(TAG, "touch performance unavailable", error)
            0L
        }
    }

    /** Deliberate touch only: never part of the chat vocabulary or the action panel. */
    fun playTouchPerformance(): Boolean {
        if (TOUCH_PERFORMANCE_ID !in motionNames) return false
        if (currentArmState != "down" || isArmPoseBusy) return false
        pendingMotion.set(TOUCH_PERFORMANCE_ID)
        animateFor(touchPerformanceMillis + 1500L)
        return true
    }
    val touchPerformanceAvailable: Boolean get() = touchPerformanceMillis > 0L


    /** Only the four audited facial effects, never props / clothing / loading / heart-eye toggles. */
    private fun reviewedFaceOverlay(name: String, data: ByteArray): Int {
        if (name !in setOf("锛烇紲", "娴佹唱", "榛戣劯", "鑴哥孩")) return 0
        return try {
            val parameters = JSONObject(String(data, Charsets.UTF_8)).getJSONArray("Parameters")
            val values = (0 until parameters.length()).map { index ->
                val parameter = parameters.getJSONObject(index)
                FacialEffectParameter(parameter.getString("Id"), parameter.getDouble("Value"), parameter.optString("Blend", "Add"))
            }
            PresentationPolicy.reviewedFaceOverlay(name, values)
        } catch (error: Exception) {
            Log.w(TAG, "automatic facial effect rejected: model=$modelDirName name=$name")
            0
        }
    }

    private fun remapCallGesture(original: ByteArray, rig: JSONObject, gestureId: String): ByteArray {
        val document = JSONObject(String(original, Charsets.UTF_8))
        val curves = document.getJSONArray("Curves")
        for (index in 0 until curves.length()) {
            val curve = curves.getJSONObject(index)
            check(curve.getString("Target") == "Parameter")
            val sourceId = curve.getString("Id")
            val channel = rig.getJSONObject(sourceId)
            val minimum = channel.getDouble("minimum")
            val center = channel.getDouble("default")
            val maximum = channel.getDouble("maximum")
            val negativeSource = channel.getDouble("sourceDefault") - channel.getDouble("sourceMinimum")
            val positiveSource = channel.getDouble("sourceMaximum") - channel.getDouble("sourceDefault")
            check(minimum <= center && center <= maximum && minimum < maximum)
            val segments = curve.getJSONArray("Segments")
            check((segments.length() - 2) % 7 == 0)
            var curvePeak = kotlin.math.abs(segments.getDouble(1))
            for (point in 2 until segments.length()) {
                if ((point - 2) % 7 in listOf(2, 4, 6))
                    curvePeak = maxOf(curvePeak, kotlin.math.abs(segments.getDouble(point)))
            }
            check(curvePeak > 0)
            var mappedPeak = 0.0
            for (point in 0 until segments.length()) {
                // Application motions contain only cubic marker + three time/value pairs.
                if (point == 1 || point >= 2 && (point - 2) % 7 in listOf(2, 4, 6)) {
                    val value = segments.getDouble(point)
                    check(value.isFinite())
                    if (value == 0.0) segments.put(point, center)
                    else {
                        val sourceSpan = if (value > 0) positiveSource else negativeSource
                        val targetSpan = if (value > 0) maximum - center else center - minimum
                        check(sourceSpan > 0 && targetSpan > 0) {
                            "Gesture direction unavailable: $sourceId -> ${channel.getString("id")}"
                        }
                        // The SDK numeric parser accepts plain decimals, not exponent notation.
                        val mapped = PresentationPolicy.mapGestureValue(value, curvePeak, minimum, center, maximum, gestureId, sourceId)
                        mappedPeak = maxOf(mappedPeak, kotlin.math.abs(mapped))
                        segments.put(point, BigDecimal.valueOf(mapped).setScale(6, RoundingMode.HALF_UP))
                    }
                } else if (point >= 2 && (point - 2) % 7 == 0) check(segments.getInt(point) == 1)
            }
            curve.put("Id", channel.getString("id"))
            DiagLog.write("motion.log", "map gesture=$gestureId out=$sourceId driver=${channel.getString("id")} " +
                "range=$minimum/$center/$maximum peak=$curvePeak mapped_peak=$mappedPeak")
            Log.i(TAG, "gesture curve mapped: model=$modelDirName gesture=$gestureId output=$sourceId driver=${channel.getString("id")} peak_fraction=${PresentationPolicy.gesturePeakFraction(gestureId, sourceId)}")
        }
        // Cubism 5-r.4 ParseNumeric terminates at a comma/newline, not a closing ]/}.
        // Keep one value per line; JSONObject's compact form fails on the last array value.
        return (document.toString(2) + "\n").toByteArray(Charsets.UTF_8)
    }

    private fun loadEmotionAliases(disk: File?, assetRoot: String) {
        // A model-local file may override packaged mappings for imported versions.
        // The model3 list is authoritative: no arbitrary paths or expression indices.
        val supplied = try {
            val file = disk?.let { File(it, "emotion_aliases.json") }?.takeIf { it.isFile }
            val text = file?.readText() ?: context.assets.open("$assetRoot/emotion_aliases.json")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONObject(text)
        } catch (_: java.io.FileNotFoundException) { null }
          catch (error: Exception) {
            Log.w(TAG, "emotion aliases unavailable: model=$modelDirName", error); null
        }
        val blocked = mutableSetOf<String>()
        // These two resources are explicit toggles, never an initialization step.
        if (modelDirName == "ellen") blocked.add("shuiyin")
        if (modelDirName == "whale") blocked.add("鍔犺浇")
        supplied?.optJSONArray("blocked_expressions")?.let { list ->
            for (index in 0 until list.length()) blocked.add(list.getString(index))
        }
        blockedExpressions = blocked
        val aliases = supplied?.optJSONObject("aliases")
        val resolved = linkedMapOf<String, String>()
        for (tag in listOf("neutral", "happy", "sad", "angry", "surprised", "thinking", "shy")) {
            val name = aliases?.optString(tag, "") ?: ""
            resolved[tag] = if (name.isEmpty() || name in expressionNames && name !in blocked) name else {
                Log.w(TAG, "emotion alias reset to neutral: model=$modelDirName tag=$tag missing=$name"); ""
            }
        }
        resolved["neutral"] = ""
        emotionAliases = resolved
        Log.i(TAG, "emotion aliases model=$modelDirName supported=${resolved.filterValues { it.isNotEmpty() }}")
    }

    private fun resolveExpression(request: String): String {
        val value = request.trim()
        if (value.isEmpty()) return ""
        if (value.startsWith("face:", ignoreCase = true)) {
            val name = value.substringAfter(':').trim()
            return if (name in expressionNames && name !in blockedExpressions) name else {
                Log.w(TAG, "unknown face expression ignored: $name"); ""
            }
        }
        if (value.startsWith("mobile:", ignoreCase = true)) {
            val incoming = value.substringAfter(':').lowercase(Locale.ROOT)
            val tag = when (incoming) {
                "joy", "smirk" -> "happy"
                "sadness" -> "sad"
                "anger", "disgust" -> "angry"
                "surprise", "fear" -> "surprised"
                else -> incoming
            }
            if (tag !in emotionAliases) Log.w(TAG, "unknown mobile emotion reset: $incoming")
            val name = emotionAliases[tag] ?: ""
            Log.i(TAG, "emotion mapped: model=$modelDirName tag=$tag expression=${name.ifEmpty { "neutral" }}")
            return name
        }
        // Legacy indices are only resolved inside the selected model's own ordered list.
        val index = value.toIntOrNull()
        val name = if (index != null) expressionNames.getOrNull(index) else value
        if (name == null || name !in expressionNames || name in blockedExpressions) {
            Log.w(TAG, "expression reset, invalid or blocked: model=$modelDirName request=$value")
            return ""
        }
        return name
    }

    private fun ensureShaders(): File {
        val directory = File(context.filesDir, "FrameworkShaders").apply { mkdirs() }
        val names = context.assets.list("shaders") ?: error("No Cubism shaders packaged")
        check(names.isNotEmpty()) { "Cubism shader directory empty" }
        for (name in names) context.assets.open("shaders/$name").use { input ->
            File(directory, name).outputStream().use { output -> input.copyTo(output) }
        }
        return directory
    }

    private external fun nativeInit(shaderDir: String)
    private external fun nativeSetup(moc: ByteArray, model3: ByteArray, physics: ByteArray, hiddenPart: String): Boolean
    private external fun nativeBindTexture(index: Int, rgba: ByteBuffer, width: Int, height: Int): Boolean
    private external fun nativeAddExpressionFile(name: String, data: ByteArray)
    private external fun nativeAddMotionFile(name: String, data: ByteArray, idle: Boolean): Boolean
    private external fun nativeSetExpression(name: String)
    private external fun nativeConfigureJsPerformance(names: Array<String>, bounds: FloatArray): Boolean
    private external fun nativeSetJsParameters(values: FloatArray): Boolean
    private external fun nativeJsDiagnostics(): String
    private external fun nativeClearExpression()
    private external fun nativeConfigureContinuousFace(whale: Boolean, reviewedOverlayMask: Int): Boolean
    private external fun nativeSetPresentationTransform(scale: Float, offsetX: Float, offsetY: Float)
    private external fun nativeSetEmotionWeights(happy: Float, sad: Float, angry: Float, surprised: Float, shy: Float)
    private external fun nativePrepareCallGestureRig(useWhale: Boolean): String
    private external fun nativeConfigureArmPoses(up: ByteArray, down: ByteArray): Boolean
    private external fun nativeRequestArmPose(raised: Boolean)
    private external fun nativeResetMotionState()
    private external fun nativePlayMotion(name: String)
    private external fun nativeProbeMotion(data: ByteArray): Float
    private external fun nativeProbeParams(): String
    private external fun nativePrepareWalk(): Boolean
    private external fun nativePlayWalk(): Boolean
    private external fun nativePlayLegProbe(): Boolean
    private external fun nativeSetDrag(x: Float, y: Float)
    private external fun nativeSetMouth(value: Float)
    private external fun nativeUpdate(delta: Float): Int
    private external fun nativeRenderFrame(pixels: ByteBuffer, width: Int, height: Int): Boolean
    private external fun nativeConfigureTouchRegions(whale: Boolean)
    private external fun nativeTouchBounds(width: Int, height: Int): FloatArray?
    private external fun nativeDrawSurface(width: Int, height: Int): Boolean
    private external fun nativeDispose()
}
