package com.aneb.probe.apiprobe

import com.aneb.probe.net.RawSseEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Anthropic Messages API 流式协议适配器（`POST /v1/messages`, stream=true）。
 *
 * wire 形态（官方 SSE，每 event 带 event 名）：
 * ```
 * event: message_start
 * data: {"type":"message_start","message":{...,"usage":{"input_tokens":N,...}}}
 *
 * event: content_block_start
 * data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}
 *
 * event: ping
 * data: {"type": "ping"}
 *
 * event: content_block_delta
 * data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"..."}}
 *
 * event: message_delta
 * data: {"type":"message_delta","delta":{"stop_reason":"end_turn",...},"usage":{"output_tokens":N}}
 *
 * event: message_stop
 * data: {"type":"message_stop"}
 * ```
 * 计数规则：
 *  - 仅 `content_block_delta` 记为 token 到达（text_delta 取 text 字符数；thinking/
 *    input_json 等其它 delta 类型同样是到达事件，字符数取 0）；
 *  - message_start/content_block_start/stop/ping/message_stop 不算 token、不算错误；
 *  - `event: error` → protocolError；
 *  - data JSON 解析失败 → parseErrors++（跳过并计数，绝不静默错位，R-08 同款）。
 */
class AnthropicSseAdapter(
    private val json: Json = Json { ignoreUnknownKeys = true },
) : LlmStreamAdapter {

    override val protocolId: String = "anthropic_messages"

    override fun parse(raw: List<RawSseEvent>): LlmParseResult {
        val arrivals = ArrayList<LlmTokenArrival>(raw.size)
        var stopReason: String? = null
        var inputTokens: Int? = null
        var outputTokens: Int? = null
        var parseErrors = 0
        var protocolError: String? = null

        for (ev in raw) {
            val text = ev.bytes.toString(Charsets.UTF_8)
            val (eventName, data) = splitSseEvent(text)
            if (data == null) {
                // 纯注释/空 event：容忍跳过（Anthropic 不发注释帧，但代理可能注入 keep-alive）
                continue
            }
            val obj = try {
                json.parseToJsonElement(data).jsonObject
            } catch (e: Exception) {
                parseErrors++
                continue
            }
            // event 名缺失时退回 data.type（防中间盒剥 event 行）
            when (eventName ?: obj["type"]?.jsonPrimitive?.contentOrNullSafe()) {
                "content_block_delta" -> {
                    val delta = try {
                        obj["delta"]?.jsonObject
                    } catch (e: Exception) {
                        null
                    }
                    if (delta == null) {
                        parseErrors++
                        continue
                    }
                    val chars = delta["text"]?.jsonPrimitive?.contentOrNullSafe()?.length ?: 0
                    arrivals.add(
                        LlmTokenArrival(
                            index = arrivals.size,
                            arrivalNanos = ev.arrivalNanos,
                            sameReadBatch = ev.sameReadBatch,
                            textChars = chars,
                        )
                    )
                }

                "message_start" -> {
                    inputTokens = try {
                        obj["message"]?.jsonObject?.get("usage")?.jsonObject
                            ?.get("input_tokens")?.jsonPrimitive?.int
                    } catch (e: Exception) {
                        null
                    }
                }

                "message_delta" -> {
                    stopReason = try {
                        obj["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.contentOrNullSafe()
                    } catch (e: Exception) {
                        null
                    } ?: stopReason
                    outputTokens = try {
                        obj["usage"]?.jsonObject?.get("output_tokens")?.jsonPrimitive?.int
                    } catch (e: Exception) {
                        null
                    } ?: outputTokens
                }

                "error" -> {
                    protocolError = try {
                        val err = obj["error"]?.jsonObject
                        "${err?.get("type")?.jsonPrimitive?.contentOrNullSafe()}:" +
                            (err?.get("message")?.jsonPrimitive?.contentOrNullSafe() ?: "")
                    } catch (e: Exception) {
                        "error_event_unparseable"
                    }
                }

                // 已知的非 token 事件：不算错误
                "content_block_start", "content_block_stop", "ping", "message_stop" -> Unit

                else -> parseErrors++
            }
        }
        return LlmParseResult(arrivals, stopReason, inputTokens, outputTokens, parseErrors, protocolError)
    }
}

/** JsonPrimitive.content 的免抛封装（非字符串原语也返回其字面量文本；null 元素返回 null）。 */
internal fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    try {
        if (this is kotlinx.serialization.json.JsonNull) null else content
    } catch (e: Exception) {
        null
    }
