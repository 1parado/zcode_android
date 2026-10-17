package com.zai.zcode.core.protocol.v4

import com.zai.zcode.core.protocol.rpc.asLongOrNull
import com.zai.zcode.core.protocol.rpc.asStringOrNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class WireReassemblyTest {

    private fun frameJson(topic: String, subId: String, seq: Long = 1) = buildJsonObject {
        put("topic", topic)
        put("subscriptionId", subId)
        put("fromSeq", seq)
        put("toSeq", seq)
        put("sentAt", 0L)
        put("payload", buildJsonObject { put("kind", "snapshot"); put("snapshot", buildJsonObject {}) })
    }

    private fun completeWire(topic: String, subId: String, frame: JsonObject) = buildJsonObject {
        put("wireVersion", V4.WIRE_PROTOCOL_VERSION)
        put("kind", "complete")
        put("deliveryKind", "initial")
        put("logicalFrameId", "lf-1")
        put("logicalFrameOrdinal", 1L)
        put("topic", topic)
        put("subscriptionId", subId)
        put("frame", frame)
    }

    @Test
    fun `complete wire passes through`() {
        val wire = TopicWireFrame.fromJson(completeWire("conversation/s1", "sub-1", frameJson("conversation/s1", "sub-1")))!!
        val result = WireReassembly.reassemble(listOf(wire))
        assertTrue(result is ReassembleResult.Complete)
        result as ReassembleResult.Complete
        assertEquals("conversation/s1", result.frame.topic)
        assertEquals("initial", result.deliveryKind)
    }

    @Test
    fun `complete wire envelope mismatch rejected`() {
        // frame.topic 与 wire 信封不一致
        val wire = TopicWireFrame.fromJson(completeWire("conversation/s1", "sub-1", frameJson("conversation/OTHER", "sub-1")))!!
        val result = WireReassembly.reassemble(listOf(wire))
        assertTrue(result is ReassembleResult.Rejected)
    }

    @Test
    fun `crc32 golden fixture`() {
        // Python zlib.crc32 交叉验证值。
        // 修复原因：原期望 "a684c7dc" 是写错的值；"The quick brown fox..." 的 zlib.crc32
        // 权威值为 0x414fa339（实测输出与 zlib 一致，实现正确，测试期望错误）。
        assertEquals("cbf43926", crc32WireBytes("123456789".toByteArray()))
        assertEquals("00000000", crc32WireBytes(ByteArray(0)))
        assertEquals("414fa339", crc32WireBytes("The quick brown fox jumps over the lazy dog".toByteArray()))
    }

    @Test
    fun `fragment reassembly with crc`() {
        val payload = """{"topic":"conversation/s1","subscriptionId":"sub-1","fromSeq":1,"toSeq":1,"sentAt":0,"payload":{"kind":"snapshot","snapshot":{"rows":[]}}}"""
            .toByteArray(Charsets.UTF_8)
        val mid = payload.size / 2
        val b64 = Base64.getEncoder()
        val checksum = crc32WireBytes(payload)
        fun frag(index: Int, data: ByteArray, id: String) = buildJsonObject {
            put("wireVersion", V4.WIRE_PROTOCOL_VERSION)
            put("kind", "fragment")
            put("deliveryKind", "online")
            put("logicalFrameId", id)
            put("logicalFrameOrdinal", 2L)
            put("topic", "conversation/s1")
            put("subscriptionId", "sub-1")
            put("fragmentIndex", index)
            put("fragmentCount", 2)
            put("logicalBytes", payload.size.toLong())
            put("checksum", buildJsonObject { put("algorithm", "crc32"); put("value", checksum) })
            put("dataBase64", b64.encodeToString(data))
        }
        val w1 = TopicWireFrame.fromJson(frag(0, payload.copyOfRange(0, mid), "lf-2"))!!
        val w2 = TopicWireFrame.fromJson(frag(1, payload.copyOfRange(mid, payload.size), "lf-2"))!!
        val result = WireReassembly.reassemble(listOf(w2, w1)) // 乱序传入
        assertTrue(result is ReassembleResult.Complete)
        result as ReassembleResult.Complete
        assertEquals(1L, result.frame.toSeq)
    }

    @Test
    fun `fragment missing index incomplete`() {
        val payload = "hello".toByteArray()
        fun frag(index: Int, data: ByteArray) = buildJsonObject {
            put("wireVersion", V4.WIRE_PROTOCOL_VERSION); put("kind", "fragment")
            put("deliveryKind", "online"); put("logicalFrameId", "lf-x")
            put("logicalFrameOrdinal", 1L); put("topic", "conversation/s1")
            put("subscriptionId", "sub-1"); put("fragmentIndex", index)
            put("fragmentCount", 2); put("logicalBytes", payload.size.toLong())
            put("checksum", buildJsonObject { put("algorithm", "crc32"); put("value", crc32WireBytes(payload)) })
            put("dataBase64", Base64.getEncoder().encodeToString(data))
        }
        val w1 = TopicWireFrame.fromJson(frag(0, payload))!!
        val result = WireReassembly.reassemble(listOf(w1))
        assertTrue(result is ReassembleResult.Incomplete)
        assertEquals(listOf(1), (result as ReassembleResult.Incomplete).missingIndexes)
    }

    @Test
    fun `non crc32 algorithm rejected at parse`() {
        val obj = buildJsonObject {
            put("wireVersion", V4.WIRE_PROTOCOL_VERSION); put("kind", "fragment")
            put("deliveryKind", "online"); put("logicalFrameId", "lf")
            put("logicalFrameOrdinal", 1L); put("topic", "conversation/s1")
            put("subscriptionId", "sub"); put("fragmentIndex", 0); put("fragmentCount", 1)
            put("logicalBytes", 1L)
            put("checksum", buildJsonObject { put("algorithm", "sha256"); put("value", "x") })
            put("dataBase64", "aGk=")
        }
        assertNull(TopicWireFrame.fromJson(obj))
    }

    @Test
    fun `invalid base64 rejected`() {
        val frag = buildJsonObject {
            put("wireVersion", V4.WIRE_PROTOCOL_VERSION); put("kind", "fragment")
            put("deliveryKind", "online"); put("logicalFrameId", "lf")
            put("logicalFrameOrdinal", 1L); put("topic", "conversation/s1")
            put("subscriptionId", "sub"); put("fragmentIndex", 0); put("fragmentCount", 1)
            put("logicalBytes", 2L)
            put("checksum", buildJsonObject { put("algorithm", "crc32"); put("value", "00000000") })
            put("dataBase64", "!!!") // 非法 alphabet
        }
        val wire = TopicWireFrame.fromJson(frag)!!
        val result = WireReassembly.reassemble(listOf(wire))
        assertEquals("proto.frameAssemblyInvalidBase64", (result as ReassembleResult.Rejected).reasonCode)
    }
}

