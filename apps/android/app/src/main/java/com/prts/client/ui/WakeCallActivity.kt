package com.prts.client.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.prts.client.BuildConfig

/**
 * Incoming-call screen for a proactive wake.
 *
 * 2026-11-20（0.0.78）：以前这里是个「直达通话」的跳板——点开来电就等于接通，而且
 * CallActivity 会把来电文案当成主人说的话发回去。现在改成真·来电页：响铃 + 震动 +
 * 「接听 / 挂断」，30 秒不接按未接处理；只有点了接听才进通话页，通话页不再替主人发言。
 */
class WakeCallActivity : AppCompatActivity() {
    companion object {
        const val ACTION_WAKE_CALL = "com.prts.client.action.WAKE_CALL"
        /** What the user actually said after the wake word (may be empty). */
        const val EXTRA_WAKE_TEXT = "com.prts.client.extra.WAKE_TEXT"
        /** Why she called: "silence" / "followup"（只用于来电页文案，不发给服务端）. */
        const val EXTRA_WAKE_REASON = "com.prts.client.extra.WAKE_REASON"

        // 2026-11-26：原来的 WAKE_TOKEN 常量已删除。它只防「同机 App 乱拉来电页」，而源码一开源
        // 就等于公开；现在改为 Manifest 的 signature 级权限（见 AndroidManifest.xml）+ activity
        // 不导出，口令本身不再需要，也不该留在公开源码里。

        /** 第 1 项（2026-11-20）：挂断/超时/划掉来电页时，包内广播给 PresenceService，
         *  由它走常驻 socket 回报服务端当场结单，不用再等 5 分钟 grace。 */
        const val ACTION_WAKE_REJECTED = "com.prts.client.call.WAKE_REJECTED"
        const val EXTRA_REJECT_REASON = "com.prts.client.extra.REJECT_REASON"

        /** 30 秒没人接 = 未接；服务端另有 5 分钟兜底，不依赖手机回报。 */
        private const val RING_TIMEOUT_MS = 30_000L

        fun intent(ctx: Context, said: String = "", reason: String = ""): Intent =
            Intent(ctx, WakeCallActivity::class.java)
                .setAction(ACTION_WAKE_CALL)
                .putExtra(EXTRA_WAKE_TEXT, said)
                .putExtra(EXTRA_WAKE_REASON, reason)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var alert: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var settled = false
    private var wakeText = ""
    private var wakeReason = ""

    private val timeout = Runnable { settle(accepted = false, why = "timeout") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        // 2026-11-26：这里原来的 token 比对已删除。activity 现在 exported=false（见 Manifest），
        // 外部 App 既不能直启它、也不能绕过 receiver 的 signature 权限，口令校验没有对象了。
        if (isFinishing) {
            finish()
            return
        }
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("probe", false) == true) {
            // Diagnostic entry: let adb ask the keep-alive service to open its own socket.
            com.prts.client.call.CallKeepAliveService.startProbe(this, intent.getStringExtra("endpoint").orEmpty())
            finish()
            return
        }
        wakeText = intent?.getStringExtra(EXTRA_WAKE_TEXT).orEmpty().trim()
        wakeReason = intent?.getStringExtra(EXTRA_WAKE_REASON).orEmpty().trim()
        setContentView(buildUi())
        startAlert()
        handler.postDelayed(timeout, RING_TIMEOUT_MS)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun round(color: Int, radiusDp: Int = 28): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
        }

