package com.aneb.probe.net

import android.os.SystemClock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import java.util.Base64

/**
 * 单个 token event 的到达记录。
 *
 * @param seq           服务端序号（R-08：KPI 对齐一律按 seq join，禁数组位置配对）
 * @param schedUs       服务端"期望发出时刻"（profile 时刻表，进程启动锚点单调 us；缺失为 -1）
 * @param preFlushUs    服务端"实际 flush 前时刻"（同上单调轴；缺失为 -1）
 * @param arrivalNanos  客户端读出该 event 所在 read 块的时刻（elapsedRealtimeNanos）
 * @param payloadBytes  payload 解码后字节数（base64 传输，R-08 杜绝随机字节与 \n\n 冲突）
 * @param sameReadBatch R-04：同一次 source.read 切出的第 2..n 个 event 标 true——
 *                      它们与前一 event 的间隔是内存读出伪 0 值，ITL 统计须剔除
 */
data class TokenEvent(
    val seq: Long,
    val schedUs: Long,
    val preFlushUs: Long,
    val arrivalNanos: Long,
    val payloadBytes: Int,
    val sameReadBatch: Boolean,
)

/** prelude 注释帧（R-20：响应头写出后先 flush 一帧，把服务端 dwell 从 T1 网络分量剥离）。 */
data class SsePrelude(
    val arrivalNanos: Long,
    /** 注释帧原文（去掉前导 ':'），形如 `prelude {"srv_ts_us":...}`，解析留给上层 */
    val raw: String,
)

data class SseStreamResult(
    val prelude: SsePrelude?,
    val events: List<TokenEvent>,
    /** summary event 的 data 原文（服务端发送自检统计），阶段 0 不深度解析 */
    val summaryRaw: String?,
    val readCount: Int,
    val totalBytes: Long,
    /** 解析失败被跳过的 event 数（R-08：跳过并计数，绝不静默错位） */
    val parseErrors: Int,
    /** EOF 时累积缓冲仍有残留 => 尾部截断 event */
    val truncatedTail: Boolean,
    /** 流 EOF 时刻（elapsedRealtimeNanos）——解析阶段起点打戳（P0-C12） */
    val eofNanos: Long,
    /** 解析完成时刻（elapsedRealtimeNanos）（P0-C12） */
    val parseEndNanos: Long,
) {
    /** 解析阶段总耗时（us）＝ parseEnd − EOF（P0-C12：解析开销不得混入 ITL 的证据） */
    val parseDurUs: Long get() = (parseEndNanos - eofNanos) / 1_000L

    /**
     * summary 透出的服务端 TCP_INFO `retrans_total`（tcpi_total_retrans，连接生命周期
     * 累计重传段数；P3-C05 retrans 共变量，供 BufferingDetector 区分"丢包重传批化"
     * 与"中间盒缓冲批化"）。summary 缺失 / 字段缺省（非 Linux 服务端、h3/QUIC、
     * 截断流）→ null（R-10：无值不造值，检测器按无共变量数据回退）。
     */
    val summaryRetransTotal: Long?
        get() = summaryRaw?.let { RETRANS_TOTAL_REGEX.find(it)?.groupValues?.get(1)?.toLongOrNull() }

    /** 每 event 平均解析耗时（us）＝ parseDurUs / 事件数；无事件记 null（R-10） */
    val perEventParseUs: Double? get() = if (events.isEmpty()) null else parseDurUs.toDouble() / events.size

    private companion object {
        /** summary data JSON 中的 `"retrans_total":N`（服务端手拼 JSON，数值无引号） */
        private val RETRANS_TOTAL_REGEX = Regex("\"retrans_total\"\\s*:\\s*(\\d+)")
    }
}

/**
 * 原始 SSE event（批读打戳层输出，未做任何文本/JSON 解析）。
 * 阶段 2 起为公共类型：aneb 仿真流（[SseReader.readStream]）与真实 LLM API 探针
 * （com.aneb.probe.apiprobe 的协议适配器）共用同一读循环与打戳语义（R-04）。
 */
