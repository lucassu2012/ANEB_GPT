package com.aneb.probe.ui

import androidx.annotation.DrawableRes
import com.aneb.probe.R
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * 真实业务场景轮换库（零 emoji）：
 * 每次测试基于 `runId` / 轮次递增序号自动切换不同真实业务用例，避免每次测速展示相同静态文本；
 * 且每个场景文本与代码长度按完整 SSE 流时长设计（650–2,400 字符），配合逐字符 1:1 跟随真实
 * `liveStreamEvents` 推进，使屏幕生成速度与上方实时 SSE 到达速率曲线严格同步。
 */
object RealisticScenarioCatalog {

    private val sessionSequence = AtomicInteger(0)

    fun nextRunSeed(): Int = sessionSequence.getAndIncrement()

    fun pickIndex(runKey: String?, count: Int, fallbackSeed: Int = 0): Int {
        if (count <= 1) return 0
        if (runKey.isNullOrBlank()) return abs(fallbackSeed) % count
        var h = 17
        for (c in runKey) {
            h = h * 31 + c.code
        }
        return abs(h) % count
    }

    data class ChatCase(
        val id: String,
        val headerTitle: String,
        val prompt: String,
        val thinkingLabelDone: String,
        val thinkingLabelActive: String,
        val thinkingBody: String,
        val answerBody: String,
    )

    data class CodingCase(
        val id: String,
        val language: String,
        val fileName: String,
        val fullPath: String,
        val branch: String,
        val diffStat: String,
        val toolSteps: List<String>,
        val diffLines: List<Pair<Char, String>>,
        val baseLineNumber: Int,
    ) {
        val totalChars: Int = diffLines.sumOf { it.second.length + 1 }
    }

    data class VisionBox(
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val tone: String, // "excellent" | "brand" | "fair"
    )

    data class MultimodalVisionCase(
        val id: String,
        val headerTitle: String,
        val reportTitle: String,
        val fileName: String,
        val resolutionTag: String,
        val detectionSummary: String,
        @DrawableRes val imageRes: Int,
        val boxes: List<VisionBox>,
        val analysisBody: String,
    )

    data class MultimodalDocCase(
        val id: String,
        val fileName: String,
        val metaSummary: String,
        val indexedSummary: String,
        val reportTitle: String,
        val analysisBody: String,
    )

    data class MultimodalTextCase(
        val id: String,
        val roleTitle: String,
        val prompt: String,
        val reportTitle: String,
        val answerBody: String,
    )

