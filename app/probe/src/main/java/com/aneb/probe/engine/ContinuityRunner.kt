package com.aneb.probe.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.room.withTransaction
import com.aneb.probe.data.AnebDatabase
import com.aneb.probe.data.ContinuityResultEntity
import com.aneb.probe.data.EnvEvent
import com.aneb.probe.data.EnvEventType
import com.aneb.probe.net.AnebClient
import com.aneb.probe.net.BoundNetwork
import com.aneb.probe.net.GuardException
import com.aneb.probe.net.NetGuard
import com.aneb.probe.net.PathMonitor
import com.aneb.probe.scoring.AqsScorer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 阶段 2 C 组连续性实验（KPI 文档 5.1/5.2 C1/C2/C3；设计文档 §8 阶段 2）。
 *
 * 实验设计（与场景 run 分离，不复用 TestEngine 的场景状态机——测量对象不同）：
 * - **C2 切换恢复时间**：长流（默认 tokens=1200 @40tps ≈30s）中发生传输中断
 *   （IOException/流截断）时，从中断检出时刻起自动重连（新请求同参数，指数退避
 *   500ms 起、最多 5 次），恢复时间 = 中断时刻 → 重连流首 token 到达时刻。
 * - **C1 会话中断率**：实验内流式段（首段+各重连段）的异常断开数 / 总段数。
 * - **C3 NAT 静默挂起**：同一 OkHttp 连接池上按阶梯 idle（默认 1/3/5 分钟）后
 *   复用连接发 /echo，记录连接是否被静默回收（EventListener 是否新建连接）与耗时。
 *   模拟器 NAT 语义与运营商 CGNAT 不同 → 结果一律标 functional_only。
 *
 * 路径监控豁免（PathMonitor.exemptPathChanges 的设计用途）：路径迁移是本实验的
 * **测量对象**而非污染源——路径事件全量记 EnvEvent 时间轴（exempt=true），但绝不
 * invalidate/中止；监控器自身注册失败仍 fail-closed（豁免只豁免路径迁移类事件）。
 *
 * 日志合同（全部新增 KEY，不动既有 KEY 集）：CONTINUITY_START / CONTINUITY_GUARD /
 * CONTINUITY_BIND / CONTINUITY_BIND_FAIL / CONTINUITY_PATH / CONTINUITY_SEGMENT /
 * CONTINUITY_RECONNECT / CONTINUITY_RECOVERY / CONTINUITY_RECOVERY_FAILED /
 * CONTINUITY_C1 / CONTINUITY_C2 / CONTINUITY_C3_PRIME / CONTINUITY_C3 /
 * CONTINUITY_KPI / CONTINUITY_DB_WRITE / CONTINUITY_FAILED / CONTINUITY_END。
 */
class ContinuityRunner(private val context: Context) {

    data class Config(
        val serverBase: String,
        val transport: TestEngine.TransportMode = TestEngine.TransportMode.AUTO,
        /** 长流参数：1200 token @ 40tps ≈ 30s */
        val tokens: Int = DEFAULT_TOKENS,
        val rateTps: Double = DEFAULT_RATE_TPS,
        val seed: Long = DEFAULT_SEED,
        /** 段数上限（防持续劣化网络下无限重连拉长实验） */
        val maxSegments: Int = DEFAULT_MAX_SEGMENTS,
        val maxReconnectAttempts: Int = ContinuityMath.DEFAULT_MAX_ATTEMPTS,
        val backoffBaseMs: Long = ContinuityMath.DEFAULT_BACKOFF_BASE_MS,
        /** C3 阶梯 idle 时长（秒）；KPI 文档 5.1：N ∈ {1,3,5,10} 分钟，MVP 取 1/3/5 */
        val c3IdleSeconds: List<Int> = DEFAULT_C3_IDLE_S,
    )

