package com.prts.client.net

import java.net.URI

/**
 * 2026-11-26：远端地址一律走 TLS。
 *
 * 用户在「连接设置 / 语音 API」里可能还填着 http://host；这里在真正发请求/建
 * WebSocket 前把远端 http 升成 https（wss），本机回环与模拟器地址保持明文，
 * 因为 Android 的 network_security_config 只为 localhost 开了 cleartext。
 */
object SecureUrl {
    private val LOOPBACK = setOf("127.0.0.1", "localhost", "::1", "10.0.2.2")

    fun base(raw: String): String {
        val e = raw.trim()
        if (!e.startsWith("http://", ignoreCase = true)) return e
        val host = try { URI(e).host } catch (_: Exception) { return e }
        if (host.isNullOrBlank() || LOOPBACK.contains(host.lowercase())) return e
        return "https://" + e.substring("http://".length)
    }

    fun websocket(raw: String): String {
        val b = base(raw)
        return when {
            b.startsWith("https://", ignoreCase = true) -> "wss://" + b.substring("https://".length)
            b.startsWith("http://", ignoreCase = true) -> "ws://" + b.substring("http://".length)
            else -> b
        }
    }
}