    val chatCases: List<ChatCase> = listOf(
        ChatCase(
            id = "chat_sea_hq",
            headerTitle = "DeepThink-R1 · 跨境架构决策",
            prompt = "对比深圳前海与新加坡作为东南亚 SaaS 区域总部的三年综合成本、跨境专线时延与合规落地建议，输出可直接上会的结论。",
            thinkingLabelDone = "已完成深度推演 · 检索 3 项跨境海缆与税制基准",
            thinkingLabelActive = "深度推演中 · 正在校准深港 IPLC 与 PDPA 合规模型…",
            thinkingBody = "拆解维度：1) 3 年 TCO（租金 + 研发/商务人力 + 15% vs 17% 税率阶梯）；2) 东南亚主要 POP 实测 RTT（前海经香港 IPLC 至新加坡 ~36ms）；3) 跨境脱敏数据回传合规周期。",
            answerBody = "一、高管决策摘要（推荐「前海研发 + 新加坡结算双核」架构）\n" +
                "若首年团队 35–60 人且核心研发在国内，纯新加坡总部 3 年综合成本约高出 58%；建议采用「深圳前海承接核心产研与 AI 算法（享 15% 优惠税率）+ 新加坡设立区域商务与数据合规主体」的双核布局，在成本、交付速度与跨境合规之间取得最优平衡。\n\n" +
                "二、核心指标量化对比（3 年口径）\n" +
                "• 人力与办公综合成本：深圳前海约 1,850 万 RMB；新加坡核心区约 2,920 万 RMB，主要差额来自资深全栈/算法薪资溢价与甲级写字楼租金。\n" +
                "• 东南亚端到端网络时延：新加坡本地机房直连雅加达/曼谷/吉隆坡实测 RTT 8–22 ms；前海经深港双路由 IPLC 专线接入新加坡边缘 POP 实测 RTT 34–39 ms，完全满足企业 SaaS 与实时 AI 对话流体验。\n" +
                "• 隐私合规与资金结算：新加坡主体直接满足 PDPA 与 ISO 27701 客户审计要求，支持多币种收单；前海研发中心通过标准合同仅访问脱敏遥测与模型梯度，不触碰原始 PII 数据。\n\n" +
                "三、90 天分阶段落地路线\n" +
                "1. 第 1–3 周：完成新加坡 ACRA 注册与新加坡 AWS/阿里云双区 POP 压测，锁定 2 条独立海缆运营商备份链路。\n" +
                "2. 第 4–6 周：上线深港双活 IPLC 隧道并部署 AI 网关自动降级策略（异常抖动 > 180ms 自动切换备用 POP，目标 RTO < 15 秒）。\n" +
                "3. 第 7–12 周：完成首批 3 家印尼/马来西亚标杆企业交付，建立月度 TTFT、卡顿率与 SLA 履约复盘看板。",
        ),
        ChatCase(
            id = "chat_5ga_factory",
            headerTitle = "DeepThink-R1 · 5G-A 专网方案",
            prompt = "华南汽车电子工厂计划用 5G-A 工业专网替代有线工业以太网，支撑 24 台视觉质检相机与 AGV 调度，请给出 UPF 下沉架构与 ROI 测算。",
            thinkingLabelDone = "已完成深度推演 · 核对 3GPP R18 上行增强与工位密度",
            thinkingLabelActive = "深度推演中 · 正在估算 24 路 4K 工业图传上行并发带宽…",
            thinkingBody = "核心约束：单台 AOI 视觉相机 4K 无损回传峰值 38 Mbps，24 台并发需上行 >= 920 Mbps；AGV 运动控制要求空口 RTT P99 <= 15ms 且零长静默中断。",
            answerBody = "一、网络承载瓶颈与 5G-A 架构选型\n" +
                "传统 5G 宏网上下行配比偏重下行（DDDSU），24 台 4K 质检相机并发上传会瞬间打满上行时隙并引发 AGV 控制面排队抖动。建议部署「园区独享 5G-A 大上行帧结构（DSUUU）+ 厂区本地 MEC/UPF 下沉」方案，将单小区上行峰值提升至 1.8 Gbps 以上。\n\n" +
                "二、业务切片与 QoS 隔离策略\n" +
                "• URLLC 控制切片（5QI=82）：独占 12% PRB 资源，专供 40 台 AGV 与机械臂安全联锁信号，空口单向时延稳定在 6–9 ms，彻底隔离视频上传突发的 Bufferbloat 冲击。\n" +
                "• eMBB 视觉质检切片（5QI=6）：承接 24 路 AOI 高清图像上传至本地边缘推理集群，单图 12 MB 回传 + 缺陷推理端到端耗时控制在 180 ms 以内，满足产线 0.6 秒/件节拍。\n\n" +
                "三、3 年投资回报（ROI）测算\n" +
                "1. 初期投入：厂区微站 6 套 + 下沉 UPF + 工业 CPE 合计约 245 万 RMB。\n" +
                "2. 直接收益：产线每季度换型免去 48 小时重新布线停工损失（年化省 135 万），AOI 边缘统一推理漏检率由 0.42% 降至 0.08%（年化减少客诉返工 78 万）。\n" +
                "3. 回收周期：静态投资回收期约 14.2 个月，具备向集团其余 4 座华南基地复制的标准化条件。",
        ),
        ChatCase(
            id = "chat_ai_gateway_dr",
            headerTitle = "DeepThink-R1 · AI 网关容灾复盘",
            prompt = "复盘双十一峰值期间 AI 智能客服网关出现 P99 首字飙升至 4.8 秒的根因，并输出下一代多活流控与优雅降级改造方案。",
            thinkingLabelDone = "已完成深度推演 · 关联队列积压、KV 缓存驱逐与重试风暴",
            thinkingLabelActive = "深度推演中 · 正在定位首字 P99 尾延迟放大因子…",
            thinkingBody = "故障链条：大促 20:00 并发请求增至平日 6.4 倍 -> 推理集群 Prefill 队列超长 -> 客户端 3 秒超时触发自动重试 -> 有效吞吐反降 41%，形成经典重试雪崩。",
            answerBody = "一、根因定位（三大放大器叠加）\n" +
                "1. Prefill 与 Decode 混部干扰：长上下文订单查询（4K+ tokens）阻塞短回复 Decode 步进，导致在途 SSE 流出现 600–1,400 ms 锯齿停顿。\n" +
                "2. 客户端无退避重试风暴：移动端 SDK 在 TTFT 超过 3 秒时直接重发完整 Prompt，使网关入口流量在 90 秒内放大 2.8 倍。\n" +
                "3. 跨区域回源无熔断：华东主集群过载后，未按实时 TTFT 探针权重切流至华南备用推理池，反而继续排队直至触发 504 网关超时。\n\n" +
                "二、架构级改造方案（目标峰值 P99 TTFT <= 1.1s）\n" +
                "• 推理层 PD 分离：拆分 Prefill 计算池与 Decode 生成池，并通过高速 RDMA 异步传输 KV Cache，保障流式吐字 ITL P95 稳定在 28 ms 以下。\n" +
                "• 自适应令牌桶与优先级队列：按订单支付态/售前咨询分级调度，超载时自动截断冗余历史上下文并启用 7B 轻量精调模型兜底，拒绝整请求 504 失败。\n" +
                "• 端网协同断路器：客户端取消盲目重试，改由网关返回 Retry-After + 抖动退避窗口；每周常态化注入 5% 丢包与 300ms 延迟混沌演练，确保持续达标。",
        ),
        ChatCase(
            id = "chat_gpu_quant",
            headerTitle = "DeepThink-R1 · 推理集群测算",
            prompt = "银行私有化部署 72B 大模型客服与合规审计双业务，对比 FP16、FP8 与 AWQ-INT4 量化在并发 200 路下的 GPU 卡数、吞吐与精度损耗。",
            thinkingLabelDone = "已完成深度推演 · 校验显存带宽受限区间与 KV Cache 占用",
            thinkingLabelActive = "深度推演中 · 正在核算 200 并发 8K 上下文显存水位…",
            thinkingBody = "权重显存：72B 模型 FP16 约 144 GB、FP8 约 72 GB、INT4 约 38 GB；200 并发平均 4K 上下文 KV Cache 约需 160 GB（启用 FP8 KV 后可压缩至 80 GB）。",
            answerBody = "一、三档精度部署规格与硬件成本对比\n" +
                "• FP16 原精度基线：单实例至少 4×80GB GPU 仅能承接约 35 路高流畅并发，200 路并发需 24 张卡（3 台 8 卡机），硬件与机房功耗成本最高，仅建议用于离线基准校验。\n" +
                "• FP8 权重 + FP8 KV Cache（推荐主力方案）：单机 8×80GB GPU 划分 2 组 TP4 实例，结合前缀缓存命中率 45% 可稳定支撑 110 路并发，全量 200 路仅需 16 张卡，算力成本直降 33%，且金融合规抽取评测 F1 仅波动 -0.18%，几乎无感。\n" +
                "• AWQ-INT4 量化方案：单组 TP2 即可启动，总卡数压缩至 10–12 张，但在复杂多表财报推理与长法规交叉引用任务中，数值幻觉率上升约 1.4%，建议仅承接标准化 FAQ 客服流量。\n\n" +
                "二、混合路由落地建议\n" +
                "1. 采用「合规审计走 FP8 高精度池 + 高频客服走 INT4 弹性池」的意图分级路由，整体集群规模可压至 14 卡并预留 25% 突发冗余。\n" +
                "2. 上线前必须使用真实长文档 + 并发压测工具连续跑满 72 小时，重点监控显存碎片率、ITL P99 尾延迟与 200 路同时首字冲击下的队列恢复时间。",
        ),
    )

