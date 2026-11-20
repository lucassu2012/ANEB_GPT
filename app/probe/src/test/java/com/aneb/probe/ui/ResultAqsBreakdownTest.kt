package com.aneb.probe.ui

import com.aneb.probe.data.TestRun
import com.aneb.probe.engine.ItlHistogram
import com.aneb.probe.engine.ResultReporter
import com.aneb.probe.scoring.AqsScorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * [ResultAqsBreakdown] 契约单测：真实子分分解只解析落库上报体、贡献分 = 子分 × 权重，
 * 权重直引 [AqsScorer.WEIGHTS]（单一事实源）。
 */
class ResultAqsBreakdownTest {

    private fun run(version: String) = TestRun(
        runId = "run-1", startedAtEpochMs = 1_752_000_000_000L,
        serverBase = "http://10.0.2.2:8443", mode = "forensic",
        scenarioOrder = "s1_chat,s2_coding_agent,s3_multimodal", transport = "auto",
        kpiSet = "agent-qoe-kpi-v0.2", aqsVersion = version,
        profileVersions = "s1_chat@0.2.0", schemaVersion = "1.0",
        profileSource = "server", appVersionName = "0.3.0", appVersionCode = 1L,
        guardMetadata = "private_dns_active=false", aqsScore = 89.2, aqsLowConfidence = false,
        aqsVetoApplied = false, aqsNotComputableReason = null,
        status = "completed", reportStatus = null,
    )

    private fun reportJson(
        version: String,
        subScores: Map<String, Double>,
        score: Double? = 89.2,
        lowConfidence: Boolean = false,
        vetoApplied: Boolean = false,
    ): String = ResultReporter.build(
        run = run(version),
        scenarios = emptyList(),
        aqs = AqsScorer.AqsResult(
            aqsVersion = version, kpiSetVersion = "agent-qoe-kpi-v0.2",
            score = score, subScores = subScores,
            vetoApplied = vetoApplied, lowConfidence = lowConfidence, notComputableReason = null,
        ),
    )

    private val v01Subs = mapOf(
        "T1" to 97.3, "T3" to 100.0, "T2" to 98.0,
        "U1" to 77.5, "U2" to 77.8, "N1" to 75.1, "N2" to 81.6,
    )

    @Test
    fun `真实上报体端到端解析_v0_1_三组齐全`() {
        val b = ResultAqsBreakdown.fromReportJson(reportJson(AqsScorer.AQS_VERSION, v01Subs))
        assertNotNull("可计算 run 必出分解", b)
        b!!
        assertEquals("aqs-v0.1", b.aqsVersion)
        assertEquals(89.2, b.score!!, 1e-9)
        assertEquals(listOf("流式体验", "上行突发", "网络基线"), b.groups.map { it.label })
        val stream = b.groups.first { it.label == "流式体验" }
        assertEquals(listOf("T1", "T3", "T2"), stream.kpis.map { it.id })
    }

    @Test
    fun `贡献分等于子分乘权重_且权重直引 AqsScorer`() {
        val b = ResultAqsBreakdown.fromReportJson(reportJson(AqsScorer.AQS_VERSION, v01Subs))!!
        val t1 = b.groups.flatMap { it.kpis }.first { it.id == "T1" }
        assertEquals(AqsScorer.WEIGHTS.getValue("T1"), t1.weight, 1e-9)
        assertEquals(97.3, t1.subScore, 1e-9)
        assertEquals(97.3 * AqsScorer.WEIGHTS.getValue("T1"), t1.contributionPoints, 1e-9)
        assertEquals(AqsScorer.WEIGHTS.getValue("T1") * 100.0, t1.maxPoints, 1e-9)
    }

    @Test
    fun `组小计与满分正确`() {
        val b = ResultAqsBreakdown.fromReportJson(reportJson(AqsScorer.AQS_VERSION, v01Subs))!!
        val stream = b.groups.first { it.label == "流式体验" }
        assertEquals(55.0, stream.maxPoints, 1e-9)
        val expected = 97.3 * 0.20 + 100.0 * 0.20 + 98.0 * 0.15
        assertEquals(expected, stream.subtotalPoints, 1e-9)
    }