class ConversationReducerTest {

    private fun snapshotFrame(topic: String, toSeq: Long, snapshot: JsonObject) = buildJsonObject {
        put("topic", topic); put("subscriptionId", "sub")
        put("fromSeq", 0L); put("toSeq", toSeq); put("sentAt", 0L)
        put("payload", buildJsonObject { put("kind", "snapshot"); put("snapshot", snapshot) })
    }

    private fun deltaFrame(topic: String, fromSeq: Long, toSeq: Long, deltas: String) = TopicFrame.fromJson(
        buildJsonObject {
            put("topic", topic); put("subscriptionId", "sub")
            put("fromSeq", fromSeq); put("toSeq", toSeq); put("sentAt", 0L)
            put("payload", buildJsonObject {
                put("kind", "deltas")
                put("deltas", com.zai.zcode.core.common.AppJson.parseToJsonElement(deltas))
            })
        }
    )!!

    @Test
    fun `snapshot replaces rows then delta appends`() {
        val reducer = ConversationReducer()
        val topic = "conversation/s1"
        val snap = TopicFrame.fromJson(snapshotFrame(topic, 5, buildJsonObject {
            put("rows", com.zai.zcode.core.common.AppJson.parseToJsonElement(
                """[{"rowId":1,"kind":"userInput","text":"hi"},{"rowId":2,"kind":"assistantText","text":"he"}]"""
            ))
            put("state", buildJsonObject { put("phase", "thinking") })
            put("logEpoch", "epoch-a")
        }))!!
        assertTrue(reducer.apply(snap))
        var state = reducer.snapshot("s1")
        assertEquals(2, state.rows.size)
        assertEquals("thinking", state.state?.get("phase")?.toString()?.trim('"'))
        assertEquals("epoch-a", state.logEpoch)

        // row.delta append text
        assertTrue(reducer.apply(deltaFrame(topic, 5, 6, """[{"op":"row.delta","rowId":2,"path":"text","append":"llo"}]""")))
        state = reducer.snapshot("s1")
        // 修复原因：Kotlin 扩展函数必须用接收者语法调用（x.asStringOrNull()），
        // 顶层调用语法 asStringOrNull(x) 会被当作普通函数解析而报候选不适用。
        assertEquals("hello", state.rows[1]["text"].asStringOrNull())
    }

