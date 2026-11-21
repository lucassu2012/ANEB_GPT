package com.aneb.probe.engine

import com.aneb.probe.net.AnebClient
import com.aneb.probe.net.ReachabilityProbe
import com.aneb.probe.net.RealtimeSimSession
import com.aneb.probe.net.RealtimeWire
import com.aneb.probe.scoring.BufferingDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * AI 实时交互（语音，GPT-Live 式）模式运行器——与 [SpeedRunner] 并列的观测口径模式
 * （PROFILE_FRAMEWORK §4.1）。**零服务端部署**：
 * - **上行帧**：[AnebClient.uploadPaced] 按 20ms 帧节奏发小包（模拟 Opus 上行），服务端
 *   `/upload` 的 `chunk_us` 权威到达序列 → **上行帧间抖动 M3**（含客户端调度抖动，观测上界）。
 * - **下行帧**：已部署 `/stream?tokens=&rate_tps=50` 实测输出精确 20ms 帧节奏（sched_us 差
 *   恰 20000µs），客户端 arrivalNanos 帧间隔 → **下行帧间抖动 M2**。
 * - **口到耳预算 M1**（DERIVED，口径注明）＝RTT_P50 + max(上/下行帧抖动 P95) +
 *   编解码/播放缓冲名义常数 [CODEC_JB_BUDGET_MS]——网络贡献的口到耳下界预算，非真实音频链路实测。
 *
 * 观测/展示口径，不进 v0.1/v0.2/Token AQS；facet4 经 [com.aneb.probe.scoring.AqsScorer.scoreVoice]
 * 独立出分（WEIGHTS_VOICE + M1>400ms 硬否决）。
 */
class VoiceRunner(private val client: AnebClient = AnebClient()) {

    enum class Phase { Ping, Uplink, Downlink, Handshake, Turns, Done }

    /** 单轮实时语音观测点（只读 UI 示波投影，绝不改写 KPI/AQS/Room 落库口径）。 */
    data class TurnLivePoint(
        val turnIndex: Int,
        val interrupted: Boolean,
        val mouthEarMs: Double?,
        val ttfbMs: Double?,
        val maxGapMs: Double?,
        val bargeStopMs: Double?,
        val protocolOk: Boolean,
    )

    data class Sample(
        val phase: Phase,
        val rttMs: Double?,
        val jitterMs: Double?,
        /** 上行帧间抖动 P95（ms，M3；服务端 chunk_us 权威）；未测/样本不足 null */
        val upFrameJitterMs: Double?,
        /** 下行帧间抖动 P95（ms，M2；客户端 arrivalNanos）；未测/样本不足 null */
        val downFrameJitterMs: Double?,
        /** 口到耳预算（ms，M1 DERIVED）；任一成分缺失 null（R-10） */
        val mouthEarBudgetMs: Double?,
        val framesSent: Int,
        val framesRecv: Int,
        val progress: Float,
        // ── v2 server-sim 口径尾部字段（D-38；additive 默认 null，v1 run() 不受影响）──
        /** M4 TTS-TTFB P50（ms，实测：speech_commit→首 ANED，已剥服务端驻留）；null=未测 */
        val ttfbP50Ms: Double? = null,
        /** M4 TTS-TTFB P95（ms） */
        val ttfbP95Ms: Double? = null,
        /** M2' 下行纯传输抖动 P95（ms；帧内嵌 sched_us 差分剥离服务端调度误差） */
        val downNetJitterMs: Double? = null,
        /** M1' 口到耳实测代理 P50（ms；末上行帧→首 ANED 剥驻留 + 编解码常数；标 PROXY） */
        val mouthEarProxyMs: Double? = null,
        /** M5 轮次切换 P50（ms；上轮 summary→下轮首 ANED，剥计划上行时长与驻留） */
        val turnSwitchP50Ms: Double? = null,
        /** M6 打断停帧最大值（ms；barge_in 发出→末 ANED 到达） */
        val bargeStopMaxMs: Double? = null,
        /** protocol_ok 轮数（诚实对账） */
        val turnsOk: Int = 0,
        /** 口径标注：null=v1 paced-proxy；"server-sim(aneb-realtime-session-v1)"=v2 */
        val caliber: String? = null,
        /** 上行入队背压出现过（ws.queueSize>0）→ 低置信 */
        val lowConfidence: Boolean = false,
        // ── 连续性 mini-run 尾部字段（D-41 预定；additive 默认 null，run()/runSim() 不受影响）──
        /**
         * 受控断连检出（ms）＝传输失败浮出（[RealtimeSimSession.TransportClosed].atUs）−
         * 断连轮 turn_summary 客户端到达（同基客户端单调 us，两两相减免钟偏）；
         * ~20s 未浮出 → null（R-10 诚实缺席）。
         */
        val continuityDetectMs: Double? = null,
        /** 受控断连重建（ms）＝新会话 session_ready 客户端到达 − 失败浮出；检出缺席则 null（R-10） */
        val continuityResumeMs: Double? = null,
        // ── M7 尾部字段（D-390 §5 B′/D-404；additive 默认 null，run()/runSim() 既有调用不受影响）──
        /** M7 最长帧间静默（ms；下行到达间隔 max，抗 P95 弃尾，D-390 订正后的首选判据） */
        val m7MaxFrameGapMs: Double? = null,
        /** 近零到达间隔占比（[0,1]；补充信号，答"有没有发生"，M7 答"有多严重"） */
        val voiceNearZeroArrivalRatio: Double? = null,
        // ── 实时双向示波座舱只读投影（additive 默认值，零测量/落库影响）──
        val liveTurnHistory: List<TurnLivePoint> = emptyList(),
        val totalPlannedTurns: Int = 8,
    )

