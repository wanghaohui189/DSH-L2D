package com.prts.client.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.widget.ImageButton
import com.prts.client.BuildConfig
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import android.view.View
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.prts.client.R
import com.prts.client.audio.AutoListener
import com.prts.client.audio.SileroVad
import com.prts.client.audio.CallGuard
import com.prts.client.audio.DiagLog
import com.prts.client.audio.MicRecorder
import com.prts.client.call.AlbumWatcher
import com.prts.client.call.CallKeepAliveService
import com.prts.client.call.ScreenCaptureService
import com.prts.client.call.LocationReporter
import com.prts.client.audio.SpeechPlayer
import com.prts.client.audio.SpeechTempo
import com.prts.client.audio.SpeechSynthesisRouter
import com.prts.client.audio.TtsBackendSettings
import com.prts.client.audio.audio8.Audio8ModelStore
import com.prts.client.audio.LocalSpeechText
import com.prts.client.audio.LocalVoiceSettings
import com.prts.client.databinding.ActivityCallBinding
import com.prts.client.live2d.Live2DView
import com.prts.client.net.InAudio
import com.prts.client.net.Actions
import com.prts.client.net.DisplayText
import com.prts.client.net.InEmotionUpdate
import com.prts.client.net.EmotionIntent
import com.prts.client.net.Proto
import com.prts.client.net.WsClient
import com.prts.client.net.ConnectionProfile
import com.prts.client.net.ConnectionSettings
import com.prts.client.net.FaceObservationMetadata
import com.prts.client.ui.assets.BackgroundPresenter
import com.prts.client.ui.assets.CharacterLibrary
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject

class CallActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_KEEP_ALIVE = "com.prts.client.extra.KEEP_ALIVE"
        const val EXTRA_FROM_WAKE = "com.prts.client.extra.FROM_WAKE"
        /** 0.0.78：她主动打来、主人点了「接听」；不带任何要代发的台词。 */
        const val EXTRA_WAKE_ACCEPTED = "com.prts.client.extra.WAKE_ACCEPTED"
    /** Sentence spoken together with the wake word; sent as soon as the call is ready. */
    const val EXTRA_INITIAL_TEXT = "com.prts.client.extra.INITIAL_TEXT"
        /**
         * 接听主动来电时发这一条固定标记。服务端把它识别成「他接起了电话，还没开口」，
         * 不写进主人说的话；界面上也不加气泡（走 sendText，不走 sendTypedMessage）。
         */
        private const val WAKE_ACCEPTED_TEXT = "（他接起了电话，还没开口）"
        private const val KEEPALIVE_TICK_MS = 3000L
        private const val KEEPALIVE_STALE_MS = 15000L
        /**
         * 一段话发出去之后的最短静音窗。2026-11-23：2500→400。
         * 原来这 2.5 s 覆盖「等 ASR 回包」，主人在这段时间说的后半句连日志都没有（22 次漏收里
         * 大部分落在这一类边界上，见 VOICE-RECEIVE-2026-11-23.md）。现在转写期间不关麦，
         * 400 ms 只用来吞掉刚说完那句的尾音/按键声。
         */
        private const val TURN_SETTLE_MS = 250L
        /** 麦克风按理说该开着、却连续这么久没有任何 rec 在跑 → 判定卡死，强制解开。 */
        private const val LISTEN_STUCK_MS = 12_000L
        /** 她说话期间最多攒几句主人插的话（超出丢最老的，防止长篇输出时无限堆积）。 */
        private const val MAX_PENDING_UTTERANCES = 6
        /** 位置多久报一次（天气类问题用；不用很勤）。 */
        private const val LOC_REPORT_MS = 10 * 60 * 1000L
        /** 触屏互动用新 key：老 key `character_touch_reaction` 在已装手机上还是 true，默认关不能靠改默认值。 */
        private const val CHARACTER_TOUCH_PREF = "character_touch_reaction_v2"
    }

    private lateinit var b: ActivityCallBinding
    private lateinit var ws: WsClient
    private lateinit var adapter: BubbleAdapter
    private lateinit var live2D: Live2DView
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var speech: SpeechPlayer
    private lateinit var mic: MicRecorder
    private var autoListen: AutoListener? = null
    /** Silero VAD（端点判定）；null = 模型加载失败，AutoListener 退回能量门。 */
    private var vad: SileroVad? = null
    /** mutedNow 从哪一刻开始一直为 true（卡死看门狗用）。 */
    private var mutedSinceAt = 0L
    /**
     * 她说话期间主人插的话（2026-11-24 方案 A）：只收不打断，等她说完再按顺序送。
     * 上限 4 段，超了丢最老的——防止她长篇输出时无限堆积。
     */
    private val pendingDuringPlayback = ArrayDeque<FloatArray>()
    private var lastPendingFlushAt = 0L
    private var callGuard: CallGuard? = null
    /** When the current turn was sent; used to keep the microphone closed until she answers. */
    private var autoTurnAt = 0L
    private var autoHeardAudio = false
    private var autoBusyAt = 0L
    private var autoResumeMs = 200
    private var autoMuted = true
    /** User pressed the mic button to mute hands-free listening; auto-resume must respect it. */
    private var manualMuted = false
    private var autoSendAt = 0L
    /** 2026-11-24 P6：ASR 真故障提示节流。key 失效/ASR 挂掉时每段都会失败，不节流就每 ~6 s 弹一条。 */
    private var lastFatalAsrNoticeAt = 0L
    /** True after the user pressed Home/Recents: stop listening until the call screen returns. */
    private var userLeft = false
    /** 权限页「后台继续听」的本地缓存（服务端 listen.background 的快照）。 */
    private var backgroundListen = false
    private lateinit var serverUrl: String
    private var connectionProfile: ConnectionProfile? = null
    private var voiceAccessToken = ""
    private var replyLanguage = "zh"
    private var callCharacter = "whale"
    private var opening = CallOpeningPolicy()
    private var background: BackgroundPresenter? = null
    private var placementEditor: CharacterPlacementEditor.Handle? = null
    private var placementEditing = false
    private var directPlacement: DirectCharacterPlacementController? = null
    private var providerReady = false
    private var localSpeech: SpeechSynthesisRouter? = null
    private lateinit var camera: CallCameraController
    private var liveCameraOn = false
    /** observe.album 的手机端：通话中只上报「最近一张」的缩略图，没权限就整条静默。 */
    private var albumWatcher: AlbumWatcher? = null
    private var frontCameraOn = false
    private var guestTalkOn = false
    /** Touch reactions are opt-in: the top-left button starts off so a stray tap cannot pet her. */
    private var characterTouchOn = false
    private var touchVoice: TouchVoicePlayer? = null
    private var pendingImage: PreparedCallImage? = null
    private var imageSending = false
    private var imageSendUncertain = false
    private var imageAttempt = 0L
    private var draftThumbnail: android.graphics.Bitmap? = null
    private var draftThumbnailImage: PreparedCallImage? = null
    private lateinit var motionDirector: AutoMotionDirector
    private var actionPanelOpen = false
    // 2026-11-21: manual panel defaults to the extended amplitude. Gentle was too small to
    // judge a deliberate tap ("动作别轻柔了，幅度太小很难看到"); automatic gestures stay gentle.
    private var manualGestureExtended = true
    private var connectionSettingsError: String? = null
    @Volatile private var foreground = false
    /** Foreground service keeps the socket, playback and microphone alive while backgrounded. */
    private val audioLive get() = foreground || CallKeepAliveService.running
    private var hangupRegistered = false
    /** Releases the microphone when the user comes back to an unlocked screen away from the call. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON && !foreground && !keyguardLocked()) {
                stopAutoListen()
                Log.i("PRTS-Call", "screen on while background: hands-free listening released")
            }
        }
    }
    @Volatile private var lastInboundAt = 0L
    private var lastReconnectAt = 0L
    private val hangupReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != CallKeepAliveService.ACTION_HANGUP) return
            if (::speech.isInitialized) speech.cancel()
            localSpeech?.cancel()
            finish()
        }
    }
    private var modelPreparation: AutoCloseable? = null
    private var captionTickScheduled = false
    private val captions = StreamingCaptionController { frame ->
        if (foreground && ::adapter.isInitialized && !isDestroyed) {
            val manager = b.callChat.layoutManager as? LinearLayoutManager
            val follow = adapter.itemCount == 0 || (manager?.findLastVisibleItemPosition() ?: -1) >= adapter.itemCount - 2
            val position = adapter.upsertReply(frame.replyKey, Bubble(frame.who, frame.text, frame.translation, frame.language))
            if (!characterTouchOn) b.callChat.visibility = View.VISIBLE
            // 宠物模式收起聊天区后，她说的话仍然要能看见（0.0.85）。
            else renderPetCaption(frame.text, frame.translation)
            if (follow) b.callChat.scrollToPosition(position)
        }
    }
    private val captionTick = object : Runnable {
        override fun run() {
            if (!foreground) { captionTickScheduled = false; return }
            captions.advanceOne()
            if (captions.hasPending) handler.postDelayed(this, 24) else captionTickScheduled = false
        }
    }
    private fun animateCaptions() {
        if (foreground && captions.hasPending && !captionTickScheduled) {
            captionTickScheduled = true; handler.post(captionTick)
        }
    }
    // A picker result may clear the controller flag before onResume runs.
    private var mediaSuspended = false
    private var connectionState = "未连接"
    private var activityState = ""
    private var startedAt = 0L
    private var playbackCompletePending = false
    private val localOpeningPlayback = LocalOpeningPlaybackState()
    private var ackAttempt = 0
    private val proactive by lazy {
        CallProactivePolicy(SystemClock::elapsedRealtime, CallProactiveSettings.waitSeconds(this) * 1000L)
    }
    private var proactiveEnabled = true
    private var remoteProactiveSupported = false
    private var remoteProactiveId: String? = null
    private var proactivePlaybackComplete = false
    /** 0.0.78：接听主动来电后要发的一条「接起了」标记（等 socket + 语音源就绪）。 */
    private var wakeAcceptPending = false
    private var submittedSequence = 0L
    private var activeLocalRequest: String? = null
    private val replyToLocalRequest = LinkedHashMap<String, String>()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notice(if (granted) "麦克风权限已开启，点击麦克风开始录音" else "没有麦克风权限，仍可用文字聊天")
        if (granted) ensureKeepAliveService()
    }
    /** 定位权限：只为了天气这类问题知道你在哪，不授权也不影响通话。 */
    private var locationAsked = false
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val ok = res[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            res[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (ok) sendLocationNow()
    }
    private val locTick = object : Runnable {
        override fun run() {
            sendLocationNow()
            handler.postDelayed(this, LOC_REPORT_MS)
        }
    }

    /**
     * observe.screen：每次点「让她看屏幕」都要主人自己在系统弹窗里点一次授权。
     * 2026-11-17 起：授权后开一个 90 秒窗口，等主人切到想让她看的应用（本页 onStop）
     * 再自动截一帧——以前授权后立刻截，拍到的是通话页自己，切应用就「看不到」。
     */
    private val screenConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            notice("没拿到录屏授权，这次不看了")
            return@registerForActivityResult
        }
        ScreenCaptureService.sink = { jpeg, label ->
            if (::ws.isInitialized) ws.sendScreenFrame(jpeg, System.currentTimeMillis(), label) else false
        }
        ScreenCaptureService.start(this, result.resultCode, data)
        notice("切到想让她看的应用，她会自动截一帧（90 秒内）")
    }

    /** 按需截一帧：没连上就不打扰系统弹窗。 */
    private fun lookAtScreen() {
        if (!::ws.isInitialized || !ws.isReady) {
            notice("还没连上，等她接通再看")
            return
        }
        if (!Prefs.grantCached(this, "observe.screen")) {
            notice("先在「权限」页打开「屏幕」，再点这里")
            return
        }
        val manager = getSystemService(android.media.projection.MediaProjectionManager::class.java)
        // 2026-11-17：以前用无参 createScreenCaptureIntent()，Android 14+ 默认弹「单个应用」选择，
        // 选中的是 L2D 自己 → 切到别的应用后截到的还是旧画面（「切应用就不读屏幕」）。
        // createConfigForDefaultDisplay() 强制整屏捕获，切哪个应用就看哪个。
        val intent = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                manager?.createScreenCaptureIntent(
                    android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                manager?.createScreenCaptureIntent()
            }
        }.getOrNull()
        if (intent == null) {
            notice("这台设备不支持录屏授权")
            return
        }
        runCatching { screenConsent.launch(intent) }
            .onFailure { notice("打不开录屏授权：${it.javaClass.simpleName}") }
    }

    /** 把当前定位报给中继；没权限就顺手问一次。 */
    internal fun sendLocationNow() {
        try {
            if (!::ws.isInitialized || !ws.isReady) return
            if (!LocationReporter.hasPermission(this)) {
                if (!locationAsked) {
                    locationAsked = true
                    locationPermission.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
                return
            }
            val known = LocationReporter.lastKnown(this)
            if (known != null) {
                ws.sendLocation(known.first, known.second, known.third)
                Log.i("PRTS-Call", "location sent %.3f,%.3f acc=%.0f".format(known.first, known.second, known.third))
                return
            }
            // 没有缓存位置：主动要一次（首次开启定位后也能拿到）
            LocationReporter.requestOnce(this) { lat, lon, acc ->
                runOnUiThread {
                    try {
                        if (::ws.isInitialized && ws.isReady) {
                            ws.sendLocation(lat, lon, acc)
                            Log.i("PRTS-Call", "location sent(fresh) %.3f,%.3f acc=%.0f".format(lat, lon, acc))
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("PRTS-Call", "sendLocation failed", e)
        }
    }
    private val clockTick = object : Runnable {
        override fun run() {
            if (!foreground) return
            renderStatus()
            camera.setCallBusy(speech.isBusy || localSpeech?.isBusy == true || mic.isRecording || imageSending || camera.isPhotoProcessing)
            maybeSpeakProactively()
            handler.postDelayed(this, 1000)
        }
    }
    private val emotionTick = object : Runnable {
        override fun run() {
            if (!foreground) return
            motionDirector.updateEmotionFrame(manualUiOpen = actionPanelOpen || placementEditing || directPlacement?.isManipulating == true || mic.isRecording)
            handler.postDelayed(this, 100)
        }
    }
    private val playbackAck = object : Runnable {
        override fun run() {
            if (!audioLive || !playbackCompletePending || speech.isBusy || localSpeech?.isBusy == true || !ws.isReady) return
            ws.sendSignal("frontend-playback-complete")
            // The local ack has been sent; a missing server echo must not keep the mic muted.
            playbackCompletePending = false
            handler.removeCallbacks(this)
        }
    }

    /** Detects a silently dead socket while backgrounded and reconnects on its own. */
    private val keepAliveTick = object : Runnable {
        private var ticks = 0
        override fun run() {
            try {
                if (!audioLive) return
                ticks++
                if (::ws.isInitialized && ws.isReady) ws.sendSignal("ping")
                val now = SystemClock.elapsedRealtime()
                val stale = lastInboundAt > 0 && now - lastInboundAt > KEEPALIVE_STALE_MS
                if (ticks % 10 == 0) Log.i("PRTS-Call", "keepalive tick=$ticks ready=${::ws.isInitialized && ws.isReady} "
                    + "idle=${if (lastInboundAt == 0L) -1 else now - lastInboundAt} stale=$stale")
                if (stale && now - lastReconnectAt > KEEPALIVE_STALE_MS) {
                    Log.i("PRTS-Call", "keepalive stale=${now - lastInboundAt}ms; reconnecting")
                    lastReconnectAt = now
                    reconnectCall()
                }
            } catch (error: Exception) {
                Log.w("PRTS-Call", "keepalive tick failed", error)
            } finally {
                handler.postDelayed(this, KEEPALIVE_TICK_MS)
            }
        }
    }

    private fun startKeepAliveTick() {
        handler.removeCallbacks(keepAliveTick)
        Log.i("PRTS-Call", "keepalive tick started")
        handler.post(keepAliveTick)
    }

    private fun reconnectCall() {
        providerReady = false
        if (::ws.isInitialized) ws.close()
        runCatching { readConnectionSettings() }
        openConnection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagLog.install(filesDir)
        opening = CallOpeningPolicy(savedInstanceState?.getBoolean("localOpeningConsumed", false) == true)
        proactiveEnabled = CallProactiveSettings.enabled(this)
        // 2026-11-18：权限页开了「后台继续听」才在切出 App 后继续收音（默认关）。
        backgroundListen = Prefs.grantCached(this, "listen.background")
        b = ActivityCallBinding.inflate(layoutInflater)
        setContentView(b.root)
        // Wake app may hand over the sentence spoken with the wake word.
        // 0.0.78：主动来电的「接听」只发固定标记，不再把来电文案塞进输入框发回去。
        if (intent?.getBooleanExtra(EXTRA_WAKE_ACCEPTED, false) == true) {
            wakeAcceptPending = true
            pendingWakeText = ""
        } else {
            pendingWakeText = stripWakeWord(intent?.getStringExtra(EXTRA_INITIAL_TEXT))
        }
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        registerHangupReceiver()
        handler.postDelayed(locTick, 8000)
        runCatching {
            ContextCompat.registerReceiver(this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON),
                ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        camera = CallCameraController(this, { image ->
            pendingImage = image
            imageSendUncertain = false
            renderImageDraft()
        }, ::notice, {
            mic.stop(false)
            interruptReply("camera_open")
            activityState = ""
            renderStatus()
        }, CallCameraUi(b.cameraPreviewCard, b.cameraPreviewHost, b.cameraPreviewStatus,
            b.faceTrackingToggle, b.btnPreviewPhoto, b.btnPreviewFlip, b.btnPreviewClose),
            onLiveFrame = { jpeg -> if (::ws.isInitialized) ws.sendLiveFrame(jpeg) },
            onLivePreviewClosed = {
                liveCameraOn = false
                frontCameraOn = false
                syncCameraButtons()
            })
        b.btnLiveCamera.setOnClickListener {
            liveCameraOn = !liveCameraOn
            if (liveCameraOn) frontCameraOn = false
            applyLiveCamera()
            notice(if (liveCameraOn) "后摄像头已开启" else "后摄像头已关闭")
        }
        b.btnFrontCamera.setOnClickListener {
            frontCameraOn = !frontCameraOn
            if (frontCameraOn) liveCameraOn = false
            applyLiveCamera()
            notice(if (frontCameraOn) "前摄像头已开启" else "前摄像头已关闭")
        }
        syncCameraButtons()
        b.allowGuestToggle.setOnCheckedChangeListener { _, enabled ->
            guestTalkOn = enabled
            b.allowGuestToggle.text = if (enabled) "旁人对话 · 开" else "旁人对话 · 关"
            if (::ws.isInitialized && ws.isReady) ws.sendAllowGuest(enabled)
            notice(if (enabled) "旁人对话已开启" else "旁人对话已关闭")
        }
        b.allowGuestToggle.text = if (guestTalkOn) "旁人对话 · 开" else "旁人对话 · 关"
        characterTouchOn = getSharedPreferences("prts", MODE_PRIVATE)
            .getBoolean(CHARACTER_TOUCH_PREF, false)
        touchVoice = TouchVoicePlayer(this) { traceVoice(it) }
        traceVoice("voice player ready v=${BuildConfig.VERSION_NAME} gap=700 repeat_gap=900 duck=0.35")
        renderCharacterTouchButton()
        // The preference survives a cold start, so the collapsed layout must be restored too.
        if (characterTouchOn) applyPetMode()
        b.characterTouchToggle.setOnClickListener {
            characterTouchOn = !characterTouchOn
            getSharedPreferences("prts", MODE_PRIVATE).edit()
                .putBoolean(CHARACTER_TOUCH_PREF, characterTouchOn).apply()
            renderCharacterTouchButton()
            applyPetMode()
            notice(if (characterTouchOn) "触屏互动已开启：控件已收起，点她试试；「触屏互动 · 看清单」能看到全部反应" else "触屏互动已关闭：控件已恢复")
        }
        // 清单做成独立页面（主人 2026-11-20：可以做成权限那样的页面），点按钮才进去。
        b.touchInventoryToggle.setOnClickListener { openTouchGuide() }
        // Being zoomed/panned until her head is off the render area used to be a dead end on this screen.
        // 长按仍然是「复位」，但现在连渲染一起重载：摸鱼模式下这是唯一够得着的复位入口。
        b.characterTouchToggle.setOnLongClickListener {
            resetCallModel("long-press")
            true
        }
        // 通话中也能改权限：权限页用独立 socket，不动正在进行的通话。
        b.callPermissionsButton.setOnClickListener {
            startActivity(Intent(this, PermissionSettingsActivity::class.java))
        }
        // 模型没画出来（渲染线程失败 / 位置跑偏）时不必退到后台再回来：这里就地重载一遍。
        b.callModelResetButton.setOnClickListener { resetCallModel("button") }

        background = BackgroundPresenter(this, b.bgImage).also { it.showSelected() }
        val charLib = CharacterLibrary(this)
        val character = charLib.current()
        callCharacter = character
        replyLanguage = LocalVoiceSettings.loadReplyLanguage(this)
        readConnectionSettings()
        ensureKeepAliveService()
        b.callName.text = charLib.resolve(character)?.displayName ?: character
        startedAt = SystemClock.elapsedRealtime()

        // Live2D（Cubism 可用时真渲染；否则保持立绘占位）
        if (Live2DView.available) {
            live2D = Live2DView(this, character)
            live2D.onRenderFailure = { failure ->
                if (foreground) notice("模型没画出来：$failure，点左上角「模型复位」重试")
            }
            val transform = CharacterPresentationStore.load(this, character)
            live2D.setPresentationTransform(transform.scale, transform.offsetX, transform.offsetY)
            b.live2dHost.addView(live2D, android.widget.FrameLayout.LayoutParams(-1, -1))
        } else {
            val iv = android.widget.ImageView(this).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(-1, -1)
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                setImageResource(R.drawable.ic_avatar_placeholder)
            }
            b.live2dHost.addView(iv)
        }
        motionDirector = AutoMotionDirector(
            clock = SystemClock::elapsedRealtime,
            expressions = { if (::live2D.isInitialized) live2D.availableExpressions().map { it.id }.toSet() else emptySet() },
            gestures = { if (::live2D.isInitialized) live2D.availableGestures().map { it.id }.toSet() else emptySet() },
            showEmotionWeights = { weights -> if (::live2D.isInitialized) live2D.setEmotionWeights(
                weights.happy.toFloat(), weights.sad.toFloat(), weights.angry.toFloat(),
                weights.surprised.toFloat(), weights.shy.toFloat()) },
            clearAutoEmotion = { if (::live2D.isInitialized) live2D.clearEmotionState() },
            releaseManualExpression = { if (::live2D.isInitialized) live2D.clearExpression() },
            playGesture = { id -> ::live2D.isInitialized && live2D.playPerformanceGesture(id) },
        playTouch = { ::live2D.isInitialized && live2D.playTouchPerformance() },
            report = { Log.i("PRTS-AutoMotion", it); if (characterTouchOn) traceTouch("director $it") },
            emotionReport = { Log.i("PRTS-Emotion", it) },
            onPerformanceIntent = { emotion, signal ->
                if (::live2D.isInitialized) live2D.setPerformanceEmotion(emotion,
                    signal?.intensity?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: .35,
                    signal?.confidence?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 1.0,
                    signal?.valence?.takeIf { it.isFinite() }?.coerceIn(-1.0, 1.0),
                    signal?.arousal?.takeIf { it.isFinite() }?.coerceIn(-1.0, 1.0),
                    signal?.dominance?.takeIf { it.isFinite() }?.coerceIn(-1.0, 1.0))
            },
        )
        motionDirector.setEnabled(getSharedPreferences("prts", MODE_PRIVATE).getBoolean("automatic_character_motion", true))

        adapter = BubbleAdapter()
        b.callChat.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        b.callChat.adapter = adapter
        (b.callChat.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        b.callChat.visibility = View.GONE

        speech = SpeechPlayer(::onSpeechStart, { message ->
            traceCall("audio_start", message)
            startReplyPerformance(message)
            if (!message.forwarded) ws.playbackStarted(message.display_text)
            activityState = "正在说话"
            renderStatus()
        }, { value ->
            if (::live2D.isInitialized) live2D.setMouth(value)
        }, {
            finishLocalOpeningIfIdle()
            schedulePlaybackAck()
        }, ::notice,
            onAudioEnded = { message, completed ->
                traceCall("audio_end", message, completed = completed)
                DiagLog.write("playback.log", "caption_finish completed=$completed " +
                    "chars=${message.display_text?.text?.length ?: 0}")
                captions.finish(message, completed); animateCaptions()
            },
            onPerformanceAudio = { speaking, durationMs ->
                if (::live2D.isInitialized) live2D.setPerformanceSpeech(speaking, durationMs)
            },
            onPlaybackProgress = { message, playedMs, totalMs, known ->
                captions.progress(message, playedMs, totalMs, known); animateCaptions()
            },
            playbackSpeed = { SpeechTempo.speed(this) })
        val voiceProfile = LocalVoiceSettings.voiceProfile(this, callCharacter)
        Log.i("PRTS-Language", "call language=$replyLanguage local_voice_language=${voiceProfile.language} local_voice_id=${voiceProfile.voiceId} legacy=${connectionProfile?.usesLegacyCallSocket == true}")
        localSpeech = SpeechSynthesisRouter(this, voiceProfile,
            { payload -> if (audioLive) speech.enqueue(payload) }, ::notice,
            { finishLocalOpeningIfIdle(); renderStatus(); schedulePlaybackAck() },
            onLocalPayload = { payload -> if (audioLive) speech.enqueueLocal(payload) else payload.close() },
            onProgress = { progress ->
                if (foreground && !speech.isBusy) {
                    activityState = when (progress.phase) {
                        "preparing" -> "正在准备语音"
                        "synthesizing", "semantic", "conditioning", "acoustic" -> "正在生成语音"
                        "ready" -> "正在准备播放"
                        "waveform" -> "正在整理语音"
                        "failed" -> ""
                        else -> activityState
                    }
                    renderStatus()
                }
            },
            onStreamingPcm = { source, pcm -> audioLive && speech.enqueuePcm(source, pcm) },
            onStreamingComplete = { source, result ->
                speech.completeStreaming(source, result)
                finishLocalOpeningIfIdle(); renderStatus(); schedulePlaybackAck()
            }, mossCharacter = callCharacter)
        mic = MicRecorder({ samples ->
            b.btnMic.isPressed = false
            muteAutoListenForTurn()
            activityState = "正在发送语音"
            renderStatus()
            ws.sendMic(samples, { faceObservation() }, replyLanguage) { ok ->
                val localDsh = connectionProfile?.mode == com.prts.client.net.ConnectionMode.LOCAL_DSH
                activityState = if (ok) "正在听清你说的话" else if (localDsh) "本机语音识别未能开始" else "语音没发出去"
                renderStatus()
                if (!ok) notice(if (localDsh) "本机语音识别未能开始，请检查DSHGo语音设置或稍后重试" else "语音没发出去，请等连接恢复再试")
            }
        }, { message -> b.btnMic.isPressed = false; activityState = ""; renderStatus(); notice(message) })

        // 端点判定换 Silero VAD（2026-11-23 0.0.97）：能量门在这台手机的底噪里分不开人声。
        // 模型加载失败时 vad=null，AutoListener 自动退回能量门——通话不受影响，只是端点变慢。
        vad = SileroVad.load(assets)
        DiagLog.write("call.log", "auto-listen endpoint=" +
            (if (vad != null) "silero-vad" else "energy-fallback"))
        Log.i("PRTS-Call", "auto-listen endpoint=" + (if (vad != null) "silero-vad" else "energy-fallback"))
        autoListen = AutoListener(
            onUtterance = { samples, bargeStarted -> handler.post { onAutoUtterance(samples, bargeStarted) } },
            onState = { state ->
                handler.post {
                    if (foreground) {
                        activityState = state
                        renderStatus()
                    }
                }
            },
            onDebug = { line -> DiagLog.write("barge.log", line) },
            // 打断不等端点：barge 下检测到明显人声就停播（端点还要等 200ms 静音，实测太慢）。
            onBargeStart = { speechMs ->
                DiagLog.write("playback.log", "barge_voice action=interrupt_early speech_ms=$speechMs")
                handler.post {
                    // 方案 A：只有「说话打断」开关真的打开时才掐她的播放；现在是恒 false，
                    // 这里就只留日志——收进来的话由 onAutoUtterance 排队，等她说完再送。
                    if (AutoListenSettings.bargeIn(this@CallActivity) && herVoiceBusy()) {
                        earlyBargeInterruptAt = SystemClock.elapsedRealtime()
                        interruptReply("auto_barge_in_early")
                    }
                }
            },
            vad = vad,
        )
        callGuard = CallGuard(this) { inCall -> handler.post { onCallStateChanged(inCall) } }.also { it.start() }
        autoBusyAt = SystemClock.elapsedRealtime()
        handler.postDelayed(autoListenTick, 400)

        b.btnMic.setOnClickListener {
            prioritizeCallInput()
            motionDirector.userInteraction()
            if (callGuard?.inCall == true) {
                notice("正在通话中，先挂断电话再用麦克风")
                return@setOnClickListener
            }
            // Master switch: one tap mutes/unmutes the hands-free listener (and any manual take).
            manualMuted = !manualMuted
            if (manualMuted) {
                if (mic.isRecording) mic.stop(true)
                autoListen?.setMuted(true)
                activityState = "麦克风已关，她听不到了"
                notice("麦克风已关：再点一下恢复")
            } else {
                autoBusyAt = SystemClock.elapsedRealtime()
                autoListen?.setMuted(false)
                activityState = "麦克风已开"
                notice("麦克风已开")
            }
            renderStatus()
        }

        // Long press keeps the old push-to-talk behaviour for a single utterance.
        b.btnMic.setOnLongClickListener {
            prioritizeCallInput()
            motionDirector.userInteraction()
            if (callGuard?.inCall == true) {
                return@setOnLongClickListener true
            }
            if (mic.isRecording) {
                mic.stop(true)
                activityState = "正在结束录音"
                renderStatus()
            } else if (mic.isBusy) {
                notice("正在整理上一段录音，请稍等")
            } else if (!ws.isReady || !providerReady) {
                notice(connectionSettingsError ?: connectionState)
            } else {
                interruptReply("manual_mic_long_press")
                if (mic.start()) {
                    activityState = "麦克风已开，再长按一次关闭并发送"
                    renderStatus()
                }
            }
            true
        }

        b.inputText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                if (count > 0 || before > 0) prioritizeCallInput()
            }
            override fun afterTextChanged(text: android.text.Editable?) = Unit
        })
        b.inputText.setOnEditorActionListener { v, _, _ ->
            sendTypedMessage()
            true
        }
        b.btnScreenLook.setOnClickListener { lookAtScreen() }
        albumWatcher = AlbumWatcher(this) { jpeg, at, name ->
            if (::ws.isInitialized) ws.sendAlbumPhoto(jpeg, at, name) else false
        }.also { it.start() }
        b.btnSend.setOnClickListener { sendTypedMessage() }
        b.btnCamera.setOnClickListener {
            prioritizeCallInput()
            motionDirector.userInteraction()
            camera.openPicker()
        }
        b.btnRemovePhoto.setOnClickListener { if (!imageSending) { pendingImage = null; renderImageDraft() } }
        b.btnAction.setOnClickListener { showExpressionActions() }

        if (::live2D.isInitialized) {
            directPlacement = DirectCharacterPlacementController(live2D, callCharacter,
                enabled = { foreground && !placementEditing && !actionPanelOpen && !mic.isRecording },
                blockedViews = { listOfNotNull(b.inputBar, b.buttonCluster, b.callChat, b.photoDraft,
                    b.cameraPreviewCard, b.faceTrackingToggle, b.allowGuestToggle,
                    b.characterTouchToggle, b.touchInventoryToggle, b.petCaption,
                    b.callPermissionsButton,
                    (b.callName.parent as? View)?.parent as? View) },
                onInteraction = { motionDirector.userInteraction() },
                onTap = ::recordScreenTap,
                onHold = ::recordCharacterHold,
                onManipulationChanged = {
                    motionDirector.manualInteraction()
                },
            )
        }

        b.btnHangup.setOnClickListener { finish() }
    }

    private fun showAutoListenSettings() {
        val sheet = BottomSheetDialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(actionDp(22), actionDp(12), actionDp(22), actionDp(24))
            setBackgroundResource(R.drawable.ui_sheet_surface)
        }
        fun heading(text: String, size: Float, bold: Boolean = false) = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(ContextCompat.getColor(this@CallActivity,
                if (bold) R.color.ui_text else R.color.ui_text_muted))
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        var gate = AutoListenSettings.gateMul(this)
        var silence = AutoListenSettings.silenceMs(this)
        var minSpeech = AutoListenSettings.minSpeechMs(this)
        fun persist() {
            AutoListenSettings.save(this, gate, silence, minSpeech)
            autoListen?.updateConfig(AutoListenSettings.config(this))
            maybeStartAutoListen()
        }
        fun choices(labels: List<String>, current: Int, pick: (Int) -> Unit): LinearLayout {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val views = ArrayList<TextView>()
            for ((index, label) in labels.withIndex()) {
                val view = TextView(this).apply {
                    text = label
                    textSize = 13f
                    gravity = Gravity.CENTER
                    minHeight = actionDp(42)
                    setPadding(actionDp(6), actionDp(7), actionDp(6), actionDp(7))
                    setTextColor(ContextCompat.getColor(this@CallActivity, R.color.ui_text))
                    setBackgroundResource(
                        if (index == current) R.drawable.ui_chip_selected else R.drawable.ui_chip)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        views.forEachIndexed { i, item ->
                            item.setBackgroundResource(
                                if (i == index) R.drawable.ui_chip_selected else R.drawable.ui_chip)
                        }
                        pick(index)
                    }
                }
                views.add(view)
                line.addView(view, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (index < labels.size - 1) marginEnd = actionDp(8)
                })
            }
            return line
        }

        content.addView(View(this).apply { setBackgroundResource(R.drawable.ui_sheet_handle) },
            LinearLayout.LayoutParams(actionDp(36), actionDp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = actionDp(18)
            })
        content.addView(heading("自动收音", 21f, true))
        content.addView(heading("说完停顿一下就发送；她说话时麦克风自动关闭。", 12f),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = actionDp(6); bottomMargin = actionDp(16) })

        content.addView(heading("灵敏度（越低越容易触发）", 13f, true))
        content.addView(choices(listOf("低", "中", "高"),
            when { gate >= 2.8f -> 0; gate <= 1.7f -> 2; else -> 1 }) { index ->
            gate = listOf(3.0f, 2.2f, 1.5f)[index]
            persist()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = actionDp(8); bottomMargin = actionDp(14) })

        content.addView(heading("断句等待（说完了多久算一句）", 13f, true))
        content.addView(choices(listOf("0.5 秒", "0.8 秒", "1.2 秒"),
            when { silence <= 600 -> 0; silence >= 1000 -> 2; else -> 1 }) { index ->
            silence = listOf(500, 800, 1200)[index]
            persist()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = actionDp(8); bottomMargin = actionDp(14) })

        content.addView(heading("最短一句（更短当噪声丢掉）", 13f, true))
        content.addView(choices(listOf("0.3 秒", "0.5 秒", "0.8 秒"),
            when { minSpeech <= 400 -> 0; minSpeech >= 700 -> 2; else -> 1 }) { index ->
            minSpeech = listOf(300, 500, 800)[index]
            persist()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = actionDp(8); bottomMargin = actionDp(10) })

        content.addView(SwitchCompat(this).apply {
            text = "锁屏时继续收音"
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@CallActivity, R.color.ui_text))
            isChecked = AutoListenSettings.screenOff(this@CallActivity)
            setOnCheckedChangeListener { _, checked ->
                AutoListenSettings.setScreenOff(this@CallActivity, checked)
                notice(if (checked) "锁屏也会继续听" else "只在亮屏时听，更省电")
                if (!checked && !this@CallActivity.foreground) stopAutoListen()
            }
        }, LinearLayout.LayoutParams(-1, actionDp(48)).apply { topMargin = actionDp(6) })

        content.addView(heading("回复期间麦克风完全关闭（半双工），不能插话；点麦克风仍是手动模式。", 11f),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = actionDp(4) })

        content.addView(TextView(this).apply {
            text = "完成"
            textSize = 14f
            gravity = Gravity.CENTER
            minHeight = actionDp(46)
            setTextColor(ContextCompat.getColor(this@CallActivity, R.color.ui_text))
            setBackgroundResource(R.drawable.ui_chip)
            isClickable = true
            isFocusable = true
            setOnClickListener { sheet.dismiss() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = actionDp(14) })

        sheet.setContentView(content)
        sheet.setOnDismissListener { actionPanelOpen = false }
        sheet.show()
    }

    private fun showExpressionActions() {
        if (!::live2D.isInitialized) { notice("角色还在准备，请稍等"); return }
        val expressions = live2D.availableExpressions()
        // 2026-11-23: the panel lists whatever the rig registered. Hand gestures (举手/放下/挥手/
        // 招手/伸手/害羞抬手) are no longer registered at all, so no hand chips can appear.
        val gestures = live2D.availableGestures().filter {
            !it.id.startsWith("pose:") && !it.id.startsWith("touch:")
        }
        actionPanelOpen = true
        motionDirector.manualInteraction()
        val sheet = BottomSheetDialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(actionDp(22), actionDp(12), actionDp(22), actionDp(24))
            setBackgroundResource(R.drawable.ui_sheet_surface)
        }
        content.addView(View(this).apply {
            setBackgroundResource(R.drawable.ui_sheet_handle)
        }, LinearLayout.LayoutParams(actionDp(36), actionDp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = actionDp(18)
        })
        content.addView(TextView(this).apply {
            text = "表情与动作"; textSize = 21f
            setTextColor(ContextCompat.getColor(this@CallActivity, R.color.ui_text))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(this).apply {
            text = "平时会跟着对话表达，也可以在这里手动调整。"; textSize = 12f
            setTextColor(ContextCompat.getColor(this@CallActivity, R.color.ui_text_muted))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = actionDp(7); bottomMargin = actionDp(18) })
        content.addView(SwitchCompat(this).apply {
            text = "自动表情与动作"
            textSize = 14f
            contentDescription = "自动表情与动作开关"
            setTextColor(ContextCompat.getColor(this@CallActivity, R.color.ui_text))
            isChecked = motionDirector.enabled
            setOnCheckedChangeListener { _, checked ->
                motionDirector.setEnabled(checked)
                if (!checked && ::live2D.isInitialized) live2D.resetMotionState()
                getSharedPreferences("prts", MODE_PRIVATE).edit()
                    .putBoolean("automatic_character_motion", checked).apply()
            }
        }, LinearLayout.LayoutParams(-1, actionDp(48)))
        content.addView(SwitchCompat(this).apply {
            text = "主动聊天"
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@CallActivity, R.color.ui_text))
            isChecked = proactiveEnabled
            setOnCheckedChangeListener { _, checked ->
                proactiveEnabled = checked
                prioritizeCallInput()
                CallProactiveSettings.saveEnabled(this@CallActivity, checked)
            }
        }, LinearLayout.LayoutParams(-1, actionDp(48)))
        content.addView(SwitchCompat(this).apply {
            text = "自动收音（免按键）"
            textSize = 14f
            contentDescription = "自动收音开关：关闭后仍可点麦克风说话"
            setTextColor(ContextCompat.getColor(this@CallActivity, R.color.ui_text))
            isChecked = AutoListenSettings.enabled(this@CallActivity)
            setOnCheckedChangeListener { _, checked ->
                AutoListenSettings.setEnabled(this@CallActivity, checked)
                syncAutoListen()
            }
        }, LinearLayout.LayoutParams(-1, actionDp(48)))
        content.addView(TextView(this).apply {
            text = "收音参数：灵敏度 / 断句 / 锁屏继续"
            contentDescription = "打开自动收音参数设置"
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(actionDp(14), 0, actionDp(14), 0)
            setTextColor(ContextCompat.getColor(this@CallActivity, R.color.ui_text))
            setBackgroundResource(R.drawable.ui_chip)
            isClickable = true; isFocusable = true
            setOnClickListener {
                sheet.dismiss()
                handler.postDelayed({ if (!isFinishing && !isDestroyed) showAutoListenSettings() }, 180)
            }
        }, LinearLayout.LayoutParams(-1, actionDp(46)).apply { bottomMargin = actionDp(8) })
        content.addView(TextView(this).apply {
            text = "调整角色位置与大小"; contentDescription = "调整角色位置与大小"
            textSize = 13f; gravity = Gravity.CENTER_VERTICAL; setPadding(actionDp(14), 0, actionDp(14), 0)
            setTextColor(ContextCompat.getColor(this@CallActivity, R.color.ui_text))
            setBackgroundResource(R.drawable.ui_chip); isClickable = true; isFocusable = true
            setOnClickListener { sheet.dismiss(); openCharacterPlacement() }
        }, LinearLayout.LayoutParams(-1, actionDp(46)).apply { bottomMargin = actionDp(16) })

        fun label(text: String) = content.addView(TextView(this).apply {
            this.text = text; textSize = 12f
            setTextColor(ContextCompat.getColor(this@CallActivity, R.color.ui_text_secondary))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = actionDp(9) })

        fun chip(text: String, description: String = text, clicked: () -> Unit) = TextView(this).apply {
            this.text = text; textSize = 13f; gravity = Gravity.CENTER
            contentDescription = description
            minHeight = actionDp(47); maxLines = 2
            setPadding(actionDp(6), actionDp(9), actionDp(6), actionDp(9))
            setTextColor(ContextCompat.getColor(this@CallActivity, R.color.ui_text))
            setBackgroundResource(R.drawable.ui_chip)
            isClickable = true; isFocusable = true
            setOnClickListener { clicked() }
        }

        fun grid(buttons: List<View>, columns: Int) {
            for (group in buttons.chunked(columns)) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for (index in 0 until columns) {
                    val item = group.getOrNull(index) ?: View(this)
                    row.addView(item, LinearLayout.LayoutParams(0, -2, 1f).apply {
                        if (index < columns - 1) marginEnd = actionDp(8)
                    })
                }
                content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = actionDp(8) })
            }
        }

        label("表情")
        grid(expressions.map { expression ->
            chip(expression.title, "表情：${expression.title}") {
                motionDirector.manualInteraction(manualExpression = true)
                live2D.setExpression(expression.id)
                if (expression.id == "mobile:neutral") live2D.resetMotionState()
                Log.i("PRTS-Call", "manual expression selected: ${expression.id}")
                sheet.dismiss()
            }
        }, 3)

        if (gestures.isNotEmpty()) {
            content.addView(View(this), LinearLayout.LayoutParams(1, actionDp(10)))
            label("手动动作幅度")
            lateinit var gentle: TextView
            lateinit var extended: TextView
            fun refreshIntensity() {
                for ((button, selected) in listOf(gentle to !manualGestureExtended, extended to manualGestureExtended)) {
                    button.isSelected = selected
                    button.setBackgroundResource(if (selected) R.drawable.ui_chip_selected else R.drawable.ui_chip)
                    button.setTextColor(ContextCompat.getColor(this, if (selected) R.color.ui_text else R.color.ui_text_secondary))
                    button.contentDescription = "手动动作幅度：${button.text}，${if (selected) "已选择" else "未选择"}"
                }
            }
            gentle = chip("轻柔") { manualGestureExtended = false; refreshIntensity() }
            extended = chip("舒展") { manualGestureExtended = true; refreshIntensity() }
            grid(listOf(gentle, extended), 2)
            refreshIntensity()
            content.addView(View(this), LinearLayout.LayoutParams(1, actionDp(10)))
            label("动作")
            grid(gestures.map { gesture ->
                val title = when (gesture.id) {
                    "call:nod" -> "点头"
                    "call:shake" -> "摇头"
                    "call:bow" -> "欠身"
                    "call:sway" -> "摇摆"
                    else -> gesture.title
                }
                chip(title, "动作：$title") {
                    // 2026-11-23 owner: 手部动作不要了 —— 举手/放下/挥手/招手/伸手/害羞抬手 都不再注册，
                    // 面板自然只剩头/身体/腿的动作。害羞只做表情。
                    val shyPose = gesture.id == "call:shy"
                    motionDirector.manualInteraction(manualExpression = shyPose)
                    if (shyPose) live2D.setExpression("mobile:shy")
                    if (live2D.playGesture(gesture.id, extended = manualGestureExtended)) {
                        Log.i("PRTS-Call", "manual gesture queued: ${gesture.id} extended=$manualGestureExtended")
                        sheet.dismiss()
                    } else notice("这个动作还在准备，请稍等")
                }
            }, 2)
        }
        content.addView(TextView(this).apply {
            text = "手动表情优先15秒，再回到当前心情；自动动作优先用舒展幅度，这里可以切回轻柔。"
            textSize = 10f
            setTextColor(ContextCompat.getColor(this@CallActivity, R.color.ui_text_muted))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = actionDp(9) })
        sheet.setContentView(ScrollView(this).apply { isFillViewport = false; addView(content) })
        sheet.setOnShowListener {
            sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            sheet.behavior.maxHeight = (resources.displayMetrics.heightPixels * .78f).toInt()
            sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            sheet.window?.setDimAmount(.12f)
        }
        sheet.setOnDismissListener { actionPanelOpen = false; motionDirector.userInteraction() }
        sheet.show()
    }

    private fun openCharacterPlacement() {
        if (!::live2D.isInitialized || placementEditing) return
        directPlacement?.finishGesture()
        if (mic.isRecording) { mic.stop(false); b.btnMic.isPressed = false; activityState = ""; renderStatus() }
        placementEditing = true
        motionDirector.manualInteraction()
        placementEditor = CharacterPlacementEditor.show(this, callCharacter, live2D) {
            placementEditor = null; placementEditing = false
            directPlacement?.reload()
            if (foreground) motionDirector.manualInteraction()
        }
    }

    private fun actionDp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private var touchDiagnosticsLogged = false

    private fun recordScreenTap(x: Float, y: Float) {
        if (!characterTouchOn) return
        if (!touchDiagnosticsLogged) {
            touchDiagnosticsLogged = true
            traceTouch("diag gestures=${live2D.availableGestures().map { it.id }} " +
                "expressions=${live2D.availableExpressions().map { it.id }} " +
                "view=${live2D.width}x${live2D.height} render=${live2D.renderingDiagnostics()}")
        }
        val region = live2D.characterTouchRegion(x, y)
        if (region == null) { traceTouch("tap x=${x.toInt()} y=${y.toInt()} region=none"); return }
        traceTouch("tap x=${x.toInt()} y=${y.toInt()} region=$region")
        if (!foreground || actionPanelOpen || placementEditing || mic.isRecording ||
            directPlacement?.isManipulating == true) return
        val reaction = motionDirector.onCharacterTouch(region) ?: return
        live2D.performHapticFeedback(reaction.haptic)
        logCharacterTouch("tap", region, reaction)
    }

    /** A still finger held on the character is a head-pat, not an accidental long press. */
    private fun recordCharacterHold(x: Float, y: Float) {
        if (!characterTouchOn) return
        val region = live2D.characterTouchRegion(x, y) ?: return
        if (!foreground || actionPanelOpen || placementEditing || mic.isRecording ||
            directPlacement?.isManipulating == true) return
        val reaction = motionDirector.onCharacterTouch(region, held = true) ?: return
        live2D.performHapticFeedback(reaction.haptic)
        logCharacterTouch("hold", region, reaction)
    }

    /** This phone's logcat buffer is disabled by the ROM, so the touch ground truth is a small file
     * under `files/` that `adb shell run-as com.prts.client cat files/touch_debug.log` can read. */
    private fun logCharacterTouch(kind: String, region: String, reaction: CharacterTouchPolicy.Reaction) {
        Log.i("PRTS-Touch", "character_reaction region=$region kind=$kind motion=${reaction.gesture}")
        // Her own sentence playing does not cancel the grunt any more: it is ducked and mixed in.
        // The old hard block was one of the two reasons pokes were silent (0.0.50).
        val herVoice = (::speech.isInitialized && speech.isBusy) || localSpeech?.isBusy == true
        traceTouch("react kind=$kind region=$region motion=${reaction.gesture} " +
            "expression=${reaction.expression} label=${reaction.label} voice=${reaction.voice} " +
            "her_voice=$herVoice")
        touchVoice?.play(reaction.voice, region, kind, duck = herVoice) { durationMs ->
            holdTouchExpression(reaction.expression, durationMs)
        }
    }

    /** Owner 2026-11-16: 「我想要的是表情能跟着话发生变化」.
     *
     * The continuous five-weight channel (setEmotionWeights) is blended against the JS performance
     * engine and reads as almost nothing on this rig. The author's own Expression files are a hard
     * override (manualPerformanceFace=true), so the face actually follows the sentence, then returns
     * to the automatic mood when the clip ends. Never clears a sentence she is speaking right now. */
    private fun holdTouchExpression(expression: String, durationMs: Int) {
        if (!::live2D.isInitialized || expression.isBlank()) return
        val tag = "mobile:$expression"
        if (tag !in live2D.availableExpressions().map { it.id }) {
            traceVoice("face skip reason=no_expression want=$tag")
            return
        }
        live2D.setExpression(tag)
        traceVoice("face hold id=$tag ms=$durationMs")
        // Body motion follows the sentence, not the poke (owner 2026-11-16): hand the clip duration
        // to the performance engine so her talking motion runs exactly as long as the line.
        val herVoiceNow = (::speech.isInitialized && speech.isBusy) || localSpeech?.isBusy == true
        if (!herVoiceNow) {
            live2D.setPerformanceSpeech(true, durationMs.toLong())
            traceVoice("perform speech on ms=$durationMs")
        }
        val keep = (durationMs + 250L).coerceIn(600L, 8_000L)
        handler.postDelayed({
            val herVoice = (::speech.isInitialized && speech.isBusy) || localSpeech?.isBusy == true
            if (!herVoice) {
                live2D.setExpression("mobile:neutral")
                live2D.setPerformanceSpeech(false, 0)
                traceVoice("face release id=$tag")
            }
        }, keep)
    }

    private fun traceTouch(line: String) = traceFile(line, withBounds = true)

    /** Player lines (start / skip / error) share the same file but skip the bounds dump. */
    private fun traceVoice(line: String) = traceFile(line, withBounds = false)

    private fun traceFile(line: String, withBounds: Boolean) {
        runCatching {
            val file = java.io.File(filesDir, "touch_debug.log")
            // Raised from 96 KB: player lines now share this file and the evidence must survive
            // long enough to be read with `adb ... cat files/touch_debug.log`.
            if (file.length() > 256 * 1024) file.delete()
            file.appendText("${System.currentTimeMillis()} $line" +
                (if (withBounds) " bounds=${live2D.touchBoundsSnapshot()}" else "") + "\n")
        }
    }
    private fun renderImageDraft() {
        val image = pendingImage
        if (image !== draftThumbnailImage) {
            // Android may still retain a displayed bitmap; release references instead of
            // recycling it under a display list. Only a new draft needs a small decode.
            b.photoDraftThumb.setImageDrawable(null)
            draftThumbnail = null
            draftThumbnailImage = image
            if (image != null) {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(image.jpeg, 0, image.jpeg.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 256) sample *= 2
                draftThumbnail = android.graphics.BitmapFactory.decodeByteArray(image.jpeg, 0, image.jpeg.size,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                b.photoDraftThumb.setImageBitmap(draftThumbnail)
            }
        }
        b.photoDraft.visibility = if (image != null && !characterTouchOn) View.VISIBLE else View.GONE
        b.photoDraftLabel.text = if (imageSendUncertain) "发送结果未确认\n不会自动重发" else "照片待发送\n可在下方加上文字"
        (b.callChat.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let {
            val margin = actionDp(if (image != null) 248 else 184)
            if (it.bottomMargin != margin) { it.bottomMargin = margin; b.callChat.layoutParams = it }
        }
        b.btnSend.contentDescription = if (image != null) "发送待发送照片和输入文字" else "发送输入文字"
    }

    private fun faceObservation(): FaceObservationMetadata? {
        val observed = camera.takeFreshObservation(3000) ?: return null
        if (observed.version != 1 || observed.faceCount != 1 || observed.ageMs !in 0..3000) return null
        val probabilities = listOf(observed.confidence, observed.smileProbability,
            observed.leftEyeOpenProbability, observed.rightEyeOpenProbability, observed.mouthOpen)
        val angles = listOf(observed.headYawDeg, observed.headPitchDeg, observed.headRollDeg)
        if (probabilities.any { it != null && (!it.isFinite() || it !in 0.0..1.0) }
            || angles.any { it != null && (!it.isFinite() || it !in -90.0..90.0) }) return null
        Log.i("PRTS-Camera", "observation piggyback prepared age_ms=${observed.ageMs} face_count=1")
        return FaceObservationMetadata(age_ms = observed.ageMs, face_count = observed.faceCount,
            confidence = observed.confidence, smile_probability = observed.smileProbability,
            left_eye_open_probability = observed.leftEyeOpenProbability,
            right_eye_open_probability = observed.rightEyeOpenProbability, mouth_open = observed.mouthOpen,
            head_yaw_deg = observed.headYawDeg, head_pitch_deg = observed.headPitchDeg, head_roll_deg = observed.headRollDeg)
    }

    private fun sendPendingImage(text: String) {
        if (imageSending) return
        if (!foreground || !::ws.isInitialized || !ws.isReady || !providerReady) {
            if (foreground && pendingImage != null) notice("照片留在输入区，连接就绪后请按发送")
            return
        }
        val image = pendingImage ?: return
        if (text.length > 500) { notice("照片附带的文字最多500字，请缩短后发送"); return }
        imageSending = true
        imageSendUncertain = false
        val attempt = ++imageAttempt
        b.btnSend.isEnabled = false
        interruptReply("send_image")
        muteAutoListenForTurn()
        activityState = "正在发送图片"
        ws.sendImage(image.jpeg, text, faceObservation(), replyLanguage) { sent ->
            if (attempt != imageAttempt || isDestroyed) return@sendImage
            imageSending = false
            b.btnSend.isEnabled = true
            if (sent) {
                if (pendingImage === image) pendingImage = null
                if (b.inputText.text.toString().trim() == text) b.inputText.setText("")
                renderImageDraft()
                if (text.isNotBlank()) motionDirector.onUserInput(text)
                addBubble("我", "[图片] " + text.ifBlank { "看看这张图片" })
                activityState = "正在看图片"
            } else { activityState = "图片未发送"; notice("照片仍在输入区，请等连接恢复后再按发送") }
            renderStatus()
        }
        renderStatus()
    }

    private fun finishUnconfirmedImageSend(showNotice: Boolean) {
        if (!imageSending) return
        imageSending = false
        imageAttempt++ // Invalidates a delayed completion from the disconnected socket.
        imageSendUncertain = true
        b.btnSend.isEnabled = true
        renderImageDraft()
        activityState = "照片发送结果未确认"
        Log.i("PRTS-Camera", "image send result unknown after connection/lifecycle change; draft retained; no automatic replay")
        if (showNotice) notice("照片发送结果未确认，仍保留在输入区，不会自动重发")
    }

    private fun providerConfigured() {
        val local = localSpeech
        if (local == null) {
            providerReady = true; connectionState = "已连接"; activityState = ""
            handler.postDelayed({ sendLocationNow() }, 1500)
            renderImageDraft()
        } else if (!local.available) {
            providerReady = false
            if (local.usesLocalModel) {
                val preparation = Audio8ModelStore.status(this)
                connectionState = if (preparation.phase == "needs_reference") "请导入 Audio8 参考音" else "正在准备手机语音"
                if (preparation.error != null) notice("手机语音准备失败，请检查可用存储后重试")
            } else { connectionState = "请检查语音API设置"; notice(local.unavailableReason) }
        } else {
            providerReady = false; connectionState = "正在启用手机语音"
            if (!ws.configureVoice(true)) notice("手机语音模式没有发送成功")
        }
        renderStatus()
        maybeStartAutoListen()
    }
    private fun prioritizeCallInput() {
        if (proactive.userInput()) {
            interruptReply("proactive_user_input")
            activityState = ""
            renderStatus()
        }
    }

    private fun maybeSpeakProactively() {
        if (proactive.finishAfterPlayback(proactivePlaybackComplete,
                playbackCompletePending || speech.isBusy || localSpeech?.isBusy == true)) {
            remoteProactiveId = null
            proactivePlaybackComplete = false
        }
        if (!proactiveEnabled || !::ws.isInitialized || !ws.isReady || !providerReady ||
            (!remoteProactiveSupported && connectionProfile?.mode != com.prts.client.net.ConnectionMode.LOCAL_DSH) ||
            !window.decorView.hasWindowFocus() || actionPanelOpen || placementEditing || mic.isBusy ||
            camera.isPhotoInteractionPending || speech.isBusy || localSpeech?.isBusy == true ||
            activityState.isNotBlank() || playbackCompletePending || pendingImage != null ||
            b.inputText.text.isNotBlank() || !proactive.canStart()) return
        val observation = faceObservation()
        val hint = when {
            (observation?.smile_probability ?: 0.0) >= .7 -> "smile"
            observation != null -> "present"
            else -> null
        }
        val sent = if (remoteProactiveSupported) {
            val id = java.util.UUID.randomUUID().toString().replace("-", "")
            ws.sendRemoteProactive(id, hint, replyLanguage).also {
                if (it) {
                    remoteProactiveId = id
                    proactivePlaybackComplete = false
                }
            }
        } else {
            val context = when (hint) {
                "smile" -> "前置相机本机分析发现对方可能在微笑；只能当成不确定的线索。"
                "present" -> "前置相机本机分析检测到一张脸；表情含义不确定。"
                else -> ""
            }
            val prompt = "【通话应用的安静提醒，非用户发言】现在通话暂时安静。$context" +
                "用当前角色自然地主动说一句短话，衔接最近话题或轻声开个新话头。" +
                "不要读出提醒，不要凭空描述你看到的画面，不要调用工具或执行操作。"
            ws.sendProactiveText(prompt, replyLanguage)
        }
        if (sent) {
            proactive.started()
            activeLocalRequest = "call-proactive-${SystemClock.elapsedRealtime()}-${++submittedSequence}"
            traceCall("proactive_submit", requestId = activeLocalRequest)
            activityState = "正在回复"
            renderStatus()
        }
    }

    /** Wake word and sentence arrived together: send it once the socket and provider are up. */
    private var pendingWakeText = ""
    private var wakeTextDeadline = 0L
    private val wakeTextSender = object : Runnable {
        override fun run() {
            val text = pendingWakeText
            if (text.isBlank()) return
            val ready = ::ws.isInitialized && ws.isReady && providerReady && foreground
            if (ready) {
                pendingWakeText = ""
                Log.i("PRTS-Call", "wake text sending: ${text.take(40)}")
                b.inputText.setText(text)
                sendTypedMessage()
                return
            }
            if (SystemClock.elapsedRealtime() > wakeTextDeadline) {
                Log.w("PRTS-Call", "wake text dropped (not ready): ${text.take(40)}")
                pendingWakeText = ""
                return
            }
            handler.postDelayed(this, 500)
        }
    }

    private fun scheduleWakeText() {
        if (pendingWakeText.isBlank()) return
        wakeTextDeadline = SystemClock.elapsedRealtime() + 45000
        handler.postDelayed(wakeTextSender, 800)
    }

    /** 接听主动来电：只发一条服务端认得的「接起了」标记，不加气泡、不替主人说话。 */
    private val wakeAcceptSender = object : Runnable {
        override fun run() {
            if (!wakeAcceptPending) return
            val ready = ::ws.isInitialized && ws.isReady && providerReady && foreground
            if (ready) {
                interruptReply("wake_accept")
                muteAutoListenForTurn()
                if (ws.sendText(WAKE_ACCEPTED_TEXT, faceObservation(), replyLanguage)) {
                    wakeAcceptPending = false
                    Log.i("PRTS-Call", "wake accepted signal sent")
                    activeLocalRequest = "call-wake-${SystemClock.elapsedRealtime()}-${++submittedSequence}"
                    traceCall("wake_accepted_submit", requestId = activeLocalRequest)
                    activityState = "正在回复"
                    renderStatus()
                    return
                }
            }
            if (SystemClock.elapsedRealtime() > wakeTextDeadline) {
                Log.w("PRTS-Call", "wake accept dropped (not ready)")
                wakeAcceptPending = false
                return
            }
            handler.postDelayed(this, 500)
        }
    }

    private fun scheduleWakeAccept() {
        if (!wakeAcceptPending) return
        wakeTextDeadline = SystemClock.elapsedRealtime() + 45000
        handler.postDelayed(wakeAcceptSender, 800)
    }

    private fun sendTypedMessage() {
        prioritizeCallInput()
        val text = b.inputText.text.toString().trim()
        if (pendingImage != null) { sendPendingImage(text); return }
        if (text.isEmpty()) return
        if (!ws.isReady || !providerReady) { notice("${connectionSettingsError ?: connectionState} · 文字未发送"); return }
        interruptReply("send_text")
        muteAutoListenForTurn()
        if (ws.sendText(text, faceObservation(), replyLanguage)) {
            activeLocalRequest = "call-${SystemClock.elapsedRealtime()}-${++submittedSequence}"
            traceCall("call_submit", requestId = activeLocalRequest, inputChars = text.codePointCount(0, text.length))
            motionDirector.onUserInput(text)
            addBubble("我", text)
            activityState = "正在回复"
            b.inputText.setText("")
            renderStatus()
        } else notice("文字没发出去，请稍后再试")
    }

    private fun callTime(): String {
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }

    private fun renderStatus() {
        b.callTimer.text = "${callTime()} · $connectionState" + if (activityState.isBlank()) "" else " · $activityState"
        val ready = ::ws.isInitialized && ws.isReady && providerReady
        b.connectionDot.alpha = if (ready) 1f else 0.4f
        val microphoneOn = ::mic.isInitialized && mic.isRecording
        // 短按现在是「麦克风总开关」：关着的时候要让按钮看起来就是关的，别只更新一行小字。
        b.micLabel.text = when {
            manualMuted -> "麦克风 · 已关（长按说话）"
            microphoneOn -> "麦克风 · 开"
            else -> "麦克风 · 关"
        }
        b.btnMic.isSelected = !manualMuted && microphoneOn
        b.btnMic.isEnabled = !::mic.isInitialized || microphoneOn || !mic.isBusy
        b.btnMic.setBackgroundResource(if (!manualMuted && microphoneOn) R.drawable.ui_chip_selected else R.drawable.ui_glass_button)
        b.btnMic.contentDescription = when {
            manualMuted -> "麦克风已关，点击恢复收音；长按手动录音"
            microphoneOn -> "麦克风已开启，点击关闭并发送录音，每段最多60秒"
            else -> "麦克风已关闭，点击打开收音"
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) b.btnMic.stateDescription = if (!manualMuted) "开启" else "关闭"
    }

    /** 触屏互动改成按钮：只显示开/关，不再是拨动开关。 */
    private fun renderCharacterTouchButton() {
        b.characterTouchToggle.text = if (characterTouchOn) "触屏互动 · 开" else "触屏互动 · 关"
        b.characterTouchToggle.setBackgroundResource(
            if (characterTouchOn) R.drawable.ui_chip_selected else R.drawable.ui_glass_panel)
        b.characterTouchToggle.isSelected = characterTouchOn
        b.characterTouchToggle.contentDescription = if (characterTouchOn)
            "触屏互动按钮，当前开启；点她会有反应，长按摸头会害羞"
        else "触屏互动按钮，当前关闭；点击开启"
    }

    /** 触屏互动的「摸鱼模式」：只留她 + 这一个按钮，输入栏/麦克风/摄像头/挂断/名字牌全部收起。 */
    private fun applyPetMode() {
        val pet = characterTouchOn
        if (pet && (liveCameraOn || frontCameraOn)) {
            // 控件收起后看不到预览，就顺手把相机也关掉，别让镜头空开着。
            liveCameraOn = false
            frontCameraOn = false
            applyLiveCamera()
        }
        val controls = if (pet) View.GONE else View.VISIBLE
        b.inputBar.visibility = controls
        b.buttonCluster.visibility = controls
        b.allowGuestToggle.visibility = controls
        // 摸鱼模式的约定是「只留一个触屏互动按钮」，权限入口也跟着收起；
        // 要改权限就先关掉触屏互动，别破了主人 0.0.25 定的规矩。
        b.callPermissionsButton.visibility = controls
        b.callModelResetButton.visibility = controls
        (b.callName.parent as? View)?.parent?.let { (it as? View)?.visibility = controls }
        b.callChat.visibility = controls
        b.photoDraft.visibility = if (!pet && pendingImage != null) View.VISIBLE else View.GONE
        // The top/bottom scrims exist to keep the controls readable; in pet mode they only dim her.
        b.scrimTop.visibility = controls
        b.scrimBottom.visibility = controls
        // 0.0.85：宠物模式下多一个「看清单」小按钮（在触屏互动按钮正下方，不压它），
        // 点开是独立页面（和「权限」页一样）。字幕带只在她说出话时出现——两条都收起控件，
        // 不算把控件放回来。
        b.touchInventoryToggle.visibility = if (pet) View.VISIBLE else View.GONE
        b.petCaption.visibility = if (pet && b.petCaptionText.text.isNotBlank()) View.VISIBLE else View.GONE
    }

    /** 宠物模式（控件收起）时的字幕带：她说的话仍然出现在屏幕下方，跟着语音流式长出来。
     *  贴底、自带半透明底、不接收点按（并登记进 blockedViews），所以既碰不到左上按钮，
     *  也不会把「想看清楚字幕」的点击当成摸她。 */
    private fun renderPetCaption(text: String, translation: String?) {
        if (!::adapter.isInitialized) return
        b.petCaptionText.text = text
        val extra = translation?.trim().orEmpty()
        b.petCaptionTranslation.text = extra
        b.petCaptionTranslation.visibility = if (extra.isEmpty()) View.GONE else View.VISIBLE
        val show = characterTouchOn && text.isNotBlank()
        b.petCaption.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        // Keep the newest words visible: cap the window at ~4 lines and always scroll to the tail.
        b.petCaptionScroll.post {
            if (isFinishing || isDestroyed) return@post
            val cap = actionDp(126)
            val wanted = b.petCaptionText.height.coerceAtMost(cap)
            val params = b.petCaptionScroll.layoutParams
            if (params.height != wanted) {
                params.height = wanted
                b.petCaptionScroll.layoutParams = params
            }
            b.petCaptionScroll.scrollTo(0,
                (b.petCaptionText.height - b.petCaptionScroll.height).coerceAtLeast(0))
        }
    }

    /** 「触屏互动 · 看清单」：独立说明页，不在通话页上盖层，所以看多久都不会挡着她。 */
    private fun openTouchGuide() {
        if (!characterTouchOn) return
        startActivity(Intent(this, TouchGuideActivity::class.java))
    }

    /** 前后摄像头互斥；预览小窗跟着相机一起开/关。 */
    private fun applyLiveCamera() {
        camera.setLiveEnabled(frontCameraOn || liveCameraOn, useFront = frontCameraOn)
        syncCameraButtons()
    }

    private fun syncCameraButtons() {
        val activeColor = Color.parseColor("#67E8F9")
        val inactiveColor = ContextCompat.getColor(this, R.color.ui_text_secondary)
        val activeTint = ColorStateList.valueOf(Color.parseColor("#3342D6A5"))
        fun update(btn: ImageButton, label: TextView, on: Boolean, name: String) {
            label.text = if (on) "$name · 开" else "$name · 关"
            label.setTextColor(if (on) activeColor else inactiveColor)
            btn.alpha = if (on) 1f else 0.55f
            btn.backgroundTintList = if (on) activeTint else null
            btn.contentDescription = if (on) "$name，已开启" else "$name，已关闭"
        }
        update(b.btnFrontCamera, b.frontCameraLabel, frontCameraOn, "前摄像头")
        update(b.btnLiveCamera, b.liveCameraLabel, liveCameraOn, "后摄像头")
    }

    private fun notice(text: String) {
        if (!isFinishing && !isDestroyed) Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun addBubble(name: String, text: String, translation: String? = null, translationLanguage: String? = null) {
        adapter.add(Bubble(name, text, translation, translationLanguage))
        if (!characterTouchOn) b.callChat.visibility = View.VISIBLE
        b.callChat.smoothScrollToPosition(adapter.itemCount - 1)
    }

    private fun onSpeechStart(message: InAudio) {
        // One reply row. Characters advance with actual playback, never with queued PCM.
        traceCall("caption_ready", message)
        autoHeardAudio = true
        autoBusyAt = SystemClock.elapsedRealtime()
        autoListen?.setMuted(true)
        val display = message.display_text
        // 2026-11-21: 字幕是独立消费者——只显示剥掉控制码的文本（括号保留），
        // 动作解析仍在路由器里读原始 display_text，互不影响。
        val text = (display?.text?.ifBlank { null } ?: display?.transcript?.ifBlank { null })
            ?.let(LocalSpeechText::forDisplay)
        if (!text.isNullOrBlank()) captions.begin(message,
            message.actions?.reply_id?.let { "reply:$it" }
                ?: activeLocalRequest?.let { "request:$it" }
                ?: "voice:${System.identityHashCode(message)}",
            b.callName.text.toString(), text, display?.translation, display?.translation_language)
        activityState = if (message.audio.isNullOrBlank()) "正在回复" else "准备语音"
        renderStatus()
    }

    private fun startReplyPerformance(message: InAudio) {
        val display = message.display_text
        val text = display?.text?.ifBlank { null } ?: display?.transcript?.ifBlank { null }
        if (actionPanelOpen || mic.isRecording) motionDirector.manualInteraction()
        motionDirector.onSpeech(text.orEmpty(), AutoMotionDirector.Cue(
            expression = message.actions?.expressions?.firstOrNull()?.content,
            gesture = message.actions?.motions?.firstOrNull(),
            replyId = message.actions?.reply_id,
            emotionIntent = emotionSignal(message),
        ))
    }

    /** Timings only: no input/reply text, endpoints, prompts or credentials.
     * request_id is local sequence association, not a server-echoed correlation ID.
     */
    private fun traceCall(event: String, message: InAudio? = null, requestId: String? = null,
                          completed: Boolean? = null, inputChars: Int? = null) {
        val replyId = message?.actions?.reply_id
        val associated = requestId ?: if (message?.forwarded == true) null
            else replyId?.let { replyToLocalRequest[it] } ?: activeLocalRequest
        val record = JSONObject().put("event", event).put("at_ms", SystemClock.elapsedRealtime())
            .put("engine", localSpeech?.engineId ?: LocalVoiceSettings.MODE_ID)
            .put("character", callCharacter).put("language", replyLanguage)
            .put("correlation", if (message?.forwarded == true) "local_opening" else "local_sequence_association")
        associated?.let { record.put("request_id", it) }
        replyId?.let { record.put("reply_id", it.take(160)) }
        completed?.let { record.put("completed", it) }
        inputChars?.let { record.put("input_chars", it) }
        message?.let { record.put("forwarded", it.forwarded) }
        Log.i("PRTS-LocalSpeech", record.toString())
    }

    private fun emotionSignal(message: InAudio): EmotionSignal? = emotionSignal(message.actions?.emotion_intent)

    private fun emotionSignal(intent: EmotionIntent?): EmotionSignal? = intent?.let {
        EmotionSignal(it.emotion, it.intensity, it.valence, it.arousal, it.dominance,
            it.confidence, it.source, it.analysis_ms, it.intent, it.intent_confidence,
            it.gesture, it.gesture_intensity)
    }

    private fun schedulePlaybackAck() {
        if (!audioLive || !playbackCompletePending || speech.isBusy || localSpeech?.isBusy == true) return
        handler.removeCallbacks(playbackAck)
        ackAttempt = 0
        // Coalesce queued payloads; bounded retries cover an ACK reaching the server before its waiter exists.
        handler.postDelayed(playbackAck, 100)
    }

    /** 她（远端或本地 TTS）是否正在出声。 */
    private fun herVoiceBusy(): Boolean = (::speech.isInitialized && speech.isBusy) ||
        localSpeech?.isBusy == true || playbackCompletePending

    /** [source] is recorded in `files/playback.log`: it is the only way to tell why playback stopped. */
    private fun interruptReply(source: String) {
        val id = remoteProactiveId
        DiagLog.write("playback.log", "interrupt source=$source " +
            "speaking=${::speech.isInitialized && speech.isBusy} local_busy=${localSpeech?.isBusy == true} " +
            "pending=$playbackCompletePending proactive=${id != null}")
        remoteProactiveId = null
        proactivePlaybackComplete = false
        proactive.finished()
        captions.cancel(); handler.removeCallbacks(captionTick); captionTickScheduled = false
        localOpeningPlayback.cancel()
        opening.consume()
        playbackCompletePending = false
        handler.removeCallbacks(playbackAck)
        speech.cancel()
        localSpeech?.cancel()
        if (::motionDirector.isInitialized) motionDirector.cancelReply()
        else if (::live2D.isInitialized) live2D.clearExpression()
        if (::ws.isInitialized && ws.isReady) {
            if (id == null) ws.sendInterrupt() else ws.sendRemoteProactiveCancel(id)
        }
    }

    private fun onWsEvent(raw: String) {
        lastInboundAt = SystemClock.elapsedRealtime()
        try {
            if (!foreground && !mediaSuspended && !CallKeepAliveService.running) return
            val obj = Proto.json.parseToJsonElement(raw) as? JsonObject ?: return
            val type = obj["type"]?.jsonPrimitive?.contentOrNull
            val proactiveId = obj["proactive_id"]?.jsonPrimitive?.contentOrNull
            if (proactiveId != null && proactiveId != remoteProactiveId) return
            if (!foreground && !CallKeepAliveService.running && type !in setOf("mobile-provider-ready", "mobile-provider-error",
                    "voice-profile-ready", "voice-profile-error")) {
                // A reply interrupted before opening the picker must not leave an ACK waiter.
                if (type == "backend-synth-complete") ws.sendInterrupt()
                return
            }
            when (type) {
                "mobile-provider-ready" -> {
                    remoteProactiveSupported = obj["proactive"]?.jsonPrimitive?.contentOrNull == "mobile-proactive-v1" &&
                        connectionProfile?.mode == com.prts.client.net.ConnectionMode.DIRECT_API
                    providerConfigured()
                }
                "mobile-proactive-rejected" -> {
                    if (proactiveId != null) {
                        remoteProactiveId = null
                        proactive.finished()
                        activityState = ""
                        renderStatus()
                    }
                }
                "voice-profile-ready" -> {
                    if (localSpeech != null && obj["mode"]?.jsonPrimitive?.contentOrNull == "client-local") {
                        providerReady = true; connectionState = "已连接 · 手机发声"; activityState = ""
                        renderImageDraft(); renderStatus()
                        maybeStartAutoListen()
                        playOpeningIfReady()
                    }
                }
                "voice-profile-error" -> {
                    providerReady = false; connectionState = "手机语音启用失败"; renderStatus()
                    notice(obj["message"]?.jsonPrimitive?.contentOrNull ?: "无法启用手机语音，请返回设置")
                }
                "image-input-accepted" -> { activityState = "正在看图片"; renderStatus() }
                "image-input-error" -> {
                    activityState = "图片未能处理"; renderStatus()
                    notice(obj["message"]?.jsonPrimitive?.contentOrNull ?: "图片未能处理，请稍后再试")
                }
                "mobile-provider-error" -> {
                    providerReady = false
                    connectionState = "回复来源配置失败"
                    activityState = "请返回设置检查"
                    renderStatus()
                    notice(obj["message"]?.jsonPrimitive?.contentOrNull ?: "来源配置未成功，未切换到其他模型")
                }
                "mobile-provider-status" -> {
                    activityState = when (obj["status"]?.jsonPrimitive?.contentOrNull) {
                        "looking_at_image" -> "正在看图片"
                        "needs_interaction" -> "等待你在原界面确认"
                        "running" -> "正在处理"
                        else -> ""
                    }
                    renderStatus()
                }
                "early-speech-preview", "early-speech-cancel" -> {
                    if (connectionProfile?.mode == com.prts.client.net.ConnectionMode.LOCAL_DSH) {
                        val id = obj["reply_id"]?.jsonPrimitive?.contentOrNull ?: return
                        if (type == "early-speech-cancel") localSpeech?.cancelPreview(id)
                        else obj["text"]?.jsonPrimitive?.contentOrNull?.let { localSpeech?.preparePreview(id, it) }
                    }
                }
                "phone-action" -> {
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val act = obj["action"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val on = obj["on"]?.jsonPrimitive?.booleanOrNull == true
                    val value = obj["value"]?.jsonPrimitive?.intOrNull ?: 0
                    val appName = obj["app"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    // control.phone：无障碍指令的附加字段（click 的文字、tap/swipe 的坐标）。
                    val text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    // settext 的写入内容（text 是目标输入框的标签/提示）。
                    val text2 = obj["text2"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val px = obj["x"]?.jsonPrimitive?.intOrNull ?: 0
                    val py = obj["y"]?.jsonPrimitive?.intOrNull ?: 0
                    val px2 = obj["x2"]?.jsonPrimitive?.intOrNull ?: 0
                    val py2 = obj["y2"]?.jsonPrimitive?.intOrNull ?: 0
                    val ms = obj["ms"]?.jsonPrimitive?.intOrNull ?: 0
                    // Shizuku 的 shell 命令会阻塞，动作统一放到后台线程，结果再回主线程发回中继。
                    Thread({
                        val result = com.prts.client.phone.PhoneActions.run(
                            this, act, on, appName, value, text, px, py, px2, py2, ms, text2)
                        handler.post {
                            if (isFinishing || isDestroyed) return@post
                            val reply = JSONObject().put("type", "phone-action-result").put("id", id)
                                .put("ok", result.ok).put("message", result.message)
                            ws.sendRaw(reply.toString())
                            Log.i("PRTS-Call", "phone action $act ok=${result.ok} ${result.message}")
                        }
                    }, "PRTS-PhoneAction").start()
                }
                "audio" -> {
                    // Any inbound reply closes the microphone before its first sample plays,
                    // so her own synthesized voice can never be captured and sent back.
                    autoListen?.setMuted(true)
                    autoBusyAt = SystemClock.elapsedRealtime()
                    val msg = Proto.json.decodeFromString(InAudio.serializer(), raw)
                    if (!msg.forwarded) msg.actions?.reply_id?.let { replyId ->
                        activeLocalRequest?.let { requestId ->
                            if (!replyToLocalRequest.containsKey(replyId)) {
                                if (replyToLocalRequest.size >= 64) replyToLocalRequest.remove(replyToLocalRequest.keys.first())
                                replyToLocalRequest[replyId] = requestId
                            }
                        }
                    }
                    traceCall("backend_reply", msg)
                    // 2026-11-20 主人：「文字出现的还是不够快」。整段回复一到就铺满字幕，
                    // 不再等逐段 TTS 合成 / 播放进度；语音照常排队，后出的分片不会再追加文字。
                    val displayNow = msg.display_text
                    // 2026-11-21: 这一路是「收到 audio 就整段铺字」，直接画原文会把
                    // [gesture:*] / 句中 [happy] 带上屏（中继只剥行首一个情绪词）。
                    val textNow = (displayNow?.text?.ifBlank { null } ?: displayNow?.transcript?.ifBlank { null })
                        ?.let(LocalSpeechText::forDisplay)
                    if (!textNow.isNullOrBlank()) {
                        captions.beginFull(msg,
                            msg.actions?.reply_id?.let { "reply:$it" }
                                ?: activeLocalRequest?.let { "request:$it" }
                                ?: "voice:${System.identityHashCode(msg)}",
                            b.callName.text.toString(), textNow,
                            displayNow?.translation, displayNow?.translation_language)
                    }
                    motionDirector.stageCue(AutoMotionDirector.Cue(
                        expression = msg.actions?.expressions?.firstOrNull()?.content,
                        gesture = msg.actions?.motions?.firstOrNull(), replyId = msg.actions?.reply_id,
                        emotionIntent = emotionSignal(msg),
                    ))
                    handler.removeCallbacks(playbackAck)
                    localSpeech?.enqueue(msg) ?: speech.enqueue(msg)
                }
                "emotion-baseline" -> {
                    // M2 2026-11-18：这通电话的第一回合，服务端把上次通话留下的 VAD 发来。
                    // 以前没有这条通道，手机每通都从中性开始；现在先种基线，第一条回复在它上面累积。
                    val sig = EmotionSignal(
                        obj["emotion"]?.jsonPrimitive?.contentOrNull,
                        obj["intensity"]?.jsonPrimitive?.doubleOrNull,
                        obj["valence"]?.jsonPrimitive?.doubleOrNull,
                        obj["arousal"]?.jsonPrimitive?.doubleOrNull,
                        obj["dominance"]?.jsonPrimitive?.doubleOrNull,
                        confidence = 1.0,
                        source = obj["source"]?.jsonPrimitive?.contentOrNull ?: "call-baseline")
                    if (motionDirector.seedEmotionBaseline(sig)) {
                        DiagLog.write("emotion.log", "baseline seeded emotion=${sig.emotion} " +
                            "v=${sig.valence} a=${sig.arousal} d=${sig.dominance}")
                    }
                }
                "emotion-update" -> {
                    // Optional metadata never blocks or enters the speech queue. The director
                    // requires the current active reply ID already seen on its audio envelope.
                    val update = runCatching { Proto.json.decodeFromString(InEmotionUpdate.serializer(), raw) }.getOrNull()
                    if (update != null) emotionSignal(update.emotion_intent)?.let {
                        motionDirector.onEmotionUpdate(update.reply_id, it)
                    }
                }
                "backend-synth-complete" -> {
                    traceCall("backend_complete")
                    playbackCompletePending = true
                    schedulePlaybackAck()
                }
                "control" -> when (obj["text"]?.jsonPrimitive?.contentOrNull) {
                    "conversation-chain-start" -> {
                        motionDirector.beginReply()
                        playbackCompletePending = false
                        handler.removeCallbacks(playbackAck)
                        activityState = "正在回复"
                        renderStatus()
                    }
                    "conversation-chain-end" -> {
                        if (proactive.isActive) proactivePlaybackComplete = true
                        motionDirector.endReply()
                        playbackCompletePending = false
                        handler.removeCallbacks(playbackAck)
                        activityState = ""
                        renderStatus()
                    }
                    "interrupt" -> {
                        DiagLog.write("playback.log", "interrupt source=server speaking=${speech.isBusy} " +
                            "local_busy=${localSpeech?.isBusy == true} pending=$playbackCompletePending")
                        remoteProactiveId = null
                        proactivePlaybackComplete = false
                        proactive.finished()
                        captions.cancel(); handler.removeCallbacks(captionTick); captionTickScheduled = false
                        localOpeningPlayback.cancel()
                        motionDirector.cancelReply()
                        playbackCompletePending = false; handler.removeCallbacks(playbackAck)
                        speech.cancel(); localSpeech?.cancel(); activityState = ""; renderStatus()
                    }
                }
                "local-asr-error" -> {
                    // 2026-11-24：中继把 ASR 失败分了类（reason=timeout/failed 是真故障；
                    // too_short 是环境声切出来的碎段）。原来不分类型、一律在自动收音后 20 s 内静默，
                    // 于是 11-23 17:14 那次「asr timeout」主人只看到界面卡住、没有任何提示。
                    val reason = obj["reason"]?.jsonPrimitive?.contentOrNull
                    val fatal = reason == "timeout" || reason == "failed"
                    val msg = obj["message"]?.jsonPrimitive?.contentOrNull ?: "本机语音识别失败，请重新说一次"
                    DiagLog.write("playback.log", "asr_error reason=${reason ?: "na"} fatal=$fatal " +
                        "since_send_ms=${SystemClock.elapsedRealtime() - autoSendAt} msg=$msg")
                    if (!fatal && SystemClock.elapsedRealtime() - autoSendAt < 20000L) {
                        // 自动收音常常把电视/外放声当一句话，碎段/空转写时不要每句都弹提示（老行为）。
                        Log.i("PRTS-Call", "auto asr error suppressed: $msg")
                        activityState = ""
                        renderStatus()
                        return
                    }
                    // 真故障：立刻把麦克风放开（别等 resumeDelay / TURN_SETTLE 的静音窗口），
                    // 主人这时候最可能的动作就是「再说一遍」。
                    val nowMs = SystemClock.elapsedRealtime()
                    autoBusyAt = nowMs - autoResumeMs - 1
                    autoTurnAt = nowMs - TURN_SETTLE_MS - 1
                    activityState = if (fatal) "语音没听清，再说一遍" else "语音识别未完成"
                    renderStatus()
                    // 2026-11-24 P6：10 s 内只弹一次（状态栏照常更新，asr_error 日志每段都写）。
                    // 以前 ASR key 失效时每个碎段都失败 → 每 ~6 s 一条提示，看起来像 App 抽风。
                    val sinceNotice = nowMs - lastFatalAsrNoticeAt
                    if (sinceNotice >= 10_000L) {
                        lastFatalAsrNoticeAt = nowMs
                        notice(msg)
                    } else {
                        Log.i("PRTS-Call", "asr error notice throttled: ${sinceNotice}ms")
                    }
                }
                "user-input-transcription" -> {
                    obj["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                        motionDirector.onUserInput(it)
                        addBubble("我", it)
                    }
                    activityState = "正在回复"
                    renderStatus()
                }
                "error" -> {
                    // 2026-11-24：中继其实带了具体原因（例：「AstrBot 插件还没连上中继，稍等几秒再说」），
                    // 以前一律显示「后端处理失败，未收到完整回复」——主人分不清是该重说、该等、还是真坏了。
                    val errMsg = obj["message"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    DiagLog.write("playback.log", "backend_error msg=${errMsg ?: "na"}")
                    if (proactive.isActive) {
                        interruptReply("backend_error")
                        activityState = ""
                        renderStatus()
                        return
                    }
                    proactive.finished()
                    localOpeningPlayback.cancel()
                    motionDirector.cancelReply()
                    activityState = "后端处理失败"
                    renderStatus()
                    notice(errMsg ?: "后端处理失败，未收到完整回复")
                }
            }
        } catch (error: Exception) {
            Log.w("PRTS-Call", "Cannot decode backend event", error)
            notice("收到一条无法读取的回复，请稍后再试")
        }
    }

    private fun readConnectionSettings() {
        val mode = ConnectionSettings.selectedMode(this)
        connectionSettingsError = null
        connectionProfile = null
        voiceAccessToken = ""
        serverUrl = Prefs.get(this).first
        try {
            val profile = ConnectionSettings.load(this, mode)
            connectionProfile = profile
            if (profile.isPaired) {
                val secure = com.prts.client.net.SecureUrl.base(profile.endpoint)
                val relay = java.net.URI(secure)
                require(relay.scheme?.lowercase() in setOf("http", "https") && !relay.host.isNullOrBlank())
                serverUrl = secure.trimEnd('/').replaceFirst(Regex("^https://", RegexOption.IGNORE_CASE), "wss://")
                    .replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "ws://") + "/mobile-call/v1/ws"
                voiceAccessToken = profile.apiKey
            } else {
                connectionSettingsError = when {
                    profile.requiresPairing -> profile.validationError()
                    serverUrl.isBlank() -> "请先在连接设置填写服务地址"
                    else -> null
                }
                if (connectionSettingsError == null) voiceAccessToken = ConnectionSettings.loadVoiceKey(this)
            }
        } catch (_: Exception) {
            connectionSettingsError = if (mode == com.prts.client.net.ConnectionMode.LOCAL_DSH)
                "本机桥未开启，请回到 L2D 设置恢复连接"
            else "连接设置无法读取，请返回设置检查"
        }
    }

    private fun openConnection() {
        providerReady = false
        val localDsh = connectionProfile?.mode == com.prts.client.net.ConnectionMode.LOCAL_DSH
        ws = WsClient(serverUrl, ::onWsEvent, voiceAccessToken) { state ->
            // Keep configuration state current if the socket reconnects inside the picker.
            if (!foreground && !mediaSuspended && !CallKeepAliveService.running) return@WsClient
            connectionState = when {
                state == "open" -> "已连接"
                localDsh && state == "unauthorized" -> "本机授权已失效，请到 DSH Go 的「全部功能」→「DSH L2D桥接」重连"
                localDsh -> "等待 DSH Go 本机连接"
                state == "connecting" -> "连接中"
                state == "invalid-url" -> "服务器地址不正确"
                state == "unauthorized" -> "通话服务 Key 不正确或未获授权"
                state.startsWith("retry ") -> "连接断开，重连 ${state.removePrefix("retry ")}"
                else -> "连接失败，请返回检查服务器地址"
            }
            if (state == "open") {
                providerReady = false
                lastInboundAt = SystemClock.elapsedRealtime()
                if (::ws.isInitialized) ws.sendAllowGuest(guestTalkOn)
                val profile = connectionProfile
                if (profile == null) {
                    connectionState = "连接设置需要重新填写"
                } else if (profile.usesLegacyCallSocket) {
                    val config = Prefs.getConfig(this)
                    if (config.isNotBlank() && config != "default") ws.switchConfig(config)
                    providerConfigured()
                } else {
                    connectionState = "正在配置回复来源"
                    if (!ws.configureProvider(profile, replyLanguage)) connectionState = "来源配置未发送"
                    handler.postDelayed({
                        if (foreground && !providerReady && ws.isReady && connectionState == "正在配置回复来源") {
                            connectionState = if (localDsh) "本机插件未完成握手" else "回复来源配置超时"
                            renderStatus()
                        }
                    }, 20000)
                }
            } else {
                remoteProactiveSupported = false
                remoteProactiveId = null
                proactivePlaybackComplete = false
                proactive.finished()
                localOpeningPlayback.cancel()
                motionDirector.cancelReply()
                providerReady = false
                mic.stop(false)
                speech.cancel()
                localSpeech?.cancel()
                playbackCompletePending = false
                handler.removeCallbacks(playbackAck)
                activityState = ""
                finishUnconfirmedImageSend(showNotice = foreground)
            }
            renderStatus()
        }
        val error = connectionSettingsError
        if (error == null) ws.open()
        else { connectionState = error; renderStatus() }
    }

    private fun playOpeningIfReady() {
        val router = localSpeech ?: return
        val line = opening.claim(callCharacter, replyLanguage, foreground,
            providerReady, router.available,
            speech.isBusy || router.isBusy || mic.isRecording || imageSending ||
                actionPanelOpen || placementEditing || b.inputText.text.isNotBlank()) ?: return
        val message = InAudio(display_text = DisplayText(text = line.text,
            translation = line.translation, translation_language = line.translationLanguage,
            spoken_text = line.spokenText),
            actions = Actions(reply_id = "local-opening-ellen-v1"), forwarded = true)
        activityState = "正在准备语音"
        localOpeningPlayback.queued()
        Log.i("PRTS-Opening", "local opening queued character=ellen language=$replyLanguage")
        router.enqueue(message)
        renderStatus()
    }

    /** A local welcome has no backend chain-end. Its failure can hand text to
     * SpeechPlayer synchronously while the router is still busy, so both idle
     * sources must check completion. No caption or retry is produced here. */
    private fun finishLocalOpeningIfIdle() {
        if (!localOpeningPlayback.takeIdleCompletion(foreground, speech.isBusy,
                localSpeech?.isBusy == true, playbackCompletePending)) return
        activityState = ""
        if (::motionDirector.isInitialized) motionDirector.endReply()
        renderStatus()
    }

    private fun ensureKeepAliveService() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val endpoint = (connectionProfile?.endpoint?.takeIf { it.isNotBlank() }
            ?: if (::serverUrl.isInitialized) serverUrl else "")
            .replaceFirst(Regex("^wss://", RegexOption.IGNORE_CASE), "https://")
            .replaceFirst(Regex("^ws://", RegexOption.IGNORE_CASE), "http://")
            .removeSuffix("/mobile-call/v1/ws")
            .trimEnd('/')
        Log.i("PRTS-Call", "keepalive ensure granted=$granted running=${CallKeepAliveService.running} endpoint=$endpoint")
        if (!granted) return
        CallKeepAliveService.start(this, endpoint, AutoListenSettings.enabled(this))
    }

    private fun registerHangupReceiver() {
        if (hangupRegistered) return
        try {
            ContextCompat.registerReceiver(this, hangupReceiver, IntentFilter(CallKeepAliveService.ACTION_HANGUP),
                ContextCompat.RECEIVER_NOT_EXPORTED)
            hangupRegistered = true
        } catch (_: Exception) { }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("localOpeningConsumed", opening.consumed)
        super.onSaveInstanceState(outState)
    }

    /**
     * Keep the wake word: the relay uses "the sentence starts with 小鲸鱼" to tell a command
     * ("小鲸鱼，打开计算器") from chatter. It strips the prefix itself before the model sees it.
     */
    private fun stripWakeWord(raw: String?): String = (raw ?: "").trim()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A wake that arrives while the call screen already exists lands here, not in onCreate.
        if (intent.getBooleanExtra(EXTRA_WAKE_ACCEPTED, false)) {
            wakeAcceptPending = true
            scheduleWakeAccept()
            return
        }
        val said = stripWakeWord(intent.getStringExtra(EXTRA_INITIAL_TEXT))
        if (said.isNotEmpty()) {
            pendingWakeText = said
            scheduleWakeText()
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        userLeft = false
        if (wakeAcceptPending) scheduleWakeAccept() else scheduleWakeText()
        Log.i("PRTS-Call", "onResume running=${CallKeepAliveService.running} wsReady=${::ws.isInitialized && ws.isReady}")
        startKeepAliveTick()
        proactive.resumed()
        if (localSpeech?.engineId == LocalVoiceSettings.MODE_ID) {
            Audio8ModelStore.prepare(this)
            modelPreparation = Audio8ModelStore.observe(this) { state ->
            if (foreground) {
                if (!state.ready && state.error == null) {
                    val percent = if (state.totalBytes > 0) (state.processedBytes * 100 / state.totalBytes).coerceIn(0, 100) else 0
                    activityState = "正在准备语音 · $percent%"
                    renderStatus()
                } else if (state.ready && ::ws.isInitialized && ws.isReady && !providerReady) providerConfigured()
            }
            }
        }
        directPlacement?.setForeground(true)
        // 从权限页回来时刷新一次（权限页会把服务端快照写进本地缓存）。
        backgroundListen = Prefs.grantCached(this, "listen.background")
        if (background == null) background = BackgroundPresenter(this, b.bgImage).also { it.showSelected() }
        camera.setForeground(true)
        motionDirector.setForeground(true)
        if (::live2D.isInitialized) {
            val transform = CharacterPresentationStore.load(this, callCharacter)
            live2D.setPresentationTransform(transform.scale, transform.offsetX, transform.offsetY)
            live2D.startRender()
            // 从权限页/桌面回来时视图尺寸可能悄悄变了（没有 size-changed 回调），按当前尺寸重对齐一次，
            // 否则 TextureView 的等比拟合还停在旧尺寸上，画面就会被拉扁（「模型变形」）。
            live2D.refitSurface()
        }
        val reuseMediaConnection = (mediaSuspended || CallKeepAliveService.running) && ::ws.isInitialized &&
            (ws.isReady || ws.isConnecting)
        mediaSuspended = false
        if (reuseMediaConnection) {
            // Do not reconfigure an uninterrupted socket: that would create a new remote session.
            renderImageDraft()
            renderStatus()
        } else {
            if (::ws.isInitialized) ws.close()
            if (ConnectionSettings.selectedMode(this) == com.prts.client.net.ConnectionMode.LOCAL_DSH) readConnectionSettings()
            openConnection()
        }
        handler.post(clockTick)
        handler.post(emotionTick)
        handler.removeCallbacks(autoListenReevaluate)
        handler.postDelayed(autoListenTick, 400)
        maybeStartAutoListen()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 2026-11-18：开了「后台继续听」时，用户切到别的 App（Home/最近任务/点通知）也继续听——
        // 他就是要在她打开的应用里接着说。以前这里无条件释放麦克风，「切应用后无法指挥」就是它。
        val keep = backgroundListen && CallKeepAliveService.running
        DiagLog.write("call.log", "leave-hint background=$backgroundListen keepalive=${CallKeepAliveService.running} keep=$keep")
        if (keep) {
            userLeft = false
            Log.i("PRTS-Call", "onUserLeaveHint: background-listen keeps the mic")
            handler.postDelayed(autoListenReevaluate, 600)
            return
        }
        userLeft = true
        stopAutoListen()
        Log.i("PRTS-Call", "onUserLeaveHint: hands-free listening released")
    }

    override fun onPause() {
        modelPreparation?.close(); modelPreparation = null
        directPlacement?.setForeground(false)
        placementEditor?.close()
        background?.close(); background = null
        mediaSuspended = camera.externalActivityPending
        val keepRender = debugFlag("keep-render")
        val keepCamera = debugFlag("keep-camera")
        if (!keepCamera) camera.setForeground(false)
        finishUnconfirmedImageSend(showNotice = false)
        // With the keep-alive service running the call survives screen-off and backgrounding:
        // keep the socket, the in-flight reply and an active recording; only the UI goes quiet.
        val keepSession = CallKeepAliveService.running
        DiagLog.write("call.log", "pause keepSession=$keepSession background=$backgroundListen " +
            "userLeft=$userLeft manualMuted=$manualMuted enabled=${AutoListenSettings.enabled(this)}")
        Log.i("PRTS-Call", "onPause keepSession=$keepSession wsReady=${::ws.isInitialized && ws.isReady} micRecording=${mic.isRecording} keepRender=$keepRender keepCamera=$keepCamera")
        // PTT is a foreground interaction: never keep a manual recording alive in the background.
        mic.stop(false)        // Hands-free listening must not hold the microphone while the app is in the background
        // with the screen on. If the screen is off it may come back (see autoListenReevaluate).
        if (backgroundListen && keepSession) {
            // 「后台继续听」：麦克风不释放，切到别的应用后还能接着说；由 autoListen tick 继续管理静音。
            Log.i("PRTS-Call", "onPause background-listen kept (permission on)")
            handler.postDelayed(autoListenReevaluate, 800)
        } else {
            stopAutoListen()
            if (keepSession && !mediaSuspended && AutoListenSettings.screenOff(this) && !userLeft) {
                handler.postDelayed(autoListenReevaluate, 1500)
            }
        }
        if (!keepSession) interruptReply("onPause")
        motionDirector.setForeground(false)
        foreground = false
        if (!keepSession) handler.removeCallbacksAndMessages(null)
        if (!mediaSuspended && !keepSession) {
            providerReady = false
            if (::ws.isInitialized) ws.close()
        }
        if (!keepRender) {
            if (::live2D.isInitialized) {
                live2D.resetMotionState()
                // Keep-alive calls must not tear the renderer down: the EGL/model/JS teardown
                // aborts the network socket, so idle the loop instead (no frames drawn).
                if (keepSession) live2D.pauseRendering() else live2D.stopRender()
            }
        }
        super.onPause()
    }

    /**
     * observe.screen 的触发点：主人真的切到别的应用之后才拍，这时屏幕上是目标应用。
     * 结束通话/转动屏幕不算「切应用」，用 isFinishing 挡掉。
     */
    override fun onStop() {
        super.onStop()
        if (!isFinishing) ScreenCaptureService.onAppBackground()
    }

    // ---------------------------------------------------------------- hands-free listening

    /**
     * Keeps the microphone closed from the moment a turn is sent until her answer finished
     * playing (plus a short tail), so the speaker never re-triggers the endpoint detector.
     */
    /** 别的 App 正在放媒体声音（视频/音乐）时，麦克风拾到的是外放，不能当成用户说话。 */
    private fun mediaPlaying(): Boolean = try {
        (getSystemService(AUDIO_SERVICE) as android.media.AudioManager).isMusicActive
    } catch (_: Exception) {
        false
    }

    /** Anything captured this soon after her playback ends is treated as echo. */
    private val ECHO_GUARD_MS = 1500L

    /** 早打断（不等端点）刚触发过：端点回调不要再重复停播/重复发 interrupt。 */
    private var earlyBargeInterruptAt = 0L

    /** True while her voice (remote or local TTS) is playing; drives [lastPlaybackEndAt]. */
    private var autoWasBusy = false
    private var lastPlaybackEndAt = 0L
    /** True while the recorder is open only for barge-in monitoring. */
    private var autoBarge = false
    private var lastHerVoiceAt = 0L

    private val autoListenTick = object : Runnable {
        override fun run() {
            try {
                val listener = autoListen ?: return
                if (manualMuted) {
                    listener.setBarge(false)
                    listener.setMuted(true)
                    autoBusyAt = SystemClock.elapsedRealtime()
                    // 手动关麦 = 主人明确不想被听，攒着的段也一并丢掉。
                    pendingDuringPlayback.clear()
                    return
                }
                val now = SystemClock.elapsedRealtime()
                if (callGuard?.inCall == true) {
                    listener.setBarge(false)
                    listener.setMuted(true)
                    autoBusyAt = now
                    return
                }
                if (!AutoListenSettings.enabled(this@CallActivity)) {
                    listener.setBarge(false)
                    listener.setMuted(true)
                    return
                }
                // This tick keeps running for the whole activity lifetime (also while the call is
                // kept alive in the background), so it has to apply the same rule as
                // maybeStartAutoListen: without it onPause() mutes the listener and the next tick
                // re-opens the recorder 250 ms later, stealing the microphone from the wake-word
                // app while the call sits in the background or behind the keyguard.
                if (!mayHoldMic()) {
                    listener.setBarge(false)
                    listener.setMuted(true)
                    return
                }
                val herVoice = (::speech.isInitialized && speech.isBusy) ||
                    localSpeech?.isBusy == true || playbackCompletePending
                if (herVoice) lastHerVoiceAt = now
                val busy = herVoice || mediaPlaying() ||
                    (::mic.isInitialized && (mic.isRecording || mic.isBusy))
                if (busy) {
                    autoBusyAt = now
                    autoWasBusy = true
                } else if (autoWasBusy) {
                    // Playback just ended: remember when, so the echo tail cannot be re-captured.
                    autoWasBusy = false
                    lastPlaybackEndAt = now
                }
                // 她刚说完：把「她在说时」主人插的话按顺序送出去（一次一段，下一个 tick 再送下一段，
                // 中继续写队列会逐段转写）。见 2026-11-24 方案 A。
                if (!herVoice && pendingDuringPlayback.isNotEmpty()) flushPendingDuringPlayback()
                val idleFor = now - autoBusyAt
                val sinceTurn = now - autoTurnAt
                // 说话打断只在「她正在说」时开；手动录音或别的 App 放媒体时照旧让麦。
                // 注意不能拿 mediaPlaying() 当条件：她自己的 TTS 也会把它置 true。
                // 句子之间的空隙会让 herVoice 闪断，hold 900ms，否则打断窗口被反复拆开。
                val bargeEnabled = AutoListenSettings.bargeIn(this@CallActivity)
                // 2026-11-24 方案 A：她说话时也继续收音（默认开），但**不发打断**——
                // 收到的话先攒着，她说完再送。barge 通道本来就是「muted=true + barge=true 时
                // 继续收、用实测回声门限」，这里只是把触发条件从「打断开关」换成这个新开关。
                val keepListening = AutoListenSettings.listenDuringPlayback(this@CallActivity) && !manualMuted
                val bargeNow = (bargeEnabled || keepListening) && (herVoice || now - lastHerVoiceAt < 900)
                listener.setBarge(bargeNow)
                // 只有「她还出声」这段时间才换 VOICE_COMMUNICATION（让平台 AEC 拿到播放参考）；
                // 普通收音仍走 MIC，避免影响 0.0.97 已经验收过的端点表现。
                // AutoListener 里有 500ms 电平探针：这个源在这台 vivo 上要是有时给 0 电平，会自动退回 MIC。
                listener.setEchoSafeSource(bargeNow)
                // muted 在她说话时必须保持 true：AutoListener 的 bargeMode = muted && barge，
                // 只有 muted 为真才用实测回声门限（0.03~0.09）。旧代码在 bargeNow 时把 muted 设回 false，
                // 于是她说话期间走的是普通门限 0.010，主人喊的「停」被 8 秒普通捕获吞掉（10:43 实测）。
                if (bargeNow && !autoBarge) earlyBargeInterruptAt = 0L
                val mutedNow = when {
                    busy -> true
                    idleFor < autoResumeMs -> true
                    sinceTurn < TURN_SETTLE_MS -> true
                    else -> false
                }
                listener.setMuted(mutedNow)
                // 卡死看门狗（2026-11-23 0.0.97）：mutedNow 连续 12 s 不放、而此刻既没有她的声音
                // 也没在手动录音 —— 就是 15:39 那种「界面在等你说、麦克风却一直关着」
                // （dumpsys 里 70 分钟零 rec start）。直接解开静音：宁可多收一段回声
                // （中继 isEcho 会丢），也不能整通电话听不见。
                if (mutedNow) {
                    if (mutedSinceAt == 0L) mutedSinceAt = now
                    if (now - mutedSinceAt >= LISTEN_STUCK_MS && !herVoice &&
                        !(::mic.isInitialized && mic.isRecording)) {
                        DiagLog.write("playback.log",
                            "listen-stuck-unmute muted_ms=${now - mutedSinceAt} busy=$busy")
                        Log.i("PRTS-Call", "auto-listen stuck muted ${now - mutedSinceAt}ms -> force unmute")
                        autoBusyAt = now - autoResumeMs - 1
                        autoTurnAt = now - TURN_SETTLE_MS - 1
                        mutedSinceAt = 0L
                    }
                } else {
                    mutedSinceAt = 0L
                }
                if (bargeNow != autoBarge) {
                    autoBarge = bargeNow
                    DiagLog.write("playback.log", "barge_window on=$bargeNow her_voice=$herVoice")
                    Log.i("PRTS-Call", "auto-listen barge=$bargeNow herVoice=$herVoice")
                }
                if (mutedNow != autoMuted) {
                    autoMuted = mutedNow
                    DiagLog.write("playback.log", "listen_muted=$mutedNow busy=$busy barge=$bargeNow " +
                        "idle_ms=$idleFor speech=${::speech.isInitialized && speech.isBusy} " +
                        "local=${localSpeech?.isBusy == true} pending=$playbackCompletePending")
                    Log.i("PRTS-Call", "auto-listen muted=$mutedNow busy=$busy idle=$idleFor speech=${::speech.isInitialized && speech.isBusy} local=${localSpeech?.isBusy == true} pending=$playbackCompletePending")
                }
            } finally {
                handler.postDelayed(this, 150)
            }
        }
    }

    /** Screen-off listening is opt-in: when the user leaves the app with the screen on, stop. */
    private val autoListenReevaluate = Runnable {
        if (foreground || userLeft) return@Runnable
        if (!CallKeepAliveService.running) return@Runnable
        if (backgroundListen) { maybeStartAutoListen(); return@Runnable }
        if (!AutoListenSettings.screenOff(this)) return@Runnable
        val screenOn = (getSystemService(POWER_SERVICE) as android.os.PowerManager).isInteractive
        if (screenOn && !keyguardLocked()) return@Runnable
        maybeStartAutoListen()
    }

    private fun keyguardLocked(): Boolean = try {
        (getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked
    } catch (_: Exception) {
        false
    }

    /**
     * Whether this activity may hold the microphone right now: only while the call UI is in the
     * foreground (screen on), or when the user explicitly opted into "锁屏时继续收音" AND the
     * screen really is off / the keyguard is up. Everything else must release the recorder so the
     * wake-word app (com.whale.wake) owns it.
     */
    private fun mayHoldMic(): Boolean {
        if (foreground) return true
        // 2026-11-18：权限页开了「后台继续听」→ 切到别的应用/锁屏都不释放麦克风。
        if (backgroundListen && !userLeft && CallKeepAliveService.running) return true
        if (userLeft || !AutoListenSettings.screenOff(this)) return false
        val screenOn = (getSystemService(POWER_SERVICE) as android.os.PowerManager).isInteractive
        return !screenOn || keyguardLocked()
    }

    private fun maybeStartAutoListen() {
        val listener = autoListen ?: return
        if (!AutoListenSettings.enabled(this)) {
            listener.stop()
            return
        }
        if (callGuard?.inCall == true) {
            listener.setMuted(true)
            return
        }
        if (mediaPlaying()) {
            // 别的 App 在放声音时，麦克风拾到的是外放，不是用户；先别听。
            listener.setMuted(true)
            return
        }
        if (!audioLive || !::ws.isInitialized || !ws.isReady || !providerReady) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) return
        if (!foreground) {
            if (backgroundListen && !userLeft && CallKeepAliveService.running) {
                // 「后台继续听」：保持免提监听（onPause 不会把它关掉）。
            } else {
                if (userLeft || !AutoListenSettings.screenOff(this)) return
                val screenOn = (getSystemService(POWER_SERVICE) as android.os.PowerManager).isInteractive
                // 亮屏但已锁屏时仍要继续听（通话界面可能压在锁屏后面）；只有亮屏且已解锁才让给唤醒 App。
                if (screenOn && !keyguardLocked()) return
            }
        }
        val config = AutoListenSettings.config(this)
        autoResumeMs = config.resumeDelayMs
        listener.updateConfig(config)
        listener.start()
        DiagLog.write("call.log", "auto-listen start fg=$foreground background=$backgroundListen " +
            "keepalive=${CallKeepAliveService.running} userLeft=$userLeft")
        if (SystemClock.elapsedRealtime() - autoBusyAt < config.resumeDelayMs + 400L ||
            (::speech.isInitialized && speech.isBusy) || localSpeech?.isBusy == true || playbackCompletePending) {
            listener.setMuted(true)
            autoBusyAt = SystemClock.elapsedRealtime()
        }
        Log.i("PRTS-Call", "auto-listen started gate=%.2f silence=%dms min=%dms".format(
            config.gateMul, config.silenceMs, config.minSpeechMs))
    }

    private fun stopAutoListen() {
        // Closing the recorder is enough; the worker idles (80 ms naps) so a resume is instant.
        autoListen?.setMuted(true)
    }

    private fun syncAutoListen() {
        if (!AutoListenSettings.enabled(this)) {
            stopAutoListen()
            notice("自动收音已关闭，仍可点麦克风说话")
            return
        }
        ensureKeepAliveService()
        maybeStartAutoListen()
        notice("自动收音已开启：说完停顿一下就发送")
    }

    private fun muteAutoListenForTurn() {
        autoTurnAt = SystemClock.elapsedRealtime()
        autoHeardAudio = false
        autoBusyAt = autoTurnAt
        autoListen?.setMuted(true)
    }

    /**
     * 送出「她在说话时」攒下的一句。方案 A：不打断她，只把主人插的话排到她说完之后。
     * 每 150ms 的 tick 送一段，中继的 micQueue 会逐段串行转写，不会并发。
     */
    private fun flushPendingDuringPlayback() {
        if (herVoiceBusy()) return
        if (!::ws.isInitialized || !ws.isReady || !providerReady) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPendingFlushAt < 250) return
        lastPendingFlushAt = now
        // 方案 B：她的回声也会被收进来，最新的那段最可能是主人刚插的话 → 后进先出，先送最新的。
        val samples = pendingDuringPlayback.removeLastOrNull() ?: return
        val ms = samples.size * 1000 / 16_000
        DiagLog.write("playback.log",
            "utterance_flush ms=$ms queued=${pendingDuringPlayback.size} action=send_after_playback")
        Log.i("PRTS-Call", "flush queued utterance ${ms}ms (left=${pendingDuringPlayback.size})")
        muteAutoListenForTurn()
        autoSendAt = now
        activityState = "正在发送语音"
        renderStatus()
        ws.sendMic(samples, { faceObservation() }, replyLanguage) { ok ->
            if (!ok) {
                activityState = ""
                renderStatus()
                notice("语音没发出去，请等连接恢复再试")
            }
        }
    }

    private fun onAutoUtterance(samples: FloatArray, bargeStarted: Boolean) {
        if (isFinishing || isDestroyed) return
        if (callGuard?.inCall == true) return
        if (!foreground && !CallKeepAliveService.running) return
        if (::mic.isInitialized && (mic.isRecording || mic.isBusy)) return
        if (!::ws.isInitialized || !ws.isReady || !providerReady) return
        if (samples.size < 3_200) return
        val ms = samples.size * 1000 / 16_000
        // 打断的判定：这句必须是「在 barge 模式下开始收的」（她说的时候用户才开口）。
        // 旧判定只看「此刻正在播放」，于是普通监听跨进播放窗口的句子也会把播放停掉 ——
        // 11-16 09:41 那次「说到 22 字停住、声音也停」就是这么来的。
        val interrupting = herVoiceBusy()
        val earlyFired = earlyBargeInterruptAt > 0L &&
            SystemClock.elapsedRealtime() - earlyBargeInterruptAt < 2_000L
        // 方案 A（2026-11-24 主人拍板）：她在说话、而「打断」开关是关的（bargeIn() 恒 false）——
        // 主人插的话**只收不打断**：攒进队列，等她说完再按顺序送（flushPendingDuringPlayback）。
        // 原来这里会 interruptReply() 把她的播放掐掉，那是被打断语义，不是方案 A。
        if (interrupting && !AutoListenSettings.bargeIn(this) &&
            AutoListenSettings.listenDuringPlayback(this)) {
            DiagLog.write("playback.log",
                "utterance ms=$ms barge_started=$bargeStarted playing=true action=queue_until_quiet")
            Log.i("PRTS-Call", "queued while she talks: ${ms}ms")
            if (pendingDuringPlayback.size >= MAX_PENDING_UTTERANCES) {
                pendingDuringPlayback.removeFirstOrNull()
                DiagLog.write("playback.log", "utterance_queue_overflow dropped_oldest ms=$ms")
            }
            pendingDuringPlayback.addLast(samples)
            return
        }
        if (interrupting && !bargeStarted) {
            DiagLog.write("playback.log",
                "utterance ms=$ms barge_started=false playing=true action=keep_playing")
        } else if (interrupting && bargeStarted && earlyFired) {
            // 早打断已经在 160ms 处停了播，这里只负责把这句「停」发给 ASR。
            DiagLog.write("playback.log",
                "utterance ms=$ms barge_started=true playing=true action=send_after_early_interrupt")
        } else if (interrupting) {
            DiagLog.write("playback.log",
                "utterance ms=$ms barge_started=true playing=true action=interrupt")
            Log.i("PRTS-Call", "barge-in: user talked over her voice (%dms), interrupting".format(ms))
            interruptReply("auto_barge_in")
        } else {
            DiagLog.write("playback.log",
                "utterance ms=$ms barge_started=$bargeStarted playing=false action=send")
        }
        // Echo guard: audio captured right after her own playback is usually the speaker tail
        // (that is what produced repeated 8 s "utterances" right after every reply).
        if (lastPlaybackEndAt > 0) {
            val sincePlay = SystemClock.elapsedRealtime() - lastPlaybackEndAt
            // 2026-11-23：barge 起点（主人真的盖着她说话）不能被回声门吞掉——那句是人的声音，
            // 不是喇叭尾音；原来这里一刀切，打断后的第一句常被丢。
            if (sincePlay < ECHO_GUARD_MS && !(interrupting && bargeStarted)) {
                Log.i("PRTS-Call", "auto-listen dropped: ${ms}ms captured ${sincePlay}ms after playback (echo guard)")
                DiagLog.write("playback.log", "utterance_dropped ms=$ms echo_guard_since_ms=$sincePlay")
                autoBusyAt = SystemClock.elapsedRealtime()
                return
            }
        }
        muteAutoListenForTurn()
        autoSendAt = SystemClock.elapsedRealtime()
        prioritizeCallInput()
        motionDirector.userInteraction()
        activityState = "正在发送语音"
        renderStatus()
        Log.i("PRTS-Call", "auto-listen utterance ${ms}ms")
        ws.sendMic(samples, { faceObservation() }, replyLanguage) { ok ->
            if (!ok) {
                activityState = ""
                renderStatus()
                notice("语音没发出去，请等连接恢复再试")
            }
        }
    }

    private fun onCallStateChanged(inCall: Boolean) {
        if (isFinishing || isDestroyed) return
        if (inCall) {
            pendingDuringPlayback.clear()
            if (::mic.isInitialized && mic.isRecording) {
                mic.stop(false)
                b.btnMic.isPressed = false
            }
            autoListen?.setMuted(true)
            autoBusyAt = SystemClock.elapsedRealtime()
            interruptReply("call_started")
            activityState = "通话中，已暂停"
            renderStatus()
            Log.i("PRTS-Call", "call started: microphone and playback released")
        } else {
            autoBusyAt = SystemClock.elapsedRealtime()
            activityState = ""
            renderStatus()
            maybeStartAutoListen()
            Log.i("PRTS-Call", "call ended: hands-free listening restored")
        }
    }

    /** 「模型复位」：恢复默认大小位置 + 重新加载模型。渲染失败后不用退到后台再回来。 */
    private fun resetCallModel(reason: String) {
        if (!::live2D.isInitialized) { notice("角色还在准备，请稍等"); return }
        placementEditor?.close(); placementEditor = null
        CharacterPresentationStore.save(this, callCharacter, PresentationTransform())
        live2D.setPresentationTransform(1f, 0f, 0f)
        val restarted = live2D.resetModel(reason)
        if (restarted) {
            activityState = "正在复位模型"
            renderStatus()
            notice("模型已复位（大小 100%）")
        } else {
            notice("模型暂未就绪，请稍后再试")
        }
    }

    private fun debugFlag(name: String): Boolean =
        runCatching { java.io.File(filesDir, name).isFile }.getOrDefault(false)

    override fun onDestroy() {
        Log.i("PRTS-Call", "onDestroy finishing=$isFinishing running=${CallKeepAliveService.running} wsReady=${::ws.isInitialized && ws.isReady}")
        handler.removeCallbacks(locTick)
        directPlacement?.close(); directPlacement = null
        if (::motionDirector.isInitialized) motionDirector.setForeground(false)
        if (hangupRegistered) {
            runCatching { unregisterReceiver(hangupReceiver) }
            hangupRegistered = false
        }
        runCatching { unregisterReceiver(screenReceiver) }
        // 通话页结束：看一眼窗口必须关掉，投影不能留在后台。
        if (isFinishing) ScreenCaptureService.closeWindow()
        if (isFinishing) CallKeepAliveService.stop(this)
        if (isFinishing) handler.removeCallbacks(keepAliveTick)
        stopAutoListen()
        autoListen?.stop()
        callGuard?.stop()
        handler.removeCallbacksAndMessages(null)
        // A recreated activity (screen-off config change) must not tear down a live call.
        if (isFinishing) {
            mic.close()
            speech.close()
            localSpeech?.close()
            touchVoice?.close()
        }
        background?.close(); background = null
        b.photoDraftThumb.setImageDrawable(null)
        draftThumbnail = null; draftThumbnailImage = null
        albumWatcher?.stop(); albumWatcher = null
        if (isFinishing) ScreenCaptureService.sink = null
        if (isFinishing && ::ws.isInitialized) ws.close()
        if (::live2D.isInitialized) live2D.stopRender()
        super.onDestroy()
    }
}

/** Main-thread state independent of whether synthesis ever produced PCM. */
internal class LocalOpeningPlaybackState {
    var pending: Boolean = false
        private set

    fun queued() { pending = true }
    fun cancel() { pending = false }

    fun takeIdleCompletion(foreground: Boolean, playerBusy: Boolean,
                           routerBusy: Boolean, backendPlaybackPending: Boolean): Boolean {
        if (!pending || !foreground || playerBusy || routerBusy || backendPlaybackPending) return false
        pending = false
        return true
    }
}







