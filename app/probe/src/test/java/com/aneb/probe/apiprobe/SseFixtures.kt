package com.aneb.probe.apiprobe

import com.aneb.probe.net.RawSseEvent

/**
 * 夹具工具：把 SSE 流原文按 `\n\n` 切 event（同 SseReader.readRaw 的边界规则），
 * 按给定到达时刻打戳，构造批读打戳层输出供适配器单测。
 */
object SseFixtures {

    /**
     * @param arrivalsNanos 每 event 到达时刻；少于 event 数时按最后值+50ms 递推
     * @param sameReadBatchAt 标 sameReadBatch=true 的 event 下标集合
     */
    fun toRawEvents(
        streamText: String,
        arrivalsNanos: List<Long> = emptyList(),
        sameReadBatchAt: Set<Int> = emptySet(),
    ): List<RawSseEvent> {
        val blocks = streamText.split("\n\n").filter { it.isNotBlank() }
        return blocks.mapIndexed { i, block ->
            val arrival = when {
                i < arrivalsNanos.size -> arrivalsNanos[i]
                arrivalsNanos.isNotEmpty() -> arrivalsNanos.last() + (i - arrivalsNanos.size + 1) * 50_000_000L
                else -> 1_000_000_000L + i * 50_000_000L
            }
            RawSseEvent(
                bytes = block.toByteArray(Charsets.UTF_8),
                arrivalNanos = arrival,
                sameReadBatch = i in sameReadBatchAt,
            )
        }
    }

    /** Anthropic Messages API 流式响应真实格式样本（内容为固定 prompt 的合成回答）。 */
    val ANTHROPIC_STREAM = """
event: message_start
data: {"type":"message_start","message":{"id":"msg_014p7gG3wDgGV9EUtLvnow3U","type":"message","role":"assistant","model":"claude-3-5-haiku-latest","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":12,"output_tokens":1}}}

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

event: ping
data: {"type": "ping"}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"我是"}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Claude"}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"，一个由"}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Anthropic"}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"开发的"}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"AI助手。"}}

event: content_block_stop
data: {"type":"content_block_stop","index":0}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":21}}

event: message_stop
data: {"type":"message_stop"}
""".trimIndent()

    /** OpenAI Chat Completions 兼容（Kimi/Moonshot 同款）流式响应真实格式样本。 */
    val OPENAI_STREAM = """
data: {"id":"chatcmpl-mock-1","object":"chat.completion.chunk","created":1770000000,"model":"moonshot-v1-8k","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}

data: {"id":"chatcmpl-mock-1","object":"chat.completion.chunk","created":1770000000,"model":"moonshot-v1-8k","choices":[{"index":0,"delta":{"content":"我是"},"finish_reason":null}]}

data: {"id":"chatcmpl-mock-1","object":"chat.completion.chunk","created":1770000000,"model":"moonshot-v1-8k","choices":[{"index":0,"delta":{"content":"Kimi"},"finish_reason":null}]}

data: {"id":"chatcmpl-mock-1","object":"chat.completion.chunk","created":1770000000,"model":"moonshot-v1-8k","choices":[{"index":0,"delta":{"content":"，月之暗面"},"finish_reason":null}]}

data: {"id":"chatcmpl-mock-1","object":"chat.completion.chunk","created":1770000000,"model":"moonshot-v1-8k","choices":[{"index":0,"delta":{"content":"的AI助手"},"finish_reason":null}]}

data: {"id":"chatcmpl-mock-1","object":"chat.completion.chunk","created":1770000000,"model":"moonshot-v1-8k","choices":[{"index":0,"delta":{"content":"。"},"finish_reason":null}]}

data: {"id":"chatcmpl-mock-1","object":"chat.completion.chunk","created":1770000000,"model":"moonshot-v1-8k","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":18,"total_tokens":30}}

data: [DONE]
""".trimIndent()
}
