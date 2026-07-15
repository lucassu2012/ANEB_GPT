package com.aneb.probe.apiprobe

import com.aneb.probe.net.RawSseEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * OpenAI Chat Completions 兼容流式协议适配器（`POST /v1/chat/completions`, stream=true）。
 * Kimi/Moonshot 即此格式（api.moonshot.cn 兼容 OpenAI SDK）。
 *
 * wire 形态（无 event 名，只有 data 行）：
 * ```
 * data: {"id":"...","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}
 *
 * data: {"id":"...","choices":[{"index":0,"delta":{"content":"我是"},"finish_reason":null}]}
 *
 * data: {"id":"...","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":N,"completion_tokens":M,"total_tokens":K}}
 *
 * data: [DONE]
 * ```
 * 计数规则：
 *  - `choices[0].delta.content` 为**非空字符串**才记 token 到达（首帧 role 帧的
 *    content:"" 与结束帧的空 delta 不算——它们不是模型增量，混入会伪造 0/巨大 ITL）；
 *  - `data: [DONE]` 为规范结束哨兵，不算 token、不算错误；
 *  - finish_reason / usage（Kimi 在尾帧携带）提取入结果；
 *  - data JSON 解析失败 → parseErrors++（跳过并计数，R-08 同款）。
 */
class OpenAiSseAdapter(
    private val json: Json = Json { ignoreUnknownKeys = true },
) : LlmStreamAdapter {

    override val protocolId: String = "openai_chat"

    override fun parse(raw: List<RawSseEvent>): LlmParseResult {
        val arrivals = ArrayList<LlmTokenArrival>(raw.size)
        var stopReason: String? = null
        var inputTokens: Int? = null
        var outputTokens: Int? = null
        var parseErrors = 0
        var protocolError: String? = null

        for (ev in raw) {
            val text = ev.bytes.toString(Charsets.UTF_8)
            val (_, data) = splitSseEvent(text)
            if (data == null) continue // 注释/keep-alive 帧：容忍跳过
            if (data == "[DONE]") continue // 规范结束哨兵

            val obj = try {
                json.parseToJsonElement(data).jsonObject
            } catch (e: Exception) {
                parseErrors++
                continue
            }

            // 顶层 error 对象（OpenAI 兼容端点流中报错形态）
            obj["error"]?.let { errEl ->
                protocolError = try {
                    val err = errEl.jsonObject
                    "${err["type"]?.jsonPrimitive?.contentOrNullSafe()}:" +
                        (err["message"]?.jsonPrimitive?.contentOrNullSafe() ?: "")
                } catch (e: Exception) {
                    "error_object_unparseable"
                }
                // error 帧不再当 chunk 解析
            }
            if (protocolError != null && obj["choices"] == null) continue

            val choice0 = try {
                obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            } catch (e: Exception) {
                parseErrors++
                continue
            }
            if (choice0 == null) {
                // 无 choices 且无 error：非本协议已知帧
                if (obj["usage"] != null) {
                    // OpenAI stream_options include_usage 的独立 usage 尾帧
                    extractUsage(obj)?.let { (pin, pout) ->
                        inputTokens = pin ?: inputTokens
                        outputTokens = pout ?: outputTokens
                    }
                } else {
                    parseErrors++
                }
                continue
            }

            // reasoning 模型（如 Moonshot kimi-k2.6）把增量放在 delta.reasoning_content，
            // 正文 content 可能整段为空——对 TTFT/ITL 而言两者都是真实的流式 token 到达，
            // 故任一非空即记到达（优先 content，其次 reasoning_content）。
            val content = try {
                val delta = choice0["delta"]?.jsonObject
                delta?.get("content")?.jsonPrimitive?.contentOrNullSafe().takeUnless { it.isNullOrEmpty() }
                    ?: delta?.get("reasoning_content")?.jsonPrimitive?.contentOrNullSafe()
            } catch (e: Exception) {
                null
            }
            if (!content.isNullOrEmpty()) {
                arrivals.add(
                    LlmTokenArrival(
                        index = arrivals.size,
                        arrivalNanos = ev.arrivalNanos,
                        sameReadBatch = ev.sameReadBatch,
                        textChars = content.length,
                    )
                )
            }
            try {
                choice0["finish_reason"]?.jsonPrimitive?.contentOrNullSafe()?.let { stopReason = it }
            } catch (e: Exception) {
                // finish_reason 非原语：忽略
            }
            extractUsage(obj)?.let { (pin, pout) ->
                inputTokens = pin ?: inputTokens
                outputTokens = pout ?: outputTokens
            }
        }
        return LlmParseResult(arrivals, stopReason, inputTokens, outputTokens, parseErrors, protocolError)
    }

    private fun extractUsage(obj: kotlinx.serialization.json.JsonObject): Pair<Int?, Int?>? = try {
        // Moonshot（api.moonshot.cn）的尾帧有时把 usage 内嵌在 choices[0] 内，
        // 而非 OpenAI 规范的顶层——两处都查（顶层优先），否则 out_tokens 恒为 null。
        val usage = obj["usage"]?.jsonObject
            ?: obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("usage")?.jsonObject
            ?: return null
        val pin = try {
            usage["prompt_tokens"]?.jsonPrimitive?.int
        } catch (e: Exception) {
            null
        }
        val pout = try {
            usage["completion_tokens"]?.jsonPrimitive?.int
        } catch (e: Exception) {
            null
        }
        pin to pout
    } catch (e: Exception) {
        null
    }
}
