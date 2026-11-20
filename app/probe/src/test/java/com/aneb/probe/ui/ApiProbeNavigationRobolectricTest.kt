package com.aneb.probe.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.aneb.probe.apiprobe.LlmProvider
import com.aneb.probe.apiprobe.ProviderPresets
import com.aneb.probe.engine.TestEngine
import com.aneb.probe.ui.components.AnebTabBar
import com.aneb.probe.ui.components.MainTab
import com.aneb.probe.ui.theme.AnebTheme
import com.aneb.probe.ui.theme.LocalReducedMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ApiProbeNavigationRobolectricTest {

    @Test
    fun `AnebTabBar renders exactly 4 tabs without Probe and selects Settings`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = controller.get()

        var selected = MainTab.Test
        val composeView = ComposeView(activity).apply {
            setContent {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    AnebTheme {
                        AnebTabBar(current = selected, onSelect = { selected = it })
                    }
                }
            }
        }
        activity.setContentView(composeView)
        controller.setup()
        idleMain()

        val texts = collectTexts(composeView)
        assertEquals(listOf("测试", "结果", "地图", "设置"), texts)
        assertFalse(texts.contains("探针"))

        assertTrue(clickNodeWithText(composeView, "设置"))
        idleMain()
        assertEquals(MainTab.Settings, selected)
    }

    @Test
    fun `SettingsScreen places API 探针 under 高级 above 自定义服务器地址`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = controller.get()

        var openedApiProbe = false
        val composeView = ComposeView(activity).apply {
            setContent {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    AnebTheme {
                        SettingsScreen(
                            serverUrl = ProbeSettings.DEFAULT_SERVER_URL,
                            onServerUrlChange = {},
                            mode = TestEngine.Mode.QUICK,
                            onModeChange = {},
                            transport = TestEngine.TransportMode.AUTO,
                            onTransportChange = {},
                            driveTest = false,
                            onDriveTestChange = {},
                            injectActive = null,
                            onOpenServer = {},
                            onOpenApiProbe = { openedApiProbe = true },
                            onOpenReachBoard = {},
                            onOpenProfiles = {},
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

        val texts = collectTexts(composeView)
        val networkToolsIdx = texts.indexOfFirst { it.equals("网络与工具", ignoreCase = true) }
        val transportIdx = texts.indexOf("传输通道")
        val gpsIdx = texts.indexOf("GPS 路测")
        val advancedIdx = texts.indexOfFirst { it.equals("高级", ignoreCase = true) }
        val apiProbeIdx = texts.indexOf("API 探针")
        val customServerIdx = texts.indexOf("自定义服务器地址")

        assertTrue("networkToolsIdx=$networkToolsIdx texts=$texts", networkToolsIdx >= 0)
        assertTrue(transportIdx > networkToolsIdx)
        assertTrue(gpsIdx > transportIdx)
        assertTrue(advancedIdx > gpsIdx)
        assertTrue(apiProbeIdx > advancedIdx)
        assertTrue(customServerIdx > apiProbeIdx)

        assertTrue(clickNodeWithText(composeView, "API 探针"))
        idleMain()
        assertTrue(openedApiProbe)
    }

    @Test
    fun `Settings to ApiProbe round trip supports both top-bar back and system BackHandler without finishing`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = controller.get()

        val composeView = ComposeView(activity).apply {
            setContent {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    AnebTheme {
                        var onApiProbe by remember { mutableStateOf(false) }
                        var tab by remember { mutableStateOf(MainTab.Test) }
                        if (onApiProbe) {
                            ApiProbeScreen(
                                provider = LlmProvider.OPENAI_COMPAT,
                                onProviderChange = {},
                                baseUrl = "https://example.invalid/v1",
                                onBaseUrlChange = {},
                                model = "test-model",
                                onModelChange = {},
                                keyInput = "",
                                onKeyInputChange = {},
                                hasStoredKey = false,
                                keyStoreEncrypted = true,
                                onSaveConfig = {},
                                onClearKey = {},
                                running = false,
                                onRun = {},
                                logs = remember { mutableStateListOf() },
                                results = emptyList(),
                                exportStatus = null,
                                onExport = {},
                                presets = ProviderPresets.all,
                                selectedPresetId = null,
                                onSelectPreset = {},
                                onOpenReachBoard = {},
                                onBack = { onApiProbe = false },
                                showBack = true,
                            )
                        } else {
                            Box {
                                if (tab == MainTab.Settings) {
                                    SettingsScreen(
                                        serverUrl = ProbeSettings.DEFAULT_SERVER_URL,
                                        onServerUrlChange = {},
                                        mode = TestEngine.Mode.QUICK,
                                        onModeChange = {},
                                        transport = TestEngine.TransportMode.AUTO,
                                        onTransportChange = {},
                                        driveTest = false,
                                        onDriveTestChange = {},
                                        injectActive = null,
                                        onOpenServer = {},
                                        onOpenApiProbe = { onApiProbe = true },
                                        onOpenReachBoard = {},
                                        onOpenProfiles = {},
                                        onBack = { tab = MainTab.Test },
                                        showBack = false,
                                    )
                                }
                                AnebTabBar(
                                    current = tab,
                                    onSelect = { tab = it },
                                    modifier = Modifier.align(Alignment.BottomCenter),
                                )
                            }
                        }
                    }
                }
            }
        }
        activity.setContentView(composeView)
        controller.setup()
        idleMain()

        // 1. Switch bottom bar to 设置
        assertTrue(clickNodeWithText(composeView, "设置"))
        idleMain()
        assertTrue(collectTexts(composeView).contains("API 探针"))

        // 2. Enter API 探针 from 设置 -> 高级
        assertTrue(clickNodeWithText(composeView, "API 探针"))
        idleMain()
        val probeTexts = collectTexts(composeView)
        assertTrue(probeTexts.contains("ENDPOINT MONITOR"))
        assertTrue(probeTexts.contains("‹"))
        assertFalse(probeTexts.contains("测试"))

        // 3. Top-bar ‹ returns to 设置 with bottom bar restored
        assertTrue(clickNodeWithText(composeView, "‹"))
        idleMain()
        var backTexts = collectTexts(composeView)
        assertTrue(backTexts.contains("自定义服务器地址"))
        assertTrue(backTexts.contains("测试"))

        // 4. Re-enter API 探针 and press system back -> BackHandler returns cleanly without finishing Activity
        assertTrue(clickNodeWithText(composeView, "API 探针"))
        idleMain()
        assertTrue(collectTexts(composeView).contains("ENDPOINT MONITOR"))

        activity.onBackPressedDispatcher.onBackPressed()
        idleMain()
        assertFalse(activity.isFinishing)
        backTexts = collectTexts(composeView)
        assertTrue(backTexts.contains("自定义服务器地址"))
        assertTrue(backTexts.contains("设置"))
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

    private fun clickNodeWithText(root: View, text: String): Boolean {
        val match = collectSemanticsNodesDfs(root).firstOrNull { node ->
            nodeTexts(node).any { it == text }
        } ?: return false
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
}
