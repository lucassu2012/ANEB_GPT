package com.aneb.probe.scoring

import com.aneb.probe.engine.VoiceRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音实时交互出分锚定（PROFILE_FRAMEWORK §4.1，D-31）：WEIGHTS_VOICE Σ=1、
 * scoreVoice 加权/硬否决/缺失语义，及 VoiceRunner 纯函数（帧抖动 P95 / 口到耳预算合成）。
 */
class AqsScorerVoiceTest {

    private fun v(value: Double?, unit: String = "ms", n: Int = 10) =
        KpiValue(value, unit, n, lowConfidence = false)

    @Test
    fun `WEIGHTS_VOICE 权重和为 1`() {
        assertEquals(1.0, AqsScorer.WEIGHTS_VOICE.values.sum(), 1e-9)
    }

    @Test
    fun `全优良输入_出分且子分含 M 组与基线`() {
        val r = AqsScorer.scoreVoice(
            n1RttMs = v(25.0), n2JitterMs = v(3.0),
            m1BudgetMs = v(95.0), m2DownFrameJitterMs = v(5.0), m3UpFrameJitterMs = v(8.0),
        )
        assertTrue("应可计算", r.score != null)
        assertEquals(AqsScorer.AQS_VERSION_VOICE, r.aqsVersion)
        assertEquals(setOf("M1", "M2", "M3", "N1", "N2"), r.subScores.keys)
        assertTrue("全优良应高分", r.score!! > 85.0)
        assertTrue(!r.vetoApplied)
    }

    @Test
    fun `M1 超 400ms 红线_硬否决封顶 54`() {
        val r = AqsScorer.scoreVoice(
            n1RttMs = v(25.0), n2JitterMs = v(3.0),
            m1BudgetMs = v(450.0), m2DownFrameJitterMs = v(5.0), m3UpFrameJitterMs = v(8.0),
        )
        assertTrue("M1>400 应触发硬否决", r.vetoApplied)
        assertTrue("分数应封顶 ≤54", r.score!! <= AqsScorer.T4_VETO_CAP)
    }

    @Test
    fun `在表指标缺失_KPI_MISSING 不以 0 顶替`() {
        val r = AqsScorer.scoreVoice(
            n1RttMs = v(25.0), n2JitterMs = v(3.0),
            m1BudgetMs = v(95.0), m2DownFrameJitterMs = v(null), m3UpFrameJitterMs = v(8.0),
        )
        assertNull("缺 M2 → 分 null（R-10）", r.score)
        assertEquals("KPI_MISSING:M2", r.notComputableReason)
    }

    // ---------- v0.2（M7 最长帧间静默；D-390 §5.6 / 提案 §4）----------

    @Test
    fun `两张 v0_2 语音权重表 Σ=1`() {
        assertEquals(1.0, AqsScorer.WEIGHTS_VOICE_V02.values.sum(), 1e-9)
        assertEquals(1.0, AqsScorer.WEIGHTS_VOICE_SIM_V02.values.sum(), 1e-9)
    }

    @Test
    fun `v0_1 与 v0_2 并列_旧入口不受影响`() {
        // spec/README.md §3「已发布权重表只增不改不删」：v0.1 必须还在，且语义原样——
        // 否则语料里盖着 aqs-voice-v0.1 的历史分数就再也重算不出来了。
        assertEquals(
            mapOf("M1" to 0.30, "M2" to 0.20, "M3" to 0.15, "N1" to 0.15, "N2" to 0.20),
            AqsScorer.WEIGHTS_VOICE,
        )
        val old = AqsScorer.scoreVoice(
            n1RttMs = v(25.0), n2JitterMs = v(3.0),
            m1BudgetMs = v(95.0), m2DownFrameJitterMs = v(5.0), m3UpFrameJitterMs = v(8.0),
        )
        assertEquals(AqsScorer.AQS_VERSION_VOICE, old.aqsVersion)
        assertTrue("v0.1 出分不得含 M7", !old.subScores.containsKey("M7"))
    }

    @Test
    fun `传 M7 即走 v0_2_盖 v0_2 版本戳且子分含 M7`() {
        val r = AqsScorer.scoreVoice(
            n1RttMs = v(25.0), n2JitterMs = v(3.0),
            m1BudgetMs = v(95.0), m2DownFrameJitterMs = v(5.0), m3UpFrameJitterMs = v(8.0),
            m7MaxFrameGapMs = v(30.0),
        )
        assertEquals(AqsScorer.AQS_VERSION_VOICE_V02, r.aqsVersion)
        assertEquals(setOf("M1", "M2", "M3", "M7", "N1", "N2"), r.subScores.keys)
        assertTrue("全优良应高分", r.score!! > 85.0)
    }