    companion object {
        /** 语音帧节奏（Opus 典型 20ms/帧） */
        const val FRAME_INTERVAL_MS = 20L

        /** 帧大小（~64kbps 语音帧量级） */
        const val FRAME_BYTES = 160

        /** 上/下行各 ~4s（200 帧 × 20ms） */
        const val UPLINK_FRAMES = 200
        const val DOWNLINK_FRAMES = 200

        /** 编解码 + 播放抖动缓冲名义预算（ms，口径常数：codec ~20 + playout buffer ~40） */
        const val CODEC_JB_BUDGET_MS = 60.0

        /** v2 server-sim 口径标注（contract_version 直接入库，D-38） */
        const val SIM_CALIBER = "server-sim(aneb-realtime-session-v1)"

        /** v2 中 M3 专用 uploadPaced 帧数（3s 短段；M3 权威仍是 /upload chunk_us） */
        const val SIM_M3_FRAMES = 150

        /**
         * 默认 8 轮语音计划（D-38 §4）：20ms×160B≈64kbps Opus 量级（与 v1 口径常数一致，
         * M2/M3 跨口径可比）；75 上行帧≈1.5s 说话、100 下行帧≈2s TTS、300ms 模拟思考
         * （剥离后不进 KPI）；idx 3/6 为打断轮（barge_in@25 帧，expected_stop 250ms）。
         * 全部字段已逐项过合同限额（turns≤32、frame_ms∈[10,100]、帧数/字节/等待在界）。
         */
        fun defaultSimPlan(seed: Long): RealtimeWire.SessionPlan = RealtimeWire.SessionPlan(
            sessionId = "voice-sim-${seed.toString(16)}",
            seed = seed,
            setupMs = 200.0,
            frameMs = 20,
            turns = (0 until 8).map { i ->
                val interrupted = i == 3 || i == 6
                RealtimeWire.TurnPlan(
                    turnId = "t$i", turnIndex = i, startAfterPreviousMs = 0,
                    uplinkFrames = 75, uplinkFrameBytes = FRAME_BYTES, responseWaitMs = 300,
                    plannedDownlinkFrames = 100, downlinkFrameBytes = FRAME_BYTES,
                    interrupted = interrupted,
                    bargeInAfterFrames = if (interrupted) 25 else null,
                    expectedStopWithinMs = if (interrupted) 250 else null,
                )
            },
        )

        /** 连续性 mini-run：受控断连轮 N（服务端跑完轮 N、发出其 turn_summary 后裸关 TCP；合同 0≤N<32） */
        const val CONT_DISCONNECT_AFTER_TURN = 1

        /** 连续性 mini-run 单轮量级（25 上行帧≈0.5s、40 下行帧≈0.8s——快跑取事件，不产 KPI 分位数） */
        const val CONT_UPLINK_FRAMES = 25
        const val CONT_DOWNLINK_FRAMES = 40

        /**
         * 连续性 mini-run 3 轮断连计划（D-41 预定）：复用 [defaultSimPlan] 形状（20ms×160B），
         * 全轮非中断（barge 字段 null）；配合 `controlled_disconnect_after_turn=`
         * [CONT_DISCONNECT_AFTER_TURN] 使用——轮 2 仅为保证断连点非计划末轮
         * （服务端在轮 1 summary 发出后裸关，轮 2 永不运行）。字段逐项过合同限额
         * （turns≤32、frame_ms∈[10,100]、帧数/字节/等待在界）。
         */
        fun continuitySimPlan(seed: Long): RealtimeWire.SessionPlan = RealtimeWire.SessionPlan(
            sessionId = "voice-cont-${seed.toString(16)}",
            seed = seed,
            setupMs = 200.0,
            frameMs = 20,
            turns = (0 until 3).map { i ->
                RealtimeWire.TurnPlan(
                    turnId = "t$i", turnIndex = i, startAfterPreviousMs = 0,
                    uplinkFrames = CONT_UPLINK_FRAMES, uplinkFrameBytes = FRAME_BYTES, responseWaitMs = 300,
                    plannedDownlinkFrames = CONT_DOWNLINK_FRAMES, downlinkFrameBytes = FRAME_BYTES,
                    interrupted = false,
                )
            },
        )

        /**
         * 连续性 mini-run 重建计划（D-41 预定）：断连浮出后**全新会话**（新 session_id）单轮
         * 计划，跑完该轮 + session_summary 证明会话可用；连接不带 disconnect 参数。
         */
        fun continuityResumePlan(seed: Long): RealtimeWire.SessionPlan = RealtimeWire.SessionPlan(
            sessionId = "voice-cont-r-${seed.toString(16)}",
            seed = seed,
            setupMs = 200.0,
            frameMs = 20,
            turns = listOf(
                RealtimeWire.TurnPlan(
                    turnId = "t0", turnIndex = 0, startAfterPreviousMs = 0,
                    uplinkFrames = CONT_UPLINK_FRAMES, uplinkFrameBytes = FRAME_BYTES, responseWaitMs = 300,
                    plannedDownlinkFrames = CONT_DOWNLINK_FRAMES, downlinkFrameBytes = FRAME_BYTES,
                    interrupted = false,
                ),
            ),
        )

        /**
         * 帧间抖动 P95（ms）＝相邻到达间隔对名义帧间隔偏差绝对值的最近秩 P95。
         * 间隔样本 <2 → null（R-10）。
         */
        fun frameJitterP95Ms(intervalsUs: List<Long>, nominalUs: Long): Double? {
            if (intervalsUs.size < 2) return null
            val devs = intervalsUs.map { abs(it - nominalUs) / 1000.0 }.sorted()
            val rank = ceil(0.95 * devs.size).toInt().coerceIn(1, devs.size)
            return devs[rank - 1]
        }

        /**
         * 口到耳预算（ms，DERIVED）＝RTT_P50 + max(上行帧抖动, 下行帧抖动) + [CODEC_JB_BUDGET_MS]。
         * 任一成分缺失 → null（R-10：不以部分成分冒充整体预算）。
         */
        fun mouthEarBudgetMs(rttP50Ms: Double?, upJitterMs: Double?, downJitterMs: Double?): Double? {
            if (rttP50Ms == null || upJitterMs == null || downJitterMs == null) return null
            return rttP50Ms + max(upJitterMs, downJitterMs) + CODEC_JB_BUDGET_MS
        }

        /**
         * M7 最长帧间静默（ms）＝下行到达间隔（原始，非对名义节奏的偏差）max。
         * D-390 订正：P95 类判据会把「罕见但致命」的长冻结当尾部弃掉；max 不丢尾、无饱和平台。
         * 间隔样本为空 → null（R-10：无样本不等于零静默）。
         */
        fun maxFrameGapMs(intervalsUs: List<Long>): Double? =
            intervalsUs.maxOrNull()?.let { it / 1000.0 }

        /**
         * 近零到达间隔占比（[0,1]）＝背靠背投递帧数 / 总间隔数，复用 [BufferingDetector] 判据
         * （同一常量 [BufferingDetector.NEAR_ZERO_ARRIVAL_US]，不另定义）。M7 答"有多严重"，
         * 本值答"有没有发生"，两者互补（D-390 §5 结论）。间隔样本为空 → null（R-10）。
         */
        fun nearZeroArrivalRatio(intervalsUs: List<Long>): Double? {
            if (intervalsUs.isEmpty()) return null
            return intervalsUs.count { it in 0 until BufferingDetector.NEAR_ZERO_ARRIVAL_US }.toDouble() / intervalsUs.size
        }
    }

