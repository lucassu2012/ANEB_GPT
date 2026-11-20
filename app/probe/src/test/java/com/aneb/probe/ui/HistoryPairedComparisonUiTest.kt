package com.aneb.probe.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.aneb.probe.data.BasicSpeedResultEntity
import com.aneb.probe.data.RadioSampleEntity
import com.aneb.probe.data.ScenarioResultEntity
import com.aneb.probe.data.TestRun
import com.aneb.probe.engine.NetworkSpeedEngine
import com.aneb.probe.ui.PairedComparisonFormat.PairedComparisonResult
import com.aneb.probe.ui.theme.AnebTheme
import com.aneb.probe.ui.theme.LocalReducedMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, qualifiers = "w411dp-h891dp-xxhdpi")
class HistoryPairedComparisonUiTest {

    private fun makeRun(id: String, startedAt: Long, transport: String, score: Double) = TestRun(
        runId = id,
        startedAtEpochMs = startedAt,
        serverBase = ProbeSettings.DEFAULT_SERVER_URL,
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
        aqsScore = score,
        aqsLowConfidence = false,
        aqsVetoApplied = false,
        aqsNotComputableReason = null,
        status = "completed",
        reportStatus = "http=200",
    )

    private fun makeScenarios(runId: String, netTransport: String, t1: Double, u1: Double): List<ScenarioResultEntity> {
        fun row(profileId: String) = ScenarioResultEntity(
            runId = runId,
            profileId = profileId,
            profileVersion = "1.0.0",
            repeatIndex = 0,
            orderIndex = 0,
            startedAtNanos = 1L,
            endedAtNanos = 2L,
            validity = "valid",
            invalidReasons = "",
            t1TtftMs = t1, t1Grade = null,
            t2ItlP95Ms = 65.0, t2Grade = null,
            t2ItlP95InclCoalescedMs = 68.0,
            t3StallRate = 0.01, t3Grade = null,
            t3StallRateInclResume = 0.01,
            t4SevereStallRate = 0.0, t4Grade = null,
            t5ResumeP95Ms = null,
            n1RttP50Ms = 24.0, n1Grade = null,
            n2JitterMs = 4.0, n2Grade = null,
            u1GoodputMbps = u1, u1Grade = null,
            u1GoodputExclSlowStartMbps = u1,
            u2ToolLoopP95Ms = 120.0, u2Grade = null,
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
        return listOf(row("s1_chat"), row("s2_coding_agent"), row("s3_multimodal"))
    }

    private fun makeNrSample(runId: String) = RadioSampleEntity(
        runId = runId,
        tsNanos = 1L,
        cellTsNanos = 1L,
        stale = false,
        subId = 1,
        subSwitched = false,
        networkType = "NR",
        overrideType = null,
        nrState = "connected",
        rat = "NR",
        pci = 1,
        tac = 1,
        arfcn = 1,
        rsrp = -84,
        rsrq = -8,
        sinr = 15,
        operatorName = "TestNR",
    )

    @Test
    fun `HistoryScreen renders Wi-Fi vs 5G quick pair card and dispatches Markdown and Share actions`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = controller.get()

        val wifiRun = makeRun("run-wifi", 2_000L, "wifi", 92.0)
        val nrRun = makeRun("run-nr", 1_000L, "cellular", 78.0)
        val scenarios = mapOf(
            "run-wifi" to makeScenarios("run-wifi", "wifi", 120.0, 48.0),
            "run-nr" to makeScenarios("run-nr", "cellular", 220.0, 26.0),
        )
        val radios = mapOf("run-nr" to listOf(makeNrSample("run-nr")))

        var exportedMd: PairedComparisonResult.Compatible? = null
        var sharedImage: PairedComparisonResult.Compatible? = null

        val composeView = ComposeView(activity).apply {
            setContent {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    AnebTheme {
                        HistoryScreen(
                            runs = listOf(wifiRun, nrRun),
                            basicRuns = emptyList(),
                            onOpen = {},
                            onOpenBasic = {},
                            onGenerateReport = {},
                            onBack = {},
                            showBack = false,
                            scenariosByRunId = scenarios,
                            radioSamplesByRunId = radios,
                            comparisonExportStatus = null,
                            onExportComparisonMarkdown = { exportedMd = it },
                            onShareComparisonImage = { sharedImage = it },
                        )
                    }
                }
            }
        }
        activity.setContentView(composeView)
        controller.setup()
        idleMain()

        val initialTexts = collectTexts(composeView)
        assertTrue(initialTexts.contains("对比两项"))
        assertTrue(initialTexts.contains("Wi-Fi vs 5G 对比"))

        assertTrue(clickNodeWithText(composeView, "Wi-Fi vs 5G 对比"))
        idleMain()

        val pairedTexts = collectTexts(composeView)
        assertTrue("pairedTexts=$pairedTexts", pairedTexts.any { it.contains("配对对比") })
        assertTrue(pairedTexts.any { it.contains("A · Wi-Fi") })
        assertTrue(pairedTexts.any { it.contains("B · 5G NR") })
        assertTrue(pairedTexts.contains("导出 MD"))
        assertTrue(pairedTexts.contains("分享图片"))

