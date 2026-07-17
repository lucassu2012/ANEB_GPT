package com.aneb.probe.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.room.withTransaction
import com.aneb.probe.data.AnebDatabase
import com.aneb.probe.data.EchoSampleEntity
import com.aneb.probe.data.EnvEvent
import com.aneb.probe.data.EnvEventType
import com.aneb.probe.data.ScenarioResultEntity
import com.aneb.probe.data.TestRun
import com.aneb.probe.data.TokenEventEntity
import com.aneb.probe.net.AnebClient
import com.aneb.probe.net.BoundNetwork
import com.aneb.probe.net.GuardException
import com.aneb.probe.net.NetGuard
import com.aneb.probe.net.PathMonitor
import com.aneb.probe.net.ReachabilityProbe
import com.aneb.probe.radio.LocationTagger
import com.aneb.probe.radio.RadioCollector
import com.aneb.probe.radio.RadioSample
import com.aneb.probe.scoring.AqsScorer
import com.aneb.probe.scoring.BufferingDetector
import com.aneb.probe.scoring.BufferingReport
import com.aneb.probe.scoring.InvalidReason
import com.aneb.probe.scoring.KpiCalculator
import com.aneb.probe.scoring.KpiResult
import com.aneb.probe.scoring.KpiValue
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 阶段 1 统一接线（P1-C05+C06）：profile 驱动三场景 × 快测/取证双模式 ×
 * 守卫全程监控 × KPI/AQS × Room 全量落库 × 结果上报。
 *
 * 日志合同：所有汇总行 `KEY key=value ...`（无引号、值内无空格），供模拟器自动化验收
 * 提取。KEY 全集：RUN_START / GUARD_OK / GUARD_REJECT / NET_BIND / NET_BIND_FAIL /
 * PROFILES / PROFILE_WARN / ORDER / SCENARIO_START / CLOCK_SYNC / SKEW / THINK_PAUSE /
 * UPLOAD / STREAM / PARSE / TOOLLOOP / SCENARIO_ABORT / SCENARIO_INVALID / SCENARIO_KPI /
 * PHASE_SKIP / RUN_ABORT / RUN_FAILED / AQS_INPUT_MAP / AQS / DB_WRITE / REPORT /
 * REPORT_CONTRACT_ERRORS / REPORT_SIZE_WARN / RUN_END。
 * 阶段3 遗留接线新增 KEY（additive，不动既有 KEY 语义）：BUFFERING（每场景批化标注，
 * P1-C08；R-05 分数不改 validity）/ AQS_V02（run 级 v0.2 并列出分，阶段2 C03）/
 * DRIVE_TEST（GPS 路测开启时的打点器状态；坐标只入本地，绝不进上报体 §9.1）。
 */
class TestEngine(private val context: Context) {

    /**
     * 实时分层遥测只读通道（观测通道，与测量记录口径解耦，不参与测量，R-16）。
     * 由 run() 内独立采样协程（Dispatchers.Default, ~100ms 节流）读取引擎既有已记录状态
     * 派生并 conflated 发射；run 结束/取消时复位为 [LiveTelemetry.EMPTY]。UI 只读观测，
     * 绝不回压测量热路径——发射只在采样协程，不在 SSE 读线程/计时回调内进行。
     */
    private val _telemetry = MutableStateFlow(LiveTelemetry.EMPTY)
    val telemetry: StateFlow<LiveTelemetry> = _telemetry.asStateFlow()

    enum class Mode { QUICK, FORENSIC, STRESS, NETWORK_RECOVERY, GATEWAY_LOSS, GATEWAY_RECOVERY }

    /** transport 策略（P1 范围 3）：AUTO=不绑定仅监控（模拟器用 AUTO） */
    enum class TransportMode { AUTO, WIFI, CELLULAR }

    data class RunConfig(
        val serverBase: String,
        val mode: Mode = Mode.QUICK,
        val transport: TransportMode = TransportMode.AUTO,
        /** /stream 故障注入透传（C09 前置）；调用方（UI）必须用 BuildConfig.DEBUG 门控 */
        val inject: String? = null,
        /**
         * GPS 路测模式（阶段3；默认关）：开启时 run 全程随 RadioCollector 1Hz 附带
         * lat/lon/accuracy 打点。隐私边界（§9.1）：坐标只入本地 Room 与本地导出，
         * 绝不进 /results 上报体（ResultReporter 无坐标字段，单测锚定）。
         */
        val driveTest: Boolean = false,
    )

