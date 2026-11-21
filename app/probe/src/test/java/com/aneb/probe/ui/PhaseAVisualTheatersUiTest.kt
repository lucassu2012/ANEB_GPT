package com.aneb.probe.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.aneb.probe.engine.AnebTestMode
import com.aneb.probe.engine.BasicSpeedPhase
import com.aneb.probe.engine.BasicSpeedResult
import com.aneb.probe.engine.BasicSpeedTelemetry
import com.aneb.probe.engine.BehaviorTracePlan
import com.aneb.probe.engine.BehaviorTraceRunner
import com.aneb.probe.engine.LiveStreamWindow
import com.aneb.probe.engine.LiveTelemetry
import com.aneb.probe.engine.LoadedResponsiveness
import com.aneb.probe.engine.LoadedResponsivenessReport
import com.aneb.probe.engine.NetworkSpeedEngine
import com.aneb.probe.engine.PlannedTask
import com.aneb.probe.engine.PlannedToken
import com.aneb.probe.engine.ResponsivenessByteObservation
import com.aneb.probe.engine.ResponsivenessEcho
import com.aneb.probe.engine.ResponsivenessStage
import com.aneb.probe.engine.VideoStreamingEngine
import com.aneb.probe.engine.VoiceRunner
import com.aneb.probe.ui.theme.AnebTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h1400dp-xxhdpi")
class PhaseAVisualTheatersUiTest {

