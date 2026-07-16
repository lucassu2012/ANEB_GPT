package com.aneb.probe.apiprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Anthropic Messages SSE 适配器：真实格式夹具解析（阶段 2 无 key 可测验证路径）。 */
class AnthropicAdapterTest {

    private val adapter = AnthropicSseAdapter()

    @Test
    fun `full fixture parses six deltas`() {
        val res = adapter.parse(SseFixtures.toRawEvents(SseFixtures.ANTHROPIC_STREAM))
        assertEquals(6, res.arrivals.size)
        assertEquals(0, res.parseErrors)
        assertNull(res.protocolError)
        // 到达序号连续（0 起，到达顺序）
        assertEquals((0 until 6).toList(), res.arrivals.map { it.index })
    }

    @Test
    fun `usage extracted from message_start and message_delta`() {
        val res = adapter.parse(SseFixtures.toRawEvents(SseFixtures.ANTHROPIC_STREAM))
        assertEquals(12, res.inputTokens)
        assertEquals(21, res.outputTokens)
    }

    @Test
    fun `stop_reason end_turn extracted`() {
        val res = adapter.parse(SseFixtures.toRawEvents(SseFixtures.ANTHROPIC_STREAM))
        assertEquals("end_turn", res.stopReason)
    }

    @Test
    fun `ping and lifecycle events are not tokens and not errors`() {
        // 夹具含 message_start/content_block_start/ping/content_block_stop/message_stop
        // 共 5 个非 delta 事件——全部既不进 arrivals 也不计 parseErrors（前两个测试锚定）
        val res = adapter.parse(SseFixtures.toRawEvents(SseFixtures.ANTHROPIC_STREAM))
        assertEquals(6, res.arrivals.size)
        assertEquals(0, res.parseErrors)
    }

    @Test
    fun `text chars counted per delta`() {
        val res = adapter.parse(SseFixtures.toRawEvents(SseFixtures.ANTHROPIC_STREAM))
        assertEquals("我是Claude，一个由Anthropic开发的AI助手。".length, res.arrivals.sumOf { it.textChars })
    }

    @Test
    fun `malformed data json counted as parse error and skipped`() {
        val broken = SseFixtures.ANTHROPIC_STREAM.replace(
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Claude"}}""",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":""",
        )
        val res = adapter.parse(SseFixtures.toRawEvents(broken))
        assertEquals(5, res.arrivals.size) // 坏 event 跳过，其余照常
        assertEquals(1, res.parseErrors) // R-08 同款：跳过并计数，绝不静默错位
    }

    @Test
    fun `error event surfaces protocolError`() {
        val stream = """
event: error
data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}
""".trimIndent()
        val res = adapter.parse(SseFixtures.toRawEvents(stream))
        assertNotNull(res.protocolError)
        assertTrue(res.protocolError!!.contains("overloaded_error"))
        assertEquals(0, res.arrivals.size)
    }

    @Test
    fun `sameReadBatch flag propagates from raw events`() {
        val res = adapter.parse(
            SseFixtures.toRawEvents(SseFixtures.ANTHROPIC_STREAM, sameReadBatchAt = setOf(4))
        )
        // 夹具下标 4 = 第 2 个 content_block_delta（"Claude"）
        assertTrue(res.arrivals[1].sameReadBatch)
        assertTrue(res.arrivals.filterIndexed { i, _ -> i != 1 }.none { it.sameReadBatch })
    }

    @Test
    fun `crlf line endings tolerated`() {
        val crlf = SseFixtures.ANTHROPIC_STREAM.replace("\n", "\r\n")
        // \r\n\r\n 切界后每行末尾带 \r —— splitSseEvent 去 \r
        val events = crlf.split("\r\n\r\n").filter { it.isNotBlank() }.mapIndexed { i, b ->
            com.aneb.probe.net.RawSseEvent(b.toByteArray(), 1_000_000_000L + i * 50_000_000L, false)
        }
        val res = adapter.parse(events)
        assertEquals(6, res.arrivals.size)
        assertEquals(0, res.parseErrors)
    }
}
