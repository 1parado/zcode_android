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
 * 5. 事件 = serialize([204, id, event]) + serialize(payload)
 */
class ChannelClient(
    private val scope: CoroutineScope,
) {
    enum class State { UNINITIALIZED, IDLE, CLOSED }

    data class EventFireData(val channelName: String, val event: String, val payload: Any?)

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
                val event = (arr.getOrNull(2) as? JsonPrimitive)?.contentOrNull ?: return
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
        val body = RpcSerialization.serializeTwoSegments(header, args)
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
        send(RpcRequestType.EVENT_LISTEN, id, channelName, eventName, arg)
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
