package com.zai.zcode.core.network

import com.zai.zcode.core.common.ZLog
import com.zai.zcode.core.protocol.rpc.ChannelClient
import com.zai.zcode.core.protocol.rpc.RpcFrameDecoder
import com.zai.zcode.core.protocol.rpc.RpcProtocolException
import com.zai.zcode.core.protocol.rpc.encodeRpcMessage
import com.zai.zcode.core.protocol.v4.ZcodeProtocolV4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "ZcodeConnection"

/**
 * 到 ZCode server 的连接（状态机所有者）：
 *
 * ```
 * Disconnected → Discovering → ConnectingWs → Handshaking → Ready
 *        ↑                                             │
 *        └────────────── (失败/断开，自动重连) ◄────────┘
 * ```
 *
 * 认证语义（对齐 packages/server）：GET /api/任意端点?token=xxx 首次访问下发 HttpOnly
 * （注意：Kotlin 块注释可嵌套，KDoc 里不能出现裸的"星杠"序列，否则注释不闭合）
 * Cookie `zcode_lite_token`，后续凭 Cookie。OkHttp CookieJar 只在内存持有 cookie，
 * token 本体由上层（EncryptedDataStore）持久化，不落本类。
 *
 * 重连恢复：重连成功后按 reducer 记账的 (logEpoch, seq) 重新订阅 —— 服务端
 * replayable 语义会补发增量；logEpoch 变化（服务端重启）时 reducer 由 base=null
 * 快照整表替换。
 */
