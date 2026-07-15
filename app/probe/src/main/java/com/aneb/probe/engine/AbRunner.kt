package com.aneb.probe.engine

import android.content.Context
import com.aneb.probe.data.AbResultEntity
import com.aneb.probe.data.AnebDatabase
import com.aneb.probe.net.AnebClient
import com.aneb.probe.net.NetGuard
import com.aneb.probe.net.SseStreamResult
import com.aneb.probe.net.TokenEvent
import com.aneb.probe.net.cronet.CronetStreamClient
import com.aneb.probe.scoring.KpiCalculator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import java.net.URI

/**
 * P2-C05：同 profile TCP(TLS) vs QUIC(h3) 背靠背 A/B（设计文档 §8 阶段 2，D-17/D-19）。
 *
 * 实验设计（独立入口，不动 TestEngine 场景状态机 / OkHttp 主测量路径）：
 * - 同 profile 的 token_stream（默认 s2_coding_agent phase0，300 token burst）背靠背
 *   跑 N 对（默认 3+3），**ABAB… 交替顺序**记录（时变网络下组间条件对齐）；
 * - A 组＝Cronet disableQuic（TLS 上协商 http/1.1 或 h2）；B 组＝Cronet enableQuic
 *   + QUIC hint 指向目标主机端口；
 * - **B 组逐样本按 negotiatedProtocol 分箱**（红队"QUIC 启用 ≠ 协商 h3"）：h3 计入
 *   QUIC 组，非 h3 单列 fallback **不进对比**；
 * - 每组测量前各做一次对称 warmup 请求（/serverinfo，不计入样本）：让 B 组 QUIC
 *   会话先建立（否则首样本几乎必然 TCP 竞速获胜落 fallback），A 组同样 warmup 保持
 *   连接预热对称——两组样本均在"引擎内已有热连接"语义下测量，组间可比；
 * - 两栈计时钩子粒度不同（见 CronetStreamClient KDoc），**A/B 结论只在 Cronet 栈内
 *   得出**；结果 stack=cronet，claim scope 仍为 probe_node 口径。
 *
 * fail-closed：h3 为 TLS-only，serverBase 非 https 直接拒跑（明文上做 A/B 无意义且
 * 会静默退化为"两组全 TCP"）；测前守卫与场景 run 同口径（VPN/代理硬拒，D-16）。
 *
 * 日志合同（全部新增 KEY，不动既有 KEY 集）：AB_START / AB_GUARD / AB_PROFILES /
 * AB_WARMUP / AB_SAMPLE / AB_SUMMARY / AB_DB_WRITE / AB_FAILED / AB_END。
 */
class AbRunner(private val context: Context) {

    data class Config(
        val serverBase: String,
        val profileId: String = DEFAULT_PROFILE_ID,
        /** profile 内第几个 token_stream phase（0 起；s2 phase0＝300 token burst） */
        val phaseIndex: Int = DEFAULT_PHASE_INDEX,
        /** 每组样本数（A、B 各 pairs 个，ABAB… 交替） */
        val pairs: Int = DEFAULT_PAIRS,
        /** true=开 Cronet NetLog（externalCacheDir，debug 诊断 h3 协商失败归因用） */
        val netlog: Boolean = false,
    )