    fun run(config: Config): Flow<String> = channelFlow {
        val log: suspend (String) -> Unit = { send(it) }
        val db = AnebDatabase.get(context)
        val runId = TestEngine.newRunId()
        val startedAtEpochMs = System.currentTimeMillis()
        val base = config.serverBase.trim().trimEnd('/')
        val transportStr = config.transport.name.lowercase()
        log(
            "CONTINUITY_START run_id=$runId server=$base transport=$transportStr " +
                "tokens=${config.tokens} rate_tps=${config.rateTps} seed=${config.seed} " +
                "max_segments=${config.maxSegments} max_attempts=${config.maxReconnectAttempts} " +
                "backoff_base_ms=${config.backoffBaseMs} " +
                "c3_idle_s=${config.c3IdleSeconds.joinToString(",")}"
        )

        // ---------------- 测前守卫（R-03 硬拒测项与场景 run 同口径） ----------------
        val guard = NetGuard.guardCheck(context)
        if (!guard.ok) {
            log("CONTINUITY_GUARD ok=false reasons=${guard.reasons.joinToString(",")}")
            persist(db, emptyEntity(runId, startedAtEpochMs, base, transportStr, config, "guard_rejected"))
            log("CONTINUITY_END run_id=$runId status=guard_rejected")
            return@channelFlow
        }
        log(
            "CONTINUITY_GUARD ok=true metadata=" +
                guard.metadata.entries.joinToString(";") { "${it.key}=${it.value}" }.replace(' ', '_')
        )

        // ---------------- 网络绑定（可选；AUTO=不绑定，模拟器路径） ----------------
        val bound: BoundNetwork? = try {
            when (config.transport) {
                TestEngine.TransportMode.AUTO -> null
                TestEngine.TransportMode.WIFI ->
                    NetGuard.acquireNetwork(context, NetworkCapabilities.TRANSPORT_WIFI)
                TestEngine.TransportMode.CELLULAR ->
                    NetGuard.acquireNetwork(context, NetworkCapabilities.TRANSPORT_CELLULAR)
            }
        } catch (e: GuardException) {
            log("CONTINUITY_BIND_FAIL transport=$transportStr error=${e.message?.replace(' ', '_')}")
            persist(db, emptyEntity(runId, startedAtEpochMs, base, transportStr, config, "bind_failed"))
            log("CONTINUITY_END run_id=$runId status=bind_failed")
            return@channelFlow
        }
        bound?.let {
            log("CONTINUITY_BIND transport=$transportStr snapshot=${it.snapshot.capabilities.replace(' ', '_')}")
        }

        // 承载网络提供者：AUTO=unbound（走系统默认网）；绑定模式持有原绑定句柄，真机硬切换
        // 拆除原网后可迁到当前新默认网（D-23 跨网迁移修复；决策逻辑在纯 JVM ContinuityRecovery）。
        val netProvider = ContinuityNetworkProvider(bound)
        val envBuf = ConcurrentLinkedQueue<EnvEvent>()
        val pathChangeCount = AtomicInteger(0)
        val monitorFailure = AtomicReference<String?>(null)

        // ---------------- 豁免路径监控（事件全量记录，不 invalidate） ----------------
        val onPathEvent: (EnvEvent) -> Unit = { ev ->
            envBuf.add(ev)
            if (ev.type == EnvEventType.PATH_CHANGE) pathChangeCount.incrementAndGet()
            // 真机硬切换：绑定的原蜂窝网被系统拆除（bound_network_lost）→ 标记原句柄失效，
            // 重连时迁到当前可用新默认网（AUTO 只发 default_network_lost，永不命中此分支）。
            if (ev.type == EnvEventType.PATH_CHANGE && ev.detail.startsWith("bound_network_lost")) {
                netProvider.markBoundNetworkLost()
            }
            // channelFlow 回调线程安全发送；关闭后丢弃（run 已结束，事件仍在 envBuf 兜底落库）
            trySend("CONTINUITY_PATH type=${ev.type.name} detail=${ev.detail.replace(' ', '_')}")
        }
        val watch: ContinuityWatch = if (bound != null) {
            // 设计用途本尊：PathMonitor 豁免模式（路径事件只记时间轴，不中止）
            PathMonitorWatch(context, bound, onPathEvent, monitorFailure::set)
        } else {
            ExemptDefaultNetWatch(context, onPathEvent, monitorFailure::set)
        }

        var status = "completed"
        var segments = 0
        var disconnects = 0
        val recoveryMs = ArrayList<Double>()
        // 跨网迁移恢复的样本数（原绑定句柄被硬切换拆除→迁新默认网后恢复；D-23 两种 C2 语义）
        var crossNetworkRecoveries = 0
        val c3Probes = ArrayList<ContinuityMath.C3Probe>()

        try {
            watch.start()
            monitorFailure.get()?.let { reason ->
                // 豁免不豁免监控器自身故障：无法证明事件被完整记录 → fail-closed
                log("CONTINUITY_FAILED run_id=$runId error=monitor:$reason")
                status = "monitor_failed"
                persist(db, buildEntity(runId, startedAtEpochMs, base, transportStr, config, segments, disconnects, recoveryMs, c3Probes, pathChangeCount.get(), status))
                log("CONTINUITY_END run_id=$runId status=$status")
                return@channelFlow
            }

            val streamUrl = "$base/api/v1/stream?tokens=${config.tokens}" +
                "&rate_tps=${config.rateTps}&seed=${config.seed}&run=$runId"

            // ---------------- C1/C2：长流 + 中断自动重连 ----------------
            var current: AnebClient.ContinuityStreamResult? = netProvider.client().continuityStream(streamUrl)
            experiment@ while (true) {
                val r = current ?: break
                segments++
                log(
                    "CONTINUITY_SEGMENT run_id=$runId seg=$segments tokens=${r.tokenCount} " +
                        "max_seq=${r.maxSeq ?: "null"} summary=${r.sawSummary} " +
                        "http=${r.httpCode ?: "null"} conn_new=${r.connectionWasNew ?: "null"} " +
                        "completed=${r.completed} error=${r.error?.replace(' ', '_') ?: "none"}"
                )
                if (r.completed) break@experiment // 干净收尾：实验主段结束

                // 异常断开（IOException / 无 summary 截断 / HTTP 错误）＝ C1 证据
                disconnects++
                if (segments >= config.maxSegments) {
                    status = "max_segments_reached"
                    break@experiment
                }
                // C2 恢复计时起点：错误检出时刻，缺失退化为最后 event 到达时刻
                val interruptNanos = r.errorNanos ?: r.lastEventNanos
                    ?: SystemClock.elapsedRealtimeNanos()

                // 重连恢复（纯 JVM 决策，副作用注入）：绑定句柄失效（真机硬切换 EPERM/
                // bound_network_lost）时迁到当前可用新默认网再发流（D-23），否则同一 client 重连。
                val outcome = ContinuityRecovery.recover(
                    interruptNanos = interruptNanos,
                    maxAttempts = config.maxReconnectAttempts,
                    firstTokenNanosOf = { it.firstTokenNanos },
                    errorOf = { it.error },
                    delayBeforeAttempt = { attempt ->
                        delay(ContinuityMath.backoffDelayMs(attempt, config.backoffBaseMs))
                    },
                    boundNetworkLost = { netProvider.boundNetworkLost() },
                    rebindToCurrentNetwork = { netProvider.rebindToCurrentNetwork() },
                    attemptStream = { netProvider.client().continuityStream(streamUrl) },
                    onAttemptFailed = { attempt, error ->
                        // 死句柄错误（回绑已拆除网 EPERM）→ 标记失效，下一次尝试迁新默认网
                        if (ContinuityRecovery.isBoundHandleDeadError(error)) {
                            netProvider.markBoundNetworkLost()
                        }
                        log(
                            "CONTINUITY_RECONNECT run_id=$runId seg=$segments attempt=$attempt " +
                                "ok=false error=${error?.replace(' ', '_') ?: "no_token"}"
                        )
                    },
                    onRebind = { attempt, detail ->
                        log(
                            "CONTINUITY_REBIND run_id=$runId seg=$segments attempt=$attempt " +
                                "ok=${detail != null} target=${detail ?: "unavailable"}"
                        )
                    },
                )
                when (outcome) {
                    is ContinuityRecovery.Outcome.Recovered -> {
                        recoveryMs.add(outcome.recoveryMs)
                        if (outcome.crossNetwork) crossNetworkRecoveries++
                        val semantic = if (outcome.crossNetwork) "cross_network" else "same_network"
                        log(
                            "CONTINUITY_RECOVERY run_id=$runId seg=$segments attempt=${outcome.attempt} " +
                                "recovery_ms=${"%.1f".format(outcome.recoveryMs)} semantic=$semantic " +
                                "conn_new=${outcome.result.connectionWasNew ?: "null"}"
                        )
                        current = outcome.result // 重连流即下一段，回到段循环继续判定
                    }
                    is ContinuityRecovery.Outcome.Failed -> {
                        log(
                            "CONTINUITY_RECOVERY_FAILED run_id=$runId seg=$segments " +
                                "attempts=${outcome.attempts}"
                        )
                        status = "recovery_failed"
                        break@experiment
                    }
                }
            }

            val c1 = ContinuityMath.c1Rate(disconnects, segments)
            log(
                "CONTINUITY_C1 run_id=$runId segments=$segments disconnects=$disconnects " +
                    "rate=${c1?.let { "%.4f".format(it) } ?: "null"} " +
                    "grade=${KpiGrading.grade("C1", c1) ?: "null"}"
            )
            val c2p50 = ContinuityMath.medianMs(recoveryMs)
            log(
                "CONTINUITY_C2 run_id=$runId samples=${recoveryMs.size} " +
                    "cross_network_samples=$crossNetworkRecoveries " +
                    "p50_ms=${c2p50?.let { "%.1f".format(it) } ?: "null"} " +
                    "all_ms=${recoveryMs.joinToString(",") { "%.1f".format(it) }.ifEmpty { "none" }} " +
                    "grade=${KpiGrading.grade("C2", c2p50) ?: "null"}"
            )

            // ---------------- C3：NAT 静默挂起阶梯探测（functional_only） ----------------
            if (status == "completed") {
                for (idleS in config.c3IdleSeconds) {
                    // 先热一条连接（确保 idle 起点是活连接），再 idle，再复用探测
                    val prime = netProvider.client().echo("$base/api/v1/echo")
                    log(
                        "CONTINUITY_C3_PRIME run_id=$runId idle_s=$idleS " +
                            "http=${prime.httpCode ?: "null"} error=${prime.error?.replace(' ', '_') ?: "none"}"
                    )
                    delay(idleS * 1000L)
                    val t0 = SystemClock.elapsedRealtimeNanos()
                    val probe = netProvider.client().echo("$base/api/v1/echo")
                    val t1 = SystemClock.elapsedRealtimeNanos()
                    val connNew = probe.timing?.let { it.connectStartNs != null }
                    val echoMs = if (probe.error == null) (t1 - t0) / 1e6 else null
                    c3Probes.add(ContinuityMath.C3Probe(idleS, connNew, echoMs, probe.error))
                    log(
                        "CONTINUITY_C3 run_id=$runId idle_s=$idleS conn_new=${connNew ?: "null"} " +
                            "echo_ms=${echoMs?.let { "%.2f".format(it) } ?: "null"} " +
                            "error=${probe.error?.replace(' ', '_') ?: "none"} " +
                            "pool_keepalive_s=300 functional_only=true"
                    )
                }
            }

            // ---------------- 汇总 + 落库 ----------------
            log(
                "CONTINUITY_KPI run_id=$runId c1_rate=${c1?.let { "%.4f".format(it) } ?: "null"} " +
                    "c2_recovery_p50_ms=${c2p50?.let { "%.1f".format(it) } ?: "null"} " +
                    "c3=${ContinuityMath.c3LadderCsv(c3Probes).ifEmpty { "none" }} " +
                    "path_change_events=${pathChangeCount.get()} " +
                    "aqs_version_candidate=${AqsScorer.AQS_VERSION_V02}"
            )
            persist(
                db,
                buildEntity(
                    runId, startedAtEpochMs, base, transportStr, config,
                    segments, disconnects, recoveryMs, c3Probes, pathChangeCount.get(), status,
                    crossNetworkRecoveries,
                ),
            )
            log("CONTINUITY_DB_WRITE run_id=$runId env_events=${envBuf.size}")
            log("CONTINUITY_END run_id=$runId status=$status")
        } catch (e: CancellationException) {
            throw e // 不吞取消（fail-closed §4.6/§4.7）
        } catch (e: Exception) {
            status = "error:${e.javaClass.simpleName}"
            log("CONTINUITY_FAILED run_id=$runId error=${e.toString().replace(' ', '_')}")
            runCatching {
                persist(
                    db,
                    buildEntity(
                        runId, startedAtEpochMs, base, transportStr, config,
                        segments, disconnects, recoveryMs, c3Probes, pathChangeCount.get(), status,
                        crossNetworkRecoveries,
                    ),
                )
            }
            log("CONTINUITY_END run_id=$runId status=$status")
        } finally {
            watch.stop()
            // 环境事件兜底落库（NonCancellable 语义由 runCatching+挂起点前置保证足够：
            // 事件量小、单事务）
            runCatching { flushEnv(db, runId, envBuf) }.onFailure {
                android.util.Log.e(
                    LOG_TAG,
                    "DB_WRITE_FAILED table=env_events reason=${it.toString().replace(' ', '_')}",
                )
            }
            // 绑定句柄由 netProvider 统管释放（跨网迁移后释放的是"当前持有"的那个，幂等）
            netProvider.release()
        }
    }.flowOn(Dispatchers.IO)

