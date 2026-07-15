package com.aneb.probe.apiprobe

import com.aneb.probe.net.RawSseEvent

/**
 * 真实 LLM API 流式响应中一次 token 增量（delta）的到达记录。
 *
 * claim scope：`application_end_to_end_to_llm_api`——端到端含 DNS/TLS/代理/服务商排队/
 * 模型推理全部分量，与仿真节点口径（application_end_to_end_to_probe_node，服务端注入
 * 时延可剥离）明确分开；本记录只声明"客户端观测到的到达节奏"，绝不归因网络分量。
 *
 * @param index         该 delta 在流内的序号（0 起，到达顺序；真实 API 无服务端 seq 可 join）
 * @param arrivalNanos  所在 read 块的到达时刻（elapsedRealtimeNanos，R-04 批读打戳）
 * @param sameReadBatch 与前一 event 同一次 read 切出（间隔为伪 0，ITL 剔除，R-04）
 * @param textChars     该 delta 携带的文本字符数（无文本记 0）
 */
data class LlmTokenArrival(
    val index: Int,
    val arrivalNanos: Long,
    val sameReadBatch: Boolean,
    val textChars: Int,
)

/**
 * 协议适配器解析结果。数值缺失一律 null（失败样本语义 R-10，禁 0/哨兵值）。
 *
 * @param arrivals      token delta 到达序列（只含真正携带增量的 event；role 帧/ping/
 *                      开闭帧不算 token）
 * @param stopReason    服务端声明的结束原因（anthropic: stop_reason / openai: finish_reason）
 * @param inputTokens   服务端计数的输入 token 数（usage；缺失 null）
 * @param outputTokens  服务端计数的输出 token 数（usage；缺失 null）
 * @param parseErrors   解析失败被跳过的 event 数（R-08 同款：跳过并计数，绝不静默错位）
 * @param protocolError 协议层错误（如 anthropic `event: error`）；无错误 null
 */
data class LlmParseResult(
    val arrivals: List<LlmTokenArrival>,
    val stopReason: String?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val parseErrors: Int,
    val protocolError: String?,
)

/**
 * LLM 流式协议适配器：把批读打戳层（SseReader.readRaw）输出的原始 SSE event 序列
 * 解析为统一的 [LlmParseResult]。纯函数、无 Android 依赖，JVM 单测直接喂固定夹具。
 */
interface LlmStreamAdapter {
    /** 协议标识（入库/日志/导出），如 anthropic_messages / openai_chat */
    val protocolId: String

    fun parse(raw: List<RawSseEvent>): LlmParseResult
}

/** SSE event 文本的行级拆解（共用小工具）：event 名 + data 行拼接（SSE 规范多 data 行以 \n join）。 */
internal fun splitSseEvent(text: String): Pair<String?, String?> {
    var eventName: String? = null
    val dataLines = ArrayList<String>(2)
    for (rawLine in text.split('\n')) {
        val line = rawLine.removeSuffix("\r")
        when {
            line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
            line.startsWith("data:") -> dataLines.add(line.removePrefix("data:").trim())
        }
    }
    return eventName to (if (dataLines.isEmpty()) null else dataLines.joinToString("\n"))
}