    fun run(serverBase: String): Flow<Sample> = channelFlow {
        // D-25：E-01 sslip SNI-RST → bare-IP 等价基址（同节点同路径）
        val raw = serverBase.trim().trimEnd('/')
        val base = ReachabilityProbe.deriveE01Pair(raw)?.second ?: raw

        // ---- Ping（~1s，12 次 echo；墙钟 RTT，丢首个建连样本）----
        val rtts = ArrayList<Double>()
        val pingN = 12
        for (i in 0 until pingN) {
            val t0 = System.nanoTime()
            val r = withContext(Dispatchers.IO) { runCatching { client.echo("$base/api/v1/echo") }.getOrNull() }
            val rttMs = (System.nanoTime() - t0) / 1e6
            if (i > 0 && r != null && r.error == null) rtts.add(rttMs)
            send(Sample(Phase.Ping, median(rtts), quantJitter(rtts), null, null, null, 0, 0, i.toFloat() / pingN * 0.2f))
            delay(70)
        }
        val rttMed = median(rtts)
        val nJit = quantJitter(rtts)

        // ---- Uplink 帧（~4s：200 帧 × 20ms × 160B → /upload；chunk_us 权威到达）----
        val sent = AtomicInteger(0)
        var upResult: AnebClient.UploadResult? = null
        val upJob = launch(Dispatchers.IO) {
            upResult = runCatching {
                client.uploadPaced(
                    "$base/api/v1/upload?run=voice",
                    frames = UPLINK_FRAMES, frameBytes = FRAME_BYTES, intervalMs = FRAME_INTERVAL_MS,
                ) { n, _ -> sent.set(n) }
            }.getOrNull()
        }
        while (upJob.isActive) {
            val prog = 0.2f + sent.get().toFloat() / UPLINK_FRAMES * 0.35f
            send(Sample(Phase.Uplink, rttMed, nJit, null, null, null, sent.get(), 0, prog.coerceIn(0f, 0.55f)))
            delay(100)
        }
        val upIntervalsUs = upResult?.serverView?.chunkUs.orEmpty().zipWithNext { a, b -> b - a }
        val upJitter = frameJitterP95Ms(upIntervalsUs, FRAME_INTERVAL_MS * 1000)

        // ---- Downlink 帧（~4s：/stream?tokens=200&rate_tps=50，20ms 节奏；arrivalNanos）----
        val recv = AtomicInteger(0)
        var streamResult: AnebClient.StreamResult? = null
        val dlJob = launch(Dispatchers.IO) {
            streamResult = runCatching {
                client.stream(
                    "$base/api/v1/stream?tokens=$DOWNLINK_FRAMES&rate_tps=50&run=voice",
                    expectedTokens = DOWNLINK_FRAMES,
                    onEventArrival = { recv.incrementAndGet() },
                )
            }.getOrNull()
        }
        while (dlJob.isActive) {
            val prog = 0.55f + recv.get().toFloat() / DOWNLINK_FRAMES * 0.4f
            send(
                Sample(
                    Phase.Downlink, rttMed, nJit, upJitter, null, null,
                    sent.get(), recv.get(), prog.coerceIn(0f, 0.97f),
                )
            )
            delay(100)
        }
        val arrivals = streamResult?.stream?.events.orEmpty().map { it.arrivalNanos }
        val downIntervalsUs = arrivals.zipWithNext { a, b -> (b - a) / 1000 }
        val downJitter = frameJitterP95Ms(downIntervalsUs, FRAME_INTERVAL_MS * 1000)
        val maxGap = maxFrameGapMs(downIntervalsUs)
        val nearZeroRatio = nearZeroArrivalRatio(downIntervalsUs)

        // ---- Done：口到耳预算合成 ----
        val budget = mouthEarBudgetMs(rttMed, upJitter, downJitter)
        val actualFramesRecv = streamResult?.stream?.events?.size ?: recv.get()
        send(
            Sample(
                Phase.Done, rttMed, nJit, upJitter, downJitter, budget, sent.get(), actualFramesRecv, 1f,
                m7MaxFrameGapMs = maxGap,
                voiceNearZeroArrivalRatio = nearZeroRatio,
            )
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    //  v2 server-sim 口径（D-38）：/realtime-sim WebSocket 会话编排
    //  相位序 Ping(/echo)→Uplink(/upload 专供 M3)→Handshake→Turns(8 轮)→Done。
    //  协议/时戳合同见 RealtimeWire KDoc；协议致命错误一律抛出（fail-closed 不产部分分）。
    // ─────────────────────────────────────────────────────────────────────

    fun runSim(serverBase: String): Flow<Sample> = channelFlow {
        val raw = serverBase.trim().trimEnd('/')
        val base = ReachabilityProbe.deriveE01Pair(raw)?.second ?: raw

        // ---- Ping（同 v1 口径：12 次 echo 墙钟，丢首个建连样本 → N1/N2）----
        val rtts = ArrayList<Double>()
        for (i in 0 until 12) {
            val t0 = System.nanoTime()
            val r = withContext(Dispatchers.IO) { runCatching { client.echo("$base/api/v1/echo") }.getOrNull() }
            val rttMs = (System.nanoTime() - t0) / 1e6
            if (i > 0 && r != null && r.error == null) rtts.add(rttMs)
            send(Sample(Phase.Ping, median(rtts), quantJitter(rtts), null, null, null, 0, 0, i / 12f * 0.15f, caliber = SIM_CALIBER))
            delay(70)
        }
        val rttMed = median(rtts)
        val nJit = quantJitter(rtts)

        // ---- Uplink（v1 相位原样保留专供 M3：wire 无逐上行帧服务端时戳，chunk_us 仍是唯一权威）----
        val sent = AtomicInteger(0)
        var upResult: AnebClient.UploadResult? = null
        val upJob = launch(Dispatchers.IO) {
            upResult = runCatching {
                client.uploadPaced(
                    "$base/api/v1/upload?run=voice_sim",
                    frames = SIM_M3_FRAMES, frameBytes = FRAME_BYTES, intervalMs = FRAME_INTERVAL_MS,
                ) { n, _ -> sent.set(n) }
            }.getOrNull()
        }
        while (upJob.isActive) {
            send(Sample(Phase.Uplink, rttMed, nJit, null, null, null, sent.get(), 0, (0.15f + sent.get().toFloat() / SIM_M3_FRAMES * 0.15f).coerceIn(0f, 0.3f), caliber = SIM_CALIBER))
            delay(100)
        }
        val upIntervalsUs = upResult?.serverView?.chunkUs.orEmpty().zipWithNext { a, b -> b - a }
        val upJitter = frameJitterP95Ms(upIntervalsUs, FRAME_INTERVAL_MS * 1000)

        // ---- Handshake：WS 升级 + 计划首帧 + session_ready ----
        send(Sample(Phase.Handshake, rttMed, nJit, upJitter, null, null, sent.get(), 0, 0.32f, caliber = SIM_CALIBER))
        val plan = defaultSimPlan(seed = System.nanoTime())
        val session = client.realtimeSim(base, plan)
        session.connect()
        try {
            // 应用层指纹（fail-closed）：WS 101 升级响应不带 X-Aneb-Server（gorilla 自写 101、
            // 绕过 header 中间件，真机实证）；改校验 session_ready 回显的 session_id 与计划一致。
            val ready = awaitControl(session, type = "session_ready", timeoutMs = 15_000)
            check(ready.sessionId == plan.sessionId) { "session_ready id mismatch: ${ready.sessionId}" }

            // ---- Turns：逐轮 上行发帧→commit→收下行(可打断)→turn_summary ----
            val ledger = ArrayList<TurnLedger>(plan.turns.size)
            var framesRecvTotal = 0
            var backpressure = false
            val payload = ByteArray(plan.turns.first().uplinkFrameBytes) { (it * 31 + 17).toByte() }
            for ((i, t) in plan.turns.withIndex()) {
                session.sendText(RealtimeWire.jsonOut.encodeToString(RealtimeWire.TurnStart.serializer(), RealtimeWire.TurnStart(turnId = t.turnId, turnIndex = i)))
                // 上行绝对期限调度（不累积漂移）
                val t0 = android.os.SystemClock.elapsedRealtimeNanos()
                var lastUpEnqUs = 0L
                for (seq in 0 until t.uplinkFrames) {
                    val lagNs = t0 + seq * plan.frameMs * 1_000_000L - android.os.SystemClock.elapsedRealtimeNanos()
                    if (lagNs > 0) delay(lagNs / 1_000_000)
                    lastUpEnqUs = nowUs()
                    session.sendFrame(RealtimeWire.encodeUplink(i, seq, payload))
                }
                // 背压：入队打戳含排队 → 低置信。但采样点不能是"末帧刚入队时"——
                // 那一刻末帧自己就在队列里，queueSize>0 几乎恒真（T65/D-507 实证 35/35
                // 全 run 低置信，N1/M1/M3 置信标记失去区分力）。等一个帧周期让 socket
                // 排出，仍有积压才是真背压；commit 相应晚一帧周期，TTFB 等 KPI 均以
                // commitEnqUs 为基准相对计算，不受影响。
                delay(plan.frameMs.toLong())
                if (session.queueSize() > 0) backpressure = true
                val commitEnqUs = nowUs()
                session.sendText(RealtimeWire.jsonOut.encodeToString(RealtimeWire.SpeechCommit.serializer(), RealtimeWire.SpeechCommit(turnId = t.turnId)))

                // 收下行帧直至 turn_summary；打断轮在第 bargeInAfterFrames 帧后发 barge_in
                val frames = ArrayList<RealtimeWire.DownFrame>(t.plannedDownlinkFrames)
                var bargeEnqUs: Long? = null
                var summary: RealtimeWire.InboundControl? = null
                var summaryArrivalUs = 0L
                kotlinx.coroutines.withTimeout(20_000) {
                    while (summary == null) {
                        when (val m = session.inbound.receive()) {
                            is RealtimeSimSession.In.Frame -> {
                                val f = RealtimeWire.decodeDownlink(m.bytes, m.arrivalUs) ?: continue
                                if (f.turnIndex != i) continue // 迷途帧不入统计
                                frames.add(f)
                                if (t.interrupted && bargeEnqUs == null && frames.size == t.bargeInAfterFrames) {
                                    bargeEnqUs = nowUs()
                                    session.sendText(RealtimeWire.jsonOut.encodeToString(RealtimeWire.BargeIn.serializer(), RealtimeWire.BargeIn(turnId = t.turnId)))
                                }
                            }
                            is RealtimeSimSession.In.Text -> {
                                val c = RealtimeWire.jsonIn.decodeFromString(RealtimeWire.InboundControl.serializer(), m.text)
                                when (c.type) {
                                    "turn_summary" -> { summary = c; summaryArrivalUs = m.arrivalUs }
                                    "error" -> error("realtime-sim error: ${c.message}")
                                    else -> Unit // pong 等
                                }
                            }
                        }
                    }
                }
                framesRecvTotal += frames.size
                val turnLedger = TurnLedger(t, commitEnqUs, lastUpEnqUs, bargeEnqUs, frames, summary!!, summaryArrivalUs)
                ledger.add(turnLedger)
                val liveHistory = ledger.map { l ->
                    val first = l.frames.firstOrNull()
                    val dwellUs = if (l.summary.firstDownlinkPreWriteUs != null && l.summary.commitRecvUs != null) {
                        l.summary.firstDownlinkPreWriteUs - l.summary.commitRecvUs
                    } else null
                    val turnTtfb = if (first != null && dwellUs != null) (first.arrivalUs - l.commitEnqUs - dwellUs) / 1000.0 else null
                    val turnMouthEar = if (first != null && dwellUs != null) (first.arrivalUs - l.lastUpEnqUs - dwellUs) / 1000.0 + CODEC_JB_BUDGET_MS else null
                    val turnGaps = l.frames.zipWithNext { a, b -> b.arrivalUs - a.arrivalUs }
                    val turnBarge = if (l.bargeEnqUs != null && l.frames.isNotEmpty()) (l.frames.last().arrivalUs - l.bargeEnqUs) / 1000.0 else null
                    TurnLivePoint(
                        turnIndex = l.plan.turnIndex,
                        interrupted = l.plan.interrupted,
                        mouthEarMs = turnMouthEar,
                        ttfbMs = turnTtfb,
                        maxGapMs = maxFrameGapMs(turnGaps),
                        bargeStopMs = turnBarge,
                        protocolOk = l.summary.protocolOk == true,
                    )
                }
                val runningMouthEar = median(liveHistory.mapNotNull { it.mouthEarMs })
                val runningTtfb = median(liveHistory.mapNotNull { it.ttfbMs })
                val runningM7 = liveHistory.mapNotNull { it.maxGapMs }.maxOrNull()
                send(
                    Sample(
                        Phase.Turns, rttMed, nJit, upJitter, null, null, sent.get(), framesRecvTotal,
                        (0.35f + (i + 1f) / plan.turns.size * 0.6f).coerceIn(0f, 0.97f),
                        ttfbP50Ms = runningTtfb,
                        mouthEarProxyMs = runningMouthEar,
                        bargeStopMaxMs = liveHistory.mapNotNull { it.bargeStopMs }.maxOrNull(),
                        turnsOk = ledger.count { it.summary.protocolOk == true },
                        caliber = SIM_CALIBER, lowConfidence = backpressure,
                        m7MaxFrameGapMs = runningM7,
                        liveTurnHistory = liveHistory,
                        totalPlannedTurns = plan.turns.size,
                    )
                )
            }
            awaitControl(session, type = "session_summary", timeoutMs = 10_000)

            // ---- KPI 合成（剥离公式见 D-38 设计 §2；服务端时戳同基准两两相减免钟偏）----
            check(ledger.all { it.summary.protocolOk == true }) { "protocol_ok=false in ${ledger.count { it.summary.protocolOk != true }} turns" }
            val ttfbs = ledger.mapNotNull { l ->
                val first = l.frames.firstOrNull() ?: return@mapNotNull null
                val dwellUs = (l.summary.firstDownlinkPreWriteUs ?: return@mapNotNull null) - (l.summary.commitRecvUs ?: return@mapNotNull null)
                (first.arrivalUs - l.commitEnqUs - dwellUs) / 1000.0
            }
            val jitterDevsUs = ledger.flatMap { l ->
                l.frames.zipWithNext { a, b -> abs((b.arrivalUs - a.arrivalUs) - (b.schedUs - a.schedUs)) }
            }
            // M7/近零占比要的是原始到达间隔，不是对名义节奏的偏差（jitterDevsUs 是后者）；
            // 按轮内 zipWithNext 池化，轮间切换时长已由 M5 单独承接，不与静默混同。
            val downRawIntervalsUs = ledger.flatMap { l ->
                l.frames.zipWithNext { a, b -> b.arrivalUs - a.arrivalUs }
            }
            val mouthEars = ledger.mapNotNull { l ->
                val first = l.frames.firstOrNull() ?: return@mapNotNull null
                val dwellUs = (l.summary.firstDownlinkPreWriteUs ?: return@mapNotNull null) - (l.summary.commitRecvUs ?: return@mapNotNull null)
                (first.arrivalUs - l.lastUpEnqUs - dwellUs) / 1000.0 + CODEC_JB_BUDGET_MS
            }
            val switches = ledger.zipWithNext { prev, next ->
                val first = next.frames.firstOrNull() ?: return@zipWithNext null
                val dwellUs = (next.summary.firstDownlinkPreWriteUs ?: return@zipWithNext null) - (next.summary.commitRecvUs ?: return@zipWithNext null)
                val uplinkUs = next.plan.uplinkFrames.toLong() * plan.frameMs * 1000L
                (first.arrivalUs - prev.summaryArrivalUs - uplinkUs - dwellUs) / 1000.0
            }.filterNotNull()
            val bargeStops = ledger.mapNotNull { l ->
                val enq = l.bargeEnqUs ?: return@mapNotNull null
                val last = l.frames.lastOrNull() ?: return@mapNotNull null
                (last.arrivalUs - enq) / 1000.0
            }
            val finalLiveHistory = ledger.map { l ->
                val first = l.frames.firstOrNull()
                val dwellUs = if (l.summary.firstDownlinkPreWriteUs != null && l.summary.commitRecvUs != null) {
                    l.summary.firstDownlinkPreWriteUs - l.summary.commitRecvUs
                } else null
                val turnTtfb = if (first != null && dwellUs != null) (first.arrivalUs - l.commitEnqUs - dwellUs) / 1000.0 else null
                val turnMouthEar = if (first != null && dwellUs != null) (first.arrivalUs - l.lastUpEnqUs - dwellUs) / 1000.0 + CODEC_JB_BUDGET_MS else null
                val turnGaps = l.frames.zipWithNext { a, b -> b.arrivalUs - a.arrivalUs }
                val turnBarge = if (l.bargeEnqUs != null && l.frames.isNotEmpty()) (l.frames.last().arrivalUs - l.bargeEnqUs) / 1000.0 else null
                TurnLivePoint(
                    turnIndex = l.plan.turnIndex,
                    interrupted = l.plan.interrupted,
                    mouthEarMs = turnMouthEar,
                    ttfbMs = turnTtfb,
                    maxGapMs = maxFrameGapMs(turnGaps),
                    bargeStopMs = turnBarge,
                    protocolOk = l.summary.protocolOk == true,
                )
            }
            send(
                Sample(
                    Phase.Done, rttMed, nJit, upJitter, null, null, sent.get(), framesRecvTotal, 1f,
                    ttfbP50Ms = median(ttfbs),
                    ttfbP95Ms = p95(ttfbs),
                    downNetJitterMs = if (jitterDevsUs.size < 2) null else p95(jitterDevsUs.map { it / 1000.0 }),
                    mouthEarProxyMs = median(mouthEars),
                    turnSwitchP50Ms = median(switches),
                    bargeStopMaxMs = bargeStops.maxOrNull(),
                    turnsOk = ledger.count { it.summary.protocolOk == true },
                    caliber = SIM_CALIBER,
                    lowConfidence = backpressure,
                    m7MaxFrameGapMs = maxFrameGapMs(downRawIntervalsUs),
                    voiceNearZeroArrivalRatio = nearZeroArrivalRatio(downRawIntervalsUs),
                    liveTurnHistory = finalLiveHistory,
                    totalPlannedTurns = plan.turns.size,
                )
            )
        } finally {
            session.cancel()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  受控断连连续性 mini-run（D-41 预定）：controlled_disconnect_after_turn 口径
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 语音连续性 mini-run：**服务端受控 WS 硬关**（TEST_SERVER_CAPABILITIES §2
     * "连接级受控中断"），**非真实蜂窝断网**。序：[continuitySimPlan] 3 轮计划 +
     * `controlled_disconnect_after_turn=1` 连接 → 跑轮 0..1（轮 1 的 turn_summary 合同
     * 保证照发）→ 服务端裸关 TCP（无 close 帧）→ 客户端 onFailure 浮出
     * [RealtimeSimSession.TransportClosed]（inbound channel 带因关闭）：
     * - **检出** [Sample.continuityDetectMs]＝TransportClosed.atUs − 轮 1 turn_summary 到达
     *   （同基客户端单调 us）；~20s 未浮出 → null（R-10 诚实缺席，不以超时值顶替）。
     * - **重建** [Sample.continuityResumeMs]＝全新会话（[continuityResumePlan]，新 session_id、
     *   无 disconnect 参数）session_ready 到达 − 失败浮出；随后跑完该轮 + session_summary
     *   证明会话可用。检出缺席（无失败浮出锚点）则重建亦 null。
     * 单次受控事件，观测口径，**不进任何分**（LOW/INCONCLUSIVE）；与 ContinuityRecovery
     * （D-23 真实跨网迁移恢复）严格分口径，不可互相替代或比较。协议错误一律抛出
     * （fail-closed，不产部分结论）。
     */
    fun runSimContinuity(serverBase: String): Flow<Sample> = channelFlow {
        // D-25：E-01 sslip SNI-RST → bare-IP 等价基址（同 runSim）
        val raw = serverBase.trim().trimEnd('/')
        val base = ReachabilityProbe.deriveE01Pair(raw)?.second ?: raw

        send(Sample(Phase.Handshake, null, null, null, null, null, 0, 0, 0.05f, caliber = SIM_CALIBER))
        val plan = continuitySimPlan(seed = System.nanoTime())
        var framesSentTotal = 0
        var framesRecvTotal = 0
        var closedAtUs: Long? = null
        var detectMs: Double? = null

        // ---- 断连会话：轮 0..1 → 轮 1 summary 后服务端裸关 → TransportClosed 浮出 ----
        val session = client.realtimeSim(base, plan, disconnectAfterTurn = CONT_DISCONNECT_AFTER_TURN)
        session.connect()
        try {
            val ready = awaitControlTimed(session, type = "session_ready", timeoutMs = 15_000).first
            check(ready.sessionId == plan.sessionId) { "session_ready id mismatch: ${ready.sessionId}" }

            var lastSummaryArrivalUs = 0L
            for (i in 0..CONT_DISCONNECT_AFTER_TURN) {
                val t = plan.turns[i]
                val (recv, summaryArrivalUs) = driveTurn(session, plan.frameMs, t)
                framesSentTotal += t.uplinkFrames
                framesRecvTotal += recv
                lastSummaryArrivalUs = summaryArrivalUs
                send(
                    Sample(
                        Phase.Turns, null, null, null, null, null, framesSentTotal, framesRecvTotal,
                        0.1f + (i + 1) * 0.2f, caliber = SIM_CALIBER,
                    )
                )
            }

            // 检出：下一次 receive 因裸关抛 TransportClosed；~20s 未浮出 → null（R-10）
            closedAtUs = kotlinx.coroutines.withTimeoutOrNull(20_000) {
                try {
                    while (true) {
                        session.inbound.receive() // 排空迷途消息直至传输终结（session_summary 合同上不再来）
                    }
                    @Suppress("UNREACHABLE_CODE")
                    error("unreachable")
                } catch (e: RealtimeSimSession.TransportClosed) {
                    e.atUs
                }
            }
            detectMs = closedAtUs?.let { (it - lastSummaryArrivalUs) / 1000.0 }
            send(
                Sample(
                    Phase.Turns, null, null, null, null, null, framesSentTotal, framesRecvTotal, 0.6f,
                    caliber = SIM_CALIBER, continuityDetectMs = detectMs,
                )
            )
        } finally {
            session.cancel()
        }

        // ---- 重建：全新 1 轮计划/新 session_id（无 disconnect 参数），跑完 + session_summary ----
        val plan2 = continuityResumePlan(seed = System.nanoTime())
        val session2 = client.realtimeSim(base, plan2)
        session2.connect()
        var resumeMs: Double? = null
        try {
            val (ready2, readyArrivalUs) = awaitControlTimed(session2, type = "session_ready", timeoutMs = 15_000)
            check(ready2.sessionId == plan2.sessionId) { "session_ready id mismatch: ${ready2.sessionId}" }
            resumeMs = closedAtUs?.let { (readyArrivalUs - it) / 1000.0 } // 检出缺席→无锚点，重建亦 null（R-10）
            send(
                Sample(
                    Phase.Turns, null, null, null, null, null, framesSentTotal, framesRecvTotal, 0.75f,
                    caliber = SIM_CALIBER, continuityDetectMs = detectMs, continuityResumeMs = resumeMs,
                )
            )
            val t2 = plan2.turns.first()
            val (recv2, _) = driveTurn(session2, plan2.frameMs, t2)
            framesSentTotal += t2.uplinkFrames
            framesRecvTotal += recv2
            awaitControlTimed(session2, type = "session_summary", timeoutMs = 10_000) // 证明会话可用
        } finally {
            session2.cancel()
        }
        send(
            Sample(
                Phase.Done, null, null, null, null, null, framesSentTotal, framesRecvTotal, 1f,
                caliber = SIM_CALIBER, continuityDetectMs = detectMs, continuityResumeMs = resumeMs,
            )
        )
    }

    /**
     * 跑完一轮：turn_start → 绝对期限节奏上行（不累积漂移）→ speech_commit → 收下行帧直至
     * turn_summary。与 [runSim] 轮循环同构（连续性计划全轮非中断，无 barge 分支）；
     * summary.protocol_ok!=true 或 error 型即抛（fail-closed）。
     * @return (本轮收帧数, turn_summary 客户端到达 us)
     */
    private suspend fun driveTurn(
        session: RealtimeSimSession,
        frameMs: Int,
        t: RealtimeWire.TurnPlan,
    ): Pair<Int, Long> {
        session.sendText(RealtimeWire.jsonOut.encodeToString(RealtimeWire.TurnStart.serializer(), RealtimeWire.TurnStart(turnId = t.turnId, turnIndex = t.turnIndex)))
        val payload = ByteArray(t.uplinkFrameBytes) { (it * 31 + 17).toByte() }
        val t0 = android.os.SystemClock.elapsedRealtimeNanos()
        for (seq in 0 until t.uplinkFrames) {
            val lagNs = t0 + seq * frameMs * 1_000_000L - android.os.SystemClock.elapsedRealtimeNanos()
            if (lagNs > 0) delay(lagNs / 1_000_000)
            session.sendFrame(RealtimeWire.encodeUplink(t.turnIndex, seq, payload))
        }
        session.sendText(RealtimeWire.jsonOut.encodeToString(RealtimeWire.SpeechCommit.serializer(), RealtimeWire.SpeechCommit(turnId = t.turnId)))
        var recv = 0
        var summaryArrivalUs = 0L
        kotlinx.coroutines.withTimeout(20_000) {
            var done = false
            while (!done) {
                when (val m = session.inbound.receive()) {
                    is RealtimeSimSession.In.Frame -> {
                        val f = RealtimeWire.decodeDownlink(m.bytes, m.arrivalUs) ?: continue // 坏帧不入统计（R-10）
                        if (f.turnIndex == t.turnIndex) recv++
                    }
                    is RealtimeSimSession.In.Text -> {
                        val c = RealtimeWire.jsonIn.decodeFromString(RealtimeWire.InboundControl.serializer(), m.text)
                        when (c.type) {
                            "turn_summary" -> {
                                check(c.protocolOk == true) { "protocol_ok=false turn=${t.turnIndex}" }
                                summaryArrivalUs = m.arrivalUs
                                done = true
                            }
                            "error" -> error("realtime-sim error: ${c.message}")
                            else -> Unit // pong 等
                        }
                    }
                }
            }
        }
        return recv to summaryArrivalUs
    }

    private class TurnLedger(
        val plan: RealtimeWire.TurnPlan,
        val commitEnqUs: Long,
        val lastUpEnqUs: Long,
        val bargeEnqUs: Long?,
        val frames: List<RealtimeWire.DownFrame>,
        val summary: RealtimeWire.InboundControl,
        val summaryArrivalUs: Long,
    )

    /** 等待指定 type 的控制消息（跳过其它 TEXT；error 型即抛，fail-closed）。 */
    private suspend fun awaitControl(session: RealtimeSimSession, type: String, timeoutMs: Long): RealtimeWire.InboundControl =
        awaitControlTimed(session, type, timeoutMs).first

    /** 同 [awaitControl]，但连同客户端到达 us 一并返回（连续性 mini-run 重建打点需要）。 */
    private suspend fun awaitControlTimed(session: RealtimeSimSession, type: String, timeoutMs: Long): Pair<RealtimeWire.InboundControl, Long> =
        kotlinx.coroutines.withTimeout(timeoutMs) {
            while (true) {
                val m = session.inbound.receive()
                if (m !is RealtimeSimSession.In.Text) continue
                val c = RealtimeWire.jsonIn.decodeFromString(RealtimeWire.InboundControl.serializer(), m.text)
                when (c.type) {
                    type -> return@withTimeout c to m.arrivalUs
                    "error" -> error("realtime-sim error: ${c.message}")
                    else -> Unit
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    private fun nowUs(): Long = android.os.SystemClock.elapsedRealtimeNanos() / 1000

    private fun p95(xs: List<Double>): Double? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        val rank = ceil(0.95 * s.size).toInt().coerceIn(1, s.size)
        return s[rank - 1]
    }

    private fun median(xs: List<Double>): Double? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    private fun quantJitter(xs: List<Double>): Double? {
        if (xs.size < 2) return null
        return median(xs.zipWithNext { a, b -> abs(b - a) })
    }
}