    // ------------------------------------------------------------------
    // 落库
    // ------------------------------------------------------------------

    private fun buildEntity(
        runId: String,
        startedAtEpochMs: Long,
        serverBase: String,
        transport: String,
        config: Config,
        segments: Int,
        disconnects: Int,
        recoveryMs: List<Double>,
        c3Probes: List<ContinuityMath.C3Probe>,
        pathChangeEvents: Int,
        status: String,
        /** 跨网迁移恢复样本数（D-23）；实验未进入重连阶段（guard/bind/monitor 失败）记 null */
        crossNetworkRecoveries: Int? = null,
    ): ContinuityResultEntity {
        val c1 = ContinuityMath.c1Rate(disconnects, segments)
        val c2p50 = ContinuityMath.medianMs(recoveryMs)
        return ContinuityResultEntity(
            runId = runId,
            startedAtEpochMs = startedAtEpochMs,
            serverBase = serverBase,
            transport = transport,
            tokens = config.tokens,
            rateTps = config.rateTps,
            segmentsTotal = segments,
            abnormalDisconnects = disconnects,
            c1DropRate = c1,
            c1Grade = KpiGrading.grade("C1", c1),
            recoveryMsCsv = recoveryMs.joinToString(",") { "%.1f".format(it) },
            c2RecoveryMsP50 = c2p50,
            c2Grade = KpiGrading.grade("C2", c2p50),
            c3LadderCsv = ContinuityMath.c3LadderCsv(c3Probes),
            c3FunctionalOnly = true,
            pathChangeEvents = pathChangeEvents,
            status = status,
            aqsVersionCandidate = AqsScorer.AQS_VERSION_V02,
            c2CrossNetworkRecoveries = crossNetworkRecoveries,
        )
    }

