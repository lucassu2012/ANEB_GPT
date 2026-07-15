package com.aneb.probe.apiprobe

import android.content.Context
import android.os.SystemClock
import com.aneb.probe.data.AnebDatabase
import com.aneb.probe.data.ApiProbeResultEntity
import com.aneb.probe.net.NetGuard
import com.aneb.probe.net.SseReader
import com.aneb.probe.net.TimingEventListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 真实 LLM API 探针（阶段 2 任务 #7）：固定短 prompt 测端到端 TTFT/ITL，作仿真节点
 * KPI 的**对照列**。
 *
 * **claim scope＝`application_end_to_end_to_llm_api`**，与仿真口径明确分开；结果绝不
 * 进 AQS、不进 /results 上报（本地 Room + 导出单独归类；若阶段 3 需上报须先扩展服务端
 * 合同，TODO(阶段3)）。
 *
 * **探针流量走系统默认网络（含用户代理）——D-16 豁免说明**：
 * D-16（测量流量直连）约束的是**仿真节点测量**：那条口径要求归因到"终端至指定节点的
 * 网络路径"，代理会把路径换成"终端→代理→节点"导致归因失效，故 AnebClient 钉死
 * Proxy.NO_PROXY 且 NetGuard 测前硬拒代理。而 API 探针的对照列口径本就是"用户真实
 * 使用该 LLM 服务的端到端体验"，用户路径里若有代理（Anthropic 在国内不可直连，代理
 * 是常态），代理耗时就是体验的一部分——**属于被测对象而非污染源**。因此本探针：
 *  - OkHttpClient **不设** Proxy.NO_PROXY（走系统默认，含系统代理）；
 *  - NetGuard.guardCheck 照常执行但**只记元数据不拒测**（proxy_detected/vpn_detected
 *    随结果入库并在展示/导出标注，保证对照列可解释）。
 *
 * **烧钱护栏**（设计文档 §9：真实 API 烧钱且波动大）：固定短 prompt（[PROMPT]）+
 * max_tokens=[MAX_TOKENS] 硬顶；单次手动触发，无自动重试（retryOnConnectionFailure=false）。
 *
 * **隐私红线**：key 只进请求 header；所有出口文本（日志/入库/导出）过 [ApiKeyRedactor]。
 *
 * 日志合同（logcat 自动化验收）：`APIPROBE_RESULT provider=.. protocol=.. http_code=..
 * ttft_ms=.. itl_median_ms=.. itl_p95_ms=.. itl_samples=.. tokens=.. total_ms=..
 * out_tokens=.. stop=.. parse_errors=.. proxy_detected=.. vpn_detected=..
 * claim_scope=application_end_to_end_to_llm_api error=..`
 */
class ApiProbe(private val context: Context) {

    data class Config(
        val provider: LlmProvider,
        val baseUrl: String,
        val model: String,
        val apiKey: String,
    )

    private val timingFactory = TimingEventListener.Factory()
    private val sseReader = SseReader()

