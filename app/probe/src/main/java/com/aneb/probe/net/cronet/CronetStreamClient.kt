package com.aneb.probe.net.cronet

import android.content.Context
import android.os.SystemClock
import com.aneb.probe.net.EngineeringCleartextPolicy
import com.aneb.probe.net.RawSseStream
import com.aneb.probe.net.SseBoundaryScanner
import com.aneb.probe.net.SseReader
import com.aneb.probe.net.SseStreamResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.Buffer
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.ExperimentalCronetEngine
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.resume

/**
 * Cronet SSE 流客户端（P2-C05：同 profile TCP(TLS) vs QUIC(h3) 背靠背 A/B，D-17/D-19）。
 *
 * **与 OkHttp 主测量路径的关系**：完全独立——不动 AnebClient/TimingEventListener；
 * A/B 测量流量全部走本类，结果标 stack=cronet。
 *
 * **计时钩子粒度（KDoc 合同，红队"两栈数据不可互比"）**：
 *  - 打戳点＝[UrlRequest.Callback.onReadCompleted] 回调到达（callback executor 单线程），
 *    语义类比 OkHttp 路径的"一次 source.read 一次戳"+sameReadBatch（R-04）：一次
 *    onReadCompleted 视为一次 read，同一回调切出的第 2..n 个 event 标 sameReadBatch。
 *  - 但 Cronet 的回调经过其内部网络线程 → executor 的投递（含跨线程切换开销），且
 *    读块聚合策略与 OkHttp/okio 不同；OkHttp 路径的戳在 EventListener/读线程栈内。
 *    因此 **Cronet 栈与 OkHttp 栈的 TTFT/ITL 绝对值不可直接互比**——A/B 结论只在
 *    Cronet 栈内 TCP vs QUIC 对比得出（两组共享同一打戳语义，栈内可比）。
 *  - TTFT 起点＝[UrlRequest.start] 前的就地打戳（Cronet 无 requestHeadersEnd 级钩子）；
 *    A/B 两组同起点语义，组间可比。
 *
 * **协议判定唯一依据**：逐请求记录 [UrlResponseInfo.getNegotiatedProtocol]（红队
 * "QUIC 启用 ≠ 协商 h3"）——enableQuic(true) 只是允许，h3 与否看逐样本协商结果。
 *
 * **引擎配置**：
 *  - 禁缓存（HTTP_CACHE_DISABLED）：测量流量绝不允许缓存命中；
 *  - [quic]=true 时 addQuicHint(host,port,port)：首请求即尝试 QUIC（不等 Alt-Svc 学习）；
 *  - Cronet 尊重 debug network_security_config 的 <certificates src="@raw/aneb_local_ca"/>
 *    信任锚（本地自签双栈服务端联调路径）——**但仅对 TCP(TLS) 栈**：Chromium 的 QUIC
 *    额外要求服务端证书链到"公共已知根"（ProofVerifierChromium，对应错误码
 *    ERR_QUIC_CERT_ROOT_NOT_KNOWN）。20260713 模拟器 NetLog 实测：QUIC 握手推进到
 *    ENCRYPTION_HANDSHAKE，CERT_VERIFY_PROC 报 cert_status=0（信任锚令证书校验通过）
 *    但 is_issued_by_known_root=false → 客户端以 TLS alert 46 certificate_unknown
 *    主动收连接（from_peer=false），退回 TCP 协商 h2。私有/自签 CA 天然不满足
 *    known-root，NSC 信任锚、把 CA 装进系统根、实验选项 QUIC.origins_to_force_quic_on
 *    /host_whitelist 均无法改写该判定（详见 evidence/phase2/cronet_ab_e2e_20260713.log
 *    的穷尽尝试）。故本地自签联调环境下 B 组恒 fallback；链到公共根（如 Let's Encrypt）
 *    的真实 h3 部署上 B 组才会协商 h3。此处仍设 QUIC 实验选项：对公网已知根部署
 *    origins_to_force_quic_on 是冗余无害项（Cronet 忽略未识别的 QUIC 子键，不抛异常）。
 *  - 代理：Cronet 无 OkHttp Proxy.NO_PROXY 等价开关；D-16 直连红线由上层 NetGuard
 *    guardCheck 硬拒代理环境保证（AbRunner 与场景 run 同口径测前守卫）。
 *
 * **生命周期协议（[close] 与协程取消的契约）**：
 *  - 每个 [streamSse] 请求在 UrlRequest.start 前计入"已发起未终态"计数，仅在 Cronet
 *    终态回调（onSucceeded/onFailed/onCanceled）落地后递减——协程取消只是异步发起
 *    request.cancel()，取消路径立即返回调用方（不阻塞），但请求在引擎内直到 onCanceled
 *    才真正结束；
 *  - [close] 先带超时（[CLOSE_DRAIN_TIMEOUT_MS]）阻塞等待计数归零（全部终态回调落地），
 *    再 engine.shutdown()——否则 shutdown 会因引擎内仍有活跃请求抛 IllegalStateException，
 *    CronetEngine 原生资源泄漏。等待超时后仍尝试 shutdown，失败异常向调用方传播
 *    （AbRunner 记 AB_ENGINE_CLOSE_FAILED），不静默吞；
 *  - close 后本客户端不可复用（engine/executor 均已 shutdown）。
 */