    val codingCases: List<CodingCase> = listOf(
        CodingCase(
            id = "code_kotlin_quic",
            language = "Kotlin",
            fileName = "ConnectionPoolGuard.kt",
            fullPath = "gateway/src/main/kotlin/net/ConnectionPoolGuard.kt",
            branch = "fix/quic-0rtt-fallback",
            diffStat = "+38  -5",
            baseLineNumber = 78,
            toolSteps = listOf(
                "grep_search(\"QuicSessionPool\") -> 4 处命中",
                "read_file(\"ConnectionPoolGuard.kt:78-138\")",
                "apply_patch(ConnectionPoolGuard.kt +38 -5)",
                "run_tests(\":gateway:testQuicFallback\") -> 14/14 PASS",
            ),
            diffLines = listOf(
                ' ' to "class ConnectionPoolGuard(",
                ' ' to "    private val quicPool: QuicSessionPool,",
                ' ' to "    private val h2Pool: Http2SessionPool,",
                ' ' to "    private val metrics: TransportTelemetry,",
                ' ' to "    private val maxFallbackRetries: Int = 3,",
                ' ' to ") {",
                ' ' to "    private val stallThresholdMs = 280L",
                ' ' to "    private val circuitOpenedAt = AtomicLong(0L)",
                ' ' to "",
                ' ' to "    suspend fun acquireSession(endpoint: Endpoint): ConnectionResult {",
                ' ' to "        val cached = quicPool.peekHealthy(endpoint)",
                '-' to "        if (cached != null) return ConnectionResult.Ready(cached)",
                '-' to "        return ConnectionResult.Ready(quicPool.openZeroRtt(endpoint))",
                '+' to "        if (cached != null && !cached.isStalled(stallThresholdMs)) {",
                '+' to "            metrics.recordPoolHit(endpoint, protocol = \"h3\")",
                '+' to "            return ConnectionResult.Ready(cached)",
                '+' to "        }",
                '+' to "        if (isQuicCircuitOpen(endpoint)) {",
                '+' to "            val h2 = h2Pool.acquireOrConnect(endpoint)",
                '+' to "            metrics.recordCircuitBypass(endpoint, protocol = \"h2\")",
                '+' to "            return ConnectionResult.Ready(h2)",
                '+' to "        }",
                '+' to "        var attempt = 0",
                '+' to "        var lastError: IOException? = null",
                '+' to "        while (attempt < maxFallbackRetries) {",
                '+' to "            val startNs = System.nanoTime()",
                '+' to "            try {",
                '+' to "                val session = if (attempt == 0) {",
                '+' to "                    quicPool.openZeroRtt(endpoint, timeoutMs = 450L)",
                '+' to "                } else {",
                '+' to "                    h2Pool.openFreshTcpTls(endpoint, timeoutMs = 900L)",
                '+' to "                }",
                '+' to "                val rttMs = (System.nanoTime() - startNs) / 1_000_000.0",
                '+' to "                metrics.recordHandshake(endpoint, attempt, rttMs)",
                '+' to "                return ConnectionResult.Ready(session)",
                '+' to "            } catch (e: IOException) {",
                '+' to "                lastError = e",
                '+' to "                attempt++",
                '+' to "                if (attempt == 1) tripQuicCircuit(endpoint, e)",
                '+' to "                metrics.recordFallback(endpoint, attempt, e.javaClass.simpleName)",
                '+' to "                if (attempt >= maxFallbackRetries) break",
                '+' to "                delay(jitteredBackoffMs(attempt))",
                '+' to "            }",
                '+' to "        }",
                '+' to "        throw lastError ?: IOException(\"exhausted_retries:\${endpoint.host}\")",
                ' ' to "    }",
                ' ' to "",
                '+' to "    private fun isQuicCircuitOpen(endpoint: Endpoint): Boolean {",
                '+' to "        val opened = circuitOpenedAt.get()",
                '+' to "        if (opened == 0L) return false",
                '+' to "        return (System.nanoTime() - opened) < 30_000_000_000L",
                '+' to "    }",
                '+' to "",
                '+' to "    private fun jitteredBackoffMs(attempt: Int): Long {",
                '+' to "        val base = (65L shl attempt.coerceAtMost(4)).coerceAtMost(800L)",
                '+' to "        return base + ThreadLocalRandom.current().nextLong(15L, 55L)",
                '+' to "    }",
                ' ' to "}",
            ),
        ),
        CodingCase(
            id = "code_go_sse",
            language = "Go",
            fileName = "stream_scheduler.go",
            fullPath = "server/internal/sse/stream_scheduler.go",
            branch = "perf/sse-zero-copy-flush",
            diffStat = "+36  -4",
            baseLineNumber = 112,
            toolSteps = listOf(
                "grep_search(\"FlushTokenEvent\") -> 6 处命中",
                "read_file(\"stream_scheduler.go:112-168\")",
                "apply_patch(stream_scheduler.go +36 -4)",
                "go test ./internal/sse/... -race -> PASS (0.84s)",
            ),
            diffLines = listOf(
                ' ' to "func (s *StreamScheduler) ServeTokenStream(",
                ' ' to "    ctx context.Context,",
                ' ' to "    w http.ResponseWriter,",
                ' ' to "    plan StreamPlan,",
                ' ' to ") error {",
                ' ' to "    flusher, ok := w.(http.Flusher)",
                ' ' to "    if !ok {",
                ' ' to "        return ErrStreamingUnsupported",
                ' ' to "    }",
                '-' to "    ticker := time.NewTicker(plan.Interval)",
                '-' to "    defer ticker.Stop()",
                '+' to "    var buf bytes.Buffer",
                '+' to "    buf.Grow(256)",
                '+' to "    origin := time.Now()",
                '+' to "    deadline := origin.Add(plan.InitialDwell)",
                '+' to "    if err := sleepUntil(ctx, deadline); err != nil {",
                '+' to "        return err",
                '+' to "    }",
                ' ' to "    for seq := 0; seq < plan.TotalTokens; seq++ {",
                '-' to "        <-ticker.C",
                '-' to "        fmt.Fprintf(w, \"event: token\\ndata: {\\\"seq\\\":%d}\\n\\n\", seq)",
                '+' to "        target := origin.Add(plan.OffsetFor(seq))",
                '+' to "        if err := sleepUntil(ctx, target); err != nil {",
                '+' to "            return err",
                '+' to "        }",
                '+' to "        preFlushUs := time.Since(origin).Microseconds()",
                '+' to "        buf.Reset()",
                '+' to "        buf.WriteString(\"event: token\\ndata: {\\\"seq\\\":\")",
                '+' to "        buf.WriteString(strconv.Itoa(seq))",
                '+' to "        buf.WriteString(\",\\\"sched_us\\\":\")",
                '+' to "        buf.WriteString(strconv.FormatInt(plan.OffsetFor(seq).Microseconds(), 10))",
                '+' to "        buf.WriteString(\",\\\"pre_flush_us\\\":\")",
                '+' to "        buf.WriteString(strconv.FormatInt(preFlushUs, 10))",
                '+' to "        buf.WriteString(\"}\\n\\n\")",
                '+' to "        if _, err := w.Write(buf.Bytes()); err != nil {",
                '+' to "            s.metrics.IncWriteAbort(plan.ProfileID)",
                '+' to "            return err",
                '+' to "        }",
                '+' to "        flusher.Flush()",
                '+' to "        if gap := time.Since(target); gap > 12*time.Millisecond {",
                '+' to "            s.metrics.ObserveSchedulerLag(gap)",
                '+' to "        }",
                ' ' to "    }",
                '+' to "    return s.writeSummaryFrame(w, flusher, origin, plan.TotalTokens)",
                ' ' to "}",
                ' ' to "",
                '+' to "func sleepUntil(ctx context.Context, at time.Time) error {",
                '+' to "    d := time.Until(at)",
                '+' to "    if d <= 0 {",
                '+' to "        return ctx.Err()",
                '+' to "    }",
                '+' to "    timer := time.NewTimer(d)",
                '+' to "    defer timer.Stop()",
                '+' to "    select {",
                '+' to "    case <-ctx.Done():",
                '+' to "        return ctx.Err()",
                '+' to "    case <-timer.C:",
                '+' to "        return nil",
                '+' to "    }",
                '+' to "}",
            ),
        ),
        CodingCase(
            id = "code_ts_jitter",
            language = "TypeScript",
            fileName = "AdaptiveJitterBuffer.ts",
            fullPath = "client/audio/AdaptiveJitterBuffer.ts",
            branch = "feat/rtc-plc-bargein",
            diffStat = "+39  -4",
            baseLineNumber = 42,
            toolSteps = listOf(
                "grep_search(\"popPlayoutFrame\") -> 3 处命中",
                "read_file(\"AdaptiveJitterBuffer.ts:42-98\")",
                "apply_patch(AdaptiveJitterBuffer.ts +39 -4)",
                "vitest run audio/AdaptiveJitterBuffer.spec.ts -> 19/19 PASS",
            ),
            diffLines = listOf(
                ' ' to "export class AdaptiveJitterBuffer {",
                ' ' to "  private readonly ring = new Map<number, AudioPacket>();",
                ' ' to "  private targetDelayMs = 60;",
                ' ' to "  private ewmaJitterMs = 4.0;",
                '+' to "  private consecutivePlcFrames = 0;",
                '+' to "  private fadingOutBargeIn = false;",
                ' ' to "",
                ' ' to "  pushInboundFrame(packet: AudioPacket, arrivalMs: number): void {",
                ' ' to "    const delta = Math.abs(arrivalMs - packet.expectedArrivalMs);",
                '-' to "    this.ewmaJitterMs = 0.9 * this.ewmaJitterMs + 0.1 * delta;",
                '-' to "    this.targetDelayMs = Math.min(200, this.ewmaJitterMs * 3);",
                '+' to "    const alpha = delta > this.ewmaJitterMs ? 0.28 : 0.08;",
                '+' to "    this.ewmaJitterMs = (1 - alpha) * this.ewmaJitterMs + alpha * delta;",
                '+' to "    const desired = Math.ceil(this.ewmaJitterMs * 3.2 + 20);",
                '+' to "    this.targetDelayMs = clamp(desired, 40, 260);",
                '+' to "    if (this.fadingOutBargeIn && packet.turnId !== this.activeTurnId) {",
                '+' to "      return; // drop stale turn frames immediately after barge-in",
                '+' to "    }",
                ' ' to "    this.ring.set(packet.seq, packet);",
                ' ' to "    this.evictExpired(packet.seq - 64);",
                ' ' to "  }",
                ' ' to "",
                ' ' to "  popPlayoutFrame(nextSeq: number): PlayoutChunk {",
                ' ' to "    const pkt = this.ring.get(nextSeq);",
                '-' to "    if (!pkt) return PlayoutChunk.silence(20);",
                '-' to "    this.ring.delete(nextSeq);",
                '+' to "    if (!pkt) {",
                '+' to "      this.consecutivePlcFrames++;",
                '+' to "      if (this.consecutivePlcFrames <= 3) {",
                '+' to "        return this.plcSynthesizer.concealFrame(this.lastPcm, 20);",
                '+' to "      }",
                '+' to "      this.telemetry.recordLongSilenceMs(this.consecutivePlcFrames * 20);",
                '+' to "      return PlayoutChunk.comfortNoise(20);",
                '+' to "    }",
                '+' to "    this.consecutivePlcFrames = 0;",
                '+' to "    this.ring.delete(nextSeq);",
                '+' to "    const pcm = this.fadingOutBargeIn",
                '+' to "      ? applyCosineFadeOut(pkt.pcm, 8)",
                '+' to "      : pkt.pcm;",
                '+' to "    this.lastPcm = pcm;",
                '+' to "    return PlayoutChunk.voice(pcm, this.targetDelayMs);",
                ' ' to "  }",
                ' ' to "",
                '+' to "  triggerBargeIn(nextTurnId: string): void {",
                '+' to "    this.fadingOutBargeIn = true;",
                '+' to "    this.activeTurnId = nextTurnId;",
                '+' to "    this.ring.clear();",
                '+' to "    this.telemetry.markBargeFlush(Date.now());",
                '+' to "  }",
                ' ' to "}",
            ),
        ),
        CodingCase(
            id = "code_py_router",
            language = "Python",
            fileName = "prefill_decode_router.py",
            fullPath = "inference/router/prefill_decode_router.py",
            branch = "opt/kv-prefix-affinity",
            diffStat = "+37  -4",
            baseLineNumber = 64,
            toolSteps = listOf(
                "grep_search(\"select_prefill_worker\") -> 5 处命中",
                "read_file(\"prefill_decode_router.py:64-118\")",
                "apply_patch(prefill_decode_router.py +37 -4)",
                "pytest tests/router/test_kv_affinity.py -> 22/22 PASS",
            ),
            diffLines = listOf(
                ' ' to "class PrefillDecodeRouter:",
                ' ' to "    def __init__(self, workers: list[WorkerState], sla_ttft_ms: float = 900.0):",
                ' ' to "        self.workers = workers",
                ' ' to "        self.sla_ttft_ms = sla_ttft_ms",
                '+' to "        self.prefix_ring = PrefixHashRing(replicas=128)",
                '+' to "        self.shed_counter = 0",
                ' ' to "",
                ' ' to "    def route_request(self, req: InferenceRequest) -> RoutingDecision:",
                '-' to "        worker = min(self.workers, key=lambda w: w.active_reqs)",
                '-' to "        return RoutingDecision(worker_id=worker.id, degraded=False)",
                '+' to "        prefix_key = req.system_prompt_hash[:16]",
                '+' to "        candidates = self.prefix_ring.top_candidates(prefix_key, k=3)",
                '+' to "        scored = []",
                '+' to "        for worker in candidates:",
                '+' to "            if not worker.healthy or worker.kv_util_ratio > 0.92:",
                '+' to "                continue",
                '+' to "            est_ttft = worker.queue_prefill_ms + req.prompt_tokens * worker.ms_per_tok",
                '+' to "            cache_bonus = 0.62 if worker.has_prefix(prefix_key) else 1.0",
                '+' to "            scored.append((est_ttft * cache_bonus, worker))",
                '+' to "        if not scored:",
                '+' to "            return self._fallback_least_loaded(req)",
                '+' to "        est_ms, chosen = min(scored, key=lambda item: item[0])",
                '+' to "        if est_ms > self.sla_ttft_ms * 1.35 and req.allow_fast_tier:",
                '+' to "            self.shed_counter += 1",
                '+' to "            return RoutingDecision(",
                '+' to "                worker_id=self.fast_pool.next_healthy().id,",
                '+' to "                degraded=True,",
                '+' to "                reason=\"ttft_sla_guard_shed\",",
                '+' to "            )",
                '+' to "        return RoutingDecision(",
                '+' to "            worker_id=chosen.id,",
                '+' to "            degraded=False,",
                '+' to "            kv_prefix_hit=chosen.has_prefix(prefix_key),",
                '+' to "            predicted_ttft_ms=round(est_ms, 1),",
                '+' to "        )",
                ' ' to "",
                '+' to "    def _fallback_least_loaded(self, req: InferenceRequest) -> RoutingDecision:",
                '+' to "        healthy = [w for w in self.workers if w.healthy]",
                '+' to "        if not healthy:",
                '+' to "            raise ClusterOverloadedError(\"no_healthy_prefill_workers\")",
                '+' to "        best = min(healthy, key=lambda w: (w.kv_util_ratio, w.queue_prefill_ms))",
                '+' to "        return RoutingDecision(worker_id=best.id, degraded=True, reason=\"kv_pressure\")",
                ' ' to "",
                '+' to "    def record_completion_feedback(self, worker_id: str, actual_ttft_ms: float) -> None:",
                '+' to "        worker = self._by_id.get(worker_id)",
                '+' to "        if worker is None:",
                '+' to "            return",
                '+' to "        worker.ewma_ttft_ms = 0.8 * worker.ewma_ttft_ms + 0.2 * actual_ttft_ms",
                '+' to "        if actual_ttft_ms > self.sla_ttft_ms * 1.5:",
                '+' to "            worker.mark_soft_pressure(ttl_sec=15.0)",
            ),
        ),
    )

