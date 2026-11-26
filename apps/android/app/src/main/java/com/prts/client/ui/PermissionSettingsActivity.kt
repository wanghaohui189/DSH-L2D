package com.prts.client.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.prts.client.R
import com.prts.client.databinding.ActivityPermissionsBinding
import com.prts.client.net.ConnectionMode
import com.prts.client.net.ConnectionSettings
import com.prts.client.net.PermissionStoreClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 「权限」页：9 个 scope 可看可改，服务端 PermissionStore 是唯一事实源。
 *
 * 入口：开始页「设置 → 权限」一级列表，以及通话页左上角的「权限」按钮。
 * 协议见 net/PermissionStoreClient.kt；系统权限仍由 Android 自己申请，两边都过才生效。
 */
class PermissionSettingsActivity : AppCompatActivity() {

    private data class Choice(val value: String, val label: String)

    private data class Row(
        val scope: String,
        val title: String,
        val desc: String,
        val choices: List<Choice>? = null,
        val system: String = "",
    )

    private data class Holder(
        val row: Row,
        val root: LinearLayout,
        val switch: SwitchCompat?,
        val value: TextView,
        val hint: TextView,
        val grant: TextView,
    )

    private lateinit var b: ActivityPermissionsBinding
    private var client: PermissionStoreClient? = null
    /** 2026-11-20：「主动联系」卡片里的两个本地开关（响铃 / 震动）。 */
    private var proactiveRingSwitch: SwitchCompat? = null
    private var proactiveVibrateSwitch: SwitchCompat? = null
    /** 第 8 项（2026-11-20）：服务端 permission-state 里的 proactive 快照。 */
    private var proactiveStateText: TextView? = null
    private var proactiveState: JsonObject? = null

    private val rows = listOf(
        Row(
            "resident.mode", "常驻形态", "决定她能不能在通话之外出现；只在通话时「主动联系」不可用。",
            listOf(
                Choice("call_only", "只在通话"),
                Choice("phone_resident", "手机常驻"),
            ),
        ),
        Row("observe.camera", "摄像头", "只在通话中、你主动打开摄像头时才看画面。", system = "camera"),
        Row("observe.screen", "屏幕", "点「让她看屏幕」并授权后，切到目标应用她会自动截一帧（90 秒内有效）。", system = "screen"),
        Row("observe.album", "相册", "只在通话中读相册里最近拍的一张（缩略图），没有相册授权就不读。", system = "album"),
        Row("observe.notifications", "通知", "读取通知标题和内容，需要「通知使用权」。", system = "notifications"),
        Row(
            "listen.background", "后台继续听",
            "切到别的应用/锁屏时继续收音，能在她打开的应用里接着说；只对免提有效，点麦克风说话无效。",
            system = "microphone",
        ),
        Row(
            "control.phone", "手机控制", "off 不动；辅助=按指令点按；托管=连续操作，30 分钟自动过期。",
            listOf(Choice("off", "关"), Choice("assist", "辅助"), Choice("auto", "托管")),
            system = "accessibility",
        ),
        Row("proactive.contact", "主动联系", "她可以主动打来或发消息；常驻形态不是「只在通话」才可用。"),
        Row("memory.longterm", "长期记忆", "通话摘要和事实写进长期记忆；关掉后不注入上次通话。"),
    )

    private val holders = mutableListOf<Holder>()
    private val values = mutableMapOf<String, Any>()
    private val pending = mutableSetOf<String>()
    private val sources = mutableMapOf<String, String>()
    private val expiries = mutableMapOf<String, Long>()
    private var audit: List<JsonObject> = emptyList()
    private var rendering = false
    private var canConnect = true
    private var noConnectReason = ""
    private var device = DeviceState()

