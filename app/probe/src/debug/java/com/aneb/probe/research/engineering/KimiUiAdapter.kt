package com.aneb.probe.research.engineering

/** Closed Kimi text UI semantics; Native owns actual identities, public operations and all recording. */
class KimiUiAdapter(private val android: Android, private val preparationDeadline: Long) {
    interface Android {
        fun read(until: Long): DeepSeekUiAdapter.Frame
        fun focusCurrentEmptyEditor(frame: DeepSeekUiAdapter.Frame, editor: DeepSeekUiAdapter.Node, until: Long)
        fun clickCurrentSemanticSend(frame: DeepSeekUiAdapter.Frame, send: DeepSeekUiAdapter.Node, until: Long) {
            error("KIMI_PUBLIC_SEMANTIC_SEND_NOT_SUPPORTED")
        }
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

    private fun editor(frame: DeepSeekUiAdapter.Frame, requireEmpty: Boolean = true): DeepSeekUiAdapter.Node {
        val input = current(frame).filter { it.editable }.singleOrNull()
            ?: error("KIMI_CURRENT_EDITOR_NOT_UNIQUE")
        check(input.className == "android.widget.EditText" && (!requireEmpty || empty(input))) { "UNKNOWN_DRAFT_NOT_TOUCHED" }
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

    fun ordinaryTextReady(frame: DeepSeekUiAdapter.Frame): Boolean {
        validate(frame)
        val input = editor(frame, requireEmpty = false)
        check(empty(input) || !input.showingHint && input.text == TongyiEngineeringRunner.PAYLOAD) { "UNKNOWN_DRAFT_NOT_TOUCHED" }
        val nodes = current(frame)
        val quick = nodes.singleOrNull { it.text == "快速" && it.className == "android.widget.TextView" }
        val advanced = nodes.singleOrNull { it.text == "进阶" && it.className == "android.widget.TextView" }
        check(quick != null && advanced != null && quick.parent != null && quick.parent == advanced.parent) {
            "KIMI_CURRENT_TEXT_COMPOSER_REQUIRED"
        }
        return true // Visible text-composer capability, never a selected/backend-mode claim.
    }

    /** The semantic object itself is the role; no A11y CLICK capability is invented. */
    fun sendCandidate(frame: DeepSeekUiAdapter.Frame, description: String): DeepSeekUiAdapter.Node? {
        check(description == "发送讯息") { "KIMI_FIXED_SEND_DESCRIPTION_REQUIRED" }
        ordinaryTextReady(frame)
        val input = editor(frame, requireEmpty = false)
        if (empty(input)) return null
        check(input.focused && input.enabled && input.setTextAvailable && !input.showingHint &&
            input.text == TongyiEngineeringRunner.PAYLOAD && input.text.toByteArray(Charsets.UTF_8).size == 239) {
            "KIMI_EXACT_OWNED_DRAFT_REQUIRED"
        }
        val nodes = current(frame)
        val semantic = nodes.singleOrNull { it.description == description } ?: error("KIMI_SEND_SEMANTIC_NOT_UNIQUE")
        val parent = nodes.singleOrNull { it.id == semantic.parent } ?: error("KIMI_SEND_PARENT_REQUIRED")
        fun path(node: DeepSeekUiAdapter.Node): List<DeepSeekUiAdapter.Node> {
            val result = ArrayList<DeepSeekUiAdapter.Node>()
            var value = node
            while (value.parent != null) {
                value = nodes.single { it.id == value.parent }
                result.add(value) // validate already proved this graph bounded and acyclic.
            }
            return result
        }
        val sendPath = path(semantic)
        val composer = path(input).firstOrNull { it in sendPath && it.id != frame.rootId }
            ?: error("KIMI_SHARED_TEXT_COMPOSER_REQUIRED")
        val root = frame.nodes.single { it.id == frame.rootId }
        check(semantic.className == "android.view.View" && !semantic.editable && !semantic.checkable &&
            semantic.id != input.id && input !in sendPath && parent.id != composer.id &&
            !parent.editable && parent.text.isEmpty() && parent.description.isEmpty() && parent.hint.isNullOrEmpty() &&
            frame.nodes.filter { it.parent == parent.id }.singleOrNull() == semantic &&
            composer.bounds != root.bounds && semantic.bounds != input.bounds &&
            inside(input.bounds, composer.bounds) && inside(parent.bounds, composer.bounds) &&
            inside(semantic.bounds, parent.bounds) && inside(composer.bounds, root.bounds)) {
            "KIMI_SEND_COMPOSER_RELATION_REQUIRED"
        }
        val centerX = semantic.bounds.left + (semantic.bounds.right - semantic.bounds.left) / 2
        val centerY = semantic.bounds.top + (semantic.bounds.bottom - semantic.bounds.top) / 2
        check(centerX > 0 && centerX < frame.width && centerY > 0 && centerY < frame.height &&
            (listOf(semantic) + sendPath).all { node ->
                centerX > node.bounds.left && centerX < node.bounds.right &&
                    centerY > node.bounds.top && centerY < node.bounds.bottom
            }) { "KIMI_CURRENT_SEND_CENTER_CLIPPED" }
        return semantic
    }

    fun sendOnce(frame: DeepSeekUiAdapter.Frame, description: String) {
        val until = minOf(preparationDeadline, android.nowNanos() + TongyiEngineeringRunner.INPUT_NANOS)
        gate(until)
        val semantic = checkNotNull(sendCandidate(frame, description)) { "KIMI_CURRENT_SEND_REQUIRED" }
        gate(until)
        android.clickCurrentSemanticSend(frame, semantic, until) // One current-semantic primary operation, never a fallback.
        gate(until)
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
                after.width == before.width && after.height == before.height && after.rotation == before.rotation) {
                "KIMI_CURRENT_EDITOR_BINDING_CHANGED"
            }
            // IDs/parent IDs are acquisition-local and may change after focus.
            // Native independently proves the actual editor/parent identity, not copied indices.
            gate(until)
            if (focused.focused) return after
            android.sleepMillis(50)
        }
    }
}