    private fun emptyEntity(
        runId: String,
        startedAtEpochMs: Long,
        serverBase: String,
        transport: String,
        config: Config,
        status: String,
    ): ContinuityResultEntity = buildEntity(
        runId, startedAtEpochMs, serverBase, transport, config,
        segments = 0, disconnects = 0, recoveryMs = emptyList(), c3Probes = emptyList(),
        pathChangeEvents = 0, status = status,
    )

    private suspend fun persist(db: AnebDatabase, entity: ContinuityResultEntity) {
        runCatching { db.continuityResultDao().insert(entity) }.onFailure {
            android.util.Log.e(
                LOG_TAG,
                "DB_WRITE_FAILED table=continuity_result reason=${it.toString().replace(' ', '_')}",
            )
        }
    }

    private suspend fun flushEnv(db: AnebDatabase, runId: String, buf: ConcurrentLinkedQueue<EnvEvent>) {
        val entities = ArrayList<com.aneb.probe.data.EnvEventEntity>()
        while (true) entities.add((buf.poll() ?: break).toEntity(runId))
        if (entities.isEmpty()) return
        db.withTransaction { db.envEventDao().insertAll(entities) }
    }

    companion object {
        const val DEFAULT_TOKENS = 1200
        const val DEFAULT_RATE_TPS = 40.0
        const val DEFAULT_SEED = 42L
        const val DEFAULT_MAX_SEGMENTS = 8
        val DEFAULT_C3_IDLE_S = listOf(60, 180, 300)

        private const val LOG_TAG = "AnebProbe"
    }
}

