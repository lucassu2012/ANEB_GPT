package com.aneb.probe.research.engineering

/** Same NativePhone preparation boundary; no SEND, recording or mode operations. */
class KimiUiAdapter(private val android: Android, private val preparationDeadline: Long) {
    interface Android {
        fun read(until: Long): DeepSeekUiAdapter.Frame
        fun focusCurrentEmptyEditor(frame: DeepSeekUiAdapter.Frame, editor: DeepSeekUiAdapter.Node, until: Long)
        fun nowNanos(): Long
        fun sleepMillis(milliseconds: Long)
    }

    private fun inside(value: TongyiPreparationRunner.Bounds, container: TongyiPreparationRunner.Bounds) =
        value.right > value.left && value.bottom > value.top && value.left >= container.left &&
            value.top >= container.top && value.right <= container.right && value.bottom <= container.bottom

    private fun gate(until: Long) {
        val now = android.nowNanos()
        check(now < preparationDeadline) { "PREPARATION_180_EXPIRED" }
        check(now < until) { "KIMI_UI_PREPARATION_FIVE_SECOND_LIMIT" }
    }

    private fun validate(frame: DeepSeekUiAdapter.Frame): DeepSeekUiAdapter.Frame {
        check(frame.owner.isNotBlank() && frame.windowId >= 0 && frame.width > 0 && frame.height > 0 &&
            frame.rotation in 0..3 && frame.nodes.size in 1..2048 &&
            frame.nodes.all { it.id.isNotBlank() && it.packageName == EngineeringTarget.KIMI.packageName &&
                it.windowId == frame.windowId } && frame.nodes.map { it.id }.distinct().size == frame.nodes.size) {
            "KIMI_CURRENT_OWNED_FRAME_REQUIRED"
        }
        val byId = frame.nodes.associateBy { it.id }
        val root = checkNotNull(byId[frame.rootId]) { "KIMI_CURRENT_ROOT_REQUIRED" }
        check(root.parent == null && root.visible && root.enabled &&
            inside(root.bounds, TongyiPreparationRunner.Bounds(0, 0, frame.width, frame.height))) {
            "KIMI_CURRENT_ROOT_REQUIRED"
        }
        for (node in frame.nodes) {
            val seen = HashSet<String>()
            var cursor = node
            while (cursor.id != root.id) {
                check(seen.add(cursor.id)) { "KIMI_CURRENT_GRAPH_REQUIRED" }
                cursor = checkNotNull(cursor.parent?.let(byId::get)) { "KIMI_CURRENT_GRAPH_REQUIRED" }
            }
        }
        return frame
    }

    private fun current(frame: DeepSeekUiAdapter.Frame): List<DeepSeekUiAdapter.Node> {
        val byId = frame.nodes.associateBy { it.id }
        return frame.nodes.filter { node ->
            var cursor: DeepSeekUiAdapter.Node? = node
            var eligible = true
            while (cursor != null) {
                eligible = eligible && cursor.visible && cursor.enabled
                cursor = cursor.parent?.let(byId::getValue)
            }
            eligible
        }
    }

    private fun empty(node: DeepSeekUiAdapter.Node) = if (node.showingHint)
        !node.hint.isNullOrEmpty() && (node.text.isEmpty() || node.text == node.hint) else node.text.isEmpty()

    private fun editor(frame: DeepSeekUiAdapter.Frame): DeepSeekUiAdapter.Node {
        val input = current(frame).filter { it.editable }.singleOrNull()
            ?: error("KIMI_CURRENT_EDITOR_NOT_UNIQUE")
        check(input.className == "android.widget.EditText" && empty(input)) { "UNKNOWN_DRAFT_NOT_TOUCHED" }
        val parent = checkNotNull(frame.nodes.singleOrNull { it.id == input.parent }) {
            "KIMI_CURRENT_EDITOR_PARENT_REQUIRED"
        }
        check(inside(input.bounds, parent.bounds) && inside(parent.bounds, frame.nodes.single { it.id == frame.rootId }.bounds)) {
            "KIMI_CURRENT_EDITOR_BOUNDS_REQUIRED"
        }
        return input
    }

    private fun requireHome(frame: DeepSeekUiAdapter.Frame, input: DeepSeekUiAdapter.Node) {
        val visible = current(frame)
        val greeting = visible.singleOrNull { it.className == "android.widget.TextView" &&
            it.text.endsWith("今天要和 Kimi 一起做点什么") } ?: error("KIMI_CURRENT_EMPTY_HOME_REQUIRED")
        val hint = visible.singleOrNull { it.className == "android.widget.TextView" && it.text == "尽管问，带图也行" }
            ?: error("KIMI_CURRENT_EMPTY_HOME_REQUIRED")
        val quick = visible.singleOrNull { it.className == "android.widget.TextView" && it.text == "快速" }
        val advanced = visible.singleOrNull { it.className == "android.widget.TextView" && it.text == "进阶" }
        check(hint.parent == input.id && inside(hint.bounds, input.bounds) &&
            inside(greeting.bounds, frame.nodes.single { it.id == frame.rootId }.bounds) &&
            quick != null && advanced != null && quick.parent != null && quick.parent == advanced.parent) {
            "KIMI_CURRENT_EMPTY_HOME_REQUIRED"
        }
        // These are visible home labels only, never selected/checked/backend-mode authority.
    }

    private fun read(until: Long): DeepSeekUiAdapter.Frame {
        gate(until)
        val frame = android.read(until)
        gate(until)
        return validate(frame)
    }

    fun prepareEmpty(): DeepSeekUiAdapter.Frame {
        val until = minOf(preparationDeadline, android.nowNanos() + TongyiEngineeringRunner.INPUT_NANOS)
        val before = read(until)
        val input = editor(before)
        requireHome(before, input)
        if (input.focused) {
            gate(until)
            return before
        }
        check(input.focusAvailable) { "KIMI_CURRENT_PUBLIC_FOCUS_REQUIRED" }
        gate(until)
        android.focusCurrentEmptyEditor(before, input, until)
        gate(until)
        while (true) {
            val after = read(until)
            val focused = editor(after)
            check(after.owner == before.owner && after.windowId == before.windowId &&
                after.width == before.width && after.height == before.height && after.rotation == before.rotation &&
                focused.id == input.id && focused.parent == input.parent) { "KIMI_CURRENT_EDITOR_BINDING_CHANGED" }
            // Copied keys constrain this value flow, not raw Accessibility identity.
            // Native must establish the same actual editor/parent in its public-API transaction.
            gate(until)
            if (focused.focused) return after
            android.sleepMillis(50)
        }
    }
}

