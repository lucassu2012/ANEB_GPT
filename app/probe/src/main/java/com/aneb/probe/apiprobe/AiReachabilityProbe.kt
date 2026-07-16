package com.aneb.probe.apiprobe

import android.util.Log
import com.aneb.probe.net.TimingEventListener
import com.aneb.probe.net.TimingRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

/**
 * AI 业务**应用可达性看板**探针（mode① 无 key 连接层探测）。
 *
 * 目标：对每个 [ProviderPreset] 的 [ProviderPreset.baseUrl] 根发一次**无 key、无 body**
 * 的 GET，只判断"能否完成完整 TLS 握手 / 拿到任意 HTTP 响应"——拿到任意 HTTP 响应即
 * 判 [Status.OK]（TLS 握手完成、路径可达），**不看 2xx/4xx 语义**。这是**连接层口径**，
 * 明确**不测 TTFT**、**不进 AQS**（[CLAIM_SCOPE] = `application_reachability_tls_no_key`）。
 *
 * 与 [ApiProbe] 的区别：ApiProbe 是带 key 的端到端 TTFT/ITL 对照列（烧钱、需 key）；本探针
 * 是"这些 AI 服务在当前网络下能不能连上"的看板，**无 key（安全，绝不触碰 [ApiKeyStore]）**、
 * 短超时、best-effort。
 *
 * 分类（[Status]，只看 TLS/连接层）：
 *  - [Status.OK]：拿到任何 HTTP 响应（记 httpCode）；
 *  - [Status.RST]：[SSLHandshakeException] 或连接被重置（message 含 "reset"）——SNI-RST 特征；
 *  - [Status.TIMEOUT]：[SocketTimeoutException] / 连接超时；
 *  - [Status.DNS_FAIL]：[UnknownHostException]（DNS 解析失败）；
 *  - [Status.ERROR]：其它 [IOException]（note 记 `error:<简名>`）；
 *  - [Status.UNPROBED]：未探测（保留，本实现不主动产生）。
 *
 * TLS 握手耗时经 [TimingEventListener]（`secureConnectEnd - secureConnectStart`）；取不到记
 * null（R-10：绝不写 0 或哨兵）。connect 耗时同理（`connectEnd - connectStart`）。
 *
 * 边界：短超时（connect/read 各 8s）、`retryOnConnectionFailure(false)`、best-effort——除
 * [CancellationException]（fail-closed，重新抛）外**绝不抛**。key 无涉（本探针从不读取/发送
 * 任何 key，日志/结果均无 key 字段）。
 *
 * 日志合同（logcat 自动化验收，tag 与 `APIPROBE_*` 同为 `AnebProbe`）：
 *  - 每家：`AIREACH_RESULT preset=.. host=.. status=.. tls_ms=.. http_code=..`；
 *  - 收尾：`AIREACH_RUN_END count=.. ok=.. rst=..`。
 */
class AiReachabilityProbe {

    enum class Status { OK, RST, TIMEOUT, DNS_FAIL, ERROR, UNPROBED }

    data class Result(
        val presetId: String,
        val displayName: String,
        val host: String,
        val status: Status,
        /** TLS 握手耗时 ms（secureConnectEnd-Start）；取不到 null（R-10 禁 0）。 */
        val tlsHandshakeMs: Long?,
        /** connect 建连耗时 ms（connectEnd-Start）；取不到 null。 */
        val connectMs: Long?,
        /** OK 时的 HTTP 状态码（不判语义，仅证明拿到响应）；失败为 null。 */
        val httpCode: Int?,
        /** 承接自预置：端点/协议是否已官方核实（UI 对 false 显式提示以官方文档为准）。 */
        val verified: Boolean,
        /** 诊断备注（失败记异常简名，ERROR 记 `error:<简名>`）；OK 为 null。 */
        val note: String?,
    )

    private val timingFactory = TimingEventListener.Factory()

    // 无 key、短超时、不重试；不复用 AnebClient（其 Proxy.NO_PROXY 是仿真测量红线）。
    // 可达性看板走系统默认网络即可（用户真实网络下能否连上这些 AI 服务）。
    private val client: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .eventListenerFactory(timingFactory)
        .build()

    /**
     * 限流并发（[MAX_CONCURRENCY] 家同时在飞）探测全部预置——总耗时较串行 ~70s 收窄到 ~20s。
     * best-effort：单家异常被就地分类为失败结果，绝不冒泡（除 [CancellationException] 重新抛，
     * fail-closed）。每家探完立即回调 [onResult]（在 [Mutex] 内与该家 `AIREACH_RESULT` 日志一起
     * 串行化，避免并发 UI 写，让看板逐条亮起）。AIREACH_RESULT/AIREACH_RUN_END 日志合同不变
     * （仅行序变为完成序，KEY/字段/计数一致）。全程无 key。
     *
     * @param presets 待探测预置。
     * @param onResult 每家探完的增量回调（可空、串行化调用）；供看板逐条就地更新。
     */
    suspend fun probeAll(
        presets: List<ProviderPreset>,
        onResult: (suspend (Result) -> Unit)? = null,
    ): List<Result> = coroutineScope {
        val gate = Semaphore(MAX_CONCURRENCY)
        val emit = Mutex() // 串行化每家的"日志 + onResult"，避免并发 UI 写
        val results = presets.map { preset ->
            async {
                val r = gate.withPermit { probeOne(preset) }
                emit.withLock {
                    Log.i(
                        TAG,
                        "AIREACH_RESULT preset=${r.presetId} host=${r.host} status=${r.status} " +
                            "tls_ms=${r.tlsHandshakeMs ?: "null"} http_code=${r.httpCode ?: "null"}",
                    )
                    onResult?.invoke(r)
                }
                r
            }
        }.awaitAll()
        val ok = results.count { it.status == Status.OK }
        val rst = results.count { it.status == Status.RST }
        Log.i(TAG, "AIREACH_RUN_END count=${results.size} ok=$ok rst=$rst")
        results
    }