    val multimodalVisionCases: List<MultimodalVisionCase> = listOf(
        MultimodalVisionCase(
            id = "vision_dc_rack",
            headerTitle = "机房光路 AI 视觉巡检 · 多模态工作台",
            reportTitle = "AI Ops 现场光路视觉巡检报告",
            fileName = "DC_B04_U28_FIBER_4K.jpg",
            resolutionTag = "4032×2268",
            detectionSummary = "视觉检测 3 处目标 · 置信 98.4%",
            imageRes = R.drawable.multimodal_dc_rack,
            boxes = listOf(
                VisionBox(0.44f, 0.43f, 0.25f, 0.24f, "excellent"),
                VisionBox(0.60f, 0.44f, 0.11f, 0.22f, "fair"),
                VisionBox(0.43f, 0.65f, 0.38f, 0.24f, "brand"),
            ),
            analysisBody = "1. 端口与光模块状态识别（深圳南山机房 B04-U28 / U29）\n" +
                "• 左侧 16 路 SFP28 光模块链路指示灯呈稳定绿色常亮，收发光功率处于正常门限区间，核心业务无链路闪断迹象。\n" +
                "• 右侧管理与电源状态面板检出 2 枚琥珀色告警灯（U28/U29 右端），建议立即联动 BMC 核查冗余电源模块 PSU-2 供电日志与风扇转速曲线。\n\n" +
                "2. 光纤跳线与物理层损耗评估\n" +
                "• 黄色 OS2 单模尾纤与青色 OM4 多模跳线已通过魔术贴束带分区导入右侧理线架，整体标识清晰、走线规范。\n" +
                "• 下层交换机前部青色多模束弯曲弧度接近 32mm 最小弯曲半径，长期受力易产生 0.4–0.8 dB 微弯附加衰耗，建议下个变更窗加装 1U 前置水平理线槽。\n\n" +
                "3. 自动工单与下一步核查建议\n" +
                "• 工单编号：INC-20261121-0418 · 优先级 P2（预防性巡检）\n" +
                "• 建议执行 `show interfaces transceiver detail` 采集 DOM 光衰基线并归档至月度巡检台账。",
        ),
        MultimodalVisionCase(
            id = "vision_sz_drone",
            headerTitle = "无人机航拍 AI 解译 · 多模态工作台",
            reportTitle = "4K 无人机航拍多模态视觉解译报告",
            fileName = "SZBAY_MAVIC3_4K_0914.jpg",
            resolutionTag = "3840×2160",
            detectionSummary = "检出 3 类空间要素 · 置信 99.1%",
            imageRes = R.drawable.multimodal_city_drone,
            boxes = listOf(
                VisionBox(0.25f, 0.08f, 0.24f, 0.42f, "brand"),
                VisionBox(0.56f, 0.40f, 0.40f, 0.54f, "excellent"),
                VisionBox(0.03f, 0.50f, 0.34f, 0.44f, "fair"),
            ),
            analysisBody = "1. 航拍空间要素与交通态势识别（深圳湾 / 后海核心区）\n" +
                "• 右下跨海斜拉桥主通道：双向多车道车流呈中低密度顺畅通行状态，桥面无事故占道与缓行排队，双主塔结构轮廓清晰。\n" +
                "• 左中超高层商务集群：高密度玻璃幕墙塔楼群沿海岸线阶梯分布，黄昏低角度逆光在玻璃幕墙与海面形成高反光带，对视觉避障相机存在轻度眩光干扰。\n" +
                "• 左侧滨海绿廊与快速路：双向快速路与海滨慢行步道隔离完整，沿线绿化带连续无异常裸土或违规施工占用。\n\n" +
                "2. 5G-A 低空通感与图传覆盖评估\n" +
                "• 跨海桥面与开阔水域具备极佳视距传播条件（LoS），但水面镜面反射易造成多径相位抵消，建议开启波束赋形与跨扇区平滑切换保护。\n" +
                "• 超高层建筑群背风侧存在 15–22 dB 阴影衰落，建议巡检航线保持距建筑立面 >= 120 米以稳定维持 4K 实时上行回传。",
        ),
        MultimodalVisionCase(
            id = "vision_pcb_aoi",
            headerTitle = "工业产线 AOI 光学质检 · 多模态工作台",
            reportTitle = "5G 基带主板 AOI 视觉缺陷复判报告",
            fileName = "PCB_AOI_LINE07_4K.jpg",
            resolutionTag = "4096×2304",
            detectionSummary = "复判 3 处关键器件区 · 良率 99.6%",
            imageRes = R.drawable.multimodal_pcb_aoi,
            boxes = listOf(
                VisionBox(0.39f, 0.38f, 0.22f, 0.24f, "excellent"),
                VisionBox(0.41f, 0.72f, 0.24f, 0.22f, "fair"),
                VisionBox(0.50f, 0.25f, 0.29f, 0.26f, "brand"),
            ),
            analysisBody = "1. 核心芯片与焊盘阵列光学检视（Line-07 产线工位）\n" +
                "• 中央 BGA 主控封装共面性良好，四周高密度植球阵列表面光泽均匀，未检出桥连短路或虚焊冷焊特征。\n" +
                "• 右上方 4 颗高速存储封装丝印方向一致、本体无崩边划伤，周边高速差分金线等长蛇形绕线轮廓完整无断线。\n\n" +
                "2. 异常复判与工艺预警（需人工二次抽检）\n" +
                "• 底部左侧 2 组预留 DRAM 焊盘区（空贴位）助焊剂残留略偏高，存在高温高湿环境下枝晶迁移隐患，建议检查清洗机第 3 槽喷淋压力。\n" +
                "• 主控左下侧 0402 滤波电容阵列存在 1 颗轻微偏位（约 0.08mm，仍在 IPC-A-610 Class 2 允许公差内），已标记入 SPC 趋势监控。\n\n" +
                "3. 质检闭环结论\n" +
                "• 整板判定：PASS（建议关注回流焊炉温曲线 Zone-4），批次追踪码已自动同步至 MES 质量追溯库。",
        ),
    )