    fun run(config: RunConfig): Flow<String> = channelFlow {
        val log: suspend (String) -> Unit = { send(it) }
        val db = AnebDatabase.get(context)
        val runId = newRunId()
        val startedAtEpochMs = System.currentTimeMillis()
        val base = config.serverBase.trim().trimEnd('/')
        val modeStr = config.mode.name.lowercase()
        val transportStr = config.transport.name.lowercase()
        log(
            "RUN_START run_id=$runId mode=$modeStr transport=$transportStr server=$base " +
                "inject=${config.inject ?: "none"} drive_test=${config.driveTest}"
        )

        // ---------------- 守卫硬拒测（R-03：VPN/代理） ----------------
        val guard = NetGuard.guardCheck(context)
        val guardMeta = guard.metadata.entries.joinToString(";") { "${it.key}=${it.value}" }
        if (!guard.ok) {
            log("GUARD_REJECT reasons=${guard.reasons.joinToString(",")}")
            persistRun(
                db, baseRun(runId, startedAtEpochMs, base, modeStr, transportStr, "", "none", guardMeta)
                    .copy(status = "guard_rejected:${guard.reasons.joinToString(",")}"),
            )
            log("RUN_END run_id=$runId status=guard_rejected")
            return@channelFlow
        }
        log("GUARD_OK metadata=${guardMeta.replace(' ', '_')}")

        // ---------------- 网络绑定（transport 策略，R-01） ----------------
        val bound: BoundNetwork? = try {
            when (config.transport) {
                TransportMode.AUTO -> null
                TransportMode.WIFI -> NetGuard.acquireNetwork(context, NetworkCapabilities.TRANSPORT_WIFI)
                TransportMode.CELLULAR -> NetGuard.acquireNetwork(context, NetworkCapabilities.TRANSPORT_CELLULAR)
            }
        } catch (e: GuardException) {
            log("NET_BIND_FAIL transport=$transportStr error=${e.message?.replace(' ', '_')}")
            persistRun(
                db, baseRun(runId, startedAtEpochMs, base, modeStr, transportStr, "", "none", guardMeta)
                    .copy(status = "bind_failed"),
            )
            log("RUN_END run_id=$runId status=bind_failed")
            return@channelFlow
        }
        bound?.let { log("NET_BIND transport=$transportStr snapshot=${it.snapshot.capabilities.replace(' ', '_')}") }

        val client = AnebClient(bound)

        // ---------------- SNI 双通道连接可达性探测（阶段3，additive best-effort） ----------------
        // run 前对同一 E-01 分别用 {带 SNI 主机名, bare-IP} 各发 1 次 /serverinfo，
        // 把电信 SNI-keyed TLS RST 变成可量化维度（带 SNI vs bare-IP 成功率）。
        // 仅当目标是 E-01（sslip 主机名或 bare-IP）时探测；非 E-01 保持 null（未探测）。
        // 探测失败绝不影响测量（runCatching 兜底）；WiFi/公共域名路径行为不变。
        var reach: ReachabilityProbe.DualReach? = null
        ReachabilityProbe.deriveE01Pair(base)?.let { (sniBase, ipBase) ->
            reach = runCatching {
                ReachabilityProbe(bound).probeDual(sniBase, ipBase)
            }.getOrNull()
            reach?.let {
                log(
                    "REACH sni=${it.sni.status} sni_ms=${it.sni.elapsedMs ?: "null"} " +
                        "ip=${it.ip.status} ip_ms=${it.ip.elapsedMs ?: "null"}"
                )
            }
        }

        // SNI-RST 自动旁路（D-25）：run 前若探到 SNI 通道被 RST 而 bare-IP 通道可达，把测量端点
        // 切到 bare-IP 等价基址（同节点同物理路径，仅换 SNI/证书绕过 DPI 的 SNI-keyed RST，
        // claim_scope 不变、无测量偏差）；否则保持配置端点。后续 profiles/场景/上报/落库均用 measureBase。
        val measureBase = ReachabilityProbe.preferredMeasureBase(base, reach)
        if (measureBase != base) {
            log("REACH_SWITCH from=sni_host to=bare_ip reason=sni_rst_ip_ok base=$measureBase")
        }

        // ---------------- profiles（服务端拉取，assets 兜底） ----------------
        val loaded = try {
            ProfileRepository(context).load(client, measureBase)
        } catch (e: Exception) {
            log("RUN_FAILED run_id=$runId error=profiles_unavailable:${e.javaClass.simpleName}")
            bound?.release()
            persistRun(
                db, baseRun(runId, startedAtEpochMs, base, modeStr, transportStr, "", "none", guardMeta)
                    .copy(status = "profiles_unavailable"),
            )
            log("RUN_END run_id=$runId status=profiles_unavailable")
            return@channelFlow
        }
        val profileVersions = ProfileParser.versionString(loaded.profiles)
        log("PROFILES source=${loaded.source} versions=$profileVersions")
        loaded.warnings.forEach { log("PROFILE_WARN ${it.replace(' ', '_')}") }

        // ---------------- 全程监控（R-01/R-12/R-16 + RadioCollector 1Hz） ----------------
        val envBuf = ConcurrentLinkedQueue<EnvEvent>()
        val radioBuf = ConcurrentLinkedQueue<RadioSample>()
        // 实时遥测投影源（观测通道，非测量）：引擎在既有记录点追加式填充，采样协程只读。
        val telemetrySource = TelemetrySource()
        // SSE 读线程仅写 event 到达戳；独立遥测协程读取 1 秒滑窗，绝不反压测量热路径。
        val liveStreamWindow = LiveStreamWindow()
        val invalidReason = AtomicReference<String?>(null)
        val currentScenario = AtomicReference<Job?>(null)
        fun invalidate(reason: String) {
            if (invalidReason.compareAndSet(null, reason)) {
                currentScenario.get()?.cancel(CancellationException("invalidated:$reason"))
            }
        }

        val envMonitors = EnvMonitors(context)
        // GPS 路测（阶段3）：开关开启才创建 LocationTagger；坐标只随 RadioSample 入本地
        // Room（§9.1 隐私边界——上报体无坐标字段）。注册失败/权限缺失静默降级＝坐标列 null。
        val locationTagger = if (config.driveTest) LocationTagger(context) else null
        val radio = RadioCollector(context, locationTagger?.let { it::current })
        val pathWatch: PathWatch = if (bound != null) {
            BoundPathWatch(context, bound, envBuf::add, ::invalidate)
        } else {
            DefaultNetWatch(context, envBuf::add, ::invalidate) // AUTO：不绑定仅监控默认网
        }

        val collectors = mutableListOf<Job>()
        var radioShareJob: Job? = null
        var status = "completed"
        var reportStatus: String? = null
        var aqs: AqsScorer.AqsResult? = null
        val orderRecord = ArrayList<String>()
        val scenarioReports = ArrayList<Pair<ScenarioResultEntity, ItlHistogram>>()

        try {
            envMonitors.start()
            pathWatch.start()
            locationTagger?.let {
                it.start() // 主线程 Looper 回调，仅更新 AtomicReference，极薄
                log("DRIVE_TEST enabled=true gps_active=${it.active}")
            }
            collectors += launch {
                envMonitors.events.collect { ev ->
                    envBuf.add(ev)
                    // §4.6：测中 Doze/省电状态变化 → invalid（初始状态行除外）
                    if ((ev.type == EnvEventType.POWER_SAVE || ev.type == EnvEventType.DOZE) &&
                        !ev.detail.startsWith("initial")
                    ) {
                        invalidate("power_state_changed:${ev.type.name.lowercase()}")
                    }
                }
            }
            collectors += launch { radio.events.collect { envBuf.add(it) } }
            // C07 联调修复（生命周期，不改测量语义）：shareIn 的共享协程随 scope 存活，
            // 直接挂在 channelFlow scope 会使 run flow 永不完成（collect 端挂死）——
            // 放进可显式取消的子 SupervisorJob，finally 统一收尸。
            val shareJob = kotlinx.coroutines.SupervisorJob(coroutineContext[Job])
            radioShareJob = shareJob
            val radioFlow = radio.start(kotlinx.coroutines.CoroutineScope(coroutineContext + shareJob))
            // 最近无线样本的 O(1) 只读引用：随 radioBuf.add 同步更新，供采样协程读取，
            // 避免 radioBuf.lastOrNull() 对 ConcurrentLinkedQueue 的 O(n) 全量遍历（取证 run 更明显）。
            val latestRadio = AtomicReference<RadioSample?>(null)
            collectors += launch { radioFlow.collect { radioBuf.add(it); latestRadio.set(it) } }

            // ---- 实时遥测采样协程（观测通道，非测量；Dispatchers.Default, ~100ms 节流）----
            // 只读 latestRadio 引用（O(1)，不消费队列）+ telemetrySource 投影 → derive → conflated
            // 发射。绝不在 SSE 读线程/计时回调发射（R-16）；随 collectors 在 finally 统一取消。
            collectors += launch(Dispatchers.Default) {
                while (true) {
                    val liveStream = liveStreamWindow.snapshot(SystemClock.elapsedRealtimeNanos())
                    val snapshot = telemetrySource.read(latestRadio.get()).copy(
                        streamArrivalRatePerSec = liveStream.arrivalRatePerSec,
                        streamTargetRatePerSec = liveStream.targetRatePerSec,
                        streamActive = liveStream.active,
                    )
                    _telemetry.value = LiveTelemetry.derive(snapshot)
                    delay(TELEMETRY_SAMPLE_MS)
                }
            }

            // ---------------- 场景循环（快测 1 遍 / 取证 3 遍拉丁方轮转） ----------------
            val ids = ProfileParser.REQUIRED_IDS
            val rounds = when (config.mode) {
                Mode.QUICK -> LatinSquare.quickOrder(ids.size)
                Mode.FORENSIC -> LatinSquare.orders(ids.size)
                Mode.STRESS -> error("stress_not_supported_for_legacy_engine")
                Mode.NETWORK_RECOVERY, Mode.GATEWAY_LOSS, Mode.GATEWAY_RECOVERY ->
                    error("network_lab_mode_not_supported_for_legacy_engine")
            }
            val runner = ScenarioRunner(client, liveStreamWindow)
            val kpiByScenario = LinkedHashMap<String, MutableList<KpiResult>>()
            var orderIndex = 0
            // 实时遥测累计投影（观测通道，非测量）：总场景数用于进度分母；ITL/token 累计
            // 与既有 KPI 同源，只是节流暴露给 UI。
            val totalScenarios = rounds.sumOf { it.size }.coerceAtLeast(1)
            val liveItl = ArrayList<Double>()
            var liveTokens = 0
            var liveTokenElapsedSec = 0.0

            runLoop@ for ((round, order) in rounds.withIndex()) {
                orderRecord.add(order.joinToString(",") { ids[it] })
                log("ORDER round=$round order=${order.joinToString(",") { ids[it] }}")
                for (pos in order) {
                    val profile = loaded.profiles.getValue(ids[pos])
                    val scenarioKey = "${profile.profileId}#$round"
                    log("SCENARIO_START scenario=$scenarioKey round=$round order_index=$orderIndex")
                    // 遥测投影：进度阶段（观测通道，非测量）
                    telemetrySource.update {
                        it.copy(phase = scenarioKey, fraction = orderIndex.toDouble() / totalScenarios)
                    }
                    val outcome = ScenarioRunner.ScenarioOutcome(profile, scenarioKey)
                    val netSnap = bound?.snapshot ?: autoNetSnapshot(context)
                    var engineError: String? = null

                    // 场景跑在子 Job 内：守卫 invalidate → cancel 该 Job（中止场景、
                    // executeCancellable 链取消 in-flight 请求），run 主协程继续善后。
                    // LAZY 注册→检查→start 的无竞态协议见 ScenarioGate KDoc（评审发现 3）
                    val job = ScenarioGate.launchGuarded(this, invalidReason, currentScenario) {
                        try {
                            runner.run(measureBase, runId, outcome, config.inject, log)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            engineError = e.toString()
                        }
                    }
                    job.join()
                    currentScenario.set(null)

                    val invalidated = invalidReason.get()
                    val external = mutableListOf<InvalidReason>()
                    if (invalidated != null) {
                        external += when {
                            invalidated.startsWith("power_state_changed") -> InvalidReason.GUARD_FAILED
                            // 监控器自身故障 ≠ 路径真的变了：分流成 MONITOR_FAILURE（评审发现 5）
                            invalidated == "connectivity_manager_unavailable" ||
                                invalidated == "path_monitor_registration_failed" -> InvalidReason.MONITOR_FAILURE
                            else -> InvalidReason.PATH_CHANGED
                        }
                        log("SCENARIO_INVALID scenario=$scenarioKey reason=$invalidated")
                    }
                    if (engineError != null) {
                        external += InvalidReason.ENGINE_ERROR
                        log("SCENARIO_INVALID scenario=$scenarioKey reason=engine_error:${engineError!!.replace(' ', '_')}")
                    }

                    // ---- KPI（seq join / null 语义 / 三态 Gate 全在 KpiCalculator） ----
                    val input = ScenarioKpi.buildKpiInput(outcome, external)
                    val kpi = KpiCalculator.calculate(input)
                    kpiByScenario.getOrPut(profile.profileId) { mutableListOf() }.add(kpi)

                    // ---- 实时遥测投影（观测通道，非测量；run 主协程、非热路径）----
                    // ITL 与既有直方图/KPI 同源（correctedItlSamplesMs）；RTT 同源首个
                    // clock_sync；upload goodput 同源 U1 终点。只读 outcome，不改任何落库/计时。
                    liveItl.addAll(ScenarioKpi.correctedItlSamplesMs(input.tokenSamples, input.pauseSeqs))
                    val evAll = outcome.streams.flatMap { it.result.stream?.events ?: emptyList() }
                    liveTokens += evAll.size
                    if (evAll.size >= 2) {
                        val arr = evAll.map { it.arrivalNanos }
                        liveTokenElapsedSec += (arr.max() - arr.min()) / 1e9
                    }
                    val liveUpMbps = outcome.uploads.lastOrNull()?.let { up ->
                        up.durationNanos?.let { d -> if (d > 0) up.profileBytes * 8.0 / (d / 1e9) / 1e6 else null }
                    }
                    val liveRttMs = outcome.clockSyncs.firstOrNull()?.samples.orEmpty()
                        .filter { !it.warmup && it.result.error == null && it.result.rttUs != null }
                        .map { it.result.rttUs!! / 1000.0 }
                    val liveTtft = outcome.streams.lastOrNull()?.ttftMs
                    telemetrySource.update {
                        it.copy(
                            itlAllMs = ArrayList(liveItl),
                            tokensReceived = liveTokens,
                            tokenElapsedSec = liveTokenElapsedSec.takeIf { s -> s > 0.0 },
                            ttftMs = liveTtft ?: it.ttftMs,
                            rttSamplesMs = liveRttMs,
                            latestUpMbps = liveUpMbps ?: it.latestUpMbps,
                            fraction = (orderIndex + 1).toDouble() / totalScenarios,
                        )
                    }

                    // ---- skew（C06/R-22：双 clock_sync 线性插值轨迹 + 漂移率入库） ----
                    val track = outcome.offsetTrack()
                    log(
                        "SKEW scenario=$scenarioKey " +
                            "start_offset_us=${track.start?.offsetUs ?: "null"} " +
                            "end_offset_us=${track.end?.offsetUs ?: "null"} " +
                            "drift_ppm=${track.driftPpm?.let { "%.2f".format(it) } ?: "null"} " +
                            "offset_suspect=${track.offsetSuspect}"
                    )

                    // ---- 批化检测（P1-C08 遗留接线）：token_stream 结束后由残差序列跑
                    // BufferingDetector；R-05 红线：score/attribution 只作标注落库，
                    // 绝不参与上面 KpiCalculator 的三态判定。radioBuf/envBuf 此刻尚未被
                    // persistScenario 排空，快照式（不消费）取场景窗口内的 R1/jank 联动数据。
                    val buffering = analyzeBuffering(outcome, envBuf, radioBuf)

                    val entity = buildScenarioEntity(
                        runId, profile, round, orderIndex, outcome, kpi, track, netSnap, buffering,
                    )
                    val hist = ItlHistogram.of(
                        ScenarioKpi.correctedItlSamplesMs(input.tokenSamples, input.pauseSeqs)
                    )
                    scenarioReports.add(entity to hist)

                    // ---- Room 批量事务落库（R-16：phase/场景结束后统一写） ----
                    persistScenario(db, runId, outcome, entity, envBuf, radioBuf)
                    log(
                        "SCENARIO_KPI scenario=$scenarioKey validity=${kpi.validity} " +
                            "reasons=${kpi.invalidReasons.joinToString(",").ifEmpty { "none" }} " +
                            "t1_ms=${fmt(kpi.t1TtftMs)} t2_ms=${fmt(kpi.t2ItlP95Ms)} " +
                            "t2_incl_ms=${fmt(kpi.t2ItlP95InclCoalescedMs)} t3=${fmt(kpi.t3StallRate)} " +
                            "t3_incl=${fmt(kpi.t3StallRateInclResume)} t4=${fmt(kpi.t4SevereStallRate)} " +
                            "t5_ms=${fmt(kpi.t5ResumeP95Ms)} n1_ms=${fmt(kpi.n1RttP50Ms)} " +
                            "n2_ms=${fmt(kpi.n2JitterMs)} u1_mbps=${fmt(kpi.u1GoodputMbps)} " +
                            "u1_excl_mbps=${fmt(kpi.u1GoodputExclSlowStartMbps)} u2_ms=${fmt(kpi.u2ToolLoopP95Ms)} " +
                            "gaps=${kpi.seqGapCount} dup=${kpi.seqDupCount}"
                    )
                    log(
                        "BUFFERING scenario=$scenarioKey " +
                            "score=${buffering?.let { "%.3f".format(it.bufferingScore) } ?: "null"} " +
                            "attribution=${buffering?.attribution?.name?.lowercase() ?: "null"} " +
                            "samples=${buffering?.sampleCount ?: 0} " +
                            "sawtooth=${buffering?.let { "%.3f".format(it.sawtoothRatio) } ?: "null"} " +
                            "near_zero=${buffering?.let { "%.3f".format(it.nearZeroArrivalRatio) } ?: "null"} " +
                            "lag1=${buffering?.let { "%.3f".format(it.lag1Autocorrelation) } ?: "null"} " +
                            "batch_count=${buffering?.batchCount ?: 0} " +
                            "best_grid_us=${buffering?.bestGridUs ?: "null"} " +
                            "jank_overlap=${buffering?.let { "%.3f".format(it.jankOverlapRatio) } ?: "null"} " +
                            "retrans_rate=${buffering?.retransRate?.let { "%.4f".format(it) } ?: "null"} " +
                            "affects_validity=false"
                    )

                    orderIndex++
                    if (invalidated != null) {
                        // 守卫失效为 run 级一次性状态（首事件获胜）：后续场景环境已不可证，
                        // fail-closed 中止整个 run（当前场景已记 INVALID+原因码入库）
                        status = "aborted:$invalidated"
                        log("RUN_ABORT run_id=$runId reason=$invalidated remaining_skipped=true")
                        break@runLoop
                    }
                }
            }

            // ---------------- run 级 AQS ----------------
            // AQS 输入映射合同（KDoc 详见 AqsInputMapper）：N1/N2←S1 首次 clock_sync；
            // T 组←S2（S1/S3 的 T 组仅展示不进 AQS）；U1←S3 1MB 上传（进 AQS 用含慢启动
            // 口径）；U2←S2 tool_loop。任一项 INVALID/缺失→KpiValue=null→AqsScorer 按
            // 现有语义返回 KPI_MISSING（绝不以 0 顶替）。
            val composite = AqsInputMapper.map(kpiByScenario)
            val aqsResult = AqsScorer.score(composite)
            aqs = aqsResult
            // 遥测投影：run 收尾粗 AQS（观测通道，非测量；null 时不覆盖）
            telemetrySource.update { it.copy(aqsRunning = aqsResult.score ?: it.aqsRunning, fraction = 1.0) }
            log("AQS_INPUT_MAP map=${AqsInputMapper.MAPPING_DESCRIPTION}")
            log(
                "AQS run_id=$runId score=${aqsResult.score?.let { "%.1f".format(it) } ?: "null"} " +
                    "low_confidence=${aqsResult.lowConfidence} veto=${aqsResult.vetoApplied} " +
                    "reason=${aqsResult.notComputableReason ?: "none"} " +
                    "subs=${aqsResult.subScores.entries.joinToString(",") { "${it.key}:${"%.1f".format(it.value)}" }.ifEmpty { "none" }}"
            )

            // ---------------- run 级 AQS v0.2（阶段2 C03 遗留接线，additive） ----------------
            // 最近 24h 内存在可用 continuity 结果（C1/C2 均非 null）→ 用 AqsScorer 既有
            // v0.2 入口并列出分并标注数据来源；无可用数据 → 不出 v0.2（v0.1 语义不变）。
            val nowEpochMs = System.currentTimeMillis()
            val continuityCandidates = runCatching {
                db.continuityResultDao().since(nowEpochMs - AqsV02Gate.CONTINUITY_MAX_AGE_MS)
            }.getOrElse { emptyList() }
            val continuitySrc = AqsV02Gate.select(continuityCandidates, nowEpochMs)
            val aqsV02 = continuitySrc?.let { AqsScorer.score(composite, AqsV02Gate.toContinuityKpi(it)) }
            if (continuitySrc != null && aqsV02 != null) {
                log(
                    "AQS_V02 run_id=$runId score=${aqsV02.score?.let { "%.1f".format(it) } ?: "null"} " +
                        "low_confidence=${aqsV02.lowConfidence} veto=${aqsV02.vetoApplied} " +
                        "reason=${aqsV02.notComputableReason ?: "none"} " +
                        "c1=${continuitySrc.c1DropRate} c2_ms=${continuitySrc.c2RecoveryMsP50} " +
                        "continuity_run=${continuitySrc.runId} " +
                        "continuity_started_at_epoch_ms=${continuitySrc.startedAtEpochMs}"
                )
            } else {
                log("AQS_V02 run_id=$runId available=false reason=no_usable_continuity_in_24h")
            }

            // ---------------- 结果上报（合同字段 + 400 errors 自检） ----------------
            val runEntity = baseRun(
                runId, startedAtEpochMs, measureBase, modeStr, transportStr,
                orderRecord.joinToString("|"), loaded.source, guardMeta,
            ).copy(
                profileVersions = profileVersions,
                aqsScore = aqsResult.score,
                aqsLowConfidence = aqsResult.lowConfidence,
                aqsVetoApplied = aqsResult.vetoApplied,
                aqsNotComputableReason = aqsResult.notComputableReason,
                status = status,
                // v0.2 并列出分（无可用 continuity 数据时全 null＝无 v0.2 分支）
                aqsV02Score = aqsV02?.score,
                aqsV02LowConfidence = aqsV02?.lowConfidence,
                aqsV02VetoApplied = aqsV02?.vetoApplied,
                aqsV02NotComputableReason = aqsV02?.notComputableReason,
                aqsV02ContinuityRunId = continuitySrc?.runId,
                aqsV02ContinuityStartedAtEpochMs = continuitySrc?.startedAtEpochMs,
                aqsV02C1DropRate = continuitySrc?.c1DropRate,
                aqsV02C2RecoveryMs = continuitySrc?.c2RecoveryMsP50,
                // SNI 双通道连接可达性（run 前探测；非 E-01 或探测失败保持 null）
                sniReachable = reach?.sni?.status,
                sniReachMs = reach?.sni?.elapsedMs,
                ipReachable = reach?.ip?.status,
                ipReachMs = reach?.ip?.elapsedMs,
            )
            val body = ResultReporter.build(runEntity, scenarioReports, aqsResult)
            val bodyBytes = body.toByteArray(Charsets.UTF_8).size
            if (bodyBytes > ResultReporter.MAX_REPORT_BYTES) {
                log("REPORT_SIZE_WARN bytes=$bodyBytes limit=${ResultReporter.MAX_REPORT_BYTES}")
            }
            val resp = client.postResults("$measureBase/api/v1/results", body)
            reportStatus = "http=${resp.httpCode ?: "null"}"
            if (resp.httpCode == 400) {
                // 合同自检：服务端拒收即本端合同实现有错，errors 全量进日志
                log("REPORT_CONTRACT_ERRORS body=${resp.body?.replace(' ', '_') ?: "empty"}")
            }
            log("REPORT http=${resp.httpCode ?: "null"} bytes=$bodyBytes error=${resp.error ?: "none"}")

            persistRun(db, runEntity.copy(reportStatus = reportStatus))
            // C07 导出源：上报体原样存档（与上报严格同构，导出禁止事后重算）
            persistReportBody(db, runId, body)
            log(
                "DB_WRITE run_id=$runId scenarios=${scenarioReports.size} " +
                    "env_events_pending=${envBuf.size} radio_samples_pending=${radioBuf.size}"
            )
            log("RUN_END run_id=$runId status=$status")
        } catch (e: CancellationException) {
            throw e // 外部取消（fail-closed：不吞取消）
        } catch (e: Exception) {
            status = "error"
            log("RUN_FAILED run_id=$runId error=${e.toString().replace(' ', '_')}")
            persistRun(
                db, baseRun(
                    runId, startedAtEpochMs, measureBase, modeStr, transportStr,
                    orderRecord.joinToString("|"), loaded.source, guardMeta,
                ).copy(profileVersions = profileVersions, status = "error:${e.javaClass.simpleName}"),
            )
            log("RUN_END run_id=$runId status=error")
        } finally {
            collectors.forEach { it.cancel() }
            radioShareJob?.cancel() // C07：shareIn 共享协程收尸，run flow 才能正常完成
            // 遥测观测通道复位（run 结束/取消）：采样协程已随 collectors 取消，最终态置空
            telemetrySource.reset()
            liveStreamWindow.reset()
            _telemetry.value = LiveTelemetry.EMPTY
            pathWatch.stop()
            envMonitors.stop()
            locationTagger?.stop()
            // 残余 env/radio 缓冲落库（NonCancellable：外部取消也不丢已采数据）
            withContext(NonCancellable) {
                runCatching { flushBuffers(db, runId, envBuf, radioBuf) }.onFailure {
                    // 评审发现 4：DB 写失败不再静默——进 AnebProbe logcat 镜像
                    Log.e(LOG_TAG, "DB_WRITE_FAILED table=env_events,radio_samples reason=${it.toString().replace(' ', '_')}")
                }
            }
            bound?.release()
        }
    }.flowOn(Dispatchers.IO) // R-16：场景状态机/守卫/KPI/上报/落库全部离开收集端主线程