class RawSseEvent(
    val bytes: ByteArray,
    val arrivalNanos: Long,
    /** 同一次 source.read 切出的第 2..n 个 event（到达间隔为内存读出伪 0，R-04） */
    val sameReadBatch: Boolean,
)

/** 批读打戳层的整流结果（解析前）。 */
class RawSseStream(
    val events: List<RawSseEvent>,
    val readCount: Int,
    val totalBytes: Long,
    /** EOF 时累积缓冲仍有残留 => 尾部截断 event */
    val truncatedTail: Boolean,
    /** 流 EOF 时刻（elapsedRealtimeNanos） */
    val eofNanos: Long,
)

/**
 * SSE 边界扫描器（P2-C05 抽出，[SseReader.readRaw] 与 Cronet 路径共用同一份
 * `\n\n` 切边界逻辑与 sameReadBatch 语义——"一次读一次戳"，同一次 read 切出的
 * 第 2..n 个 event 标 sameReadBatch=true，R-04）。
 *
 * 有状态、非线程安全：一次流一个实例，读循环单线程内调用（OkHttp 读线程或
 * Cronet callback executor 单线程）。
 */
class SseBoundaryScanner(
    /**
     * 可选只读观察回调：每切出一个完整 SSE event 后仅传到达戳，不传内容、不做协议解析。
     * 调用发生在读线程，回调实现必须为常数时间且无阻塞。
     */
    private val onEventArrival: ((Long) -> Unit)? = null,
) {
    private val acc = Buffer()
    private val events = ArrayList<RawSseEvent>(1024)
    private var readCount = 0
    private var totalBytes = 0L

    /**
     * 交付一次 read 的字节（[chunk] 会被整体读空）。[arrivalNanos] 为该次 read
     * 返回时刻的打戳——本方法内不打戳，戳由调用方在读返回处就地打（R-04）。
     */
    fun onRead(chunk: Buffer, byteCount: Long, arrivalNanos: Long) {
        readCount++
        totalBytes += byteCount
        acc.writeAll(chunk)

        var eventsInThisRead = 0
        while (true) {
            val boundary = acc.indexOf(EVENT_DELIMITER)
            if (boundary == -1L) break
            val eventBytes = acc.readByteArray(boundary)
            acc.skip(EVENT_DELIMITER.size.toLong())
            if (eventBytes.isEmpty()) continue
            events.add(
                RawSseEvent(
                    bytes = eventBytes,
                    arrivalNanos = arrivalNanos,
                    // 同一 read 内第 2..n 个 event：到达间隔是伪 0（R-04）
                    sameReadBatch = eventsInThisRead > 0,
                )
            )
            onEventArrival?.invoke(arrivalNanos)
            eventsInThisRead++
        }
    }

    /** 流结束（EOF/错误检出）时收口；[eofNanos] 为 EOF 打戳时刻（P0-C12 边界）。 */
    fun finish(eofNanos: Long): RawSseStream =
        RawSseStream(events, readCount, totalBytes, truncatedTail = acc.size > 0L, eofNanos = eofNanos)

    private companion object {
        /** 服务端固定 "\n\n" 分隔（与 SseReader 同一 wire 约定；"\r\n\r\n" 兼容留 TODO） */
        private val EVENT_DELIMITER = "\n\n".encodeUtf8()
    }
}

