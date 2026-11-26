package com.prts.client.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.RingtoneManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.prts.client.ui.CallAlertSettings

/**
 * Holds a microphone-typed foreground service for the duration of a call so the
 * socket, playback and microphone keep working when the screen turns off or the
 * user leaves the activity. Started only by the visible call UI, never on boot.
 */
class CallKeepAliveService : Service() {
    companion object {
        private const val CHANNEL = "call_keepalive"
        private const val ID = 47652
        const val ACTION_HANGUP = "com.prts.client.call.HANGUP"
        private const val ACTION_STOP = "com.prts.client.call.STOP"
        const val EXTRA_ENDPOINT = "com.prts.client.extra.CALL_ENDPOINT"
        const val EXTRA_PROBE_WS = "com.prts.client.extra.PROBE_WS"
        /** Hands-free listening needs the microphone type while the screen is off. */
        const val EXTRA_MIC = "com.prts.client.extra.CALL_MIC"
        const val ACTION_WAKE_REQUEST = "com.prts.client.call.WAKE_REQUEST"
        private const val ID_WAKE = 47653
        private const val EXTRA_WAKE_TEXT = "com.prts.client.call.EXTRA_WAKE_TEXT"
        private const val EXTRA_WAKE_REASON = "com.prts.client.call.EXTRA_WAKE_REASON"

        /** 这次前台服务是否只为「来电」而起；正在通话的保持服务不能被挂断顺手停掉。 */
        @Volatile private var wakeOnly = false

        /** Companion app asks for an incoming-call style wake (full-screen intent). */
        fun requestWake(ctx: Context, endpoint: String, said: String = "", reason: String = "") {
            val intent = Intent(ctx, CallKeepAliveService::class.java)
                .setAction(ACTION_WAKE_REQUEST)
                .putExtra(EXTRA_ENDPOINT, endpoint)
                .putExtra(EXTRA_WAKE_TEXT, said)
                .putExtra(EXTRA_WAKE_REASON, reason)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent)
                else ctx.startService(intent)
            } catch (_: Exception) { }
        }

        /** 挂断/超时：收起来电通知；只有这次服务是专门为来电起的才停服务。 */
        fun stopWake(ctx: Context) {
            cancelWakeNotice(ctx)
            if (wakeOnly) {
                wakeOnly = false
                stop(ctx)
            }
        }

        /** 接听：只收起来电通知，通话保持服务继续跑。 */
        fun cancelWakeNotice(ctx: Context) {
            try {
                val manager = ctx.getSystemService(NotificationManager::class.java)
                manager?.cancel(ID_WAKE)
            } catch (_: Exception) { }
        }

        @Volatile
        var running = false
            private set

        fun start(ctx: Context, endpoint: String = "", mic: Boolean = false) {
            val intent = Intent(ctx, CallKeepAliveService::class.java)
                .putExtra(EXTRA_ENDPOINT, endpoint)
                .putExtra(EXTRA_MIC, mic)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent)
                else ctx.startService(intent)
            } catch (_: Exception) { }
        }

        fun startProbe(ctx: Context, endpoint: String) {
            val intent = Intent(ctx, CallKeepAliveService::class.java)
                .putExtra(EXTRA_ENDPOINT, endpoint)
                .putExtra(EXTRA_PROBE_WS, true)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent)
                else ctx.startService(intent)
            } catch (_: Exception) { }
        }

        fun stop(ctx: Context) {            running = false
            try { ctx.stopService(Intent(ctx, CallKeepAliveService::class.java)) } catch (_: Exception) { }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    /** Sticky: once the call UI asks for the microphone type, a later plain start must not drop it. */
    @Volatile private var micType = false
    private var heartbeat: Thread? = null
    private var audioHold: Thread? = null
    @Volatile private var audioHoldAlive = false
    @Volatile private var alive = false
    @Volatile private var beatOk: Boolean? = null
    private var beatTicks = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("PRTS-Call", "svc onStartCommand action=${intent?.action} startId=$startId")
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_HANGUP) {
            sendBroadcast(Intent(ACTION_HANGUP).setPackage(packageName))
            stopSelf()
            return START_NOT_STICKY
        }
        val wakeRequest = intent?.action == ACTION_WAKE_REQUEST
        val wasRunning = running
        if (wakeRequest && !wasRunning) wakeOnly = true
        if (intent?.getBooleanExtra(EXTRA_MIC, false) == true) micType = true
        try {
            val manager = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "通话保持", NotificationManager.IMPORTANCE_LOW))
            val notification = callNotification()
            if (Build.VERSION.SDK_INT >= 29) {
                var types = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
                if (Build.VERSION.SDK_INT >= 34 && micType) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                try {
                    startForeground(ID, notification, types)
                } catch (error: SecurityException) {
                    // No RECORD_AUDIO (or the OS refused a background mic type): keep the call.
                    Log.w("PRTS-Call", "microphone FGS type refused; falling back to specialUse", error)
                    startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                }
            } else {
                startForeground(ID, notification)
            }
            running = true
            acquireWakeLock()
            acquireWifiLock()
            startHeartbeat(intent?.getStringExtra(EXTRA_ENDPOINT).orEmpty())
            if (intent?.getBooleanExtra(EXTRA_PROBE_WS, false) == true) startProbeWebSocket()
            if (wakeRequest) postWakeNotification(intent?.getStringExtra(EXTRA_WAKE_TEXT).orEmpty(),
                intent?.getStringExtra(EXTRA_WAKE_REASON).orEmpty())
        } catch (error: Exception) {
            Log.w("PRTS-Call", "svc startForeground failed", error)
            running = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Incoming-call style notification: the only reliable screen-on-over-keyguard path. */
    private fun postWakeNotification(said: String, reason: String) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            val ring = CallAlertSettings.ringEnabled(this)
            val vibrate = CallAlertSettings.vibrateEnabled(this)
            val channel = wakeChannelId(ring, vibrate)
            if (Build.VERSION.SDK_INT >= 26) {
                // 0.0.81：App 在后台/关着时 FSI 可能只弹横幅，声音和震动必须由通道自己出。
                // 按「权限 → 主动联系」的两个开关选 4 个通道之一，通道设置固定、组合固定。
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                manager.createNotificationChannel(
                    NotificationChannel(channel, "唤醒来电", NotificationManager.IMPORTANCE_HIGH).apply {
                        if (ring) setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), attrs)
                        else setSound(null, null)
                        enableVibration(vibrate)
                        if (vibrate) vibrationPattern = longArrayOf(0, 700, 700)
                    })
            }
            val full = PendingIntent.getActivity(this, 11,
                com.prts.client.ui.WakeCallActivity.intent(this, said, reason),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channel)
            else Notification.Builder(this)
            val notification = builder.setSmallIcon(android.R.drawable.stat_sys_phone_call)
                .setContentTitle("小鲸鱼来电")
                .setContentText("点一下进入通话")
                .setCategory(Notification.CATEGORY_CALL)
                .setPriority(Notification.PRIORITY_HIGH)
                .setContentIntent(full)
                .setFullScreenIntent(full, true)
                .setAutoCancel(true)
                .setTimeoutAfter(30_000L)
                .build()
            manager.notify(ID_WAKE, notification)
            Log.i("PRTS-Call", "wake notification posted, full-screen intent armed, channel=$channel ring=$ring vibrate=$vibrate")
        } catch (error: Exception) {
            Log.w("PRTS-Call", "wake notification failed", error)
        }
    }

    /** 0.0.81：4 个通道对应「响铃×震动」四种组合；通道 ID 固定，设置只在首次创建时生效。 */
    private fun wakeChannelId(ring: Boolean, vibrate: Boolean): String = when {
        ring && vibrate -> "call_wake_rv"
        ring -> "call_wake_r"
        vibrate -> "call_wake_v"
        else -> "call_wake_s"
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val lock = wakeLock ?: (getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "prts:call")?.also { it.setReferenceCounted(false) })
            ?: return
        wakeLock = lock
        try { lock.acquire(CALL_WAKE_LOCK_MS) } catch (_: Exception) { }
    }

    /**
     * Probes the relay every few seconds so a silently dead socket cannot hide
     * behind an idle connection, and so background network loss is visible in logcat.
     */
    private fun startHeartbeat(endpoint: String) {
        val base = endpoint.trim().trimEnd('/')
        if (base.isEmpty() || heartbeat != null) return
        alive = true
        heartbeat = Thread({
            while (alive) {
                try { Thread.sleep(5000L) } catch (_: InterruptedException) { break }
                if (!alive) break
                val ok = probe(base + "/healthz")
                beatTicks++
                if (beatOk != ok || beatTicks % 6 == 0) {
                    Log.i("PRTS-Call", "svc heartbeat ok=$ok tick=$beatTicks")
                    beatOk = ok
                }
            }
        }, "PRTS-CallHeartbeat").apply { isDaemon = true; start() }
    }

    private fun probe(url: String): Boolean = try {
        val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 3000
            readTimeout = 3000
            requestMethod = "GET"
        }
        val code = connection.responseCode
        connection.disconnect()
        code in 200..299
    } catch (_: Exception) { false }

    /**
     * Diagnostic only: a service-owned WebSocket to the same relay, so a socket that dies
     * after the activity pauses can be told apart from an OS-level kill of the whole app.
     */
    private fun startProbeWebSocket() {
        alive = true
        Thread({
            val profile = runCatching { com.prts.client.net.ConnectionSettings.load(this) }.getOrNull()
            val base = com.prts.client.net.SecureUrl.base(profile?.endpoint?.trim()?.trimEnd('/').orEmpty())
            if (base.isEmpty()) { Log.i("PRTS-Call", "probe ws skipped: no endpoint"); return@Thread }
            val wsUrl = base
                .replaceFirst(Regex("^https://", RegexOption.IGNORE_CASE), "wss://")
                .replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "ws://") + "/mobile-call/v1/ws"
            val client = okhttp3.OkHttpClient.Builder()
                .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS).build()
            val request = okhttp3.Request.Builder().url(wsUrl).apply {
                if (profile != null && profile.apiKey.isNotBlank()) header("Authorization", "Bearer ${profile.apiKey}")
            }.build()
            Log.i("PRTS-Call", "probe ws connecting $wsUrl")
            val socket = client.newWebSocket(request, object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                    Log.i("PRTS-Call", "probe ws open code=${response.code}")
                }
                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                    Log.i("PRTS-Call", "probe ws message ${text.take(80)}")
                }
                override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                    Log.i("PRTS-Call", "probe ws closed code=$code reason=$reason")
                }
                override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                    Log.i("PRTS-Call", "probe ws failure ${t.javaClass.simpleName}: ${t.message}")
                }
            })
            while (alive) {
                try { Thread.sleep(10000L) } catch (_: InterruptedException) { break }
                Log.i("PRTS-Call", "probe ws ping sent=${socket.send("{\"type\":\"ping\"}")}")
            }
            socket.close(1000, "probe done")
        }, "PRTS-ProbeWs").apply { isDaemon = true; start() }
    }

    /**
     * Keeps a silent audio client alive for the whole call. vivo freezes background
     * processes that have no active audio session even when they hold a mic FGS.
     */
    private fun startSilentAudio() {
        if (audioHold != null) return
        audioHoldAlive = true
        audioHold = Thread({
            val rate = 16000
            val track = try {
                val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                AudioTrack(AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192), AudioTrack.MODE_STREAM)
            } catch (_: Exception) { null }
            if (track == null) { Log.w("PRTS-Call", "silent audio: no AudioTrack"); return@Thread }
            try {
                track.play()
                val zeros = ShortArray(rate / 10)
                while (audioHoldAlive) {
                    track.write(zeros, 0, zeros.size)
                }
            } catch (error: Exception) {
                Log.w("PRTS-Call", "silent audio failed", error)
            } finally {
                runCatching { track.stop() }
                runCatching { track.release() }
            }
        }, "PRTS-CallAudioHold").apply { isDaemon = true; start() }
    }

    /** Keeps the Wi-Fi radio out of power-save so the call socket survives screen-off. */
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val lock = wifiLock ?: try {
            val wifi = applicationContext.getSystemService(WifiManager::class.java)
            wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "prts:call-wifi")
                ?.also { it.setReferenceCounted(false) }
        } catch (_: Exception) { null }
        wifiLock = lock ?: return
        try { lock.acquire() } catch (_: Exception) { }
    }

    private fun callNotification(): Notification {
        val open = PendingIntent.getActivity(this, 1,
            Intent(this, com.prts.client.ui.CallActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(com.prts.client.ui.CallActivity.EXTRA_KEEP_ALIVE, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val hangup = PendingIntent.getService(this, 2,
            Intent(this, CallKeepAliveService::class.java).setAction(ACTION_HANGUP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("通话进行中")
            .setContentText("麦克风与连接保持中，锁屏或返回桌面也不会断开")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_CALL)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "结束通话", hangup).build())
            .build()
    }

    override fun onDestroy() {
        Log.i("PRTS-Call", "svc onDestroy")
        running = false
        alive = false
        audioHoldAlive = false
        heartbeat?.interrupt(); heartbeat = null
        audioHold?.interrupt(); audioHold = null
        try { wakeLock?.release() } catch (_: Exception) { }
        wakeLock = null
        try { wifiLock?.release() } catch (_: Exception) { }
        wifiLock = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
        super.onDestroy()
    }

    private val CALL_WAKE_LOCK_MS = 60L * 60L * 1000L
}
