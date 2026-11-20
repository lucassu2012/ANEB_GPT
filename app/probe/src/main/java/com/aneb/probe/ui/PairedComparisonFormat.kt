package com.aneb.probe.ui

import com.aneb.probe.data.BasicSpeedResultEntity
import com.aneb.probe.data.RadioSampleEntity
import com.aneb.probe.data.ScenarioResultEntity
import com.aneb.probe.data.TestRun
import com.aneb.probe.data.VoiceResultEntity
import com.aneb.probe.engine.LoadedResponsiveness
import com.aneb.probe.engine.LoadedResponsivenessReport
import com.aneb.probe.engine.NetworkSpeedEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 历史记录配对对比（F13：Wi-Fi vs 5G NR / 任意同口径双次测试对比）纯展示与差值计算层。
 *
 * 设计红线：
 * - 纯 JVM（零 `android.*` / `androidx.compose.*` 依赖），可直接跑 JUnit4 单测；
 * - 绝不重算 KPI / AQS（直接复用 [ResultFormat.runKpiRows]、[LoadedResponsiveness.summarize]、
 *   [VoiceFormat.scoreEntity] 与 [VoiceFormat.primaryMouthEar]）；
 * - 严格遵守 R-10：任何 null 或非有限值统一显示 `"—"`，差值记 unavailable，绝不以 0 顶替；
 * - 严格遵守 R-15：5G NR 标注仅在 `nrState == "connected"` 或 `networkType == "NR" && rat == "NR"`
 *   时成立，绝不使用 display overrideType 伪造 5G；
 * - 所有数值与时间格式化固定 [Locale.ROOT]，防逗号小数区域污染表格与 Markdown。
 */
object PairedComparisonFormat {

    enum class TransportKind {
        WIFI,
        CELLULAR,
        OTHER,
        UNKNOWN,
    }

    enum class ComparisonCategory(val label: String) {
        TOKEN("AI 体验"),
        BASIC_SPEED("基础测速"),
        LOADED_RESPONSIVENESS("负载响应性"),
        VOICE("实时语音"),
    }

    enum class MetricDirection {
        HIGHER_IS_BETTER,
        LOWER_IS_BETTER,
        NEUTRAL,
    }

    enum class DeltaTone {
        IMPROVED,
        REGRESSED,
        NEUTRAL,
        UNAVAILABLE,
    }

    sealed interface ComparableRunInput {
        val entryKey: String
        val startedAtEpochMs: Long
        val category: ComparisonCategory

        data class Token(
            val run: TestRun,
            val scenarios: List<ScenarioResultEntity>,
            val radioSamples: List<RadioSampleEntity> = emptyList(),
        ) : ComparableRunInput {
            override val entryKey: String = "token:${run.runId}"
            override val startedAtEpochMs: Long = run.startedAtEpochMs
            override val category: ComparisonCategory = ComparisonCategory.TOKEN
        }

        data class Basic(
            val entity: BasicSpeedResultEntity,
            val responsivenessReport: LoadedResponsivenessReport? = null,
        ) : ComparableRunInput {
            override val entryKey: String = "basic:${entity.runId}"
            override val startedAtEpochMs: Long = entity.startedAtEpochMs
            override val category: ComparisonCategory =
                if (entity.profileId == NetworkSpeedEngine.RESPONSIVENESS_PROFILE_ID) {
                    ComparisonCategory.LOADED_RESPONSIVENESS
                } else {
                    ComparisonCategory.BASIC_SPEED
                }
        }

        data class Voice(
            val entity: VoiceResultEntity,
        ) : ComparableRunInput {
            override val entryKey: String = "voice:${entity.id}"
            override val startedAtEpochMs: Long = entity.tsEpochMs
            override val category: ComparisonCategory = ComparisonCategory.VOICE
        }
    }

    data class ComparisonRunSummary(
        val entryKey: String,
        val badge: String,
        val category: ComparisonCategory,
        val transportKind: TransportKind,
        val transportLabel: String,
        val nodeLabel: String,
        val startedAtEpochMs: Long,
        val statusLabel: String,
        val lowConfidence: Boolean,
        val aqsScore: Double?,
    )