    private data class DeviceState(
        val camera: Boolean = false,
        val album: Boolean = false,
        val screen: Boolean = false,
        val notifications: Boolean = false,
        val accessibility: Boolean = false,
        val microphone: Boolean = false,
    )

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshDevice(); render()
    }
    private val albumPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshDevice(); render()
    }
    private val screenCapture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            setLocalFlag(KEY_SCREEN_GRANTED, true)
            toast("已拿到本次录屏授权")
        }
        refreshDevice(); render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPermissionsBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnPermissionsBack.setOnClickListener { finish() }
        buildRows()
        b.btnPermissionsAllOff.setOnClickListener { confirmAllOff() }
        b.btnPermissionsDefaults.setOnClickListener { confirmDefaults() }
        b.btnPermissionsAudit.setOnClickListener { showAudit() }

        val socket = resolveSocket()
        if (socket == null) {
            canConnect = false
            noConnectReason = "还没有可用的连接设置，先在「连接管理」里填地址/配对"
            b.permissionsStatus.text = "未连接"
        } else {
            val (url, token) = socket
            client = PermissionStoreClient(
                url,
                token,
                onState = ::onConnectionState,
                onSnapshot = { snapshot, detail, rows, proactive ->
                    onServerState(snapshot, detail, rows, proactive)
                },
                onError = { message ->
                    pending.clear()
                    render()
                    toast(message)
                },
            ).also { it.connect() }
        }
        refreshDevice()
        render()
    }

    override fun onResume() {
        super.onResume()
        if (!::b.isInitialized) return
        refreshDevice()
        render()
        client?.takeIf { it.isReady }?.query()
    }

    override fun onDestroy() {
        client?.close()
        client = null
        super.onDestroy()
    }

    // ---------- 连接 ----------

    private fun resolveSocket(): Pair<String, String>? {
        val mode = ConnectionSettings.selectedMode(this)
        if (mode == ConnectionMode.LOCAL_DSH) return null
        val profile = try {
            ConnectionSettings.load(this, mode)
        } catch (_: Exception) {
            null
        } ?: return null
        val base = com.prts.client.net.SecureUrl.base(if (profile.isPaired) {
            profile.endpoint.trim().trimEnd('/')
        } else {
            Prefs.get(this).first.trim().trimEnd('/')
        })
        if (base.isBlank()) return null
        val ws = base
            .replaceFirst(Regex("^https://", RegexOption.IGNORE_CASE), "wss://")
            .replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "ws://")
        if (!ws.startsWith("ws://") && !ws.startsWith("wss://")) return null
        val token = if (profile.isPaired) profile.apiKey else ConnectionSettings.loadVoiceKey(this)
        // role=permission 让 relay 用独立 socket 槽，绝不抢占通话用的 phone 连接。
        return (ws + "/mobile-call/v1/ws?role=permission") to token
    }

    private fun onConnectionState(state: String) {
        b.permissionsStatus.text = when (state) {
            "open" -> "已连接"
            "connecting" -> "连接中…"
            "invalid-url" -> "地址不对"
            "unauthorized" -> "连接未授权"
            "failed" -> "连接失败"
            else -> if (state.startsWith("retry ")) "重连 ${state.removePrefix("retry ")}" else state
        }
        if (state == "open") {
            // 延迟 300ms，避免和 relay 的 paired 握手抢在同一个 tick。
            b.root.postDelayed({ if (!isFinishing) client?.query() }, 300L)
        }
    }

    private fun onServerState(snapshot: JsonObject, detail: JsonObject, rows: List<JsonObject>,
                              proactive: JsonObject?) {
        snapshot.entries.forEach { (scope, element) ->
            val raw = element.jsonPrimitive
            val value: Any? = raw.booleanOrNull ?: raw.contentOrNull
            if (value != null) values[scope] = value
        }
        detail.entries.forEach { (scope, element) ->
            val item = runCatching { element.jsonObject }.getOrNull() ?: return@forEach
            sources[scope] = item["source"]?.jsonPrimitive?.contentOrNull.orEmpty()
            expiries[scope] = item["expires_at"]?.jsonPrimitive?.contentOrNull
                ?.toDoubleOrNull()?.toLong() ?: 0L
        }
        audit = rows
        // 第 8 项：拿不到就设 null → 状态行隐藏，别显示过期数字。
        this.proactiveState = proactive
        pending.clear()
        // 把「要不要主动上传 / 后台收不收音」缓存到本地：关着的时候手机根本不上传相册/屏幕，
        // 通话页也据此决定 onPause 要不要释放麦克风。
        for (scope in listOf("observe.album", "observe.screen", "listen.background")) {
            val v = values[scope]
            Prefs.setGrantCached(this, scope, v == true || v == "true")
        }
        render()
    }

    // ---------- UI ----------

    private fun buildRows() {
        b.permissionRows.removeAllViews()
        holders.clear()
        rows.forEach { row ->
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.ui_glass_panel)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val title = TextView(this).apply {
                text = row.title
                setTextColor(ContextCompat.getColor(this@PermissionSettingsActivity, R.color.ui_text))
                textSize = 15f
            }
            head.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val switch = if (row.choices == null) SwitchCompat(this).apply {
                setOnCheckedChangeListener { _, checked ->
                    if (rendering) return@setOnCheckedChangeListener
                    onToggle(row, checked)
                }
            } else null
            val valueLabel = TextView(this).apply {
                setTextColor(ContextCompat.getColor(this@PermissionSettingsActivity, R.color.ui_accent))
                textSize = 13f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setBackgroundResource(R.drawable.ui_chip)
                setOnClickListener { if (!rendering) pickChoice(row) }
            }
            if (switch != null) head.addView(switch) else head.addView(valueLabel)
            root.addView(head)

            val hint = TextView(this).apply {
                setTextColor(ContextCompat.getColor(this@PermissionSettingsActivity, R.color.ui_text_secondary))
                textSize = 11.5f
                setPadding(0, dp(5), 0, 0)
                setLineSpacing(dp(2).toFloat(), 1f)
            }
            root.addView(hint)

            val grant = TextView(this).apply {
                text = "去授权"
                setTextColor(ContextCompat.getColor(this@PermissionSettingsActivity, R.color.ui_accent))
                textSize = 12f
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setBackgroundResource(R.drawable.ui_chip)
                visibility = View.GONE
                setOnClickListener { if (!rendering) requestSystem(row) }
            }
            root.addView(grant, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })

            if (row.scope == "proactive.contact") {
                root.addView(buildCallAlertRows())
            }

            b.permissionRows.addView(root)
            holders.add(Holder(row, root, switch, valueLabel, hint, grant))
        }
    }

    /** 「主动联系」卡片里的响铃 / 震动：本地设置，不占服务端 scope。 */
    private fun buildCallAlertRows(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(0))
        }
        proactiveRingSwitch = addAlertSwitch(
            box, "响铃", "来电时播放铃声；关掉只亮屏，通知本身是静音的。",
            CallAlertSettings.ringEnabled(this)
        ) { CallAlertSettings.setRing(this, it) }
        proactiveVibrateSwitch = addAlertSwitch(
            box, "震动", "来电时震动；关掉只亮屏。",
            CallAlertSettings.vibrateEnabled(this)
        ) { CallAlertSettings.setVibrate(this, it) }
        // 第 8 项（2026-11-20）：主动联系状态（今天几次 / 下次多久 / 等接听）。
        proactiveStateText = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@PermissionSettingsActivity, R.color.ui_text_secondary))
            textSize = 11.5f
            setPadding(0, dp(10), 0, 0)
            visibility = View.GONE
        }
        box.addView(proactiveStateText)
        // 第 4 项（2026-11-20）：vivo 的「悬浮通知 / 锁屏通知」只能在系统通知页开，
        // 以前只能靠主人自己一层层点；这里给一个直达入口。
        box.addView(TextView(this).apply {
            text = "通知与锁屏设置"
            setTextColor(ContextCompat.getColor(this@PermissionSettingsActivity, R.color.ui_accent))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundResource(R.drawable.ui_chip)
            setOnClickListener { openNotificationSettings() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        box.addView(TextView(this).apply {
            text = "锁屏要响：在系统通知里打开「悬浮通知 / 锁屏通知」。"
            setTextColor(ContextCompat.getColor(this@PermissionSettingsActivity, R.color.ui_text_secondary))
            textSize = 11f
            setPadding(0, dp(6), 0, 0)
        })
        return box
    }

    /**
     * 第 8 项（2026-11-20）：把服务端的 proactive 快照说成人话。
     * 字段读不到就用保守默认值，宁可少显示也不假装知道状态。
     */
    private fun describeProactive(p: JsonObject): String {
        val day = p["day_count"]?.jsonPrimitive?.intOrNull ?: 0
        val limit = p["day_limit"]?.jsonPrimitive?.intOrNull ?: 2
        val left = p["interval_left_h"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val retryIn = p["retry_in_h"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val pending = p["pending"]?.jsonPrimitive?.booleanOrNull ?: false
        val miss = p["miss_streak"]?.jsonPrimitive?.intOrNull ?: 0
        // 2026-11-20 去掉日上限后服务端发 day_limit=0：显示「今天 x 次」，不显示 x/0。
        val bits = mutableListOf(if (limit > 0) "今天 $day/$limit 次" else "今天 $day 次")
        when {
            pending -> bits.add("等接听")
            retryIn > 0.05 -> bits.add("下次 %.1f 小时后重试".format(retryIn))
            left > 0.05 -> bits.add("下次 %.1f 小时后".format(left))
            else -> bits.add("现在可以打")
        }
        when {
            pending -> Unit
            left > 0.05 || retryIn > 0.05 -> bits.add("冷却中")
            miss > 0 -> bits.add("上次没接")
        }
        return bits.joinToString(" · ")
    }

    /** 第 4 项（2026-11-20）：系统通知设置页；老 ROM 不支持就退到应用详情页。 */
    private fun openNotificationSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (error: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                toast("打不开系统通知设置，请手动到「设置 → 通知」里找 DSH L2D")
            }
        }
    }

    private fun addAlertSwitch(
        parent: LinearLayout,
        title: String,
        desc: String,
        initial: Boolean,
        onChange: (Boolean) -> Unit,
    ): SwitchCompat {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@PermissionSettingsActivity, R.color.ui_text))
        })
        labels.addView(TextView(this).apply {
            text = desc
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@PermissionSettingsActivity, R.color.ui_text_secondary))
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val sw = SwitchCompat(this).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> if (!rendering) onChange(checked) }
        }
        row.addView(sw, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        parent.addView(row)
        return sw
    }

    private fun render() {
        rendering = true
        holders.forEach { holder ->
            val row = holder.row
            val value = values[row.scope]
            val busy = pending.contains(row.scope)
            holder.switch?.isChecked = value as? Boolean ?: false
            holder.switch?.isEnabled = canConnect && !busy
            val label = when (val v = value) {
                is String -> row.choices?.firstOrNull { it.value == v }?.label ?: v
                is Boolean -> if (v) "已开" else "已关"
                else -> "—"
            }
            holder.value.text = label
            holder.value.isEnabled = canConnect && !busy
            holder.root.alpha = if (canConnect) 1f else 0.55f

            val bits = mutableListOf<String>()
            if (!canConnect) bits.add(noConnectReason)
            bits.add(if (busy) "同步中…" else "服务端：${describeValue(row, value)}${sourceText(row)}")
            expiryText(row)?.let { bits.add(it) }
            systemStateText(row)?.let { bits.add(it) }
            if (row.scope == "proactive.contact" && values["resident.mode"] == "call_only")
                bits.add("先把常驻形态切到「手机常驻」")
            holder.hint.text = bits.joinToString(" · ")

            val canGrant = systemReady(row) && !hasSystemPermission(row)
            holder.grant.visibility = if (canGrant) View.VISIBLE else View.GONE

            if (row.scope == "proactive.contact") {
                val alertOn = values["proactive.contact"] != false
                proactiveRingSwitch?.let {
                    it.isChecked = CallAlertSettings.ringEnabled(this)
                    it.isEnabled = alertOn
                    it.alpha = if (alertOn) 1f else 0.5f
                }
                proactiveVibrateSwitch?.let {
                    it.isChecked = CallAlertSettings.vibrateEnabled(this)
                    it.isEnabled = alertOn
                    it.alpha = if (alertOn) 1f else 0.5f
                }
                // 第 8 项：状态行；proactiveState 为 null（老服务端/还没回帧）就隐藏。
                proactiveStateText?.let { status ->
                    val state = proactiveState
                    if (state == null) {
                        status.visibility = View.GONE
                    } else {
                        status.visibility = View.VISIBLE
                        status.text = describeProactive(state)
                    }
                    status.alpha = if (alertOn) 1f else 0.5f
                }
            }
        }
        rendering = false
    }

    private fun describeValue(row: Row, value: Any?): String = when (val v = value) {
        null -> "读取中"
        is Boolean -> if (v) "开" else "关"
        is String -> row.choices?.firstOrNull { it.value == v }?.label ?: v
        else -> v.toString()
    }

    /** 方案要求每项显示「当前值 / 来源（APP or 默认）」。 */
    private fun sourceText(row: Row): String = when (sources[row.scope]) {
        null, "" -> ""
        "default" -> " · 来源：默认"
        "phone" -> " · 来源：APP"
        "owner" -> " · 来源：主人"
        else -> " · 来源：${sources[row.scope]}"
    }

    /** 有 TTL 的项（control.phone=auto）显示还剩多少分钟。 */
    private fun expiryText(row: Row): String? {
        val expiresAt = expiries[row.scope] ?: 0L
        if (expiresAt <= 0L) return null
        val leftMs = expiresAt * 1000 - System.currentTimeMillis()
        if (leftMs <= 0L) return null
        val minutes = (leftMs / 60000L).coerceAtLeast(1L)
        return "$minutes 分钟后过期"
    }

    private fun onToggle(row: Row, checked: Boolean) {
        val c = client
        if (c == null || !c.isReady) {
            toast("还没连上，稍后再试")
            render()
            return
        }
        if (checked && row.scope in setOf("observe.screen", "control.phone")) {
            confirmRisky(row) { reallySend(row, checked) }
            render()
            return
        }
        reallySend(row, checked)
    }

    private fun confirmRisky(row: Row, onOk: () -> Unit) {
        val message = if (row.scope == "observe.screen")
            "打开后，小鲸鱼能读取屏幕上显示的内容（每次通话还要系统再确认一次）。确定打开？"
        else
            "「手机控制」会让她能替你点屏幕：可能误触，也可能被游戏判定为外挂。确定继续？"
        AlertDialog.Builder(this)
            .setTitle(row.title)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ -> onOk() }
            .show()
    }

    private fun reallySend(row: Row, checked: Boolean) {
        val c = client ?: return
        pending.add(row.scope)
        values[row.scope] = checked
        render()
        val ok = if (row.choices == null) c.set(row.scope, checked) else c.revoke(row.scope)
        if (!ok) {
            pending.remove(row.scope)
            values[row.scope] = !checked
            toast("发送失败，检查连接")
            render()
        }
    }

    private fun pickChoice(row: Row) {
        val choices = row.choices ?: return
        val c = client
        if (c == null || !c.isReady) {
            toast("还没连上，稍后再试")
            return
        }
        val labels = choices.map { it.label }.toTypedArray()
        val current = choices.indexOfFirst { it.value == values[row.scope] }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(row.title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                val choice = choices[which]
                if (row.scope == "control.phone" && choice.value == "auto") {
                    confirmRisky(row) { sendChoice(row, choice) }
                } else {
                    sendChoice(row, choice)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun sendChoice(row: Row, choice: Choice) {
        val c = client ?: return
        pending.add(row.scope)
        values[row.scope] = choice.value
        render()
        if (!c.setChoice(row.scope, choice.value)) {
            pending.remove(row.scope)
            toast("发送失败，检查连接")
            render()
        }
    }

    private fun confirmAllOff() {
        AlertDialog.Builder(this)
            .setTitle("全部关闭")
            .setMessage("把 8 项能力全部关掉；之后可以逐项再打开。确定？")
            .setNegativeButton("取消", null)
            .setPositiveButton("全部关闭") { _, _ ->
                val c = client
                if (c == null || !c.isReady) {
                    toast("还没连上")
                    return@setPositiveButton
                }
                rows.forEach { row ->
                    pending.add(row.scope)
                    if (row.choices == null) c.set(row.scope, false) else {
                        val off = row.choices!!.lastOrNull { it.value == "off" }?.value
                            ?: row.choices!!.first().value
                        c.setChoice(row.scope, off)
                    }
                }
                if (values["resident.mode"] == null) values["resident.mode"] = "call_only"
                render()
            }
            .show()
    }

    private fun confirmDefaults() {
        AlertDialog.Builder(this)
            .setTitle("恢复默认")
            .setMessage("删掉所有手动改动，回到默认值：摄像头、长期记忆开，其余关。确定？")
            .setNegativeButton("取消", null)
            .setPositiveButton("恢复默认") { _, _ ->
                val c = client
                if (c == null || !c.isReady) {
                    toast("还没连上")
                    return@setPositiveButton
                }
                rows.forEach { row ->
                    pending.add(row.scope)
                    c.revoke(row.scope)
                }
                render()
            }
            .show()
    }

    private fun showAudit() {
        if (audit.isEmpty()) {
            AlertDialog.Builder(this).setTitle("变更记录").setMessage("暂时没有记录。").setPositiveButton("好", null).show()
            return
        }
        val lines = audit.takeLast(20).reversed().map { item ->
            val at = item["at"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            val time = if (at != null) DateFormat.format("MM-dd HH:mm", (at * 1000).toLong()).toString() else "--"
            val action = item["action"]?.jsonPrimitive?.contentOrNull ?: "?"
            val scope = item["scope"]?.jsonPrimitive?.contentOrNull ?: "?"
            val value = item["value"]?.let { runCatching { it.jsonPrimitive.contentOrNull ?: it.jsonPrimitive.toString() }.getOrNull() } ?: ""
            val source = item["source"]?.jsonPrimitive?.contentOrNull ?: ""
            "$time  $action  $scope = $value${if (source.isNotBlank()) "（$source）" else ""}"
        }.joinToString("\n")
        AlertDialog.Builder(this)
            .setTitle("最近的权限变更")
            .setMessage(lines)
            .setPositiveButton("好", null)
            .show()
    }

    // ---------- 系统权限 ----------

    private fun systemReady(row: Row): Boolean = row.system.isNotEmpty()

    private fun hasSystemPermission(row: Row): Boolean = when (row.system) {
        "camera" -> device.camera
        "album" -> device.album
        "screen" -> device.screen
        "notifications" -> device.notifications
        "accessibility" -> device.accessibility
        else -> true
    }

    private fun systemStateText(row: Row): String? = when (row.system) {
        "camera" -> if (device.camera) "系统：已授权" else "系统：未授权"
        "album" -> if (device.album) "系统：已授权" else "系统：未授权"
        "screen" -> if (device.screen) "系统：已授权一次" else "系统：未授权"
        "notifications" -> if (device.notifications) "系统：通知使用权已开" else "系统：通知使用权未开"
        "accessibility" -> if (device.accessibility) "系统：无障碍已开" else "系统：无障碍未开"
        else -> null
    }

    private fun refreshDevice() {
        device = DeviceState(
            camera = granted(Manifest.permission.CAMERA),
            album = granted(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE),
            screen = getLocalFlag(KEY_SCREEN_GRANTED),
            notifications = notificationListenerEnabled(),
            accessibility = accessibilityEnabled(),
            microphone = granted(Manifest.permission.RECORD_AUDIO),
        )
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun notificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        return flat.split(':').any { it.contains(packageName) }
    }

    private fun accessibilityEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return flat.split(':').any { it.contains(packageName) }
    }

    private fun requestSystem(row: Row) {
        when (row.system) {
            "camera" -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                    cameraPermission.launch(Manifest.permission.RECORD_AUDIO)
                cameraPermission.launch(Manifest.permission.CAMERA)
            }
            "album" -> albumPermission.launch(
                if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
                else Manifest.permission.READ_EXTERNAL_STORAGE
            )
            "screen" -> {
                val manager = getSystemService(MediaProjectionManager::class.java)
                if (manager == null) toast("这台设备不支持录屏") else screenCapture.launch(manager.createScreenCaptureIntent())
            }
            "notifications" -> openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "accessibility" -> openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }
    }

    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    // ---------- 本地标记与工具 ----------

    private fun setLocalFlag(key: String, value: Boolean) {
        getSharedPreferences("prts", MODE_PRIVATE).edit().putBoolean(key, value).apply()
    }

    private fun getLocalFlag(key: String): Boolean =
        getSharedPreferences("prts", MODE_PRIVATE).getBoolean(key, false)

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val KEY_SCREEN_GRANTED = "permission_screen_granted_at"
    }
}
