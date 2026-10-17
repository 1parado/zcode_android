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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
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
        // 等 Initialize 帧的超时（P0-C）：正常几百毫秒内到达，半开连接靠此兜底
        private const val WS_IDLE_TIMEOUT_MS = 15_000L
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

    /**
     * 当前 workspace 的单一 owner（评审 P2-5：原先 ChatViewModel/SessionsViewModel 各自
     * 从 serverInfo.workspaces.firstOrNull() 推导，重复三处）。Ready 时派生第一个
     * workspace；feature 只消费，不再自行推导。
     */
    private val _currentWorkspace = MutableStateFlow<ServerInfo.Workspace?>(null)
    val currentWorkspace: StateFlow<ServerInfo.Workspace?> = _currentWorkspace.asStateFlow()

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
    private var desiredRelay: RelayConnectionParams? = null
    private var relayClient: RelayClient? = null
    private var relayPayloadJob: Job? = null
    private var frameTransport: RpcFrameTransport? = null
    private var reconnectAttempt = 0
    private val wantConnected = AtomicReference(false)
    private val lifecycleMutex = Mutex()

    /** 认证后携带 cookie 的 HTTP url 构造（server-info 等少量 REST 调用用）。 */
    suspend fun authorizedHttpGet(baseUrl: String, path: String, token: String?): JsonObject? =
        withContext(Dispatchers.IO) {
            // 修复依据：HttpUrl.resolve 对非法 path 返回 null，须先判空再接链式构造
            val resolved = baseUrl.toHttpUrl().resolve(path) ?: return@withContext null
            val url = resolved.newBuilder().apply {
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
     *
     * relay 模式：url 是桌面端网页远控二维码（zcode.z.ai/remote/v4?sid=&hash=&t=...）时，
     * 直接走 relay 配对 + workspace bridge，无需局域网 server（用户报障"扫桌面二维码
     * 链接失败"的正解——桌面二维码本就是 relay 链接）。
     */
    fun connect(serverUrl: String, authToken: String?) {
        scope.launch { lifecycleMutex.withLock {
            val trimmed = serverUrl.trim()
            val relay = RelayConnectionParams.parse(trimmed)
            desiredRelay = relay
            desiredUrl = trimmed.trimEnd('/')
            desiredToken = authToken
            wantConnected.set(true)
            reconnectAttempt = 0
            if (relay != null) startRelayPipeline() else startPipeline()
        } }
    }

    fun disconnect() {
        scope.launch { lifecycleMutex.withLock {
            wantConnected.set(false)
            teardown("user disconnect")
            _state.value = State.Disconnected
        } }
    }

    // ── relay 模式管线：pair → bootstrap → bridge-open → V4 握手 ──

    private suspend fun startRelayPipeline() {
        connectJob?.cancel()
        connectJob = scope.launch {
            val relay = desiredRelay ?: return@launch
            try {
                teardownRelay()
                _state.value = State.Discovering(relay.host)
                val rClient = RelayClient(scope, relay, appVersion)
                relayClient = rClient

                // 转发 relay payload：bootstrap/bridge 控制面在此处理，rpc-frame 交给 transport
                relayPayloadJob = scope.launch {
                    rClient.payloads.collect { payload -> handleRelayPayload(payload) }
                }

                _state.value = State.ConnectingWs(relay.host)
                rClient.start()
                rClient.awaitPaired()
                _state.value = State.Handshaking

                // bootstrap：工作区总览替代 /api/server-info（relay 模式无 REST 端点）
                val bootRequest = buildJsonObject {
                    put("zcode_type", "bootstrap-request")
                    put("requestId", "bootstrap-" + newRequestId())
                }
                // 修复依据(run 39020514047 CI):relayRequest 的尾随 lambda 会绑定到
                // 最后一个参数 timeoutMs(Long),matcher 必须用命名参数显式传递
                val boot = relayRequest(bootRequest, match = { p, sent ->
                    p.zcodeType() == "bootstrap-response" &&
                        p["requestId"]?.jsonPrimitive?.contentOrNull ==
                            sent["requestId"]?.jsonPrimitive?.contentOrNull
                })
                val info = relayServerInfoFromBootstrap(boot["result"] as? JsonObject)
                _serverInfo.value = info

                // 对第一个 workspace 开 bridge（与 web 移动端流程一致：单工作区自动打开）
                val ws0 = info.workspaces.firstOrNull()
                    ?: throw IllegalStateException("bootstrap 无可用工作区")
                openBridge(ws0)

                _state.value = State.Ready(info)
                _currentWorkspace.value = info.workspaces.firstOrNull()
                reconnectAttempt = 0
                ZLog.i(TAG, "relay connection ready: ${info.workspaces.size} workspace(s)")
            } catch (e: Exception) {
                if (!wantConnected.get()) return@launch // 主动断开期间静默
                scheduleReconnect(e.message ?: "relay error")
            }
        }
    }

    /** bridge 生命周期 + V4 会话（relay 模式独有持有）。 */
    private class RelayBridge(
        val bridge: JsonObject,
        val transport: RpcFrameTransport,
        val channel: ChannelClient,
        val protocol: ZcodeProtocolV4,
    )

    @Volatile private var relayBridge: RelayBridge? = null
    private var bridgeGeneration = 0
    private val relayPending = java.util.concurrent.ConcurrentHashMap<String, PendingRequest>()

    /**
     * relay bootstrap 给出的 workspaceIdentity（AGENTS.md：identity 用于身份隔离，
     * path 用于文件操作；bootstrap 只发 key 时两者同值，显式 identity 时 V4 scope
     * 必须带 workspaceIdentity 才能命中正确的 workspace 绑定）。
     */
    private val relayWorkspaceIdentity = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun newRequestId(): String =
        java.util.UUID.randomUUID().toString().replace("-", "").take(12)

    private suspend fun relayRequest(
        payload: JsonObject,
        match: (p: JsonObject, sent: JsonObject) -> Boolean,
        timeoutMs: Long = 30_000,
    ): JsonObject {
        val requestId = payload["requestId"]?.jsonPrimitive?.contentOrNull ?: newRequestId()
        val deferred = kotlinx.coroutines.CompletableDeferred<JsonObject>()
        // 与 web 客户端 k() 一致：响应不保证回带 requestId，每个 payload 都过一遍全部 matcher
        relayPending[requestId] = PendingRequest(payload, deferred, match)
        try {
            relayClient?.sendPayload(payload) ?: throw IllegalStateException("relay not connected")
            return withTimeout(timeoutMs) { deferred.await() }
        } finally {
            relayPending.remove(requestId)
        }
    }

    private class PendingRequest(
        val sent: JsonObject,
        val deferred: kotlinx.coroutines.CompletableDeferred<JsonObject>,
        val match: (p: JsonObject, sent: JsonObject) -> Boolean,
    )

    private suspend fun handleRelayPayload(payload: JsonObject) {
        when (val t = payload.zcodeType()) {
            "rpc-frame", "rpc-frame-ack" -> {
                frameTransport?.acceptPayload(payload)
                    ?: ZLog.w(TAG, "rpc-frame before bridge ready, dropped")
            }
            "bridge-degraded" -> {
                ZLog.w(TAG, "bridge degraded: ${payload["reason"]?.jsonPrimitive?.contentOrNull}")
                recoverBridge()
            }
            else -> {
                // 镜像 web 客户端 k()：遍历全部 pending matcher，命中即完成
                var consumed = false
                for ((id, pending) in relayPending) {
                    if (pending.deferred.isCompleted) continue
                    if (runCatching { pending.match(payload, pending.sent) }.getOrDefault(false)) {
                        relayPending.remove(id)
                        pending.deferred.complete(payload)
                        consumed = true
                        break
                    }
                }
                if (!consumed) ZLog.d(TAG, "unhandled relay payload: $t")
            }
        }
    }

    /** bootstrap-response.result → ServerInfo（relay 模式下 workspaces 字段名是 workspacePath/label）。 */
    private fun relayServerInfoFromBootstrap(result: JsonObject?): ServerInfo {
        val workspaces = (result?.get("workspaces") as? kotlinx.serialization.json.JsonArray
            ?: kotlinx.serialization.json.JsonArray(emptyList()))
            .mapNotNull { it as? JsonObject }
            .mapNotNull { w ->
                // 对齐 web 客户端 HC()：key = workspaceIdentity?.trim() || workspacePath
                val identity = w.strOrNull("workspaceIdentity")?.trim()?.takeUnless { it.isEmpty() }
                val path = w.strOrNull("workspacePath") ?: w.strOrNull("workspaceKey")
                    ?: w.strOrNull("key") ?: w.strOrNull("id") ?: identity
                    ?: return@mapNotNull null
                if (identity != null) relayWorkspaceIdentity[path] = identity
                ServerInfo.Workspace(path = path, label = w.strOrNull("label"))
            }
        return ServerInfo(
            serverId = result?.strOrNull("serverId") ?: "relay",
            version = result?.strOrNull("version"),
            protocolVersion = result?.intOrNull("protocolVersion") ?: 3,
            authRequired = false,
            workspaces = workspaces,
        )
    }

    private fun JsonObject.strOrNull(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf {
            it !is kotlinx.serialization.json.JsonNull && it.isString
        }?.contentOrNull

    private fun JsonObject.intOrNull(key: String): Int? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf {
            it !is kotlinx.serialization.json.JsonNull
        }?.contentOrNull?.toIntOrNull()

    /**
     * workspace-bridge-open → ready → 组装 rpc-frame 传输 + ChannelClient + V4 握手。
     * bridge 上每个重组后的 rpc-frame 逻辑消息就是一个 ChannelClient body（值流直喂，
     * 无 IPC 13 字节帧—— zemote rpc_transport.dart 注释确认的语义）。
     */
    private suspend fun openBridge(workspace: ServerInfo.Workspace, recoveryId: String? = null) {
        // 重开场景先释放旧 bridge（旧 transport/channel/protocol 一并作废），
        // 避免新旧两套 rpc-frame 传输同时向 relay 发送/接收。
        relayBridge?.let { old ->
            old.transport.dispose()
            old.channel.close()
            old.protocol.close()
        }
        relayBridge = null
        frameTransport = null
        bridgeGeneration += 1
        val bridgeSessionId = "bridge-" + newRequestId()
        val gen = bridgeGeneration
        val res = relayRequest(buildJsonObject {
            put("zcode_type", "workspace-bridge-open")
            put("requestId", "wb-" + newRequestId())
            put("bridgeSessionId", bridgeSessionId)
            put("bridgeGeneration", gen)
            put("workspaceKey", workspace.path)
            recoveryId?.let { put("recoveryId", it) }
        }, match = { p, sent ->
            (p.zcodeType() == "workspace-bridge-ready" || p.zcodeType() == "workspace-bridge-error") &&
                p["bridgeSessionId"]?.jsonPrimitive?.contentOrNull ==
                    sent["bridgeSessionId"]?.jsonPrimitive?.contentOrNull
        })
        if (res.zcodeType() == "workspace-bridge-error") {
            throw IllegalStateException("workspace-bridge-error: ${res["error"]?.jsonPrimitive?.contentOrNull}")
        }
        val bridge = res["bridge"] as? JsonObject ?: buildJsonObject { }
        val confirmedBridgeId = bridge.strOrNull("bridgeSessionId") ?: bridgeSessionId

        val wsChannel = ChannelClient(scope)
        val transport = RpcFrameTransport(
            bridgeSessionId = confirmedBridgeId,
            bridgeGeneration = bridge.intOrNull("bridgeGeneration") ?: gen,
            recoveryId = bridge.strOrNull("recoveryId"),
            sendPayload = { p -> relayClient?.sendPayload(p) },
            scope = scope,
        )
        transport.start()
        // 下行：transport 重组出的完整 body 直接喂 ChannelClient（绕过 IPC 物理帧解码）；
        // 上行：ChannelClient.onSend 的物理帧原样作为逻辑消息分片发出。
        // ChannelClient 产出的是 IPC 物理帧（header+body 已 serializeTwoSegments），
        // bridge 值流路径的 body 恰好等价于"整个物理帧"——因为桌面 web 客户端在同一
        // 传输上也按完整 IPC 帧处理（zemote channel_client 的 handleMessage 也是整帧解析）。
        scope.launch {
            transport.messages.collect { body -> wsChannel.onMessageRaw(body) }
        }
        wsChannel.onSend = { frame -> transport.sendMessage(frame) }

        // 等桌面侧 Initialize（值流 Initialize 帧）到达 IDLE
        withTimeout(WS_IDLE_TIMEOUT_MS) { wsChannel.state.first { it == ChannelClient.State.IDLE } }

        val protocol = ZcodeProtocolV4(scope, wsChannel, clientId, appVersion)
        protocol.handshake()
        // attach 事件监听：identity 存在时传 workspaceIdentity（远程链路贯穿传递，AGENTS.md）
        protocol.attachFrameListener(
            workspacePath = workspace.path,
            workspaceIdentity = relayWorkspaceIdentity[workspace.path],
        )

        relayBridge = RelayBridge(bridge, transport, wsChannel, protocol)
        _protocol.value = protocol

        // 通知桌面当前移动端视图状态（web 客户端 N() 语义）
        relayClient?.sendPayload(buildJsonObject {
            put("zcode_type", "mobile-view-state-update")
            put("viewState", buildJsonObject {
                put("activeWorkspaceKey", workspace.path)
                put("updatedAt", System.currentTimeMillis())
            })
            put("deviceInfo", buildJsonObject {
                put("platform", "android")
                put("version", appVersion)
                put("name", "ZCode Android")
            })
        })
        frameTransport = transport
    }

    /** bridge-degraded 恢复：先便宜的 reconnect-request，失败重开 bridge（带 recoveryId）。 */
    private suspend fun recoverBridge() {
        val current = relayBridge ?: return
        val ws0 = _currentWorkspace.value ?: return
        repeat(3) { attempt ->
            try {
                val res = relayRequest(buildJsonObject {
                    put("zcode_type", "workspace-reconnect-request")
                    put("requestId", "wr-" + newRequestId())
                    put("workspaceKey", ws0.path)
                }, match = { p, sent ->
                    p.zcodeType() == "workspace-reconnect-response" &&
                        p["requestId"]?.jsonPrimitive?.contentOrNull == sent["requestId"]?.jsonPrimitive?.contentOrNull
                })
                if ((res["success"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true") {
                    ZLog.i(TAG, "bridge reconnected (${attempt + 1})")
                    return
                }
            } catch (e: Exception) {
                ZLog.w(TAG, "reconnect-request failed (attempt ${attempt + 1}): ${e.message}")
            }
            delay(3_000)
        }
        try {
            val rid = current.bridge.strOrNull("recoveryId")
            openBridge(ws0, recoveryId = rid)
            ZLog.i(TAG, "bridge reopened")
        } catch (e: Exception) {
            ZLog.w(TAG, "bridge reopen failed: ${e.message}")
        }
    }

    private fun teardownRelay() {
        relayPayloadJob?.cancel()
        relayPayloadJob = null
        relayBridge?.transport?.dispose()
        relayBridge?.channel?.close()
        relayBridge?.protocol?.close()
        relayBridge = null
        frameTransport = null
        relayClient?.stop()
        relayClient = null
        relayPending.values.forEach { it.deferred.completeExceptionally(java.io.IOException("relay torn down")) }
        relayPending.clear()
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

                // 修复依据（P0-C，评审 2026-10-17）：newWebSocket 立即返回，WS 异步建立；
                // 服务端 Initialize 帧到达前 ChannelClient 仍是 UNINITIALIZED，此刻
                // request() 的 check(IDLE) 必然抛异常 → 无限重连循环。
                // 必须挂起等待 IDLE（带超时兜底防 WS 半开永久挂起）再握手。
                val wsChannel = channel ?: throw IllegalStateException("channel missing after ws open")
                withTimeout(WS_IDLE_TIMEOUT_MS) {
                    wsChannel.state.first { it == ChannelClient.State.IDLE }
                }

                _state.value = State.Handshaking
                val protocol = ZcodeProtocolV4(scope, wsChannel, clientId, appVersion)
                protocol.handshake()
                _protocol.value = protocol

                // 修复依据（P1-A，评审 2026-10-17）：attachFrameListener（EventListen）此前
                // 全仓库无调用者——subscribe 只建 topic，服务端只向已 listen 的动态事件
                // 发 EventFire，导致一行流式数据都收不到。事件监听是连接生命周期的一
                // 部分，由拥有生命周期的本类在进入 Ready 时自动挂上，不让每个 feature 记着挂。
                val workspace = info.workspaces.firstOrNull()?.path
                if (workspace != null) {
                    protocol.attachFrameListener(workspacePath = workspace)
                }

                _state.value = State.Ready(info)
                _currentWorkspace.value = info.workspaces.firstOrNull()
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
        teardownRelay()
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