    fun run(config: Config): Flow<String> = channelFlow {
        val log: suspend (String) -> Unit = { send(it) }
        val db = AnebDatabase.get(context)
        val runId = TestEngine.newRunId()
        val startedAtEpochMs = System.currentTimeMillis()
        val base = config.serverBase.trim().trimEnd('/')
        log(
            "AB_START run_id=$runId server=$base profile=${config.profileId} " +
                "phase=${config.phaseIndex} pairs=${config.pairs} stack=$STACK " +
                "claim_scope=$CLAIM_SCOPE"
        )

        // ---------------- fail-closed：h3 TLS-only，必须 https ----------------
        val uri = try {
            URI(base)
        } catch (e: Exception) {
            log("AB_FAILED run_id=$runId error=bad_server_url")
            log("AB_END run_id=$runId status=bad_server_url")
            return@channelFlow
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            log("AB_FAILED run_id=$runId error=https_required_for_h3 scheme=${uri.scheme}")
            log("AB_END run_id=$runId status=https_required")
            return@channelFlow
        }
        val host = uri.host ?: run {
            log("AB_FAILED run_id=$runId error=no_host_in_url")
            log("AB_END run_id=$runId status=bad_server_url")
            return@channelFlow
        }
        val port = if (uri.port > 0) uri.port else 443

        // ---------------- 测前守卫（与场景 run 同口径；D-16 代理红线在此把关，
        // Cronet 无 NO_PROXY 等价开关——守卫是 A/B 的唯一直连闸） ----------------
        val guard = NetGuard.guardCheck(context)
        if (!guard.ok) {
            log("AB_GUARD ok=false reasons=${guard.reasons.joinToString(",")}")
            log("AB_END run_id=$runId status=guard_rejected")
            return@channelFlow
        }
        log(
            "AB_GUARD ok=true metadata=" +
                guard.metadata.entries.joinToString(";") { "${it.key}=${it.value}" }.replace(' ', '_')
        )

        // ---------------- profile（控制面拉取走 AnebClient，仅取 tokens 参数） ----------------
        val expectedTokens: Int = try {
            val loaded = ProfileRepository(context).load(AnebClient(), base)
            loaded.warnings.forEach { log("AB_PROFILES warn=${it.replace(' ', '_')}") }
            val profile = loaded.profiles[config.profileId]
                ?: throw IllegalStateException("profile_not_found:${config.profileId}")
            val phase = tokenStreamPhase(profile, config.phaseIndex)
                ?: throw IllegalStateException("token_stream_phase_not_found:${config.phaseIndex}")
            log(
                "AB_PROFILES source=${loaded.source} profile=${config.profileId} " +
                    "version=${profile.version} tokens=${phase.tokens} rate_tps=${phase.rateTps}"
            )
            phase.tokens
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("AB_FAILED run_id=$runId error=profiles:${e.message?.replace(' ', '_') ?: e.javaClass.simpleName}")
            log("AB_END run_id=$runId status=profiles_unavailable")
            return@channelFlow
        }

        val streamUrl = "$base/api/v1/stream?profile=${config.profileId}&phase=${config.phaseIndex}&run=$runId"
        val rows = ArrayList<AbResultEntity>(config.pairs * 2)
        var status = "completed"

        var clientA: CronetStreamClient? = null
        var clientB: CronetStreamClient? = null
        try {
            val netLogB = if (config.netlog) {
                val dir = context.externalCacheDir ?: context.cacheDir
                java.io.File(dir, "ab_netlog_b.json").absolutePath.also {
                    log("AB_NETLOG group=b path=$it")
                }
            } else {
                null
            }
            clientA = CronetStreamClient(context, host, port, quic = false)
            clientB = CronetStreamClient(context, host, port, quic = true, netLogPath = netLogB)

            // ---------------- 对称 warmup（不计样本；见类 KDoc） ----------------
            for ((label, client) in listOf(GROUP_A to clientA, GROUP_B to clientB)) {
                val w = client.streamSse("$base/api/v1/serverinfo")
                log(
                    "AB_WARMUP group=$label protocol=${w.negotiatedProtocol ?: "null"} " +
                        "http=${w.httpCode ?: "null"} error=${w.error?.replace(' ', '_') ?: "none"}"
                )
            }

            // ---------------- ABAB… 交替采样 ----------------
            val order = alternatingOrder(config.pairs)
            for ((sampleIndex, group) in order.withIndex()) {
                val client = if (group == GROUP_A) clientA else clientB
                val r = client.streamSse(streamUrl)
                val proto = r.negotiatedProtocol
                val bin = binOf(group, proto)
                val kpi = sampleKpi(
                    events = r.stream?.events ?: emptyList(),
                    preludeSrvTsUs = r.stream?.prelude?.let {
                        ScenarioRunner.parsePreludeSrvTsUs(it.raw)
                    },
                    expectedTokens = expectedTokens,
                    requestStartNanos = r.requestStartNanos,
                    transportError = r.error != null,
                )
                rows.add(
                    AbResultEntity(
                        runId = runId,
                        startedAtEpochMs = startedAtEpochMs,
                        serverBase = base,
                        stack = STACK,
                        claimScope = CLAIM_SCOPE,
                        profileId = config.profileId,
                        phaseIndex = config.phaseIndex,
                        sampleIndex = sampleIndex,
                        groupLabel = group,
                        bin = bin,
                        negotiatedProtocol = proto,
                        httpCode = r.httpCode,
                        error = r.error,
                        ttftMs = kpi.ttftMs,
                        itlP50Ms = kpi.itlP50Ms,
                        itlP95Ms = kpi.itlP95Ms,
                        itlSampleCount = kpi.itlSampleCount,
                        stallCount = kpi.stallCount,
                        stallRate = kpi.stallRate,
                        gapCount = kpi.gapCount,
                        dupCount = kpi.dupCount,
                        tokenEventCount = kpi.tokenEventCount,
                        truncatedEarly = kpi.truncatedEarly,
                    )
                )
                log(
                    "AB_SAMPLE run_id=$runId idx=$sampleIndex group=$group " +
                        "protocol=${proto ?: "null"} bin=$bin " +
                        "ttft_ms=${kpi.ttftMs.fmt()} itl_p50_ms=${kpi.itlP50Ms.fmt()} " +
                        "itl_p95_ms=${kpi.itlP95Ms.fmt()} itl_n=${kpi.itlSampleCount} " +
                        "stall_rate=${kpi.stallRate.fmt(4)} gaps=${kpi.gapCount} dup=${kpi.dupCount} " +
                        "events=${kpi.tokenEventCount} truncated_early=${kpi.truncatedEarly} " +
                        "http=${r.httpCode ?: "null"} error=${r.error?.replace(' ', '_') ?: "none"}"
                )
            }

            // ---------------- 汇总（fallback 单列，不进对比） ----------------
            val s = summarize(rows)
            log(
                "AB_SUMMARY run_id=$runId stack=$STACK " +
                    "a_n=${s.aN} a_ttft_p50_ms=${s.aTtftP50Ms.fmt()} a_itl_p50_ms=${s.aItlP50Ms.fmt()} " +
                    "a_itl_p95_ms=${s.aItlP95Ms.fmt()} " +
                    "quic_n=${s.quicN} quic_ttft_p50_ms=${s.quicTtftP50Ms.fmt()} " +
                    "quic_itl_p50_ms=${s.quicItlP50Ms.fmt()} quic_itl_p95_ms=${s.quicItlP95Ms.fmt()} " +
                    "fallback_n=${s.fallbackN} fallback_protocols=${s.fallbackProtocols.ifEmpty { "none" }} " +
                    "comparable=${s.comparable}"
            )
            if (s.quicN == 0) status = "no_h3_negotiated"
        } catch (e: CancellationException) {
            throw e // 不吞取消（fail-closed §4.6/§4.7）
        } catch (e: Exception) {
            status = "error:${e.javaClass.simpleName}"
            log("AB_FAILED run_id=$runId error=${e.toString().replace(' ', '_')}")
        } finally {
            // close 失败＝引擎未能静默 shutdown（原生资源泄漏征兆），必须留痕不静默吞
            runCatching { clientA?.close() }.onFailure {
                android.util.Log.e(
                    LOG_TAG,
                    "AB_ENGINE_CLOSE_FAILED group=a reason=${it.toString().replace(' ', '_')}",
                )
            }
            runCatching { clientB?.close() }.onFailure {
                android.util.Log.e(
                    LOG_TAG,
                    "AB_ENGINE_CLOSE_FAILED group=b reason=${it.toString().replace(' ', '_')}",
                )
            }
            // 逐样本行兜底落库（部分样本也有取证价值——invalid 只抑制聚合，原始数据全量入库）
            if (rows.isNotEmpty()) {
                runCatching { db.abResultDao().insertAll(rows) }
                    .onSuccess { log("AB_DB_WRITE run_id=$runId rows=${rows.size}") }
                    .onFailure {
                        android.util.Log.e(
                            LOG_TAG,
                            "DB_WRITE_FAILED table=ab_result reason=${it.toString().replace(' ', '_')}",
                        )
                    }
            }
        }
        log("AB_END run_id=$runId status=$status")
    }.flowOn(Dispatchers.IO)

