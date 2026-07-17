package com.aneb.probe.engine

import android.os.SystemClock
import com.aneb.probe.net.AnebClient
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * profile 驱动的场景执行器（P1 范围 1）：按 [ScenarioProfile.phases] 依次执行
 * clock_sync / upload_burst / download_burst / think_pause / token_stream / tool_loop。
 *
 * - clock_sync：/echo × samples，前 [ECHO_WARMUP] 个 warmup 丢弃（5.3.2），样本间
 *   100–300ms 随机间隔去相关（R-23）；Cristian min-RTT 样本收敛 offset。
 * - upload_burst：bytes/chunk_kb 逐块打戳（claim=写入本地协议栈，R-07 辅助诊断），
 *   U1 终点=2xx 响应头；服务端逐块到达序列（权威）随响应返回。
 * - download_burst：读取到响应体最后一字节才算完成；D1 原始 goodput 只在字节数精确匹配时出值。
 *   该新增原始量不改变旧版 token-experience AQS 的评分合同。
 * - token_stream：GET /stream?profile=&phase=<token_stream 序号>&run=，SseReader 收流；
 *   TTFT=请求头发完→首 token 到达 − 服务端已知注入时延（首 token sched − prelude srv_ts，
 *   R-20 三段法第一段）；prelude 缺失时 TTFT 记 null（无法剥离 dwell 的样本不出值，R-10）。
 * - tool_loop：rounds × POST /toolloop，读 X-Aneb-Trecv-Us/X-Aneb-Tsend-Us 头，
 *   实际 serverProc = tsend − trecv。
 *
 * 结果写入调用方传入的 [ScenarioOutcome]（边跑边填——协程被守卫取消时已采样本仍可入库，
 * "invalid 只抑制聚合、原始事件全量入库" §4.6）。
 *
 * 传输层失败策略：echo/upload/toolloop 单样本失败记 null 继续；token_stream 传输错误
 * 即中止场景剩余 phase 并标 [ScenarioOutcome.abortReason]（流中断＝会话中断证据，5.3.8）。
 */