    @Test
    fun `子分档色同锚 AQS 分档线`() {
        val b = ResultAqsBreakdown.fromReportJson(reportJson(AqsScorer.AQS_VERSION, v01Subs))!!
        val kpis = b.groups.flatMap { it.kpis }.associateBy { it.id }
        assertEquals(ResultFormat.aqsGrade(97.3), kpis.getValue("T1").gradeKey)
        assertEquals(ResultFormat.aqsGrade(77.5), kpis.getValue("U1").gradeKey)
    }

    @Test
    fun `v0_2 出现连续性组_权重取 WEIGHTS_V02`() {
        val subs = v01Subs + mapOf("C1" to 90.0, "C2" to 84.0)
        val b = ResultAqsBreakdown.fromReportJson(reportJson(AqsScorer.AQS_VERSION_V02, subs))!!
        assertEquals("aqs-v0.2", b.aqsVersion)
        assertEquals(listOf("流式体验", "上行突发", "网络基线", "连续性"), b.groups.map { it.label })
        val t1 = b.groups.flatMap { it.kpis }.first { it.id == "T1" }
        assertEquals(AqsScorer.WEIGHTS_V02.getValue("T1"), t1.weight, 1e-9)
        val c1 = b.groups.flatMap { it.kpis }.first { it.id == "C1" }
        assertEquals(AqsScorer.WEIGHTS_V02.getValue("C1"), c1.weight, 1e-9)
        assertEquals(1.0, b.groups.sumOf { it.weight }, 1e-9)
    }

    @Test
    fun `不可计算 run 无子分_返回 null（R-10 绝不 0 顶替）`() {
        val json = reportJson(AqsScorer.AQS_VERSION, emptyMap(), score = null)
        assertNull(ResultAqsBreakdown.fromReportJson(json))
    }

    @Test
    fun `空_null_畸形输入均返回 null 不抛`() {
        assertNull(ResultAqsBreakdown.fromReportJson(null))
        assertNull(ResultAqsBreakdown.fromReportJson(""))
        assertNull(ResultAqsBreakdown.fromReportJson("   "))
        assertNull(ResultAqsBreakdown.fromReportJson("{ not json"))
        assertNull(ResultAqsBreakdown.fromReportJson("[]"))
        assertNull(ResultAqsBreakdown.fromReportJson("""{"run":{}}"""))
        assertNull(ResultAqsBreakdown.fromReportJson("""{"run":{"aqs":{}}}"""))
    }

    @Test
    fun `低置信与否决标志透传`() {
        val json = reportJson(AqsScorer.AQS_VERSION, v01Subs, lowConfidence = true, vetoApplied = true)
        val b = ResultAqsBreakdown.fromReportJson(json)!!
        assertTrue(b.lowConfidence)
        assertTrue(b.vetoApplied)
    }

    @Test
    fun `v0_2 落库上报体 aqs_v02 端到端解析（D-26 additive）`() {
        val v02Subs = v01Subs + mapOf("C1" to 90.0, "C2" to 84.0)
        val body = ResultReporter.build(
            run = run(AqsScorer.AQS_VERSION),
            scenarios = emptyList(),
            aqs = AqsScorer.AqsResult(
                aqsVersion = AqsScorer.AQS_VERSION, kpiSetVersion = "agent-qoe-kpi-v0.2",
                score = 89.2, subScores = v01Subs, vetoApplied = false, lowConfidence = false,
                notComputableReason = null,
            ),
            aqsV02 = AqsScorer.AqsResult(
                aqsVersion = AqsScorer.AQS_VERSION_V02, kpiSetVersion = "agent-qoe-kpi-v0.2",
                score = 87.5, subScores = v02Subs, vetoApplied = false, lowConfidence = true,
                notComputableReason = null,
            ),
        )
        val v01 = ResultAqsBreakdown.fromReportJson(body)!!
        assertEquals(listOf("流式体验", "上行突发", "网络基线"), v01.groups.map { it.label })
        assertEquals("aqs-v0.1", v01.aqsVersion)
        val v02 = ResultAqsBreakdown.v02FromReportJson(body)!!
        assertEquals("aqs-v0.2", v02.aqsVersion)
        assertEquals(87.5, v02.score!!, 1e-9)
        assertTrue(v02.lowConfidence)
        assertEquals(listOf("流式体验", "上行突发", "网络基线", "连续性"), v02.groups.map { it.label })
        val c1 = v02.groups.flatMap { it.kpis }.first { it.id == "C1" }
        assertEquals(AqsScorer.WEIGHTS_V02.getValue("C1"), c1.weight, 1e-9)
        assertEquals(1.0, v02.groups.sumOf { it.weight }, 1e-9)
    }