class CronetStreamClient(
    context: Context,
    host: String,
    port: Int,
    /** true=B 组（enableQuic+hint）；false=A 组（disableQuic，TLS 上协商 http/1.1 或 h2） */
    quic: Boolean,
    /** 非 null 时开启 Cronet NetLog 到该文件（debug 诊断用；h3 协商失败归因） */
    netLogPath: String? = null,
) : AutoCloseable {

    private val engine: CronetEngine = ExperimentalCronetEngine.Builder(context.applicationContext)
        .apply {
            enableHttp2(true)
            enableQuic(quic)
            enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISABLED, 0)
            if (quic) {
                // addQuicHint 是实际驱动首请求即尝试 QUIC 的钩子（不等 Alt-Svc 学习）
                addQuicHint(host, port, port)
                // origins_to_force_quic_on/host_whitelist 为 embedder-only 配置键，公开
                // setExperimentalOptions 不识别（NetLog 实测该字段恒 []），对已知根部署
                // 无害冗余、对私有 CA 也无法放行 known-root——保留以文档化尝试边界（见类 KDoc）
                setExperimentalOptions(
                    """{"QUIC":{"origins_to_force_quic_on":"$host:$port","host_whitelist":"$host"}}"""
                )
            }
        }
        .build()
        .also { if (netLogPath != null) it.startNetLogToFile(netLogPath, false) }

    private val netLogging = netLogPath != null

    /** callback executor：单线程——保证 onReadCompleted 串行、扫描器无并发 */
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "aneb-cronet-cb").apply { isDaemon = true }
    }

    private val sseReader = SseReader()

    // ---- "已发起但未终态"请求追踪（见类 KDoc 生命周期协议） ----
    private val inFlightLock = ReentrantLock()
    private val inFlightDrained: Condition = inFlightLock.newCondition()

    /** 已调用 UrlRequest.start 但终态回调（onSucceeded/onFailed/onCanceled）未落地的请求数（guarded by [inFlightLock]） */
    private var inFlightCount = 0

    private fun trackRequestStarted() {
        inFlightLock.withLock { inFlightCount++ }
    }

    private fun trackRequestTerminated() {
        inFlightLock.withLock {
            inFlightCount--
            if (inFlightCount <= 0) inFlightDrained.signalAll()
        }
    }

    /**
     * 阻塞等待全部已发起请求的终态回调落地。
     * @return true=已全部落地；false=超时（引擎内仍有活跃请求，shutdown 会抛）
     */
    private fun awaitTerminalCallbacks(timeoutMs: Long): Boolean {
        var remainNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        inFlightLock.withLock {
            while (inFlightCount > 0) {
                if (remainNanos <= 0L) return false
                remainNanos = try {
                    inFlightDrained.awaitNanos(remainNanos)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return inFlightCount == 0
                }
            }
        }
        return true
    }

    /**
     * 一次 Cronet 流请求的原始结果。数值字段失败记 null（R-10）。
     * [stream] 在传输错误时仍保留已收部分（供诊断），但 [error] 非 null 的样本
     * 不得进入 A/B KPI 对比（调用方约束）。
     */
    class Result(
        /** UrlRequest.start 前打戳（elapsedRealtimeNanos）——TTFT 起点 */
        val requestStartNanos: Long,
        /** 逐请求协商协议（"h3"/"h2"/"http/1.1"/...）；未拿到响应头记 null */
        val negotiatedProtocol: String?,
        val httpCode: Int?,
        val stream: SseStreamResult?,
        val error: String?,
    )

    /**
     * GET [url] 并按 SSE 语义收流（Accept: text/event-stream；禁缓存）。
     * 边界扫描与解析复用 OkHttp 路径同一实现（[SseBoundaryScanner]/[SseReader.parseRaw]）。
     * 取消链路：协程取消 → request.cancel()（异步，调用方立即返回）→ onCanceled
     * 终态回调落地才算请求真正结束（fail-closed §4.6/§4.7；close() 依赖该信号等待
     * 引擎静默后才 shutdown，见类 KDoc 生命周期协议）。
     */
    suspend fun streamSse(url: String): Result {
        val parsedUrl = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("invalid Cronet URL")
        EngineeringCleartextPolicy.requireAllowed(parsedUrl, prototypePrivate = false)
        val requestStartNanos = SystemClock.elapsedRealtimeNanos()
        return suspendCancellableCoroutine { cont ->
            val scanner = SseBoundaryScanner()
            val copyBuf = Buffer()

            val callback = object : UrlRequest.Callback() {
                private var info: UrlResponseInfo? = null

                override fun onRedirectReceived(
                    request: UrlRequest,
                    info: UrlResponseInfo,
                    newLocationUrl: String,
                ) {
                    // 仿真服务端无重定向；出现即异常路径，跟随并留痕于最终 info
                    request.followRedirect()
                }

                override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
                    this.info = info
                    request.read(ByteBuffer.allocateDirect(READ_BUF_BYTES))
                }

                override fun onReadCompleted(
                    request: UrlRequest,
                    info: UrlResponseInfo,
                    byteBuffer: ByteBuffer,
                ) {
                    // 一次 onReadCompleted＝一次 read＝一次戳（回调线程就地打，R-04 类比）
                    val arrivalNanos = SystemClock.elapsedRealtimeNanos()
                    byteBuffer.flip()
                    val n = byteBuffer.remaining()
                    if (n > 0) {
                        val bytes = ByteArray(n)
                        byteBuffer.get(bytes)
                        copyBuf.write(bytes)
                        scanner.onRead(copyBuf, n.toLong(), arrivalNanos)
                    }
                    byteBuffer.clear()
                    request.read(byteBuffer)
                }

                override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
                    try {
                        finish(info, error = null)
                    } finally {
                        trackRequestTerminated()
                    }
                }

                override fun onFailed(
                    request: UrlRequest,
                    info: UrlResponseInfo?,
                    error: CronetException,
                ) {
                    try {
                        finish(info ?: this.info, error = error.toString())
                    } finally {
                        trackRequestTerminated()
                    }
                }

                override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
                    // 协程侧已处于 cancelled 状态；resume 按协程语义被忽略。
                    // 但终态计数必须在此落地：close() 以它为准等待引擎静默（见类 KDoc）
                    trackRequestTerminated()
                }

                private fun finish(info: UrlResponseInfo?, error: String?) {
                    if (cont.isCancelled) return
                    // EOF/错误检出打戳＝解析阶段边界（P0-C12 同款语义）
                    val raw: RawSseStream = scanner.finish(SystemClock.elapsedRealtimeNanos())
                    val parsed = try {
                        if (info != null && info.httpStatusCode in 200..299) sseReader.parseRaw(raw) else null
                    } catch (e: Exception) {
                        return cont.resume(
                            Result(
                                requestStartNanos, info?.negotiatedProtocol, info?.httpStatusCode,
                                null, "parse:${e.javaClass.simpleName}",
                            )
                        )
                    }
                    val httpError = if (info != null && info.httpStatusCode !in 200..299) {
                        "http ${info.httpStatusCode}"
                    } else {
                        null
                    }
                    cont.resume(
                        Result(
                            requestStartNanos = requestStartNanos,
                            negotiatedProtocol = info?.negotiatedProtocol,
                            httpCode = info?.httpStatusCode,
                            stream = parsed,
                            error = error ?: httpError,
                        )
                    )
                }
            }

            val request = engine.newUrlRequestBuilder(url, callback, executor)
                .addHeader("Accept", "text/event-stream")
                .disableCache()
                .build()
            // fail-closed 取消链路：只异步发起 cancel，立即返回调用方（不阻塞取消路径）；
            // 请求真正终止以 onCanceled 终态回调为准，由 close() 等待（见类 KDoc）
            cont.invokeOnCancellation { request.cancel() }
            trackRequestStarted()
            try {
                request.start()
            } catch (e: CancellationException) {
                trackRequestTerminated() // start 未成功：终态回调不会到来
                throw e
            } catch (e: Exception) {
                trackRequestTerminated() // start 同步失败：请求未进入引擎，终态回调不会到来
                if (!cont.isCancelled) {
                    cont.resume(Result(requestStartNanos, null, null, null, e.toString()))
                }
            }
        }
    }

    /**
     * 关闭客户端（生命周期协议见类 KDoc）：
     * 1. 带超时（[CLOSE_DRAIN_TIMEOUT_MS]）等待全部已发起请求的终态回调
     *    （onSucceeded/onFailed/onCanceled）落地——协程取消路径只异步发起
     *    request.cancel()，不等这里就 shutdown 会因引擎内仍有活跃请求抛
     *    IllegalStateException 并泄漏 CronetEngine 原生资源；
     * 2. engine.shutdown()——等待超时后仍尝试，失败异常向调用方传播（不静默吞）；
     * 3. executor.shutdown() + awaitTermination（finally 中执行，engine.shutdown
     *    抛异常也不跳过）。
     */
    override fun close() {
        awaitTerminalCallbacks(CLOSE_DRAIN_TIMEOUT_MS)
        if (netLogging) runCatching { engine.stopNetLog() }
        try {
            engine.shutdown()
        } finally {
            executor.shutdown()
            try {
                executor.awaitTermination(EXECUTOR_TERMINATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private companion object {
        /** 与 OkHttp 路径 READ_CHUNK_BYTES 同量级（8KB） */
        private const val READ_BUF_BYTES = 8192

        /** close() 等待"已发起未终态"请求全部落地的上限（终态回调正常在毫秒级到达） */
        private const val CLOSE_DRAIN_TIMEOUT_MS = 3_000L

        /** callback executor 停机等待上限（此时已无在途回调，仅兜底） */
        private const val EXECUTOR_TERMINATION_TIMEOUT_MS = 1_000L
    }
}