    // ------------------------------------------------------------------
    // 落库
    // ------------------------------------------------------------------

    private suspend fun persistScenario(
        db: AnebDatabase,
        runId: String,
        outcome: ScenarioRunner.ScenarioOutcome,
        entity: ScenarioResultEntity,
        envBuf: ConcurrentLinkedQueue<EnvEvent>,
        radioBuf: ConcurrentLinkedQueue<RadioSample>,
    ) {
        val tokenEntities = ArrayList<TokenEventEntity>()
        for (st in outcome.streams) {
            val events = st.result.stream?.events ?: continue
            for (e in events) {
                tokenEntities.add(
                    TokenEventEntity(
                        runId = runId,
                        scenarioKey = outcome.scenarioKey,
                        streamIndex = st.streamIndex,
                        seq = e.seq,
                        schedUs = e.schedUs.takeIf { it >= 0 },
                        preFlushUs = e.preFlushUs.takeIf { it >= 0 },
                        arrivalNanos = e.arrivalNanos,
                        payloadBytes = e.payloadBytes,
                        sameReadBatch = e.sameReadBatch,
                    )
                )
            }
        }
        val echoEntities = ArrayList<EchoSampleEntity>()
        for (cs in outcome.clockSyncs) {
            for (rec in cs.samples) {
                val r = rec.result
                echoEntities.add(
                    EchoSampleEntity(
                        runId = runId,
                        scenarioKey = outcome.scenarioKey,
                        phaseIndex = cs.phaseIndex,
                        idx = rec.idx,
                        warmup = rec.warmup,
                        t0Us = r.t0Us, t1Us = r.t1Us, t2Us = r.t2Us, t3Us = r.t3Us,
                        rttUs = r.rttUs, offsetUs = r.offsetUs, error = r.error,
                    )
                )
            }
        }
        val envEntities = drainEnv(envBuf).map { it.toEntity(runId) }
        val radioEntities = drainRadio(radioBuf).map { it.toEntity(runId) }
        db.withTransaction {
            db.scenarioResultDao().insert(entity)
            if (tokenEntities.isNotEmpty()) db.tokenEventDao().insertAll(tokenEntities)
            if (echoEntities.isNotEmpty()) db.echoSampleDao().insertAll(echoEntities)
            if (envEntities.isNotEmpty()) db.envEventDao().insertAll(envEntities)
            if (radioEntities.isNotEmpty()) db.radioSampleDao().insertAll(radioEntities)
        }
    }

