package com.aneb.probe.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * aneb-realtime-session-v1 wire 合同锚定（D-38；权威=Codex handlers_realtime_sim.go）：
 * ANEU/ANED 帧逐字节布局（BE、偏移 4:6/6:10/10:18）、边界值、坏帧拒收、计划 JSON 字段
 * 与合同一字不差（服务端 DisallowUnknownFields，多/错字段整计划被拒）。
 */
class RealtimeWireTest {

    @Test
    fun `ANEU 上行帧逐字节布局_BE_偏移与合同一致`() {
        val payload = ByteArray(3) { (it + 1).toByte() }
        val f = RealtimeWire.encodeUplink(turnIndex = 5, seq = 258, payload = payload)
        assertEquals(10 + 3, f.size)
        assertEquals("ANEU", f.substring(0, 4).utf8())
        val buf = f.asByteBuffer()
        assertEquals(5, buf.getShort(4).toInt() and 0xFFFF)
        assertEquals(258, buf.getInt(6))
        assertEquals(1, f[10].toInt()); assertEquals(3, f[12].toInt())
    }

    @Test
    fun `ANEU 边界值_turn65535_seq最大u32`() {
        val f = RealtimeWire.encodeUplink(turnIndex = 65535, seq = -1, payload = ByteArray(1))
        val buf = f.asByteBuffer()
        assertEquals(65535, buf.getShort(4).toInt() and 0xFFFF)
        assertEquals(0xFFFFFFFFL, buf.getInt(6).toLong() and 0xFFFFFFFFL)
    }

    @Test
    fun `ANED 下行帧解码_sched_us于10到18_BE`() {
        val payload = ByteArray(7)
        val raw = ByteBuffer.allocate(18 + payload.size)
            .put(byteArrayOf(0x41, 0x4E, 0x45, 0x44)) // "ANED"
            .putShort(3).putInt(41).putLong(1_234_567_890_123L)
            .put(payload).array().toByteString()
        val d = RealtimeWire.decodeDownlink(raw, arrivalUs = 99L)!!
        assertEquals(3, d.turnIndex)
        assertEquals(41L, d.seq)
        assertEquals(1_234_567_890_123L, d.schedUs)
        assertEquals(7, d.payloadLen)
        assertEquals(99L, d.arrivalUs)
    }

    @Test
    fun `坏帧拒收_magic不符或长度不足返回null`() {
        assertNull(RealtimeWire.decodeDownlink(ByteArray(17).toByteString(), 0L)) // <18B
        val badMagic = ByteBuffer.allocate(18)
            .put(byteArrayOf(0x41, 0x4E, 0x45, 0x55)).putShort(0).putInt(0).putLong(0)
            .array().toByteString() // "ANEU" 头装下行
        assertNull(RealtimeWire.decodeDownlink(badMagic, 0L))
    }

    @Test
    fun `计划JSON字段与合同一字不差_无多余字段`() {
        val plan = RealtimeWire.SessionPlan(
            sessionId = "s1", seed = 1, setupMs = 0.0, frameMs = 20,
            turns = listOf(
                RealtimeWire.TurnPlan(
                    turnId = "t0", turnIndex = 0, startAfterPreviousMs = 0,
                    uplinkFrames = 1, uplinkFrameBytes = 32, responseWaitMs = 0,
                    plannedDownlinkFrames = 1, downlinkFrameBytes = 48, interrupted = false,
                ),
            ),
        )
        val obj = Json.parseToJsonElement(
            RealtimeWire.jsonOut.encodeToString(RealtimeWire.SessionPlan.serializer(), plan)
        ).jsonObject
        // 顶层字段集精确（DisallowUnknownFields：多一个字段整个计划被拒）
        assertEquals(
            setOf("contract_version", "session_id", "seed", "setup_ms", "frame_ms", "turns"),
            obj.keys,
        )
        assertEquals("aneb-realtime-session-v1", obj["contract_version"]!!.jsonPrimitive.content)
        val turn = (obj["turns"]!! as? kotlinx.serialization.json.JsonArray)!![0].jsonObject
        // 非中断轮：barge_in 两字段为 null（合同要求缺省/null）
        assertEquals(
            setOf(
                "turn_id", "turn_index", "start_after_previous_ms", "uplink_frames",
                "uplink_frame_bytes", "response_wait_ms", "planned_downlink_frames",
                "downlink_frame_bytes", "interrupted", "barge_in_after_frames", "expected_stop_within_ms",
            ),
            turn.keys,
        )
        assertTrue(turn["barge_in_after_frames"] is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun `出站控制DTO_type常量落wire`() {
        val ts = RealtimeWire.jsonOut.encodeToString(
            RealtimeWire.TurnStart.serializer(), RealtimeWire.TurnStart(turnId = "t0", turnIndex = 0)
        )
        val obj: JsonObject = Json.parseToJsonElement(ts).jsonObject
        assertEquals("turn_start", obj["type"]!!.jsonPrimitive.content)
        assertEquals(setOf("type", "turn_id", "turn_index"), obj.keys)
    }

    @Test
    fun `入站turn_summary解析_关键时戳齐备`() {
        val txt = """{"type":"turn_summary","turn_id":"t0","turn_index":0,
            "uplink_frames_expected":75,"uplink_frames_received":75,
            "downlink_frames_planned":100,"downlink_frames_emitted":100,
            "commit_recv_us":1000,"first_downlink_sched_us":301000,
            "first_downlink_pre_write_us":301050,"barge_in_received":false,
            "protocol_ok":true,"future_field":123}"""
        val c = RealtimeWire.jsonIn.decodeFromString(RealtimeWire.InboundControl.serializer(), txt)
        assertEquals("turn_summary", c.type)
        assertEquals(1000L, c.commitRecvUs)
        assertEquals(301050L, c.firstDownlinkPreWriteUs)
        assertEquals(true, c.protocolOk) // 未知字段 future_field 被忽略（前向兼容）
    }
}
