package com.aneb.probe.research.engineering

/** Closed execution identity, never an arbitrary package or a scientific/measurement mode. */
enum class EngineeringTarget(val id: String, val packageName: String) {
    TONGYI("tongyi", "com.aliyun.tongyi"),
    DEEPSEEK("deepseek", "com.deepseek.chat");

    companion object {
        fun fromId(id: String): EngineeringTarget = entries.singleOrNull { it.id == id }
            ?: error("UNSUPPORTED_TARGET_PROFILE")
    }
}

/** Target semantics only. Android owns all live acquisition/action/owner/deadline checks. */
class DeepSeekUiAdapter(private val android: Android, private val preparationDeadline: Long) {
    data class Node(
        val id: String, val parent: String?, val bounds: TongyiPreparationRunner.Bounds,
        val text: String = "", val description: String = "", val hint: String? = null,
        val showingHint: Boolean = false, val visible: Boolean = true, val enabled: Boolean = true,
        val clickable: Boolean = false, val checkable: Boolean = false, val checked: Boolean = false,
        val editable: Boolean = false, val focused: Boolean = false, val setTextAvailable: Boolean = false,
        val clickAvailable: Boolean = false, val focusAvailable: Boolean = false,
        val windowId: Int = 1, val packageName: String = EngineeringTarget.DEEPSEEK.packageName,
        val className: String = "",
    )
    data class Frame(val owner: String, val windowId: Int, val width: Int, val height: Int,
        val rotation: Int, val rootId: String, val nodes: List<Node>)
    enum class Action { CLICK, FOCUS }
    interface Android {
        fun read(): Frame
        fun invoke(frame: Frame, node: Node, action: Action)
        fun nowNanos(): Long
        fun sleepMillis(milliseconds: Long)
    }

    private fun inside(a: TongyiPreparationRunner.Bounds, b: TongyiPreparationRunner.Bounds) =
        a.right > a.left && a.bottom > a.top && a.left >= b.left && a.top >= b.top && a.right <= b.right && a.bottom <= b.bottom