    data class ComparisonKpiRow(
        val id: String,
        val label: String,
        val unit: String,
        val direction: MetricDirection,
        val valueA: Double?,
        val valueB: Double?,
        val formattedA: String,
        val formattedB: String,
        val absDelta: Double?,
        val pctDelta: Double?,
        val formattedDelta: String,
        val tone: DeltaTone,
        val lowConfidenceA: Boolean = false,
        val lowConfidenceB: Boolean = false,
    )

    sealed interface PairedComparisonResult {
        data class Compatible(
            val category: ComparisonCategory,
            val left: ComparisonRunSummary,
            val right: ComparisonRunSummary,
            val headline: String,
            val rows: List<ComparisonKpiRow>,
            val disclaimers: List<String>,
        ) : PairedComparisonResult

        data class Mismatch(
            val leftLabel: String,
            val rightLabel: String,
            val reason: String,
        ) : PairedComparisonResult
    }

    data class HistoryComparisonState(
        val compareMode: Boolean = false,
        val selectedKeys: List<String> = emptyList(),
        val swapped: Boolean = false,
        val notice: String? = null,
    ) {
        fun toggleCompareMode(): HistoryComparisonState =
            if (compareMode) {
                HistoryComparisonState()
            } else {
                copy(compareMode = true, notice = null)
            }

        fun quickPairWifiVs5g(inputs: List<ComparableRunInput>): HistoryComparisonState {
            val pair = findQuickWifiVsCellularPair(inputs)
            return if (pair != null) {
                HistoryComparisonState(
                    compareMode = true,
                    selectedKeys = listOf(pair.first, pair.second),
                    swapped = false,
                    notice = null,
                )
            } else {
                copy(
                    compareMode = true,
                    notice = "暂无同类型 Wi-Fi 与蜂窝记录，请手动点选 2 条",
                )
            }
        }

        fun toggleSelection(key: String): HistoryComparisonState {
            val next = when {
                key in selectedKeys -> selectedKeys.filterNot { it == key }
                selectedKeys.size < 2 -> selectedKeys + key
                else -> listOf(selectedKeys[0], key)
            }
            return copy(selectedKeys = next, swapped = false, notice = null)
        }

        fun swap(): HistoryComparisonState = copy(swapped = !swapped, notice = null)

        fun clearSelection(): HistoryComparisonState =
            copy(selectedKeys = emptyList(), swapped = false, notice = null)

        fun resolveComparison(inputsByKey: Map<String, ComparableRunInput>): PairedComparisonResult? {
            if (selectedKeys.size != 2) return null
            val first = inputsByKey[selectedKeys[0]] ?: return null
            val second = inputsByKey[selectedKeys[1]] ?: return null
            val (baseLeft, baseRight) = canonicalPairOrder(first, second)
            return if (swapped) {
                compare(baseRight, baseLeft, preserveOrder = true)
            } else {
                compare(baseLeft, baseRight, preserveOrder = true)
            }
        }
    }

    fun resolveTransport(input: ComparableRunInput): Pair<TransportKind, String> = when (input) {
        is ComparableRunInput.Token -> resolveTokenTransport(input.run, input.scenarios, input.radioSamples)
        is ComparableRunInput.Basic -> resolveBasicTransport(input.responsivenessReport)
        is ComparableRunInput.Voice -> TransportKind.UNKNOWN to "未记录网络"
    }

    fun resolveCellularLabel(radioSamples: List<RadioSampleEntity>): String {
        val nrConnected = radioSamples.any { sample ->
            sample.nrState.equals("connected", ignoreCase = true) ||
                (sample.networkType.equals("NR", ignoreCase = true) && sample.rat.equals("NR", ignoreCase = true))
        }
        if (nrConnected) return "5G NR"
        val lteOnly = radioSamples.any { it.nrState.equals("none", ignoreCase = true) }
        return if (lteOnly) "4G LTE" else "蜂窝网络"
    }

    private const val MIXED_TRANSPORT_LABEL: String = "混合网络"

