package com.aneb.probe.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.aneb.probe.engine.AnebTestMode
import com.aneb.probe.ui.theme.AnebTheme
import com.aneb.probe.ui.theme.LocalReducedMotion
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
class HomeScreenPrototypeEntryTest {

    @Test
    fun `shouldShowPrototypeEntry hides PROTOTYPE only on public release builds`() {
        assertFalse(
            shouldShowPrototypeEntry(
                isPrototypeRelease = false,
                isPrototypeEngineering = false,
                isDebug = false,
            ),
        )
        assertTrue(
            shouldShowPrototypeEntry(
                isPrototypeRelease = true,
                isPrototypeEngineering = false,
                isDebug = false,
            ),
        )
        assertTrue(
            shouldShowPrototypeEntry(
                isPrototypeRelease = false,
                isPrototypeEngineering = true,
                isDebug = false,
            ),
        )
        assertTrue(
            shouldShowPrototypeEntry(
                isPrototypeRelease = false,
                isPrototypeEngineering = false,
                isDebug = true,
            ),
        )
    }

    @Test
    fun `HomeScreen hides PROTOTYPE capsule when showPrototypeEntry is false`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = controller.get()

        var started = false
        val composeView = ComposeView(activity).apply {
            setContent {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    AnebTheme {
                        HomeScreen(
                            lastRun = null,
                            lastBasicRun = null,
                            testMode = AnebTestMode.TOKEN_EXPERIENCE,
                            onTestModeChange = {},
                            running = false,
                            notice = null,
                            connectionLabel = "自动选择网络",
                            nodeLabel = "阿里云深圳 miw2",
                            onStart = { started = true },
                            onOpenServer = {},
                            onOpenPrototype = {},
                            onOpenLastResult = {},
                            onOpenLastBasicResult = {},
                            onOpenVoice = {},
                            showPrototypeEntry = false,
                        )
                    }
                }
            }
        }
        activity.setContentView(composeView)
        controller.setup()
        idleMain()

        val texts = collectTexts(composeView)
        assertFalse("Public release HomeScreen must hide PROTOTYPE capsule: $texts", texts.contains("PROTOTYPE"))
        assertTrue(texts.any { it.contains("评估网络是否适合 AI 对话") })
        assertFalse(started)
    }

    @Test
    fun `HomeScreen shows and opens PROTOTYPE capsule when showPrototypeEntry is true`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = controller.get()

        var openedPrototype = false
        val composeView = ComposeView(activity).apply {
            setContent {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    AnebTheme {
                        HomeScreen(
                            lastRun = null,
                            lastBasicRun = null,
                            testMode = AnebTestMode.TOKEN_EXPERIENCE,
                            onTestModeChange = {},
                            running = false,
                            notice = null,
                            connectionLabel = "自动选择网络",
                            nodeLabel = "阿里云深圳 miw2",
                            onStart = {},
                            onOpenServer = {},
                            onOpenPrototype = { openedPrototype = true },
                            onOpenLastResult = {},
                            onOpenLastBasicResult = {},
                            onOpenVoice = {},
                            showPrototypeEntry = true,
                        )
                    }
                }
            }
        }
        activity.setContentView(composeView)
        controller.setup()
        idleMain()

        val texts = collectTexts(composeView)
        assertTrue("Prototype/debug HomeScreen should show PROTOTYPE capsule: $texts", texts.contains("PROTOTYPE"))
        assertTrue(clickNodeWithText(composeView, "PROTOTYPE"))
        idleMain()
        assertTrue(openedPrototype)
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