    private suspend fun flushBuffers(
        db: AnebDatabase,
        runId: String,
        envBuf: ConcurrentLinkedQueue<EnvEvent>,
        radioBuf: ConcurrentLinkedQueue<RadioSample>,
    ) {
        val env = drainEnv(envBuf).map { it.toEntity(runId) }
        val radio = drainRadio(radioBuf).map { it.toEntity(runId) }
        if (env.isEmpty() && radio.isEmpty()) return
        db.withTransaction {
            if (env.isNotEmpty()) db.envEventDao().insertAll(env)
            if (radio.isNotEmpty()) db.radioSampleDao().insertAll(radio)
        }
    }

    private fun drainEnv(q: ConcurrentLinkedQueue<EnvEvent>): List<EnvEvent> {
        val out = ArrayList<EnvEvent>()
        while (true) out.add(q.poll() ?: break)
        return out
    }

    private fun drainRadio(q: ConcurrentLinkedQueue<RadioSample>): List<RadioSample> {
        val out = ArrayList<RadioSample>()
        while (true) out.add(q.poll() ?: break)
        return out
    }

    private suspend fun persistReportBody(db: AnebDatabase, runId: String, body: String) {
        withContext(NonCancellable) {
            runCatching { db.reportBodyDao().insert(com.aneb.probe.data.ReportBodyEntity(runId, body)) }
                .onFailure {
                    Log.e(LOG_TAG, "DB_WRITE_FAILED table=report_body reason=${it.toString().replace(' ', '_')}")
                }
        }
    }