    private fun normalizeTransportKind(raw: String): TransportKind =
        when (raw.trim().lowercase(Locale.ROOT)) {
            "wifi", "auto(wifi)" -> TransportKind.WIFI
            "cellular", "auto(cellular)" -> TransportKind.CELLULAR
            "ethernet", "auto(ethernet)" -> TransportKind.OTHER
            else -> TransportKind.UNKNOWN
        }

    private fun resolveTokenTransport(
        run: TestRun,
        scenarios: List<ScenarioResultEntity>,
        radioSamples: List<RadioSampleEntity>,
    ): Pair<TransportKind, String> {
        val observedKinds = scenarios
            .mapNotNull { s -> s.netTransport?.trim()?.takeIf { it.isNotBlank() } }
            .map(::normalizeTransportKind)
            .distinct()
        if (observedKinds.size > 1) {
            return TransportKind.OTHER to MIXED_TRANSPORT_LABEL
        }
        val singleKind = observedKinds.singleOrNull() ?: normalizeTransportKind(run.transport)
        return when (singleKind) {
            TransportKind.WIFI -> TransportKind.WIFI to "Wi-Fi"
            TransportKind.CELLULAR -> TransportKind.CELLULAR to resolveCellularLabel(radioSamples)
            TransportKind.OTHER -> TransportKind.OTHER to "以太网"
            TransportKind.UNKNOWN -> TransportKind.UNKNOWN to "未知网络"
        }
    }

    private fun resolveBasicTransport(report: LoadedResponsivenessReport?): Pair<TransportKind, String> {
        if (report == null) return TransportKind.UNKNOWN to "未记录网络"
        return when (report.transport.trim().lowercase(Locale.ROOT)) {
            "wifi", "auto(wifi)" -> TransportKind.WIFI to "Wi-Fi"
            "cellular", "auto(cellular)" -> TransportKind.CELLULAR to "蜂窝网络"
            "ethernet", "auto(ethernet)" -> TransportKind.OTHER to "以太网"
            else -> TransportKind.UNKNOWN to "未知网络"
        }
    }

    fun findQuickWifiVsCellularPair(entries: List<ComparableRunInput>): Pair<String, String>? {
        val ordered = entries.sortedByDescending { it.startedAtEpochMs }
        val categoryPriority = listOf(
            ComparisonCategory.TOKEN,
            ComparisonCategory.LOADED_RESPONSIVENESS,
        )
        for (cat in categoryPriority) {
            val inCat = ordered.filter { it.category == cat }
            val wifi = inCat.firstOrNull { resolveTransport(it).first == TransportKind.WIFI }
            val cellular = inCat.firstOrNull { resolveTransport(it).first == TransportKind.CELLULAR }
            if (wifi != null && cellular != null) {
                return wifi.entryKey to cellular.entryKey
            }
        }
        return null
    }

    private fun canonicalPairOrder(
        first: ComparableRunInput,
        second: ComparableRunInput,
    ): Pair<ComparableRunInput, ComparableRunInput> {
        val t1 = resolveTransport(first).first
        val t2 = resolveTransport(second).first
        return if (t1 == TransportKind.CELLULAR && t2 == TransportKind.WIFI) {
            second to first
        } else {
            first to second
        }
    }