    @Test
    fun `空会话 initial snapshot toSeq=0 必须施加`() {
        // 修复依据(真机 v0.2.11"新建会话后 加载会话… 永挂"):全新空会话 initial
        // snapshot toSeq=0,旧实现 `toSeq <= seq`(seq 初值 0)把它静默丢弃,rows 永远为空。
        val reducer = ConversationReducer()
        val topic = "conversation/s-new"
        val snap = TopicFrame.fromJson(snapshotFrame(topic, 0, buildJsonObject {
            put("rows", com.zai.zcode.core.common.AppJson.parseToJsonElement("[]"))
            put("state", buildJsonObject { put("phase", "draft") })
        }))!!
        assertTrue(reducer.apply(snap))
        val st = reducer.snapshot("s-new")
        assertEquals(0, st.rows.size)
        assertEquals("draft", st.state?.get("phase")?.jsonPrimitive?.content)

        // 后续 delta 以 fromSeq=0 起步仍可衔接
        assertTrue(reducer.apply(deltaFrame(topic, 0, 1, """[{"op":"row.appended","row":{"rowId":1,"kind":"userInput","text":"hi"}}]""")))
        assertEquals(1, reducer.snapshot("s-new").rows.size)
    }

    @Test
    fun `stale frame dropped`() {
        val reducer = ConversationReducer()
        val topic = "conversation/s1"
        reducer.apply(TopicFrame.fromJson(snapshotFrame(topic, 10, buildJsonObject { put("rows", com.zai.zcode.core.common.AppJson.parseToJsonElement("[]")) }))!!)
        // 修复依据(真机 v0.2.11 永挂加载):snapshot 改为整表替换永远施加(重复同帧
        // 幂等,不丢),旧帧判定只作用于 deltas——与 TS conversationProjectionStore 对齐。
        assertTrue(reducer.apply(TopicFrame.fromJson(snapshotFrame(topic, 10, buildJsonObject {
            put("rows", com.zai.zcode.core.common.AppJson.parseToJsonElement("""[{"rowId":9,"kind":"userInput"}]"""))
        }))!!))
        assertEquals(1, reducer.snapshot("s1").rows.size)
        // stale delta(toSeq <= seq)仍被丢弃
        assertFalse(reducer.apply(deltaFrame(topic, 10, 10, """[{"op":"row.appended","row":{"rowId":1,"kind":"userInput","text":"x"}}]""")))
        assertEquals(1, reducer.snapshot("s1").rows.size)
    }

    @Test
    fun `row removed truncates from fromRowId`() {
        val reducer = ConversationReducer()
        val topic = "conversation/s1"
        reducer.apply(TopicFrame.fromJson(snapshotFrame(topic, 1, buildJsonObject {
            put("rows", com.zai.zcode.core.common.AppJson.parseToJsonElement(
                """[{"rowId":1,"kind":"userInput"},{"rowId":2,"kind":"userInput"},{"rowId":3,"kind":"userInput"}]"""
            ))
        }))!!)
        assertTrue(reducer.apply(deltaFrame(topic, 1, 2, """[{"op":"row.removed","fromRowId":2}]""")))
        val rows = reducer.snapshot("s1").rows
        assertEquals(1, rows.size)
        assertEquals(1L, rows[0]["rowId"].asLongOrNull())
    }

    @Test
    fun `state updated replaces keys not deep merge`() {
        val reducer = ConversationReducer()
        val topic = "conversation/s1"
        reducer.apply(TopicFrame.fromJson(snapshotFrame(topic, 1, buildJsonObject {
            put("rows", com.zai.zcode.core.common.AppJson.parseToJsonElement("[]"))
            put("state", buildJsonObject { put("a", "old"); put("b", "keep") })
        }))!!)
        reducer.apply(deltaFrame(topic, 1, 2, """[{"op":"state.updated","patch":{"a":"new"}}]"""))
        val st = reducer.snapshot("s1").state!!
        assertEquals("\"new\"", st["a"].toString())
        assertEquals("\"keep\"", st["b"].toString())
    }

