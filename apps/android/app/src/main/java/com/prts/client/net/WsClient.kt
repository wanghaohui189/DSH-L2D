package com.prts.client.net

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

class WsClient(
    private val url: String,
    private val onEvent: (String) -> Unit,     // raw json line
    private val accessToken: String = "",
    private val onState: (String) -> Unit,     // "connecting" / "open" / "closed" / "retry n"
) {
    private val local by lazy {
        if (url == com.prts.client.localdsh.LocalDshBridge.SOCKET_URL)
            com.prts.client.localdsh.LocalDshClient(accessToken, onEvent) { state ->
                isReady = state == "open"; onState(state)
            } else null
    }
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private val generation = AtomicInteger(0)
    private val encoder = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var wantOpen = false
    @Volatile var isReady = false
    private var retries = 0
    private var closed = false

    /** A socket is on its way up: callers must not tear it down just because it is not ready yet. */
    val isConnecting: Boolean get() = wantOpen && !closed && !isReady

    fun open() {
        if (local != null) { if (!closed) local?.open(); return }
        if (closed || wantOpen) return
        wantOpen = true
        retries = 0
        connect()
    }

    private fun valid(ticket: Int) = wantOpen && !closed && ticket == generation.get()
    private fun post(ticket: Int, block: () -> Unit) {
        handler.post { if (valid(ticket)) block() }
    }

    private fun connect() {
        if (!wantOpen || closed) return
        val ticket = generation.incrementAndGet()
        isReady = false
        onState("connecting")
        val req = try { Request.Builder().url(url).apply {
            if (accessToken.isNotBlank()) header("Authorization", "Bearer $accessToken")
        }.build() } catch (_: Exception) {
            wantOpen = false
            onState("invalid-url")
            return
        }
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!valid(ticket)) { webSocket.close(1000, "closed"); return }
                ws = webSocket
                isReady = true
                post(ticket) { onState("open") }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                post(ticket) { onEvent(text) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i("PRTS-Ws", "onClosed code=$code reason=$reason")
                post(ticket) { disconnected(ticket) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.i("PRTS-Ws", "onFailure ${t.javaClass.simpleName}: ${t.message} response=${response?.code}")
                if (response?.code in listOf(401, 403)) {
                    handler.post {
                        if (!closed && ticket == generation.get()) {
                            wantOpen = false
                            isReady = false
                            onState("unauthorized")
                        }
                    }
                    return
                }
                post(ticket) { disconnected(ticket) }
            }
        })
    }

    private fun disconnected(ticket: Int) {
        if (!valid(ticket)) return
        Log.i("PRTS-Ws", "disconnected retries=$retries")
        generation.incrementAndGet()
        isReady = false
        ws = null
        if (++retries > MAX_RETRIES) {
            wantOpen = false
            onState("failed")
            return
        }
        onState("retry $retries/$MAX_RETRIES")
        handler.postDelayed({ if (wantOpen && !closed) connect() }, 1500L * retries)
    }

    fun sendText(text: String, face: FaceObservationMetadata? = null, replyLanguage: String = "zh") =
        sendRaw(Proto.json.encodeToString(OutText.serializer(), OutText(text = text, face_observation = face,
            reply_language = locale(replyLanguage), bilingual_captions = bilingual(replyLanguage))))

    fun sendProactiveText(text: String, replyLanguage: String): Boolean = local != null && sendRaw(buildJsonObject {
        put("type", "proactive-text-input")
        put("text", text)
        put("reply_language", locale(replyLanguage))
    }.toString())

    fun sendRemoteProactive(id: String, faceHint: String?, language: String): Boolean =
        local == null && sendRaw(buildJsonObject {
            put("type", "mobile-proactive-input")
            put("proactive_id", id)
            put("reply_language", locale(language))
            if (faceHint != null) put("face_hint", faceHint)
        }.toString())

    fun sendRemoteProactiveCancel(id: String): Boolean = local == null && sendRaw(buildJsonObject {
        put("type", "mobile-proactive-cancel")
        put("proactive_id", id)
    }.toString())

    fun configureVoice(local: Boolean) = sendRaw(buildJsonObject {
        put("type", "set-voice-profile"); put("mode", if (local) "client-local" else "server")
    }.toString())

    fun sendLiveFrame(jpeg: ByteArray): Boolean {
        if (local != null || jpeg.isEmpty() || jpeg.size > 120 * 1024) return false
        val ticket = generation.get()
        val socket = ws
        if (!isReady || socket == null || !valid(ticket)) return false
        return encoder.submit {
            val raw = buildJsonObject {
                put("type", "live-frame")
                put("image", android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP))
                put("at", System.currentTimeMillis())
            }.toString()
            if (valid(ticket) && isReady) socket.send(raw)
        } != null
    }

    /** observe.album：通话中把「最近一张照片」的缩略图报给中继（服务端还要过权限门）。 */
    fun sendAlbumPhoto(jpeg: ByteArray, at: Long, name: String): Boolean {
        if (local != null || jpeg.isEmpty() || jpeg.size > 400 * 1024) return false
        val ticket = generation.get()
        val socket = ws
        if (!isReady || socket == null || !valid(ticket)) return false
        return encoder.submit {
            val raw = buildJsonObject {
                put("type", "album")
                put("image", android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP))
                put("at", at)
                put("name", name.take(60))
            }.toString()
            if (valid(ticket) && isReady) socket.send(raw)
        } != null
    }

    /** observe.screen：按需截的一帧屏幕（每帧主人自己点过一次系统录屏授权）。 */
    fun sendScreenFrame(jpeg: ByteArray, at: Long, label: String): Boolean {
        if (local != null || jpeg.isEmpty() || jpeg.size > 600 * 1024) return false
        val ticket = generation.get()
        val socket = ws
        if (!isReady || socket == null || !valid(ticket)) return false
        return encoder.submit {
            val raw = buildJsonObject {
                put("type", "screen-frame")
                put("image", android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP))
                put("at", at)
                put("label", label.take(40))
            }.toString()
            if (valid(ticket) && isReady) socket.send(raw)
        } != null
    }

    fun sendAllowGuest(enabled: Boolean): Boolean = sendRaw(buildJsonObject {
        put("type", "allow-guest"); put("enabled", enabled)
    }.toString())

    fun sendImage(jpeg: ByteArray, text: String, face: FaceObservationMetadata? = null, replyLanguage: String = "zh", onResult: (Boolean) -> Unit) {
        if (local != null) { handler.post { onResult(false) }; return }
        require(jpeg.size <= 700 * 1024 && text.length <= 500)
        val ticket = generation.get()
        val socket = ws
        if (!isReady || socket == null || !valid(ticket)) { onResult(false); return }
        encoder.execute {
            val raw = buildJsonObject {
                put("type", "image-input"); put("mime_type", "image/jpeg")
                put("image", android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP))
                put("text", text)
                put("reply_language", locale(replyLanguage))
                put("bilingual_captions", bilingual(replyLanguage))
                if (face != null) put("face_observation", Proto.json.encodeToJsonElement(FaceObservationMetadata.serializer(), face))
            }.toString()
            val ok = valid(ticket) && isReady && socket.send(raw)
            handler.post { if (valid(ticket)) onResult(ok) }
        }
    }

    fun configureProvider(profile: ConnectionProfile, replyLanguage: String = "zh"): Boolean = sendRaw(buildJsonObject {
        put("type", "configure-mobile-provider")
        put("reply_language", locale(replyLanguage))
        put("bilingual_captions", bilingual(replyLanguage))
        put("capabilities", JsonArray(listOf(JsonPrimitive("emotion-update-v1"), JsonPrimitive("mobile-proactive-v1"))))
        put("config", buildJsonObject {
            put("mode", if (profile.isPaired) "paired" else profile.mode.name.lowercase())
            put("endpoint", if (profile.isPaired) "http://127.0.0.1:12395" else profile.endpoint)
            put("key", profile.apiKey)
            put("model", profile.model)
            put("prompt", profile.prompt)
            put("profile_id", profile.profileId)
        })
    }.toString())

    fun sendControl(text: String) {
        sendRaw(Proto.json.encodeToString(OutControl.serializer(), OutControl(text = text)))
    }

    /** 把自己当前的位置报给中继（天气类问题用；中继拿不到就退回 IP 反查）。 */
    fun sendLocation(lat: Double, lon: Double, accuracy: Float): Boolean = sendRaw(buildJsonObject {
        put("type", "phone-location")
        put("lat", lat)
        put("lon", lon)
        put("accuracy", accuracy.toDouble())
        put("at", System.currentTimeMillis())
    }.toString())

    fun sendRaw(raw: String): Boolean = if (local != null) !closed && local?.send(raw) == true else isReady && wantOpen && ws?.send(raw) == true
    fun sendInterrupt(text: String = "") = sendRaw(Proto.json.encodeToString(OutInterrupt.serializer(), OutInterrupt(text = text)))
    fun sendSignal(type: String) = sendRaw(Proto.json.encodeToString(OutSignal.serializer(), OutSignal(type)))
    fun switchConfig(file: String) = sendRaw(Proto.json.encodeToString(OutSwitchConfig.serializer(), OutSwitchConfig(file = file)))
    fun playbackStarted(display: DisplayText?) = sendRaw(Proto.json.encodeToString(OutPlayStart.serializer(), OutPlayStart(display_text = display)))

    fun sendMic(samples: FloatArray, faceAtEnd: () -> FaceObservationMetadata? = { null }, replyLanguage: String = "zh", onResult: (Boolean) -> Unit) {
        val localTarget = local
        if (localTarget != null) {
            val ticket = localTarget.beginMic()
            encoder.execute {
                val ok = runCatching {
                    if (closed || !localTarget.micCurrent(ticket)) false else {
                        val wav = com.prts.client.localdsh.LocalDshPcmWav.encode(samples)
                        val base64 = android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP)
                        !closed && localTarget.micCurrent(ticket) &&
                            localTarget.transcribe(base64, samples.size, locale(replyLanguage), ticket, faceAtEnd())
                    }
                }.getOrDefault(false)
                handler.post { if (!closed && localTarget.micCurrent(ticket)) onResult(ok) }
            }
            return
        }
        val ticket = generation.get()
        val socket = ws
        if (!isReady || socket == null || !valid(ticket)) { onResult(false); return }
        encoder.execute {
            var ok = true
            var offset = 0
            while (offset < samples.size && ok) {
                val end = (offset + 8000).coerceAtMost(samples.size)
                ok = valid(ticket) && isReady && socket.send(Proto.json.encodeToString(
                    OutMicAudio.serializer(), OutMicAudio(audio = samples.copyOfRange(offset, end).asList())))
                offset = end
            }
            // Read freshness at the user-submit marker, not before encoding audio chunks.
            ok = ok && valid(ticket) && socket.send(Proto.json.encodeToString(
                OutMicEnd.serializer(), OutMicEnd(face_observation = faceAtEnd(), reply_language = locale(replyLanguage),
                    bilingual_captions = bilingual(replyLanguage))))
            val result = ok
            handler.post {
                if (!valid(ticket)) return@post
                if (!result) socket.cancel()
                onResult(result)
            }
        }
    }

    fun close() {
        if (closed) return
        Log.i("PRTS-Ws", "close() thread=${Thread.currentThread().name} from=" +
            Throwable().stackTrace.drop(1).take(5).joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" })
        closed = true
        local?.close()
        wantOpen = false
        isReady = false
        generation.incrementAndGet()
        handler.removeCallbacksAndMessages(null)
        ws?.close(1000, "bye")
        ws = null
        encoder.shutdownNow()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }

    companion object {
        private const val MAX_RETRIES = 3
        private fun locale(value: String) = value.takeIf { it in setOf("ja", "en") } ?: "zh"
        private fun bilingual(value: String) = value in setOf("ja", "en")
    }
}

