package com.aneb.probe.research.engineering

import java.io.File
import java.nio.file.Files
import com.aneb.probe.research.engineering.TongyiPreparationRunner.Bounds
import org.junit.Assert.*
import org.junit.Test

/** Real shared Core/lifecycle; only external Android/clock/process responses are fixtures. */
class DeepSeekEngineeringMigrationTest {
    private class VisibleAndroid : DeepSeekUiAdapter.Android {
        var now = 1_000_000_000L
        var newSession = false
        var search = true
        var focused = false
        var draft = ""
        var refuseSearchOff = false
        var duplicateSend = false
        var missingEmptyComposerProof = false
        var replaceDraftAfterNewEmptyRead = false
        var focusSupported = true
        private var newEmptyReadReturned = false
        val invoked = mutableListOf<String>()
        val modeRequests = mutableListOf<String>()
        private val bounds = TongyiPreparationRunner.Bounds(0, 0, 1200, 2486)
        override fun read(): DeepSeekUiAdapter.Frame {
            if (newSession && replaceDraftAfterNewEmptyRead) {
                if (newEmptyReadReturned) draft = "EXTERNAL_UNKNOWN_REPLACEMENT"
                else newEmptyReadReturned = true
            }
            fun n(id: String, parent: String?, text: String = "", description: String = "") =
                DeepSeekUiAdapter.Node(id, parent, if (id == "composer" || parent in setOf("composer", "thinking", "search", "hint-holder"))
                    TongyiPreparationRunner.Bounds(0,2000,1200,2486) else bounds, text = text, description = description)
            val nodes = mutableListOf(n("root", null), n("composer", "root"),
                n("new-parent", "root").copy(clickable = true, clickAvailable = true),
                n("new-label", "new-parent", description = "开启新对话"),
                n("thinking", "composer").copy(checkable = true, clickable = true, clickAvailable = true),
                n("thinking-label", "thinking", "深度思考").copy(bounds = TongyiPreparationRunner.Bounds(0,0,0,0)),
                n("search", "composer").copy(checkable = true, checked = search, clickable = true, clickAvailable = true),
                n("search-label", "search", "智能搜索").copy(bounds = TongyiPreparationRunner.Bounds(0,0,0,0)),
                n("input", "composer").copy(text = draft, editable = true, focused = focused, setTextAvailable = true, focusAvailable = focusSupported),
                n("send", "composer", description = "EXTERNAL_FIXTURE_SEND").copy(clickable = true, clickAvailable = true))
            if (duplicateSend) nodes += n("second-send", "composer", description = "EXTERNAL_FIXTURE_SEND").copy(clickable = true, clickAvailable = true)
            if (missingEmptyComposerProof) nodes.removeAll { it.id == "input" }
            if (!newSession && !missingEmptyComposerProof) {
                nodes.removeAll { it.id == "input" }
                nodes += n("hint-holder", "composer").copy(className = "androidx.compose.ui.viewinterop.ViewFactoryHolder")
                nodes += n("hint", "hint-holder", "发消息或按住说话").copy(className = "android.widget.TextView")
            }
            if (newSession) nodes += listOf(n("title", "root", "新对话"), n("welcome", "root", "今天有什么可以帮到你？"))
            return DeepSeekUiAdapter.Frame("42/10241/12345", 1, 1200, 2640, 0, "root", nodes)
        }
        override fun invoke(frame: DeepSeekUiAdapter.Frame, node: DeepSeekUiAdapter.Node, action: DeepSeekUiAdapter.Action) {
            check(read() == frame) // The external OS refuses a changed acquisition, never a cached handle.
            invoked += "${node.id}:$action"
            when (node.id) {
                "new-parent" -> newSession = true
                "search" -> if (!refuseSearchOff) search = false
                "input" -> focused = true
                else -> error("UNEXPECTED_PUBLIC_ACTION")
            }
        }
        override fun activateCheckedMode(frame: DeepSeekUiAdapter.Frame, label: DeepSeekUiAdapter.Node, control: DeepSeekUiAdapter.Node) {
            check(label.parent == control.id && control.checked)
            modeRequests += control.id
            invoke(frame, control, DeepSeekUiAdapter.Action.CLICK) // External fixture effect only, not a Native fallback.
        }
        override fun nowNanos() = now
        override fun sleepMillis(milliseconds: Long) { now += milliseconds * 1_000_000 }
    }

