package com.zai.zcode.core.network

import com.zai.zcode.core.protocol.v4.crc32WireBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * 分片重组完整性回归（评审 Finding #3）：本端发送方恒带 crc32 checksum，缺失 checksum 的
 * 帧必须被拒绝、不得免校验放行，否则损坏/被篡改的物理帧会直通到 V4 解析层。
 *
 * 修复依据（BUG-010）：runTest 依赖 kotlinx-coroutines-test（build.gradle.kts 已补）；
 * async/withTimeoutOrNull 均在 runTest 协程作用域内调用（修复此前非协程上下文调用）。
 */
class RpcFrameTransportTest {

    private fun transport() = RpcFrameTransport(
        bridgeSessionId = "b1",
        bridgeGeneration = 1,
        recoveryId = null,
        sendPayload = {},
        scope = CoroutineScope(SupervisorJob()),
    )

    private fun frame(checksum: String?, payload: ByteArray, index: Int = 0, count: Int = 1) =
        buildJsonObject {
            put("zcode_type", "rpc-frame")
            put("bridgeSessionId", "b1")
            put("bridgeGeneration", 1)
            put("seq", 1)
            put("messageSeq", 1)
            put("fragmentIndex", index)
            put("fragmentCount", count)
            put("messageBytes", payload.size)
            if (checksum != null) {
                put("checksum", buildJsonObject { put("algorithm", "crc32"); put("value", checksum) })
            }
            put("dataBase64", Base64.getEncoder().encodeToString(payload))
        }

    @Test
    fun `checksum present valid frame is accepted`() = runTest {
        val t = transport()
        val data = "hello".toByteArray()
        // SharedFlow replay=0，须先订阅再喂帧，否则 first() 永远等不到已发出的帧。
        // 订阅等待必须挂在真实时间调度器（Dispatchers.Default）上：transport 的
        // emit 用自己的 scope（默认调度器、真实时间），而 runTest 是虚拟时间——
        // await 挂在虚拟调度器上会让虚拟时钟瞬间跑完 1s 超时，真实 emit 尚未发生。
        val received = async(Dispatchers.Default) { t.messages.first() }
        val accepted = t.acceptPayload(frame(crc32WireBytes(data), data))
        assertTrue(accepted)
        assertArrayEquals(data, withTimeoutOrNull(1_000) { received.await() })
    }

    @Test
    fun `checksum absent frame is rejected`() = runTest {
        val t = transport()
        val data = "hello".toByteArray()
        // 仍是合法信封（zcode_type=rpc-frame），acceptPayload 返回 true 表示已消费
        val accepted = t.acceptPayload(frame(null, data))
        assertTrue(accepted)
        // 但无校验和，绝不应投递到 messages
        val received = withTimeoutOrNull(500) { t.messages.first() }
        assertNull(received)
    }
}