    fun compare(
        first: ComparableRunInput,
        second: ComparableRunInput,
        preserveOrder: Boolean = false,
    ): PairedComparisonResult {
        if (first.category != second.category) {
            val t1 = resolveTransport(first).second
            val t2 = resolveTransport(second).second
            return PairedComparisonResult.Mismatch(
                leftLabel = "${first.category.label}（$t1）",
                rightLabel = "${second.category.label}（$t2）",
                reason = "${first.category.label} 与 ${second.category.label} 测量口径不同，不可直接对比",
            )
        }
        if (first is ComparableRunInput.Voice && second is ComparableRunInput.Voice) {
            val caliberA = VoiceFormat.caliberLabel(first.entity.caliber)
            val caliberB = VoiceFormat.caliberLabel(second.entity.caliber)
            if (caliberA != caliberB) {
                return PairedComparisonResult.Mismatch(
                    leftLabel = "${first.category.label}（$caliberA）",
                    rightLabel = "${second.category.label}（$caliberB）",
                    reason = "实时语音 $caliberA 与 $caliberB 测量口径不同，不可直接对比",
                )
            }
        }
        val (leftInput, rightInput) = if (preserveOrder) {
            first to second
        } else {
            canonicalPairOrder(first, second)
        }
        val leftSummary = buildSummary(leftInput, badge = "A")
        val rightSummary = buildSummary(rightInput, badge = "B")
        val rows = when (leftInput) {
            is ComparableRunInput.Token -> buildTokenRows(leftInput, rightInput as ComparableRunInput.Token)
            is ComparableRunInput.Basic -> {
                val r = rightInput as ComparableRunInput.Basic
                if (leftInput.category == ComparisonCategory.LOADED_RESPONSIVENESS) {
                    buildResponsivenessRows(leftInput, r)
                } else {
                    buildBasicSpeedRows(leftInput, r)
                }
            }
            is ComparableRunInput.Voice -> buildVoiceRows(leftInput, rightInput as ComparableRunInput.Voice)
        }
        val headline = buildHeadline(leftSummary, rightSummary, rows)
        val disclaimers = buildDisclaimers(leftSummary, rightSummary)
        return PairedComparisonResult.Compatible(
            category = leftSummary.category,
            left = leftSummary,
            right = rightSummary,
            headline = headline,
            rows = rows,
            disclaimers = disclaimers,
        )
    }

    private fun buildSummary(input: ComparableRunInput, badge: String): ComparisonRunSummary {
        val (kind, transportLabel) = resolveTransport(input)
        return when (input) {
            is ComparableRunInput.Token -> {
                val grade = input.run.aqsScore?.takeIf { it.isFinite() }?.let {
                    ResultFormat.gradeLabel(ResultFormat.aqsGrade(it))
                } ?: "未评分"
                ComparisonRunSummary(
                    entryKey = input.entryKey,
                    badge = badge,
                    category = input.category,
                    transportKind = kind,
                    transportLabel = transportLabel,
                    nodeLabel = ProbeNodeCatalog.labelForUrl(input.run.serverBase),
                    startedAtEpochMs = input.startedAtEpochMs,
                    statusLabel = "${input.run.mode} · $grade",
                    lowConfidence = input.run.aqsLowConfidence == true,
                    aqsScore = input.run.aqsScore?.takeIf { it.isFinite() },
                )
            }
            is ComparableRunInput.Basic -> {
                ComparisonRunSummary(
                    entryKey = input.entryKey,
                    badge = badge,
                    category = input.category,
                    transportKind = kind,
                    transportLabel = transportLabel,
                    nodeLabel = ProbeNodeCatalog.labelForUrl(input.entity.serverBase),
                    startedAtEpochMs = input.startedAtEpochMs,
                    statusLabel = input.entity.status,
                    lowConfidence = input.entity.status != "completed",
                    aqsScore = null,
                )
            }
            is ComparableRunInput.Voice -> {
                val scored = VoiceFormat.scoreEntity(input.entity)
                ComparisonRunSummary(
                    entryKey = input.entryKey,
                    badge = badge,
                    category = input.category,
                    transportKind = kind,
                    transportLabel = transportLabel,
                    nodeLabel = "未记录节点",
                    startedAtEpochMs = input.startedAtEpochMs,
                    statusLabel = VoiceFormat.caliberLabel(input.entity.caliber),
                    lowConfidence = input.entity.lowConfidence,
                    aqsScore = scored.score?.takeIf { it.isFinite() },
                )
            }
        }
    }