        assertTrue(clickNodeWithText(composeView, "导出 MD"))
        idleMain()
        assertNotNull(exportedMd)
        assertEquals("Wi-Fi", exportedMd!!.left.transportLabel)
        assertEquals("5G NR", exportedMd!!.right.transportLabel)

        assertTrue(clickNodeWithText(composeView, "分享图片"))
        idleMain()
        assertNotNull(sharedImage)

        // Swap flips A and B sides
        assertTrue(clickNodeWithText(composeView, "⇄ 对调"))
        idleMain()
        val swappedTexts = collectTexts(composeView)
        assertTrue(swappedTexts.any { it.contains("A · 5G NR") })
        assertTrue(swappedTexts.any { it.contains("B · Wi-Fi") })
    }

    @Test
    fun `HistoryScreen shows Mismatch card when selecting Token and Basic runs manually`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = controller.get()

        val tokenRun = makeRun("run-token", 2_000L, "wifi", 88.0)
        val basicRun = BasicSpeedResultEntity(
            runId = "run-basic",
            startedAtEpochMs = 1_000L,
            serverBase = ProbeSettings.DEFAULT_SERVER_URL,
            claimScope = "app",
            profileId = NetworkSpeedEngine.PROFILE_ID,
            profileVersion = "1.0.0",
            conclusionPolicyId = "basic_v1",
            status = "completed",
            downloadMbps = 100.0,
            uploadMbps = 30.0,
            pingMs = 20.0,
            jitterMs = 2.0,
            requestLossRate = 0.0,
            postLoadPingMs = 40.0,
            downloadBytes = 1_000_000L,
            uploadBytes = 500_000L,
            transferErrors = "",
        )

        val composeView = ComposeView(activity).apply {
            setContent {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    AnebTheme {
                        HistoryScreen(
                            runs = listOf(tokenRun),
                            basicRuns = listOf(basicRun),
                            onOpen = {},
                            onOpenBasic = {},
                            onGenerateReport = {},
                            onBack = {},
                            showBack = false,
                        )
                    }
                }
            }
        }
        activity.setContentView(composeView)
        controller.setup()
        idleMain()

        assertTrue(clickNodeWithText(composeView, "对比两项"))
        idleMain()
        assertTrue(collectTexts(composeView).contains("已选 0/2"))

        assertTrue(clickNodeContainingText(composeView, "WiFi · Node-E01"))
        idleMain()
        assertTrue(collectTexts(composeView).contains("已选 1/2"))

        assertTrue(clickNodeContainingText(composeView, "基础测速 · Node-E01"))
        idleMain()
        val mismatchTexts = collectTexts(composeView)
        assertTrue(mismatchTexts.any { it.contains("口径不一致") })
        assertTrue(mismatchTexts.any { it.contains("不可直接对比") })
    }

    @Test
    fun `MainActivity HistoryRoute wires Room v15 DAOs and export share actions without schema changes`() {
        val mainActivity = normalizedSource(
            "app/probe/src/main/java/com/aneb/probe/ui/MainActivity.kt",
            "src/main/java/com/aneb/probe/ui/MainActivity.kt",
            "../../app/probe/src/main/java/com/aneb/probe/ui/MainActivity.kt",
        )
        val database = normalizedSource(
            "app/probe/src/main/java/com/aneb/probe/data/AnebDatabase.kt",
            "src/main/java/com/aneb/probe/data/AnebDatabase.kt",
            "../../app/probe/src/main/java/com/aneb/probe/data/AnebDatabase.kt",
        )

        assertTrue(database.contains("version = 15"))
        assertTrue(mainActivity.contains("db.scenarioResultDao().all().groupBy { it.runId }"))
        assertTrue(mainActivity.contains("db.reportBodyDao().forRun(run.runId)"))
        assertTrue(mainActivity.contains("db.radioSampleDao().forRun(run.runId)"))
        assertTrue(mainActivity.contains("PairedComparisonFormat.buildMarkdown(compatible)"))
        assertTrue(mainActivity.contains("PairedComparisonShareCard.renderAndSave(applicationContext, model)"))
        assertTrue(mainActivity.contains("PairedComparisonShareCard.launchShare(applicationContext, saveOutcome.uri)"))
        assertFalse(mainActivity.contains("Toast.makeText"))
    }

    @Test
    fun `replacing 2nd item from scrolled long list scrolls back to top and clears stale export status`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = controller.get()

        val runs = (0 until 20).map { idx ->
            val transport = if (idx == 1) "cellular" else "wifi"
            makeRun(
                id = "run-${idx.toString().padStart(2, '0')}",
                startedAt = 1_752_000_000_000L + (19 - idx) * 60_000L,
                transport = transport,
                score = (99 - idx).toDouble(),
            )
        }
        val scenarios = runs.associate { run ->
            val net = if (run.runId == "run-01") "auto(cellular)" else "auto(wifi)"
            run.runId to makeScenarios(run.runId, net, 150.0, 40.0)
        }
        val radios = mapOf("run-01" to listOf(makeNrSample("run-01")))

        val composeView = ComposeView(activity).apply {
            setContent {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    AnebTheme {
                        var exportStatus by remember { mutableStateOf<String?>(null) }
                        HistoryScreen(
                            runs = runs,
                            basicRuns = emptyList(),
                            onOpen = {},
                            onOpenBasic = {},
                            onGenerateReport = {},
                            onBack = {},
                            showBack = false,
                            scenariosByRunId = scenarios,
                            radioSamplesByRunId = radios,
                            comparisonExportStatus = exportStatus,
                            onExportComparisonMarkdown = { compatible ->
                                exportStatus = "COMPARE_EXPORT pair=${compatible.left.entryKey}->${compatible.right.entryKey}"
                            },
                            onShareComparisonImage = {},
                            onClearComparisonStatus = { exportStatus = null },
                        )
                    }
                }
            }
        }
        activity.setContentView(composeView)
        controller.setup()
        idleMain()

        // 1. Quick-pair Wi-Fi vs 5G NR and export Markdown to populate comparisonExportStatus
        assertTrue(clickNodeWithText(composeView, "Wi-Fi vs 5G 对比"))
        idleMain()
        assertTrue(collectTexts(composeView).any { it.contains("配对对比") })

        assertTrue(clickNodeWithText(composeView, "导出 MD"))
        idleMain()
        assertTrue(collectTexts(composeView).any { it.contains("COMPARE_EXPORT pair=token:run-00->token:run-01") })

        // 2. Scroll the LazyColumn down until the top PairedComparisonCard leaves the viewport and row score "82" is visible
        for (step in 0 until 10) {
            if (!collectTexts(composeView).any { it.contains("配对对比") } &&
                collectTexts(composeView).contains("82")
            ) {
                break
            }
            assertTrue("LazyColumn should be scrollable at step $step", scrollLazyListBy(composeView, 600f))
            idleMain()
        }
        val scrolledTexts = collectTexts(composeView)
        assertFalse("PairedComparisonCard should be scrolled off-screen: $scrolledTexts", scrolledTexts.any { it.contains("配对对比") })
        assertTrue("Lower row score 82 should be visible after scrolling: $scrolledTexts", scrolledTexts.contains("82"))

        // 3. Tap the 3rd item (score 82 -> run-17), keeping selectedKeys.size == 2 while replacing selectedKeys[1]
        assertTrue(clickNodeWithText(composeView, "82"))
        idleMain()

        val afterReplaceTexts = collectTexts(composeView)
        assertTrue(
            "PairedComparisonCard should scroll back to top on 2nd-item replacement: $afterReplaceTexts",
            afterReplaceTexts.any { it.contains("配对对比") },
        )
        assertFalse(
            "Stale comparisonExportStatus must be cleared when pair changes: $afterReplaceTexts",
            afterReplaceTexts.any { it.contains("COMPARE_EXPORT") },
        )

        // 4. Re-export and verify swapping clears stale export status
        assertTrue(clickNodeWithText(composeView, "导出 MD"))
        idleMain()
        assertTrue(collectTexts(composeView).any { it.contains("COMPARE_EXPORT") })

        assertTrue(clickNodeWithText(composeView, "⇄ 对调"))
        idleMain()
        assertFalse(
            "Stale comparisonExportStatus must be cleared when swapping A/B: ${collectTexts(composeView)}",
            collectTexts(composeView).any { it.contains("COMPARE_EXPORT") },
        )
    }

    private fun scrollLazyListBy(root: View, deltaY: Float): Boolean {
        val scrollable = collectSemanticsNodesDfs(root).firstOrNull { node ->
            node.config.getOrNull(SemanticsActions.ScrollBy) != null
        } ?: return false
        return scrollable.config.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, deltaY) == true
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

    private fun clickNodeWithText(root: View, text: String): Boolean =
        clickMatchingNode(root) { node -> nodeTexts(node).any { it == text } }

    private fun clickNodeContainingText(root: View, substring: String): Boolean =
        clickMatchingNode(root) { node -> nodeTexts(node).any { it.contains(substring) } }

    private fun clickMatchingNode(root: View, predicate: (SemanticsNode) -> Boolean): Boolean {
        val match = collectSemanticsNodesDfs(root).firstOrNull(predicate) ?: return false
        var current: SemanticsNode? = match
        while (current != null) {
            val action = current.config.getOrNull(SemanticsActions.OnClick)?.action
            if (action != null) {
                return action.invoke()
            }
            current = current.parent
        }
        return false
    }

    private fun normalizedSource(vararg candidates: String): String {
        val path = candidates
            .map(Path::of)
            .firstOrNull(Files::isRegularFile)
            ?: error("source fixture was not found: ${candidates.first()}")
        return Files.readAllBytes(path)
            .toString(UTF_8)
            .replace(Regex("\\s+"), " ")
    }
}