/**
 * SSE 读取器（R-04 核心）。
 *
 * 读循环规则：
 *  - 按可用量批读 `source.read(buffer, 8192)`，一次 read 返回打一次戳；
 *  - 在累积缓冲内扫描 `\n\n` 边界切 event；同一次 read 切出的多个 event 共享该 read
 *    的到达时戳，且第 2..n 个标 sameReadBatch=true（杜绝伪造 0ms ITL 稀释 P95）；
 *  - 读循环内除必要的字节切片分配外不做重活：原始 event 字节先写入预分配 ArrayList，
 *    文本解码 / JSON 解析 / base64 解码全部推迟到流读完之后；
 *  - 阶段3 收口（设计文档 §4 第 10 条 / R-04）：EOF 后的解析从读线程移到
 *    [SseParseThread]（THREAD_PRIORITY_URGENT_AUDIO 的专用单线程）——读线程只做
 *    read → 打戳 → 缓冲；parseDurUs 打点语义不变（仍为 EOF→解析完成）。
 *
 * 服务端 wire 约定（见 probe/README.md）：
 *  - 注释帧:  `: prelude {"srv_ts_us":...}\n\n`
 *  - token:  `event: token\ndata: {"seq":N,"sched_us":...,"pre_flush_us":...,"payload":"<base64>"}\n\n`
 *  - 结尾:   `event: summary\ndata: {...}\n\n`
 */
class SseReader private constructor(
    private val json: Json,
    private val clock: MonotonicNanosClock,
) {

    /** Preserve the existing public default-argument constructor and Android clock behavior. */
    @JvmOverloads
    constructor(json: Json = Json { ignoreUnknownKeys = true }) : this(json, AndroidMonotonicNanosClock)

    @Serializable
    private data class TokenWire(
        val seq: Long,
        @SerialName("sched_us") val schedUs: Long = -1L,
        @SerialName("pre_flush_us") val preFlushUs: Long = -1L,
        val payload: String = "",
    )

    /**
     * 批读打戳层（阶段 2 抽出，供 LLM API 探针复用）：只做 read → 打戳 → `\n\n`
     * 切边界 → 存原始字节，绝不解析。语义与原 readStream 读循环完全一致。
     */
    fun readRaw(source: BufferedSource, onEventArrival: ((Long) -> Unit)? = null): RawSseStream {
        // 切边界/打戳语义收敛在 SseBoundaryScanner（P2-C05：Cronet 路径共用同一实现）
        val scanner = SseBoundaryScanner(onEventArrival)
        val readBuf = Buffer()

        // ---- 读循环：read → 打戳 → 切边界 → 存原始字节，别的都不做 ----
        while (true) {
            val n = source.read(readBuf, READ_CHUNK_BYTES)
            if (n == -1L) break
            // 一次 read 返回打一次戳（R-04），戳在读线程就地打
            scanner.onRead(readBuf, n, clock.now())
        }
        // P0-C12：EOF 打戳——解析阶段与读循环的时间边界
        return scanner.finish(clock.now())
    }

    /**
     * 读 + 解析全流程。读循环在调用线程（OkHttp 读线程）执行——只做 read→打戳→
     * 切边界→缓冲；EOF 后的解析移交 [SseParseThread] 专用线程执行并同步等待结果
     * （设计文档 §4 第 10 条 / R-04 / R-16：解析开销不占读线程；EOF 后读线程已无
     * 测量职责，同步等待不改任何打点语义——eofNanos 仍在读线程 EOF 处打，
     * parseEndNanos 在解析线程解析完成处打，同一单调钟）。
     */
    fun readStream(source: BufferedSource, onEventArrival: ((Long) -> Unit)? = null): SseStreamResult {
        val raw = readRaw(source, onEventArrival)
        return SseParseThread.execute { parseRaw(raw) }
    }

    /**
     * 解析阶段（流已读完后执行；P2-C05 抽出为公共入口，Cronet 路径复用同一解析器）。
     * 本方法自身线程无关（纯数据变换 + 末尾打戳）；[readStream] 已把它调度到
     * [SseParseThread]，直接调用方（如需）自行决定执行线程。
     */
    fun parseRaw(raw: RawSseStream): SseStreamResult {
        val rawEvents = raw.events
        val readCount = raw.readCount
        val totalBytes = raw.totalBytes
        val truncatedTail = raw.truncatedTail
        val eofNanos = raw.eofNanos

        var prelude: SsePrelude? = null
        var summaryRaw: String? = null
        var parseErrors = 0
        val events = ArrayList<TokenEvent>(rawEvents.size)

        for (raw in rawEvents) {
            val text = raw.bytes.toString(Charsets.UTF_8)
            var eventName: String? = null
            var dataLine: String? = null
            var commentLine: String? = null
            // 阶段 0 简化：服务端保证单 data 行；多 data 行拼接留 TODO 阶段1
            for (line in text.split('\n')) {
                when {
                    line.startsWith(":") -> commentLine = line.removePrefix(":").trim()
                    line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> dataLine = line.removePrefix("data:").trim()
                }
            }
            when {
                commentLine != null && commentLine.startsWith("prelude") ->
                    prelude = SsePrelude(raw.arrivalNanos, commentLine)

                eventName == "summary" -> summaryRaw = dataLine

                eventName == "token" && dataLine != null -> {
                    try {
                        val wire = json.decodeFromString(TokenWire.serializer(), dataLine)
                        events.add(
                            TokenEvent(
                                seq = wire.seq,
                                schedUs = wire.schedUs,
                                preFlushUs = wire.preFlushUs,
                                arrivalNanos = raw.arrivalNanos,
                                payloadBytes = decodedPayloadSize(wire.payload),
                                sameReadBatch = raw.sameReadBatch,
                            )
                        )
                    } catch (e: Exception) {
                        // R-08：畸形 event 跳过并计数（后续 seq join 计 gap），绝不静默错位
                        parseErrors++
                    }
                }

                else -> parseErrors++
            }
        }

        // P0-C12：解析完成打戳；parseDurUs/perEventParseUs 由 SseStreamResult 派生输出
        val parseEndNanos = SystemClock.elapsedRealtimeNanos()

        return SseStreamResult(
            prelude = prelude,
            events = events,
            summaryRaw = summaryRaw,
            readCount = readCount,
            totalBytes = totalBytes,
            parseErrors = parseErrors,
            truncatedTail = truncatedTail,
            eofNanos = eofNanos,
            parseEndNanos = parseEndNanos,
        )
    }

    private fun decodedPayloadSize(payload: String): Int =
        try {
            Base64.getDecoder().decode(payload).size
        } catch (e: IllegalArgumentException) {
            // 非 base64（不符合 wire 约定）：退化为原文字节数
            payload.toByteArray(Charsets.UTF_8).size
        }

    companion object {
        private const val READ_CHUNK_BYTES = 8192L

        /** Construct a clock-injected reader without exposing a public overload. */
        @JvmSynthetic
        internal fun createForTest(json: Json, clock: MonotonicNanosClock): SseReader =
            SseReader(json, clock)
    }
}