    private fun buildTokenRows(
        left: ComparableRunInput.Token,
        right: ComparableRunInput.Token,
    ): List<ComparisonKpiRow> {
        val leftMap = ResultFormat.runKpiRows(left.scenarios).associateBy { it.row.id }
        val rightMap = ResultFormat.runKpiRows(right.scenarios).associateBy { it.row.id }
        val rows = mutableListOf(
            computeRow(
                id = "AQS",
                label = "AQS 体验分",
                unit = "分",
                direction = MetricDirection.HIGHER_IS_BETTER,
                decimals = 1,
                valueA = left.run.aqsScore,
                valueB = right.run.aqsScore,
                lowConfA = left.run.aqsLowConfidence == true,
                lowConfB = right.run.aqsLowConfidence == true,
            ),
            kpiRowFromMap("T1", "TTFT 首字延迟", "ms", MetricDirection.LOWER_IS_BETTER, 1, leftMap, rightMap),
            kpiRowFromMap("T2", "ITL P95 (TPOT)", "ms", MetricDirection.LOWER_IS_BETTER, 1, leftMap, rightMap),
            kpiRowFromMap("T3", "卡顿率", "ratio", MetricDirection.LOWER_IS_BETTER, 2, leftMap, rightMap),
            kpiRowFromMap("N1", "RTT P50", "ms", MetricDirection.LOWER_IS_BETTER, 1, leftMap, rightMap),
            kpiRowFromMap("N2", "抖动", "ms", MetricDirection.LOWER_IS_BETTER, 1, leftMap, rightMap),
            kpiRowFromMap("U1", "上行吞吐", "Mbps", MetricDirection.HIGHER_IS_BETTER, 2, leftMap, rightMap),
            kpiRowFromMap("U2", "工具循环 P95 (E2E)", "ms", MetricDirection.LOWER_IS_BETTER, 1, leftMap, rightMap),
        )
        if (left.run.aqsV02Score != null || right.run.aqsV02Score != null) {
            rows += computeRow(
                id = "AQS_V02",
                label = "AQS v0.2",
                unit = "分",
                direction = MetricDirection.HIGHER_IS_BETTER,
                decimals = 1,
                valueA = left.run.aqsV02Score,
                valueB = right.run.aqsV02Score,
                lowConfA = left.run.aqsV02LowConfidence == true,
                lowConfB = right.run.aqsV02LowConfidence == true,
            )
        }
        return rows
    }

    private fun kpiRowFromMap(
        id: String,
        label: String,
        unit: String,
        direction: MetricDirection,
        decimals: Int,
        leftMap: Map<String, ResultFormat.RunKpiRow>,
        rightMap: Map<String, ResultFormat.RunKpiRow>,
    ): ComparisonKpiRow {
        val l = leftMap[id]?.row
        val r = rightMap[id]?.row
        return computeRow(
            id = id,
            label = label,
            unit = unit,
            direction = direction,
            decimals = decimals,
            valueA = l?.value,
            valueB = r?.value,
            lowConfA = l?.lowConfidence == true,
            lowConfB = r?.lowConfidence == true,
        )
    }

    private fun buildBasicSpeedRows(
        left: ComparableRunInput.Basic,
        right: ComparableRunInput.Basic,
    ): List<ComparisonKpiRow> {
        val a = left.entity
        val b = right.entity
        return listOf(
            computeRow("DL", "下行吞吐", "Mbps", MetricDirection.HIGHER_IS_BETTER, 2, a.downloadMbps, b.downloadMbps),
            computeRow("UL", "上行吞吐", "Mbps", MetricDirection.HIGHER_IS_BETTER, 2, a.uploadMbps, b.uploadMbps),
            computeRow("PING", "空载时延", "ms", MetricDirection.LOWER_IS_BETTER, 1, a.pingMs, b.pingMs),
            computeRow("POST_LOAD_PING", "负载后时延", "ms", MetricDirection.LOWER_IS_BETTER, 1, a.postLoadPingMs, b.postLoadPingMs),
            computeRow("JITTER", "抖动", "ms", MetricDirection.LOWER_IS_BETTER, 1, a.jitterMs, b.jitterMs),
            computeRow("LOSS", "请求失败率", "ratio", MetricDirection.LOWER_IS_BETTER, 2, a.requestLossRate, b.requestLossRate),
        )
    }