    private fun buildUi(): FrameLayout {
        val root = FrameLayout(this)
        root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#10131A"), Color.parseColor("#232A3A")))

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(56), dp(28), dp(40))
        }
        val frameParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(column, frameParams)

        val spacerTop = android.view.View(this)
        column.addView(spacerTop, LinearLayout.LayoutParams(1, 0, 1f))

        column.addView(TextView(this).apply {
            text = "小鲸鱼来电"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val subtitle = when {
            wakeText.isNotBlank() -> wakeText
            wakeReason == "silence" -> "想听听你的声音"
            wakeReason == "followup" -> "想接着上次的话说"
            else -> "想跟你说句话"
        }
        column.addView(TextView(this).apply {
            text = subtitle
            setTextColor(Color.parseColor("#C3CAD6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })

        val spacerBottom = android.view.View(this)
        column.addView(spacerBottom, LinearLayout.LayoutParams(1, 0, 1f))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        column.addView(buttons, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val decline = TextView(this).apply {
            text = "挂断"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            background = round(Color.parseColor("#3A404D"))
            setOnClickListener { settle(accepted = false, why = "declined") }
        }
        buttons.addView(decline, LinearLayout.LayoutParams(0, dp(62), 1f).apply {
            rightMargin = dp(10)
        })

        val accept = TextView(this).apply {
            text = "接听"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            background = round(Color.parseColor("#2E9E5B"))
            setOnClickListener { settle(accepted = true, why = "accepted") }
        }
        buttons.addView(accept, LinearLayout.LayoutParams(0, dp(62), 1f).apply {
            leftMargin = dp(10)
        })

        column.addView(TextView(this).apply {
            text = "30 秒无人接听会自动挂断"
            setTextColor(Color.parseColor("#7A8290"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(24)
        })
        return root
    }

    private fun startAlert() {
        // 「权限 → 主动联系」里的两个开关；关掉就只亮屏，不响也不震。
        if (CallAlertSettings.ringEnabled(this)) {
            try {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                alert = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                    setDataSource(this@WakeCallActivity, uri)
                    isLooping = true
                    prepare()
                    start()
                }
            } catch (error: Exception) {
                alert?.release()
                alert = null
                android.util.Log.w("PRTS-Call", "wake ringtone failed", error)
            }
        } else {
            android.util.Log.i("PRTS-Call", "wake ring disabled by settings")
        }
        if (!CallAlertSettings.vibrateEnabled(this)) {
            android.util.Log.i("PRTS-Call", "wake vibrate disabled by settings")
            return
        }
        try {
            val manager = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            vibrator = manager
            if (manager != null && manager.hasVibrator()) {
                val pattern = longArrayOf(0, 700, 700)
                if (Build.VERSION.SDK_INT >= 26) {
                    manager.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    manager.vibrate(pattern, 0)
                }
            }
        } catch (error: Exception) {
            android.util.Log.w("PRTS-Call", "wake vibrate failed", error)
        }
    }

    private fun stopAlert() {
        try {
            alert?.stop()
        } catch (_: Exception) { }
        try {
            alert?.release()
        } catch (_: Exception) { }
        alert = null
        try {
            vibrator?.cancel()
        } catch (_: Exception) { }
        vibrator = null
    }

    private fun settle(accepted: Boolean, why: String) {
        if (settled) return
        settled = true
        handler.removeCallbacks(timeout)
        stopAlert()
        if (accepted) {
            // 接听：收起来电通知，通话保持服务继续；不带任何「主人说过的话」。
            com.prts.client.call.CallKeepAliveService.cancelWakeNotice(this)
            try {
                startActivity(Intent(this, CallActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(CallActivity.EXTRA_KEEP_ALIVE, true)
                    putExtra(CallActivity.EXTRA_FROM_WAKE, true)
                    putExtra(CallActivity.EXTRA_WAKE_ACCEPTED, true)
                })
            } catch (error: Exception) {
                android.util.Log.w("PRTS-Call", "wake accept failed", error)
            }
        } else {
            android.util.Log.i("PRTS-Call", "wake $why")
            // 第 1 项：先回报「拒接」，让服务端当场结单（第二通不会被 pending 挡）；
            // 再收起保留服务与来电通知。300 秒 grace 只作为「手机一个信号都没回」的兜底。
            reportRejected(why)
            com.prts.client.call.CallKeepAliveService.stopWake(this)
        }
        finish()
    }

    /** 第 1 项（2026-11-20）：包内广播给常驻服务；注册方用 RECEIVER_NOT_EXPORTED。 */
    private fun reportRejected(why: String) {
        try {
            sendBroadcast(Intent(ACTION_WAKE_REJECTED).setPackage(packageName)
                .putExtra(EXTRA_REJECT_REASON, why))
            android.util.Log.i("PRTS-Call", "wake rejected broadcast $why")
        } catch (error: Exception) {
            android.util.Log.w("PRTS-Call", "wake rejected broadcast failed", error)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        settle(accepted = false, why = "dismissed")
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        stopAlert()
        super.onDestroy()
    }
}