    private suspend fun persistRun(db: AnebDatabase, run: TestRun) {
        withContext(NonCancellable) {
            runCatching { db.testRunDao().insert(run) }.onFailure {
                // 评审发现 4：DB 写失败不再静默——进 AnebProbe logcat 镜像
                Log.e(LOG_TAG, "DB_WRITE_FAILED table=test_runs reason=${it.toString().replace(' ', '_')}")
            }
        }
    }

    private fun baseRun(
        runId: String,
        startedAtEpochMs: Long,
        serverBase: String,
        mode: String,
        transport: String,
        order: String,
        profileSource: String,
        guardMeta: String,
    ): TestRun {
        val pkg = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        return TestRun(
            runId = runId,
            startedAtEpochMs = startedAtEpochMs,
            serverBase = serverBase,
            mode = mode,
            scenarioOrder = order,
            transport = transport,
            kpiSet = KPI_SET,
            aqsVersion = AqsScorer.AQS_VERSION,
            profileVersions = "",
            schemaVersion = ResultReporter.SCHEMA_VERSION,
            profileSource = profileSource,
            appVersionName = pkg?.versionName,
            appVersionCode = pkg?.longVersionCode,
            guardMetadata = guardMeta,
            aqsScore = null,
            aqsLowConfidence = null,
            aqsVetoApplied = null,
            aqsNotComputableReason = null,
            status = null,
            reportStatus = null,
        )
    }