    /** 对单个预置的 baseUrl 根发一次无 key GET 并分类。绝不抛（除 [CancellationException]）。 */
    private suspend fun probeOne(preset: ProviderPreset): Result = withContext(Dispatchers.IO) {
        val url = preset.baseUrl.trim().trimEnd('/')
        val parsed = url.toHttpUrlOrNull()
        val host = parsed?.host ?: hostOf(url)
        if (parsed == null) {
            return@withContext Result(
                presetId = preset.id, displayName = preset.displayName, host = host,
                status = Status.ERROR, tlsHandshakeMs = null, connectMs = null,
                httpCode = null, verified = preset.verified, note = "error:bad_url",
            )
        }
        val call = client.newCall(Request.Builder().url(parsed).get().build())
        try {
            call.execute().use { resp ->
                resp.body?.close()
                val timing = timingFactory.recordFor(call) // 取走即移除，防泄漏
                // 拿到任意 HTTP 响应＝TLS 握手完成、可达（不看 2xx/4xx 语义）。
                Result(
                    presetId = preset.id, displayName = preset.displayName, host = host,
                    status = Status.OK, tlsHandshakeMs = tlsHandshakeMs(timing),
                    connectMs = connectMs(timing), httpCode = resp.code,
                    verified = preset.verified, note = null,
                )
            }
        } catch (e: CancellationException) {
            timingFactory.recordFor(call) // 取走防泄漏
            throw e // fail-closed：不吞取消
        } catch (e: Exception) {
            val timing = timingFactory.recordFor(call)
            val status = classify(e)
            val note = if (status == Status.ERROR) {
                "error:${e.javaClass.simpleName}"
            } else {
                e.javaClass.simpleName
            }
            Result(
                presetId = preset.id, displayName = preset.displayName, host = host,
                status = status, tlsHandshakeMs = tlsHandshakeMs(timing),
                connectMs = connectMs(timing), httpCode = null,
                verified = preset.verified, note = note,
            )
        }
    }

    /** TLS 握手耗时（secureConnectEnd-Start）；任一端点缺失或异常回退 null（R-10 禁 0 哨兵）。 */
    private fun tlsHandshakeMs(t: TimingRecord?): Long? {
        val start = t?.secureConnectStartNs ?: return null
        val end = t.secureConnectEndNs ?: return null
        val ms = (end - start) / 1_000_000L
        return if (ms >= 0) ms else null
    }

    /** connect 建连耗时（connectEnd-Start）；缺失回退 null。 */
    private fun connectMs(t: TimingRecord?): Long? {
        val start = t?.connectStartNs ?: return null
        val end = t.connectEndNs ?: return null
        val ms = (end - start) / 1_000_000L
        return if (ms >= 0) ms else null
    }

    companion object {
        /** 可达性看板的口径标签：连接层、无 key、明确不进 AQS、不测 TTFT。 */
        const val CLAIM_SCOPE = "application_reachability_tls_no_key"

        /** 可达性探测的最大并发度（限流，避免同时打满系统连接/线程池）。 */
        private const val MAX_CONCURRENCY = 4

        private const val TAG = "AnebProbe"

        /**
         * 异常→[Status] 的纯判定（供单测锚定；与 [com.aneb.probe.net.ReachabilityProbe] 同风格）。
         * 顺序敏感：具体子类（SSL/超时/DNS）先于泛化 [IOException]（三者均 extends IOException）。
         */
        internal fun classify(e: Throwable): Status = when {
            e is SSLHandshakeException -> Status.RST // SNI-keyed TLS RST 常表现为握手异常
            e is UnknownHostException -> Status.DNS_FAIL
            e is SocketTimeoutException -> Status.TIMEOUT
            e is IOException -> {
                val msg = e.message ?: ""
                when {
                    msg.contains("reset", ignoreCase = true) -> Status.RST
                    msg.contains("timeout", ignoreCase = true) ||
                        msg.contains("timed out", ignoreCase = true) -> Status.TIMEOUT
                    else -> Status.ERROR
                }
            }
            else -> Status.ERROR
        }

        /** baseUrl 解析失败时的 host 兜底提取（scheme://host[:port]/...）。 */
        private fun hostOf(url: String): String {
            val rest = url.substringAfter("://", missingDelimiterValue = url)
            val hostPort = rest.substringBefore('/')
            return hostPort.substringBeforeLast(':', missingDelimiterValue = hostPort)
        }
    }
}