    // 系统默认网络（含系统代理）——探针豁免 D-16，理由见类 KDoc；不复用 AnebClient
    // （其 Proxy.NO_PROXY 是仿真测量红线，绝不放宽，故这里独立建 client）。
    private val client: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false) // 重试会掩盖失败且重复烧钱
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS) // 真实 API 排队+推理可远超仿真节点 30s
        .eventListenerFactory(timingFactory)
        .build()

    /**
     * 执行一次探针。永不抛出（除协程取消）：失败以 error 字段入库返回（R-10 语义）。
     *
     * @param log 行日志回调（已保证不含 key）
     */
    suspend fun run(config: Config, log: suspend (String) -> Unit): ApiProbeResultEntity {
        val key = config.apiKey
        fun clean(s: String?): String? = ApiKeyRedactor.redact(s, key)

        // ---- 守卫：只记元数据不拒测（探针豁免，见类 KDoc） ----
        val guard = NetGuard.guardCheck(context)
        val proxyDetected = guard.reasons.any { it.startsWith("http_proxy") }
        val vpnDetected = guard.reasons.any { it.startsWith("vpn_active") }
        val guardMeta = clean(
            (guard.metadata.entries.joinToString(";") { "${it.key}=${it.value}" } +
                if (guard.reasons.isEmpty()) "" else ";reasons=" + guard.reasons.joinToString(","))
        )
        log(
            "APIPROBE_GUARD proxy_detected=$proxyDetected vpn_detected=$vpnDetected " +
                "note=recorded_not_rejected(probe_exemption)"
        )

        val adapter: LlmStreamAdapter = when (config.provider) {
            LlmProvider.ANTHROPIC -> AnthropicSseAdapter()
            LlmProvider.OPENAI_COMPAT -> OpenAiSseAdapter()
        }
        val base = config.baseUrl.trim().trimEnd('/')
        val url = base + endpointPath(config.provider)
        val bodyJson = requestBodyJson(config.provider, config.model)
        val requestBuilder = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .header("Accept", "text/event-stream")
        when (config.provider) {
            LlmProvider.ANTHROPIC -> {
                requestBuilder.header("x-api-key", key)
                requestBuilder.header("anthropic-version", ANTHROPIC_VERSION)
            }
            LlmProvider.OPENAI_COMPAT -> requestBuilder.header("Authorization", "Bearer $key")
        }
        val call = client.newCall(requestBuilder.build())

        val startedAtEpochMs = System.currentTimeMillis()
        val requestStartNanos = SystemClock.elapsedRealtimeNanos()
        log("APIPROBE_START provider=${config.provider.id} protocol=${adapter.protocolId} model=${config.model}")

        var httpCode: Int? = null
        var error: String? = null
        var parse: LlmParseResult? = null
        var kpis: ApiProbeKpi.Kpis? = null
        var readCount: Int? = null
        var totalBytes: Long? = null
        try {
            executeCancellable(call) { resp ->
                httpCode = resp.code
                if (!resp.isSuccessful) {
                    // 错误 body 截断 300 字符（含服务商错误说明），过 redactor 后入库
                    val bodyHead = try {
                        resp.body?.string()?.take(300)
                    } catch (e: Exception) {
                        null
                    }
                    error = "http ${resp.code}" + (bodyHead?.let { " $it" } ?: "")
                } else {
                    val raw = sseReader.readRaw(checkNotNull(resp.body) { "empty body for 2xx" }.source())
                    readCount = raw.readCount
                    totalBytes = raw.totalBytes
                    val p = adapter.parse(raw.events)
                    parse = p
                    kpis = ApiProbeKpi.compute(requestStartNanos, p.arrivals, raw.eofNanos)
                    if (raw.truncatedTail) {
                        error = "truncated_tail"
                    }
                }
            }
        } catch (e: CancellationException) {
            timingFactory.recordFor(call) // 取走防泄漏（Factory 每 call 一份记录）
            throw e // 不吞取消（fail-closed §4.6/§4.7）
        } catch (e: Exception) {
            error = clean(e.toString())
        }
        // 传输层计时点（DNS/TLS/TTFB 分量，辅助诊断；取走即移除防泄漏）
        timingFactory.recordFor(call)?.let { log("APIPROBE_TIMING ${it.summarize().replace(' ', '_')}") }

        val k = kpis
        val p = parse
        val entity = ApiProbeResultEntity(
            startedAtEpochMs = startedAtEpochMs,
            provider = config.provider.id,
            protocolId = adapter.protocolId,
            baseUrl = clean(base) ?: base,
            model = config.model,
            claimScope = CLAIM_SCOPE,
            httpCode = httpCode,
            error = clean(error),
            ttftMs = k?.ttftMs,
            itlMedianMs = k?.itlMedianMs,
            itlP95Ms = k?.itlP95Ms,
            itlSampleCount = k?.itlSampleCount ?: 0,
            tokenEventCount = k?.tokenEventCount ?: 0,
            totalMs = k?.totalMs,
            totalTextChars = k?.totalTextChars ?: 0,
            inputTokens = p?.inputTokens,
            outputTokens = p?.outputTokens,
            stopReason = p?.stopReason,
            parseErrors = p?.parseErrors ?: 0,
            protocolError = clean(p?.protocolError),
            proxyDetected = proxyDetected,
            vpnDetected = vpnDetected,
            guardMetadata = guardMeta,
            readCount = readCount,
            totalBytes = totalBytes,
        )
        AnebDatabase.get(context).apiProbeResultDao().insert(entity)

        fun fmt(v: Double?): String = if (v == null) "null" else "%.2f".format(v)
        log(
            "APIPROBE_RESULT provider=${entity.provider} protocol=${entity.protocolId} " +
                "http_code=${entity.httpCode ?: "null"} ttft_ms=${fmt(entity.ttftMs)} " +
                "itl_median_ms=${fmt(entity.itlMedianMs)} itl_p95_ms=${fmt(entity.itlP95Ms)} " +
                "itl_samples=${entity.itlSampleCount} tokens=${entity.tokenEventCount} " +
                "total_ms=${fmt(entity.totalMs)} out_tokens=${entity.outputTokens ?: "null"} " +
                "stop=${entity.stopReason ?: "null"} parse_errors=${entity.parseErrors} " +
                "proxy_detected=${entity.proxyDetected} vpn_detected=${entity.vpnDetected} " +
                "claim_scope=${entity.claimScope} " +
                "error=${entity.error?.replace(' ', '_') ?: "none"}"
        )
        return entity
    }

    /** 同 AnebClient.executeCancellable：取消链路覆盖建连到读完全程（fail-closed）。 */
    private suspend fun <T> executeCancellable(call: Call, consume: (Response) -> T): T =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isCancelled) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use(consume)
                    } catch (e: Exception) {
                        if (!cont.isCancelled) cont.resumeWithException(e)
                        return
                    }
                    if (!cont.isCancelled) cont.resume(result)
                }
            })
        }

    companion object {
        const val CLAIM_SCOPE = ApiProbeReport.CLAIM_SCOPE

        /** 固定短 prompt（烧钱护栏：短输入 + 输出硬顶） */
        const val PROMPT = "用一句话介绍你自己"

        /** max_tokens 硬顶（防烧钱，设计文档 §9） */
        const val MAX_TOKENS = 128

        const val ANTHROPIC_VERSION = "2023-06-01"

        /**
         * 端点路径拼接约定（与 base URL 分工，勿混）：
         *  - **OpenAI 兼容**：base **含版本段**（如 `.../v1`、`.../api/paas/v4`），本函数只
         *    拼 `/chat/completions`。故最终 URL = `{base}/chat/completions`。
         *  - **Anthropic**：base **不含版本**（`https://api.anthropic.com`），本函数拼
         *    `/v1/messages`。
         *
         * 本改动对现有 Kimi 默认路径 **URL 等价、零行为变化**：旧 `moonshot.cn` +
         * `/v1/chat/completions` == 新 `moonshot.cn/v1` + `/chat/completions`。base 侧统一
         * 含版本段后，各家预置（deepseek/glm/qwen…）不再各自漂移版本段，单测锚定见
         * ProviderPresetUrlTest。
         */
        fun endpointPath(provider: LlmProvider): String = when (provider) {
            LlmProvider.ANTHROPIC -> "/v1/messages"
            LlmProvider.OPENAI_COMPAT -> "/chat/completions"
        }

        /** 请求体构造（纯函数，单测锚定 max_tokens 硬顶与固定 prompt）。 */
        fun requestBodyJson(provider: LlmProvider, model: String): String = when (provider) {
            LlmProvider.ANTHROPIC ->
                """{"model":${jsonStr(model)},"max_tokens":$MAX_TOKENS,"stream":true,""" +
                    """"messages":[{"role":"user","content":${jsonStr(PROMPT)}}]}"""

            // 不带 temperature：各 OpenAI 兼容服务商约束不一（如 Moonshot kimi-k2.6 仅接受
            // temperature=1，显式传 0 会 400 invalid_temperature），延迟探针不依赖确定性输出，
            // 走服务端默认值兼容面最大。
            LlmProvider.OPENAI_COMPAT ->
                """{"model":${jsonStr(model)},"max_tokens":$MAX_TOKENS,"stream":true,""" +
                    """"messages":[{"role":"user","content":${jsonStr(PROMPT)}}]}"""
        }

        private fun jsonStr(s: String): String = buildString {
            append('"')
            for (c in s) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                }
            }
            append('"')
        }
    }
}
