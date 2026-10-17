package com.zai.zcode.core.network

import com.zai.zcode.core.common.AppJson
import com.zai.zcode.core.common.ZLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val TAG = "RelayClient"

/**
 * 配对证明（对齐 zemote proof.dart，web 客户端 aen()/ien() 语义）：
 * proof = base64url_nopad(HMAC-SHA256(key: utf8(passHash), msg: utf8("$nonce|$role|$sid")))
 *
 * passHash 是 HMAC key material，只存在于本函数与参数持有方，绝不写日志。
 */
internal fun calculateRelayProof(passHash: String, nonce: String, role: String, deviceSid: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(passHash.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    val digest = mac.doFinal("$nonce|$role|$deviceSid".toByteArray(Charsets.UTF_8))
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

/** relay close code → 语义（对齐 web 客户端 VC()）。 */
internal fun relayCloseReason(code: Int): String? = when (code) {
    4004 -> "session-not-found"
    4009 -> "session-conflict"
    4010 -> "desktop-disconnected"
    4011 -> "session-expired"
    4012 -> "workspace-closed"
    4013 -> "invalid-mobile-connection"
    else -> null
}

/**
 * Relay 终端 socket 客户端（对齐 zemote relay_client.dart，web 客户端 pen 类语义）。
 *
 * 状态机：
 * ```
 * Connecting → Authenticating → Waiting(等桌面扫码确认配对) → Paired
 *      ↑                              │
 *      └────(断线/心跳超时, 自动重连) ◄─┘
 * ```
 *
 * 协议（JSON 文本帧，wss://<host>/ws）：
 * - 出:auth_init{role:terminal, device_sid} → 收 auth_challenge{nonce}
 * - 出:auth_response{proof} → 收 pair_status_ack{pair_status: waiting|matched}
 * - Paired/Waiting 期间每 10s 发 pair_status_query 心跳；30s 无 ack 先探测一次再重连
 * - data 帧 payload 双向转发；未 Paired 期间出站排队(≤100)配对后 flush
 * - close 4010(桌面断开)或曾配对过 → 重连；其余按错误终止
 * - error 帧 code=KICKED：detail 含 conflict/duplicate/already connected/another
 *   connection 时自动重连一次，否则 kicked 终态(被另一端挤下线)
 */
class RelayClient(
    private val scope: CoroutineScope,
    private val params: RelayConnectionParams,
    private val appVersion: String,
) {
    enum class State { Connecting, Authenticating, Waiting, Paired, Reconnecting, Error, Kicked, Closed }

    data class Failure(val reason: String, val message: String?)

    private val _state = MutableStateFlow(State.Connecting)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 桌面转发来的业务 payload（data 帧内层，zcode_type: ...）。 */
    private val _payloads = MutableSharedFlow<JsonObject>(
        replay = 0, extraBufferCapacity = 1024,
    )
    val payloads: SharedFlow<JsonObject> = _payloads.asSharedFlow()

    private val _failures = MutableSharedFlow<Failure>(replay = 0, extraBufferCapacity = 16)
    val failures: SharedFlow<Failure> = _failures.asSharedFlow()

    /** 等 Paired（挂起点；配对完成或抛异常）。 */
    suspend fun awaitPaired(timeoutMs: Long = 60_000) {
        withTimeout(timeoutMs) {
            state.first { it == State.Paired || it == State.Error || it == State.Kicked || it == State.Closed }
        }
        when (val s = state.value) {
            State.Error -> throw IllegalStateException("relay error")
            State.Kicked -> throw IllegalStateException("已被其他设备接管连接")
            State.Closed -> throw IllegalStateException("relay closed")
            else -> Unit
        }
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS) // WS 长连接
        .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private val wantConnected = AtomicBoolean(false)
    private val disposed = AtomicBoolean(false)
    private val intentionalClose = AtomicBoolean(false)
    private val wasPaired = AtomicBoolean(false)
    private val kickRecoveryAttempted = AtomicBoolean(false)
    private val staleProbeSent = AtomicBoolean(false)
    private val lastInboundAt = AtomicReference(System.currentTimeMillis())
    private val lastPairStatusAckAt = AtomicReference(System.currentTimeMillis())
    private val lifecycleMutex = Mutex()
    private var heartbeatJob: Job? = null
    private var rewaitJob: Job? = null
    private var reconnectAttempt = 0

    // 未配对期间的出站队列：重连中发出的请求在 paired 后 flush，避免请求消失导致上层挂死
    private val outboundQueue = ArrayDeque<JsonObject>()

    fun start() {
        scope.launch { lifecycleMutex.withLock {
            disposed.set(false)
            intentionalClose.set(false)
            wasPaired.set(false)
            kickRecoveryAttempted.set(false)
            reconnectAttempt = 0
            wantConnected.set(true)
            connectSocket()
        } }
    }

    fun stop() {
        scope.launch { lifecycleMutex.withLock {
            wantConnected.set(false)
            disposed.set(true)
            intentionalClose.set(true)
            stopHeartbeat()
            rewaitJob?.cancel()
            ws?.close(1000, "user disconnect")
            ws = null
            _state.value = State.Closed
        } }
    }

    /** 发送一个业务 payload（data 帧内层）。未 Paired 时排队（≤100）。 */
    fun sendPayload(payload: JsonObject) {
        if (!wantConnected.get()) return
        if (_state.value != State.Paired || ws == null) {
            synchronized(outboundQueue) {
                if (outboundQueue.size < 100) outboundQueue.addLast(payload)
            }
            ZLog.d(TAG, "queued payload (state=${_state.value})")
            return
        }
        sendData(payload)
    }

    private fun sendData(payload: JsonObject) {
        val frame = relayDataPayload(payload, System.currentTimeMillis())
        ws?.send(AppJson.encodeToString(JsonObject.serializer(), frame))
    }

    private fun flushOutboundQueue() {
        val queued = synchronized(outboundQueue) {
            if (outboundQueue.isEmpty()) return
            val copy = ArrayDeque(outboundQueue)
            outboundQueue.clear()
            copy
        }
        ZLog.i(TAG, "flushing ${queued.size} queued payload(s)")
        for (p in queued) sendData(p)
    }

    private fun sendFrame(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        val frame = buildJsonObject(block)
        ws?.send(AppJson.encodeToString(JsonObject.serializer(), frame))
    }

    private fun connectSocket() {
        intentionalClose.set(false)
        staleProbeSent.set(false)
        lastPairStatusAckAt.set(System.currentTimeMillis())
        lastInboundAt.set(System.currentTimeMillis())
        _state.value = if (wasPaired.get()) State.Reconnecting else State.Connecting
        val request = Request.Builder().url(params.relayWsUrl().toString()).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _state.value = State.Authenticating
                sendFrame {
                    put("type", "auth_init")
                    put("role", "terminal")
                    put("device_sid", params.deviceSid)
                    put("meta", buildJsonObject {
                        put("platform", "android")
                        put("version", appVersion)
                        put("name", params.deviceName ?: "ZCode Android")
                    })
                    put("client_ts", System.currentTimeMillis())
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastInboundAt.set(System.currentTimeMillis())
                handleText(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handleClosed(1006, t.message)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handleClosed(code, reason)
            }
        })
    }

    private fun handleText(text: String) {
        val frame = runCatching {
            AppJson.parseToJsonElement(text).jsonObject
        }.getOrNull() ?: return
        when (frame["type"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }) {
            "auth_challenge" -> {
                val nonce = frame["nonce"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: ""
                // 凭据红线：proof 计算细节与 hash 值均不落日志
                sendFrame {
                    put("type", "auth_response")
                    put("device_sid", params.deviceSid)
                    put("proof", calculateRelayProof(params.passHash, nonce, "terminal", params.deviceSid))
                    put("client_ts", System.currentTimeMillis())
                }
            }
            "auth_ack", "pair_status_ack" -> {
                applyPairStatus(frame["pair_status"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content })
            }
            "data" -> {
                val payload = frame["payload"] as? JsonObject ?: return
                _payloads.tryEmit(payload)
            }
            "error" -> {
                val code = frame["code"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                val message = frame["message"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                handleRelayError(code, message)
            }
        }
    }

    private fun applyPairStatus(status: String?) {
        lastPairStatusAckAt.set(System.currentTimeMillis())
        staleProbeSent.set(false)
        when (status) {
            "waiting" -> {
                _state.value = State.Waiting
                startHeartbeat()
                if (wasPaired.get()) {
                    // 已配对过的重连应立即 matched；卡在 waiting 超时强制重连
                    rewaitJob?.cancel()
                    rewaitJob = scope.launch {
                        delay(20_000)
                        if (wantConnected.get() && _state.value == State.Waiting && wasPaired.get()) {
                            ZLog.i(TAG, "re-pair stuck in waiting, reconnecting")
                            reconnect()
                        }
                    }
                }
            }
            "matched" -> {
                rewaitJob?.cancel()
                reconnectAttempt = 0
                kickRecoveryAttempted.set(false)
                _state.value = State.Paired
                wasPaired.set(true)
                startHeartbeat()
                flushOutboundQueue()
                ZLog.i(TAG, "relay paired with desktop")
            }
        }
    }

    private fun handleRelayError(code: String?, message: String?) {
        ZLog.w(TAG, "relay error frame: code=$code")
        if (code == "KICKED") {
            val detail = (message ?: "").lowercase()
            val transient = detail.contains("conflict") || detail.contains("duplicate") ||
                detail.contains("already connected") || detail.contains("another connection")
            if (!kickRecoveryAttempted.get() &&
                (_state.value == State.Authenticating || transient)
            ) {
                kickRecoveryAttempted.set(true)
                _failures.tryEmit(Failure("session-conflict", message))
                reconnect()
                return
            }
            _state.value = State.Kicked
            intentionalClose.set(true)
            _failures.tryEmit(Failure("kicked", message))
            ws?.close(1000, "kicked")
        }
    }

    private fun handleClosed(code: Int, reason: String?) {
        stopHeartbeat()
        rewaitJob?.cancel()
        ZLog.w(TAG, "relay closed code=$code mapped=${relayCloseReason(code)}")
        if (intentionalClose.get() || disposed.get()) return
        if (!wantConnected.get()) return
        // 曾配对过或桌面端断开(4010)都是可恢复的，走重连；否则配对失败终止
        if (wasPaired.get() || code == 4010) {
            scheduleReconnect()
            return
        }
        _state.value = State.Error
        val mapped = relayCloseReason(code)
        _failures.tryEmit(
            Failure(mapped ?: "relay-unavailable", reason ?: "connection closed ($code)")
        )
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            var tick = 0
            while (wantConnected.get()) {
                delay(10_000)
                val s = _state.value
                if (s != State.Paired && s != State.Waiting) continue
                tick++
                if (s == State.Waiting && tick % 2 == 1) continue
                if (System.currentTimeMillis() - lastPairStatusAckAt.get() > 30_000) {
                    // 心跳 30s 无 ack：先探测一次，仍无响应则重建 socket
                    if (!staleProbeSent.compareAndSet(false, true)) {
                        staleProbeSent.set(false)
                        ZLog.w(TAG, "heartbeat ack timeout, reconnecting")
                        reconnect()
                        continue
                    }
                    ZLog.w(TAG, "heartbeat stale, probing")
                } else {
                    staleProbeSent.set(false)
                }
                sendFrame {
                    put("type", "pair_status_query")
                    put("device_sid", params.deviceSid)
                    put("client_ts", System.currentTimeMillis())
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /** 回到前台等场景的活性探测：链路陈旧直接重连。 */
    fun poke() {
        if (!wantConnected.get() || intentionalClose.get()) return
        when (_state.value) {
            State.Paired -> {
                if (System.currentTimeMillis() - lastInboundAt.get() > 25_000) {
                    ZLog.w(TAG, "poke: stale inbound link, reconnecting")
                    reconnect()
                } else {
                    sendFrame {
                        put("type", "pair_status_query")
                        put("device_sid", params.deviceSid)
                        put("client_ts", System.currentTimeMillis())
                    }
                }
            }
            State.Closed, State.Kicked, State.Error -> Unit
            else -> scope.launch { reconnect() }
        }
    }

    private fun reconnect() {
        scope.launch { lifecycleMutex.withLock {
            if (!wantConnected.get() || intentionalClose.get()) return@withLock
            _state.value = State.Reconnecting
            stopHeartbeat()
            ws?.close(1000, "reconnect")
            ws = null
            connectSocket()
        } }
    }

    private fun scheduleReconnect() {
        scope.launch {
            _state.value = State.Reconnecting
            val delayMs = (1_000L shl reconnectAttempt.coerceAtMost(4)).coerceIn(1_000, 15_000)
            reconnectAttempt++
            ZLog.i(TAG, "relay reconnect in ${delayMs}ms (attempt $reconnectAttempt)")
            delay(delayMs)
            if (wantConnected.get() && !intentionalClose.get()) {
                lifecycleMutex.withLock {
                    if (wantConnected.get()) connectSocket()
                }
            }
        }
    }
}