    private fun buildResponsivenessRows(
        left: ComparableRunInput.Basic,
        right: ComparableRunInput.Basic,
    ): List<ComparisonKpiRow> {
        val sumA = left.responsivenessReport?.let { LoadedResponsiveness.summarize(it) }
        val sumB = right.responsivenessReport?.let { LoadedResponsiveness.summarize(it) }
        val idleA = sumA?.idleP50Ms ?: left.entity.pingMs
        val idleB = sumB?.idleP50Ms ?: right.entity.pingMs
        return listOf(
            computeRow("IDLE_P50", "空载 RTT P50", "ms", MetricDirection.LOWER_IS_BETTER, 1, idleA, idleB),
            computeRow("DL_P95", "下行负载 P95", "ms", MetricDirection.LOWER_IS_BETTER, 1, sumA?.downloadP95Ms, sumB?.downloadP95Ms),
            computeRow("DL_DELTA", "下行排队增量", "ms", MetricDirection.LOWER_IS_BETTER, 1, sumA?.downloadDeltaMs, sumB?.downloadDeltaMs),
            computeRow("UL_P95", "上行负载 P95", "ms", MetricDirection.LOWER_IS_BETTER, 1, sumA?.uploadP95Ms, sumB?.uploadP95Ms),
            computeRow("UL_DELTA", "上行排队增量", "ms", MetricDirection.LOWER_IS_BETTER, 1, sumA?.uploadDeltaMs, sumB?.uploadDeltaMs),
            computeRow("DL", "下行吞吐", "Mbps", MetricDirection.HIGHER_IS_BETTER, 2, left.entity.downloadMbps, right.entity.downloadMbps),
            computeRow("UL", "上行吞吐", "Mbps", MetricDirection.HIGHER_IS_BETTER, 2, left.entity.uploadMbps, right.entity.uploadMbps),
        )
    }

    private fun buildVoiceRows(
        left: ComparableRunInput.Voice,
        right: ComparableRunInput.Voice,
    ): List<ComparisonKpiRow> {
        val a = left.entity
        val b = right.entity
        val scoreA = VoiceFormat.scoreEntity(a)
        val scoreB = VoiceFormat.scoreEntity(b)
        val mouthEarA = VoiceFormat.primaryMouthEar(a.mouthEarProxyMs, a.mouthEarBudgetMs).first
        val mouthEarB = VoiceFormat.primaryMouthEar(b.mouthEarProxyMs, b.mouthEarBudgetMs).first
        return listOf(
            computeRow(
                id = "VOICE_SCORE",
                label = "语音体验分",
                unit = "分",
                direction = MetricDirection.HIGHER_IS_BETTER,
                decimals = 1,
                valueA = scoreA.score,
                valueB = scoreB.score,
                lowConfA = a.lowConfidence,
                lowConfB = b.lowConfidence,
            ),
            computeRow("MOUTH_EAR", "口到耳时延", "ms", MetricDirection.LOWER_IS_BETTER, 1, mouthEarA, mouthEarB, a.lowConfidence, b.lowConfidence),
            computeRow("RTT", "RTT", "ms", MetricDirection.LOWER_IS_BETTER, 1, a.rttMs, b.rttMs, a.lowConfidence, b.lowConfidence),
            computeRow("JITTER", "抖动", "ms", MetricDirection.LOWER_IS_BETTER, 1, a.jitterMs, b.jitterMs, a.lowConfidence, b.lowConfidence),
            computeRow("TTFB_P50", "TTFB P50", "ms", MetricDirection.LOWER_IS_BETTER, 1, a.ttfbP50Ms, b.ttfbP50Ms, a.lowConfidence, b.lowConfidence),
            computeRow("TURN_SWITCH", "轮次切换 P50", "ms", MetricDirection.LOWER_IS_BETTER, 1, a.turnSwitchP50Ms, b.turnSwitchP50Ms, a.lowConfidence, b.lowConfidence),
            computeRow("M7_GAP", "M7 最大帧间隔", "ms", MetricDirection.LOWER_IS_BETTER, 1, a.m7MaxFrameGapMs, b.m7MaxFrameGapMs, a.lowConfidence, b.lowConfidence),
            computeRow("NEAR_ZERO", "近零到达占比", "ratio", MetricDirection.LOWER_IS_BETTER, 2, a.voiceNearZeroArrivalRatio, b.voiceNearZeroArrivalRatio, a.lowConfidence, b.lowConfidence),
        )
    }