    @Test
    fun `无 aqs_v02 时 v02FromReportJson 返回 null（正常 v0_1 run）`() {
        val body = reportJson(AqsScorer.AQS_VERSION, v01Subs)
        assertNull(ResultAqsBreakdown.v02FromReportJson(body))
        assertNotNull(ResultAqsBreakdown.fromReportJson(body))
    }

    @Test
    fun `GROUP_KPI_IDS_V01 与 AqsScorer 权重表同源不漂移`() {
        val ids = ResultAqsBreakdown.GROUP_KPI_IDS_V01.flatMap { it.second }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(AqsScorer.WEIGHTS.keys, ids.toSet())
        assertEquals(listOf("流式体验", "上行突发", "网络基线"), ResultAqsBreakdown.GROUP_KPI_IDS_V01.map { it.first })
    }

    @Test
    fun `ItlHistogram of 可构造（编译期依赖锚定）`() {
        assertNotNull(ItlHistogram.of(listOf(50.0, 80.0)))
    }

    @Test
    fun `拖累维度＝子分最低的那个 KPI`() {
        val b = ResultAqsBreakdown.fromReportJson(reportJson(AqsScorer.AQS_VERSION, v01Subs))
        assertEquals("N1", ResultAqsBreakdown.draggingDim(b)?.id)
    }

    @Test
    fun `越界子分不得劫持拖累维度`() {
        val subs = v01Subs + ("T1" to -5.0)
        val b = ResultAqsBreakdown.fromReportJson(reportJson(AqsScorer.AQS_VERSION, subs))
        assertEquals("越界值被滤掉后，拖累维度仍应是真实最低的 N1", "N1", ResultAqsBreakdown.draggingDim(b)?.id)
    }

    @Test
    fun `上越界同样剔除`() {
        val subs = v01Subs + ("N1" to 150.0)
        val b = ResultAqsBreakdown.fromReportJson(reportJson(AqsScorer.AQS_VERSION, subs))
        assertEquals("N1 越界后，最低应变成 U1=77.5", "U1", ResultAqsBreakdown.draggingDim(b)?.id)
    }

    @Test
    fun `无子分时拖累维度为 null 且判词行如实说无`() {
        val b = ResultAqsBreakdown.fromReportJson(reportJson(AqsScorer.AQS_VERSION, emptyMap()))
        assertNull(ResultAqsBreakdown.draggingDim(b))
        assertTrue(ResultAqsBreakdown.draggingVerdictLine(b).contains("无可用子分"))
    }

    @Test
    fun `breakdown 本身为 null 时不炸且如实说无`() {
        assertNull(ResultAqsBreakdown.draggingDim(null))
        assertTrue(ResultAqsBreakdown.draggingVerdictLine(null).contains("无可用子分"))
    }

    @Test
    fun `并列时取规范顺序里靠前的那个`() {
        val subs = v01Subs + ("T1" to 60.0) + ("N1" to 60.0)
        val b = ResultAqsBreakdown.fromReportJson(reportJson(AqsScorer.AQS_VERSION, subs))
        assertEquals("T1", ResultAqsBreakdown.draggingDim(b)?.id)
    }

    @Test
    fun `判词行用报告层同一个词「拖累」并带维度与分值`() {
        val b = ResultAqsBreakdown.fromReportJson(reportJson(AqsScorer.AQS_VERSION, v01Subs))
        val line = ResultAqsBreakdown.draggingVerdictLine(b)
        assertTrue("须用报告层同一个词汇「拖累」", line.contains("拖累"))
        assertTrue("须点名维度 id", line.contains("N1"))
        assertTrue("须给出分值", line.contains("75"))
    }