    // ------------------------------------------------------------------
    // 纯函数层（JVM 可单测；无 Android 依赖）
    // ------------------------------------------------------------------

    /** 每样本 KPI（Cronet 栈内口径；不可算记 null，R-10）。 */
    data class SampleKpi(
        val ttftMs: Double?,
        val itlP50Ms: Double?,
        val itlP95Ms: Double?,
        val itlSampleCount: Int,
        val stallCount: Int?,
        val stallRate: Double?,
        val gapCount: Int,
        val dupCount: Int,
        val tokenEventCount: Int,
        val truncatedEarly: Boolean,
    )

    /** A/B 汇总（fallback 单列；组内无样本记 null 而非 0，R-10）。 */
    data class Summary(
        val aN: Int,
        val aTtftP50Ms: Double?,
        val aItlP50Ms: Double?,
        val aItlP95Ms: Double?,
        val quicN: Int,
        val quicTtftP50Ms: Double?,
        val quicItlP50Ms: Double?,
        val quicItlP95Ms: Double?,
        val fallbackN: Int,
        /** fallback 样本实际协商到的协议清单（逗号分隔，去重） */
        val fallbackProtocols: String,
    ) {
        /** 两组均有可比样本才可下结论 */
        val comparable: Boolean get() = aN > 0 && quicN > 0
    }

