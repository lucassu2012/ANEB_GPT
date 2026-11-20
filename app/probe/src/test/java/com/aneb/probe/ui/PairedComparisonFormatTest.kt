package com.aneb.probe.ui

import com.aneb.probe.data.BasicSpeedResultEntity
import com.aneb.probe.data.RadioSampleEntity
import com.aneb.probe.data.ScenarioResultEntity
import com.aneb.probe.data.TestRun
import com.aneb.probe.data.VoiceResultEntity
import com.aneb.probe.engine.LoadedResponsivenessReport
import com.aneb.probe.engine.NetworkSpeedEngine
import com.aneb.probe.engine.ResponsivenessByteObservation
import com.aneb.probe.engine.ResponsivenessEcho
import com.aneb.probe.engine.ResponsivenessStage
import com.aneb.probe.engine.VoiceRunner
import com.aneb.probe.ui.PairedComparisonFormat.ComparableRunInput
import com.aneb.probe.ui.PairedComparisonFormat.ComparisonCategory
import com.aneb.probe.ui.PairedComparisonFormat.DeltaTone
import com.aneb.probe.ui.PairedComparisonFormat.HistoryComparisonState
import com.aneb.probe.ui.PairedComparisonFormat.MetricDirection
import com.aneb.probe.ui.PairedComparisonFormat.PairedComparisonResult
import com.aneb.probe.ui.PairedComparisonFormat.TransportKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class PairedComparisonFormatTest {

    private fun testRun(
        runId: String,
        startedAt: Long,
        transport: String = "auto",
        aqsScore: Double? = 85.0,
        serverBase: String = ProbeSettings.DEFAULT_SERVER_URL,
        lowConfidence: Boolean? = false,
        aqsV02Score: Double? = null,
    ) = TestRun(
        runId = runId,
        startedAtEpochMs = startedAt,
        serverBase = serverBase,
        mode = "quick",
        scenarioOrder = "s1_chat,s2_coding_agent,s3_multimodal",
        transport = transport,
        kpiSet = "agent-qoe-kpi-v0.2",
        aqsVersion = "aqs-v0.1",
        profileVersions = "s1_chat:1.0.0",
        schemaVersion = "1.0",
        profileSource = "server",
        appVersionName = "0.2.0",
        appVersionCode = 20L,
        guardMetadata = null,
        aqsScore = aqsScore,
        aqsLowConfidence = lowConfidence,
        aqsVetoApplied = false,
        aqsNotComputableReason = null,
        status = "completed",
        reportStatus = "http=200",
        aqsV02Score = aqsV02Score,
    )

    private fun scenario(
        runId: String,
        profileId: String,
        netTransport: String? = "auto(wifi)",
        t1: Double? = 180.0,
        t2: Double? = 60.0,
        t3: Double? = 0.01,
        n1: Double? = 25.0,
        n2: Double? = 4.0,
        u1: Double? = 40.0,
        u2: Double? = 140.0,
    ) = ScenarioResultEntity(
        runId = runId,
        profileId = profileId,
        profileVersion = "1.0.0",
        repeatIndex = 0,
        orderIndex = 0,
        startedAtNanos = 1_000L,
        endedAtNanos = 2_000L,
        validity = "valid",
        invalidReasons = "",
        t1TtftMs = t1, t1Grade = null,
        t2ItlP95Ms = t2, t2Grade = null,
        t2ItlP95InclCoalescedMs = t2,
        t3StallRate = t3, t3Grade = null,
        t3StallRateInclResume = t3,
        t4SevereStallRate = 0.0, t4Grade = null,
        t5ResumeP95Ms = null,
        n1RttP50Ms = n1, n1Grade = null,
        n2JitterMs = n2, n2Grade = null,
        u1GoodputMbps = u1, u1Grade = null,
        u1GoodputExclSlowStartMbps = u1,
        u2ToolLoopP95Ms = u2, u2Grade = null,
        seqGapCount = 0,
        seqDupCount = 0,
        lowConfidenceKpis = "",
        offsetStartUs = 10L, offsetStartErrUs = 1L,
        offsetEndUs = 20L, offsetEndErrUs = 1L,
        offsetDriftPpm = 0.5, offsetSuspect = false,
        netTransport = netTransport,
        netCapabilities = null,
        netInterfaceName = null,
        serverObservedAddr = null,
        parseDurUsTotal = 100L,
        perEventParseUs = 1.0,
    )

    private fun tokenScenarios(
        runId: String,
        netTransport: String?,
        t1: Double? = 180.0,
        t3: Double? = 0.01,
        u1: Double? = 40.0,
    ): List<ScenarioResultEntity> = listOf(
        scenario(runId, "s1_chat", netTransport = netTransport, n1 = 22.0, n2 = 3.5),
        scenario(runId, "s2_coding_agent", netTransport = netTransport, t1 = t1, t3 = t3),
        scenario(runId, "s3_multimodal", netTransport = netTransport, u1 = u1),
    )

    private fun radioSample(
        runId: String,
        nrState: String,
        networkType: String = "LTE",
        rat: String? = "LTE",
        overrideType: String? = null,
    ) = RadioSampleEntity(
        runId = runId,
        tsNanos = 1_000_000L,
        cellTsNanos = 1_000_000L,
        stale = false,
        subId = 1,
        subSwitched = false,
        networkType = networkType,
        overrideType = overrideType,
        nrState = nrState,
        rat = rat,
        pci = 101,
        tac = 202,
        arfcn = 303,
        rsrp = -88,
        rsrq = -9,
        sinr = 12,
        operatorName = "TestNet",
    )

    private fun basicEntity(
        runId: String,
        startedAt: Long,
        profileId: String = NetworkSpeedEngine.PROFILE_ID,
        downloadMbps: Double? = 120.0,
        uploadMbps: Double? = 45.0,
        pingMs: Double? = 28.0,
    ) = BasicSpeedResultEntity(
        runId = runId,
        startedAtEpochMs = startedAt,
        serverBase = ProbeSettings.DEFAULT_SERVER_URL,
        claimScope = "app",
        profileId = profileId,
        profileVersion = "1.0.0",
        conclusionPolicyId = "basic_v1",
        status = "completed",
        downloadMbps = downloadMbps,
        uploadMbps = uploadMbps,
        pingMs = pingMs,
        jitterMs = 3.0,
        requestLossRate = 0.0,
        postLoadPingMs = 55.0,
        downloadBytes = 10_000_000L,
        uploadBytes = 5_000_000L,
        transferErrors = "",
    )

    private fun responsivenessReport(runId: String, transport: String, idleUs: Long, dlUs: Long): LoadedResponsivenessReport {
        val idleStage = ResponsivenessStage(
            stage = "idle",
            startUs = 0L,
            endUs = 100_000L,
            transferBytes = 0L,
            samples = listOf(
                ResponsivenessEcho("idle", 0, 10_000L, 11_000L, 11_100L, 10_000L + idleUs, 200, null),
            ),
        )
        val dlStage = ResponsivenessStage(
            stage = "download",
            startUs = 200_000L,
            endUs = 500_000L,
            transferBytes = 200_000L,
            samples = listOf(
                ResponsivenessEcho("download", 0, 250_000L, 251_000L, 251_100L, 250_000L + dlUs, 200, null, 1_000L, 50_000L),
            ),
            byteObservations = listOf(
                ResponsivenessByteObservation(240_000L, 1_000L),
                ResponsivenessByteObservation(250_000L + dlUs, 50_000L),
            ),
        )
        return LoadedResponsivenessReport(
            runId = runId,
            serverBase = ProbeSettings.DEFAULT_SERVER_URL,
            transport = transport,
            status = "completed",
            stages = listOf(idleStage, dlStage),
        )
    }

    private fun voiceEntity(
        id: Long,
        ts: Long,
        rttMs: Double? = 32.0,
        mouthEarProxyMs: Double? = 170.0,
        caliber: String? = VoiceRunner.SIM_CALIBER,
        mouthEarBudgetMs: Double? = 95.0,
    ) =
        VoiceResultEntity(
            id = id,
            tsEpochMs = ts,
            caliber = caliber,
            lowConfidence = false,
            rttMs = rttMs,
            jitterMs = 4.0,
            upFrameJitterMs = 5.0,
            downFrameJitterMs = 6.0,
            mouthEarBudgetMs = mouthEarBudgetMs,
            framesSent = 150,
            framesRecv = 800,
            ttfbP50Ms = 110.0,
            ttfbP95Ms = 150.0,
            downNetJitterMs = 4.5,
            mouthEarProxyMs = mouthEarProxyMs,
            turnSwitchP50Ms = 135.0,
            bargeStopMaxMs = 90.0,
            turnsOk = 8,
            m7MaxFrameGapMs = 35.0,
            voiceNearZeroArrivalRatio = 0.01,
        )

    @Test
    fun `resolveTransport enforces R-15 for 5G NR vs 4G LTE vs fallback and basic or voice honesty`() {
        val wifiRun = ComparableRunInput.Token(
            run = testRun("r-wifi", 100L, transport = "auto"),
            scenarios = tokenScenarios("r-wifi", netTransport = "auto(wifi)"),
        )
        assertEquals(TransportKind.WIFI to "Wi-Fi", PairedComparisonFormat.resolveTransport(wifiRun))

        // Display overrideType="NR_NSA" WITHOUT nrState="connected" must NOT claim 5G NR (R-15).
        val fake5gOverride = ComparableRunInput.Token(
            run = testRun("r-lte", 200L, transport = "auto"),
            scenarios = tokenScenarios("r-lte", netTransport = "auto(cellular)"),
            radioSamples = listOf(radioSample("r-lte", nrState = "none", networkType = "LTE", rat = "LTE", overrideType = "NR_NSA")),
        )
        assertEquals(TransportKind.CELLULAR to "4G LTE", PairedComparisonFormat.resolveTransport(fake5gOverride))

        val true5gNr = ComparableRunInput.Token(
            run = testRun("r-nr", 300L, transport = "cellular"),
            scenarios = tokenScenarios("r-nr", netTransport = "cellular"),
            radioSamples = listOf(radioSample("r-nr", nrState = "connected", networkType = "NR", rat = "NR")),
        )
        assertEquals(TransportKind.CELLULAR to "5G NR", PairedComparisonFormat.resolveTransport(true5gNr))

        val cellularNoRadio = ComparableRunInput.Token(
            run = testRun("r-cell", 350L, transport = "cellular"),
            scenarios = emptyList(),
        )
        assertEquals(TransportKind.CELLULAR to "蜂窝网络", PairedComparisonFormat.resolveTransport(cellularNoRadio))

        val ethernetRun = ComparableRunInput.Token(
            run = testRun("r-eth", 360L, transport = "auto"),
            scenarios = tokenScenarios("r-eth", netTransport = "auto(ethernet)"),
        )
        assertEquals(TransportKind.OTHER to "以太网", PairedComparisonFormat.resolveTransport(ethernetRun))

        val respWifi = ComparableRunInput.Basic(
            entity = basicEntity("b-resp", 400L, profileId = NetworkSpeedEngine.RESPONSIVENESS_PROFILE_ID),
            responsivenessReport = responsivenessReport("b-resp", "wifi", 25_000L, 120_000L),
        )
        assertEquals(TransportKind.WIFI to "Wi-Fi", PairedComparisonFormat.resolveTransport(respWifi))

        val plainBasic = ComparableRunInput.Basic(entity = basicEntity("b-plain", 500L))
        assertEquals(TransportKind.UNKNOWN to "未记录网络", PairedComparisonFormat.resolveTransport(plainBasic))

        val voice = ComparableRunInput.Voice(voiceEntity(1L, 600L))
        assertEquals(TransportKind.UNKNOWN to "未记录网络", PairedComparisonFormat.resolveTransport(voice))
    }

    @Test
    fun `quick pairing prefers newest Wi-Fi and Cellular Token pair and canonicalizes A=Wi-Fi B=5G NR`() {
        val oldWifi = ComparableRunInput.Token(
            run = testRun("w-old", 1_000L, aqsScore = 88.0),
            scenarios = tokenScenarios("w-old", "auto(wifi)"),
        )
        val newWifi = ComparableRunInput.Token(
            run = testRun("w-new", 3_000L, aqsScore = 91.0),
            scenarios = tokenScenarios("w-new", "wifi", t1 = 120.0, t3 = 0.005, u1 = 50.0),
        )
        val nrRun = ComparableRunInput.Token(
            run = testRun("nr-new", 2_500L, aqsScore = 79.0),
            scenarios = tokenScenarios("nr-new", "auto(cellular)", t1 = 210.0, t3 = 0.025, u1 = 30.0),
            radioSamples = listOf(radioSample("nr-new", nrState = "connected", networkType = "NR", rat = "NR")),
        )
        val plainBasic = ComparableRunInput.Basic(basicEntity("b-1", 4_000L))

        val pair = PairedComparisonFormat.findQuickWifiVsCellularPair(listOf(oldWifi, plainBasic, nrRun, newWifi))
        assertEquals("token:w-new" to "token:nr-new", pair)

        // Passing cellular first without preserveOrder canonicalizes Wi-Fi to left (A) and 5G NR to right (B)
        val result = PairedComparisonFormat.compare(nrRun, newWifi) as PairedComparisonResult.Compatible
        assertEquals("Wi-Fi", result.left.transportLabel)
        assertEquals("5G NR", result.right.transportLabel)
        assertEquals("A", result.left.badge)
        assertEquals("B", result.right.badge)

        val aqsRow = result.rows.first { it.id == "AQS" }
        assertEquals(DeltaTone.REGRESSED, aqsRow.tone) // 91 -> 79 is regressed for higher-is-better
        val t3Row = result.rows.first { it.id == "T3" }
        assertEquals("0.50%", t3Row.formattedA)
        assertEquals("2.50%", t3Row.formattedB)
        assertEquals("+2.00 pp", t3Row.formattedDelta)
        assertEquals(DeltaTone.REGRESSED, t3Row.tone)
    }

    @Test
    fun `cross-caliber comparison yields Mismatch instead of mixing metrics`() {
        val token = ComparableRunInput.Token(
            run = testRun("t-1", 100L),
            scenarios = tokenScenarios("t-1", "wifi"),
        )
        val basic = ComparableRunInput.Basic(basicEntity("b-1", 200L))
        val resp = ComparableRunInput.Basic(
            basicEntity("b-resp", 250L, profileId = NetworkSpeedEngine.RESPONSIVENESS_PROFILE_ID),
            responsivenessReport("b-resp", "wifi", 20_000L, 100_000L),
        )
        val voice = ComparableRunInput.Voice(voiceEntity(1L, 300L))

        assertTrue(PairedComparisonFormat.compare(token, basic) is PairedComparisonResult.Mismatch)
        assertTrue(PairedComparisonFormat.compare(basic, resp) is PairedComparisonResult.Mismatch)
        assertTrue(PairedComparisonFormat.compare(token, voice) is PairedComparisonResult.Mismatch)
    }

    @Test
    fun `R-10 null NaN Infinity and zero baseline delta guards hold under Locale GERMANY`() {
        val prev = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val nullRow = PairedComparisonFormat.computeRow(
                id = "T1",
                label = "TTFT",
                unit = "ms",
                direction = MetricDirection.LOWER_IS_BETTER,
                decimals = 1,
                valueA = Double.NaN,
                valueB = 150.0,
            )
            assertNull(nullRow.valueA)
            assertEquals("—", nullRow.formattedA)
            assertEquals("150.0 ms", nullRow.formattedB)
            assertNull(nullRow.absDelta)
            assertNull(nullRow.pctDelta)
            assertEquals("—", nullRow.formattedDelta)
            assertEquals(DeltaTone.UNAVAILABLE, nullRow.tone)

            val infRow = PairedComparisonFormat.computeRow(
                id = "U1",
                label = "上行吞吐",
                unit = "Mbps",
                direction = MetricDirection.HIGHER_IS_BETTER,
                decimals = 2,
                valueA = 20.0,
                valueB = Double.POSITIVE_INFINITY,
            )
            assertNull(infRow.valueB)
            assertEquals("—", infRow.formattedB)
            assertEquals("—", infRow.formattedDelta)
            assertEquals(DeltaTone.UNAVAILABLE, infRow.tone)

            // Zero baseline omits percentage (no Infinity% or NaN%)
            val zeroBase = PairedComparisonFormat.computeRow(
                id = "N2",
                label = "抖动",
                unit = "ms",
                direction = MetricDirection.LOWER_IS_BETTER,
                decimals = 1,
                valueA = 0.0,
                valueB = 12.5,
            )
            assertEquals("0.0 ms", zeroBase.formattedA)
            assertEquals("12.5 ms", zeroBase.formattedB)
            assertNull(zeroBase.pctDelta)
            assertEquals("+12.5 ms", zeroBase.formattedDelta)
            assertEquals(DeltaTone.REGRESSED, zeroBase.tone)
        } finally {
            Locale.setDefault(prev)
        }
    }

    @Test
    fun `buildMarkdown includes claim scope disclaimers and dot-decimal metrics`() {
        val prev = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val wifi = ComparableRunInput.Token(
                run = testRun("w1", 1_752_000_000_000L, aqsScore = 88.5, aqsV02Score = 84.0),
                scenarios = tokenScenarios("w1", "wifi", t1 = 150.0, t3 = 0.005, u1 = 42.5),
            )
            val nr = ComparableRunInput.Token(
                run = testRun("n1", 1_752_000_600_000L, aqsScore = 76.0, lowConfidence = true),
                scenarios = tokenScenarios("n1", "cellular", t1 = 260.0, t3 = 0.02, u1 = 21.0),
                radioSamples = listOf(radioSample("n1", nrState = "connected", networkType = "NR", rat = "NR")),
            )
            val result = PairedComparisonFormat.compare(wifi, nr) as PairedComparisonResult.Compatible
            val md = PairedComparisonFormat.buildMarkdown(result)

            assertTrue(md.contains("# ANEB 配对对比报告（Wi-Fi vs 5G NR）"))
            assertTrue(md.contains("Node-E01 · 阿里云 (miw2)"))
            assertTrue(md.contains("88.5 分"))
            assertTrue(md.contains("76.0 分"))
            assertTrue(md.contains(ResultFormat.CLAIM_SCOPE_TEXT))
            assertTrue(md.contains(ResultFormat.AQS_DISCLAIMER_TEXT))
            assertTrue(md.contains("低置信"))
        } finally {
            Locale.setDefault(prev)
        }
    }

    @Test
    fun `HistoryComparisonState transitions handle toggle quickPair selection swap and clear`() {
        val wifi = ComparableRunInput.Token(testRun("w", 100L), tokenScenarios("w", "wifi"))
        val nr = ComparableRunInput.Token(
            testRun("n", 200L),
            tokenScenarios("n", "cellular"),
            radioSamples = listOf(radioSample("n", nrState = "connected", networkType = "NR", rat = "NR")),
        )
        val third = ComparableRunInput.Token(testRun("x", 300L), tokenScenarios("x", "wifi"))
        val byKey = listOf(wifi, nr, third).associateBy { it.entryKey }

        var state = HistoryComparisonState()
        assertEquals(false, state.compareMode)

        state = state.toggleCompareMode()
        assertTrue(state.compareMode)

        // Empty quick pair sets guidance notice
        val noPair = state.quickPairWifiVs5g(listOf(wifi))
        assertNotNull(noPair.notice)

        // Successful quick pair selects Wi-Fi and 5G NR
        state = state.quickPairWifiVs5g(listOf(wifi, nr))
        assertEquals(listOf("token:w", "token:n"), state.selectedKeys)
        val base = state.resolveComparison(byKey) as PairedComparisonResult.Compatible
        assertEquals("Wi-Fi", base.left.transportLabel)
        assertEquals("5G NR", base.right.transportLabel)

        // Swap flips left & right
        state = state.swap()
        val swapped = state.resolveComparison(byKey) as PairedComparisonResult.Compatible
        assertEquals("5G NR", swapped.left.transportLabel)
        assertEquals("Wi-Fi", swapped.right.transportLabel)

        // Selecting a 3rd key replaces the 2nd key and resets swap
        state = state.toggleSelection("token:x")
        assertEquals(listOf("token:w", "token:x"), state.selectedKeys)
        assertEquals(false, state.swapped)

        // Clear resets selection
        state = state.clearSelection()
        assertTrue(state.selectedKeys.isEmpty())
    }

    @Test
    fun `Voice server-sim vs paced-proxy yields Mismatch with caliber explanation`() {
        val simVoice = ComparableRunInput.Voice(
            voiceEntity(id = 1L, ts = 100L, caliber = VoiceRunner.SIM_CALIBER, mouthEarProxyMs = 160.0),
        )
        val proxyVoice = ComparableRunInput.Voice(
            voiceEntity(id = 2L, ts = 200L, caliber = null, mouthEarProxyMs = null, mouthEarBudgetMs = 140.0),
        )

        val mismatch1 = PairedComparisonFormat.compare(simVoice, proxyVoice)
        assertTrue(mismatch1 is PairedComparisonResult.Mismatch)
        mismatch1 as PairedComparisonResult.Mismatch
        assertTrue(mismatch1.leftLabel.contains("server-sim"))
        assertTrue(mismatch1.rightLabel.contains("paced-proxy"))
        assertTrue(mismatch1.reason.contains("server-sim") && mismatch1.reason.contains("paced-proxy"))

        val mismatch2 = PairedComparisonFormat.compare(proxyVoice, simVoice)
        assertTrue(mismatch2 is PairedComparisonResult.Mismatch)
    }

    @Test
    fun `same-caliber BasicSpeed LoadedResponsiveness and Voice comparisons produce Compatible deltas`() {
        val basicA = ComparableRunInput.Basic(
            basicEntity("b-1", 100L, downloadMbps = 120.0, uploadMbps = 40.0, pingMs = 24.0),
        )
        val basicB = ComparableRunInput.Basic(
            basicEntity("b-2", 200L, downloadMbps = 80.0, uploadMbps = 50.0, pingMs = 36.0),
        )
        val basicCmp = PairedComparisonFormat.compare(basicA, basicB) as PairedComparisonResult.Compatible
        assertEquals(ComparisonCategory.BASIC_SPEED, basicCmp.category)
        val dlRow = basicCmp.rows.first { it.id == "DL" }
        assertEquals("120.00 Mbps", dlRow.formattedA)
        assertEquals("80.00 Mbps", dlRow.formattedB)
        assertEquals(DeltaTone.REGRESSED, dlRow.tone)

        val respA = ComparableRunInput.Basic(
            basicEntity("r-1", 300L, profileId = NetworkSpeedEngine.RESPONSIVENESS_PROFILE_ID, downloadMbps = 100.0, uploadMbps = 30.0),
            responsivenessReport("r-1", "wifi", 20_000L, 100_000L),
        )
        val respB = ComparableRunInput.Basic(
            basicEntity("r-2", 400L, profileId = NetworkSpeedEngine.RESPONSIVENESS_PROFILE_ID, downloadMbps = 60.0, uploadMbps = 20.0),
            responsivenessReport("r-2", "cellular", 30_000L, 180_000L),
        )
        val respCmp = PairedComparisonFormat.compare(respA, respB) as PairedComparisonResult.Compatible
        assertEquals(ComparisonCategory.LOADED_RESPONSIVENESS, respCmp.category)
        assertEquals("Wi-Fi", respCmp.left.transportLabel)
        assertEquals("蜂窝网络", respCmp.right.transportLabel)
        assertEquals(DeltaTone.REGRESSED, respCmp.rows.first { it.id == "DL_P95" }.tone)

        val voiceSimA = ComparableRunInput.Voice(voiceEntity(10L, 500L, caliber = VoiceRunner.SIM_CALIBER, mouthEarProxyMs = 170.0))
        val voiceSimB = ComparableRunInput.Voice(voiceEntity(11L, 600L, caliber = VoiceRunner.SIM_CALIBER, mouthEarProxyMs = 140.0))
        val voiceSimCmp = PairedComparisonFormat.compare(voiceSimA, voiceSimB) as PairedComparisonResult.Compatible
        assertEquals(ComparisonCategory.VOICE, voiceSimCmp.category)
        assertEquals(DeltaTone.IMPROVED, voiceSimCmp.rows.first { it.id == "MOUTH_EAR" }.tone)

        val voiceProxyA = ComparableRunInput.Voice(
            voiceEntity(12L, 700L, caliber = null, mouthEarProxyMs = null, mouthEarBudgetMs = 130.0),
        )
        val voiceProxyB = ComparableRunInput.Voice(
            voiceEntity(13L, 800L, caliber = null, mouthEarProxyMs = null, mouthEarBudgetMs = 110.0),
        )
        val voiceProxyCmp = PairedComparisonFormat.compare(voiceProxyA, voiceProxyB) as PairedComparisonResult.Compatible
        assertEquals(ComparisonCategory.VOICE, voiceProxyCmp.category)
        assertEquals(DeltaTone.IMPROVED, voiceProxyCmp.rows.first { it.id == "MOUTH_EAR" }.tone)
    }

    @Test
    fun `mixed scenario netTransport in single TestRun resolves to 混合网络 and adds disclaimer`() {
        val mixedRun = ComparableRunInput.Token(
            run = testRun("r-mixed", 500L, transport = "auto", aqsScore = 82.0),
            scenarios = listOf(
                scenario("r-mixed", "s1_chat", netTransport = "auto(wifi)"),
                scenario("r-mixed", "s2_coding_agent", netTransport = "auto(cellular)"),
                scenario("r-mixed", "s3_multimodal", netTransport = "auto(cellular)"),
            ),
            radioSamples = listOf(radioSample("r-mixed", nrState = "connected", networkType = "NR", rat = "NR")),
        )
        assertEquals(TransportKind.OTHER to "混合网络", PairedComparisonFormat.resolveTransport(mixedRun))

        val nrRun = ComparableRunInput.Token(
            run = testRun("r-nr", 400L, transport = "cellular", aqsScore = 79.0),
            scenarios = tokenScenarios("r-nr", "cellular"),
            radioSamples = listOf(radioSample("r-nr", nrState = "connected", networkType = "NR", rat = "NR")),
        )
        // Mixed run must not masquerade as Wi-Fi in quick pairing
        assertNull(PairedComparisonFormat.findQuickWifiVsCellularPair(listOf(mixedRun, nrRun)))

        val cmp = PairedComparisonFormat.compare(mixedRun, nrRun) as PairedComparisonResult.Compatible
        assertEquals("混合网络", cmp.left.transportLabel)
        assertTrue(cmp.disclaimers.any { it.contains("混合网络") })
    }

    @Test
    fun `negative zero is normalized in formatMetricValue and computeRow`() {
        val negZero = -0.0
        assertEquals("0.0 ms", PairedComparisonFormat.formatMetricValue(negZero, "ms", 1))
        assertEquals("0.00%", PairedComparisonFormat.formatMetricValue(negZero, "ratio", 2))

        val msRow = PairedComparisonFormat.computeRow(
            id = "N2",
            label = "抖动",
            unit = "ms",
            direction = MetricDirection.LOWER_IS_BETTER,
            decimals = 1,
            valueA = negZero,
            valueB = 0.0,
        )
        assertEquals("0.0 ms", msRow.formattedA)
        assertEquals("0.0 ms", msRow.formattedB)
        assertEquals("0.0 ms", msRow.formattedDelta)
        assertEquals(DeltaTone.NEUTRAL, msRow.tone)

        val ratioRow = PairedComparisonFormat.computeRow(
            id = "T3",
            label = "卡顿率",
            unit = "ratio",
            direction = MetricDirection.LOWER_IS_BETTER,
            decimals = 2,
            valueA = negZero,
            valueB = negZero,
        )
        assertEquals("0.00%", ratioRow.formattedA)
        assertEquals("0.00%", ratioRow.formattedB)
        assertEquals("0.00 pp", ratioRow.formattedDelta)
        assertEquals(DeltaTone.NEUTRAL, ratioRow.tone)
    }

    @Test
    fun `non-Token buildMarkdown omits AQS_DISCLAIMER_TEXT while preserving CLAIM_SCOPE_TEXT`() {
        val basicA = ComparableRunInput.Basic(basicEntity("b-a", 100L))
        val basicB = ComparableRunInput.Basic(basicEntity("b-b", 200L, downloadMbps = 95.0))
        val basicMd = PairedComparisonFormat.buildMarkdown(
            PairedComparisonFormat.compare(basicA, basicB) as PairedComparisonResult.Compatible,
        )
        assertTrue(basicMd.contains(ResultFormat.CLAIM_SCOPE_TEXT))
        assertFalse(basicMd.contains(ResultFormat.AQS_DISCLAIMER_TEXT))

        val respA = ComparableRunInput.Basic(
            basicEntity("r-a", 300L, profileId = NetworkSpeedEngine.RESPONSIVENESS_PROFILE_ID),
            responsivenessReport("r-a", "wifi", 20_000L, 100_000L),
        )
        val respB = ComparableRunInput.Basic(
            basicEntity("r-b", 400L, profileId = NetworkSpeedEngine.RESPONSIVENESS_PROFILE_ID),
            responsivenessReport("r-b", "cellular", 30_000L, 150_000L),
        )
        val respMd = PairedComparisonFormat.buildMarkdown(
            PairedComparisonFormat.compare(respA, respB) as PairedComparisonResult.Compatible,
        )
        assertTrue(respMd.contains(ResultFormat.CLAIM_SCOPE_TEXT))
        assertFalse(respMd.contains(ResultFormat.AQS_DISCLAIMER_TEXT))

        val voiceA = ComparableRunInput.Voice(voiceEntity(1L, 500L))
        val voiceB = ComparableRunInput.Voice(voiceEntity(2L, 600L, mouthEarProxyMs = 150.0))
        val voiceMd = PairedComparisonFormat.buildMarkdown(
            PairedComparisonFormat.compare(voiceA, voiceB) as PairedComparisonResult.Compatible,
        )
        assertTrue(voiceMd.contains(ResultFormat.CLAIM_SCOPE_TEXT))
        assertFalse(voiceMd.contains(ResultFormat.AQS_DISCLAIMER_TEXT))
    }
}
