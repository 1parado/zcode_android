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
 * 连接机状态 → 简短中文标签。
 *
 * 放在 core:network 而不是某个 feature：它描述的是 [ZcodeConnection.State] 本身，
 * 而连接页、任务列表、会话页三处都要用同一套说法——同一件事在不同页面换一种叫法，
 * 用户会以为是两个不同的问题（曾经会话页完全不显示连接态，断线只能靠"内容不动了"
 * 猜，被读成模型卡住）。feature 之间为这一个函数互相依赖（chat → connect）会把
 * CameraX/MLKit 拖进会话页的依赖图，所以归到状态机所在模块。
 */
fun stateLabel(state: ZcodeConnection.State): String = when (state) {
    is ZcodeConnection.State.Ready -> "已连接"
    is ZcodeConnection.State.Discovering -> "发现服务…"
    is ZcodeConnection.State.ConnectingWs -> "建立连接…"
    is ZcodeConnection.State.Handshaking -> "握手…"
    is ZcodeConnection.State.Failed -> "重连中…"
    ZcodeConnection.State.Disconnected -> "未连接"
}

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
        // createSession 应答超时：CLI runtime 首次预热可能远超 15s。
        // 修复依据(真机 v0.2.14"创建会话失败"):zemote conversation.dart:258 给 90s,
        // 且 chat_page.dart:675 提示"首次可能需要预热"。超时会被转成 PromiseCancel,
        // 服务端收到 cancel 后不落 record。
        private const val CREATE_SESSION_TIMEOUT_MS = 90_000L
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
                // 修复依据（真机「连接失败：未找到桌面窗口 host process，windowId=1」）：
                // 桌面端 host 进程由 renderer dom-ready 触发 spawn（desktopWindow
                // Lifecycle.ts:126），手机扫码时桌面若正在启动/升级重启，bridge-open
                // 必然 DESKTOP_HOST_MISSING。这是暂时态——桌面起来后重试即可。
                // 有界重试（5 次 × 4s = 20s 窗口）替代直接失败，与 recoverBridge 的
                // reopen 路径同一语义。
                var opened = false
                var lastBridgeError: Exception? = null
                repeat(5) { attempt ->
                    try {
                        openBridge(ws0)
                        opened = true
                    } catch (e: Exception) {
                        lastBridgeError = e
                        if (!wantConnected.get()) return@repeat
                        ZLog.w(TAG, "bridge-open retry ${attempt + 1}/5: ${e.message}")
                        delay(4_000)
                    }
                }
                if (!opened) {
                    lastBridgeError?.let { throw it }
                    return@launch
                }

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

    /**
     * 服务端 workspaceKey 归一（[ServerInfo.Workspace.key]）：identity 缺席时回落 path。
     * 桌面所有按 workspace 的匹配（bridge-open、fan-out、topic）都用它。
     */
    private fun workspaceKeyFor(workspace: ServerInfo.Workspace): String =
        com.zai.zcode.core.protocol.v4.workspaceKeyOf(
            workspace.path,
            identityFor(workspace),
        )

    /** 当前 workspace 的 identity（V4 scope / 命令链路贯穿传递用）。 */
    private fun identityFor(workspace: ServerInfo.Workspace): String? =
        workspace.workspaceIdentity?.trim()?.takeUnless { it.isEmpty() }
            ?: relayWorkspaceIdentity[workspace.path]

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
                // 修复依据(真机日志 18:53-18:55):recoverBridge 的 relayRequest 挂起等待
                // 响应,而响应的 matcher 只在 handleRelayPayload——即本 collect 循环——里
                // 执行;同步调用等于等一个永远无人投递的信箱(死锁),表现为所有请求 30s
                // 超时、迟到的 workspace-bridge-ready/rpc-frame 全部 unhandled、bridge
                // 永久 degraded、sessions-index 无法订阅(UI 显示"暂无会话")。
                // 恢复必须脱离 collect 循环,转投独立协程执行。
                scope.launch { recoverBridge() }
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
                // 修复依据（真机 v0.2.14）：identity 随 workspace 对象携带（见
                // [ServerInfo.Workspace.workspaceIdentity]），旁路 map 只作兜底。
                ServerInfo.Workspace(
                    path = path,
                    label = w.strOrNull("label"),
                    workspaceIdentity = identity,
                )
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
            // 修复依据（真机 v0.2.14「列表零内容 + 创建会话失败」）：桌面
            // webRemoteControlManager.ts:894-901 用 resolveWebRemoteControlWorkspaceKey
            // （= workspaceIdentity?.trim() || workspacePath）**精确等值** find 目标
            // workspace，找不到就抛「目标工作区不在当前桌面窗口中」。远程 tab 的
            // identity ≠ path（rootPlatformWorkspaceSync.ts 给 isRemote 写 identity），
            // 此处发裸 path 会让 bridge 直接建不起来 → 无 V4 通道 → 零帧 + 命令全失败。
            // 对齐 zemote zemote_client.dart:279（发 workspaceKeyOf 的结果）。
            put("workspaceKey", workspaceKeyFor(workspace))
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
        // 修复依据(真机日志 17:49"rpc-frame before bridge ready, dropped"):桌面在
        // workspace-bridge-ready 后立即推送 Initialize 等帧,而 frameTransport 原来在
        // 函数末尾才赋值,导致这些帧被丢弃→ ChannelClient 永远不 IDLE→15s 超时。
        // transport 创建即注册,早于等待 IDLE。
        frameTransport = transport
        // 下行：transport 重组出的完整 body 直接喂 ChannelClient（绕过 IPC 物理帧解码）；
        // 上行：ChannelClient.onSend 的物理帧原样作为逻辑消息分片发出。
        // ChannelClient 产出的是 IPC 物理帧（header+body 已 serializeTwoSegments），
        // bridge 值流路径的 body 恰好等价于"整个物理帧"——因为桌面 web 客户端在同一
        // 传输上也按完整 IPC 帧处理（zemote channel_client 的 handleMessage 也是整帧解析）。
        scope.launch {
            transport.messages.collect { body -> wsChannel.onMessageRaw(body) }
        }
        // 修复依据(真机日志 18:02"channel initialized 后 hello 15s 无响应"):bridge
        // 值流的逻辑消息是两段序列化的 body(header数组+args),不含 13 字节 IPC 物理帧头
        // (下行已验证:handleRegular 直接 deserializeTwoSegments 成功)。上行原样发送
        // encodeRpcMessage 产物(含帧头),桌面解不出→请求全部无响应。须剥离帧头只发 body。
        wsChannel.onSend = { frame ->
            transport.sendMessage(frame.copyOfRange(com.zai.zcode.core.protocol.rpc.RpcProtocol.HEADER_LENGTH, frame.size))
        }

        // 等桌面侧 Initialize（值流 Initialize 帧）到达 IDLE
        withTimeout(WS_IDLE_TIMEOUT_MS) { wsChannel.state.first { it == ChannelClient.State.IDLE } }

        val protocol = ZcodeProtocolV4(scope, wsChannel, clientId, appVersion)
        protocol.handshake()
        // attach 事件监听：identity 存在时传 workspaceIdentity（远程链路贯穿传递，AGENTS.md）
        protocol.attachFrameListener(
            workspacePath = workspace.path,
            workspaceIdentity = identityFor(workspace),
        )

        relayBridge = RelayBridge(bridge, transport, wsChannel, protocol)
        _protocol.value = protocol

        // 通知桌面当前移动端视图状态（web 客户端 N() 语义）
        relayClient?.sendPayload(buildJsonObject {
            put("zcode_type", "mobile-view-state-update")
            put("viewState", buildJsonObject {
                // 修复依据（真机 v0.2.14）：与 workspace-bridge-open 同用服务端
                // workspaceKey 归一（identity.trim() || path）。zemote
                // sendMobileViewState（zemote_client.dart:386-403）传同一个 bridge key；
                // 发裸 path 会让桌面按 identity 记录的乐观状态对不上，视图状态与真
                // 实 bridge 错位。
                put("activeWorkspaceKey", workspaceKeyFor(workspace))
                put("updatedAt", System.currentTimeMillis())
            })
            put("deviceInfo", buildJsonObject {
                put("platform", "android")
                put("version", appVersion)
                put("name", "ZCode Android")
            })
        })
    }

    /** bridge-degraded 恢复：先便宜的 reconnect-request，失败重开 bridge（带 recoveryId）。 */
    // 修复依据(真机日志 18:54"unhandled relay payload ×4"):多个 degrade 或恢复超时
    // 重试可并发触发多次 recoverBridge,旧 bridge 对象在 openBridge 里被整体作废,
    // 两次并发的 openBridge 会互相踢掉对方的 ready 响应。用互斥串行化恢复流程。
    private val recoverMutex = Mutex()

    private suspend fun recoverBridge() {
        recoverMutex.withLock {
            if (relayBridge == null || !wantConnected.get()) return
            recoverBridgeLocked()
        }
    }

    private suspend fun recoverBridgeLocked() {
        val current = relayBridge ?: return
        val ws0 = _currentWorkspace.value ?: return
        repeat(3) { attempt ->
            try {
                val res = relayRequest(buildJsonObject {
                    put("zcode_type", "workspace-reconnect-request")
                    put("requestId", "wr-" + newRequestId())
                    // 修复依据（真机 v0.2.14）：同 openBridge，重连也按服务端
                    // workspaceKey 归一寻址，否则桌面找不到目标 bridge。
                    put("workspaceKey", workspaceKeyFor(ws0))
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
                val workspace = info.workspaces.firstOrNull()
                if (workspace != null) {
                    // 修复依据（真机 v0.2.14）：LAN 直连也有 remote tab 场景
                    // （identity ≠ path），此处原先只传 path，后续 subscribe 靠门面
                    // boundWorkspaceIdentity 兜底才没出问题；显式传 identity 让帧
                    // fan-out 参数与订阅登记同源。
                    protocol.attachFrameListener(workspace.path, identityFor(workspace))
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
        // 修复依据(真机日志 17:50"server-info unreachable 循环"):relay 模式失败重连
        // 原来走局域网 startPipeline(),对二维码 URL 做 HTTP 请求必然 404 死循环。
        // relay 失败须重走 relay 管线(重新配对+开 bridge)。
        if (wantConnected.get()) {
            if (desiredRelay != null) startRelayPipeline() else startPipeline()
        }
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

    /**
     * 切换当前 workspace（用户报障 v0.2.11：Home 页只有一个写死的「这台电脑」，
     * 无法查看其他 workspace 下的会话）。relay 模式对目标 workspace 重新走
     * workspace-bridge-open（桌面 respondToWorkspaceBridgeOpen 原生支持按 key 开桥），
     * 局域网模式只更新 _currentWorkspace（单 server 多 workspace 场景，V4 事件
     * fan-out 参数由 attachFrameListener(workspacePath) 决定）。
     */
    fun selectWorkspace(workspace: ServerInfo.Workspace) {
        scope.launch { lifecycleMutex.withLock {
            val info = _serverInfo.value ?: return@withLock
            if (_currentWorkspace.value?.path == workspace.path) return@withLock
            _currentWorkspace.value = workspace
            if (desiredRelay != null) {
                // relay：重开 bridge（openBridge 内部会作废旧 bridge 并发布新 protocol）。
                // 先置空 _protocol，让旧 protocol 的订阅者先走 onProtocolClosed；新 bridge 就绪后
                // 由全新 protocol 实例触发 repository 重订阅（实例已变会正常重发）。
                _protocol.value = null
                try {
                    openBridge(workspace)
                } catch (e: Exception) {
                    ZLog.w(TAG, "switch workspace bridge failed: ${e.message}")
                    // 失败时尝试恢复到新选 workspace（bridge-degraded 同路径）
                    scope.launch { recoverBridge() }
                }
            } else {
                // 局域网直连：单 server 多 workspace，protocol 实例不变，只需更新事件 fan-out
                // 范围（attachFrameListener）。绝不可把 _protocol.value 置 null——那会让 repository
                // 的 protocol.collect 收到 null 走 onProtocolClosed 清空订阅记账，而实例未变 +
                // StateFlow 同引用重赋值不重新派发，切换后列表/会话永久卡在加载态（评审 Finding #1）。
                // repository 重订阅由 _currentWorkspace 变化驱动（SessionsRepository.setWorkspace
                // 经 SessionsViewModel.currentWorkspace.collect 调用），此处无需重发 protocol。
                _protocol.value?.attachFrameListener(workspace.path, identityFor(workspace))
            }
            _state.value = State.Ready(info)
            relayClient?.sendPayload(buildJsonObject {
                put("zcode_type", "mobile-view-state-update")
                put("viewState", buildJsonObject {
                    // 修复依据（真机 v0.2.14）：同 openBridge，视图状态也走服务端
                    // workspaceKey 归一，避免与 bridge 登记的 key 错位。
                    put("activeWorkspaceKey", workspaceKeyFor(workspace))
                    put("updatedAt", System.currentTimeMillis())
                })
            })
            ZLog.i(TAG, "workspace selected: ${workspace.path}")
        } }
    }

    /** 会话/工作区选择变化时调用：workspaceTarget 决定事件 fan-out 范围。 */
    fun attachFrameListener(workspacePath: String, workspaceIdentity: String? = null) {
        _protocol.value?.attachFrameListener(workspacePath, workspaceIdentity)
    }

    /**
     * Android 端创建会话（用户报"应该支持在 Android 上进行会话创建"）。
     *
     * 对齐桌面 V4 路径（SessionPane.tsx:3423 / useDraftSessionPrewarm.ts:89）：
     * sendConversationCommandV4 + envelope{type:"createSession", sessionId:null,
     * payload:{workspaceId:<workspaceKey>, firstInput?}}；ack.result.sessionId 是新会话。
     *
     * 修复依据（真机 v0.2.17「创建会话还是不行」）：原先返回 `String?`，五条失败路径全部
     * 塌成一个 null，UI 只能说"请确认电脑端在线后重试"。用户报"还是不行"时这句话给不出
     * 任何可行动信息——到底没连上、被服务端拒了、还是 ACK 里没有 sessionId，完全不同。
     * 改成 [CreateSessionResult]，把服务端 reasonCode/message 原样带上。
     *
     * 修复依据（真机 v0.2.19「创建会话还是不行」）：[firstInput] 为 null 时，服务端
     * createSession 只建一条 deferred 空记录——draft 是纯内存态，不落盘、也不进
     * task index sqlite（zcodeTaskIndexSyncer.ts:675 `if (next.phase === "draft")
     * return;`）。结果是「创建成功、列表里永远看不到」，用户判定为创建失败。
     * 只有 firstInput 经 startPromptTurn（session-mgmt.ts:105）提交才会把 session
     * 提升为 immediate 并落盘。参考实现 chat_page.dart:687 同理：首条消息随
     * createSession 一起发，而不是先建空会话再补发 sendText。
     *
     * @param firstInput 首条输入文本。**必须非空**——为空等于建一个不可见的 draft。
     */
    suspend fun createSession(
        firstInput: String? = null,
        /** 草稿页选择的模型/思考/模式（createSessionRequestedConfigSchema，command.ts:66）。 */
        providerId: String? = null,
        modelId: String? = null,
        thought: String? = null,
        mode: String? = null,
    ): CreateSessionResult {
        val p = _protocol.value ?: return CreateSessionResult.Failure(CreateSessionReason.NotConnected)
        val ws = _currentWorkspace.value ?: return CreateSessionResult.Failure(CreateSessionReason.NoWorkspace)
        // 修复依据(真机 v0.2.14"创建会话失败/新会话不出现在列表"):workspaceId 必须是
        // workspaceKey(identity.trim()||path),不是裸 workspacePath。对齐 zemote
        // main_shell.dart:18 workspaceKeyOf 与桌面 useDraftSessionPrewarm.ts:76。
        // 远程链路 identity≠path,填 path 会让会话落到另一工作区。
        val wsIdentity = identityFor(ws)
        return runCatching {
            val payload = buildJsonObject {
                put("workspaceId", com.zai.zcode.core.protocol.v4.workspaceKeyOf(ws.path, wsIdentity))
                if (!firstInput.isNullOrBlank()) {
                    put("firstInput", buildJsonObject { put("text", firstInput) })
                }
                // 修复依据（用户报「新建会话没法选权限/思考/模型」）：createSession 命令
                // payload 支持 config（command.ts:66 → createSessionRequestedConfigSchema：
                // {modelSelection{providerId,modelId,options{reasoningLevel}}, mode}）。
                // 一次构造完整 config（modelSelection 与 mode 同场）。
                if (providerId != null || modelId != null || !mode.isNullOrBlank()) {
                    put("config", buildJsonObject {
                        if (providerId != null && modelId != null) {
                            put("modelSelection", buildJsonObject {
                                put("providerId", providerId)
                                put("modelId", modelId)
                                if (!thought.isNullOrBlank()) {
                                    put("options", buildJsonObject { put("reasoningLevel", thought) })
                                }
                            })
                        }
                        if (!mode.isNullOrBlank()) put("mode", mode)
                    })
                }
            }
            val envelope = com.zai.zcode.core.protocol.v4.buildCommandEnvelope(
                commandId = java.util.UUID.randomUUID().toString(),
                clientId = clientId,
                sessionId = null, // createSession 幂等桶固定 null（command.ts:345）
                type = "createSession",
                payload = payload,
            )
            // 修复依据(真机 v0.2.14"创建会话失败"):CLI runtime 首次预热可能远超默认
            // 15s,超时会被转成 PromiseCancel 导致服务端不落 record。zemote 对
            // createSession 单独给 90s(conversation.dart:258)。
            val ack = p.sendCommand(ws.path, envelope, wsIdentity, timeoutMs = CREATE_SESSION_TIMEOUT_MS)
            if (ack.status != "accepted") {
                ZLog.w(TAG, "createSession rejected: ${ack.status} ${ack.reasonCode ?: ""} ${ack.message ?: ""}")
                return@runCatching CreateSessionResult.Failure(
                    CreateSessionReason.Rejected(ack.status, ack.reasonCode, ack.message),
                )
            }
            val result = ack.result
            // 修复依据（真机 v0.2.15「已 accepted 却提示创建失败」）：zemote
            // createSession（conversation.dart:284-288）只判 sessionId 是否为非空
            // 字符串，不校验 result.type。服务端 commandResultSchema 的
            // createSession 分支确实带 type，但 duplicate 回放与部分旧 CLI 只回
            // {sessionId}，多一道 type 校验就把"会话已建好"报成失败，UI 弹错而
            // 列表里其实已经多了会话。只认 sessionId 这一条事实。
            val sessionId = result?.get("sessionId") as? JsonPrimitive
            if (sessionId == null || sessionId is kotlinx.serialization.json.JsonNull ||
                !sessionId.isString || sessionId.content.isBlank()
            ) {
                ZLog.w(TAG, "createSession ack missing result sessionId")
                return@runCatching CreateSessionResult.Failure(CreateSessionReason.NoSessionId)
            }
            CreateSessionResult.Success(sessionId.content)
        }.getOrElse {
            // 修复依据（真机 v0.2.17）：异常里可能带服务端 error code（runtime 冷启动超时
            // 与真正被拒是两种处置，前者再等等就好），把 code 与 message 一起回传，
            // UI 才能说出"该等还是该报错"。
            ZLog.w(TAG, "createSession failed: ${it.message}")
            val code = (it as? com.zai.zcode.core.protocol.rpc.PromiseErrorException)?.code
            CreateSessionResult.Failure(CreateSessionReason.Error(code, it.message))
        }
    }

    /** [createSession] 的结果：成功给会话 id，失败给**具体**原因。 */
    sealed interface CreateSessionResult {
        data class Success(val sessionId: String) : CreateSessionResult
        data class Failure(val reason: CreateSessionReason) : CreateSessionResult
    }

    /** 创建失败的可诊断原因（UI 文案按此分流，不再一律"确认电脑端在线"）。 */
    sealed interface CreateSessionReason {
        /** 协议实例还没就绪——连接断了或还在握手。 */
        data object NotConnected : CreateSessionReason

        /** 还没选定工作区（bootstrap 未返回 / 用户未选）。 */
        data object NoWorkspace : CreateSessionReason

        /** 服务端拒绝：带上 status/reasonCode/message原样透出。 */
        data class Rejected(val status: String?, val reasonCode: String?, val message: String?) : CreateSessionReason

        /** ACK accepted 但没有 sessionId——服务端/CLI 侧没建出来。 */
        data object NoSessionId : CreateSessionReason

        /** 传输层异常（超时、runtime 不可用等），保留服务端 error code 便于定位。 */
        data class Error(val code: String?, val message: String?) : CreateSessionReason
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