    @Test
    fun `output dot text appends nested`() {
        val reducer = ConversationReducer()
        val topic = "conversation/s1"
        reducer.apply(TopicFrame.fromJson(snapshotFrame(topic, 1, buildJsonObject {
            put("rows", com.zai.zcode.core.common.AppJson.parseToJsonElement(
                """[{"rowId":1,"kind":"toolCall","output":{"text":"par"}}]"""
            ))
        }))!!)
        reducer.apply(deltaFrame(topic, 1, 2, """[{"op":"row.delta","rowId":1,"path":"output.text","append":"tial"}]"""))
        val row = reducer.snapshot("s1").rows[0]
        assertEquals("\"partial\"", (row["output"] as JsonObject)["text"].toString())
    }

    @Test
    fun `non conversation topic ignored`() {
        val reducer = ConversationReducer()
        val frame = TopicFrame.fromJson(snapshotFrame("sessions-index/w1", 1, buildJsonObject {
            put("rows", com.zai.zcode.core.common.AppJson.parseToJsonElement("[]"))
        }))!!
        assertFalse(reducer.apply(frame))
    }

    @Test
    fun `deltas fromSeq gap triggers onSeqGap and is not applied`() {
        // 修复依据（真机 v0.2.15「聊天流停在半句」）：zemote ConversationState.applyFrame
        // （conversation.dart:1351-1354）对 deltas 做 `if (fromSeq != seq) { onGap(); return; }`。
        // 空洞帧若"带洞继续"施加，流式 append 会拼在缺前序的文本上，且水位与状态漂移。
        val reducer = ConversationReducer()
        var gaps = 0
        reducer.onSeqGap = { gaps++ }
        val topic = "conversation/s1"
        reducer.apply(TopicFrame.fromJson(snapshotFrame(topic, 3, buildJsonObject {
            put("rows", com.zai.zcode.core.common.AppJson.parseToJsonElement(
                """[{"rowId":1,"kind":"assistantText","text":"he"}]"""
            ))
        }))!!)
        // 中间 fromSeq=3→5 那帧丢了，直接来 fromSeq=5 的帧
        val applied = reducer.apply(deltaFrame(topic, 5, 9, """[{"op":"row.delta","rowId":1,"path":"text","append":"llo"}]"""))
        assertFalse(applied)
        assertEquals(1, gaps)
        // 空洞帧不得改状态也不得推进水位，等 resync 的 snapshot 来救
        val st = reducer.snapshot("s1")
        assertEquals(3, st.seq)
        assertEquals("\"he\"", st.rows[0]["text"].toString())
    }

    @Test
    fun `contiguous deltas do not trigger gap`() {
        val reducer = ConversationReducer()
        var gaps = 0
        reducer.onSeqGap = { gaps++ }
        val topic = "conversation/s1"
        reducer.apply(TopicFrame.fromJson(snapshotFrame(topic, 5, buildJsonObject {
            put("rows", com.zai.zcode.core.common.AppJson.parseToJsonElement(
                """[{"rowId":1,"kind":"assistantText","text":"he"}]"""
            ))
        }))!!)
        assertTrue(reducer.apply(deltaFrame(topic, 5, 6, """[{"op":"row.delta","rowId":1,"path":"text","append":"llo"}]""")))
        assertEquals(0, gaps)
        assertEquals("\"hello\"", reducer.snapshot("s1").rows[0]["text"].toString())
    }

    @Test
    fun `resync snapshot with stale toSeq still replaces`() {
        // resync 重放的 snapshot 常带着与本地陈旧水位相等甚至更小的 toSeq；
        // 必须整表替换且不倒退水位，否则下一个 delta 会被判成空洞 → resync 自我循环。
        val reducer = ConversationReducer()
        var gaps = 0
        reducer.onSeqGap = { gaps++ }
        val topic = "conversation/s1"
        reducer.apply(TopicFrame.fromJson(snapshotFrame(topic, 5, buildJsonObject {
            put("rows", com.zai.zcode.core.common.AppJson.parseToJsonElement(
                """[{"rowId":1,"kind":"assistantText","text":"he"}]"""
            ))
        }))!!)
        assertTrue(reducer.apply(TopicFrame.fromJson(snapshotFrame(topic, 5, buildJsonObject {
            put("rows", com.zai.zcode.core.common.AppJson.parseToJsonElement(
                """[{"rowId":1,"kind":"assistantText","text":"hello"},{"rowId":2,"kind":"userInput","text":"q"}]"""
            ))
        }))!!))
        val st = reducer.snapshot("s1")
        assertEquals(2, st.rows.size)
        assertEquals(5, st.seq)
        assertEquals(0, gaps)
        // 重放后水位仍是 5，后续 fromSeq=5 的 delta 可以正常衔接
        assertTrue(reducer.apply(deltaFrame(topic, 5, 6, """[{"op":"row.delta","rowId":1,"path":"text","append":"!"}]""")))
        assertEquals(0, gaps)
    }
}