    val multimodalDocCases: List<MultimodalDocCase> = listOf(
        MultimodalDocCase(
            id = "doc_sla_5g",
            fileName = "2026_Q3_5G_AI_Network_SLA_Report.pdf",
            metaSummary = "48 页 · 含 12 张 SLA 表格与 3 组核心网时延附件",
            indexedSummary = "全文切片 184 段落 · 关键条款向量索引完成",
            reportTitle = "48 页 SLA 协议风险审计结论",
            analysisBody = "一、协议条款与实测偏差审计（检出 3 处高优风险）\n" +
                "1. 条款 §4.2.1（AI 对话首字门限）：合同约定峰值期 TTFT P95 <= 1,200ms，但华南晚高峰实测落在 1,480–1,720ms 区间，存在月度服务费 5% 违约扣减风险。\n" +
                "2. 条款 §6.3（多模态上行带宽保障）：5MB 图片上行门限未区分 5G 室内弱覆盖场景，建议补充「RSRP < -105 dBm 豁免条款」或接入边缘 POP 就近汇聚。\n" +
                "3. 条款 §8.1（实时语音口到耳红线）：已明确 400ms 否决线与 M7 长静默门限，与 ANEB Profile 4 实测口径 100% 对齐，可直接作为第三方验收依据。\n\n" +
                "二、商务谈判修改建议\n" +
                "• 建议在附录 C 增加「跨运营商互联拥塞责任划分矩阵」，以月度抽检原始 JSON 证据包作为争议仲裁唯一基准。",
        ),
        MultimodalDocCase(
            id = "doc_semicon_supply",
            fileName = "2026_Edge_AI_SoC_Supply_Audit.pdf",
            metaSummary = "62 页 · 含封装交期表、BOM 成本拆解与替代清单",
            indexedSummary = "全文切片 246 段落 · 跨表 BOM 依赖图谱已构建",
            reportTitle = "62 页边缘 AI 芯片供应链风险摘要",
            analysisBody = "一、关键器件交期与单一来源风险\n" +
                "1. CoWoS 先进封装载板交期由 14 周拉长至 21 周，Q4 批量交付存在 18% 产能缺口，需提前锁定二供基板配额。\n" +
                "2. 高速 LPDDR5X 颗粒现货价环比上浮 11.4%，建议对 16GB/32GB 两种内存规格实施框架协议锁价，减少毛利波动。\n" +
                "3. PMIC 电源管理芯片已通过国产替代 AEC-Q100 验证，切换后单板 BOM 成本下降 4.8%，可在本季度工程试产导入。\n\n" +
                "二、采购执行建议\n" +
                "• 立即针对长交期光模块与封装基板下达 6 个月滚动预测单，并建立每周安全库存预警线。",
        ),
        MultimodalDocCase(
            id = "doc_fintech_arch",
            fileName = "FinTech_MultiActive_DR_Spec_v4.pdf",
            metaSummary = "54 页 · 含两地三中心拓扑、RPO/RTO 指标与压测清单",
            indexedSummary = "全文切片 212 段落 · 合规控制点已映射至审计表",
            reportTitle = "54 页两地三中心容灾架构审查结论",
            analysisBody = "一、核心容灾指标合规审查\n" +
                "1. 账务核心集群采用同城双活 + 异地异步日志复制，同城故障切换 RTO 实测 18 秒、RPO = 0，满足监管 A 类系统要求。\n" +
                "2. AI 风控推理服务尚未纳入全链路灰度切流演练，若异地专线抖动超过 120ms 可能触发风控超时放行，需补充本地规则引擎降级路径。\n" +
                "3. 证书轮换与密钥托管流程存在 2 处手工审批断点，建议接入自动化 KMS 流水线并在 30 天内完成全链路无感演练。",
        ),
    )