class ZcodeConnection(
    private val scope: CoroutineScope,
    private val clientId: String,
    private val appVersion: String,
) {
    sealed class State {
        object Disconnected : State()
        data class Discovering(val serverUrl: String) : State()
        data class ConnectingWs(val serverUrl: String) : State()
        object Handshaking : State()
        data class Ready(val serverInfo: ServerInfo) : State()
        data class Failed(val reason: String, val retrying: Boolean) : State()
    }

    /** 重连策略：指数退避，封顶 30s。 */
    companion object {
        private const val RECONNECT_BASE_MS = 1_000L
        private const val RECONNECT_MAX_MS = 30_000L
        private const val HTTP_TIMEOUT_S = 10L
        private const val WS_PING_INTERVAL_S = 20L
        const val TOKEN_QUERY = "token"
        const val TOKEN_COOKIE = "zcode_lite_token"
    }

    private val _state = MutableStateFlow<State>(State.Disconnected)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Ready 后可用的 V4 门面；断开置 null。 */
    private val _protocol = MutableStateFlow<ZcodeProtocolV4?>(null)
    val protocol: StateFlow<ZcodeProtocolV4?> = _protocol.asStateFlow()

    private val _serverInfo = MutableStateFlow<ServerInfo?>(null)
    val serverInfo: StateFlow<ServerInfo?> = _serverInfo.asStateFlow()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(HTTP_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // WS 长连接：读不超时，靠 ping 探活
        .pingInterval(WS_PING_INTERVAL_S, TimeUnit.SECONDS)
        .cookieJar(InMemoryCookieJar())
        .build()

    private var connectJob: Job? = null
    private var ws: WebSocket? = null
    private var channel: ChannelClient? = null
    private var desiredUrl: String? = null
    private var desiredToken: String? = null
    private var reconnectAttempt = 0
    private val wantConnected = AtomicReference(false)
    private val lifecycleMutex = Mutex()

    /** 认证后携带 cookie 的 HTTP url 构造（server-info 等少量 REST 调用用）。 */
    suspend fun authorizedHttpGet(baseUrl: String, path: String, token: String?): JsonObject? =
        withContext(Dispatchers.IO) {
            val url = baseUrl.toHttpUrl().resolve(path)  // path 形如 /api/server-info
                // 修复依据：HttpUrl.resolve 对非法 path 返回 null，编译器要求可空处理
                ?: return@withContext null
                .newBuilder()
                .apply {
                    if (token != null) addQueryParameter(TOKEN_QUERY, token)
                }.build()
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        ZLog.w(TAG, "GET $path -> ${resp.code}")
                        return@withContext null
                    }
                    val body = resp.body?.string() ?: return@withContext null
                    runCatching { com.zai.zcode.core.common.AppJson.parseToJsonElement(body) as JsonObject }
                        .onFailure { ZLog.w(TAG, "server-info not a JSON object: ${it.message}") }
                        .getOrNull()
                }
            } catch (e: Exception) {
                ZLog.w(TAG, "GET $path failed: ${e.message}")
                null
            }
        }

    /**
     * 建立（或重建）连接。重复调用以最后一次为准。
     * 发现 + 认证 + WS + V4 握手全部完成前 state 保持在中间态。
     */
    fun connect(serverUrl: String, authToken: String?) {
        scope.launch { lifecycleMutex.withLock {
            desiredUrl = serverUrl.trimEnd('/')
            desiredToken = authToken
            wantConnected.set(true)
            reconnectAttempt = 0
            startPipeline()
        } }
    }

    fun disconnect() {
        scope.launch { lifecycleMutex.withLock {
            wantConnected.set(false)
            teardown("user disconnect")
            _state.value = State.Disconnected
        } }
    }

    /** 连接管线：discover → ws → handshake → Ready。失败按退避重连。 */
    private suspend fun startPipeline() {
        connectJob?.cancel()
        connectJob = scope.launch {
            val url = desiredUrl ?: return@launch
            val token = desiredToken
            try {
                _state.value = State.Discovering(url)
                val infoRaw = authorizedHttpGet(url, "/api/server-info", token)
                    ?: throw IllegalStateException("server-info unreachable")
                val info = ServerInfo.fromJson(infoRaw)
                _serverInfo.value = info

                _state.value = State.ConnectingWs(url)
                openWebSocket(url, token)

                _state.value = State.Handshaking
                val protocol = channel?.let { ch ->
                    ZcodeProtocolV4(scope, ch, clientId, appVersion)
                } ?: throw IllegalStateException("channel missing after ws open")
                protocol.handshake()
                _protocol.value = protocol
                _state.value = State.Ready(info)
                reconnectAttempt = 0
                ZLog.i(TAG, "connection ready: ${info.serverId} v${info.version}")
            } catch (e: Exception) {
                if (!wantConnected.get()) return@launch // 主动断开期间静默
                scheduleReconnect(e.message ?: "unknown error")
            }
        }
    }

    private suspend fun openWebSocket(url: String, token: String?) {
        // http→ws / https→wss；去掉 scheme 后拼 /ws 路径
        val scheme = if (url.startsWith("https")) "wss" else "ws"
        val rest = url.substringAfter("://")
        val httpUrl = "$scheme://$rest/ws".toHttpUrl()
        // 认证：token 走 query（服务端下发 cookie）；已有 cookie 由 CookieJar 自动带上
        val requestUrl = if (token != null) {
            httpUrl.newBuilder().addQueryParameter(TOKEN_QUERY, token).build()
        } else httpUrl

        val wsChannel = ChannelClient(scope)
        channel = wsChannel
        val decoder = RpcFrameDecoder()

        wsChannel.onSend = { frame ->
            ws?.send(frame.toByteString())
        }

        val request = Request.Builder().url(requestUrl).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val messages = try {
                    decoder.feed(bytes.toByteArray())
                } catch (e: RpcProtocolException) {
                    ZLog.e(TAG, "protocol violation, closing: ${e.message}")
                    webSocket.close(1002, "bad frame")
                    return
                }
                for (m in messages) wsChannel.onMessage(m)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ZLog.w(TAG, "ws failure: ${t.message}")
                wsChannel.close()
                // WS 回调在 OkHttp 线程非协程上下文：重连含 delay，转投 scope 串行处理
                scope.launch { maybeReconnect(t.message ?: "ws failure") }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ZLog.i(TAG, "ws closed: $code $reason")
                wsChannel.close()
                scope.launch { maybeReconnect("ws closed $code") }
            }
        })
    }

    private suspend fun maybeReconnect(reason: String) {
        if (!wantConnected.get()) {
            teardown(reason)
            _state.value = State.Disconnected
            return
        }
        scheduleReconnect(reason)
    }

    private suspend fun scheduleReconnect(reason: String) {
        teardown(reason)
        val delayMs = minOf(RECONNECT_BASE_MS * (1L shl minOf(reconnectAttempt, 5)), RECONNECT_MAX_MS)
        reconnectAttempt++
        _state.value = State.Failed(reason, retrying = true)
        ZLog.i(TAG, "reconnect in ${delayMs}ms (attempt $reconnectAttempt): $reason")
        kotlinx.coroutines.delay(delayMs)
        if (wantConnected.get()) startPipeline()
    }

    private fun teardown(reason: String) {
        _protocol.value?.close()
        _protocol.value = null
        channel?.close()
        channel = null
        ws?.close(1000, reason)
        ws = null
    }

    /** 会话/工作区选择变化时调用：workspaceTarget 决定事件 fan-out 范围。 */
    fun attachFrameListener(workspacePath: String, workspaceIdentity: String? = null) {
        _protocol.value?.attachFrameListener(workspacePath, workspaceIdentity)
    }
}

/** 会话内存 CookieJar：只为认证 cookie 服务，不持久化（AGENTS.md：不落凭据到非加密存储）。 */
private class InMemoryCookieJar : CookieJar {
    private val cookies = java.util.concurrent.ConcurrentHashMap<String, List<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isNotEmpty()) this.cookies[url.host] = cookies
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies[url.host] ?: emptyList()
}