    private fun validate(frame: Frame): Frame {
        check(frame.owner.isNotBlank() && frame.windowId >= 0 && frame.width == 1200 && frame.height == 2640 && frame.rotation == 0 &&
            frame.nodes.size in 1..2048 && frame.nodes.map { it.id }.distinct().size == frame.nodes.size) { "DEEPSEEK_CURRENT_FRAME_REQUIRED" }
        val root = frame.nodes.single { it.id == frame.rootId }
        check(root.parent == null && root.visible && root.enabled && inside(root.bounds, TongyiPreparationRunner.Bounds(0,0,frame.width,frame.height)) &&
            frame.nodes.all { it.packageName == EngineeringTarget.DEEPSEEK.packageName && it.windowId == frame.windowId }) {
            "DEEPSEEK_CURRENT_WINDOW_REQUIRED"
        }
        return frame
    }
    private fun current(frame: Frame) = validate(frame).nodes.filter { it.visible && it.enabled }
    private fun empty(node: Node) = if (node.showingHint) !node.hint.isNullOrEmpty() && (node.text.isEmpty() || node.text == node.hint)
        else node.text.isEmpty()
    private fun editor(frame: Frame) = validate(frame).nodes.filter { it.visible && it.editable }.let {
        check(it.size <= 1) { "DEEPSEEK_EDITOR_NOT_UNIQUE" }; it.singleOrNull()
    }
    private fun witness(frame: Frame, text: String? = null, description: String? = null) =
        current(frame).singleOrNull { (text == null || it.text == text) && (description == null || it.description == description) }
            ?: error("DEEPSEEK_SEMANTIC_WITNESS_NOT_UNIQUE")
    private fun actionTarget(frame: Frame, witness: Node, focus: Boolean = false): Node {
        fun capable(n: Node) = if (focus) n.focusAvailable else n.clickable && n.clickAvailable
        val node = if (capable(witness)) witness else current(frame).singleOrNull { it.id == witness.parent && capable(it) }
            ?: error("DEEPSEEK_SUPPORTED_DIRECT_ACTION_REQUIRED")
        val root = frame.nodes.single { it.id == frame.rootId }
        check(inside(node.bounds, root.bounds) && node.windowId == witness.windowId &&
            (witness.bounds.right <= witness.bounds.left || witness.bounds.bottom <= witness.bounds.top || inside(witness.bounds, node.bounds))) {
            "DEEPSEEK_ACTION_BOUNDS_CHANGED"
        }
        return node
    }
    private fun mode(frame: Frame, text: String): Node {
        val label = witness(frame, text = text)
        return current(frame).singleOrNull { it.id == label.parent && it.checkable && it.clickable && it.clickAvailable }
            ?.also { check(inside(it.bounds, frame.nodes.single { n -> n.id == frame.rootId }.bounds)) { "DEEPSEEK_MODE_BOUNDS_REQUIRED" } }
            ?: error("DEEPSEEK_CHECKED_MODE_PARENT_REQUIRED")
    }
    private fun ancestors(frame: Frame, node: Node): List<Node> {
        val path = ArrayList<Node>()
        var child = node
        while (child.parent != null) {
            val parent = current(frame).singleOrNull { it.id == child.parent } ?: error("DEEPSEEK_ANCESTRY_CHANGED")
            check(parent !in path && path.size < frame.nodes.size) { "DEEPSEEK_ANCESTRY_CHANGED" }
            path += parent; child = parent
        }
        check(path.lastOrNull()?.id == frame.rootId) { "DEEPSEEK_ROOT_CHANGED" }
        return path
    }
    private fun composer(frame: Frame, input: Node, other: Node? = null): Node {
        val thinking = mode(frame, "深度思考")
        val search = mode(frame, "智能搜索")
        val thinkingPath = ancestors(frame, thinking)
        val searchPath = ancestors(frame, search)
        val otherPath = other?.let { ancestors(frame, it) }
        val root = frame.nodes.single { it.id == frame.rootId }
        return ancestors(frame, input).firstOrNull { it.id != root.id && it.bounds != root.bounds &&
            it in thinkingPath && it in searchPath && (otherPath == null || it in otherPath) &&
            inside(input.bounds, it.bounds) && inside(thinking.bounds, it.bounds) && inside(search.bounds, it.bounds) &&
            (other == null || inside(other.bounds, it.bounds)) } ?: error("DEEPSEEK_SHARED_COMPOSER_REQUIRED")
    }
    private fun emptyComposer(frame: Frame): Boolean {
        editor(frame)?.let { return empty(it) }
        val hints = current(frame).filter { it.text == "发消息或按住说话" && it.className == "android.widget.TextView" && !it.editable }
        if (hints.size != 1) return false
        val hint = hints.single()
        val holder = current(frame).singleOrNull { it.id == hint.parent &&
            it.className == "androidx.compose.ui.viewinterop.ViewFactoryHolder" && !it.editable &&
            it.text.isEmpty() && it.description.isEmpty() } ?: return false
        if (frame.nodes.filter { it.parent == holder.id }.singleOrNull() != hint || !inside(hint.bounds, holder.bounds)) return false
        composer(frame, hint) // Same bounded, non-root composer as the two checked mode controls, not arbitrary body text.
        return true
    }
    fun modesOff(frame: Frame) = !mode(frame, "深度思考").checked && !mode(frame, "智能搜索").checked
    private fun gate() { check(android.nowNanos() < preparationDeadline) { "PREPARATION_180_EXPIRED" } }
    private fun read(): Frame { gate(); return validate(android.read()).also { gate() } }
    private fun await(budget: Long, predicate: (Frame) -> Boolean): Frame {
        val until = minOf(preparationDeadline, android.nowNanos() + budget)
        while (true) {
            check(android.nowNanos() < until) { "DEEPSEEK_PUBLIC_READINESS_EXPIRED" }
            val frame = read()
            if (predicate(frame)) return frame
            android.sleepMillis(50)
        }
    }
    fun prepare() {
        val initial = read()
        check(editor(initial)?.let(::empty) != false) { "UNKNOWN_DRAFT_NOT_TOUCHED" }
        check(emptyComposer(initial)) { "DEEPSEEK_INITIAL_EMPTY_COMPOSER_REQUIRED" }
        android.invoke(initial, actionTarget(initial, witness(initial, description = "开启新对话")), Action.CLICK)
        await(TongyiEngineeringRunner.READY_NANOS) { frame ->
            val visible = current(frame)
            visible.count { it.text == "新对话" } == 1 && visible.count { it.text == "今天有什么可以帮到你？" } == 1 &&
                emptyComposer(frame)
        }
        for (label in listOf("深度思考", "智能搜索")) {
            val frame = read()
            val parent = mode(frame, label)
            if (parent.checked) {
                check(emptyComposer(frame)) { "UNKNOWN_DRAFT_NOT_TOUCHED" }
                android.invoke(frame, parent, Action.CLICK)
                await(TongyiEngineeringRunner.INPUT_NANOS) { !mode(it, label).checked }
            }
        }
        val frame = read()
        check(modesOff(frame)) { "DEEPSEEK_VISIBLE_OFF_OFF_REQUIRED" }
        val input = editor(frame)
        check(input?.let(::empty) != false) { "UNKNOWN_DRAFT_NOT_TOUCHED" }
        if (input?.focused != true) {
            check(emptyComposer(frame)) { "UNKNOWN_DRAFT_NOT_TOUCHED" }
            val label = input ?: witness(frame, text = "发消息或按住说话")
            val canFocus = label.focusAvailable || current(frame).any { it.id == label.parent && it.focusAvailable }
            val focus = actionTarget(frame, label, focus = canFocus)
            android.invoke(frame, focus, if (canFocus) Action.FOCUS else Action.CLICK)
        }
        await(TongyiEngineeringRunner.INPUT_NANOS) {
            modesOff(it) && editor(it)?.let { n -> n.enabled && n.focused && n.setTextAvailable && empty(n) } == true
        }
    }

    /** Read-only role; no old bounds, indices, image hashes or guessed blank icon. */
    fun sendCandidate(frame: Frame, description: String): Node? {
        check(description.isNotBlank() && description.length <= 128) { "DEEPSEEK_CURRENT_SEND_DESCRIPTION_REQUIRED" }
        check(modesOff(frame)) { "DEEPSEEK_VISIBLE_OFF_OFF_REQUIRED" }
        val input = checkNotNull(editor(frame)) { "DEEPSEEK_EDITOR_REQUIRED" }
        if (empty(input)) return null
        check(input.enabled && input.focused && input.setTextAvailable && !input.showingHint && input.text == TongyiEngineeringRunner.PAYLOAD &&
            input.text.toByteArray(Charsets.UTF_8).size == 239) { "DEEPSEEK_EXACT_239B_REQUIRED" }
        val candidate = actionTarget(frame, witness(frame, description = description))
        check(!candidate.editable && candidate.id != input.id) { "DEEPSEEK_SEND_EDITOR_COLLISION" }
        composer(frame, input, candidate)
        return candidate
    }
}
