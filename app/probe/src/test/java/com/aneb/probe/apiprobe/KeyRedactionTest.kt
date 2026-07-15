package com.aneb.probe.apiprobe

import com.aneb.probe.data.ApiProbeResultEntity
import com.aneb.probe.data.TestRun
import com.aneb.probe.engine.ResultReporter
import com.aneb.probe.scoring.AqsScorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 隐私红线锚定（阶段 2 任务 #7 条目 2）：API key 绝不出现在日志/上报体/导出文件。
 * 本测试锚定两个出口：ResultReporter（/results 上报体）与 ApiProbeReport（探针导出）。
 */
class KeyRedactionTest {

    private val fakeKey = "sk-ant-api03-FAKEKEY1234567890abcdef"

    private fun probeEntity(error: String?, guardMetadata: String? = null) = ApiProbeResultEntity(
        startedAtEpochMs = 1770000000000L,
        provider = "anthropic",
        protocolId = "anthropic_messages",
        baseUrl = "https://api.anthropic.com",
        model = "claude-3-5-haiku-latest",
        claimScope = ApiProbeReport.CLAIM_SCOPE,
        httpCode = 200,
        error = error,
        ttftMs = 512.3,
        itlMedianMs = 41.0,
        itlP95Ms = 96.5,
        itlSampleCount = 19,
        tokenEventCount = 20,
        totalMs = 1874.2,
        totalTextChars = 42,
        inputTokens = 12,
        outputTokens = 21,
        stopReason = "end_turn",
        parseErrors = 0,
        protocolError = null,
        proxyDetected = true,
        vpnDetected = false,
        guardMetadata = guardMetadata,
        readCount = 21,
        totalBytes = 4096L,
    )

    @Test
    fun `apiprobe export never contains key even if a field smuggles it`() {
        // 最坏情形：error/guardMetadata 意外携带 key（如异常消息回显请求头）——
        // 导出层 defense-in-depth 必须兜底替换
        val json = ApiProbeReport.buildJson(
            listOf(
                probeEntity(
                    error = "http 401 invalid x-api-key: $fakeKey",
                    guardMetadata = "hdr=$fakeKey",
                )
            ),
            apiKey = fakeKey,
        )
        assertFalse(json.contains(fakeKey))
        assertTrue(json.contains(ApiKeyRedactor.MASK))
    }

    @Test
    fun `apiprobe export carries independent claim scope and aqs exclusion`() {
        val json = ApiProbeReport.buildJson(listOf(probeEntity(error = null)), apiKey = fakeKey)
        assertTrue(json.contains("\"claim_scope\":\"application_end_to_end_to_llm_api\""))
        assertTrue(json.contains("\"excluded_from_aqs\":true"))
        // 与仿真口径明确分开：绝不携带 probe_node claim scope
        assertFalse(json.contains(ResultReporter.CLAIM_SCOPE))
    }

    @Test
    fun `results report body never contains key`() {
        // /results 上报体不含任何 apiprobe 字段与 key（探针结果不进上报，合同不动）
        val run = TestRun(
            runId = "0198a7b0-0000-7000-8000-000000000001",
            startedAtEpochMs = 1770000000000L,
            serverBase = "http://10.0.2.2:8443",
            mode = "quick",
            scenarioOrder = "s1,s2,s3",
            transport = "auto",
            kpiSet = "agent-qoe-kpi-v0.2",
            aqsVersion = "aqs-v0.1",
            profileVersions = "0.2.0",
            schemaVersion = "1.0",
            profileSource = "server",
            appVersionName = "0.1.0",
            appVersionCode = 1L,
            guardMetadata = "private_dns_active=false",
            aqsScore = 88.0,
            aqsLowConfidence = false,
            aqsVetoApplied = false,
            aqsNotComputableReason = null,
            status = "completed",
            reportStatus = null,
        )
        val aqs = AqsScorer.AqsResult(
            aqsVersion = "aqs-v0.1",
            kpiSetVersion = "agent-qoe-kpi-v0.2",
            score = 88.0,
            subScores = emptyMap(),
            vetoApplied = false,
            lowConfidence = false,
            notComputableReason = null,
        )
        val body = ResultReporter.build(run, emptyList(), aqs)
        assertFalse(body.contains(fakeKey))
        assertFalse(body.contains("api_key"))
        assertEquals(ResultReporter.CLAIM_SCOPE, "application_end_to_end_to_probe_node")
    }

    @Test
    fun `redactor masks every occurrence`() {
        val text = "a $fakeKey b $fakeKey"
        val out = ApiKeyRedactor.redact(text, fakeKey)!!
        assertFalse(out.contains(fakeKey))
        assertEquals(2, Regex(Regex.escape(ApiKeyRedactor.MASK)).findAll(out).count())
    }

    @Test
    fun `redactor passthrough on null or blank key`() {
        assertEquals("abc", ApiKeyRedactor.redact("abc", null))
        assertEquals("abc", ApiKeyRedactor.redact("abc", ""))
        assertEquals(null, ApiKeyRedactor.redact(null, fakeKey))
    }

    @Test
    fun `request body has hard capped max_tokens and fixed prompt`() {
        for (p in LlmProvider.entries) {
            val body = ApiProbe.requestBodyJson(p, p.defaultModel)
            assertTrue(body.contains("\"max_tokens\":${ApiProbe.MAX_TOKENS}"))
            assertTrue(body.contains(ApiProbe.PROMPT))
            assertTrue(body.contains("\"stream\":true"))
            assertFalse(body.contains(fakeKey)) // key 只走 header，绝不进 body
        }
        assertEquals(128, ApiProbe.MAX_TOKENS) // 烧钱护栏硬顶（§9 风险表）
    }
}