// ---------------------------------------------------------------------------
// 承载网络提供者（C2 恢复的 Android 副作用边界；真机跨网迁移修复 D-23）
// ---------------------------------------------------------------------------

/**
 * 把"发流用哪个 client"与"原绑定句柄失效时迁到当前可用新默认网"两处 Android 副作用，
 * 从纯 JVM 重连决策 [ContinuityRecovery] 中隔离出来。
 *
 * - **AUTO/未绑定**（模拟器路径）：client 恒为初始 unbound client（本就走系统默认网，切换
 *   由系统透明迁移）；[boundNetworkLost] 恒 false、[rebindToCurrentNetwork] 恒 no-op——
 *   same_network 508ms 基线不回归。
 * - **绑定模式**（真机 CELLULAR/WIFI）：初始 client 绑定 net 句柄；真机硬切换拆除原网
 *   （PathMonitor bound_network_lost / 回绑 EPERM）后，[rebindToCurrentNetwork] 释放已死的
 *   原绑定、改用 unbound client 落到当前系统新默认网（QUIC 迁移/重连的应用层对应）。
 *
 * 线程安全：[markBoundNetworkLost] 可能自 ConnectivityManager 回调线程与重连协程并发触达
 * （AtomicBoolean）；[currentClient] @Volatile 可见；[boundRef] 以 AtomicReference 保证换网
 * 与释放的单次语义（幂等：rebind 已释放的原绑定，release 不再重复释放）。
 */