    @Test
    fun `M7 测量失败_判 KPI_MISSING 且不得静默降级回 v0_1`() {
        // ⚠ SOLE targeted guard（突变审计 2026-08-02 实测）：静默降级的两种写法——
        // 换表、换版本戳——各自都只被这一条咬住。改动前先放替代品。
        // 这条是本轮最要紧的守卫。诱惑写法是「M7 为 null 就退回 v0.1 出个分」——
        // 那样的分会盖 v0.1 的戳，而这一轮实际按 v0.2 口径跑：版本戳替一个它没算过的口径背书。
        // 丢一个分可恢复，一个说谎的版本戳不可恢复。
        val r = AqsScorer.scoreVoice(
            n1RttMs = v(25.0), n2JitterMs = v(3.0),
            m1BudgetMs = v(95.0), m2DownFrameJitterMs = v(5.0), m3UpFrameJitterMs = v(8.0),
            m7MaxFrameGapMs = v(null),
        )
        assertNull("缺 M7 → 分 null（R-10）", r.score)
        assertEquals("KPI_MISSING:M7", r.notComputableReason)
        assertEquals("版本戳必须仍是 v0.2——降级即说谎", AqsScorer.AQS_VERSION_VOICE_V02, r.aqsVersion)
    }

    @Test
    fun `sim v0_2 同构_六项加 M7 且旧 8 参入口不变`() {
        // ⚠ SOLE targeted guard（突变审计 2026-08-02 实测）：「9 参 sim 入口悄悄用了旧表」
        // 只被这一条咬住——sim 侧没有第二个针对性守卫。改动前先放替代品。
        val old = AqsScorer.scoreVoiceSim(
            n1RttMs = v(25.0), n2JitterMs = v(3.0), m1MouthEarProxyMs = v(95.0),
            m2DownNetJitterMs = v(5.0), m3UpFrameJitterMs = v(8.0), m4TtfbMs = v(120.0),
            m5TurnSwitchMs = v(180.0), m6BargeStopMs = v(90.0),
        )
        assertEquals(AqsScorer.AQS_VERSION_VOICE_SIM, old.aqsVersion)
        assertTrue("v0.1 sim 出分不得含 M7", !old.subScores.containsKey("M7"))

        val neu = AqsScorer.scoreVoiceSim(
            n1RttMs = v(25.0), n2JitterMs = v(3.0), m1MouthEarProxyMs = v(95.0),
            m2DownNetJitterMs = v(5.0), m3UpFrameJitterMs = v(8.0), m4TtfbMs = v(120.0),
            m5TurnSwitchMs = v(180.0), m6BargeStopMs = v(90.0), m7MaxFrameGapMs = v(30.0),
        )
        assertEquals(AqsScorer.AQS_VERSION_VOICE_SIM_V02, neu.aqsVersion)
        assertEquals(setOf("M1", "M2", "M3", "M4", "M5", "M6", "M7", "N1", "N2"), neu.subScores.keys)
    }

    /**
     * M7 存在的**全部理由**：P95 会把罕见但致命的长冻结整个丢掉，而 max 不会。
     * 数字取自 D-390 实测的那一次 4.55 秒冻结（599 个间隔里占 0.67%）。
     * 这条一旦红，多半是有人把 M7 也改成了分位数——那样它与 M2 就是同一个指标了。
     */
    @Test
    fun `4550ms 冻结_M7 记 0 分而同批 M2 的 P95 毫无察觉`() {
        val nominalUs = 20_000L
        // 599 个间隔：598 个精确 20ms + 1 个 4.55 秒
        val gaps = List(598) { nominalUs } + listOf(4_550_000L)
        val p95 = VoiceRunner.frameJitterP95Ms(gaps, nominalUs)!!
        assertEquals("P95 落在正常间隔上，对 4.55s 冻结毫无察觉", 0.0, p95, 1e-9)

        val r = AqsScorer.scoreVoice(
            n1RttMs = v(25.0), n2JitterMs = v(3.0),
            m1BudgetMs = v(95.0), m2DownFrameJitterMs = v(p95), m3UpFrameJitterMs = v(8.0),
            m7MaxFrameGapMs = v(4550.0),
        )
        assertEquals("M2 拿满分（这正是问题）", 100.0, r.subScores.getValue("M2"), 1e-9)
        assertEquals("M7 越过 1000ms 末锚 → clamp 到 0", 0.0, r.subScores.getValue("M7"), 1e-9)
    }

