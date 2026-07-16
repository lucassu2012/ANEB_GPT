package com.aneb.probe.net

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ANEB 仿真服务器客户端（阶段 1 接线）。
 *
 * OkHttp 配置依据设计文档 §5：
 *  - retryOnConnectionFailure(false)：重试会掩盖网络问题；
 *  - connectTimeout 10s / readTimeout 30s；
 *  - eventListenerFactory 注入 [TimingEventListener]（回调线程就地打戳）；
 *  - `proxy(Proxy.NO_PROXY)`：测量流量必须直连（D-16 红线）——即使系统留有
 *    代理配置也绝不让测量请求走代理（NetGuard 已在测前硬拒代理，这里是第二道闸）；
 *  - [bound] 非 null 时同时绑定 socketFactory 与 Dns（R-01：否则域名解析仍走默认
 *    网络 DNS，解析与承载路径分裂）。AUTO 模式传 null＝不绑定仅监控。
 */
class AnebClient(bound: BoundNetwork? = null) {

    private val timingFactory = TimingEventListener.Factory()
    private val json = Json { ignoreUnknownKeys = true }
    private val sseReader = SseReader(json)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .eventListenerFactory(timingFactory)
        .proxy(java.net.Proxy.NO_PROXY) // D-16：测量流量直连，禁走系统代理
        .apply {
            if (bound != null) {
                socketFactory(bound.socketFactory)
                dns(bound.dns)
            }
        }
        .build()

    /**
     * 清空连接池。设计文档 §5：每场景新建连接，消除 TCP/TLS 连接复用导致的 TTFT/T1
     * 不可比。TestEngine 在每次场景运行开始时调用（阶段0验收①"连测 10 次"可比性）。
     */
    fun evictConnections() {
        client.connectionPool.evictAll()
    }

    // ------------------------------------------------------------------ echo

    @Serializable
    private data class EchoWire(
        @SerialName("t1_us") val t1Us: Long,
        @SerialName("t2_us") val t2Us: Long,
        /** 服务端观察到的客户端源 IP:port（路径对账，R-01/R-31） */
        val observed: String? = null,
    )

    /**
     * 一次 /echo 时钟同步样本。时间单位：微秒。
     * t0/t3 为客户端单调钟（elapsedRealtimeNanos/1000）；t1/t2 为服务端单调锚点钟。
     * offset = ((t1-t0)+(t2-t3))/2（服务端钟 − 客户端钟），误差 ±RTT/2（设计文档 §4.2）。
     * 失败样本：t1/t2/offset/rtt 全 null（R-10：绝不记 0）。
     */
    data class EchoResult(
        val t0Us: Long,
        val t1Us: Long?,
        val t2Us: Long?,
        val t3Us: Long?,
        val offsetUs: Long?,
        val rttUs: Long?,
        val httpCode: Int?,
        val error: String?,
        val timing: TimingRecord?,
        /** 服务端观察到的客户端源 IP:port（每场景网络快照的路径对账字段，R-01/R-31） */
        val observed: String? = null,
    )