    val multimodalTextCases: List<MultimodalTextCase> = listOf(
        MultimodalTextCase(
            id = "mm_txt_quic",
            roleTitle = "企业网络架构师提问",
            prompt = "对比 QUIC/HTTP3 与 HTTP/2 在 5% 随机丢包 + 150ms 跨境时延下对大模型 SSE 首字 TTFT 与长回答卡顿的影响，并给出客户端自动降级方案。",
            reportTitle = "传输层架构对比与客户端降级建议",
            answerBody = "一、5% 丢包 + 150ms 时延下的传输层实测差异\n" +
                "1. HTTP/2 over TCP：单条 TCP 连接在 5% 随机丢包下触发队头阻塞（HOL Blocking），单个丢包即可拖住后续 6–12 个 SSE Token 达 300–450ms，呈现明显「锯齿状批量吐字」。\n" +
                "2. QUIC / HTTP3：在弱网下可缩短 1-RTT 建连并加速单包恢复，但若运营商对 UDP 实施限速或中间盒丢弃大 MTU 报文，仍会出现首字超时与连接迁移动荡。\n\n" +
                "二、生产级客户端自动降级策略\n" +
                "• 采用 Happy Eyeballs v3 并行竞速（QUIC 优先，250ms 未完成握手自动回退 H2）；\n" +
                "• 开启流式心跳看门狗：单次 SSE 间隔 > 2.5s 触发无感重连并携带 last_event_id 续传，确保长回答不断流。",
        ),
        MultimodalTextCase(
            id = "mm_txt_voice_edge",
            roleTitle = "实时语音架构师提问",
            prompt = "为什么家庭千兆 Wi-Fi 测速跑满 300 Mbps，豆包实时语音却依然频繁抢话卡顿？从空口调度、Bufferbloat 与 20ms 帧抖动三个层面拆解。",
            reportTitle = "高带宽语音卡顿根因与优化拆解",
            answerBody = "一、为什么「高带宽」不等于「好语音」\n" +
                "1. 上行小包调度延迟：实时语音每 20ms 发送 160 字节小帧，极度依赖确定性到达间隔而非峰值吞吐；当同网设备上传云盘或刷高清视频时，家用路由器深队列会把小帧拖延 150–400ms。\n" +
                "2. 2.4GHz/5GHz 空口退避冲突：相邻信道干扰引发 Wi-Fi MAC 层重传，造成连续 3–5 帧成批到达后紧跟 120ms 长静默（M7 指标飙升），口到耳时延迅速突破 400ms 交互红线。\n\n" +
                "二、工程排查与优化建议\n" +
                "• 实测语音前优先运行 ANEB Profile 4 与「高负载并发」测试，对比 Wi-Fi 与 5G 蜂窝的 M1'/M7 实测差值；\n" +
                "• 在路由器开启 SQM / 智能流控并对 UDP 语音流标记 DSCP EF 优先队列。",
        ),
        MultimodalTextCase(
            id = "mm_txt_edge_cache",
            roleTitle = "AI 平台负责人提问",
            prompt = "针对 5MB 高清图片多模态问答业务，在移动弱网（上行 3–8 Mbps）下如何把端到端首字等待从 9 秒压降到 3 秒以内？",
            reportTitle = "移动弱网多模态首字加速架构",
            answerBody = "一、端到端耗时拆解（9 秒瓶颈在哪）\n" +
                "在 5 Mbps 上行弱网下，未压缩的 5 MB 原图仅串行传输就耗时 8.0 秒，叠加视觉编码器预处理与首字推理，用户体感接近 9.5 秒，远超交互耐心阈值。\n\n" +
                "二、三段并行加速方案（实测压降至 2.6 秒内）\n" +
                "1. 端侧渐进式分片 + 智能长边自适应缩放：优先发送 180 KB 预览图触发模型意图理解与文本先导流，后台并行补传高清 ROI 区域。\n" +
                "2. 就近边缘 POP 接入与 TLS 0-RTT 连接预热：消除跨境 TCP 慢启动，将首个 1 MB 上行耗时压缩 38%。\n" +
                "3. 多模态流式重叠流水线：图像分片上传达 80% 即提前加载 Prompt KV Prefix，上传收尾瞬间无缝触发首字吐出。",
        ),
    )
}