class SessionsIndexReducerTest {

    private fun topic(w: String) = "sessions-index/$w"

    private fun frame(kindPayload: String, toSeq: Long, fromSeq: Long = 0) = TopicFrame.fromJson(
        buildJsonObject {
            put("topic", topic("w1")); put("subscriptionId", "sub")
            put("fromSeq", fromSeq); put("toSeq", toSeq); put("sentAt", 0L)
            put("payload", com.zai.zcode.core.common.AppJson.parseToJsonElement(kindPayload))
        }
    )!!

    @Test
    fun `snapshot then upsert and remove`() {
        val reducer = SessionsIndexReducer()
        reducer.apply(frame(
            """{"kind":"snapshot","snapshot":{"workspaceId":"w1","logEpoch":"e1","sessions":[{"sessionId":"s1","title":"A"},{"sessionId":"s2","title":"B"}]}}""",
            5,
        ))
        var snap = reducer.snapshot()
        assertEquals(2, snap.sessions.size)
        assertEquals("e1", snap.logEpoch)

        reducer.apply(frame("""{"kind":"deltas","deltas":[{"op":"session.upserted","session":{"sessionId":"s3","title":"C"}}]}""", 6, 5))
        snap = reducer.snapshot()
        assertEquals(3, snap.sessions.size)

        reducer.apply(frame("""{"kind":"deltas","deltas":[{"op":"session.removed","sessionId":"s1"}]}""", 7, 6))
        snap = reducer.snapshot()
        assertEquals(2, snap.sessions.size)
        assertTrue(snap.sessions.none { SessionSummaryView(it).sessionId == "s1" })
    }

    @Test
    fun `fromSeq gap triggers onSeqGap and is not applied`() {
        // 修复依据（真机 v0.2.15「列表偶发缺失」）：zemote conversation.dart:1257 对
        // deltas 做 `if (fromSeq != seq) { onGap(); return; }`。带洞的帧必须整帧丢弃
        // 且不推进水位，否则本地状态会静默停在少条目的状态。
        val reducer = SessionsIndexReducer()
        var gaps = 0
        reducer.onSeqGap = { gaps++ }
        reducer.apply(frame(
            """{"kind":"snapshot","snapshot":{"workspaceId":"w1","logEpoch":"e1","sessions":[{"sessionId":"s1","title":"A"}]}}""",
            3,
        ))
        // 空洞：本地水位 3，帧却从 5 起步
        val applied = reducer.apply(
            frame("""{"kind":"deltas","deltas":[{"op":"session.removed","sessionId":"s1"}]}""", 9, 5),
        )
        assertFalse(applied)
        assertEquals(1, gaps)
        // 未推进水位 + 未施加：条目仍在
        assertEquals(3L, reducer.snapshot().seq)
        assertEquals(1, reducer.snapshot().sessions.size)
    }

    @Test
    fun `contiguous deltas do not trigger gap`() {
        val reducer = SessionsIndexReducer()
        var gaps = 0
        reducer.onSeqGap = { gaps++ }
        reducer.apply(frame(
            """{"kind":"snapshot","snapshot":{"workspaceId":"w1","sessions":[{"sessionId":"s1"}]}}""",
            5,
        ))
        assertTrue(reducer.apply(frame("""{"kind":"deltas","deltas":[]}""", 6, 5)))
        assertEquals(0, gaps)
    }