class ScenarioRunner(
    private val client: AnebClient,
    private val liveStreamObserver: LiveStreamObserver? = null,
) {

    class ClockSyncOutcome(
        val phaseIndex: Int,
        val point: ClockSyncPoint,
        val samples: List<EchoRecord>,
    )

    class EchoRecord(val idx: Int, val warmup: Boolean, val result: AnebClient.EchoResult)

    class UploadOutcome(
        val index: Int,
        val profileBytes: Long,
        val result: AnebClient.UploadResult,
    ) {
        /** U1 计时终点＝2xx 响应头（R-07）；失败 null */
        val durationNanos: Long? =
            if (result.error == null) {
                (result.timing?.responseHeadersStartNs ?: result.responseNanos)
                    ?.let { it - result.startNanos }
            } else {
                null
            }
    }

    class DownloadOutcome(
        val index: Int,
        val profileBytes: Long,
        val result: AnebClient.TransferResult,
    ) {
        val complete: Boolean =
            result.error == null &&
                (result.httpCode ?: 0) in 200..299 &&
                result.endNanos != null &&
                result.totalBytes == profileBytes

        /** D1 计时终点＝成功排空精确长度的响应体；失败/截断为 null，绝不填 0。 */
        val durationNanos: Long? =
            if (complete) result.endNanos?.minus(result.startNanos)?.takeIf { it > 0L } else null

        val goodputMbps: Double? = durationNanos
            ?.takeIf { it > 0L }
            ?.let { profileBytes * 8.0 / (it / 1e9) / 1e6 }
    }

    class StreamOutcome(
        val streamIndex: Int,
        val expectedTokens: Int,
        val result: AnebClient.StreamResult,
        /** TTFT（已剥服务端已知注入时延）；不可算记 null（R-10） */
        val ttftMs: Double?,
    )

    class ToolLoopOutcome(val round: Int, val nominalProcMs: Int, val result: AnebClient.ToolLoopResult)

    /** 一个场景实例（profile × repeat）的全部原始产出，运行中就地填充。 */
    class ScenarioOutcome(val profile: ScenarioProfile, val scenarioKey: String) {
        val clockSyncs = ArrayList<ClockSyncOutcome>()
        val uploads = ArrayList<UploadOutcome>()
        val downloads = ArrayList<DownloadOutcome>()
        val streams = ArrayList<StreamOutcome>()
        val toolLoops = ArrayList<ToolLoopOutcome>()

        /** 服务端观察到的客户端源 IP:port（echo/upload 回显，最后一次为准） */
        var observedAddr: String? = null
        var startedAtNanos: Long = 0
        var endedAtNanos: Long? = null

        /** 场景被中止（流传输错误等）；null=正常跑完 */
        var abortReason: String? = null

        /** 双 clock_sync skew 轨迹（C06/R-22）：首=场景开始，尾=场景结束（不足两次则退化） */
        fun offsetTrack(): OffsetTrack {
            val first = clockSyncs.firstOrNull()?.point
            val last = if (clockSyncs.size >= 2) clockSyncs.last().point else null
            return OffsetTrack(first, last)
        }
    }

    /**
     * @param inject debug 注入串（如 "truncate:50"），透传为 /stream 的 inject 查询参数
     *   （C09 前置；上层已用 BuildConfig.DEBUG 门控，release 恒 null）
     * @param emit 机器可解析日志行回调（KEY key=value ...）
     */
    suspend fun run(
        serverBase: String,
        runId: String,
        outcome: ScenarioOutcome,
        inject: String?,
        emit: suspend (String) -> Unit,
    ) {
        val base = serverBase.trim().trimEnd('/')
        val profile = outcome.profile
        // 设计文档 §5：每场景新建连接——清连接池，消除复用使 TTFT/T1 系统性偏低
        client.evictConnections()
        outcome.startedAtNanos = SystemClock.elapsedRealtimeNanos()

        var streamOrdinal = 0
        try {
            for ((phaseIdx, phase) in profile.phases.withIndex()) {
                when (phase.type) {
                    ProfilePhase.TYPE_CLOCK_SYNC ->
                        runClockSync(base, phaseIdx, phase.samples, outcome, emit)

                    ProfilePhase.TYPE_UPLOAD_BURST ->
                        runUpload(base, runId, phase, outcome, emit)

                    ProfilePhase.TYPE_DOWNLOAD_BURST ->
                        runDownload(base, phase, outcome, emit)

                    ProfilePhase.TYPE_THINK_PAUSE -> {
                        emit("THINK_PAUSE scenario=${outcome.scenarioKey} duration_ms=${phase.durationMs}")
                        delay(phase.durationMs.toLong())
                    }

                    ProfilePhase.TYPE_TOKEN_STREAM -> {
                        val ok = runStream(base, runId, streamOrdinal, phase, inject, outcome, emit)
                        streamOrdinal++
                        if (!ok) {
                            outcome.abortReason = "stream_transport_error"
                            emit(
                                "SCENARIO_ABORT scenario=${outcome.scenarioKey} " +
                                    "reason=stream_transport_error phase=$phaseIdx"
                            )
                            return // 流中断＝会话中断：剩余 phase 不再执行（5.3.8）
                        }
                    }

                    ProfilePhase.TYPE_TOOL_LOOP ->
                        runToolLoop(base, runId, phase, outcome, emit)

                    else -> emit("PHASE_SKIP scenario=${outcome.scenarioKey} unknown_type=${phase.type}")
                }
            }
        } finally {
            outcome.endedAtNanos = SystemClock.elapsedRealtimeNanos()
        }
    }

    // -------------------------------------------------------- download_burst

    private suspend fun runDownload(
        base: String,
        phase: ProfilePhase,
        outcome: ScenarioOutcome,
        emit: suspend (String) -> Unit,
    ) {
        val bytes = phase.bytes.coerceAtLeast(1L)
        val chunkKb = phase.chunkKb.coerceAtLeast(1)
        val idx = outcome.downloads.size
        val result = client.downloadThroughput(
            "$base/api/v1/download?bytes=$bytes&chunk_kb=$chunkKb",
            onBytes = { _, _ -> },
        )
        val download = DownloadOutcome(idx, bytes, result)
        outcome.downloads.add(download)
        val durationMs = download.durationNanos?.let { "%.2f".format(it / 1e6) } ?: "null"
        val goodput = download.goodputMbps?.let { "%.3f".format(it) } ?: "null"
        val error = result.error ?: if (download.complete) "none" else "incomplete_body"
        emit(
            "DOWNLOAD scenario=${outcome.scenarioKey} idx=$idx expected_bytes=$bytes " +
                "actual_bytes=${result.totalBytes} chunk_kb=$chunkKb http=${result.httpCode ?: "null"} " +
                "dur_ms=$durationMs goodput_mbps=$goodput complete=${download.complete} error=$error"
        )
    }

    // ------------------------------------------------------------ clock_sync

    private suspend fun runClockSync(
        base: String,
        phaseIndex: Int,
        samples: Int,
        outcome: ScenarioOutcome,
        emit: suspend (String) -> Unit,
    ) {
        val n = if (samples > 0) samples else 20
        val records = ArrayList<EchoRecord>(n)
        val valid = ArrayList<AnebClient.EchoResult>(n)
        for (i in 0 until n) {
            val r = client.echo("$base/api/v1/echo")
            val warmup = i < ECHO_WARMUP
            records.add(EchoRecord(i, warmup, r))
            r.observed?.let { outcome.observedAddr = it }
            if (!warmup && r.error == null && r.rttUs != null) valid.add(r)
            // R-23：样本间 100–300ms 随机间隔去相关
            delay(Random.nextLong(100L, 301L))
        }
        val best = valid.minByOrNull { it.rttUs!! }
        val point = if (best != null) {
            ClockSyncPoint(
                offsetUs = best.offsetUs,
                errUs = best.rttUs!! / 2,
                clientMidUs = (best.t0Us + (best.t3Us ?: best.t0Us)) / 2,
                validSamples = valid.size,
            )
        } else {
            ClockSyncPoint(null, null, null, 0) // 无有效样本：offset=null（R-10）
        }
        outcome.clockSyncs.add(ClockSyncOutcome(phaseIndex, point, records))
        val edge = if (outcome.clockSyncs.size == 1) "start" else "end"
        emit(
            "CLOCK_SYNC scenario=${outcome.scenarioKey} edge=$edge phase=$phaseIndex " +
                "offset_us=${point.offsetUs ?: "null"} err_us=${point.errUs ?: "null"} " +
                "valid_n=${point.validSamples} total_n=$n warmup_n=$ECHO_WARMUP"
        )
    }

    // ---------------------------------------------------------- upload_burst

    private suspend fun runUpload(
        base: String,
        runId: String,
        phase: ProfilePhase,
        outcome: ScenarioOutcome,
        emit: suspend (String) -> Unit,
    ) {
        val bytes = phase.bytes.toInt().coerceAtLeast(1)
        val chunk = (phase.chunkKb * 1024).coerceAtLeast(1024)
        val idx = outcome.uploads.size
        val r = client.uploadBurst(
            "$base/api/v1/upload?run=$runId",
            ByteArray(bytes) { 'A'.code.toByte() },
            chunk,
        )
        val up = UploadOutcome(idx, phase.bytes, r)
        outcome.uploads.add(up)
        r.serverView?.observed?.let { outcome.observedAddr = it }
        val durMs = up.durationNanos?.let { "%.2f".format(it / 1e6) } ?: "null"
        val goodput = up.durationNanos?.let {
            "%.3f".format(bytes * 8.0 / (it / 1e9) / 1e6)
        } ?: "null"
        emit(
            "UPLOAD scenario=${outcome.scenarioKey} idx=$idx bytes=$bytes chunk=$chunk " +
                "http=${r.httpCode ?: "null"} dur_ms=$durMs goodput_mbps=$goodput " +
                "server_chunks=${r.serverView?.chunkUs?.size ?: "null"} error=${r.error ?: "none"}"
        )
    }

    // ---------------------------------------------------------- token_stream

    /** @return false = 传输层错误（调用方中止场景） */
    private suspend fun runStream(
        base: String,
        runId: String,
        streamOrdinal: Int,
        phase: ProfilePhase,
        inject: String?,
        outcome: ScenarioOutcome,
        emit: suspend (String) -> Unit,
    ): Boolean {
        var url = "$base/api/v1/stream?profile=${outcome.profile.profileId}&phase=$streamOrdinal&run=$runId"
        if (!inject.isNullOrBlank()) url += "&inject=$inject" // C09 前置：debug 注入透传
        liveStreamObserver?.onStreamStarted(phase.rateTps, SystemClock.elapsedRealtimeNanos())
        val r = try {
            client.stream(
                url,
                expectedTokens = phase.tokens,
                onEventArrival = liveStreamObserver?.let { observer -> observer::onEventArrival },
            )
        } finally {
            liveStreamObserver?.onStreamFinished()
        }

        val stream = r.stream
        val ttftMs: Double?
        if (stream != null && r.error == null) {
            // TTFT（T1 网络分量）：请求头发完 → 首 token 到达，减服务端已知注入时延
            // （首 token schedUs − prelude srv_ts_us＝profile 首 token 的名义 pacing 延迟）。
            val first = stream.events.minByOrNull { it.seq }
            val preludeSrvTsUs = stream.prelude?.let { parsePreludeSrvTsUs(it.raw) }
            val originNs = r.timing?.requestHeadersEndNs
            ttftMs = if (first != null && preludeSrvTsUs != null && originNs != null && first.schedUs >= 0) {
                (first.arrivalNanos - originNs) / 1e6 - (first.schedUs - preludeSrvTsUs) / 1e3
            } else {
                null // prelude/计时点缺失 → 无法剥离服务端 dwell，T1 样本不出值（R-10/R-20）
            }
        } else {
            ttftMs = null
        }
        outcome.streams.add(StreamOutcome(streamOrdinal, phase.tokens, r, ttftMs))

        if (stream != null) {
            emit(
                "STREAM scenario=${outcome.scenarioKey} stream=$streamOrdinal expected=${phase.tokens} " +
                    "events=${stream.events.size} reads=${stream.readCount} bytes=${stream.totalBytes} " +
                    "gaps=${r.gapCount} dup=${r.duplicateCount} parse_errors=${stream.parseErrors} " +
                    "truncated_early=${r.truncatedEarly} truncated_tail=${stream.truncatedTail} " +
                    "ttft_ms=${ttftMs?.let { "%.2f".format(it) } ?: "null"} error=${r.error ?: "none"}"
            )
            // P0-C12：EOF→解析完成打点
            emit(
                "PARSE scenario=${outcome.scenarioKey} stream=$streamOrdinal " +
                    "parse_dur_us=${stream.parseDurUs} " +
                    "per_event_parse_us=${stream.perEventParseUs?.let { "%.2f".format(it) } ?: "null"} " +
                    "events=${stream.events.size}"
            )
        } else {
            emit(
                "STREAM scenario=${outcome.scenarioKey} stream=$streamOrdinal expected=${phase.tokens} " +
                    "events=0 http=${r.httpCode ?: "null"} error=${r.error ?: "none"}"
            )
        }
        return r.error == null && stream != null
    }

    // -------------------------------------------------------------- tool_loop

    private suspend fun runToolLoop(
        base: String,
        runId: String,
        phase: ProfilePhase,
        outcome: ScenarioOutcome,
        emit: suspend (String) -> Unit,
    ) {
        val rounds = if (phase.rounds > 0) phase.rounds else 8
        val procMs = if (phase.serverProcMs > 0) phase.serverProcMs else 200
        val downBytes = if (phase.downBytes > 0) phase.downBytes else 2048
        val upBytes = (if (phase.upBytes > 0) phase.upBytes else 8192).toInt()
        val url = "$base/api/v1/toolloop?run=$runId&proc_ms=$procMs&down_bytes=$downBytes"
        for (round in 0 until rounds) {
            val r = client.toolLoop(url, upBytes)
            outcome.toolLoops.add(ToolLoopOutcome(round, procMs, r))
            val totalMs = r.bodyEndNanos?.let { "%.2f".format((it - r.startNanos) / 1e6) } ?: "null"
            val actualProcUs = if (r.trecvUs != null && r.tsendUs != null) r.tsendUs - r.trecvUs else null
            emit(
                "TOOLLOOP scenario=${outcome.scenarioKey} round=$round up_bytes=$upBytes " +
                    "total_ms=$totalMs proc_nominal_ms=$procMs " +
                    "proc_actual_us=${actualProcUs ?: "null"} http=${r.httpCode ?: "null"} " +
                    "error=${r.error ?: "none"}"
            )
        }
    }

    companion object {
        /** 5.3.2：前 2–3 个请求丢弃预热；与阶段 0 一致取 3 */
        const val ECHO_WARMUP = 3

        /** 解析 prelude 注释帧 `prelude {"srv_ts_us":N,...}` 的 srv_ts_us；失败 null。 */
        fun parsePreludeSrvTsUs(raw: String): Long? =
            Regex("\"srv_ts_us\"\\s*:\\s*(\\d+)").find(raw)?.groupValues?.get(1)?.toLongOrNull()
    }
}
