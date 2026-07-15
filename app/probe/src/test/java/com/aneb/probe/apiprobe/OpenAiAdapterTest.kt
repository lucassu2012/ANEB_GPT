package com.aneb.probe.apiprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OpenAI 兼容（Kimi/Moonshot）SSE 适配器：真实格式夹具解析。 */
class OpenAiAdapterTest {

    private val adapter = OpenAiSseAdapter()

    @Test
    fun `full fixture parses five content deltas`() {
        val res = adapter.parse(SseFixtures.toRawEvents(SseFixtures.OPENAI_STREAM))
        // 首帧 role 帧 content="" 与尾帧空 delta 不算 token
        assertEquals(5, res.arrivals.size)
        assertEquals(0, res.parseErrors)
        assertNull(res.protocolError)
    }

    @Test
    fun `role frame with empty content is not a token`() {
        val res = adapter.parse(SseFixtures.toRawEvents(SseFixtures.OPENAI_STREAM))
        // 第一个 token 到达对应夹具第 2 个 event（下标 1），非 role 帧（下标 0）
        assertEquals(1_000_000_000L + 1 * 50_000_000L, res.arrivals.first().arrivalNanos)
    }

    @Test
    fun `finish_reason stop extracted`() {
        val res = adapter.parse(SseFixtures.toRawEvents(SseFixtures.OPENAI_STREAM))
        assertEquals("stop", res.stopReason)
    }

    @Test
    fun `kimi style usage on final chunk extracted`() {
        val res = adapter.parse(SseFixtures.toRawEvents(SseFixtures.OPENAI_STREAM))
        assertEquals(12, res.inputTokens)
        assertEquals(18, res.outputTokens)
    }

    @Test
    fun `done sentinel not counted as token or error`() {
        val res = adapter.parse(SseFixtures.toRawEvents(SseFixtures.OPENAI_STREAM))
        assertEquals(0, res.parseErrors)
        assertEquals(5, res.arrivals.size)
    }

    @Test
    fun `text chars counted`() {
        val res = adapter.parse(SseFixtures.toRawEvents(SseFixtures.OPENAI_STREAM))
        assertEquals("我是Kimi，月之暗面的AI助手。".length, res.arrivals.sumOf { it.textChars })
    }

    @Test
    fun `malformed chunk counted as parse error and skipped`() {
        val broken = SseFixtures.OPENAI_STREAM.replace(
            """data: {"id":"chatcmpl-mock-1","object":"chat.completion.chunk","created":1770000000,"model":"moonshot-v1-8k","choices":[{"index":0,"delta":{"content":"Kimi"},"finish_reason":null}]}""",
            """data: {"id":"chatcmpl-mock-1","choices":[{"index":0,"delta":""",
        )
        val res = adapter.parse(SseFixtures.toRawEvents(broken))
        assertEquals(4, res.arrivals.size)
        assertEquals(1, res.parseErrors) // 跳过并计数（R-08 同款）
    }

    @Test
    fun `stream error object surfaces protocolError`() {
        val stream = """
data: {"error":{"type":"rate_limit_reached_error","message":"quota exceeded"}}
""".trimIndent()
        val res = adapter.parse(SseFixtures.toRawEvents(stream))
        assertNotNull(res.protocolError)
        assertTrue(res.protocolError!!.contains("rate_limit"))
        assertEquals(0, res.arrivals.size)
        assertEquals(0, res.parseErrors)
    }

    @Test
    fun `openai stream_options separate usage frame extracted`() {
        val stream = SseFixtures.OPENAI_STREAM.replace(
            """data: [DONE]""",
            """data: {"id":"chatcmpl-mock-1","object":"chat.completion.chunk","created":1770000000,"model":"moonshot-v1-8k","usage":{"prompt_tokens":13,"completion_tokens":19,"total_tokens":32}}

data: [DONE]""",
        )
        val res = adapter.parse(SseFixtures.toRawEvents(stream))
        // 独立 usage 尾帧（无 choices）覆盖此前值
        assertEquals(13, res.inputTokens)
        assertEquals(19, res.outputTokens)
        assertEquals(0, res.parseErrors)
    }

    @Test
    fun `moonshot usage nested inside choices0 extracted`() {
        // Moonshot 变体：尾帧把 usage 内嵌在 choices[0] 内（非顶层）——
        // 修复前 extractUsage 只看顶层 obj["usage"]，out_tokens 恒 null。
        val stream = """
data: {"id":"c1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}

data: {"id":"c1","choices":[{"index":0,"delta":{"content":"你好"},"finish_reason":null}]}

data: {"id":"c1","choices":[{"index":0,"delta":{},"finish_reason":"stop","usage":{"prompt_tokens":7,"completion_tokens":11,"total_tokens":18}}]}

data: [DONE]
""".trimIndent()
        val res = adapter.parse(SseFixtures.toRawEvents(stream))
        assertEquals(7, res.inputTokens)
        assertEquals(11, res.outputTokens)
        assertEquals("stop", res.stopReason)
        assertEquals(0, res.parseErrors)
    }

    @Test
    fun `sameReadBatch flag propagates`() {
        val res = adapter.parse(
            SseFixtures.toRawEvents(SseFixtures.OPENAI_STREAM, sameReadBatchAt = setOf(2, 3))
        )
        assertTrue(res.arrivals[1].sameReadBatch)
        assertTrue(res.arrivals[2].sameReadBatch)
        assertTrue(!res.arrivals[0].sameReadBatch)
    }
}