    @Test
    fun `resync snapshot with equal toSeq is still applied`() {
        // 修复依据（真机 v0.2.15「resync 后仍空」）：resync 重放的 snapshot 可能带着
        // 与本地陈旧水位相等的 toSeq，被 `toSeq <= seq` 拦掉就等于 resync 白做。
        val reducer = SessionsIndexReducer()
        reducer.apply(frame(
            """{"kind":"snapshot","snapshot":{"workspaceId":"w1","logEpoch":"e1","sessions":[{"sessionId":"s1"}]}}""",
            5,
        ))
        val replay = frame(
            """{"kind":"snapshot","snapshot":{"workspaceId":"w1","logEpoch":"e1","sessions":[{"sessionId":"s1"},{"sessionId":"s2"},{"sessionId":"s3"}]}}""",
            5,
        )
        assertTrue(reducer.apply(replay))
        assertEquals(3, reducer.snapshot().sessions.size)
        // 水位不倒退
        assertEquals(5L, reducer.snapshot().seq)
    }
}

class V4FrameAssemblerTest {

    private class Harness {
        val frames = mutableListOf<Pair<TopicFrame, String>>()
        val faults = mutableListOf<String>()
        val assembler = V4FrameAssembler(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)) { f, d ->
            frames.add(f to d)
        }.apply {
            onFaultCallback = { faults.add(it) }
        }
    }

    private fun topicFrame(topic: String, sub: String, seq: Long) = buildJsonObject {
        put("topic", topic); put("subscriptionId", sub)
        put("fromSeq", seq); put("toSeq", seq); put("sentAt", 0L)
        put("payload", buildJsonObject { put("kind", "snapshot"); put("snapshot", buildJsonObject {}) })
    }

    private fun complete(topic: String, sub: String, ordinal: Long, id: String) = TopicWireFrame.fromJson(
        buildJsonObject {
            put("wireVersion", V4.WIRE_PROTOCOL_VERSION); put("kind", "complete")
            put("deliveryKind", "online"); put("logicalFrameId", id)
            put("logicalFrameOrdinal", ordinal); put("topic", topic); put("subscriptionId", sub)
            put("frame", topicFrame(topic, sub, ordinal))
        }
    )!!

    @Test
    fun `older ordinal silently dropped`() {
        val h = Harness()
        h.assembler.onWireFrame(complete("conversation/s1", "sub", 5, "a"))
        h.assembler.onWireFrame(complete("conversation/s1", "sub", 4, "b"))
        assertEquals(1, h.frames.size)
        assertTrue(h.faults.isEmpty())
    }

    @Test
    fun `same ordinal different id is ordinal conflict`() {
        val h = Harness()
        h.assembler.onWireFrame(complete("conversation/s1", "sub", 5, "a"))
        h.assembler.onWireFrame(complete("conversation/s1", "sub", 5, "b"))
        assertEquals(1, h.frames.size)
        assertEquals(listOf("proto.frameOrdinalConflict"), h.faults)
    }

    @Test
    fun `higher ordinal recovery supersedes without fault`() {
        val h = Harness()
        h.assembler.onWireFrame(complete("conversation/s1", "sub", 5, "a"))
        h.assembler.onWireFrame(complete("conversation/s1", "sub", 6, "b"))
        assertEquals(2, h.frames.size)
        assertTrue(h.faults.isEmpty())
    }

    @Test
    fun `different routes are independent`() {
        val h = Harness()
        h.assembler.onWireFrame(complete("conversation/s1", "sub", 5, "a"))
        h.assembler.onWireFrame(complete("conversation/s2", "sub", 3, "c"))
        assertEquals(2, h.frames.size)
    }
}

class ZcodeProtocolV4UnitTest {
    @Test
    fun `clientHello omits capabilities when host lacks workflowRunDeltas`() {
        val hello = buildClientHello("client-1", "0.1.0", false)
        assertNull(hello["capabilities"])
        assertEquals("\"clientHello\"", hello["kind"].toString())
    }

    @Test
    fun `clientHello carries workflowRunDeltas only when host declares`() {
        val hello = buildClientHello("client-1", "0.1.0", true)
        val caps = hello["capabilities"] as JsonObject
        assertEquals("true", caps["workflowRunDeltas"].toString())
    }

    @Test
    fun `subscribe params are service-face shape`() {
        val p = buildConversationSubscribeParams("/ws", "s1", baseLogEpoch = "e", baseSeq = 9)
        assertEquals("/ws", p["workspacePath"].asStringOrNull())
        assertEquals("s1", p["sessionId"].asStringOrNull())
        assertNull(p["topic"])
        assertNull(p["connectionId"])
        assertNull(p["clientMode"])
        val base = p["base"] as JsonObject
        assertEquals("\"e\"", base["logEpoch"].toString())
        assertEquals("9", base["seq"].toString())
    }
}