    private fun normalizeZero(v: Double): Double = if (v == 0.0) 0.0 else v

    internal fun computeRow(
        id: String,
        label: String,
        unit: String,
        direction: MetricDirection,
        decimals: Int,
        valueA: Double?,
        valueB: Double?,
        lowConfA: Boolean = false,
        lowConfB: Boolean = false,
    ): ComparisonKpiRow {
        val a = valueA?.takeIf { it.isFinite() }?.let(::normalizeZero)
        val b = valueB?.takeIf { it.isFinite() }?.let(::normalizeZero)
        val formattedA = formatMetricValue(a, unit, decimals)
        val formattedB = formatMetricValue(b, unit, decimals)
        if (a == null || b == null) {
            return ComparisonKpiRow(
                id = id,
                label = label,
                unit = unit,
                direction = direction,
                valueA = a,
                valueB = b,
                formattedA = formattedA,
                formattedB = formattedB,
                absDelta = null,
                pctDelta = null,
                formattedDelta = "—",
                tone = DeltaTone.UNAVAILABLE,
                lowConfidenceA = lowConfA,
                lowConfidenceB = lowConfB,
            )
        }
        val diff = normalizeZero(b - a)
        val tone = when {
            abs(diff) <= 1e-9 -> DeltaTone.NEUTRAL
            direction == MetricDirection.HIGHER_IS_BETTER ->
                if (diff > 0.0) DeltaTone.IMPROVED else DeltaTone.REGRESSED
            direction == MetricDirection.LOWER_IS_BETTER ->
                if (diff < 0.0) DeltaTone.IMPROVED else DeltaTone.REGRESSED
            else -> DeltaTone.NEUTRAL
        }
        val pctDelta = if (abs(a) > 1e-9) normalizeZero((diff / abs(a)) * 100.0) else null
        return if (unit == "ratio") {
            val pp = normalizeZero(diff * 100.0)
            val deltaStr = if (abs(pp) < 0.005) {
                "0.00 pp"
            } else {
                String.format(Locale.ROOT, "%+.2f pp", pp)
            }
            ComparisonKpiRow(
                id = id,
                label = label,
                unit = unit,
                direction = direction,
                valueA = a,
                valueB = b,
                formattedA = formattedA,
                formattedB = formattedB,
                absDelta = pp,
                pctDelta = pctDelta,
                formattedDelta = deltaStr,
                tone = tone,
                lowConfidenceA = lowConfA,
                lowConfidenceB = lowConfB,
            )
        } else {
            val absStr = if (abs(diff) < 1e-9) {
                String.format(Locale.ROOT, "%.${decimals}f %s", 0.0, unit)
            } else {
                String.format(Locale.ROOT, "%+.${decimals}f %s", diff, unit)
            }
            val formattedDelta = if (pctDelta != null) {
                val pctStr = if (abs(pctDelta) < 0.05) {
                    "0.0%"
                } else {
                    String.format(Locale.ROOT, "%+.1f%%", pctDelta)
                }
                "$absStr ($pctStr)"
            } else {
                absStr
            }
            ComparisonKpiRow(
                id = id,
                label = label,
                unit = unit,
                direction = direction,
                valueA = a,
                valueB = b,
                formattedA = formattedA,
                formattedB = formattedB,
                absDelta = diff,
                pctDelta = pctDelta,
                formattedDelta = formattedDelta,
                tone = tone,
                lowConfidenceA = lowConfA,
                lowConfidenceB = lowConfB,
            )
        }
    }

    fun formatMetricValue(value: Double?, unit: String, decimals: Int = 1): String {
        val v = value?.takeIf { it.isFinite() }?.let(::normalizeZero) ?: return "—"
        return if (unit == "ratio") {
            String.format(Locale.ROOT, "%.2f%%", normalizeZero(v * 100.0))
        } else {
            String.format(Locale.ROOT, "%.${decimals}f %s", v, unit)
        }
    }

