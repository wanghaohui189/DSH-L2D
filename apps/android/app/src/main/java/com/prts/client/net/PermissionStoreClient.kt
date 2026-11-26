package com.prts.client.net

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 「权限」页的独立中继连接：手机 → relay → AstrBot 插件 → PermissionStore。
 *
 * 协议（见 reports/L2D-PERMISSIONS-APP-2026-11-13.md）：
 *   手机 → {"type":"permission-query"}
 *   手机 → {"type":"permission-set","scope":...,"value":...,"request_id":...}
 *           value 为 null 表示撤销（删回默认）
 *   插件 → {"type":"permission-state","request_id":...,"snapshot":{...},"audit":[...],
 *           "proactive":{"day_count":..,"day_limit":..,"interval_left_h":..,"pending":..}}
 *   插件 → {"type":"permission-error","request_id":...,"error":"..."}
 *
 * 单独一条 socket，不占用通话连接；页面退出即 close()。
 */
class PermissionStoreClient(
    url: String,
    token: String,
    private val onState: (String) -> Unit,
    private val onSnapshot: (snapshot: JsonObject, detail: JsonObject, audit: List<JsonObject>,
                             proactive: JsonObject?) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val ws = WsClient(url, ::onFrame, token) { onState(it) }
    private val sequence = AtomicInteger(0)

    val isReady: Boolean get() = ws.isReady

    fun connect() = ws.open()

    fun close() = ws.close()

    fun query(): Boolean = raw(buildJsonObject {
        put("type", "permission-query")
        put("request_id", nextId())
    })

    fun set(scope: String, value: Boolean): Boolean = raw(buildJsonObject {
        put("type", "permission-set")
        put("scope", scope)
        put("value", value)
        put("request_id", nextId())
    })

    fun setChoice(scope: String, value: String): Boolean = raw(buildJsonObject {
        put("type", "permission-set")
        put("scope", scope)
        put("value", value)
        put("request_id", nextId())
    })

    fun revoke(scope: String): Boolean = raw(buildJsonObject {
        put("type", "permission-set")
        put("scope", scope)
        put("value", JsonNull)
        put("request_id", nextId())
    })

    private fun nextId(): String = "p-${sequence.incrementAndGet()}"

    private fun raw(frame: JsonObject): Boolean {
        if (!ws.isReady) return false
        return ws.sendRaw(frame.toString())
    }

    private fun onFrame(text: String) {
        val obj = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (_: Exception) {
            return
        }
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "permission-state" -> {
                val snapshot = runCatching { obj.getValue("snapshot").jsonObject }.getOrNull() ?: return
                val detail = runCatching { obj.getValue("detail").jsonObject }.getOrNull() ?: JsonObject(emptyMap())
                val audit = runCatching {
                    obj.getValue("audit").jsonArray.mapNotNull { it as? JsonObject }
                }.getOrDefault(emptyList())
                // 第 8 项（2026-11-20）：主动联系状态（今天几次 / 下次多久 / 等接听）。
                // 老服务端没有这个字段 → null，权限页就不显示，不影响其他功能。
                val proactive = runCatching { obj.getValue("proactive").jsonObject }.getOrNull()
                Log.i("PRTS-Permission", "state scopes=${snapshot.size} audit=${audit.size} proactive=${proactive != null}")
                onSnapshot(snapshot, detail, audit, proactive)
            }
            "permission-error" -> {
                val message = obj["error"]?.jsonPrimitive?.contentOrNull ?: "权限更新失败"
                Log.i("PRTS-Permission", "error $message")
                onError(message)
            }
        }
    }
}
