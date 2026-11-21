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
import com.aneb.probe.engine.ProfilePhase
import com.aneb.probe.engine.ProfilePresentation
import com.aneb.probe.engine.ScenarioProfile
import com.aneb.probe.ui.theme.AnebTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h1800dp-xxhdpi")
class ProfileCatalogLaunchUiTest {

    @Test
    fun `ProfileCatalogScreen exposes direct launch controls for voice basic responsiveness and token profiles`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = controller.get()

        val launchedModes = mutableListOf<AnebTestMode>()
        var openedVoice = false
        val validPresentation = ProfilePresentation(
            liveMetricId = "throughput_mbps",
            liveMetricLabel = "实时速率",
            liveMetricUnit = "Mbps",
            liveWindowMs = 1_000,
            uiRefreshMs = 100,
            metricIds = listOf("download_mbps", "upload_mbps"),
            conclusionPolicyId = "network-basic-conclusions-v1",
        )
        val profiles = listOf(
            ScenarioProfile(
                profileId = "basic_network",
                version = "0.1.0",
                modeId = ScenarioProfile.MODE_NETWORK_BASIC,
                description = "基础测速",
                phases = listOf(ProfilePhase(type = ProfilePhase.TYPE_CLOCK_SYNC)),
                presentation = validPresentation,
            ),
            ScenarioProfile(
                profileId = "s1_chat",
                version = "0.1.0",
                modeId = ScenarioProfile.MODE_TOKEN_EXPERIENCE,
                description = "AI 对话流",
                phases = listOf(ProfilePhase(type = ProfilePhase.TYPE_TOKEN_STREAM)),
                presentation = validPresentation.copy(
                    liveMetricId = "token_arrival_rate",
                    liveMetricLabel = "Token 速率",
                    liveMetricUnit = "tok/s",
                    metricIds = listOf("T1", "T2", "T3"),
                    conclusionPolicyId = "aqs-v0.1",
                ),
            ),
        )

        val composeView = ComposeView(activity).apply {
            setContent {
                AnebTheme(reducedMotion = true) {
                    ProfileCatalogScreen(
                        profiles = profiles,
                        source = "server",
                        warnings = emptyList(),
                        loading = false,
                        error = null,
                        onRefresh = {},
                        onBack = {},
                        onStartTestMode = { launchedModes += it },
                        onOpenVoice = { openedVoice = true },
                    )
                }
            }
        }
        try {
            activity.setContentView(composeView)
            controller.setup()
            idleMain()

            assertTrue(clickNodeWithText(composeView, "发起实时语音测试"))
            idleMain()
            assertTrue("voice profile launch button must fire onOpenVoice", openedVoice)

            assertTrue(clickNodeWithText(composeView, "发起基础测速"))
            assertTrue(clickNodeWithText(composeView, "高负载并发"))
            assertTrue(clickNodeWithText(composeView, "发起 AI 体验测试（含本场景）"))
            idleMain()
            assertEquals(
                listOf(
                    AnebTestMode.NETWORK_BASIC,
                    AnebTestMode.NETWORK_RESPONSIVENESS,
                    AnebTestMode.TOKEN_EXPERIENCE,
                ),
                launchedModes,
            )
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
