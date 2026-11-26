package com.prts.client.call

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.prts.client.net.ConnectionSettings
import com.prts.client.ui.WakeCallActivity
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * M4 手机常驻：一条「只收唤醒帧」的 socket（relay `?role=presence`）。
 *
 * 为什么单独一条连接：她主动打来时，App 不能是关着的。通话 socket 只有进了通话页才连，
 * 权限页 socket 只有打开权限页才连，所以「铃」得有一个常驻的耳朵。
 * 这里只做三件事：保持连接、收到 wake-call 就拉起系统来电界面、断了自动重连；
 * 不读屏幕、不读通知、不开麦克风，也不是开机自启——App 起一次，它就跟着起。
 */
class PresenceService : Service() {
    companion object {
        private const val TAG = "PRTS-Presence"
        private const val CHANNEL = "presence"
        private const val ID = 47654
        const val ACTION_STOP = "com.prts.client.call.PRESENCE_STOP"

        @Volatile
        var running = false
            private set

        fun start(ctx: Context) {
            val intent = Intent(ctx, PresenceService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent) else ctx.startService(intent)
            } catch (_: Exception) { }
        }

        fun stop(ctx: Context) {
            running = false
            try { ctx.stopService(Intent(ctx, PresenceService::class.java)) } catch (_: Exception) { }
        }
    }

    private var thread: Thread? = null
    private var noticeThread: Thread? = null
    @Volatile private var alive = false
    @Volatile private var lastNoticeAt = 0L
    private var socket: WebSocket? = null
    /** 用户主动关掉常驻（通知里的按钮）时，不再自动重起。 */
    @Volatile private var stopping = false
    private var restartScheduled = false
    /** 2026-11-26：断线自愈——网络变化/心跳超时都强制重连；切网或划掉任务后不再等 App 重启。 */
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var watchdogThread: Thread? = null
    @Volatile private var socketOpen = false
    @Volatile private var lastPongAt = 0L
    /** 第 1 项（2026-11-20）：来电页挂断/超时的包内广播。 */
    private var rejectReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 第 1 项：来电页 settle(accepted=false) 会发这条包内广播；立刻经常驻 socket
        // 回报 wake-rejected，服务端不用再等 300 秒 grace。
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val reason = intent?.getStringExtra(WakeCallActivity.EXTRA_REJECT_REASON)
                    .orEmpty().ifBlank { "declined" }
                Log.i(TAG, "wake rejected locally $reason")
                val sent = sendFrame(org.json.JSONObject()
                    .put("type", "wake-rejected")
                    .put("reason", reason))
                Log.i(TAG, "wake-rejected ${if (sent) "sent" else "failed"}")
            }
        }
        rejectReceiver = receiver
        try {
            // RECEIVER_NOT_EXPORTED：只收本 App 的广播（来电页和常驻服务同一进程/包）。
            ContextCompat.registerReceiver(this, receiver,
                IntentFilter(WakeCallActivity.ACTION_WAKE_REJECTED),
                ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (error: Exception) {
            Log.w(TAG, "reject receiver register failed", error)
        }
    }

    /** 常驻 socket 还没连上时不假装发出去（返回 false，调用方只记日志）。 */
    private fun sendFrame(payload: org.json.JSONObject): Boolean {
        val ws = socket ?: return false
        if (!alive) return false
        return runCatching { ws.send(payload.toString()) }.getOrDefault(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action} startId=$startId")
        if (intent?.action == ACTION_STOP) {
            stopping = true
            stopSelf()
            return START_NOT_STICKY
        }
        stopping = false
        try {
            val manager = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, "等她叫你", NotificationManager.IMPORTANCE_LOW))
            }
            startForeground(ID, notification())
        } catch (error: Exception) {
            Log.w(TAG, "foreground start failed", error)
            // 前台通知都起不来（多半是通知权限被关）：重起也没用，别进死循环。
            stopping = true
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        if (!alive || thread?.isAlive != true) {
            alive = true
            thread = Thread({ loop() }, "PRTS-Presence").apply { isDaemon = true; start() }
        }
        if (watchdogThread?.isAlive != true) {
            watchdogThread = Thread({ watchdog() }, "PRTS-PresenceWatch").apply { isDaemon = true; start() }
        }
        registerNetworkCallback()
        if (noticeThread == null) {
            noticeThread = Thread({ noticePump() }, "PRTS-PresenceNotify").apply { isDaemon = true; start() }
        }
        // 0.0.80：被系统杀掉后允许系统重起；App 被从最近任务划掉时自己也补一次。
        // 之前是 START_NOT_STICKY（stopIfKilled=true），进程一没就永久失去「她打给你」的耳朵。
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "task removed, schedule presence restart")
        if (!stopping) scheduleRestart()
    }

    /** 用 AlarmManager 兜底重起（服务被划掉/被杀后进程可能已经没了）。 */
    private fun scheduleRestart() {
        if (restartScheduled || stopping) return
        restartScheduled = true
        try {
            val restart = Intent(this, PresenceService::class.java)
            val pending = PendingIntent.getForegroundService(
                this, 43, restart,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
            val alarm = getSystemService(AlarmManager::class.java)
            alarm?.set(AlarmManager.RTC, System.currentTimeMillis() + 2000L, pending)
            Log.i(TAG, "presence restart scheduled in 2s")
        } catch (error: Exception) {
            Log.w(TAG, "presence restart schedule failed", error)
            restartScheduled = false
        }
    }

    /** 网络恢复就踢一脚：切 Wi-Fi/流量后旧 socket 往往半开，不等它自己超时。 */
    private fun registerNetworkCallback() {
        if (netCallback != null) return
        try {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "network available, force presence reconnect")
                    forceReconnect("network-available")
                }
            }
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(), cb)
            netCallback = cb
        } catch (error: Exception) {
            Log.w(TAG, "network callback register failed", error)
        }
    }

    private fun forceReconnect(why: String) {
        val ws = socket ?: return
        Log.i(TAG, "presence force reconnect: $why")
        runCatching { ws.cancel() }
    }

    /** 应用层心跳（中继 presence 收到 type=ping 会回 type=pong）：60s 没 pong 就重连。 */
    private fun watchdog() {
        while (alive) {
            try { Thread.sleep(15_000L) } catch (_: InterruptedException) { break }
            val ws = socket ?: continue
            if (!socketOpen) continue
            val now = System.currentTimeMillis()
            if (lastPongAt > 0 && now - lastPongAt > 60_000L) {
                Log.i(TAG, "presence heartbeat timeout (${now - lastPongAt}ms), force reconnect")
                forceReconnect("heartbeat-timeout")
                continue
            }
            runCatching { ws.send("{\"type\":\"ping\"}") }
        }
        Log.i(TAG, "presence watchdog exited")
    }

    /** observe.notifications 的投递：只把「新出现的」通知标题推到中继，服务端还会再门一次。 */
    private fun noticePump() {
        while (alive) {
            try {
                Thread.sleep(5_000L)
            } catch (_: InterruptedException) {
                break
            }
            val ws = socket ?: continue
            if (!alive) break
            val fresh = NotificationFeed.since(lastNoticeAt)
            if (fresh.isEmpty()) continue
            val arr = org.json.JSONArray()
            for (item in fresh) {
                arr.put(JSONObject().apply {
                    put("at", item.at)
                    put("pkg", item.pkg)
                    put("title", item.title)
                    put("text", item.text)
                })
            }
            val ok = runCatching {
                ws.send(JSONObject().put("type", "notifications").put("items", arr).toString())
            }.getOrDefault(false)
            if (ok) lastNoticeAt = fresh.last().at
        }
        Log.i(TAG, "notice pump exited")
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(this, 31,
            Intent(this, PresenceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("小鲸鱼在等你")
            .setContentText("她可以主动打给你（不影响正常使用）")
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "关掉常驻", stop).build())
            .build()
    }

    private fun loop() {
        var delay = 3L
        while (alive) {
            var latch: CountDownLatch? = null
            try {
                val profile = runCatching { ConnectionSettings.load(this) }.getOrNull()
                val base = com.prts.client.net.SecureUrl.base(profile?.endpoint?.trim()?.trimEnd('/').orEmpty())
                if (base.isEmpty() || profile?.isPaired != true) {
                    Thread.sleep(10_000L)
                    continue
                }
                val wsUrl = base
                    .replaceFirst(Regex("^https://", RegexOption.IGNORE_CASE), "wss://")
                    .replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "ws://") +
                    "/mobile-call/v1/ws?role=presence"
                val request = Request.Builder().url(wsUrl).apply {
                    if (profile.apiKey.isNotBlank()) header("Authorization", "Bearer ${profile.apiKey}")
                }.build()
                val client = OkHttpClient.Builder()
                    .pingInterval(20, TimeUnit.SECONDS)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.SECONDS)
                    .build()
                val done = CountDownLatch(1)
                latch = done
                Log.i(TAG, "presence connecting $wsUrl")
                val ws = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        Log.i(TAG, "presence open code=${response.code}")
                        socketOpen = true
                        lastPongAt = System.currentTimeMillis()
                        // 连上就补一次「最近的」通知，服务端在那头过权限门。
                        runCatching {
                            val recent = NotificationFeed.since(0)
                            if (recent.isNotEmpty()) {
                                val arr = org.json.JSONArray()
                                for (item in recent) {
                                    arr.put(JSONObject().apply {
                                        put("at", item.at)
                                        put("pkg", item.pkg)
                                        put("title", item.title)
                                        put("text", item.text)
                                    })
                                }
                                webSocket.send(JSONObject().put("type", "notifications").put("items", arr).toString())
                                lastNoticeAt = recent.last().at
                            }
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) = onFrame(text)

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        Log.i(TAG, "presence closed code=$code")
                        socketOpen = false
                        done.countDown()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.i(TAG, "presence failure ${t.javaClass.simpleName}: ${t.message}")
                        socketOpen = false
                        done.countDown()
                    }
                })
                socket = ws
                delay = 3
                // 2026-11-26：别无限等半开连接——5 分钟既没 close 也没 failure 就主动 cancel 重连。
                if (!done.await(5, TimeUnit.MINUTES)) {
                    Log.i(TAG, "presence await timeout, cancel socket")
                    runCatching { ws.cancel() }
                    done.await(5, TimeUnit.SECONDS)
                }
                socketOpen = false
                socket = null
            } catch (_: InterruptedException) {
                break
            } catch (error: Exception) {
                Log.i(TAG, "presence loop error ${error.javaClass.simpleName}: ${error.message}")
            }
            latch = null
            if (!alive) break
            try { Thread.sleep(delay * 1000) } catch (_: InterruptedException) { break }
            delay = minOf(delay * 2, 30L)
        }
        Log.i(TAG, "presence loop exited")
    }

    /** 目前只认 wake-call：别的帧一律忽略，这条连接不参与聊天。 */
    private fun onFrame(text: String) {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        val type = obj.optString("type")
        if (type == "pong") {
            lastPongAt = System.currentTimeMillis()
            return
        }
        if (type != "wake-call") return
        lastPongAt = System.currentTimeMillis()
        val said = obj.optString("text")
        val reason = obj.optString("reason")
        Log.i(TAG, "wake-call received reason=$reason text=$said")
        // 第 3 项（2026-11-20）：先回执——中继靠它区分「真送到」和「半开连接」；
        // 2 秒内没回执，服务端会按「手机没回执」算没送到（不扣次数、不记没人接）。
        val acked = sendFrame(JSONObject().put("type", "wake-ack").put("at", System.currentTimeMillis()))
        Log.i(TAG, "wake-ack ${if (acked) "sent" else "failed"}")
        try {
            startActivity(WakeCallActivity.intent(this, said, reason))
        } catch (error: Exception) {
            Log.w(TAG, "wake activity blocked", error)
        }
        val endpoint = runCatching { ConnectionSettings.load(this).endpoint }.getOrNull().orEmpty()
        CallKeepAliveService.requestWake(this, endpoint, said, reason)
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        rejectReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) { }
        }
        rejectReceiver = null
        netCallback?.let {
            try { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } catch (_: Exception) { }
        }
        netCallback = null
        watchdogThread?.interrupt()
        watchdogThread = null
        socketOpen = false
        alive = false
        thread?.interrupt()
        thread = null
        noticeThread?.interrupt()
        noticeThread = null
        runCatching { socket?.close(1000, "presence stop") }
        socket = null
        running = false
        // 不是用户按「关掉常驻」时，补一次重起（划掉任务/被系统回收都走这里）。
        if (!stopping) scheduleRestart()
        super.onDestroy()
    }
}