    @Test fun savedEmptyHomeWithExpandedModeBoundsReachesSupportedNewSessionBoundary() {
        // External Android frame with the captured 48-node public topology/geometry.
        // Owner/window/IDs are synthetic and consistently remapped; source pin and
        // field mapping are retained only in the external diagnosis handoff.
        // This is not a fresh failure snapshot or a claim about a later frame/editor/focus.
        val frame = DeepSeekUiAdapter.Frame("EXTERNAL_OBSERVED_FRAME_OWNER", 1, 1200, 2640, 0, "fixture-0", listOf(
            DeepSeekUiAdapter.Node("fixture-0", null, TongyiPreparationRunner.Bounds(0, 0, 1200, 2640),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.widget.FrameLayout"),
            DeepSeekUiAdapter.Node("fixture-1", "fixture-0", TongyiPreparationRunner.Bounds(0, 0, 1200, 2640),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.widget.LinearLayout"),
            DeepSeekUiAdapter.Node("fixture-2", "fixture-1", TongyiPreparationRunner.Bounds(0, 0, 1200, 2640),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.widget.FrameLayout"),
            DeepSeekUiAdapter.Node("fixture-3", "fixture-2", TongyiPreparationRunner.Bounds(0, 0, 1200, 2640),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.widget.LinearLayout"),
            DeepSeekUiAdapter.Node("fixture-4", "fixture-3", TongyiPreparationRunner.Bounds(0, 0, 1200, 2640),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.widget.FrameLayout"),
            DeepSeekUiAdapter.Node("fixture-5", "fixture-4", TongyiPreparationRunner.Bounds(0, 0, 1200, 2640),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "androidx.compose.ui.platform.ComposeView"),
            DeepSeekUiAdapter.Node("fixture-6", "fixture-5", TongyiPreparationRunner.Bounds(0, 0, 1200, 2640),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.view.View"),
            DeepSeekUiAdapter.Node("fixture-7", "fixture-6", TongyiPreparationRunner.Bounds(0, 0, 1200, 2640),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.view.View"),
            DeepSeekUiAdapter.Node("fixture-8", "fixture-7", TongyiPreparationRunner.Bounds(0, 0, 1200, 2640),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.view.View"),
            DeepSeekUiAdapter.Node("fixture-9", "fixture-8", TongyiPreparationRunner.Bounds(0, 0, 1200, 2640),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.view.View"),
            DeepSeekUiAdapter.Node("fixture-10", "fixture-9", TongyiPreparationRunner.Bounds(0, 0, 1200, 2640),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.view.View"),
            DeepSeekUiAdapter.Node("fixture-11", "fixture-10", TongyiPreparationRunner.Bounds(0, 0, 1200, 2640),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.view.View"),
            DeepSeekUiAdapter.Node("fixture-12", "fixture-11", TongyiPreparationRunner.Bounds(270, 1342, 930, 1417),
                text = "今天有什么可以帮到你？", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.widget.TextView"),
            DeepSeekUiAdapter.Node("fixture-13", "fixture-11", TongyiPreparationRunner.Bounds(0, 0, 1200, 310),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.view.View"),
            DeepSeekUiAdapter.Node("fixture-14", "fixture-13", TongyiPreparationRunner.Bounds(0, 154, 1200, 310),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.view.View"),
            DeepSeekUiAdapter.Node("fixture-15", "fixture-14", TongyiPreparationRunner.Bounds(6, 160, 150, 304),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.view.View"),
            DeepSeekUiAdapter.Node("fixture-16", "fixture-15", TongyiPreparationRunner.Bounds(6, 160, 150, 304),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = true, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = true, focusAvailable = true,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.view.View"),
            DeepSeekUiAdapter.Node("fixture-17", "fixture-16", TongyiPreparationRunner.Bounds(42, 196, 114, 268),
                text = "", description = "打开侧边栏", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.view.View"),
            DeepSeekUiAdapter.Node("fixture-18", "fixture-16", TongyiPreparationRunner.Bounds(12, 166, 144, 298),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.widget.Button"),
            DeepSeekUiAdapter.Node("fixture-19", "fixture-14", TongyiPreparationRunner.Bounds(156, 160, 1044, 304),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.view.View"),
            DeepSeekUiAdapter.Node("fixture-20", "fixture-19", TongyiPreparationRunner.Bounds(528, 203, 672, 260),
                text = "新对话", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.widget.TextView"),
            DeepSeekUiAdapter.Node("fixture-21", "fixture-14", TongyiPreparationRunner.Bounds(1050, 160, 1194, 304),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.view.View"),
            DeepSeekUiAdapter.Node("fixture-22", "fixture-21", TongyiPreparationRunner.Bounds(1050, 160, 1194, 304),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = true, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = true, focusAvailable = true,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.view.View"),
            DeepSeekUiAdapter.Node("fixture-23", "fixture-22", TongyiPreparationRunner.Bounds(1086, 196, 1158, 268),
                text = "", description = "开启新对话", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.view.View"),
            DeepSeekUiAdapter.Node("fixture-24", "fixture-22", TongyiPreparationRunner.Bounds(1056, 166, 1188, 298),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.widget.Button"),
            DeepSeekUiAdapter.Node("fixture-25", "fixture-11", TongyiPreparationRunner.Bounds(0, 2553, 1200, 2640),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.view.View"),
            DeepSeekUiAdapter.Node("fixture-26", "fixture-25", TongyiPreparationRunner.Bounds(0, 2553, 1200, 2640),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.view.View"),
            DeepSeekUiAdapter.Node("fixture-27", "fixture-26", TongyiPreparationRunner.Bounds(0, 2538, 1200, 2640),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.view.View"),
            DeepSeekUiAdapter.Node("fixture-28", "fixture-11", TongyiPreparationRunner.Bounds(36, 2259, 1164, 2403),
                text = "", description = "", hint = null, showingHint = false,
                visible = false, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.view.View"),
            DeepSeekUiAdapter.Node("fixture-29", "fixture-11", TongyiPreparationRunner.Bounds(36, 2292, 1164, 2610),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.view.View"),
            DeepSeekUiAdapter.Node("fixture-30", "fixture-29", TongyiPreparationRunner.Bounds(36, 2232, 1164, 2376),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.view.View"),
            DeepSeekUiAdapter.Node("fixture-31", "fixture-29", TongyiPreparationRunner.Bounds(36, 2304, 1164, 2454),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.view.View"),
            DeepSeekUiAdapter.Node("fixture-32", "fixture-31", TongyiPreparationRunner.Bounds(66, 2304, 1134, 2454),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.view.View"),
            DeepSeekUiAdapter.Node("fixture-33", "fixture-32", TongyiPreparationRunner.Bounds(87, 2340, 1107, 2418),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "androidx.compose.ui.viewinterop.ViewFactoryHolder"),
            DeepSeekUiAdapter.Node("fixture-34", "fixture-33", TongyiPreparationRunner.Bounds(87, 2340, 1107, 2418),
                text = "发消息或按住说话", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.widget.TextView"),
            DeepSeekUiAdapter.Node("fixture-35", "fixture-29", TongyiPreparationRunner.Bounds(36, 2478, 846, 2574),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.view.View"),
            DeepSeekUiAdapter.Node("fixture-36", "fixture-35", TongyiPreparationRunner.Bounds(36, 2424, 396, 2628),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = true, checkable = true, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = true, focusAvailable = true,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.view.View"),
            DeepSeekUiAdapter.Node("fixture-37", "fixture-36", TongyiPreparationRunner.Bounds(168, 2502, 336, 2551),
                text = "深度思考", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.widget.TextView"),
            DeepSeekUiAdapter.Node("fixture-38", "fixture-35", TongyiPreparationRunner.Bounds(396, 2424, 696, 2628),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = true, checkable = true, checked = true,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = true, focusAvailable = true,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.view.View"),
            DeepSeekUiAdapter.Node("fixture-39", "fixture-38", TongyiPreparationRunner.Bounds(492, 2502, 660, 2551),
                text = "智能搜索", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.widget.TextView"),
            DeepSeekUiAdapter.Node("fixture-40", "fixture-29", TongyiPreparationRunner.Bounds(846, 2430, 978, 2622),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.view.View"),
            DeepSeekUiAdapter.Node("fixture-41", "fixture-40", TongyiPreparationRunner.Bounds(846, 2430, 978, 2622),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = true, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = true, focusAvailable = true,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.view.View"),
            DeepSeekUiAdapter.Node("fixture-42", "fixture-41", TongyiPreparationRunner.Bounds(903, 2487, 978, 2565),
                text = "", description = "上传文件", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.view.View"),
            DeepSeekUiAdapter.Node("fixture-43", "fixture-41", TongyiPreparationRunner.Bounds(888, 2472, 996, 2580),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.widget.Button"),
            DeepSeekUiAdapter.Node("fixture-44", "fixture-29", TongyiPreparationRunner.Bounds(978, 2430, 1170, 2622),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.view.View"),
            DeepSeekUiAdapter.Node("fixture-45", "fixture-44", TongyiPreparationRunner.Bounds(978, 2430, 1170, 2622),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = true, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = true, focusAvailable = true,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.view.View"),
            DeepSeekUiAdapter.Node("fixture-46", "fixture-45", TongyiPreparationRunner.Bounds(1035, 2487, 1113, 2565),
                text = "", description = "切换至语音", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.view.View"),
            DeepSeekUiAdapter.Node("fixture-47", "fixture-45", TongyiPreparationRunner.Bounds(1020, 2472, 1128, 2580),
                text = "", description = "", hint = null, showingHint = false,
                visible = true, enabled = true, clickable = false, checkable = false, checked = false,
                editable = false, focused = false, setTextAvailable = false, clickAvailable = false, focusAvailable = false,
                windowId = 1, packageName = "com.deepseek.chat", className = "android.widget.Button")
        ))
        val requested = mutableListOf<String>()
        val android = object : DeepSeekUiAdapter.Android {
            override fun read() = frame
            override fun invoke(current: DeepSeekUiAdapter.Frame, node: DeepSeekUiAdapter.Node, action: DeepSeekUiAdapter.Action) {
                check(current == frame && node == frame.nodes.single { it.id == "fixture-22" } && action == DeepSeekUiAdapter.Action.CLICK)
                requested += "${node.id}:$action"
                // Stop at the external action request. Do not fabricate a successful click,
                // subsequent new-session frame, OFF/OFF, focus, input, SEND or cleanup.
                error("EXTERNAL_NEW_SESSION_BOUNDARY_STOP")
            }
            override fun nowNanos() = 1_000_000_000L
            override fun sleepMillis(milliseconds: Long) = error("NO_LATER_FRAME_OR_RETRY_IN_THIS_CAPTURE")
        }
        val failure = runCatching { DeepSeekUiAdapter(android, 1_000_000_000L + TongyiEngineeringRunner.PREP_NANOS).prepare() }.exceptionOrNull()
        assertEquals("the saved same-composer empty hint must reach the supported new-session boundary; refusal=${failure?.message}",
            listOf("fixture-22:CLICK"), requested)
        assertEquals("EXTERNAL_NEW_SESSION_BOUNDARY_STOP", failure?.message)
    }

    @Test fun expandedModeParentDoesNotAdmitItsLabelOutsideTheSharedComposer() {
        // Post-GREEN safety regression, using only an external Android frame.
        val base = VisibleAndroid().read()
        val frame = base.copy(nodes = base.nodes.map { node -> when (node.id) {
            "thinking" -> node.copy(bounds = TongyiPreparationRunner.Bounds(0, 1980, 1200, 2486))
            "thinking-label" -> node.copy(bounds = TongyiPreparationRunner.Bounds(20, 1985, 200, 1995))
            else -> node
        } })
        // The positive label is inside its actual action parent/root but above the
        // composer (top=2000). Expanded target bounds alone cannot prove membership.
        val requested = mutableListOf<String>()
        val android = object : DeepSeekUiAdapter.Android {
            override fun read() = frame
            override fun invoke(current: DeepSeekUiAdapter.Frame, node: DeepSeekUiAdapter.Node, action: DeepSeekUiAdapter.Action) {
                requested += "${node.id}:$action"
                error("ACTION_MUST_NOT_BE_REQUESTED")
            }
            override fun nowNanos() = 1_000_000_000L
            override fun sleepMillis(milliseconds: Long) = error("NO_RETRY_AFTER_INVALID_COMPOSER_PROOF")
        }
        val failure = runCatching { DeepSeekUiAdapter(android, 1_000_000_000L + TongyiEngineeringRunner.PREP_NANOS).prepare() }.exceptionOrNull()
        assertEquals("DEEPSEEK_SHARED_COMPOSER_REQUIRED", failure?.message)
        assertTrue("no new-session/mode/focus action on an unbound semantic label", requested.isEmpty())
    }

    @Test fun modeDiagnosticCopiesOneSuccessorAndStopsAfterFirstGateWithoutFocusOrInput() {
        // Software boundary only: this synthetic OFF response proves no real touch/PNG/App effect.
        val android = VisibleAndroid()
        val observed = mutableListOf<DeepSeekUiAdapter.Frame>()
        var gate: DeepSeekUiAdapter.ModeGate? = null
        val failure = runCatching {
            gate = DeepSeekUiAdapter(android, android.now + TongyiEngineeringRunner.PREP_NANOS).diagnoseFirstMode {
                frame, label, until, observedAt ->
                check(label == "智能搜索" && observedAt < until)
                observed += frame
            }
        }.exceptionOrNull()
        assertNull("bounded diagnostic must return its actual mode gate: ${failure?.message}", failure)
        assertEquals("智能搜索", gate?.mode)
        assertEquals(true, gate?.operationReturned)
        assertEquals(true, gate?.gateReturned)
        assertNull(gate?.failure)
        assertEquals(1, observed.size)
        assertFalse(observed.single().nodes.single { it.id == "search" }.checked)
        assertEquals(listOf("new-parent:CLICK", "search:CLICK"), android.invoked)
        assertFalse(android.focused)
        assertEquals("", android.draft)
    }

    @Test fun modeDiagnosticKeepsOriginalGateOutcomeSeparateFromCaptureCostAndFailureAndAlwaysCleansOwnedTarget() {
        // External clock/mode/capture responses only. No real PNG or App effect is inferred.
        for (offReturned in listOf(true, false)) {
            val visible = VisibleAndroid().apply { refuseSearchOff = !offReturned }
            val directory = Files.createTempDirectory("deepseek-mode-feedback").resolve("run").toFile()
            var cleaned = false
            var successorCalls = 0
            var originalUntil = 0L
            val android = object : DeepSeekModeOperationDiagnostic.Android {
                override fun diagnoseFirstMode(deadlineNanos: Long) = DeepSeekUiAdapter(visible, deadlineNanos)
                    .diagnoseFirstMode { _, _, until, _ ->
                        successorCalls++
                        originalUntil = until
                        visible.now = until // Explicit external PNG cost consumes the ORIGINAL OFF5.
                        error("EXTERNAL_CAPTURE_TEMPORAL_UNKNOWN")
                    }
                override fun cleanupOwned(): Boolean { cleaned = true; return true }
            }
            val result = DeepSeekModeOperationDiagnostic(android, object : TongyiEngineeringRunner.MonotonicClock {
                override fun nowNanos() = visible.now
            }).runOnce("MODE_FEEDBACK_BOUNDARY", directory)
            assertTrue(cleaned && result.ownedCleanup)
            assertNull(result.failure)
            assertEquals(1, successorCalls)
            assertEquals(true, result.gate?.operationReturned)
            assertEquals(offReturned, result.gate?.gateReturned)
            assertEquals("EXTERNAL_CAPTURE_TEMPORAL_UNKNOWN", result.gate?.feedbackFailure?.message)
            assertEquals(if (offReturned) null else "DEEPSEEK_PUBLIC_READINESS_EXPIRED", result.gate?.failure?.message)
            assertTrue("original wait was never reset to grant another five seconds", visible.now <= originalUntil + 50_000_000L)
            assertEquals(listOf("new-parent:CLICK", "search:CLICK"), visible.invoked)
            assertFalse(visible.focused)
            assertEquals("", visible.draft)
        }
        val noMode = VisibleAndroid().apply { search = false }
        val gate = DeepSeekUiAdapter(noMode, noMode.now + TongyiEngineeringRunner.PREP_NANOS)
            .diagnoseFirstMode { _, _, _, _ -> error("NO_SUCCESSOR_WITHOUT_AN_ON_MODE_OPERATION") }
        assertNull(gate.mode)
        assertNull(gate.operationReturned)
        assertNull(gate.gateReturned)
        assertEquals(listOf("new-parent:CLICK"), noMode.invoked)
        assertFalse(noMode.focused)

        var cleanupCalled = false
        val original = IllegalStateException("EXTERNAL_PREPARATION_REFUSAL")
        val refused = DeepSeekModeOperationDiagnostic(object : DeepSeekModeOperationDiagnostic.Android {
            override fun diagnoseFirstMode(deadlineNanos: Long): DeepSeekUiAdapter.ModeGate = throw original
            override fun cleanupOwned(): Boolean { cleanupCalled = true; error("EXTERNAL_OWNED_CLEANUP_REFUSAL") }
        }, object : TongyiEngineeringRunner.MonotonicClock { override fun nowNanos() = 1L })
            .runOnce("MODE_FEEDBACK_REFUSAL", Files.createTempDirectory("deepseek-mode-refusal").resolve("run").toFile())
        assertTrue(cleanupCalled)
        assertSame(original, refused.failure)
        assertFalse(refused.ownedCleanup)
        assertEquals("EXTERNAL_OWNED_CLEANUP_REFUSAL", refused.cleanupFailure?.message)
    }

    @Test fun currentModeControlsMayBecomeCompleteOnlyWithinTheOriginalNewSessionReadyDeadline() {
        val android = SemanticComposerAndroid()
        var reads = 0
        var incompleteReturned = false
        val requests = mutableListOf<String>()
        val boundary = object : DeepSeekUiAdapter.Android by android {
            override fun read(): DeepSeekUiAdapter.Frame {
                val complete = android.read()
                reads++
                if (reads == 4) {
                    incompleteReturned = true
                    return missingSearchCurrentFrame(complete)
                }
                // Explicit SYNTHETIC complete successor, not a saved R4 recovery frame.
                return complete
            }
            override fun activateCheckedMode(frame: DeepSeekUiAdapter.Frame, label: DeepSeekUiAdapter.Node, control: DeepSeekUiAdapter.Node) {
                check(incompleteReturned && frame == android.read() && label.parent == control.id && control.checked)
                requests += label.text
                error("EXTERNAL_COMPLETE_CURRENT_MODE_BOUNDARY_STOP")
            }
            override fun activateEmptyComposer(frame: DeepSeekUiAdapter.Frame, hint: DeepSeekUiAdapter.Node) =
                error("FOCUS_OR_INPUT_MUST_NOT_BE_REACHED")
        }
        val started = android.now
        val failure = runCatching { DeepSeekUiAdapter(boundary, started + TongyiEngineeringRunner.PREP_NANOS).prepare() }.exceptionOrNull()
        assertEquals("wait for complete current controls before requesting the mode: ${failure?.message}", listOf("智能搜索"), requests)
        assertEquals("EXTERNAL_COMPLETE_CURRENT_MODE_BOUNDARY_STOP", failure?.message)
        assertTrue(incompleteReturned)
        assertTrue("complete current controls must arrive within the original READY deadline",
            android.now < started + TongyiEngineeringRunner.READY_NANOS)
        assertEquals(listOf("public-22:CLICK"), android.actions)
        assertTrue(android.activations.isEmpty())
        assertTrue(android.searchOn)
    }

    @Test fun persistentlyMissingCurrentModeExpiresAtTheNearSpentOriginalReadyDeadlineWithoutAnyModeOrFocusAction() {
        // Honest post-GREEN regression. Time and the complete ready response are
        // external fixtures, not modelled phone latency or an observed R4 successor.
        val android = SemanticComposerAndroid()
        val started = android.now
        val originalReadyUntil = started + TongyiEngineeringRunner.READY_NANOS
        var reads = 0
        val requests = mutableListOf<String>()
        val boundary = object : DeepSeekUiAdapter.Android by android {
            override fun read(): DeepSeekUiAdapter.Frame {
                val complete = android.read()
                reads++
                if (reads == 2) android.now = originalReadyUntil - 50_000_000L
                return if (reads >= 3) missingSearchCurrentFrame(complete) else complete
            }
            override fun activateCheckedMode(frame: DeepSeekUiAdapter.Frame, label: DeepSeekUiAdapter.Node, control: DeepSeekUiAdapter.Node) {
                requests += "mode:${label.text}"
                error("NO_MODE_ACTION_WHILE_A_REQUIRED_CONTROL_IS_MISSING")
            }
            override fun activateEmptyComposer(frame: DeepSeekUiAdapter.Frame, hint: DeepSeekUiAdapter.Node) {
                requests += "composer:${hint.id}"
                error("NO_FOCUS_OR_INPUT_WHILE_A_REQUIRED_CONTROL_IS_MISSING")
            }
        }
        val failure = runCatching { DeepSeekUiAdapter(boundary, started + TongyiEngineeringRunner.PREP_NANOS).prepare() }.exceptionOrNull()
        assertEquals("DEEPSEEK_PUBLIC_READINESS_EXPIRED", failure?.message)
        assertEquals("expiry is at the original absolute READY deadline, never a restarted20/5", originalReadyUntil, android.now)
        assertTrue(requests.isEmpty())
        assertEquals(listOf("public-22:CLICK"), android.actions)
        assertTrue(android.activations.isEmpty())
        assertTrue(android.searchOn)
        assertFalse(android.read().nodes.any { it.editable })
    }

    private fun missingSearchCurrentFrame(complete: DeepSeekUiAdapter.Frame): DeepSeekUiAdapter.Frame {
        // R4-shaped public fields: no search/upload/title/welcome, OFF thinking,
        // owned empty hint and the "already new chat" message. IDs/window/owner
        // are consistently synthetic; no saved private JSON or action successor is embedded.
        val absent = setOf("public-12", "public-19", "public-20", "public-38", "public-39",
            "public-40", "public-41", "public-42", "public-43")
        val nodes = complete.nodes.filterNot { it.id in absent }.map { node -> when (node.id) {
            "public-21", "public-22" -> node.copy(bounds = Bounds(1104,160,1194,304))
            "public-23" -> node.copy(bounds = Bounds(1104,196,1158,268))
            "public-31" -> node.copy(bounds = Bounds(36,2304,1164,2430))
            "public-32" -> node.copy(bounds = Bounds(66,2304,1134,2430))
            "public-35" -> node.copy(bounds = Bounds(36,2478,96,2574))
            "public-44" -> node.copy(bounds = Bounds(1104,2430,1170,2622))
            else -> node
        } } + listOf(
            DeepSeekUiAdapter.Node("pending-message-root", "public-7", Bounds(96,0,1104,2640)),
            DeepSeekUiAdapter.Node("pending-message-holder", "pending-message-root", Bounds(443,1248,758,1392)),
            DeepSeekUiAdapter.Node("pending-message", "pending-message-holder", Bounds(443,1287,758,1353),
                text = "已经在新对话中", className = "android.widget.TextView"))
        return complete.copy(nodes = nodes)
    }

    @Test fun checkedSearchUsesOneSemanticModeRequestBeforeAnyPointerOrComposerActivation() {
        val android = SemanticComposerAndroid()
        val modeRequests = mutableListOf<String>()
        val legacyModeRequests = mutableListOf<String>()
        // This existing public topology also matches the R3 last-cached source
        // SHA05a8bbe5f386f20d6138f19bf61d7dc0852ae5726b36a40bc546890729d7f28d.
        // Owner/window/IDs and new-chat response are synthetic, not saved action successor frames.
        val boundary = object : DeepSeekUiAdapter.Android by android {
            override fun invoke(frame: DeepSeekUiAdapter.Frame, node: DeepSeekUiAdapter.Node, action: DeepSeekUiAdapter.Action) {
                if (node.id == "public-38") {
                    check(action == DeepSeekUiAdapter.Action.CLICK && node.checked)
                    legacyModeRequests += node.id
                    return // External API accepts the request without an OFF observation; no actual-cause claim.
                }
                android.invoke(frame, node, action)
            }
            override fun activateCheckedMode(frame: DeepSeekUiAdapter.Frame, label: DeepSeekUiAdapter.Node, control: DeepSeekUiAdapter.Node) {
                check(label.text == "智能搜索" && label.parent == control.id && control.checked && control.checkable)
                modeRequests += "${label.text}:${control.id}"
                error("EXTERNAL_SEMANTIC_MODE_DECLINED_BEFORE_POINTER")
            }
        }
        val failure = runCatching { DeepSeekUiAdapter(boundary, android.now + TongyiEngineeringRunner.PREP_NANOS).prepare() }.exceptionOrNull()
        assertEquals("one semantic mode request required: " + failure?.message, listOf("智能搜索:public-38"), modeRequests)
        assertEquals("EXTERNAL_SEMANTIC_MODE_DECLINED_BEFORE_POINTER", failure?.message)
        assertTrue("never perform an Accessibility mode click before the semantic request", legacyModeRequests.isEmpty())
        assertEquals(listOf("public-22:CLICK"), android.actions)
        assertTrue(android.activations.isEmpty())
        assertTrue(android.searchOn)
    }

    @Test fun semanticModeVoidReturnWithoutVisibleOffRefusesOnceBeforeFocusOrInput() {
        val android = VisibleAndroid().apply { refuseSearchOff = true }
        val failure = runCatching { DeepSeekUiAdapter(android, android.now + TongyiEngineeringRunner.PREP_NANOS).prepare() }.exceptionOrNull()
        assertEquals("DEEPSEEK_PUBLIC_READINESS_EXPIRED", failure?.message)
        assertEquals(listOf("search"), android.modeRequests)
        assertEquals(listOf("new-parent:CLICK", "search:CLICK"), android.invoked)
        assertTrue(android.search)
        assertFalse(android.focused)
        assertEquals("", android.draft)
    }

    @Test fun currentEmptyHintWithoutAdvertisedActionsReachesDistinctSemanticActivationBeforeAnyPointer() {
        val android = SemanticComposerAndroid()
        val failure = runCatching { DeepSeekUiAdapter(android, android.now + TongyiEngineeringRunner.PREP_NANOS).prepare() }.exceptionOrNull()
        assertEquals("bounded semantic activation must be requested: "+failure?.message, listOf("public-34"), android.activations)
        assertEquals("EXTERNAL_SEMANTIC_ACTIVATION_DECLINED_BEFORE_POINTER", failure?.message)
        assertEquals(listOf("public-22:CLICK", "public-38:CLICK"), android.actions)
        assertFalse("no editor/input success is fabricated by the sentinel", android.read().nodes.any { it.editable })
    }

    @Test fun semanticActivationVoidReturnWithoutARealEditableReadbackNeverCompletesPreparation() {
        val android = SemanticComposerAndroid().apply { declineActivation = false }
        val failure = runCatching { DeepSeekUiAdapter(android, android.now + TongyiEngineeringRunner.PREP_NANOS).prepare() }.exceptionOrNull()
        assertEquals("DEEPSEEK_PUBLIC_READINESS_EXPIRED", failure?.message)
        assertEquals(listOf("public-34"), android.activations)
        assertEquals(listOf("public-22:CLICK", "public-38:CLICK"), android.actions)
        assertFalse(android.read().nodes.any { it.editable })
    }

    private class SemanticComposerAndroid : DeepSeekUiAdapter.Android {
        var now = 1_000_000_000L
        var searchOn = true
        var declineActivation = true
        val actions = mutableListOf<String>()
        val activations = mutableListOf<String>()
        // Public topology/geometry from Native source SHA6c379975ece28f624df6876a8b00ca46d45e28a267b11d153d3bb692e8126787.
        // Owner/window/local IDs are synthetic. Null semantic text/description map to empty exactly as Native Node values do.
        // New-chat returns the same empty fixture and search CLICK flips its checked value: synthetic OS responses,
        // NOT captured post-new-chat/mode frames. No post-click editor, input or SEND response is invented.
        private val initial = observedEmptyComposerFrame()
        override fun read() = initial.copy(nodes = initial.nodes.map { if (it.id == "public-38") it.copy(checked = searchOn) else it })
        override fun invoke(frame: DeepSeekUiAdapter.Frame, node: DeepSeekUiAdapter.Node, action: DeepSeekUiAdapter.Action) {
            actions += "${node.id}:$action"
            check(action == DeepSeekUiAdapter.Action.CLICK && node.clickAvailable)
            when (node.id) {
                "public-22" -> Unit // Synthetic new-chat completion, no actual saved successor claim.
                "public-38" -> searchOn = false // Synthetic external mode response.
                else -> error("UNEXPECTED_ACTION_NO_INPUT_OR_SEND_ALLOWED")
            }
        }
        override fun activateCheckedMode(frame: DeepSeekUiAdapter.Frame, label: DeepSeekUiAdapter.Node, control: DeepSeekUiAdapter.Node) {
            check(label.parent == control.id && control.checked)
            invoke(frame, control, DeepSeekUiAdapter.Action.CLICK) // Mechanical external-fixture response, not product fallback.
        }
        override fun activateEmptyComposer(frame: DeepSeekUiAdapter.Frame, hint: DeepSeekUiAdapter.Node) {
            check(hint.id == "public-34" && !hint.clickAvailable && !hint.focusAvailable && !searchOn)
            activations += hint.id
            if (declineActivation) error("EXTERNAL_SEMANTIC_ACTIVATION_DECLINED_BEFORE_POINTER")
        }
        override fun nowNanos() = now
        override fun sleepMillis(milliseconds: Long) { now += milliseconds * 1_000_000 }

        private fun observedEmptyComposerFrame(): DeepSeekUiAdapter.Frame {
            fun n(id: Int, parent: Int?, bounds: Bounds, text: String = "", description: String = "",
                className: String = "android.view.View", visible: Boolean = true, checkable: Boolean = false,
                checked: Boolean = false, directAction: Boolean = false) = DeepSeekUiAdapter.Node(
                "public-$id", parent?.let { "public-$it" }, bounds, text = text, description = description,
                className = className, visible = visible, checkable = checkable, checked = checked,
                clickable = directAction, clickAvailable = directAction, focusAvailable = directAction)
            return DeepSeekUiAdapter.Frame("EXTERNAL_PUBLIC_HOME_OWNER", 1, 1200, 2640, 0, "public-0", listOf(
                n(0, null, Bounds(0, 0, 1200, 2640), className = "android.widget.FrameLayout"),
                n(1, 0, Bounds(0, 0, 1200, 2640), className = "android.widget.LinearLayout"),
                n(2, 1, Bounds(0, 0, 1200, 2640), className = "android.widget.FrameLayout"),
                n(3, 2, Bounds(0, 0, 1200, 2640), className = "android.widget.LinearLayout"),
                n(4, 3, Bounds(0, 0, 1200, 2640), className = "android.widget.FrameLayout"),
                n(5, 4, Bounds(0, 0, 1200, 2640), className = "androidx.compose.ui.platform.ComposeView"),
                n(6, 5, Bounds(0, 0, 1200, 2640)),
                n(7, 6, Bounds(0, 0, 1200, 2640)),
                n(8, 7, Bounds(0, 0, 1200, 2640)),
                n(9, 8, Bounds(0, 0, 1200, 2640)),
                n(10, 9, Bounds(0, 0, 1200, 2640)),
                n(11, 10, Bounds(0, 0, 1200, 2640)),
                n(12, 11, Bounds(270, 1342, 930, 1417), text = "今天有什么可以帮到你？", className = "android.widget.TextView"),
                n(13, 11, Bounds(0, 0, 1200, 310)),
                n(14, 13, Bounds(0, 154, 1200, 310)),
                n(15, 14, Bounds(6, 160, 150, 304)),
                n(16, 15, Bounds(6, 160, 150, 304), directAction = true),
                n(17, 16, Bounds(42, 196, 114, 268), description = "打开侧边栏"),
                n(18, 16, Bounds(12, 166, 144, 298), className = "android.widget.Button"),
                n(19, 14, Bounds(156, 160, 1044, 304)),
                n(20, 19, Bounds(528, 203, 672, 260), text = "新对话", className = "android.widget.TextView"),
                n(21, 14, Bounds(1050, 160, 1194, 304)),
                n(22, 21, Bounds(1050, 160, 1194, 304), directAction = true),
                n(23, 22, Bounds(1086, 196, 1158, 268), description = "开启新对话"),
                n(24, 22, Bounds(1056, 166, 1188, 298), className = "android.widget.Button"),
                n(25, 11, Bounds(0, 2553, 1200, 2640)),
                n(26, 25, Bounds(0, 2553, 1200, 2640)),
                n(27, 26, Bounds(0, 2538, 1200, 2640)),
                n(28, 11, Bounds(36, 2259, 1164, 2403), visible = false),
                n(29, 11, Bounds(36, 2292, 1164, 2610)),
                n(30, 29, Bounds(36, 2232, 1164, 2376)),
                n(31, 29, Bounds(36, 2304, 1164, 2454)),
                n(32, 31, Bounds(66, 2304, 1134, 2454)),
                n(33, 32, Bounds(87, 2340, 1107, 2418), className = "androidx.compose.ui.viewinterop.ViewFactoryHolder"),
                n(34, 33, Bounds(87, 2340, 1107, 2418), text = "发消息或按住说话", className = "android.widget.TextView"),
                n(35, 29, Bounds(36, 2478, 846, 2574)),
                n(36, 35, Bounds(36, 2424, 396, 2628), checkable = true, directAction = true),
                n(37, 36, Bounds(168, 2502, 336, 2551), text = "深度思考", className = "android.widget.TextView"),
                n(38, 35, Bounds(396, 2424, 696, 2628), checkable = true, checked = true, directAction = true),
                n(39, 38, Bounds(492, 2502, 660, 2551), text = "智能搜索", className = "android.widget.TextView"),
                n(40, 29, Bounds(846, 2430, 978, 2622)),
                n(41, 40, Bounds(846, 2430, 978, 2622), directAction = true),
                n(42, 41, Bounds(903, 2487, 978, 2565), description = "上传文件"),
                n(43, 41, Bounds(888, 2472, 996, 2580), className = "android.widget.Button"),
                n(44, 29, Bounds(978, 2430, 1170, 2622)),
                n(45, 44, Bounds(978, 2430, 1170, 2622), directAction = true),
                n(46, 45, Bounds(1035, 2487, 1113, 2565), description = "切换至语音"),
                n(47, 45, Bounds(1020, 2472, 1128, 2580), className = "android.widget.Button"),
            ))
        }
    }

    @Test fun currentSemanticPreparationUsesCheckedParentsAndVerifiesVisibleOffOffBeforeFocus() {
        val android = VisibleAndroid()
        val failure = runCatching { DeepSeekUiAdapter(android, android.now + TongyiEngineeringRunner.PREP_NANOS).prepare() }.exceptionOrNull()
        assertNull("actual target preparation must return after supported public actions", failure)
        assertEquals(listOf("new-parent:CLICK", "search:CLICK", "input:FOCUS"), android.invoked)
        assertTrue(android.newSession)
        assertFalse(android.search)
        assertTrue(android.focused)
    }

    @Test fun missingEditorAndEmptyComposerProofRefusesBeforeAnyNewConversationAction() {
        val android = VisibleAndroid().apply { missingEmptyComposerProof = true }
        val failure = runCatching { DeepSeekUiAdapter(android, android.now + TongyiEngineeringRunner.PREP_NANOS).prepare() }.exceptionOrNull()
        assertEquals("unknown non-editor state must not be crossed by new conversation", emptyList<String>(), android.invoked)
        assertEquals("DEEPSEEK_INITIAL_EMPTY_COMPOSER_REQUIRED", failure?.message)
    }

    @Test fun replacementDraftAfterNewEmptyReadRefusesEveryFurtherModeOrFocusActionAndSend() {
        val directory = Files.createTempDirectory("deepseek-current-draft-race").resolve("run").toFile()
        val visible = VisibleAndroid().apply { replaceDraftAfterNewEmptyRead = true }
        val android = AndroidBoundary(directory, visible)
        val result = TongyiEngineeringRunner(android, android, EngineeringTarget.DEEPSEEK).runOnce("DS_CURRENT_DRAFT_W1", "W1", directory)
        assertEquals("retain the completed new-chat action, but no action on the replacement draft",
            listOf("new-parent:CLICK"), visible.invoked)
        assertEquals("UNKNOWN_DRAFT_NOT_TOUCHED", result.reason)
        assertEquals("EXTERNAL_UNKNOWN_REPLACEMENT", visible.draft)
        assertTrue(visible.search)
        assertFalse(visible.focused)
        assertEquals("0", result.sendCalls)
        assertEquals(0, android.sends)
        assertFalse(result.operationalEvents.any { it.first == "input_started" })
        assertFalse(directory.resolve("SEND_INTENT.json").exists())
    }

    @Test fun publicUiObservationRetainsOneRawCapabilitySourceAndClosesItsOwnerWithoutInput() {
        val directory = Files.createTempDirectory("deepseek-public-ui").resolve("run").toFile()
        val os = AndroidBoundary(directory)
        val original = """{"source":"EXTERNAL_ANDROID_FIXTURE","owner":"fixture-owner","window_id":1,"nodes":[{"text":"深度思考","checked":false,"hint":null,"action_ids":[16]},{"text":"智能搜索","checked":true,"hint":null,"action_ids":[1,16]},{"text":"","editable":false,"focused":false,"action_ids":[]}]}""".toByteArray(Charsets.UTF_8)
        var reads = 0
        val android = object : DeepSeekPublicUiObservation.Android {
            override fun launchOwned(deadlineNanos: Long) { os.lifecycle.requireAlive(os.owner, deadlineNanos) }
            override fun readCurrent(): ByteArray { reads++; return original.copyOf() }
            override fun cleanupOwned(): Boolean {
                check(directory.resolve("current-public-ui-nodes.json").readBytes().contentEquals(original))
                return os.cleanupOwned()
            }
        }
        val result = DeepSeekPublicUiObservation(android, os).runOnce("DS_PUBLIC_UI", directory)
        assertTrue("raw current capabilities must be retained: ${result.failure}", result.sourceRetained)
        assertArrayEquals(original, checkNotNull(result.original).readBytes())
        assertEquals("PUBLIC_UI_OBSERVATION_RETAINED", result.state)
        assertTrue(result.ownedCleanup)
        assertEquals(1, reads)
        assertEquals(1, os.stops)
        assertEquals(0, os.sends)
        assertFalse(directory.resolve("SEND_INTENT.json").exists())
    }

    @Test fun publicUiObservationPreservesAcquisitionFailureAndStillClosesItsOwner() {
        val directory = Files.createTempDirectory("deepseek-public-ui-read-failure").resolve("run").toFile()
        val os = AndroidBoundary(directory)
        val original = IllegalStateException("EXTERNAL_CURRENT_API_READ_FAILED")
        val android = object : DeepSeekPublicUiObservation.Android {
            override fun launchOwned(deadlineNanos: Long) { os.lifecycle.requireAlive(os.owner, deadlineNanos) }
            override fun readCurrent(): ByteArray = throw original
            override fun cleanupOwned() = os.cleanupOwned()
        }
        val result = DeepSeekPublicUiObservation(android, os).runOnce("DS_PUBLIC_UI_READ_FAILURE", directory)
        assertEquals("EXTERNAL_CURRENT_API_READ_FAILED", result.failure)
        assertTrue(checkNotNull(result.originalStack).startsWith("java.lang.IllegalStateException: EXTERNAL_CURRENT_API_READ_FAILED"))
        assertEquals("PUBLIC_UI_OBSERVATION_UNKNOWN", result.state)
        assertFalse(result.sourceRetained)
        assertNull(result.original)
        assertTrue(result.ownedCleanup)
        assertEquals(1, os.stops)
        assertEquals(0, os.sends)
        assertFalse(directory.resolve("current-public-ui-nodes.json").exists())
        assertFalse(directory.resolve("SEND_INTENT.json").exists())
    }

    @Test fun publicUiObservationRetainsItsSourceButNeverClaimsClosureWhenOwnedStopFails() {
        val directory = Files.createTempDirectory("deepseek-public-ui-cleanup-failure").resolve("run").toFile()
        val os = AndroidBoundary(directory).apply { refuseOwnedStop = true }
        val original = """{"source":"EXTERNAL_ANDROID_FIXTURE","nodes":[]}""".toByteArray(Charsets.UTF_8)
        val android = object : DeepSeekPublicUiObservation.Android {
            override fun launchOwned(deadlineNanos: Long) { os.lifecycle.requireAlive(os.owner, deadlineNanos) }
            override fun readCurrent() = original.copyOf()
            override fun cleanupOwned() = os.cleanupOwned()
        }
        val result = DeepSeekPublicUiObservation(android, os).runOnce("DS_PUBLIC_UI_CLEANUP_FAILURE", directory)
        assertTrue(result.sourceRetained)
        assertArrayEquals(original, checkNotNull(result.original).readBytes())
        assertEquals("PUBLIC_UI_OBSERVATION_UNKNOWN", result.state)
        assertFalse(result.ownedCleanup)
        assertNotNull(result.failure)
        assertNotNull(result.originalStack)
        assertEquals(1, os.stops)
        assertTrue(os.alive)
        assertEquals(0, os.sends)
        assertFalse(directory.resolve("SEND_INTENT.json").exists())
    }

    @Test fun noSendQualificationRequiresRealReadbackRetainsObservedDescriptionsAndClosesItsOwner() {
        val directory = Files.createTempDirectory("deepseek-no-send-qualification").resolve("run").toFile()
        val visible = VisibleAndroid()
        val os = AndroidBoundary(directory)
        val retained = mutableListOf<String>()
        val observedDescriptions = mutableListOf<String>()
        var inputCalls = 0
        val android = object : DeepSeekDraftRoleQualification.Android {
            override fun prepareEmpty(deadlineNanos: Long) = DeepSeekUiAdapter(visible, deadlineNanos).prepare()
            override fun enterExactAndReadback(text: String): DeepSeekDraftRoleQualification.Readback {
                val before = visible.read().nodes.single { it.editable }
                check(before.focused && before.setTextAvailable && before.text.isEmpty())
                inputCalls++
                visible.draft = text // External Android ACTION_SET_TEXT boundary, not an internal validator mock.
                val frame = visible.read()
                val actual = frame.nodes.single { it.editable }
                return DeepSeekDraftRoleQualification.Readback(actual.packageName, frame.owner, actual.windowId,
                    frame.nodes.single { it.id == actual.parent }.windowId, actual.id == before.id && actual.parent == before.parent,
                    actual.text, actual.showingHint, actual.focused)
            }
            override fun retainCurrent(label: String) {
                retained += label
                observedDescriptions += visible.read().nodes.map { it.description }.filter { it.isNotEmpty() }
            }
            override fun retainRejectedCached() = false
            override fun cleanupOwned(): Boolean {
                check(visible.draft == TongyiEngineeringRunner.PAYLOAD)
                visible.draft = "" // External UI closure; real Native selection/DEL remains a separate runtime proof.
                return os.cleanupOwned() // Real selected lifecycle; only external shell packets are fixtures.
            }
        }
        val result = DeepSeekDraftRoleQualification(android, os).runOnce("DS_NO_SEND_QUALIFICATION", directory)
        assertTrue("positive actual239B readback required: ${result.failure}", result.inputReadbackConfirmed)
        assertEquals("DRAFT_ROLE_QUALIFICATION_CLOSED", result.state)
        assertEquals(listOf("new-empty", "owned-draft"), retained)
        assertEquals(retained, result.retainedStages)
        assertTrue(observedDescriptions.contains("EXTERNAL_FIXTURE_SEND"))
        assertEquals(1, inputCalls)
        assertTrue(result.ownedCleanup)
        assertEquals(1, os.stops)
        assertEquals(0, os.sends)
        assertFalse(directory.resolve("SEND_INTENT.json").exists())
    }

    @Test fun noSendQualificationPreservesUnsupportedFocusAndFalseCleanupWithoutClaimingInput() {
        val directory = Files.createTempDirectory("deepseek-no-send-refusal").resolve("run").toFile()
        val visible = VisibleAndroid().apply { focusSupported = false }
        val os = AndroidBoundary(directory).apply { refuseOwnedStop = true }
        var cleanupReached = false
        var rejectionAfterCleanup = false
        val android = object : DeepSeekDraftRoleQualification.Android {
            override fun prepareEmpty(deadlineNanos: Long) = DeepSeekUiAdapter(visible, deadlineNanos).prepare()
            override fun enterExactAndReadback(text: String): DeepSeekDraftRoleQualification.Readback = error("INPUT_MUST_NOT_BE_REACHED")
            override fun retainCurrent(label: String) = error("RETENTION_MUST_NOT_FABRICATE_PREPARATION_SUCCESS")
            override fun cleanupOwned(): Boolean { cleanupReached = true; return os.cleanupOwned() }
            override fun retainRejectedCached(): Boolean {
                check(cleanupReached)
                rejectionAfterCleanup = true
                return true // External cached observation, never a new UI acquisition.
            }
        }
        val result = DeepSeekDraftRoleQualification(android, object : TongyiEngineeringRunner.MonotonicClock {
            override fun nowNanos() = visible.now
        }).runOnce("DS_NO_SEND_REFUSAL", directory)
        assertFalse(result.inputReadbackConfirmed)
        assertEquals("DEEPSEEK_SUPPORTED_DIRECT_ACTION_REQUIRED", result.failure)
        assertFalse(result.ownedCleanup)
        assertTrue(cleanupReached && rejectionAfterCleanup && result.rejectedRetained)
        assertTrue(result.retainedStages.isEmpty())
        assertEquals("", visible.draft)
        assertEquals(1, os.stops)
        assertTrue(os.alive) // Actual external stop refusal; never turn it into claimed absence.
        assertEquals(0, os.sends)
        assertFalse(directory.resolve("SEND_INTENT.json").exists())
    }

    private class AndroidBoundary(val directory: File, val visibleUi: VisibleAndroid? = null) : TongyiEngineeringRunner.Phone,
        TongyiEngineeringRunner.MonotonicClock, OwnedProcessLifecycle.Shell {
        var now = 1_000_000_000L
        var draft = ""
        var alive = true
        var sends = 0
        var stops = 0
        var refuseOwnedStop = false
        val commands = mutableListOf<String>()
        val saved = mutableListOf<String>()
        val lifecycle = OwnedProcessLifecycle(this, this, EngineeringTarget.DEEPSEEK)
        val owner = OwnedProcessLifecycle.Owner("42", 10241, "12345", listOf("com.deepseek.chat"))
        override fun nowNanos() = now
        override fun prepare(deadlineNanos: Long): TongyiEngineeringRunner.Snapshot {
            visibleUi?.let { DeepSeekUiAdapter(it, deadlineNanos).prepare() }
            return snapshot()
        }
        override fun enterExact(text: String) { draft = text; visibleUi?.draft = text }
        override fun snapshot() = TongyiEngineeringRunner.Snapshot("com.deepseek.chat", true, true, true,
            draft.isEmpty(), draft, true, visibleUi?.let { ui ->
                DeepSeekUiAdapter(ui, now + TongyiEngineeringRunner.PREP_NANOS).sendCandidate(ui.read(), "EXTERNAL_FIXTURE_SEND") != null
            } ?: true, "W1", true)
        override fun saveOriginals(label: String) { saved += label }
        override fun startRecording(readyDeadlineNanos: Long) = Unit
        override fun sendOnce() {
            check(directory.resolve("SEND_INTENT.json").isFile)
            check(draft == TongyiEngineeringRunner.PAYLOAD)
            visibleUi?.let { ui -> check(DeepSeekUiAdapter(ui, now + TongyiEngineeringRunner.PREP_NANOS)
                .sendCandidate(ui.read(), "EXTERNAL_FIXTURE_SEND") != null) }
            sends++; draft = ""; visibleUi?.draft = ""
        }
        override fun observeUntil(deadlineNanos: Long) { now = deadlineNanos }
        override fun stopOwnedRecording() = Unit
        override fun cleanupOwned(): Boolean {
            lifecycle.stopAndConfirm(owner, OwnedProcessLifecycle.Stop.TARGET_FORCE_STOP, now + 20_000_000_000L)
            lifecycle.requirePackageAbsent("com.deepseek.chat", now + 20_000_000_000L)
            return true
        }
        override fun execute(command: String, deadlineNanos: Long): OwnedProcessLifecycle.ShellResult {
            commands += command
            val raw = when(command) {
                "ps -A -o PID" -> "PID\n1\n" + if(alive) "42\n" else ""
                "ps -A -o PID,NAME" -> "PID NAME\n1 init\n" + if(alive) "42 com.deepseek.chat\n" else ""
                "cat /proc/42/stat" -> "42 (deepseek) S " + "0 ".repeat(18) + "12345 0\n"
                "cat /proc/42/status" -> "Uid:\t10241\t10241\t10241\t10241\n"
                "cat /proc/42/cmdline" -> "com.deepseek.chat\u0000"
                "am force-stop com.deepseek.chat" -> {
                    stops++
                    if (refuseOwnedStop) return OwnedProcessLifecycle.ShellResult(true, 1, "", "EXTERNAL_OS_STOP_REFUSED")
                    alive = false; ""
                }
                else -> error("UNEXPECTED_OS_COMMAND_$command")
            }
            return OwnedProcessLifecycle.ShellResult(true, 0, raw)
        }
        override fun sleepMillis(milliseconds: Long) { now += milliseconds * 1_000_000 }
    }

    @Test fun realSharedWholeCorePreservesDeepSeekPackageAndStopsOnlyItsAttributedOwner() {
        val directory = Files.createTempDirectory("deepseek-core-fixture").resolve("run").toFile()
        val android = AndroidBoundary(directory)
        val result = TongyiEngineeringRunner(android, android, EngineeringTarget.DEEPSEEK).runOnce("SAMPLE_DS_W1", "W1", directory)
        assertEquals("OBSERVATION_CLOSED: ${result.reason}", "OBSERVATION_CLOSED", result.state)
        assertEquals("1", result.sendCalls)
        assertEquals(1, android.sends)
        assertTrue(result.ownedCleanup)
        assertFalse(android.alive)
        assertEquals(1, android.stops)
        assertTrue(android.commands.contains("am force-stop com.deepseek.chat"))
        assertFalse(android.commands.any { it.contains("com.aliyun.tongyi") })
        assertEquals(listOf("complete-draft"), android.saved)
    }

    @Test fun wholeCoreUsesCurrentTargetPreparationReadbackAndFreshSemanticSendWithIntentFirst() {
        val directory = Files.createTempDirectory("deepseek-current-ui").resolve("run").toFile()
        val visible = VisibleAndroid()
        val android = AndroidBoundary(directory, visible)
        val result = TongyiEngineeringRunner(android, android, EngineeringTarget.DEEPSEEK).runOnce("DS_CURRENT_W1", "W1", directory)
        assertEquals("OBSERVATION_CLOSED: ${result.reason}", "OBSERVATION_CLOSED", result.state)
        assertEquals("1", result.sendCalls)
        assertEquals(1, android.sends)
        assertTrue(result.ownedCleanup)
        assertEquals(listOf("new-parent:CLICK", "search:CLICK", "input:FOCUS"), visible.invoked)
        assertEquals(1, android.stops)
    }

    @Test fun visibleSearchRemainingOnRefusesBeforeInputAndStillClosesOnlyOwnedTarget() {
        val directory = Files.createTempDirectory("deepseek-mode-refusal").resolve("run").toFile()
        val visible = VisibleAndroid().apply { refuseSearchOff = true }
        val android = AndroidBoundary(directory, visible)
        val result = TongyiEngineeringRunner(android, android, EngineeringTarget.DEEPSEEK).runOnce("DS_MODE_W1", "W1", directory)
        assertEquals("DEEPSEEK_PUBLIC_READINESS_EXPIRED", result.reason)
        assertEquals("0", result.sendCalls)
        assertEquals("", android.draft)
        assertFalse(directory.resolve("SEND_INTENT.json").exists())
        assertTrue(result.ownedCleanup)
        assertEquals(listOf("new-parent:CLICK", "search:CLICK"), visible.invoked)
        assertEquals(1, android.stops)
    }

    @Test fun ambiguousCurrentSendDoesNotConsumeIntentOrInvokeAndRetainsOwnedCleanup() {
        val directory = Files.createTempDirectory("deepseek-send-refusal").resolve("run").toFile()
        val visible = VisibleAndroid().apply { duplicateSend = true }
        val android = AndroidBoundary(directory, visible)
        val result = TongyiEngineeringRunner(android, android, EngineeringTarget.DEEPSEEK).runOnce("DS_SEND_W1", "W1", directory)
        assertEquals("DEEPSEEK_SEMANTIC_WITNESS_NOT_UNIQUE", result.reason)
        assertEquals("0", result.sendCalls)
        assertEquals(0, android.sends)
        assertFalse(directory.resolve("SEND_INTENT.json").exists())
        assertTrue(result.ownedCleanup)
        assertEquals(1, android.stops)
    }
}