    /**
     * M7 锚点逐点 + 中点插值 + 越界 clamp。1000ms 那点是 PROVISIONAL（仓内无出处，
     * 首批真实语音语料出来后必须回核）——**钉住它是为了让「回核后改了它」这件事必须显式改测试**，
     * 而不是悄悄漂。
     */
    @Test
    fun `M7 锚点_60_150_400_1000 与端点 clamp`() {
        fun s(ms: Double) = AqsScorer.scoreVoice(
            n1RttMs = v(25.0), n2JitterMs = v(3.0),
            m1BudgetMs = v(95.0), m2DownFrameJitterMs = v(5.0), m3UpFrameJitterMs = v(8.0),
            m7MaxFrameGapMs = v(ms),
        ).subScores.getValue("M7")

        assertEquals(100.0, s(0.0), 1e-9)
        assertEquals(85.0, s(60.0), 1e-9)
        assertEquals(70.0, s(150.0), 1e-9)
        assertEquals(40.0, s(400.0), 1e-9)
        assertEquals(0.0, s(1000.0), 1e-9)
        assertEquals("60↔150 中点线性插值", 77.5, s(105.0), 1e-9)
        assertEquals("下端点外 clamp", 100.0, s(-5.0), 1e-9)
        assertEquals("上端点外 clamp", 0.0, s(99_999.0), 1e-9)
    }

    /**
     * 版本→表名映射必须**覆盖全部语音版本常量**，且每个表名真的是 AqsScorer 上的一张表。
     *
     * 清单从产物导出（反射枚举 AQS_VERSION_VOICE* 常量与 WEIGHTS* 字段），不手写：
     * 手写清单会漏——而漏掉的那一条，UI 会印成 "?"，或更糟，印成上一版的表名。
     */
    @Test
    fun `版本到权重表名映射_覆盖全部语音版本且表名真实存在`() {
        // ⚠ SOLE targeted guard（突变审计 2026-08-02 实测）：删掉一条版本→表名映射
        // 只被这一条咬住。它守的是 UI 上并排印的两个字段不打架。改动前先放替代品。
        val voiceVersions = AqsScorer.javaClass.declaredFields
            .filter { it.name.startsWith("AQS_VERSION_VOICE") && it.type == String::class.java }
            .map { it.isAccessible = true; it.get(AqsScorer) as String }
            .toSet()
        assertEquals(
            "语音版本常量与 VOICE_WEIGHTS_TABLE_BY_VERSION 键集漂移（新增版本须补映射）",
            voiceVersions, AqsScorer.VOICE_WEIGHTS_TABLE_BY_VERSION.keys,
        )
        val weightFieldNames = AqsScorer.javaClass.declaredFields
            .filter { it.name.startsWith("WEIGHTS") && Map::class.java.isAssignableFrom(it.type) }
            .map { it.name }.toSet()
        for ((ver, table) in AqsScorer.VOICE_WEIGHTS_TABLE_BY_VERSION) {
            assertTrue("[$ver] 映射到不存在的表 $table", weightFieldNames.contains(table))
        }
    }

    @Test
    fun `帧抖动P95_精确节奏为0_离群被P95捕获_样本不足null`() {
        // 20ms 名义节奏（µs）：完全精确 → 0
        val exact = List(100) { 20_000L }
        assertEquals(0.0, VoiceRunner.frameJitterP95Ms(exact, 20_000L)!!, 1e-9)
        // 100 个间隔里 6 个 60ms 离群（偏差 40ms）→ P95 落在离群带
        val outliers = List(94) { 20_000L } + List(6) { 60_000L }
        assertEquals(40.0, VoiceRunner.frameJitterP95Ms(outliers, 20_000L)!!, 1e-9)
        // <2 间隔 → null（R-10）
        assertNull(VoiceRunner.frameJitterP95Ms(listOf(20_000L), 20_000L))
    }

    @Test
    fun `口到耳预算_RTT加最大帧抖动加常数_任一缺失null`() {
        val b = VoiceRunner.mouthEarBudgetMs(rttP50Ms = 20.0, upJitterMs = 5.0, downJitterMs = 10.0)
        assertEquals(20.0 + 10.0 + VoiceRunner.CODEC_JB_BUDGET_MS, b!!, 1e-9)
        assertNull(VoiceRunner.mouthEarBudgetMs(null, 5.0, 10.0))
        assertNull(VoiceRunner.mouthEarBudgetMs(20.0, null, 10.0))
        assertNull(VoiceRunner.mouthEarBudgetMs(20.0, 5.0, null))
    }
}