    suspend fun echo(url: String): EchoResult {
        val body = "{\"probe\":\"aneb\"}"
            .toRequestBody("application/json".toMediaType())
        val call = client.newCall(Request.Builder().url(url).post(body).build())
        val t0Us = nowUs()
        return try {
            executeCancellable(call) { resp ->
                // t3 打戳点＝收到响应头回调（与原 execute() 返回点同语义）
                val t3Us = nowUs()
                val timing = timingFactory.recordFor(call)
                if (!resp.isSuccessful) {
                    EchoResult(t0Us, null, null, t3Us, null, null, resp.code, "http ${resp.code}", timing)
                } else {
                    val wire = json.decodeFromString(
                        EchoWire.serializer(),
                        checkNotNull(resp.body) { "empty body for 2xx" }.string(),
                    )
                    val offsetUs = ((wire.t1Us - t0Us) + (wire.t2Us - t3Us)) / 2
                    val rttUs = (t3Us - t0Us) - (wire.t2Us - wire.t1Us)
                    EchoResult(
                        t0Us, wire.t1Us, wire.t2Us, t3Us, offsetUs, rttUs,
                        resp.code, null, timing, observed = wire.observed,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e // 不吞取消（fail-closed §4.6/§4.7）
        } catch (e: Exception) {
            EchoResult(t0Us, null, null, null, null, null, null, e.toString(), timingFactory.recordFor(call))
        }
    }

    // ---------------------------------------------------------------- stream

    /**
     * S1 流式结果。gap/duplicate 由 seq join 校验（R-08）：
     * gapCount = [0..maxSeq] 中缺失的 seq 数 + 尾部截断缺失数（见 [StreamResult.truncatedEarly]）；
     * gap 超过 token 总数 1% 由上层判 invalid。
     */
    data class StreamResult(
        val requestStartNanos: Long,
        val stream: SseStreamResult?,
        val gapCount: Int,
        val duplicateCount: Int,
        val maxSeq: Long?,
        val httpCode: Int?,
        val error: String?,
        val timing: TimingRecord?,
        /**
         * 流无 HTTP 错误、无异常地"干净结束"，但收到的 token 总量 < expectedTokens：
         * 服务端/中间盒提前正常关闭连接导致的尾部整体截断（R-08 漏检分支——区间内部
         * 连续性检查测不出 [maxSeq+1, expectedTokens) 的整体缺失）。
         * 缺失数已计入 [gapCount]，参与上层 >1% invalid 判定。
         */
        val truncatedEarly: Boolean,
    )

    /**
     * 通用 SSE 流阶段执行（S1/S2/S3 的 token_stream phase 共用）。
     *
     * @param expectedTokens 调用方（ScenarioRunner）期望的 token 总数（profile 的 tokens 参数），
     *        用于尾部截断检测；seq 从 0 起，完整流应收到 seq ∈ [0, expectedTokens)。
     */
    suspend fun stream(
        url: String,
        expectedTokens: Int,
        /** SSE 边界到达只读观察；仅供实时 UI，不能解析/阻塞/参与 KPI。 */
        onEventArrival: ((Long) -> Unit)? = null,
    ): StreamResult {
        val call = client.newCall(
            Request.Builder().url(url).header("Accept", "text/event-stream").get().build()
        )
        val requestStartNanos = SystemClock.elapsedRealtimeNanos()
        return try {
            // SSE body 的流式读取整体放在 executeCancellable 的 onResponse 回调内完成
            // （resume 前不关闭 body）：invokeOnCancellation 覆盖从建连到读完的全程，
            // 协程取消 → call.cancel() → 读循环以 IOException 退出（fail-closed §4.6/§4.7）。
            executeCancellable(call) { resp ->
                if (!resp.isSuccessful) {
                    StreamResult(
                        requestStartNanos, null, 0, 0, null,
                        resp.code, "http ${resp.code}", timingFactory.recordFor(call),
                        truncatedEarly = false,
                    )
                } else {
                    val stream = sseReader.readStream(
                        checkNotNull(resp.body) { "empty body for 2xx" }.source(),
                        onEventArrival = onEventArrival,
                    )
                    val timing = timingFactory.recordFor(call)

                    // R-08：按 seq join 校验连续性，禁数组位置配对
                    val seen = HashSet<Long>(stream.events.size * 2)
                    var duplicates = 0
                    for (e in stream.events) {
                        if (!seen.add(e.seq)) duplicates++
                    }
                    val maxSeq = seen.maxOrNull()
                    var gaps = 0
                    if (maxSeq != null) {
                        var s = 0L
                        while (s <= maxSeq) {
                            if (s !in seen) gaps++
                            s++
                        }
                    }
                    // R-08 截断漏检补丁：流干净结束但 maxSeq+1 < expectedTokens 时，
                    // 尾部整体缺失也计入 gapCount（否则 gapVerdict 会误判 ok）。
                    val received = maxSeq?.plus(1L) ?: 0L
                    val tailMissing = (expectedTokens - received).coerceAtLeast(0L).toInt()
                    gaps += tailMissing
                    StreamResult(
                        requestStartNanos, stream, gaps, duplicates, maxSeq, resp.code, null, timing,
                        truncatedEarly = tailMissing > 0,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e // 不吞取消（fail-closed §4.6/§4.7）
        } catch (e: Exception) {
            StreamResult(
                requestStartNanos, null, 0, 0, null, null, e.toString(),
                timingFactory.recordFor(call), truncatedEarly = false,
            )
        }
    }

    // ------------------------------------------------------ basic throughput

    /** 基本性能模式的单条应用层传输结果；吞吐由上层按多个并发结果聚合。 */
    data class TransferResult(
        val startNanos: Long,
        val endNanos: Long?,
        val totalBytes: Long,
        val httpCode: Int?,
        val error: String?,
        val timing: TimingRecord?,
    )

    /**
     * 流式读取大对象下载。每次 body read 后调用 [onBytes]；回调必须常数时间、无阻塞，
     * 只供独立遥测采样统计，不参与最终 HTTP 成功判定。
     */
    suspend fun downloadThroughput(
        url: String,
        onBytes: (byteCount: Int, arrivalNanos: Long) -> Unit,
    ): TransferResult {
        val call = client.newCall(
            Request.Builder()
                .url(url)
                .header("Accept", "application/octet-stream")
                .header("Accept-Encoding", "identity")
                .get()
                .build(),
        )
        val startNanos = SystemClock.elapsedRealtimeNanos()
        return try {
            executeCancellable(call) { resp ->
                val timing = timingFactory.recordFor(call)
                if (!resp.isSuccessful) {
                    resp.body?.string()
                    TransferResult(startNanos, null, 0L, resp.code, "http ${resp.code}", timing)
                } else {
                    val source = checkNotNull(resp.body) { "empty body for 2xx" }.source()
                    val sink = Buffer()
                    var total = 0L
                    while (true) {
                        val n = source.read(sink, THROUGHPUT_READ_BYTES)
                        if (n == -1L) break
                        sink.skip(n)
                        total += n
                        onBytes(n.toInt(), SystemClock.elapsedRealtimeNanos())
                    }
                    TransferResult(
                        startNanos = startNanos,
                        endNanos = SystemClock.elapsedRealtimeNanos(),
                        totalBytes = total,
                        httpCode = resp.code,
                        error = null,
                        timing = timing,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TransferResult(startNanos, null, 0L, null, e.toString(), timingFactory.recordFor(call))
        }
    }

    /**
     * 流式写入固定长度大对象上传。动态回调表示应用写入 OkHttp 网络 sink 的字节，
     * 最终 goodput 仍以上层“成功响应的传输字节/阶段时长”口径收束。
     */
    suspend fun uploadThroughput(
        url: String,
        totalBytes: Long,
        chunkBytes: Int,
        onBytes: (byteCount: Int, writtenAtNanos: Long) -> Unit,
    ): TransferResult {
        require(totalBytes > 0L) { "totalBytes must be positive" }
        require(chunkBytes > 0) { "chunkBytes must be positive" }
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength(): Long = totalBytes

            override fun writeTo(sink: BufferedSink) {
                val chunk = ByteArray(chunkBytes) { index -> ((index * 31 + 17) and 0xff).toByte() }
                var remaining = totalBytes
                while (remaining > 0L) {
                    val n = minOf(chunk.size.toLong(), remaining).toInt()
                    sink.write(chunk, 0, n)
                    onBytes(n, SystemClock.elapsedRealtimeNanos())
                    remaining -= n
                }
                sink.flush()
            }
        }
        val call = client.newCall(Request.Builder().url(url).post(body).build())
        val startNanos = SystemClock.elapsedRealtimeNanos()
        return try {
            executeCancellable(call) { resp ->
                val timing = timingFactory.recordFor(call)
                resp.body?.string()
                TransferResult(
                    startNanos = startNanos,
                    endNanos = SystemClock.elapsedRealtimeNanos(),
                    totalBytes = if (resp.isSuccessful) totalBytes else 0L,
                    httpCode = resp.code,
                    error = if (resp.isSuccessful) null else "http ${resp.code}",
                    timing = timing,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TransferResult(startNanos, null, 0L, null, e.toString(), timingFactory.recordFor(call))
        }
    }

    // ------------------------------------------- continuity stream（阶段 2 C 组）

    /**
     * 连续性实验专用流结果（阶段 2 C 组；additive——不改 [stream] 既有测量语义）。
     * 与 [stream] 的关键差异：**中断容忍**——传输层异常（IOException/流截断）时已收
     * token 的计数与到达时间戳全部保留（C1/C2 的测量对象正是中断本身），并在检出
     * 中断的当下打戳 [errorNanos]（C2 恢复计时的起点）。
     *
     * 时间戳全部为 SystemClock.elapsedRealtimeNanos（单调时间轴，读线程就地打戳）。
     */
    data class ContinuityStreamResult(
        val startNanos: Long,
        /** 首个 token event 到达时刻；一个 token 都没收到记 null（R-10） */
        val firstTokenNanos: Long?,
        /** 最后一个 SSE event 到达时刻（中断兜底锚点）；无 event 记 null */
        val lastEventNanos: Long?,
        val tokenCount: Int,
        val maxSeq: Long?,
        /** 收到 summary event（服务端正常收尾标志） */
        val sawSummary: Boolean,
        val httpCode: Int?,
        val error: String?,
        /** 传输错误检出时刻；无错误记 null */
        val errorNanos: Long?,
        val timing: TimingRecord?,
    ) {
        /** 流干净收尾：无传输错误且收到 summary；否则即"异常断开/截断"（C1 证据） */
        val completed: Boolean get() = error == null && sawSummary

        /** 本请求是否新建连接（EventListener 有 connectStart 打点即新建）；无计时记录 null */
        val connectionWasNew: Boolean? get() = timing?.let { it.connectStartNs != null }
    }

    /**
     * 连续性长流（C1/C2）：增量读 SSE，逐 event 打戳，只做轻量解析（token 计数 /
     * seq 提取 / summary 检测——恢复时间是秒级量，正则开销可忽略）。
     */
    suspend fun continuityStream(url: String): ContinuityStreamResult {
        val call = client.newCall(
            Request.Builder().url(url).header("Accept", "text/event-stream").get().build()
        )
        val startNanos = SystemClock.elapsedRealtimeNanos()
        return try {
            executeCancellable(call) { resp ->
                if (!resp.isSuccessful) {
                    ContinuityStreamResult(
                        startNanos, null, null, 0, null, sawSummary = false,
                        httpCode = resp.code, error = "http ${resp.code}",
                        errorNanos = SystemClock.elapsedRealtimeNanos(),
                        timing = timingFactory.recordFor(call),
                    )
                } else {
                    val source = checkNotNull(resp.body) { "empty body for 2xx" }.source()
                    var firstTokenNanos: Long? = null
                    var lastEventNanos: Long? = null
                    var tokenCount = 0
                    var maxSeq: Long? = null
                    var sawSummary = false
                    var error: String? = null
                    var errorNanos: Long? = null
                    val acc = okio.Buffer()
                    val readBuf = okio.Buffer()
                    try {
                        while (true) {
                            val n = source.read(readBuf, 8192L)
                            if (n == -1L) break
                            val arrival = SystemClock.elapsedRealtimeNanos()
                            acc.writeAll(readBuf)
                            while (true) {
                                val boundary = acc.indexOf(SSE_EVENT_DELIMITER)
                                if (boundary == -1L) break
                                val eventText = acc.readByteArray(boundary).toString(Charsets.UTF_8)
                                acc.skip(SSE_EVENT_DELIMITER.size.toLong())
                                if (eventText.isEmpty()) continue
                                lastEventNanos = arrival
                                when {
                                    eventText.startsWith("event: summary") -> sawSummary = true
                                    eventText.startsWith("event: token") -> {
                                        tokenCount++
                                        if (firstTokenNanos == null) firstTokenNanos = arrival
                                        SEQ_REGEX.find(eventText)?.groupValues?.get(1)
                                            ?.toLongOrNull()?.let { s ->
                                                if (maxSeq == null || s > maxSeq!!) maxSeq = s
                                            }
                                    }
                                    // prelude 注释帧等：连续性实验不做 KPI 级解析，跳过
                                }
                            }
                        }
                    } catch (e: IOException) {
                        // 中断容忍：部分数据保留 + 中断时刻就地打戳（C2 恢复计时起点）
                        error = e.toString()
                        errorNanos = SystemClock.elapsedRealtimeNanos()
                    }
                    ContinuityStreamResult(
                        startNanos, firstTokenNanos, lastEventNanos, tokenCount, maxSeq,
                        sawSummary, resp.code, error, errorNanos, timingFactory.recordFor(call),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e // 不吞取消（fail-closed §4.6/§4.7）
        } catch (e: Exception) {
            // 建连级失败（连接拒绝/无网等）：错误时刻同样打戳
            ContinuityStreamResult(
                startNanos, null, null, 0, null, sawSummary = false,
                httpCode = null, error = e.toString(),
                errorNanos = SystemClock.elapsedRealtimeNanos(),
                timing = timingFactory.recordFor(call),
            )
        }
    }

    // ---------------------------------------------------------------- upload

    /** 单块写入戳。claim scope＝"写入本地协议栈"（R-07），仅作辅助诊断。 */
    data class ChunkStamp(val index: Int, val bytes: Int, val wroteAtNanos: Long)

    /**
     * /upload 响应体（服务端视角的权威逐块到达序列，R-07）。
     * chunk_us 供慢启动爬坡估计（U1 剔慢启动并列口径）。
     */
    @Serializable
    data class UploadServerView(
        val bytes: Long = -1,
        @SerialName("recv_start_us") val recvStartUs: Long = -1,
        @SerialName("recv_end_us") val recvEndUs: Long = -1,
        @SerialName("chunk_us") val chunkUs: List<Long> = emptyList(),
        val observed: String? = null,
    )

    /**
     * 上行突发结果。U1 计时终点＝收到 2xx 响应头（服务端已读完 body，R-07）：
     * 权威终点取 timing.responseHeadersStartNs；responseNanos 为响应头回调打戳的兜底值。
     */
    data class UploadResult(
        val startNanos: Long,
        val responseNanos: Long?,
        val chunkStamps: List<ChunkStamp>,
        val totalBytes: Int,
        val httpCode: Int?,
        val error: String?,
        val timing: TimingRecord?,
        /** 服务端视角逐块到达序列；解析失败/非 2xx 记 null（R-10） */
        val serverView: UploadServerView? = null,
    )

    suspend fun uploadBurst(url: String, payload: ByteArray, chunkBytes: Int = 2048): UploadResult {
        val stamps = ArrayList<ChunkStamp>(payload.size / chunkBytes + 1)
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength(): Long = payload.size.toLong()
            override fun writeTo(sink: BufferedSink) {
                var offset = 0
                var index = 0
                while (offset < payload.size) {
                    val len = minOf(chunkBytes, payload.size - offset)
                    sink.write(payload, offset, len)
                    sink.flush()
                    // 注意：这测的是写入本地 socket buffer 的时刻，不是线上发出时刻（R-07）
                    stamps.add(ChunkStamp(index, len, SystemClock.elapsedRealtimeNanos()))
                    offset += len
                    index++
                }
            }
        }
        val call = client.newCall(Request.Builder().url(url).post(body).build())
        val startNanos = SystemClock.elapsedRealtimeNanos()
        return try {
            executeCancellable(call) { resp ->
                // 打戳点＝收到响应头回调（与原 execute() 返回点同语义）
                val responseNanos = SystemClock.elapsedRealtimeNanos()
                val timing = timingFactory.recordFor(call)
                val bodyText = resp.body?.string() // 排空 + 解析服务端视角逐块到达序列（R-07 权威序列）
                val serverView = if (resp.isSuccessful && bodyText != null) {
                    try {
                        json.decodeFromString(UploadServerView.serializer(), bodyText)
                    } catch (e: Exception) {
                        null // 解析失败：serverView=null，慢启动口径退化为 null（R-10）
                    }
                } else {
                    null
                }
                val error = if (resp.isSuccessful) null else "http ${resp.code}"
                UploadResult(
                    startNanos, responseNanos, stamps, payload.size, resp.code, error, timing,
                    serverView = serverView,
                )
            }
        } catch (e: CancellationException) {
            throw e // 不吞取消（fail-closed §4.6/§4.7）
        } catch (e: Exception) {
            UploadResult(startNanos, null, stamps, payload.size, null, e.toString(), timingFactory.recordFor(call))
        }
    }

    // -------------------------------------------------------------- toolloop

    /**
     * 一轮工具循环结果（U2）。端到端终点＝下行 body 读完（2KB 全收到）。
     * trecv/tsend 来自响应头 X-Aneb-Trecv-Us / X-Aneb-Tsend-Us（服务端单调锚点 us）；
     * 实际 serverProc = tsend − trecv（比名义 200ms 更准，供 U2 剥离）。
     */
    data class ToolLoopResult(
        val startNanos: Long,
        /** 下行 body 读完时刻；失败记 null（R-10） */
        val bodyEndNanos: Long?,
        val downBytes: Long?,
        val trecvUs: Long?,
        val tsendUs: Long?,
        val httpCode: Int?,
        val error: String?,
        val timing: TimingRecord?,
    )

    suspend fun toolLoop(url: String, upBytes: Int): ToolLoopResult {
        val payload = ByteArray(upBytes) { 'T'.code.toByte() }
        val call = client.newCall(
            Request.Builder().url(url)
                .post(payload.toRequestBody("application/octet-stream".toMediaType()))
                .build()
        )
        val startNanos = SystemClock.elapsedRealtimeNanos()
        return try {
            executeCancellable(call) { resp ->
                val timing = timingFactory.recordFor(call)
                if (!resp.isSuccessful) {
                    resp.body?.string()
                    ToolLoopResult(startNanos, null, null, null, null, resp.code, "http ${resp.code}", timing)
                } else {
                    val body = checkNotNull(resp.body) { "empty body for 2xx" }.bytes()
                    val bodyEndNanos = SystemClock.elapsedRealtimeNanos() // 端到端终点＝body 读完
                    val trecv = resp.header("X-Aneb-Trecv-Us")?.toLongOrNull()
                    val tsend = resp.header("X-Aneb-Tsend-Us")?.toLongOrNull()
                    ToolLoopResult(
                        startNanos, bodyEndNanos, body.size.toLong(), trecv, tsend,
                        resp.code, null, timing,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e // 不吞取消（fail-closed §4.6/§4.7）
        } catch (e: Exception) {
            ToolLoopResult(startNanos, null, null, null, null, null, e.toString(), timingFactory.recordFor(call))
        }
    }

    // -------------------------------------------- profiles / results（控制面）

    /** 控制面简单响应（profiles 拉取 / results 上报共用）。 */
    data class HttpTextResult(val httpCode: Int?, val body: String?, val error: String?)

    /** GET /api/v1/profiles（P1 范围 1：拉不到用打包内置 assets 副本并告警） */
    suspend fun fetchProfiles(url: String): HttpTextResult =
        simpleCall(client.newCall(Request.Builder().url(url).get().build()))

    /** POST /api/v1/results（P1 范围 8：400 时 body 含 errors 清单，调用方打日志） */
    suspend fun postResults(url: String, jsonBody: String): HttpTextResult =
        simpleCall(
            client.newCall(
                Request.Builder().url(url)
                    .post(jsonBody.toRequestBody("application/json".toMediaType()))
                    .build()
            )
        )

    private suspend fun simpleCall(call: Call): HttpTextResult = try {
        executeCancellable(call) { resp ->
            val body = resp.body?.string()
            HttpTextResult(resp.code, body, if (resp.isSuccessful) null else "http ${resp.code}")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        HttpTextResult(null, null, e.toString())
    } finally {
        timingFactory.recordFor(call) // 控制面不计时，但必须取走记录防泄漏
    }

    // ------------------------------------------------- cancellable execution

    /**
     * 以可取消方式执行 [call]，并在 OkHttp 调度线程的 onResponse 回调内就地消费响应
     * （含流式 body 读取），suspend 等待 [consume] 的结果。
     *
     * 取消链路（fail-closed，设计文档 §4.6/§4.7）：协程取消 → invokeOnCancellation →
     * call.cancel() → 底层 socket 关闭 → consume 内的阻塞读以 IOException 退出；此时
     * continuation 已处于 cancelled 状态，resume/resumeWithException 按协程语义被忽略。
     *
     * 之所以把 consume 放进 onResponse（而非 resume 出 Response 后再读 body）：若 resume
     * 后才读流，invokeOnCancellation 只覆盖挂起等待响应头的窗口，body 读循环期间的协程
     * 取消无法再触达 call.cancel()，SSE 长流最长会拖满 readTimeout 30s。
     *
     * 计时语义不变：所有打戳仍用 SystemClock.elapsedRealtimeNanos()，回调线程与原先
     * flowOn(Dispatchers.IO) 的执行线程同为后台线程（TimingEventListener 本就在
     * OkHttp 回调线程打戳）。
     */
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

    private fun nowUs(): Long = SystemClock.elapsedRealtimeNanos() / 1_000L

    private companion object {
        const val THROUGHPUT_READ_BYTES: Long = 64L * 1024L
        /** 服务端固定 "\n\n" 分隔（与 SseReader 同一 wire 约定） */
        private val SSE_EVENT_DELIMITER = "\n\n".encodeUtf8()
        private val SEQ_REGEX = Regex("\"seq\"\\s*:\\s*(\\d+)")
    }
}
