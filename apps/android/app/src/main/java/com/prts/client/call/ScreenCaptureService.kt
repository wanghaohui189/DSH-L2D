package com.prts.client.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import com.prts.client.audio.DiagLog
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 看一眼手机屏幕（observe.screen 的手机端一半）。
 *
 * 2026-11-17 改：以前授权后**立刻**截一帧——截到的永远是通话页自己；主人切到别的
 * 应用时那一帧早拍完了，看起来就是「切应用就不读取屏幕了」。现在改成「授权后开一个
 * 90 秒窗口」：主人切到想让她看的应用（通话页 onStop）后 1.5 秒才截，最多 3 帧、
 * 每两帧间隔 ≥6 秒；窗口到期 / 通话页销毁 / 系统停止共享就释放投影。
 *
 * 规矩没变：
 * 1. 每次看屏幕都要主人自己在系统弹窗里点一次授权；
 * 2. 只截帧、最长边 900、JPEG 质量 78，不录屏、不存盘、不留历史；
 * 3. 截到的字节交给 [sink]（通话页注入的 socket 发送器），服务端还要再过一次
 *    `observe.screen` 门。
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "PRTS-Screen"
        private const val CHANNEL = "screen-look"
        private const val ID = 47655
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val ACTION_STOP = "com.prts.client.action.SCREEN_LOOK_STOP"
        private const val MAX_EDGE = 900
        private const val QUALITY = 78

        /** 授权后窗口最多开多久；切走太慢就不拍了。 */
        private const val WINDOW_MS = 90_000L

        /** 切走后等切换动画/首屏画完再截。 */
        private const val SETTLE_MS = 1_500L
        private const val MAX_FRAMES = 3
        private const val FRAME_GAP_MS = 6_000L

        /** 通话页注入：截到一帧就交给它发送；页面销毁时应置空。 */
        @Volatile
        var sink: ((jpeg: ByteArray, label: String) -> Boolean)? = null

        /** 正在等主人切应用；收到的每个信号 = 「现在切换了，拍一帧」。 */
        @Volatile
        private var signals: LinkedBlockingQueue<String>? = null

        /** 窗口是否还开着（系统停止共享 / 通话页销毁 / 超过帧数都会置 true）。 */
        @Volatile
        private var closed = false

        @Volatile
        private var running = false

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val intent = Intent(ctx, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent)
                else ctx.startService(intent)
            } catch (error: Exception) {
                Log.w(TAG, "start blocked", error)
            }
        }

        /** 通话页进后台（onStop）：窗口还开着就抓一帧「现在的屏幕」。 */
        fun onAppBackground() {
            signals?.offer("app_background")
        }

        /** 通话页销毁：关掉窗口（不再截、释放投影）。 */
        fun closeWindow() {
            closed = true
        }

        fun isWindowOpen(): Boolean = running
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_STOP) {
            closed = true
            DiagLog.write("screen.log", "stop_tapped")
            return START_NOT_STICKY
        }
        if (running) {
            Log.i(TAG, "窗口已在运行，忽略这次 start")
            return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, "看一眼屏幕", NotificationManager.IMPORTANCE_LOW))
            }
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
            ServiceCompat.startForeground(this, ID, notification(), type)
        } catch (error: Exception) {
            Log.w(TAG, "foreground start failed", error)
            DiagLog.write("screen.log", "foreground_failed ${error.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        Thread({ captureWindow(intent) }, "PRTS-ScreenGrab").apply { isDaemon = true }.start()
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else Notification.Builder(this)
        val stop = PendingIntent.getService(this, 7,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return builder.setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("她准备看一眼你的屏幕")
            .setContentText("切到想让她看的应用，她会自动截一帧（90 秒内）")
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stop)
            .build()
    }

    private fun captureWindow(intent: Intent) {
        DiagLog.install(filesDir)
        val queue = LinkedBlockingQueue<String>()
        signals = queue
        closed = false
        var projection: MediaProjection? = null
        var display: android.hardware.display.VirtualDisplay? = null
        var reader: ImageReader? = null
        val startAt = SystemClock.elapsedRealtime()
        var frames = 0
        var lastFrameAt = 0L
        try {
            val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val data = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
            if (code == 0 || data == null) {
                DiagLog.write("screen.log", "no_consent")
                return
            }
            val manager = getSystemService(MediaProjectionManager::class.java)
            projection = manager.getMediaProjection(code, data)
            if (projection == null) {
                DiagLog.write("screen.log", "projection_null")
                return
            }
            // Android 14+（targetSdk 34）要求先注册回调再 createVirtualDisplay，否则会抛异常。
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    closed = true
                    DiagLog.write("screen.log", "projection_stopped_by_system")
                }
            }, Handler(Looper.getMainLooper()))

            val metrics = resources.displayMetrics
            val width = ((metrics.widthPixels * 0.5f).toInt() / 2 * 2).coerceAtLeast(2)
            val height = ((metrics.heightPixels * 0.5f).toInt() / 2 * 2).coerceAtLeast(2)
            // maxImages=4：不频繁取帧时让队列多存几帧，切应用后的新画面不会被挤掉。
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 4)
            display = projection.createVirtualDisplay("prts-screen", width, height,
                metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null)
            DiagLog.write("screen.log", "window_open ${width}x$height window_ms=$WINDOW_MS")

            while (!closed && frames < MAX_FRAMES) {
                val left = WINDOW_MS - (SystemClock.elapsedRealtime() - startAt)
                if (left <= 0) {
                    DiagLog.write("screen.log", "window_timeout frames=$frames")
                    break
                }
                val why = queue.poll(minOf(200L, left), TimeUnit.MILLISECONDS) ?: continue
                if (frames > 0) {
                    val gap = SystemClock.elapsedRealtime() - lastFrameAt
                    if (gap < FRAME_GAP_MS) sleepQuietly(FRAME_GAP_MS - gap)
                }
                sleepQuietly(SETTLE_MS)
                if (closed) break
                val image = awaitImage(reader)
                if (image == null) {
                    DiagLog.write("screen.log", "frame_none trigger=$why")
                    continue
                }
                val jpeg = runCatching { toJpeg(image) }.getOrNull()
                if (jpeg == null) {
                    DiagLog.write("screen.log", "encode_failed trigger=$why")
                    continue
                }
                val target = sink
                if (target == null) {
                    DiagLog.write("screen.log", "sink_null trigger=$why")
                    break
                }
                val sent = target(jpeg, "手机屏幕")
                frames += 1
                lastFrameAt = SystemClock.elapsedRealtime()
                DiagLog.write("screen.log",
                    "frame ${jpeg.size / 1024}KB sent=$sent trigger=$why idx=$frames")
                Log.i(TAG, "屏幕帧 ${jpeg.size / 1024}KB 已发送=$sent ($why)")
            }
        } catch (error: Exception) {
            DiagLog.write("screen.log", "capture_error ${error.javaClass.simpleName}: ${error.message}")
            Log.w(TAG, "capture failed ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            signals = null
            runCatching { reader?.close() }
            runCatching { display?.release() }
            runCatching { projection?.stop() }
            running = false
            DiagLog.write("screen.log",
                "window_closed frames=$frames elapsed_ms=${SystemClock.elapsedRealtime() - startAt}")
            stopSelf()
        }
    }

    private fun awaitImage(reader: ImageReader, timeoutMs: Long = 2_500L): Image? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val frame = try { reader.acquireLatestImage() } catch (_: Exception) { null }
            if (frame != null) return frame
            sleepQuietly(50L)
        }
        return null
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms.coerceAtLeast(0L))
        } catch (_: InterruptedException) {
        }
    }

    private fun toJpeg(image: Image): ByteArray? {
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val padded = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        image.close()
        val scaled = scaleDown(cropped)
        return try {
            ByteArrayOutputStream().use { out ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) null else out.toByteArray()
            }
        } finally {
            if (scaled !== cropped) scaled.recycle()
            cropped.recycle()
            padded.recycle()
        }
    }

    private fun scaleDown(source: Bitmap): Bitmap {
        val edge = maxOf(source.width, source.height)
        if (edge <= MAX_EDGE) return source
        val ratio = MAX_EDGE.toFloat() / edge
        return Bitmap.createScaledBitmap(source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1), true)
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