    /**
     * P1-C08 接线：场景残差序列 → BufferingDetector（R-05：产出仅作标注）。
     * 无残差样本（流失败/无 token/时间戳不齐）→ null（R-10：不出值不造值）。
     * envBuf/radioBuf 只读快照（ConcurrentLinkedQueue 弱一致迭代，不消费队列——
     * 排空仍统一由 persistScenario/flushBuffers 完成，R-16 落库纪律不变）。
     */
    private fun analyzeBuffering(
        outcome: ScenarioRunner.ScenarioOutcome,
        envBuf: ConcurrentLinkedQueue<EnvEvent>,
        radioBuf: ConcurrentLinkedQueue<RadioSample>,
    ): BufferingReport? {
        val residuals = BufferingWiring.residualSamples(
            outcome.streams.map {
                ScenarioKpi.StreamTokens(it.expectedTokens, it.result.stream?.events ?: emptyList())
            }
        )
        if (residuals.isEmpty()) return null
        // P3-C05 retrans 共变量：各流 summary 的 retrans_total（无数据 null → 检测器
        // 行为与引入前完全一致）。口径与保守方向见 BufferingWiring.retransRate KDoc。
        val retransRate = BufferingWiring.retransRate(
            outcome.streams.mapNotNull { s ->
                s.result.stream?.let { BufferingWiring.StreamRetrans(it.summaryRetransTotal, it.events.size) }
            }
        )
        return BufferingDetector.analyze(
            samples = residuals,
            radio = BufferingWiring.radioSummary(radioBuf, outcome.startedAtNanos, outcome.endedAtNanos),
            appJankEventsUs = BufferingWiring.jankEventsUs(envBuf, outcome.startedAtNanos, outcome.endedAtNanos),
            retransRate = retransRate,
        )
    }