    @Test
    fun `UI sources contain zero emojis`() {
        val candidates = listOf(
            File("src/main/java/com/aneb/probe/ui"),
            File("app/probe/src/main/java/com/aneb/probe/ui"),
        )
        val uiRoot = candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate ui source directory from ${File(".").absolutePath}")
        val emojiRegex = Regex("[\uD83C-\uDBFF][\uDC00-\uDFFF]|[\u2705\u26A0\u26A1\uFE0F\u2728\u274C\u274E\u2714\u2716\u23F0-\u23FF]")
        val violations = mutableListOf<String>()
        uiRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { idx, line ->
                    if (emojiRegex.containsMatchIn(line)) {
                        violations += "${file.name}:${idx + 1}: ${line.trim()}"
                    }
                }
            }
        assertTrue("UI must not contain emojis, found:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    @Test
    fun `BehaviorTracePlan quickMultimodalSample picks one task per modality with fast pacing`() {
        fun makeTask(idx: Int, id: String, kind: String, uploadBytes: Long, tokenCount: Int) = PlannedTask(
            taskIndex = idx,
            taskId = id,
            workloadKind = kind,
            repetition = 0,
            uploadBytes = uploadBytes,
            uploadChunks = listOf(uploadBytes.toInt()),
            uploadChunkCadenceMs = 40.0,
            processingDelayMs = 200.0,
            tokenPlans = (1..tokenCount).map { seq ->
                PlannedToken(
                    seq = seq.toLong(),
                    plannedOffsetMs = seq * 25.0,
                    plannedIntervalMs = 25.0,
                    intervalState = "steady",
                    tokenBytes = 8,
                )
            },
            artifactBytes = 0L,
            artifactChunkBytes = 0,
            plannedDurationMs = tokenCount * 25.0 + 200.0,
            interTaskGapMs = 1_500.0,
        )

        val full = BehaviorTracePlan(
            contractVersion = "aneb-behavior-trace-v1",
            modelId = "token-multimodal-standard",
            modelVersion = "1.0.0",
            seed = 42L,
            eventCount = 100,
            totalPlannedSpanMs = 1_380_000.0,
            tasks = listOf(
                makeTask(0, "t1", "text", 2_048L, 200),
                makeTask(1, "t2", "text", 4_096L, 200),
                makeTask(2, "d1", "document", 10_485_760L, 600),
                makeTask(3, "d2", "document", 1_048_576L, 500),
                makeTask(4, "i1", "image", 10_485_760L, 400),
                makeTask(5, "i2", "image", 5_242_880L, 300),
            ),
        )
        val quick = full.quickMultimodalSample(maxTokensPerTask = 96)

        assertEquals(listOf("text", "document", "image"), quick.tasks.map { it.workloadKind })
        assertEquals(listOf("t1", "d2", "i2"), quick.tasks.map { it.taskId })
        assertTrue(quick.tasks.all { it.tokenPlans.size <= 96 })
        assertTrue(quick.tasks.all { it.uploadChunkCadenceMs == 0.0 && it.interTaskGapMs == 0.0 })
    }

    @Test
    fun `HomeScreen clearly frames concurrent load mode and standalone specialty cards`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = controller.get()
        val composeView = ComposeView(activity).apply {
            setContent {
                AnebTheme(reducedMotion = true) {
                    HomeScreen(
                        lastRun = null,
                        lastBasicRun = null,
                        testMode = AnebTestMode.NETWORK_RESPONSIVENESS,
                        onTestModeChange = {},
                        running = false,
                        connectionLabel = "WiFi 网络",
                        nodeLabel = "阿里云深圳 miw2",
                        onStart = {},
                        onOpenServer = {},
                        onOpenPrototype = {},
                        onOpenLastResult = {},
                        onOpenLastBasicResult = {},
                        onOpenVoice = {},
                        showPrototypeEntry = false,
                        onOpenAppCompanion = {},
                        onOpenProfiles = {},
                        onOpenReachBoard = {},
                    )
                }
            }
        }
        try {
            activity.setContentView(composeView)
            controller.setup()
            idleMain()
            val texts = collectTexts(composeView)
            assertTrue("expected human-friendly concurrent load title: $texts", texts.any { it == "高负载并发" })
            assertTrue("expected clear concurrent step preview: $texts", texts.any { it.contains("02 边下载边交互") })
            assertTrue("expected explicit standalone header: $texts", texts.any { it.contains("独立专项实测（不随上方切换）") })
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `TestingScreen renders top 22Hz telemetry deck and fixed-height authentic chat workspace`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = controller.get()
        val telemetry = LiveTelemetry(
            rttMs = 38.0,
            jitterMs = 4.2,
            ttftMs = 420.0,
            itlRecentMs = listOf(22.0, 28.0, 310.0, 24.0),
            itlMedianMs = 26.0,
            stallCount = 1,
            tokensReceived = 18,
            streamArrivalRatePerSec = 42.0,
            fastStreamRatePerSec = 46.8,
            liveRecentIntervalsMs = listOf(19.5, 22.1, 25.0, 340.0, 21.0),
            streamTargetRatePerSec = 40.0,
            streamActive = true,
            liveStreamEvents = 25,
            liveStreamGapMs = 340.0,
            liveSubPhase = "stream",
            phase = "s1_chat#0",
            fraction = 0.35,
        )
        val composeView = ComposeView(activity).apply {
            setContent {
                AnebTheme(reducedMotion = true) {
                    TestingScreen(
                        logs = listOf("SCENARIO_START scenario=s1_chat#0 round=0 order_index=0"),
                        telemetry = telemetry,
                        nodeLabel = "miw2",
                        radioEvidenceLimited = false,
                        onCancel = {},
                    )
                }
            }
        }
        try {
            activity.setContentView(composeView)
            controller.setup()
            idleMain()
            val texts = collectTexts(composeView)
            assertTrue("expected 22Hz telemetry console: $texts", texts.any { it.contains("22Hz TELEMETRY") })
            assertTrue("expected 250ms fast SSE rate: $texts", texts.any { it.contains("46.8") })
            assertTrue("expected authentic AI assistant header: $texts", texts.any { it.contains("AI 对话助手 · DeepThink-R1") })
            assertTrue("expected live stall badge: $texts", texts.any { it.contains("流式停顿 340 ms") })
            assertTrue("expected realistic executive chat copy: $texts", texts.any { it.contains("高管决策摘要") })
            assertFalse("must not show fake meta-text: $texts", texts.any { it.contains("正在实时监测首字响应") })
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `VoiceScreen renders studio 32-band audio spectrum direction status and 400ms veto line`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = controller.get()
        val sample = VoiceRunner.Sample(
            phase = VoiceRunner.Phase.Turns,
            rttMs = 48.0,
            jitterMs = 3.5,
            upFrameJitterMs = 2.8,
            downFrameJitterMs = null,
            mouthEarBudgetMs = null,
            framesSent = 225,
            framesRecv = 300,
            progress = 0.65f,
            ttfbP50Ms = 115.0,
            mouthEarProxyMs = 218.0,
            turnsOk = 3,
            caliber = VoiceRunner.SIM_CALIBER,
            m7MaxFrameGapMs = 64.0,
            liveTurnHistory = listOf(
                VoiceRunner.TurnLivePoint(0, false, 210.0, 110.0, 40.0, null, true),
                VoiceRunner.TurnLivePoint(1, false, 224.0, 118.0, 48.0, null, true),
                VoiceRunner.TurnLivePoint(3, true, 236.0, 125.0, 64.0, 72.0, true),
            ),
            totalPlannedTurns = 8,
            liveDirection = "DOWNLINK",
            liveActiveTurnIndex = 3,
            liveFrameIntervalsMs = listOf(19.8, 20.4, 21.1, 52.0, 20.2, 19.6),
            liveInstantJitterMs = 6.4,
        )
        val composeView = ComposeView(activity).apply {
            setContent {
                AnebTheme(reducedMotion = true) {
                    VoiceScreen(
                        nodeLabel = "阿里云深圳 miw2",
                        sample = sample,
                        running = true,
                        otherRunActive = false,
                        fellBackToPacedProxy = false,
                        statusNotice = null,
                        onStart = {},
                        onCancel = {},
                        contSample = null,
                        contRunning = false,
                        onStartContinuity = {},
                        onCancelContinuity = {},
                        recentVoice = emptyList(),
                        exportStatus = null,
                        onExportJson = {},
                        onExportCsv = {},
                        onBack = {},
                    )
                }
            }
        }
        try {
            activity.setContentView(composeView)
            controller.setup()
            idleMain()
            val texts = collectTexts(composeView)
            assertTrue("expected studio spectrum HUD: $texts", texts.any { it.contains("LIVE · 25Hz SPECTRUM") })
            assertTrue("expected frequency calibration scale: $texts", texts.any { it == "125Hz" } && texts.any { it == "8kHz" })
            assertTrue("expected live direction label: $texts", texts.any { it.contains("第 4 轮 · AI 回复中") })
            assertTrue("expected real-time jitter readout: $texts", texts.any { it.contains("实时抖动 6.4 ms") })
            assertTrue("expected 400ms veto line legend: $texts", texts.any { it.contains("400ms") })
            assertTrue("expected live turn mouth-to-ear reading: $texts", texts.any { it == "210" })
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `MultimodalTheaterScreen renders top 25Hz telemetry deck and fixed-height real drone workspace`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = controller.get()
        var started = false
        val progress = BehaviorTraceRunner.LiveTraceProgress(
            totalTasks = 3,
            plannedKinds = listOf("text", "document", "image"),
            currentTaskIndex = 2,
            currentTaskId = "task-0019",
            currentWorkloadKind = "image",
            currentStep = "upload",
            currentUploadBytes = 5_242_880L,
            uploadedBytes = 2_621_440L,
            liveUploadMbps = 32.5,
            currentArtifactBytes = 65_536L,
            liveSpeedHistory = listOf(22.0, 29.4, 35.1, 32.5),
            liveIntervalHistoryMs = listOf(12.0, 18.5, 9.2, 24.0),
            liveInstantJitterMs = 6.5,
            echoP50Ms = 42.0,
            echoJitterMs = 5.0,
        )
        val composeView = ComposeView(activity).apply {
            setContent {
                AnebTheme(reducedMotion = true) {
                    MultimodalTheaterScreen(
                        nodeLabel = "阿里云深圳 miw2",
                        quickMode = true,
                        onQuickModeChange = {},
                        running = true,
                        otherRunActive = false,
                        liveProgress = progress,
                        result = null,
                        logs = listOf("V2_RUN_START tasks=3"),
                        error = null,
                        onStart = { started = true },
                        onCancel = {},
                        onOpenCatalog = {},
                        onBack = {},
                    )
                }
            }
        }
        try {
            activity.setContentView(composeView)
            controller.setup()
            idleMain()
            val texts = collectTexts(composeView)
            assertTrue("expected 25Hz live waveform deck: $texts", texts.any { it.contains("25Hz LIVE WAVEFORM") })
            assertTrue("expected quick mode pill: $texts", texts.any { it.contains("快速三模态（~25s）") })
            assertTrue("expected real drone image task header: $texts", texts.any { it.contains("高清图片多模态分析") })
            assertTrue("expected real drone filename: $texts", texts.any { it.contains("SZBAY_MAVIC3_4K_0914.jpg") })
            assertTrue("expected live upload Mbps readout: $texts", texts.any { it.contains("32.5") })
            assertTrue("expected live 2.50 MB / 5.00 MB progress: $texts", texts.any { it.contains("2.50 MB / 5.00 MB") })
            assertTrue(!started)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `BasicSpeedTestingScreen and LoadedResponsivenessScreen explain purpose and actionable conclusions`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = controller.get()
        val telemetry = BasicSpeedTelemetry(
            phase = BasicSpeedPhase.DOWNLOAD,
            currentMbps = 128.4,
            phaseAverageMbps = 115.0,
            pingMs = 35.0,
            jitterMs = 3.0,
            requestLossRate = 0.0,
            progress = 0.45,
            historyMbps = listOf(80.0, 110.0, 128.4),
            isResponsivenessMode = true,
            liveLoadedRttMs = 120.0,
            loadedRttHistoryMs = listOf(45.0, 88.0, 120.0),
        )
        val composeView = ComposeView(activity).apply {
            setContent {
                AnebTheme(reducedMotion = true) {
                    BasicSpeedTestingScreen(
                        telemetry = telemetry,
                        nodeLabel = "阿里云深圳 miw2",
                        onCancel = {},
                    )
                }
            }
        }
        try {
            activity.setContentView(composeView)
            controller.setup()
            idleMain()
            val texts = collectTexts(composeView)
            assertTrue("expected concurrent load oscilloscope: $texts", texts.any { it.contains("高负载并发延迟监控") })
            assertTrue("expected plain-language phase purpose: $texts", texts.any { it.contains("边满载下载/刷高清视频边聊 AI") })
            assertTrue("expected +85 ms inflation badge: $texts", texts.any { it.contains("+85 ms") })
        } finally {
            controller.destroy()
        }

        // Also verify LoadedResponsivenessScreen explains why & renders 3 direct conclusions
        fun makeStage(name: String, startUs: Long, rttUs: Long, bytes: Long): ResponsivenessStage {
            val obs = if (bytes > 0L) {
                buildList {
                    add(ResponsivenessByteObservation(atUs = startUs + 100_000L, totalBytes = 100_000L))
                    for (idx in 0 until 5) {
                        val t0 = startUs + 500_000L + idx * 300_000L
                        add(ResponsivenessByteObservation(atUs = t0 + rttUs / 2, totalBytes = (idx + 2L) * 250_000L))
                    }
                    add(ResponsivenessByteObservation(atUs = startUs + 2_900_000L, totalBytes = bytes))
                }
            } else emptyList()
            val samples = (0 until 5).map { idx ->
                val t0 = startUs + 500_000L + idx * 300_000L
                val t3 = t0 + rttUs
                val b0 = obs.lastOrNull { it.atUs < t0 }?.totalBytes ?: 0L
                val b1 = obs.lastOrNull { it.atUs <= t3 }?.totalBytes ?: 0L
                ResponsivenessEcho(
                    stage = name,
                    sequence = idx,
                    t0Us = t0,
                    t1Us = t0 + rttUs / 2,
                    t2Us = t0 + rttUs / 2,
                    t3Us = t3,
                    httpCode = 200,
                    error = null,
                    bytesBefore = b0,
                    bytesAfter = b1,
                )
            }
            return ResponsivenessStage(
                stage = name,
                startUs = startUs,
                endUs = startUs + 3_000_000L,
                transferBytes = bytes,
                samples = samples,
                byteObservations = obs,
            )
        }
        val report = LoadedResponsivenessReport(
            runId = "resp-ui-1",
            serverBase = "https://120.77.174.207:8443",
            transport = "wifi",
            status = "completed",
            stages = listOf(
                makeStage("idle", 0L, 30_000L, 0L),
                makeStage("download", 4_000_000L, 58_000L, 2_000_000L),
                makeStage("upload", 8_000_000L, 210_000L, 2_000_000L),
            ),
        )
        val result = BasicSpeedResult(
            runId = "resp-ui-1",
            startedAtEpochMs = 1_750_000_000_000L,
            serverBase = "https://120.77.174.207:8443",
            profileVersion = "0.2.0",
            status = "completed",
            downloadMbps = 120.0,
            uploadMbps = 35.0,
            pingMs = 32.0,
            jitterMs = 3.0,
            requestLossRate = 0.0,
            postLoadPingMs = 36.0,
            downloadBytes = 10_000_000L,
            uploadBytes = 5_000_000L,
            transferErrors = emptyList(),
            profileId = NetworkSpeedEngine.RESPONSIVENESS_PROFILE_ID,
            responsivenessJson = LoadedResponsiveness.encode(report),
        )
        val controller2 = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity2 = controller2.get()
        val composeView2 = ComposeView(activity2).apply {
            setContent {
                AnebTheme(reducedMotion = true) {
                    LoadedResponsivenessScreen(result = result, onBack = {})
                }
            }
        }
        try {
            activity2.setContentView(composeView2)
            controller2.setup()
            idleMain()
            val texts2 = collectTexts(composeView2)
            assertTrue("expected human title: $texts2", texts2.any { it == "高负载并发" })
            assertTrue("expected why + 3 conclusions heading: $texts2", texts2.any { it.contains("为什么测高负载并发？3 条直接结论") })
            assertTrue("expected download scenario verdict: $texts2", texts2.any { it.contains("边下载 / 刷高清视频边聊 AI") })
            assertTrue("expected upload bottleneck advice: $texts2", texts2.any { it.contains("主要瓶颈在上行队列膨胀") })
        } finally {
            controller2.destroy()
        }
    }

    @Test
    fun `TestingScreen renders authentic Coding Agent IDE diff and real 4K DC rack multimodal inspection`() {
        // 1) Coding Agent
        val controller1 = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity1 = controller1.get()
        val codingView = ComposeView(activity1).apply {
            setContent {
                AnebTheme(reducedMotion = true) {
                    TestingScreen(
                        logs = listOf("SCENARIO_START scenario=s2_coding_agent#0 round=0 order_index=1"),
                        telemetry = LiveTelemetry(
                            rttMs = 34.0,
                            jitterMs = 2.8,
                            streamActive = true,
                            fastStreamRatePerSec = 52.4,
                            liveStreamEvents = 30,
                            liveToolRound = 2,
                            liveToolTotalRounds = 4,
                            liveSubPhase = "stream",
                            phase = "s2_coding_agent#0",
                            fraction = 0.55,
                        ),
                        nodeLabel = "miw2",
                        radioEvidenceLimited = false,
                        onCancel = {},
                    )
                }
            }
        }
        try {
            activity1.setContentView(codingView)
            controller1.setup()
            idleMain()
            val texts = collectTexts(codingView)
            assertTrue("expected Coding Agent IDE header: $texts", texts.any { it.contains("ConnectionPoolGuard.kt · Coding Agent") })
            assertTrue("expected tool chain strip: $texts", texts.any { it.contains("AGENT TOOL CHAIN") })
            assertTrue("expected fast SSE rate: $texts", texts.any { it.contains("52.4") })
        } finally {
            controller1.destroy()
        }

        // 2) Multimodal DC Rack real photo inspection
        val controller2 = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity2 = controller2.get()
        val mmView = ComposeView(activity2).apply {
            setContent {
                AnebTheme(reducedMotion = true) {
                    TestingScreen(
                        logs = listOf("SCENARIO_START scenario=s3_multimodal#0 round=0 order_index=2"),
                        telemetry = LiveTelemetry(
                            rttMs = 36.0,
                            jitterMs = 3.1,
                            upMbps = 44.2,
                            streamActive = true,
                            fastStreamRatePerSec = 39.5,
                            liveStreamEvents = 24,
                            liveUploadProgress = 1.0f,
                            liveSubPhase = "stream",
                            phase = "s3_multimodal#0",
                            fraction = 0.85,
                        ),
                        nodeLabel = "miw2",
                        radioEvidenceLimited = false,
                        onCancel = {},
                    )
                }
            }
        }
        try {
            activity2.setContentView(mmView)
            controller2.setup()
            idleMain()
            val texts = collectTexts(mmView)
            assertTrue("expected DC rack vision header: $texts", texts.any { it.contains("机房光路 AI 视觉巡检 · 多模态工作台") })
            assertTrue("expected real photo filename: $texts", texts.any { it.contains("DC_B04_U28_FIBER_4K.jpg") })
            assertTrue("expected AI Ops inspection report: $texts", texts.any { it.contains("AI Ops 现场光路视觉巡检报告") })
        } finally {
            controller2.destroy()
        }

        // 3) LiveStreamWindow 250ms fastRatePerSec & recentIntervalsMs
        val win = LiveStreamWindow()
        win.onStreamStarted(40.0, 1_000_000_000L)
        for (k in 0..8) {
            win.onEventArrival(1_000_000_000L + k * 20_000_000L)
        }
        val snap = win.snapshot(1_170_000_000L)
        assertTrue("expected fast 250ms rate before 250ms 1s floor: $snap", snap.fastRatePerSec != null && snap.fastRatePerSec!! > 20.0)
        assertEquals(8, snap.recentIntervalsMs.size)
        assertEquals(20.0, snap.recentIntervalsMs.first(), 0.5)
    }

    @Test
    fun `RealisticScenarioCatalog rotates across diverse business cases and scales full coding diffs`() {
        val chatPicked = (1..20).map { RealisticScenarioCatalog.pickIndex("run-uuid-$it", RealisticScenarioCatalog.chatCases.size) }.toSet()
        val codePicked = (1..20).map { RealisticScenarioCatalog.pickIndex("run-uuid-$it", RealisticScenarioCatalog.codingCases.size) }.toSet()
        val visionPicked = (1..20).map { RealisticScenarioCatalog.pickIndex("run-uuid-$it", RealisticScenarioCatalog.multimodalVisionCases.size) }.toSet()
        assertTrue("expected multiple rotating chat cases: $chatPicked", chatPicked.size >= 3)
        assertTrue("expected multiple rotating coding cases: $codePicked", codePicked.size >= 3)
        assertTrue("expected all 3 real 4K vision cases to rotate: $visionPicked", visionPicked.size == 3)
        assertTrue(
            "coding diffs must be deep enough for full SSE stream pacing",
            RealisticScenarioCatalog.codingCases.all { it.diffLines.size >= 45 && it.totalChars >= 1_400 },
        )
    }

    @Test
    fun `VideoStreamingEngine ABR state machine gates playback rebuffering swipe TTFF and renders Phase B theater`() {
        val sm = VideoStreamingEngine.AbrBufferStateMachine(VideoStreamingEngine.AbrTier.HD_1080P)
        assertEquals(VideoStreamingEngine.PlaybackState.COLD_START, sm.state)

        // Feed fast 12 Mbps bytes (~150 KB per 100ms) -> fills 800ms startup buffer and upgrades to 1080P60
        for (step in 1..20) {
            sm.step(deltaTimeMs = 100.0, deltaBytes = 150_000L, liveMbps = 12.0, elapsedMs = step * 100L)
        }
        assertEquals(VideoStreamingEngine.PlaybackState.PLAYING, sm.state)
        assertTrue("expected fast cold-start TTFF: ${sm.firstFrameMs}", sm.firstFrameMs != null && sm.firstFrameMs!! <= 500.0)
        assertEquals(VideoStreamingEngine.AbrTier.UHD_1080P60, sm.tier)

        // Swipe to next video -> flushes buffer and measures fresh cold-start TTFF
        sm.triggerSwipe(currentElapsedMs = 2_000L)
        assertEquals(VideoStreamingEngine.PlaybackState.COLD_START, sm.state)
        assertEquals(0.0, sm.bufferMs, 1e-6)
        for (step in 1..8) {
            sm.step(deltaTimeMs = 100.0, deltaBytes = 150_000L, liveMbps = 12.0, elapsedMs = 2_000L + step * 100L)
        }
        assertEquals(VideoStreamingEngine.PlaybackState.PLAYING, sm.state)
        assertTrue("expected swipe TTFF recorded: ${sm.latestSwipeTtffMs}", sm.latestSwipeTtffMs != null)

        // Starve network completely -> buffer empties -> REBUFFERING + stallCount + ABR downgrade
        for (step in 1..40) {
            sm.step(deltaTimeMs = 200.0, deltaBytes = 0L, liveMbps = 0.2, elapsedMs = 3_000L + step * 200L)
        }
        assertEquals(VideoStreamingEngine.PlaybackState.REBUFFERING, sm.state)
        assertTrue("expected stall count >= 1: ${sm.stallCount}", sm.stallCount >= 1)
        assertEquals(VideoStreamingEngine.AbrTier.LOW_480P, sm.tier)

        // Verify Phase B VideoStreamingTheaterScreen renders live HUD cleanly under Robolectric
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = controller.get()
        val snap = VideoStreamingEngine.LiveSnapshot(
            state = VideoStreamingEngine.PlaybackState.PLAYING,
            activeTier = VideoStreamingEngine.AbrTier.UHD_1080P60,
            videoChapterIndex = 0,
            elapsedMs = 6_400L,
            plannedDurationMs = 20_000L,
            liveMbps = 28.4,
            avgMbps = 25.1,
            rttMs = 34.0,
            jitterMs = 2.6,
            lossRate = 0.0,
            bufferMs = 5_200.0,
            firstFrameMs = 280.0,
            latestSwipeTtffMs = 310.0,
            swipeCount = 1,
            stallCount = 0,
            totalStallMs = 0L,
            totalBytes = 18_500_000L,
            speedHistoryMbps = listOf(18.0, 24.5, 29.1, 28.4),
            rttHistoryMs = listOf(32.0, 35.0, 34.0),
        )
        val composeView = ComposeView(activity).apply {
            setContent {
                AnebTheme(reducedMotion = true) {
                    VideoStreamingTheaterScreen(
                        nodeLabel = "阿里云深圳 miw2",
                        running = true,
                        otherRunActive = false,
                        snapshot = snap,
                        error = null,
                        onStart = {},
                        onCancel = {},
                        onSwipeNext = {},
                        onBack = {},
                    )
                }
            }
        }
        try {
            activity.setContentView(composeView)
            controller.setup()
            idleMain()
            val texts = collectTexts(composeView)
            assertTrue("expected Phase B video theater title: $texts", texts.any { it.contains("短视频流媒体实时压测") })
            assertTrue("expected 20Hz video stream HUD: $texts", texts.any { it.contains("20Hz VIDEO STREAM TELEMETRY") })
            assertTrue("expected active 1080P60 ABR pill: $texts", texts.any { it.contains("1080P 60fps 蓝光") })
            assertTrue("expected swipe button: $texts", texts.any { it.contains("模拟上滑切视频") })
        } finally {
            controller.destroy()
        }
    }

    private fun idleMain() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
    }

    private fun findViewRootForTest(root: View): ViewRootForTest? {
        if (root is ViewRootForTest) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findViewRootForTest(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun collectSemanticsNodesDfs(root: View): List<SemanticsNode> {
        val viewRoot = findViewRootForTest(root) ?: return emptyList()
        val out = mutableListOf<SemanticsNode>()
        fun visit(node: SemanticsNode) {
            out += node
            node.children.forEach { visit(it) }
        }
        visit(viewRoot.semanticsOwner.unmergedRootSemanticsNode)
        return out
    }

    private fun nodeTexts(node: SemanticsNode): List<String> = buildList {
        node.config.getOrNull(SemanticsProperties.Text)?.forEach { add(it.text) }
        node.config.getOrNull(SemanticsProperties.EditableText)?.let { add(it.text) }
    }

    private fun collectTexts(root: View): List<String> =
        collectSemanticsNodesDfs(root).flatMap { nodeTexts(it) }.filter { it.isNotEmpty() }
}