/**
 * SSE 专用解析线程（阶段3 收口，设计文档 §4 第 10 条 / R-04）：EOF 后的解析统一在
 * 该单线程 executor 执行，读线程只做 read→打戳→缓冲。
 *
 * - 线程在启动时尝试升到 THREAD_PRIORITY_URGENT_AUDIO（§4.10：解析不被后台负载
 *   饿死、也不与读线程抢核）；JVM 单测环境无 android.os.Process，runCatching
 *   静默降级为普通优先级——只影响调度优先级，不影响解析语义与单测可跑性。
 * - 单线程 + 同步 execute：同一时刻至多一个流在解析（流是顺序跑的），无并发解析
 *   需求；异常原样透传，保持 readStream 既有异常合同（上层 catch 语义不变）。
 * - daemon 线程：不阻碍进程/JVM 退出。
 */
internal object SseParseThread {
    /** 解析线程名（单测锚点） */
    const val THREAD_NAME = "aneb-sse-parse"

    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({
            // Android 上生效；JVM 测试环境无 android.os.Process → 静默降级（Throwable 全捕）
            runCatching {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            }
            r.run()
        }, THREAD_NAME).apply { isDaemon = true }
    }

    /** 在解析线程同步执行 [block]；block 抛出的异常按原类型透传给调用方。 */
    fun <T> execute(block: () -> T): T =
        try {
            executor.submit(java.util.concurrent.Callable { block() }).get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
}