    @Test
    fun `draggingVerdictLine formats under Locale ROOT regardless of default locale`() {
        val prev = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val b = ResultAqsBreakdown.fromReportJson(reportJson(AqsScorer.AQS_VERSION, v01Subs))
            assertEquals("拖累维度 RTT（N1）=75 分", ResultAqsBreakdown.draggingVerdictLine(b))
        } finally {
            Locale.setDefault(prev)
        }
    }

    @Test
    fun `非有限和越界子分与score在解析阶段被过滤_全部损坏返回null`() {
        val corruptedSubsJson = """
            {
              "aqs_version": "aqs-v0.1",
              "run": {
                "aqs": {
                  "score": "NaN",
                  "sub_scores": {
                    "T1": "NaN",
                    "T3": "Infinity",
                    "T2": -10.0,
                    "U1": 105.0,
                    "N1": 80.0
                  }
                }
              }
            }
        """.trimIndent()
        val b = ResultAqsBreakdown.fromReportJson(corruptedSubsJson)!!
        assertNull("NaN score must become null", b.score)
        val allKpis = b.groups.flatMap { it.kpis }
        assertEquals(listOf("N1"), allKpis.map { it.id })
        assertEquals(80.0 * AqsScorer.WEIGHTS.getValue("N1"), b.groups.single().subtotalPoints, 1e-9)

        val allInvalidJson = """
            {
              "aqs_version": "aqs-v0.1",
              "run": {
                "aqs": {
                  "score": 50.0,
                  "sub_scores": { "T1": "NaN", "N1": -1.0, "U1": 101.0 }
                }
              }
            }
        """.trimIndent()
        assertNull(ResultAqsBreakdown.fromReportJson(allInvalidJson))
    }

    @Test
    fun `显式版本优先_aqs_v0_1含C1不篡改权重_aqs_v02误标v0_1仍按v0_2_无版本含C1推v0_2`() {
        val v01WithStrayC1 = """
            {
              "aqs_version": "aqs-v0.1",
              "run": {
                "aqs": {
                  "aqs_version": "aqs-v0.1",
                  "score": 88.0,
                  "sub_scores": { "T1": 90.0, "C1": 70.0 }
                }
              }
            }
        """.trimIndent()
        val b1 = ResultAqsBreakdown.fromReportJson(v01WithStrayC1)!!
        assertEquals("aqs-v0.1", b1.aqsVersion)
        assertEquals(AqsScorer.WEIGHTS.getValue("T1"), b1.groups.flatMap { it.kpis }.first { it.id == "T1" }.weight, 1e-9)
        assertTrue(b1.groups.none { it.label == "连续性" })

        val misStampedV02 = """
            {
              "aqs_version": "aqs-v0.1",
              "run": {
                "aqs_v02": {
                  "aqs_version": "aqs-v0.1",
                  "score": 85.0,
                  "sub_scores": { "T1": 90.0, "C1": 80.0 }
                }
              }
            }
        """.trimIndent()
        val b2 = ResultAqsBreakdown.v02FromReportJson(misStampedV02)!!
        assertEquals("aqs-v0.2", b2.aqsVersion)
        assertEquals(AqsScorer.WEIGHTS_V02.getValue("T1"), b2.groups.flatMap { it.kpis }.first { it.id == "T1" }.weight, 1e-9)
        assertTrue(b2.groups.any { it.label == "连续性" })

        val unversionedWithC1 = """
            {
              "run": {
                "aqs": {
                  "score": 84.0,
                  "sub_scores": { "T1": 90.0, "C1": 80.0 }
                }
              }
            }
        """.trimIndent()
        val b3 = ResultAqsBreakdown.fromReportJson(unversionedWithC1)!!
        assertEquals("aqs-v0.2", b3.aqsVersion)
        assertEquals(AqsScorer.WEIGHTS_V02.getValue("T1"), b3.groups.flatMap { it.kpis }.first { it.id == "T1" }.weight, 1e-9)
    }
}
