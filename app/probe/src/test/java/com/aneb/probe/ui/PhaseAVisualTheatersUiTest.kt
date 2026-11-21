package com.aneb.probe.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.aneb.probe.engine.AnebTestMode
import com.aneb.probe.engine.BasicSpeedPhase
import com.aneb.probe.engine.BasicSpeedTelemetry
import com.aneb.probe.engine.BehaviorTraceRunner
import com.aneb.probe.engine.LiveTelemetry
import com.aneb.probe.engine.VoiceRunner
import com.aneb.probe.ui.theme.AnebTheme
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h1200dp-xxhdpi")
class PhaseAVisualTheatersUiTest {

    @Test
    fun `HomeScreen clearly separates top mode preview strip from standalone specialty cards`() {
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
            assertTrue("expected responsiveness preview strip: $texts", texts.any { it.contains("满载下载+回声") })
            assertTrue("expected explicit standalone header: $texts", texts.any { it.contains("独立专项实测（不随上方切换）") })
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `TestingScreen renders live typewriter stream and stall badge from real SSE telemetry`() {
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
            streamTargetRatePerSec = 40.0,
            streamActive = true,
            liveStreamEvents = 25,
            liveStreamGapMs = 340.0,
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
            assertTrue("expected AI chat theater title: $texts", texts.any { it.contains("AI 对话流实时预览") })
            assertTrue("expected live stall badge: $texts", texts.any { it.contains("停顿 340ms") })
            assertTrue("expected revealed typewriter text: $texts", texts.any { it.contains("正在实时监测首字响应") })
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `VoiceScreen renders bidirectional cockpit with turn timeline and 400ms veto line`() {
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
            assertTrue("expected bidirectional cockpit header: $texts", texts.any { it.contains("实时双向语音示波座舱") })
            assertTrue("expected 400ms veto line legend: $texts", texts.any { it.contains("400ms") })
            assertTrue("expected live turn mouth-to-ear reading: $texts", texts.any { it == "210" })
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `MultimodalTheaterScreen renders active image payload stage 20-task matrix and start button`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = controller.get()
        var started = false
        val progress = BehaviorTraceRunner.LiveTraceProgress(
            totalTasks = 20,
            plannedKinds = List(8) { "text" } + List(6) { "document" } + List(6) { "image" },
            currentTaskIndex = 14,
            currentTaskId = "task-0015",
            currentWorkloadKind = "image",
            currentStep = "upload",
            currentUploadBytes = 2_097_152L,
            currentArtifactBytes = 65_536L,
            echoP50Ms = 42.0,
            echoJitterMs = 5.0,
        )
        val composeView = ComposeView(activity).apply {
            setContent {
                AnebTheme(reducedMotion = true) {
                    MultimodalTheaterScreen(
                        nodeLabel = "阿里云深圳 miw2",
                        running = true,
                        otherRunActive = false,
                        liveProgress = progress,
                        result = null,
                        logs = listOf("V2_RUN_START tasks=20"),
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
            assertTrue("expected active image payload stage: $texts", texts.any { it.contains("高清图多模态理解") })
            assertTrue("expected 20-task matrix header: $texts", texts.any { it.contains("20 任务多模态执行矩阵") })
            assertTrue("expected upload size display: $texts", texts.any { it.contains("2.0 MB") })
            assertTrue(!started)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `BasicSpeedTestingScreen renders live Bufferbloat oscilloscope in responsiveness mode`() {
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
            assertTrue("expected Bufferbloat oscilloscope: $texts", texts.any { it.contains("满载延迟膨胀示波器") })
            assertTrue("expected +85 ms inflation badge: $texts", texts.any { it.contains("+85 ms") })
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