    private fun buildScenarioEntity(
        runId: String,
        profile: ScenarioProfile,
        repeatIndex: Int,
        orderIndex: Int,
        outcome: ScenarioRunner.ScenarioOutcome,
        kpi: KpiResult,
        track: OffsetTrack,
        netSnap: com.aneb.probe.net.NetworkSnapshot?,
        buffering: BufferingReport?,
    ): ScenarioResultEntity {
        val parse = outcome.streams.mapNotNull { it.result.stream }
        val parseDurTotal = if (parse.isEmpty()) null else parse.sumOf { it.parseDurUs }
        val eventsTotal = parse.sumOf { it.events.size }
        return ScenarioResultEntity(
            runId = runId,
            profileId = profile.profileId,
            profileVersion = profile.version,
            repeatIndex = repeatIndex,
            orderIndex = orderIndex,
            startedAtNanos = outcome.startedAtNanos,
            endedAtNanos = outcome.endedAtNanos,
            validity = kpi.validity.name.lowercase(),
            invalidReasons = kpi.invalidReasons.joinToString(","),
            t1TtftMs = kpi.t1TtftMs.value, t1Grade = KpiGrading.grade("T1", kpi.t1TtftMs.value),
            t2ItlP95Ms = kpi.t2ItlP95Ms.value, t2Grade = KpiGrading.grade("T2", kpi.t2ItlP95Ms.value),
            t2ItlP95InclCoalescedMs = kpi.t2ItlP95InclCoalescedMs.value,
            t3StallRate = kpi.t3StallRate.value, t3Grade = KpiGrading.grade("T3", kpi.t3StallRate.value),
            t3StallRateInclResume = kpi.t3StallRateInclResume.value,
            t4SevereStallRate = kpi.t4SevereStallRate.value,
            t4Grade = KpiGrading.grade("T4", kpi.t4SevereStallRate.value),
            t5ResumeP95Ms = kpi.t5ResumeP95Ms.value,
            n1RttP50Ms = kpi.n1RttP50Ms.value, n1Grade = KpiGrading.grade("N1", kpi.n1RttP50Ms.value),
            n2JitterMs = kpi.n2JitterMs.value, n2Grade = KpiGrading.grade("N2", kpi.n2JitterMs.value),
            u1GoodputMbps = kpi.u1GoodputMbps.value,
            u1Grade = KpiGrading.grade("U1", kpi.u1GoodputMbps.value),
            u1GoodputExclSlowStartMbps = kpi.u1GoodputExclSlowStartMbps.value,
            u2ToolLoopP95Ms = kpi.u2ToolLoopP95Ms.value,
            u2Grade = KpiGrading.grade("U2", kpi.u2ToolLoopP95Ms.value),
            seqGapCount = kpi.seqGapCount,
            seqDupCount = kpi.seqDupCount,
            // C07：per-KPI lowConfidence 持久化（结果页/导出标注用，KPI 文档 5.4）
            lowConfidenceKpis = listOf(
                "T1" to kpi.t1TtftMs,
                "T2" to kpi.t2ItlP95Ms,
                "T2_incl_coalesced" to kpi.t2ItlP95InclCoalescedMs,
                "T3" to kpi.t3StallRate,
                "T3_incl_resume" to kpi.t3StallRateInclResume,
                "T4" to kpi.t4SevereStallRate,
                "T5" to kpi.t5ResumeP95Ms,
                "N1" to kpi.n1RttP50Ms,
                "N2" to kpi.n2JitterMs,
                "U1" to kpi.u1GoodputMbps,
                "U1_excl_slow_start" to kpi.u1GoodputExclSlowStartMbps,
                "U2" to kpi.u2ToolLoopP95Ms,
            ).filter { it.second.lowConfidence }.joinToString(",") { it.first },
            offsetStartUs = track.start?.offsetUs,
            offsetStartErrUs = track.start?.errUs,
            offsetEndUs = track.end?.offsetUs,
            offsetEndErrUs = track.end?.errUs,
            offsetDriftPpm = track.driftPpm,
            offsetSuspect = track.offsetSuspect,
            netTransport = netSnap?.transport,
            netCapabilities = netSnap?.capabilities,
            netInterfaceName = netSnap?.interfaceName,
            serverObservedAddr = outcome.observedAddr,
            parseDurUsTotal = parseDurTotal,
            perEventParseUs = if (parseDurTotal != null && eventsTotal > 0) {
                parseDurTotal.toDouble() / eventsTotal
            } else {
                null
            },
            // P1-C08：批化标注列（R-05：只标注不改上面的 validity）；未检测全 null
            bufferingScore = buffering?.bufferingScore,
            bufferingAttribution = buffering?.attribution?.name?.lowercase(),
            bufferingSampleCount = buffering?.sampleCount,
            bufferingSawtoothRatio = buffering?.sawtoothRatio,
            bufferingNearZeroRatio = buffering?.nearZeroArrivalRatio,
            bufferingLag1Autocorr = buffering?.lag1Autocorrelation,
            bufferingBatchCount = buffering?.batchCount,
            bufferingBestGridUs = buffering?.bestGridUs,
            bufferingJankOverlapRatio = buffering?.jankOverlapRatio,
        )
    }