    private fun buildHeadline(
        left: ComparisonRunSummary,
        right: ComparisonRunSummary,
        rows: List<ComparisonKpiRow>,
    ): String {
        val lead = rows.firstOrNull { it.absDelta != null }
            ?: return "${left.transportLabel} vs ${right.transportLabel}（${left.category.label}）：可对比指标缺失"
        return "${left.transportLabel} vs ${right.transportLabel} · ${lead.label} ${lead.formattedA} → ${lead.formattedB}（${lead.formattedDelta}）"
    }

    private fun buildDisclaimers(
        left: ComparisonRunSummary,
        right: ComparisonRunSummary,
    ): List<String> = buildList {
        if (left.nodeLabel != right.nodeLabel) {
            add("注意：两次测试节点不同（A=${left.nodeLabel}，B=${right.nodeLabel}），差值含路径差异")
        }
        if (left.lowConfidence || right.lowConfidence) {
            add("存在低置信或未完整收敛样本（${ResultFormat.LOW_CONFIDENCE_LABEL}），差值仅供参考")
        }
        if (left.transportLabel == MIXED_TRANSPORT_LABEL || right.transportLabel == MIXED_TRANSPORT_LABEL) {
            add("部分记录内不同场景存在网络切换（混合网络），已禁止按单一 Wi-Fi 或蜂窝口径归因")
        }
        if (left.transportKind == TransportKind.UNKNOWN || right.transportKind == TransportKind.UNKNOWN) {
            add("部分记录未持久化传输制式，已如实标注为未知/未记录网络")
        }
        add(ResultFormat.CLAIM_SCOPE_TEXT)
        if (left.category == ComparisonCategory.TOKEN) {
            add(ResultFormat.AQS_DISCLAIMER_TEXT)
        }
    }

    fun buildMarkdown(result: PairedComparisonResult.Compatible): String = buildString {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        appendLine("# ANEB 配对对比报告（${result.left.transportLabel} vs ${result.right.transportLabel}）")
        appendLine()
        appendLine("- 对比口径: ${result.category.label}")
        appendLine("- 摘要: ${result.headline}")
        appendLine()
        appendLine("## 样本元数据")
        appendLine()
        appendLine("| 维度 | A (${result.left.transportLabel}) | B (${result.right.transportLabel}) |")
        appendLine("| --- | --- | --- |")
        appendLine("| 传输制式 | ${result.left.transportLabel} | ${result.right.transportLabel} |")
        appendLine("| 测试节点 | ${result.left.nodeLabel} | ${result.right.nodeLabel} |")
        appendLine("| 测试时间 | ${fmt.format(Date(result.left.startedAtEpochMs))} | ${fmt.format(Date(result.right.startedAtEpochMs))} |")
        appendLine("| 状态/口径 | ${result.left.statusLabel} | ${result.right.statusLabel} |")
        appendLine("| 低置信 | ${if (result.left.lowConfidence) "是" else "否"} | ${if (result.right.lowConfidence) "是" else "否"} |")
        appendLine()
        appendLine("## 指标差值（B - A）")
        appendLine()
        appendLine("| 指标 | A (${result.left.transportLabel}) | B (${result.right.transportLabel}) | 差值 (B - A) |")
        appendLine("| --- | --- | --- | --- |")
        for (row in result.rows) {
            appendLine("| ${row.label} | ${row.formattedA} | ${row.formattedB} | ${row.formattedDelta} |")
        }
        appendLine()
        appendLine("---")
        appendLine()
        for (note in result.disclaimers) {
            if (note != ResultFormat.CLAIM_SCOPE_TEXT && note != ResultFormat.AQS_DISCLAIMER_TEXT) {
                appendLine("- $note")
            }
        }
        appendLine("_${ResultFormat.CLAIM_SCOPE_TEXT}_")
        if (result.category == ComparisonCategory.TOKEN) {
            appendLine()
            appendLine("_${ResultFormat.AQS_DISCLAIMER_TEXT}_")
        }
    }
}
