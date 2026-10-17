package com.zai.zcode.core.protocol.rpc

import com.zai.zcode.core.common.ZLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "ChannelClient"

/**
 * Channel 客户端（对齐 packages/rpc/src/channelClient.ts / channelServer.ts 的交互语义）。
 *
 * 生命周期：
 * 1. 连接建立后服务端先发 Initialize 响应（header `[200]`，body undefined）
 * 2. 客户端收到后 state: UNINITIALIZED → IDLE，才允许发请求
 * 3. 请求物理帧 body = serialize([RequestType, id, channelName, methodName]) + serialize(args)
 * 4. 响应 PromiseSuccess = serialize([201, id]) + serialize(data)；
 *    PromiseError = serialize([202, id]) + serialize({message,name,...})
 * 5. 事件 fire = serialize([204, id]) + serialize(payload)（channelServer.ts sendResponse:
 *    EventFire 的 header 只有 [type, id] 两元素，不含事件名——TS channelClient 纯按
 *    id 路由 handler，事件名在 wire 上不存在）。
 */
class ChannelClient(
    private val scope: CoroutineScope,
) {
    enum class State { UNINITIALIZED, IDLE, CLOSED }

    /**
     * [event] 兼容两种 wire：桌面 ChannelServer 只发两元素 header `[204, id]`
     * （channelServer.ts:50 `send([response.type, response.id], response.data)`，事件名
     * 不上线），此时 event 为 null；若对端带第三元素则取之。
     */
    data class EventFireData(val channelName: String, val event: String?, val payload: Any?)

    private val _state = MutableStateFlow(State.UNINITIALIZED)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<EventFireData>(
        // 修复依据（P1-4）：V4 帧靠 (fromSeq, toSeq] 单调推进，乱序帧会被 reducer 丢弃。
        // 原 `scope.launch { emit }` 不保序 + DROP_OLDEST 会静默丢 delta → 状态永久分叉。
        // 改为单 socket 读协程直接 tryEmit（emit 点即 FRAME 解码点，天然串行有序），
        // 缓冲 SUSPEND：仅在读协程 emit 处生效（事件 emit 已在 collector 存在时同步），
        // 不经 launch 重排。
        replay = 0,
        extraBufferCapacity = 4096,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val events: SharedFlow<EventFireData> = _events.asSharedFlow()

    /** 由网络层注入：把一个 RPC 物理帧（header+body 已拼好）写到 socket。 */
    var onSend: ((ByteArray) -> Unit)? = null

    private val nextReqId = AtomicInteger(0)
    private val ackCounter = AtomicLong(0)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Any?>>()
    private val eventListeners = ConcurrentHashMap<Int, String>() // reqId -> channelName

    /** 下行入口：RpcFrameDecoder 解出的完整物理帧喂进来。 */
    fun onMessage(message: RpcMessage) {
        when (message.type) {
            RpcProtocol.MSG_REGULAR -> handleRegular(message)
            RpcProtocol.MSG_ACK, RpcProtocol.MSG_KEEP_ALIVE, RpcProtocol.MSG_PAUSE,
            RpcProtocol.MSG_RESUME, RpcProtocol.MSG_REPLAY_REQUEST -> Unit
            RpcProtocol.MSG_DISCONNECT -> close()
            else -> ZLog.d(TAG, "unhandled message type ${message.type}")
        }
    }

    /**
     * relay bridge 路径的下行入口：rpc-frame 重组出的逻辑消息就是完整 IPC 物理帧
     * （zemote 确认：bridge 值流无 13 字节传输层再包一层，desktop 在同一传输上按
     * 完整 IPC 帧解析）。与局域网 [onMessage] 的区别仅是没有 socket 层帧头。
     */
    fun onMessageRaw(body: ByteArray) {
        handleRegular(RpcMessage(RpcProtocol.MSG_REGULAR, 0, 0, body))
    }

    private fun handleRegular(message: RpcMessage) {
        val (header, body) = try {
            RpcSerialization.deserializeTwoSegments(message.body)
        } catch (e: Exception) {
            ZLog.w(TAG, "bad regular frame", e)
            return
        }
        val arr = header as? JsonArray ?: run {
            ZLog.w(TAG, "regular header not array")
            return
        }
        if (arr.isEmpty()) return
        val responseType = (arr[0] as? JsonPrimitive)?.content?.toIntOrNull() ?: return
        fun intAt(i: Int): Int? = (arr.getOrNull(i) as? JsonPrimitive)?.content?.toIntOrNull()

        when (responseType) {
            RpcResponseType.INITIALIZE -> {
                if (compareAndSetState()) ZLog.i(TAG, "channel initialized")
            }
            RpcResponseType.PROMISE_SUCCESS -> {
                val id = intAt(1) ?: return
                pending.remove(id)?.complete(body)
            }
            RpcResponseType.PROMISE_ERROR, RpcResponseType.PROMISE_ERROR_OBJ -> {
                val id = intAt(1) ?: return
                pending.remove(id)?.completeExceptionally(
                    PromiseErrorException(body as? JsonObject)
                )
            }
            RpcResponseType.EVENT_FIRE -> {
                val id = intAt(1) ?: return
                // 修复依据(真机 v0.2.12"订阅 ACK 成功但 20s 无帧到达"):桌面 channelServer.ts
                // 的 sendResponse 对 EventFire 发的是 send([response.type, response.id], data)
                // ——header 只有 [204, id] 两元素,不含事件名;TS channelClient.ts 纯按 id
                // 路由 handler,根本不需要事件名。旧实现写死 arr.getOrNull(2) 取事件名,
                // 取不到就 ?: return,导致所有 EventFire 帧被静默丢弃(ACK 能到、零帧),
                // 会话列表与聊天快照永不加载。现在按 eventListeners[id] 取 channelName,
                // 事件名允许缺省(第三元素存在才取);V4 层 dispatchLogicalFrame 本就按
                // frame.topic 前缀区分 conversation/sessions-index,不依赖事件名。
                val event = (arr.getOrNull(2) as? JsonPrimitive)?.contentOrNull
                val channelName = eventListeners[id] ?: return
                // 修复依据（P1-4）：去掉 scope.launch 中转——launch 不保序且 DROP_OLDEST
                // 会静默丢帧；onMessage 只被单一 socket 读协程调用，此处同步 emit 保序。
                _events.tryEmit(EventFireData(channelName, event, body))
            }
        }
    }

    private fun compareAndSetState(): Boolean {
        // 修复依据（P2-3）：原 check-then-act 非原子；MutableStateFlow 无 CAS，改 AtomicReference。
        // stateCas 是状态所有者，_state 仅作观测投影。
        if (stateCas.compareAndSet(State.UNINITIALIZED, State.IDLE)) {
            _state.value = State.IDLE
            return true
        }
        return false
    }

    private val stateCas = java.util.concurrent.atomic.AtomicReference(State.UNINITIALIZED)

    // ── 上行 ──

    private fun send(type: Int, id: Int, channelName: String?, name: String?, args: Any?) {
        val header = buildJsonArray {
            add(JsonPrimitive(type))
            add(JsonPrimitive(id))
            if (channelName != null) add(JsonPrimitive(channelName))
            if (name != null) add(JsonPrimitive(name))
        }
        // 修复依据(真机日志 18:25 zod"path:[], expected object, received undefined"):
        // TS proxy-channel 约定 channel.call(propKey, [args...])——wire body 段是参数
        // 数组,服务端 target.apply(handler, args) 按位置展开。原实现把 args 当单个
        // 值序列化,桌面解包后 args[0]=undefined,initializeConversationV4 的
        // clientHelloSchema.parse(undefined) 必然报错。null 对应 TS 无参调用 args=[]。
        val wireArgs = buildJsonArray {
            when (args) {
                null -> Unit
                is JsonArray -> args.forEach { add(it) }
                is JsonElement -> add(args)
                else -> throw RpcSerialization.BadDataException(
                    "send: unsupported args ${args::class.simpleName}"
                )
            }
        }
        val body = RpcSerialization.serializeTwoSegments(header, wireArgs)
        val frame = encodeRpcMessage(
            RpcProtocol.MSG_REGULAR,
            id,
            ackCounter.getAndIncrement().toInt(),
            body,
        )
        onSend?.invoke(frame)
    }

    /**
     * 发送 cancel/dispose（PromiseCancel / EventDispose）。
     * 修复依据（P3，评审 2026-10-17）：TS sendCancelOrDispose 的 header 是两元素
     * [type, id]；服务端按 index 取值，多带 channelName 虽当前无害，但目标是逐字
     * 对齐 TS，故 cancel/dispose 与 TS 同形。
     */
    private fun sendCancelOrDispose(type: Int, id: Int) {
        val header = buildJsonArray {
            add(JsonPrimitive(type))
            add(JsonPrimitive(id))
        }
        val body = RpcSerialization.serializeTwoSegments(header, null)
        val frame = encodeRpcMessage(
            RpcProtocol.MSG_REGULAR,
            id,
            ackCounter.getAndIncrement().toInt(),
            body,
        )
        onSend?.invoke(frame)
    }

    /**
     * 发起一次 Promise 请求。成功返回 data 段；服务端错误抛 [PromiseErrorException]。
     */
    suspend fun request(
        channelName: String,
        methodName: String,
        args: Any? = null,
        timeoutMs: Long = 30_000,
    ): Any? {
        check(_state.value == State.IDLE) { "channel not initialized: ${_state.value}" }
        val id = nextReqId.incrementAndGet()
        val deferred = CompletableDeferred<Any?>()
        pending[id] = deferred
        try {
            send(RpcRequestType.PROMISE, id, channelName, methodName, args)
        } catch (e: Exception) {
            pending.remove(id)
            throw e
        }
        try {
            return withTimeout(timeoutMs) { deferred.await() }
        } catch (e: Exception) {
            // 修复依据（P2-2）：客户端超时/取消后应发 PromiseCancel(101)，服务端不再做无用功
            //（对齐 channels.shared.ts:20 的 promise cancel 语义）。
            // 修复依据（P3）：header 用 [101, id] 两元素，与 TS sendCancelOrDispose 同形。
            try {
                sendCancelOrDispose(RpcRequestType.PROMISE_CANCEL, id)
            } catch (_: Exception) {
                // socket 已断：cancel 发不出去也无妨
            }
            throw e
        } finally {
            pending.remove(id)
        }
    }

    /**
     * 注册事件监听（EventListen）。对齐 proxy-channel.ts 的 listen(propKey, arg)：
     * EventListen 请求可携带参数（如 workspaceTarget），服务端按参数决定 fan-out 范围。
     * 返回监听 id，[disposeEvent] 时用。
     */
    fun listenEvent(channelName: String, eventName: String, arg: Any? = null): Int {
        val id = nextReqId.incrementAndGet()
        eventListeners[id] = channelName
        // 修复依据(真机 v0.2.12"订阅成功但零帧"):TS channelClient.ts sendRequest 对
        // EventListen 与 Promise 一律 serialize(writer, arg)——arg 原样序列化,不包数组
        // (Promise 的 args 数组是 proxy 层预先展开的,EventListen 的动态事件参数是单个
        // 值)。旧实现复用 send() 的 wireArgs 数组包装,桌面 connectionScope 收到的是
        // [workspaceTarget],workspaceKey 推导偏差导致帧路由不命中。
        val header = buildJsonArray {
            add(JsonPrimitive(RpcRequestType.EVENT_LISTEN))
            add(JsonPrimitive(id))
            add(JsonPrimitive(channelName))
            add(JsonPrimitive(eventName))
        }
        val body = RpcSerialization.serializeTwoSegments(header, arg as? JsonElement)
        val frame = encodeRpcMessage(
            RpcProtocol.MSG_REGULAR,
            id,
            ackCounter.getAndIncrement().toInt(),
            body,
        )
        onSend?.invoke(frame)
        return id
    }

    fun disposeEvent(id: Int) {
        val channelName = eventListeners.remove(id) ?: return
        // 修复依据（P3，评审 2026-10-17）：与 TS sendCancelOrDispose 同形 [type, id]，
        // 不再多带 channelName。channelName 仅用于本地事件路由记账。
        sendCancelOrDispose(RpcRequestType.EVENT_DISPOSE, id)
    }

    fun close() {
        // 修复依据（P2-3 同族）：close 与 compareAndSetState 并发时须以 CAS 所有者为准。
        if (!stateCas.compareAndSet(stateCas.get(), State.CLOSED)) {
            if (stateCas.get() == State.CLOSED) return
            stateCas.set(State.CLOSED)
        }
        _state.value = State.CLOSED
        val err = PromiseErrorException(null)
        pending.values.forEach { it.completeExceptionally(err) }
        pending.clear()
        eventListeners.clear()
    }
}

/** PromiseError 的 data：{message, name, stack, code?, kind?, status?...}。 */
class PromiseErrorException(val data: JsonObject?) : Exception(
    (data?.get("message") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        ?: "promise error"
) {
    val code: String?
        get() = (data?.get("code") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
}