    /** AUTO 模式（不绑定）：以当前默认网做每场景网络快照。 */
    private fun autoNetSnapshot(context: Context): com.aneb.probe.net.NetworkSnapshot? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val active = runCatching { cm.activeNetwork }.getOrNull() ?: return null
        val caps = runCatching { cm.getNetworkCapabilities(active) }.getOrNull()
        val lp = runCatching { cm.getLinkProperties(active) }.getOrNull()
        val transport = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ethernet"
            else -> "unknown"
        }
        return com.aneb.probe.net.NetworkSnapshot(
            transport = "auto($transport)",
            capabilities = caps?.toString() ?: "unknown",
            interfaceName = lp?.interfaceName,
            acquiredAtNanos = SystemClock.elapsedRealtimeNanos(),
        )
    }

    private fun fmt(v: KpiValue): String {
        val value = v.value ?: return "null"
        val s = "%.4f".format(value)
        return if (v.lowConfidence) "$s(lowconf)" else s
    }

    companion object {
        const val KPI_SET = "agent-qoe-kpi-v0.2"

        /** 实时遥测采样节流间隔（ms）：观测通道节流上限，不影响任何测量计时 */
        private const val TELEMETRY_SAMPLE_MS = 100L

        /** 与 MainActivity 的 logcat 镜像同 tag，模拟器自动化统一从该 tag 提取 */
        private const val LOG_TAG = "AnebProbe"

        /** UUIDv7（时间有序）：48bit unix ms + ver7 + 74bit 随机。 */
        fun newRunId(): String {
            val rnd = SecureRandom()
            val b = ByteArray(16)
            rnd.nextBytes(b)
            val ms = System.currentTimeMillis()
            b[0] = (ms ushr 40).toByte()
            b[1] = (ms ushr 32).toByte()
            b[2] = (ms ushr 24).toByte()
            b[3] = (ms ushr 16).toByte()
            b[4] = (ms ushr 8).toByte()
            b[5] = ms.toByte()
            b[6] = ((b[6].toInt() and 0x0F) or 0x70).toByte() // version 7
            b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte() // variant 10
            val hex = b.joinToString("") { "%02x".format(it) }
            return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
                "${hex.substring(16, 20)}-${hex.substring(20)}"
        }
    }
}

// ---------------------------------------------------------------------------
// 测中路径监控适配（接线层；不改 NetGuard 公共 API）
// ---------------------------------------------------------------------------

private interface PathWatch {
    fun start()
    fun stop()
}

/** 绑定模式：直接复用 NetGuard.PathMonitor（首事件获胜状态机）。 */
private class BoundPathWatch(
    context: Context,
    bound: BoundNetwork,
    onEvent: (EnvEvent) -> Unit,
    onInvalidate: (String) -> Unit,
) : PathWatch {
    private val monitor = PathMonitor(
        context = context,
        bound = bound,
        exemptPathChanges = false,
        onEvent = onEvent,
        onInvalidate = onInvalidate,
    )

    override fun start() = monitor.start()
    override fun stop() = monitor.stop()
}

/**
 * AUTO 模式（不绑定仅监控，P1 范围 3）：registerDefaultNetworkCallback 盯默认网——
 * 默认网切换/丢失/VALIDATED 丢失 → PATH_CHANGE 事件 + 首事件获胜 invalidate
 * （测中默认路径漂移使样本跨路径不可比，fail-closed 语义与绑定模式一致，R-01）。
 */
private class DefaultNetWatch(
    private val context: Context,
    private val onEvent: (EnvEvent) -> Unit,
    private val onInvalidate: (String) -> Unit,
) : PathWatch {
    private val tripped = AtomicBoolean(false)
    private val baseline = AtomicReference<Network?>(null)

    /**
     * 是否见过 baseline 网络 VALIDATED。registerDefaultNetworkCallback 注册后会立即
     * 回放当前 capabilities——从未 VALIDATED 的网络（如无外网的测试台架）首个回调
     * 不是"丢失"，只有 true→false 转变才算 default_validated_lost（与 KDoc"VALIDATED
     * 丢失"及 guardCheck 容忍 active_validated=false 的口径一致；联调修复，不改测量语义）。
     */
    private val everValidated = AtomicBoolean(false)
    private var cb: ConnectivityManager.NetworkCallback? = null

    override fun start() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            trip("connectivity_manager_unavailable")
            return
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (baseline.compareAndSet(null, network)) return
                if (baseline.get() != network) {
                    baseline.set(network)
                    pathEvent("default_network_changed", "-> $network")
                }
            }

            override fun onLost(network: Network) {
                if (network == baseline.get()) pathEvent("default_network_lost", "$network")
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (network != baseline.get()) return
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    everValidated.set(true)
                } else if (everValidated.get()) {
                    // 仅 true→false 转变判丢失；从未 VALIDATED 的网络不在此误杀
                    pathEvent("default_validated_lost", "$network")
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            cb = callback
        } catch (t: Throwable) {
            // R-01：注册失败无法证明测中路径稳定 → fail-closed
            onEvent(
                EnvEvent(
                    SystemClock.elapsedRealtimeNanos(),
                    EnvEventType.PATH_CHANGE,
                    "monitor_registration_failed: ${t.javaClass.simpleName}",
                ),
            )
            trip("path_monitor_registration_failed")
        }
    }

    override fun stop() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        cb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        cb = null
    }

    private fun pathEvent(reason: String, detail: String) {
        onEvent(
            EnvEvent(SystemClock.elapsedRealtimeNanos(), EnvEventType.PATH_CHANGE, "$reason $detail exempt=false"),
        )
        trip(reason)
    }

    private fun trip(reason: String) {
        if (tripped.compareAndSet(false, true)) onInvalidate(reason)
    }
}
