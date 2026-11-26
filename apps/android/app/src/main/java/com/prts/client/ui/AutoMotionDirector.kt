package com.prts.client.ui

import com.prts.client.audio.DiagLog
import kotlin.random.Random
import java.util.Locale

/** Main-thread conversation choreography. No network, microphone or unbounded timer. */
internal class AutoMotionDirector(
    private val clock: () -> Long,
    private val expressions: () -> Set<String>,
    private val gestures: () -> Set<String>,
    private val showEmotionWeights: (EmotionWeights) -> Unit,
    private val clearAutoEmotion: () -> Unit,
    private val releaseManualExpression: () -> Unit,
    private val playGesture: (String) -> Boolean,
    private val playTouch: (String) -> Boolean = { false },
    private val report: (String) -> Unit = {},
    private val emotionReport: (String) -> Unit = {},
    private val randomBetween: (Int, Int) -> Int = { from, until -> Random.nextInt(from, until) },
    private val emotionRuntime: EmotionRuntime = EmotionRuntime(clock),
    private val onPerformanceIntent: (String, EmotionSignal?) -> Unit = { _, _ -> },
) {
    data class Cue(val expression: String? = null, val gesture: String? = null, val replyId: String? = null,
        val emotionIntent: EmotionSignal? = null)

    var enabled = true
        private set
    private var foreground = false
    private var turnActive = false
    private var turnId: String? = null
    private var turnGesturePlayed = false
    /** Per-reply motion budget; a reply may span several sentence clips. */
    private var replyGestureCount = 0
    private var explicitUserGesture: String? = null
    private var lastGestureAt = -60_000L
    private var lastTouchAt = -60_000L
    private var lastTapAt = -60_000L
    private var lastTapRegion = ""
    private var rapidUntil = -60_000L
    private var lastVoiceClip: String? = null
    private val recentTaps = java.util.ArrayDeque<Long>()
    private var manualUntil = 0L
    private var manualExpressionActive = false
    private var localTurnSequence = 0L
    private var localEmotionKey = "legacy-turn:0"
    private var nextEmotionLogAt = 0.0
    private var profileReported = false
    private val repliesWithMotion = linkedSetOf<String>()
    private val stagedMotions = linkedMapOf<String, String>()
    private val stagedIntents = linkedMapOf<String, EmotionSignal>()
    private var networkReplyId: String? = null
    private val retiredNetworkReplies = linkedSetOf<String>()

    fun setEnabled(value: Boolean) {
        if (!profileReported) {
            val profile = emotionRuntime.policy()
            emotionReport(String.format(Locale.ROOT,
                "profile configured reactivity=%.4f approach_rate=%.4f half_life_s=%.3f decay_rate=%.6f hold_base_s=6 hold_scale_s=%.2f confidence_min=%.2f baseline=%.4f,%.4f,%.4f ambient=%.4f step_s=0.05",
                profile.reactivity, profile.approachRate, kotlin.math.ln(2.0) / profile.decayRate,
                profile.decayRate, profile.holdScaleSeconds, EmotionRuntime.CONFIDENCE_MINIMUM,
                profile.baseline.valence, profile.baseline.arousal, profile.baseline.dominance, profile.ambientStrength))
            profileReported = true
        }
        enabled = value
        if (!value) { cancelReply(); emotionRuntime.pause(); clearAutoEmotion(); report("automatic disabled") }
        else { if (foreground) emotionRuntime.resume(); report("automatic enabled") }
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (value) { if (enabled) emotionRuntime.resume() }
        else {
            cancelReply(); emotionRuntime.pause(); manualUntil = 0L
            if (manualExpressionActive) releaseManualExpression()
            manualExpressionActive = false
            clearAutoEmotion()
        }
    }

    /** Kept for input hooks; continuous natural motion is owned by the renderer. */
    fun userInteraction() = Unit

    /** Explicit character touch: a region from the renderer's projected head/torso boxes plus the
     * local tap rhythm (single / double / hold / repeated). It is intentionally independent of the
     * automatic-motion switch: the call screen owns its own touch toggle, and a deliberate pet is not
     * background behaviour. Returns the applied reaction so the UI can match its haptics. */
    fun onCharacterTouch(region: String, held: Boolean = false): CharacterTouchPolicy.Reaction? {
        val now = clock()
        // 2026-11-17 修：manualUntil 是「自动动作让位」窗口（打开面板/拖过模型后 15s），以前连主人
        // 自己点模型也一起吞掉——真机 touch_debug.log 里连续 33 条 `director touch ignored ... manual=true`，
        // 主人看到的就是「触屏互动没反应」。手动点击是主人的意图，必须优先于自动动作，所以这里只看前台。
        if (!foreground) {
            report("touch ignored region=$region foreground=false")
            return null
        }
        if (now - lastTouchAt < if (held) HOLD_GAP_MS else TAP_GAP_MS) {
            report("touch gap region=$region dt=${now - lastTouchAt}")
            return null
        }
        val kind = if (held) "hold" else classifyTap(now, region) ?: run {
            report("touch locked-out region=$region")
            return null
        }
        val reaction = (CharacterTouchPolicy.reaction(region, kind) ?: return null)
            .copy(voice = touchVoiceClip(kind, region))
        // Owner 2026-11-16: 「把之前做的触屏互动的动作移除吧，以后动作按照说的话来做」.
        // A poke no longer plays a region-based rig gesture (nod/shake/sway/bow), and a hold only
        // still works when it toggles one of the two deliberate prop poses (raise hand / finger up).
        // Body motion for the reply comes from the speech itself (Live2DView.setPerformanceSpeech),
        // driven from CallActivity with the clip duration.
        val propGestures = if (held) reaction.gestures.filter { it.startsWith("pose:") } else emptyList()
        val animated = propGestures.isNotEmpty() && !turnActive && playFirst(propGestures, "character-touch-pose")
        // 2026-11-20 (owner: C 只装在触屏互动里面): the author's 13s performance stays a deliberate
        // touch gesture — it is never part of the spoken vocabulary or the action panel.
        val performance = if (held && !turnActive && !animated)
            reaction.gestures.firstOrNull { it.startsWith("touch:") } else null
        val performed = performance != null && playTouch(performance)
        val expressed = "mobile:${reaction.expression}" in expressions() &&
            emotionRuntime.presentTouch(reaction.expression, reaction.peak)
        if (!animated && !expressed && !performed) return null
        lastTouchAt = now
        if (expressed) runCatching {
            onPerformanceIntent(reaction.expression, EmotionSignal(emotion = reaction.expression,
                intensity = reaction.peak, confidence = 1.0, source = "local-touch"))
        }
        report("touch region=$region gesture=$kind motion=${if (animated) reaction.gesture else "none"} " +
            "animated=$animated expressed=$expressed performance=${performance ?: "none"} " +
            "performed=$performed label=${reaction.label}")
        return reaction
    }

    /** Taps are never delayed: the first tap reacts immediately, a second tap in the same box upgrades
     * to the double-tap reaction, and the third tap inside the window becomes the annoyed reaction. */
    private fun classifyTap(now: Long, region: String): String? {
        if (now < rapidUntil) { lastTapAt = now; lastTapRegion = region; return null }
        while (true) {
            val oldest = recentTaps.peekFirst() ?: break
            if (now - oldest <= RAPID_WINDOW_MS) break
            recentTaps.pollFirst()
        }
        recentTaps.addLast(now)
        val kind = when {
            recentTaps.size >= 3 -> { rapidUntil = now + RAPID_COOLDOWN_MS; recentTaps.clear(); "rapid" }
            now - lastTapAt <= DOUBLE_WINDOW_MS && region == lastTapRegion -> "double"
            else -> "tap"
        }
        lastTapAt = now
        lastTapRegion = region
        return kind
    }

    /** One pre-rendered reply per touched body part; the clips are local, so a poke never waits on
     *  TTS and never spends model tokens. 0.0.50 shipped five generic syllables picked only by
     *  gesture, so every region sounded like the same "呀" — the owner asked instead for her to
     *  answer as the part that was touched ("就像戳手她会以手被碰来回应"). Each region now has its
     *  own sentence, single taps alternate between two variants, and a clip never repeats twice in
     *  a row. Rendered by `whale/l2d/gen_touch_voices.py` (same voice as the call TTS). */
    private fun touchVoiceClip(kind: String, region: String): String {
        val part = if (region in VOICE_REGIONS) region else "chest"
        val pool = when (kind) {
            "rapid" -> listOf("rapid_1", "rapid_2")
            "hold" -> listOf("hold_$part")
            "double" -> listOf("double_$part")
            else -> listOf("tap_${part}_1", "tap_${part}_2")
        }
        val candidates = if (pool.size > 1) pool.filter { it != lastVoiceClip } else pool
        return candidates[Random.nextInt(candidates.size)].also { lastVoiceClip = it }
    }

    private fun playFirst(candidates: List<String>, source: String): Boolean {
        for (id in candidates) if (play(id, source)) return true
        return false
    }

    fun manualInteraction(manualExpression: Boolean = false) {
        manualUntil = clock() + 15_000L
        manualExpressionActive = manualExpressionActive || manualExpression
        turnGesturePlayed = true
        rememberMotion()
        explicitUserGesture = null
        emotionRuntime.cancelReaction()
    }

    fun onUserInput(text: String) {
        retireNetworkReply()
        turnActive = true
        turnId = null
        localEmotionKey = "legacy-turn:${++localTurnSequence}"
        turnGesturePlayed = false
        replyGestureCount = 0
        explicitUserGesture = requestedGesture(text)
        // A direct request is more specific than a mood-based flourish.
        if (enabled && foreground && explicitUserGesture != null) {
            performExplicitRequest()
        }
    }

    fun beginReply(replyId: String? = null) {
        val id = replyId?.takeIf { it.isNotBlank() }?.take(160)
        if (!turnActive || (id != null && turnId != null && id != turnId)) {
            retireNetworkReply()
            turnActive = true
            turnGesturePlayed = false
            replyGestureCount = 0
            explicitUserGesture = null
            localEmotionKey = "legacy-turn:${++localTurnSequence}"
        }
        if (id != null) turnId = id
        if (id != null && id !in retiredNetworkReplies) networkReplyId = id
        if (id != null && id in repliesWithMotion) turnGesturePlayed = true
        if (turnGesturePlayed) rememberMotion()
    }

    fun onSpeech(text: String, cue: Cue = Cue()) {
        beginReply(cue.replyId)
        if (!enabled || !foreground) return
        val expression = cue.expression?.takeIf { it.isNotBlank() }?.let(::normalExpression)
            ?: inferredExpression(text)
        val emotionKey = cue.replyId?.takeIf { it.isNotBlank() }?.take(160) ?: turnId ?: localEmotionKey
        val signal = cue.emotionIntent ?: stagedIntents[emotionKey]
        applyReplyEmotion(emotionKey, signal, expression)
        // Apply the current reply's expression at playback; later slices do not nudge the mood again.
        emotionRuntime.presentSpeech(emotionKey, signal, expression)
        if (clock() < manualUntil) return
        if (explicitUserGesture != null) {
            performExplicitRequest()
            return
        }
        val structured = cue.gesture?.takeIf { it.isNotBlank() } ?: cue.replyId?.let { stagedMotions[it] }
        if (structured != null) {
            // Owner 2026-11-17: 「动作要跟着她说的话来」. The model tags the clause it belongs to,
            // the router hands that tag down with the matching sentence clip, and the gesture is
            // played while that sentence is being spoken. One reply may carry several of them.
            val gesture = normalGesture(structured)
            if (gesture == null || gesture !in gestures()) {
                // An unsupported explicit motion must not turn into an unrelated gesture.
                report("reply motion unavailable")
                return
            }
            if (replyGestureCount >= MAX_REPLY_GESTURES) { report("reply motion budget spent"); return }
            if (clock() - lastGestureAt < REPLY_GESTURE_GAP_MS) { report("reply motion too soon id=$gesture"); return }
            if (play(gesture, "reply-metadata")) {
                replyGestureCount += 1
                turnGesturePlayed = true
                rememberMotion()
            }
            return
        }
        if (turnGesturePlayed) return
        val gesture = learnedGesture(signal) ?: inferredGesture(text, expression)
        if (gesture != null && clock() - lastGestureAt >= 8_000L && play(gesture, "reply")) {
            replyGestureCount += 1
            turnGesturePlayed = true
            rememberMotion()
        }
    }

    private fun applyReplyEmotion(emotionKey: String, signal: EmotionSignal?, expression: String?): Boolean {
        val accepted = emotionRuntime.acceptReply(emotionKey, signal, expression) ?: return false
            val frame = emotionRuntime.frame()
            val safeId = emotionKey.replace(Regex("[^A-Za-z0-9:._-]"), "_")
            emotionReport(String.format(Locale.ROOT,
                "intent accepted id=%s source=%s analysis_ms=%s emotion=%s confidence=%s intensity=%s nudge_count=%d current=%.4f,%.4f,%.4f target=%.4f,%.4f,%.4f hold=%.2f",
                safeId, emotionRuntime.lastSource, emotionRuntime.lastAnalysisMs, accepted,
                signal?.confidence, signal?.intensity, emotionRuntime.nudgeCount,
                frame.state.current.valence, frame.state.current.arousal, frame.state.current.dominance,
                frame.state.target.valence, frame.state.target.arousal, frame.state.target.dominance, frame.state.holdSeconds))
        runCatching { onPerformanceIntent(accepted, signal) }
        return true
    }

    /** M2 2026-11-18：开场基线（服务端下发的上次情绪残留）。与回复脉冲无关，只改基线。 */
    fun seedEmotionBaseline(signal: EmotionSignal): Boolean {
        if (!enabled) return false
        val ok = emotionRuntime.seedBaseline(signal)
        if (!ok) return false
        val frame = emotionRuntime.frame()
        emotionReport(String.format(Locale.ROOT,
            "baseline seeded emotion=%s v=%.3f a=%.3f d=%.3f intensity=%.3f",
            signal.emotion, frame.state.current.valence, frame.state.current.arousal,
            frame.state.current.dominance, frame.state.intensity))
        return true
    }

    /** Metadata can arrive while local speech is still synthesizing an earlier slice. */
    fun stageCue(cue: Cue) {
        val id = cue.replyId?.takeIf { it.isNotBlank() && it.length <= 160 } ?: return
        if (id in retiredNetworkReplies) return
        if (turnActive && networkReplyId == null && id !in retiredNetworkReplies) networkReplyId = id
        cue.gesture?.takeIf { it.isNotBlank() }?.let { motion ->
            if (id !in stagedMotions) stagedMotions[id] = motion.take(80)
        }
        cue.emotionIntent?.let { if (id !in stagedIntents) stagedIntents[id] = it }
        while (stagedMotions.size > 32) stagedMotions.remove(stagedMotions.keys.first())
        while (stagedIntents.size > 32) stagedIntents.remove(stagedIntents.keys.first())
    }

    /** A late classifier result is metadata for the current already-seen reply, never speech. */
    fun onEmotionUpdate(replyId: String, signal: EmotionSignal): Boolean {
        if (!enabled || !foreground || !turnActive || replyId.isBlank() || replyId.length > 160
            || replyId in retiredNetworkReplies) return false
        // A fast independent classifier can finish before its audio envelope.
        // Stage it until the envelope confirms the current reply identity.
        if (networkReplyId == null) {
            if (replyId !in stagedIntents) stagedIntents[replyId] = signal
            while (stagedIntents.size > 32) stagedIntents.remove(stagedIntents.keys.first())
            return false
        }
        if (replyId != networkReplyId) return false
        if (replyId !in stagedIntents) stagedIntents[replyId] = signal
        if (replyId != turnId) return false
        val accepted = applyReplyEmotion(replyId, signal, null)
        // Explicit audio-envelope or user commands always beat a learned cue.
        if (clock() < manualUntil || explicitUserGesture != null || turnGesturePlayed || replyId in stagedMotions) return accepted
        val gesture = learnedGesture(signal) ?: return accepted
        if (play(gesture, "trained-intent:${signal.intent}")) {
            turnGesturePlayed = true
            rememberMotion()
            return true
        }
        return accepted
    }

    private fun retireNetworkReply() {
        networkReplyId?.let { retiredNetworkReplies.add(it) }
        turnId?.let { retiredNetworkReplies.add(it) }
        networkReplyId = null
        while (retiredNetworkReplies.size > 64) retiredNetworkReplies.remove(retiredNetworkReplies.first())
    }

    fun endReply() {
        retireNetworkReply()
        turnActive = false
        turnId = null
        explicitUserGesture = null
        replyGestureCount = 0
    }

    fun cancelReply() {
        retireNetworkReply()
        turnActive = false
        turnId = null
        turnGesturePlayed = false
        explicitUserGesture = null
        replyGestureCount = 0
        emotionRuntime.cancelReaction()
    }

    /** Ten-Hz publication; the GL thread owns its short anti-staircase filter. */
    fun updateEmotionFrame(manualUiOpen: Boolean = false) {
        if (!enabled || !foreground) return
        if (manualExpressionActive && clock() >= manualUntil && !manualUiOpen) {
            releaseManualExpression()
            manualExpressionActive = false
        }
        val frame = emotionRuntime.frame()
        if (!manualUiOpen) showEmotionWeights(frame.weights)
        if (frame.activeSeconds >= nextEmotionLogAt) {
            nextEmotionLogAt = frame.activeSeconds + 5
            emotionReport(String.format(Locale.ROOT,
                "state active_s=%.1f current=%.4f,%.4f,%.4f target=%.4f,%.4f,%.4f hold=%.1f mood=%s weights=%.4f,%.4f,%.4f,%.4f,%.4f",
                frame.activeSeconds, frame.state.current.valence, frame.state.current.arousal, frame.state.current.dominance,
                frame.state.target.valence, frame.state.target.arousal, frame.state.target.dominance, frame.state.holdSeconds, frame.state.dominantEmotion,
                frame.weights.happy, frame.weights.sad, frame.weights.angry, frame.weights.surprised, frame.weights.shy))
        }
    }

    fun emotionSnapshot(): EmotionFrame = emotionRuntime.frame()
    val emotionNudgeCount: Long get() = emotionRuntime.nudgeCount

    /** Compatibility hook only: no idle/waiting timer schedules large gestures.
     * The native render loop continuously blends mouth and mood into natural
     * motion; this director only issues semantically justified gesture cues. */
    @Suppress("UNUSED_PARAMETER")
    fun tick(busy: Boolean, waitingForReply: Boolean = false, speaking: Boolean = false) = Unit

    private fun performExplicitRequest() {
        if (turnGesturePlayed) return
        val wanted = explicitUserGesture ?: return
        if (wanted in gestures()) {
            if (play(wanted, "user-request")) { turnGesturePlayed = true; rememberMotion() }
        } else {
            turnGesturePlayed = true
            rememberMotion()
            report("requested motion unavailable")
        }
    }

    private fun play(id: String, source: String): Boolean {
        if (id !in gestures() || !playGesture(id)) return false
        lastGestureAt = clock()
        report("motion queued source=$source id=$id")
        // logcat is off on this ROM; the file is what a real call can be checked against.
        DiagLog.write("motion.log", "queued source=$source id=$id")
        return true
    }

    private fun rememberMotion() {
        turnId?.let { repliesWithMotion.add(it) }
        while (repliesWithMotion.size > 32) repliesWithMotion.remove(repliesWithMotion.first())
    }

    private fun normalExpression(value: String): String? {
        val text = value.trim().lowercase().removePrefix("mobile:")
        val id = when (text) {
            "neutral", "natural", "normal", "自然", "中性" -> "mobile:neutral"
            "happy", "joy", "开心", "高兴" -> "mobile:happy"
            "sad", "难过", "伤心" -> "mobile:sad"
            "angry", "生气", "愤怒" -> "mobile:angry"
            "surprised", "surprise", "惊讶" -> "mobile:surprised"
            "shy", "blush", "害羞", "脸红" -> "mobile:shy"
            "thinking", "思考" -> "mobile:neutral"
            else -> return value.trim().takeIf { it in expressions() }
        }
        return id.takeIf { it in expressions() }
    }

    /** 2026-11-23 owner: 手部动作不要了 —— shy is the rig-only body pose (no raised hand). */
    private fun shyGesture(): String? = "call:shy".takeIf { it in gestures() }

    private fun normalGesture(value: String): String? {
        val text = value.trim().lowercase().removePrefix("call:").replace('_', '-')
        return when (text) {
            "nod", "点头" -> "call:nod"
            "shake", "摇头" -> "call:shake"
            "bow", "欠身", "鞠躬" -> "call:bow"
            "sway", "摇摆", "轻摆" -> "call:sway"
            // 2026-11-23 owner: 手部动作（举手/放下/挥手/招手/伸手）已删除，不再映射。
            // 2026-11-20: six authored body gestures.
            "tilt", "歪头", "偏头" -> "call:tilt"
            "nod-twice", "连点头", "点头两下" -> "call:nod-twice"
            "shiver", "抖一抖", "发抖" -> "call:shiver"
            "look-around", "张望", "左右看" -> "call:look-around"
            "lean-in", "凑近", "靠近" -> "call:lean-in"
            "look-up", "抬头", "仰望" -> "call:look-up"
            "shy", "shyarm", "害羞", "脸红", "不好意思" -> shyGesture()
            // 2026-11-23 owner: 挥手/招手/伸手 removed with the rest of the hand gestures.
            // 2026-11-23 owner: 腿/脚动作（walk-in-place）也删了，不再映射。
            "none", "neutral", "idle" -> null
            else -> null
        }
    }

    /** One generic learned intent, routed only through this model's capabilities.
     * Engineering thresholds are fixed before held-out evaluation; scores are
     * not calibrated accuracy. The regression is not a direct joint controller. */
    private fun learnedGesture(signal: EmotionSignal?): String? {
        if (signal == null || signal.source?.contains("trained_general_head_v1") != true) return null
        val confidence = signal.intentConfidence?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: return null
        val intensity = signal.gestureIntensity?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: return null
        if (confidence < .65 || intensity < .20) return null
        val semantic = when (signal.intent) {
            "greet" -> "call:greet"
            "acknowledge", "emphasize" -> "call:nod"
            "disagree" -> "call:shake"
            "reflect", "comfort", "celebrate" -> "call:sway"
            "apologize" -> "call:bow"
            else -> return null
        }
        // Reject an inconsistent/unreviewed command instead of silently
        // executing an arbitrary string from metadata.
        if (signal.gesture != semantic) return null
        val supported = gestures()
        val selected = if (semantic == "call:greet") "call:nod" else semantic
        return selected.takeIf { it in supported }
    }

    private fun requestedGesture(text: String): String? {
        val clipped = text.take(300).lowercase()
        // 2026-11-23 owner: 手部动作不要了 —— 举手/放下手/挥手/招手/伸手 的说法直接忽略，
        // 不再映射成任何动作；下面的请求词表只留头/身体/腿。
        val mentionsGesture = Regex("(?:点头|摇头|欠身|鞠躬|歪头|偏头|抖一抖|张望|凑近|抬头)").containsMatchIn(clipped)
        if (mentionsGesture && (Regex("(?:什么意思|什么含义|含义是什么|指什么|代表什么|表示什么|定义|这个词|怎么解释|怎么实现|如何实现|原理|翻译|英文|什么是|什么叫)").containsMatchIn(clipped) ||
                Regex("(?:解释|说明|支持|有没有|具备).{0,12}(?:点头|摇头|欠身|鞠躬)").containsMatchIn(clipped)))
            return "none"
        if (Regex("(?:我(?:刚才|已经|正在)|他|她|有人).{0,4}(?:点头|摇头)").containsMatchIn(clipped))
            return null
        if (Regex("(?:不要|别|不用|别再|不许).{0,5}(?:点头|摇头|鞠躬|欠身)").containsMatchIn(clipped))
            return "none"
        if (Regex("(?:点(?:个|下|一下)?头|nod)").containsMatchIn(clipped)) return "call:nod"
        if (Regex("(?:摇(?:个|下|一下)?头|shake your head)").containsMatchIn(clipped)) return "call:shake"
        if (Regex("(?:鞠(?:个|一)?躬|欠(?:个|下)?身)").containsMatchIn(clipped)) return "call:bow"
        // 2026-11-20 (owner: 动作变多): explicit imperative wording only, never a description.
        if (Regex("(?:歪(?:个|下|一下)?头|偏(?:个|下|一下)?头)").containsMatchIn(clipped)) return "call:tilt"
        if (Regex("(?:抖(?:一)?抖|发(?:个)?抖|打个(?:哆嗦|寒战))").containsMatchIn(clipped)) return "call:shiver"
        if (Regex("(?:张望|东张西望|看(?:看|一)四周)").containsMatchIn(clipped)) return "call:look-around"
        if (Regex("(?:凑(?:近|过来)|靠(?:近|过来)|离近点)").containsMatchIn(clipped)) return "call:lean-in"
        if (Regex("(?:抬起?头(?:来|看)?|仰(?:起)?头|往上看)").containsMatchIn(clipped)) return "call:look-up"
        return null
    }

    private fun inferredExpression(text: String): String? {
        val value = text.take(400)
        val mood = when {
            Regex("(?:害羞|脸红|不好意思|别夸|照れる|恥ずかし)").containsMatchIn(value) -> "mobile:shy"
            Regex("(?:生气|气死|可恶|烦死|怒った|むかつく)").containsMatchIn(value) -> "mobile:angry"
            Regex("(?:难过|伤心|好可惜|好遗憾|悲しい|寂しい)").containsMatchIn(value) -> "mobile:sad"
            Regex("(?:居然|竟然|真的吗|没想到|びっくり|まさか)").containsMatchIn(value) -> "mobile:surprised"
            Regex("(?:哈哈|嘿嘿|开心|太好了|好耶|嬉しい|よかった)").containsMatchIn(value) -> "mobile:happy"
            else -> null
        }
        return mood?.takeIf { it in expressions() }
    }

    private fun inferredGesture(text: String, expression: String?): String? {
        val value = text.take(220)
        return when {
            Regex("(?:谢谢|多谢|感谢|ありがとう)").containsMatchIn(value) -> "call:bow"
            Regex("^(?:不行|不可以|不是|不要|没有|いいえ)").containsMatchIn(value.trim()) -> "call:shake"
            Regex("^(?:好[的呀啊吧]|嗯|对[的呀啊]|明白|知道了|是[的呀啊]|うん|はい)").containsMatchIn(value.trim()) -> "call:nod"
            // 2026-11-21: shy gets its own body pose (now with the raised hand); happy keeps
            // the light sway.
            expression == "mobile:shy" -> shyGesture()
            expression == "mobile:happy" -> "call:sway"
            else -> null
        }?.takeIf { it in gestures() }
    }

    private companion object {
        const val TAP_GAP_MS = 80L
        const val HOLD_GAP_MS = 900L
        const val DOUBLE_WINDOW_MS = 340L
        const val RAPID_WINDOW_MS = 1_600L
        const val RAPID_COOLDOWN_MS = 2_200L
        /** Sentence-tagged gestures: no machine-gun nodding, and a reply has a small budget. */
        const val REPLY_GESTURE_GAP_MS = 2_200L
        const val MAX_REPLY_GESTURES = 4
        /** Regions that have their own pre-rendered reply; anything else falls back to chest. */
        val VOICE_REGIONS = setOf("hair", "face", "ears", "chest", "waist", "skirt", "legs", "tail")
    }
}