private class ContinuityNetworkProvider(private val initialBound: BoundNetwork?) {
    private val boundRef = AtomicReference(initialBound)
    @Volatile private var currentClient = AnebClient(initialBound)
    private val boundLost = AtomicBoolean(false)

    /** 当前发流用的 client（换网后为落到系统新默认网的 unbound client）。 */
    fun client(): AnebClient = currentClient

    /** 路径事件 bound_network_lost 或重连 EPERM 触发：标记原绑定句柄已失效。 */
    fun markBoundNetworkLost() {
        boundLost.set(true)
    }

    /** AUTO/未绑定恒 false（无绑定句柄可失效）。 */
    fun boundNetworkLost(): Boolean = boundLost.get()

    /**
     * 迁到当前系统新默认网：释放已死的原绑定、改用 unbound client（落系统当前默认网）。
     * AUTO/未绑定返回 null（no-op，本就在默认网）；已迁过再调仍返回描述（幂等）。
     */
    fun rebindToCurrentNetwork(): String? {
        if (initialBound == null) return null // AUTO：本就走默认网，无需换网
        boundRef.getAndSet(null)?.let { dead ->
            currentClient = AnebClient(null) // unbound → 落系统当前默认网（新默认网）
            dead.release()                   // 释放已死的原绑定 requestNetwork 请求
        }
        return "unbound_default_network"
    }

    /** 释放仍持有的原绑定（幂等：rebind 已释放则此处无操作）。 */
    fun release() {
        boundRef.getAndSet(null)?.release()
    }
}

// ---------------------------------------------------------------------------
// 豁免路径监控适配（接线层；PathMonitor 公共 API 保持 additive-only 不动）
// ---------------------------------------------------------------------------

private interface ContinuityWatch {
    fun start()
    fun stop()
}

/**
 * 绑定模式：PathMonitor 豁免模式本尊（exemptPathChanges=true——路径事件全量记
 * EnvEvent 但不触发中止；onInvalidate 仅剩监控器自身故障路径，仍 fail-closed）。
 */
private class PathMonitorWatch(
    context: Context,
    bound: BoundNetwork,
    onEvent: (EnvEvent) -> Unit,
    onMonitorFailure: (String) -> Unit,
) : ContinuityWatch {
    private val monitor = PathMonitor(
        context = context,
        bound = bound,
        exemptPathChanges = true, // 阶段 2 C 组：路径迁移是测量对象，豁免中止
        onEvent = onEvent,
        onInvalidate = onMonitorFailure, // 豁免模式下只会因监控器自身故障触发
    )

    override fun start() = monitor.start()
    override fun stop() = monitor.stop()
}

/**
 * AUTO 模式（不绑定，模拟器路径）：盯默认网并把切换/丢失/VALIDATED 丢失全量记
 * PATH_CHANGE 事件（exempt=true），**绝不 invalidate**——与 TestEngine 的
 * DefaultNetWatch 互为豁免/非豁免对偶。注册失败仍 fail-closed（豁免只豁免路径事件）。
 */
private class ExemptDefaultNetWatch(
    private val context: Context,
    private val onEvent: (EnvEvent) -> Unit,
    private val onMonitorFailure: (String) -> Unit,
) : ContinuityWatch {
    private val baseline = AtomicReference<Network?>(null)
    private val everValidated = AtomicBoolean(false)
    private var cb: ConnectivityManager.NetworkCallback? = null

    override fun start() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            onMonitorFailure("connectivity_manager_unavailable")
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
                    pathEvent("default_validated_lost", "$network")
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            cb = callback
        } catch (t: Throwable) {
            onEvent(
                EnvEvent(
                    SystemClock.elapsedRealtimeNanos(),
                    EnvEventType.PATH_CHANGE,
                    "monitor_registration_failed: ${t.javaClass.simpleName}",
                ),
            )
            onMonitorFailure("path_monitor_registration_failed")
        }
    }

    override fun stop() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        cb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        cb = null
    }

    private fun pathEvent(reason: String, detail: String) {
        // exempt=true：事件全量记时间轴，但绝不触发中止（C 组测量对象）
        onEvent(
            EnvEvent(SystemClock.elapsedRealtimeNanos(), EnvEventType.PATH_CHANGE, "$reason $detail exempt=true"),
        )
    }
}