    companion object {
        const val DEFAULT_PROFILE_ID = "s2_coding_agent"
        const val DEFAULT_PHASE_INDEX = 0
        const val DEFAULT_PAIRS = 3

        const val GROUP_A = "a"
        const val GROUP_B = "b"
        const val BIN_TCP = "tcp"
        const val BIN_QUIC = "quic"
        const val BIN_FALLBACK = "fallback"

        const val STACK = "cronet"

        /** 与场景 run 同口径（探测对象仍是到 probe_node 的端到端路径） */
        const val CLAIM_SCOPE = "application_end_to_end_to_probe_node"

        private const val LOG_TAG = "AnebProbe"

        /** ABAB… 交替顺序（时变网络下组间条件对齐；执行序即记录序）。 */
        fun alternatingOrder(pairs: Int): List<String> =
            (0 until pairs).flatMap { listOf(GROUP_A, GROUP_B) }

        /**
         * h3 判定（唯一依据＝逐样本 negotiatedProtocol）：接受 "h3" 及带草案后缀的
         * "h3-29" 等；历史 QUIC 串（"quic/1+spdy/3"）一并计 QUIC。大小写不敏感。
         */
        fun isH3(protocol: String?): Boolean {
            val p = protocol?.lowercase() ?: return false
            return p == "h3" || p.startsWith("h3-") || p.startsWith("quic")
        }

        /**
         * 分箱：A 组恒 tcp（disableQuic 下协商结果只会是 http/1.1 或 h2）；
         * B 组 h3 → quic，非 h3 → fallback（不进对比，红队"QUIC 启用 ≠ 协商 h3"）。
         */
        fun binOf(group: String, protocol: String?): String = when {
            group == GROUP_A -> BIN_TCP
            isH3(protocol) -> BIN_QUIC
            else -> BIN_FALLBACK
        }

        /** 找 profile 内第 [phaseIndex] 个 token_stream phase（0 起）；越界 null。 */
        fun tokenStreamPhase(profile: ScenarioProfile, phaseIndex: Int): ProfilePhase? =
            profile.phases.filter { it.type == ProfilePhase.TYPE_TOKEN_STREAM }
                .getOrNull(phaseIndex)

        /**
         * 每样本 KPI：
         * - TTFT＝请求发起（UrlRequest.start 前打戳）→ 首 token 到达，减服务端已知
         *   注入时延（首 token sched − prelude srv_ts；R-20 同款剥离）。Cronet 无
         *   requestHeadersEnd 级钩子 → 起点比 OkHttp 路径早（含请求写出），两组同
         *   语义故组间可比、与 OkHttp 栈不可互比。prelude/首 sched 缺失记 null（R-10）。
         * - ITL＝主口径校正样本（ScenarioKpi.correctedItlSamplesMs 复用：剔
         *   sameReadBatch/pause/0 到达间隔，corrected = arrivalΔ − flushΔ + schedΔ）。
         * - stall＝校正 ITL > [KpiCalculator.STALL_THRESHOLD_MS] 占比。
         * - gap/dup＝seq join 审计（R-08），含尾部整体截断补计（同 AnebClient.stream）。
         * - [transportError]=true 的样本 KPI 全 null（失败样本不出值，R-10）；gap/dup
         *   仍如实统计（诊断价值）。
         */
        fun sampleKpi(
            events: List<TokenEvent>,
            preludeSrvTsUs: Long?,
            expectedTokens: Int,
            requestStartNanos: Long,
            transportError: Boolean = false,
        ): SampleKpi {
            // ---- seq 审计（R-08：join 校验 + 尾部截断补计） ----
            val seen = HashSet<Long>(events.size * 2)
            var dups = 0
            for (e in events) if (!seen.add(e.seq)) dups++
            val maxSeq = seen.maxOrNull()
            var gaps = 0
            if (maxSeq != null) {
                var s = 0L
                while (s <= maxSeq) {
                    if (s !in seen) gaps++
                    s++
                }
            }
            val received = maxSeq?.plus(1L) ?: 0L
            val tailMissing = (expectedTokens - received).coerceAtLeast(0L).toInt()
            gaps += tailMissing

            if (transportError) {
                return SampleKpi(
                    ttftMs = null, itlP50Ms = null, itlP95Ms = null, itlSampleCount = 0,
                    stallCount = null, stallRate = null,
                    gapCount = gaps, dupCount = dups,
                    tokenEventCount = events.size, truncatedEarly = tailMissing > 0,
                )
            }

            // ---- TTFT（剥服务端已知注入时延；不可剥离不出值） ----
            val first = events.minByOrNull { it.seq }
            val ttftMs = if (first != null && preludeSrvTsUs != null && first.schedUs >= 0) {
                (first.arrivalNanos - requestStartNanos) / 1e6 - (first.schedUs - preludeSrvTsUs) / 1e3
            } else {
                null
            }

            // ---- ITL（主口径校正，复用 ScenarioKpi 已锚定实现） ----
            val join = ScenarioKpi.joinStreams(
                listOf(ScenarioKpi.StreamTokens(expectedTokens, events))
            )
            val itl = ScenarioKpi.correctedItlSamplesMs(join.samples, join.pauseSeqs)
            val sorted = itl.sorted()
            val stallCount = if (itl.isEmpty()) null else itl.count { it > KpiCalculator.STALL_THRESHOLD_MS }
            return SampleKpi(
                ttftMs = ttftMs,
                itlP50Ms = percentile(sorted, 0.50),
                itlP95Ms = percentile(sorted, 0.95),
                itlSampleCount = itl.size,
                stallCount = stallCount,
                stallRate = stallCount?.let { it.toDouble() / itl.size },
                gapCount = gaps,
                dupCount = dups,
                tokenEventCount = events.size,
                truncatedEarly = tailMissing > 0,
            )
        }

        /**
         * 汇总：A 组＝bin=tcp 且无错误的样本；QUIC 组＝bin=quic 且无错误；fallback
         * 单列计数**不进对比**。中位数取每样本 KPI 的 P50（组内无可用值记 null，R-10）。
         */
        fun summarize(rows: List<AbResultEntity>): Summary {
            val a = rows.filter { it.groupLabel == GROUP_A && it.bin == BIN_TCP && it.error == null }
            val quic = rows.filter { it.bin == BIN_QUIC && it.error == null }
            val fallback = rows.filter { it.bin == BIN_FALLBACK }
            return Summary(
                aN = a.size,
                aTtftP50Ms = medianOf(a.mapNotNull { it.ttftMs }),
                aItlP50Ms = medianOf(a.mapNotNull { it.itlP50Ms }),
                aItlP95Ms = medianOf(a.mapNotNull { it.itlP95Ms }),
                quicN = quic.size,
                quicTtftP50Ms = medianOf(quic.mapNotNull { it.ttftMs }),
                quicItlP50Ms = medianOf(quic.mapNotNull { it.itlP50Ms }),
                quicItlP95Ms = medianOf(quic.mapNotNull { it.itlP95Ms }),
                fallbackN = fallback.size,
                fallbackProtocols = fallback.mapNotNull { it.negotiatedProtocol }
                    .distinct().joinToString(","),
            )
        }

        /** 最近秩百分位（与 KpiCalculator 同款保守口径：ceil(p·n) 的第 k 小）；空样本 null。 */
        fun percentile(sorted: List<Double>, p: Double): Double? {
            if (sorted.isEmpty()) return null
            val k = kotlin.math.ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[k - 1]
        }

        fun medianOf(values: List<Double>): Double? = percentile(values.sorted(), 0.50)

        private fun Double?.fmt(digits: Int = 2): String =
            this?.let { "%.${digits}f".format(it) } ?: "null"
    }
}
