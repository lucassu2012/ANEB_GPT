package com.aneb.probe.research.engineering

import java.io.File
import com.aneb.probe.research.engineering.TongyiPreparationRunner.Bounds
import com.aneb.probe.research.engineering.TongyiPreparationRunner.Frame
import com.aneb.probe.research.engineering.TongyiPreparationRunner.Node

/** Separate private debug authority acquisition. Never promotes a reference or exposes SEND. */
class TongyiUiReferenceQualificationRunner(
    private val android: Android,
    private val clock: TongyiEngineeringRunner.MonotonicClock,
) {
    enum class Stage { HOME, DRAWER, NEW_EMPTY, EDITOR_EMPTY, OWN_DRAFT, OWN_CLEARED }
    enum class NamedClick { MENU, NEW_SESSION, COMPOSER_FOCUS }
    data class StageObservation(val stage: Stage, val frame: TongyiPreparationRunner.Frame)
    /** Acquisition namespace only; these are not accepted UIQ stages or reusable references. */
    data class SourceObservation(val sourceLabel: String, val frame: Frame)
    enum class SourcePhase { OWNED_LAUNCH, INITIAL_READINESS, SOURCE_ACQUISITION, SOURCE_VALIDATION, SECOND_READINESS, PAIR_VALIDATION }
    data class SourceRejection(val phase: SourcePhase, val sourceLabel: String, val frame: Frame?,
        val retentionAttempted: Boolean, val retained: Boolean, val retentionFailure: String?)
    data class Result(
        val state: String,
        val sendCalls: String,
        val ownedCleanup: Boolean,
        val reason: String?,
        val retainedStages: List<StageObservation>,
        val referenceAccepted: Boolean = false,
        val retainedSources: List<SourceObservation> = emptyList(),
        val sourceRejection: SourceRejection? = null,
    )

    /** Only this external Android/UI/OS boundary may be substituted in HOST tests. */
    interface Android {
        fun launchOwned(deadlineNanos: Long)
        /** Node/display/root/owner projection only: no PNG, XML, canonical, retention or scene change. */
        fun currentState(deadlineNanos: Long): Frame
        /** Narrow owned HOME graphics precondition under the caller's unchanged readiness deadline. */
        fun awaitOwnedHomeGraphics(deadlineNanos: Long): Unit = error("QUALIFICATION_GRAPHICS_UNSUPPORTED")
        /** One complete live observation/PNG; menuPixels requests canonical bytes from that same PNG. */
        fun currentFrame(menuPixels: Boolean): TongyiPreparationRunner.Frame
        /** True requires this exact returned Frame's complete owned raw stage bundle to be retained. */
        fun retainStage(stage: Stage, observed: TongyiPreparationRunner.Frame): Boolean
        /** Exact current HOME originals under an independent source label, never action authority. */
        fun retainSource(sourceLabel: String, observed: Frame): Boolean = false
        /** Best-effort cached originals only; rejection diagnostics never grant an accepted action binding. */
        fun retainRejectedObservation(stage: Stage, observed: Frame, reason: String, deadlineNanos: Long): Boolean = false
        /** Source metadata scaffold; default preserves the existing best-effort rejection seam. No fresh acquisition. */
        fun retainRejectedSourceObservation(phase: SourcePhase, sourceLabel: String, observed: Frame,
            reason: String, deadlineNanos: Long): Boolean = retainRejectedObservation(Stage.HOME, observed, reason, deadlineNanos)
        /** Only three roles; fresh owner/Node revalidation performs no extra PNG or diagnostic capture. */
        fun clickNamedCurrent(role: NamedClick, observed: TongyiPreparationRunner.Frame,
            node: TongyiPreparationRunner.Node): Boolean
        /** Only the fixed PAYLOAD or an empty string for a positively attributed own draft. */
        fun setTextCurrent(observed: TongyiPreparationRunner.Frame,
            editor: TongyiPreparationRunner.Node, text: String): Boolean
        fun sleepMillis(milliseconds: Long)
        fun cleanupOwned(): Boolean
    }

    fun runOnce(runId: String, directory: File): Result = run(runId, directory, sourceInspection = false)

    /** Explicit read-only route through the same private core and public Android boundary. */
    fun runSourceInspectionOnce(runId: String, directory: File): Result = run(runId, directory, sourceInspection = true)

    private fun run(runId: String, directory: File, sourceInspection: Boolean): Result {
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        check(directory.mkdir()) { "QUALIFICATION_ALREADY_CONSUMED_NO_RETRY" }
        val deadline = clock.nowNanos() + TongyiEngineeringRunner.PREP_NANOS
        val retained = ArrayList<StageObservation>()
        val sources = ArrayList<SourceObservation>()
        var owner: String? = null
        var reason: String? = null
        var completed = false
        var cleaned = false
        var sourcePhase = SourcePhase.OWNED_LAUNCH
        var sourceLabel = "source-home-1"
        var sourceUntil = deadline
        var sourceFrame: Frame? = null
        var sourceRejection: SourceRejection? = null
        fun sourceContext(phase: SourcePhase, label: String, until: Long, observed: Frame? = null) {
            sourcePhase = phase
            sourceLabel = label
            sourceUntil = until
            sourceFrame = observed
        }
        fun gate(limit: Long = deadline) {
            check(clock.nowNanos() < minOf(deadline, limit) && !File(directory, "CANCEL").exists()) {
                "QUALIFICATION_EXPIRED_OR_CANCELLED"
            }
        }
        fun limit(budget: Long) = minOf(deadline, clock.nowNanos() + budget)
        fun inside(bounds: Bounds, container: Bounds) = bounds.left >= container.left &&
            bounds.top >= container.top && bounds.right <= container.right && bounds.bottom <= container.bottom &&
            bounds.right > bounds.left && bounds.bottom > bounds.top
        fun validate(value: Frame): Frame {
            check(value.owner.isNotEmpty() && (owner == null || owner == value.owner)) { "CURRENT_OWNER_CHANGED" }
            owner = value.owner
            check(value.width == 1200 && value.height == 2640 && value.rotation == 0 &&
                inside(value.rootBounds, Bounds(0, 0, value.width, value.height))) { "CURRENT_GEOMETRY_INVALID" }
            check(value.nodes.isNotEmpty() && value.nodes.all { it.id.isNotEmpty() } &&
                value.nodes.map { it.id }.distinct().size == value.nodes.size) { "CURRENT_NODE_IDENTITIES_INVALID" }
            val root = value.nodes.singleOrNull { it.parent == null }
            check(root != null && root.packageName == TongyiEngineeringRunner.PACKAGE && root.visible &&
                root.bounds == value.rootBounds) { "CURRENT_TARGET_ROOT_REQUIRED" }
            return value // Raw bounds remain untouched; this is not a reusable viewport guard.
        }
        fun nodes(value: Frame) = value.nodes.filter { it.visible && it.packageName == TongyiEngineeringRunner.PACKAGE }
        fun emptyHomeEvidence(value: Frame) {
            fun requireEmpty(condition: Boolean) {
                check(condition) { "UNKNOWN_EXISTING_CONVERSATION_NOT_TOUCHED" }
            }
            val all = value.nodes
            val visible = all.filter { it.visible }
            val byId = all.associateBy { it.id }
            val classified = HashSet<String>()
            fun children(node: Node) = all.filter { it.parent == node.id }
            fun ordinary(node: Node) = node.packageName == TongyiEngineeringRunner.PACKAGE && node.enabled &&
                !node.editable && node.hint.isNullOrEmpty() && node.description.isEmpty() && node.resourceId.isEmpty()
            fun mark(node: Node) { requireEmpty(ordinary(node)); classified.add(node.id) }
            fun witness(text: String): Node {
                val node = visible.singleOrNull { it.text == text && it.className == "android.widget.TextView" }
                requireEmpty(node != null)
                return node!!.also { mark(it); requireEmpty(children(it).isEmpty()) }
            }
            fun parent(node: Node, className: String): Node {
                val result = byId[node.parent]
                requireEmpty(result != null && result.visible && result.className == className && result.text.isEmpty())
                return result!!.also { mark(it) }
            }
            fun exactChildren(node: Node, expected: List<Node>) {
                requireEmpty(children(node).map { it.id }.toSet() == expected.map { it.id }.toSet())
            }
            fun image(node: Node) {
                requireEmpty(node.visible && node.className == "android.widget.ImageView" && node.text.isEmpty() &&
                    !node.clickable && children(node).isEmpty())
                mark(node)
            }
            requireEmpty(visible.all { it.packageName == TongyiEngineeringRunner.PACKAGE })
            val title = witness("我是千问")
            val subtitle = witness("为你答疑 办事 创作，随时找我聊天")
            val titleRow = parent(title, "android.widget.LinearLayout")
            val welcome = parent(subtitle, "android.widget.LinearLayout")
            requireEmpty(titleRow.parent == welcome.id && !titleRow.clickable && !welcome.clickable)
            exactChildren(titleRow, listOf(title))
            val suggestions = children(welcome).singleOrNull { it.id != titleRow.id && it.id != subtitle.id &&
                it.visible && it.className == "android.widget.LinearLayout" && it.text.isEmpty() && !it.clickable }
            requireEmpty(suggestions != null)
            mark(suggestions!!)
            exactChildren(welcome, listOf(titleRow, subtitle, suggestions))
            val suggestionLeaves = children(suggestions)
            requireEmpty(suggestionLeaves.size == 3 && suggestionLeaves.all { it.visible && it.clickable &&
                it.className == "android.widget.TextView" && it.text.isNotEmpty() && children(it).isEmpty() })
            suggestionLeaves.forEach(::mark) // Only this positively bound welcome surface, never arbitrary text.
            val welcomeFrame = parent(welcome, "android.widget.FrameLayout")
            val bodyBranch = children(welcomeFrame).singleOrNull { it.id != welcome.id }
            requireEmpty(bodyBranch != null)
            exactChildren(welcomeFrame, listOf(welcome, bodyBranch!!))
            var body = bodyBranch!!
            repeat(4) {
                requireEmpty(body.visible && body.className == "android.widget.FrameLayout" && body.text.isEmpty() &&
                    !body.clickable && children(body).size == 1)
                mark(body)
                body = children(body).single()
            }
            requireEmpty(body.className == "androidx.recyclerview.widget.RecyclerView" && !body.visible &&
                body.bounds.bottom == body.bounds.top && body.bounds.right > body.bounds.left &&
                body.text.isEmpty() && !body.clickable && children(body).isEmpty())
            mark(body) // Zero-height absence evidence must not go through the positive-height action guard.
            val main = parent(welcomeFrame, "android.widget.FrameLayout")
            val quick = witness("快速")
            val pickerRow = parent(quick, "android.widget.LinearLayout")
            val pickerInner = parent(pickerRow, "android.widget.FrameLayout")
            val pickerItem = parent(pickerInner, "android.widget.FrameLayout")
            val picker = parent(pickerItem, "androidx.recyclerview.widget.RecyclerView")
            val pickerWrapper = parent(picker, "android.widget.FrameLayout")
            val controls = parent(pickerWrapper, "android.view.ViewGroup")
            exactChildren(pickerWrapper, listOf(picker))
            val slots = listOf("快速", "工作助理", "AI生视频", "生活帮手").map { label ->
                val text = witness(label)
                val row = parent(text, "android.widget.LinearLayout")
                val inner = parent(row, "android.widget.FrameLayout")
                val item = parent(inner, "android.widget.FrameLayout")
                requireEmpty(item.parent == picker.id && item.clickable && !inner.clickable && !row.clickable)
                exactChildren(item, listOf(inner))
                exactChildren(inner, listOf(row))
                val icons = children(row).filter { it.id != text.id }
                requireEmpty(icons.size == (if (label == "快速") 2 else 1))
                icons.forEach(::image)
                item
            }
            exactChildren(picker, slots)
            val placeholder = witness("发消息或按住说话...")
            val composer = parent(placeholder, "android.view.ViewGroup")
            val composerOuter = parent(composer, "android.view.ViewGroup")
            requireEmpty(composer.clickable && composerOuter.clickable && composerOuter.parent == controls.id)
            exactChildren(composerOuter, listOf(composer))
            exactChildren(controls, listOf(pickerWrapper, composerOuter))
            val accessoryRow = children(composer).singleOrNull { it.className == "android.widget.LinearLayout" }
            val leading = children(composer).singleOrNull { it.className == "android.widget.FrameLayout" }
            requireEmpty(accessoryRow != null && leading != null)
            exactChildren(composer, listOf(placeholder, accessoryRow!!, leading!!))
            mark(accessoryRow)
            val accessoryButtons = children(accessoryRow)
            requireEmpty(accessoryButtons.size == 2)
            (accessoryButtons + leading).forEach { button ->
                requireEmpty(button.visible && button.className == "android.widget.FrameLayout" && button.text.isEmpty() &&
                    button.clickable && children(button).size == 1)
                mark(button)
                image(children(button).single())
            }
            val controlRow = parent(controls, "android.widget.LinearLayout")
            val controlOuter = parent(controlRow, "android.widget.LinearLayout")
            val controlArea = parent(controlOuter, "android.widget.FrameLayout")
            requireEmpty(controlArea.parent == main.id)
            exactChildren(controlOuter, listOf(controlRow))
            val inactive = children(controlRow).singleOrNull { it.id != controls.id }
            requireEmpty(inactive != null && !inactive.visible && inactive.className == "android.widget.FrameLayout" &&
                inactive.bounds.top == inactive.bounds.bottom && inactive.text.isEmpty() && !inactive.clickable && children(inactive).isEmpty())
            mark(inactive!!)
            exactChildren(controlRow, listOf(controls, inactive))
            val controlBackground = children(controlArea).filter { it.id != controlOuter.id }
            requireEmpty(controlBackground.size == 3 && controlBackground.all { it.className == "android.view.View" &&
                it.text.isEmpty() && !it.clickable && children(it).isEmpty() })
            controlBackground.forEach(::mark)
            val footer = witness("内容由 AI 生成，请注意核实")
            val footerFrame = parent(footer, "android.widget.FrameLayout")
            requireEmpty(footerFrame.parent == main.id)
            exactChildren(footerFrame, listOf(footer))
            val menuNode = visible.singleOrNull { it.className == "android.widget.FrameLayout" &&
                it.bounds == Bounds(56, 193, 156, 293) && it.clickable }
            requireEmpty(menuNode != null)
            val menuWrapper = parent(menuNode!!, "android.widget.LinearLayout")
            val toolbar = parent(menuWrapper, "android.widget.FrameLayout")
            requireEmpty(toolbar.parent == main.id)
            fun chrome(node: Node) {
                requireEmpty(node.visible && node.text.isEmpty() && node.className in setOf(
                    "android.widget.FrameLayout", "android.widget.LinearLayout", "android.widget.ImageView"))
                mark(node)
                children(node).forEach(::chrome)
            }
            chrome(toolbar)
            // Only ownership scaffolding ancestors, not an action/selector ancestor fallback.
            for (seed in classified.toList()) {
                var ancestor = byId[seed]?.parent
                val seen = HashSet<String>()
                while (ancestor != null && ancestor !in classified) {
                    requireEmpty(seen.add(ancestor))
                    val node = byId[ancestor]
                    requireEmpty(node != null && node.visible && node.text.isEmpty() && !node.clickable && node.className in
                        setOf("android.widget.FrameLayout", "android.widget.LinearLayout", "android.view.ViewGroup", "androidx.viewpager.widget.ViewPager"))
                    val current = node!!
                    if (current.resourceId.isEmpty()) mark(current) else {
                        fun emptyScaffold(candidate: Node) = candidate.visible && candidate.enabled &&
                            candidate.packageName == TongyiEngineeringRunner.PACKAGE && !candidate.clickable &&
                            !candidate.editable && !candidate.focused && !candidate.setTextAvailable &&
                            !candidate.showingHint && candidate.hint.isNullOrEmpty() && candidate.text.isEmpty() &&
                            candidate.description.isEmpty() && candidate.bounds == value.rootBounds
                        val actionBar = if (current.className == "android.widget.LinearLayout" &&
                            current.resourceId == "com.aliyun.tongyi:id/action_bar_root") current
                            else if (current.className == "android.widget.FrameLayout" &&
                                current.resourceId == "android:id/content") byId[current.parent] else null
                        requireEmpty(actionBar != null)
                        val bar = actionBar!!
                        val content = children(bar).singleOrNull()
                        val scaffoldFrame = byId[bar.parent]
                        val scaffoldRow = byId[scaffoldFrame?.parent]
                        val scaffoldRoot = byId[scaffoldRow?.parent]
                        requireEmpty(bar.className == "android.widget.LinearLayout" &&
                            bar.resourceId == "com.aliyun.tongyi:id/action_bar_root" && emptyScaffold(bar) &&
                            content != null && content.className == "android.widget.FrameLayout" &&
                            content.resourceId == "android:id/content" && emptyScaffold(content) &&
                            (current.id == bar.id || current.id == content.id) &&
                            scaffoldFrame != null && scaffoldFrame.className == "android.widget.FrameLayout" &&
                            ordinary(scaffoldFrame) && emptyScaffold(scaffoldFrame) &&
                            scaffoldRow != null && scaffoldRow.className == "android.widget.LinearLayout" &&
                            ordinary(scaffoldRow) && emptyScaffold(scaffoldRow) &&
                            scaffoldRoot != null && scaffoldRoot.parent == null &&
                            scaffoldRoot.className == "android.widget.FrameLayout" &&
                            ordinary(scaffoldRoot) && emptyScaffold(scaffoldRoot))
                        classified.add(current.id) // Only this inert ownership pair, never an action witness.
                    }
                    ancestor = current.parent
                }
            }
            // Finite observed inert decoration shapes, bound to current root/main/toolbar, not body-area guesses.
            fun inert(node: Node) = ordinary(node) && node.text.isEmpty() && !node.clickable
            fun leaf(node: Node, className: String, bounds: Bounds) = inert(node) && node.className == className &&
                node.bounds == bounds && children(node).isEmpty()
            fun decoration(node: Node): Boolean {
                if (!inert(node) || node.className != "android.widget.FrameLayout") return false
                val parts = children(node)
                val rootBounds = value.rootBounds
                val topOverlayBounds = Bounds(rootBounds.left, rootBounds.top, rootBounds.right, toolbar.bounds.bottom)
                val knownBounds = node.bounds == rootBounds || node.bounds == main.bounds
                val accepted = knownBounds && parts.isEmpty() || node.bounds == rootBounds && parts.size == 1 &&
                    (leaf(parts[0], "android.widget.ImageView", rootBounds) || leaf(parts[0], "android.view.View", rootBounds)) ||
                    node.bounds == rootBounds && parts.size == 2 && parts.any { leaf(it, "android.view.View", topOverlayBounds) } &&
                    parts.any { inert(it) && it.className == "android.widget.FrameLayout" && it.bounds == rootBounds &&
                        children(it).size == 1 && leaf(children(it).single(), "android.view.View", rootBounds) } ||
                    node.bounds == main.bounds && parts.size == 1 && inert(parts[0]) && parts[0].className == "android.widget.FrameLayout" &&
                    parts[0].bounds == main.bounds && children(parts[0]).size == 1 && children(parts[0]).single().let {
                        inert(it) && !it.visible && it.className == "android.widget.FrameLayout" && it.bounds.top == it.bounds.bottom &&
                            it.bounds.top == main.bounds.bottom && children(it).isEmpty()
                    }
                if (accepted) {
                    mark(node)
                    parts.forEach { part -> mark(part); children(part).forEach(::mark) }
                }
                return accepted
            }
            // Only after the positive HOME/ownership context: classify one exact inert root-direct leaf.
            val currentRoot = all.singleOrNull { it.parent == null }
            requireEmpty(currentRoot != null)
            val rootId = currentRoot!!.id
            val rootLeaves = visible.filter { node ->
                node.parent == rootId && node.className == "android.view.View" && node.bounds == value.rootBounds &&
                    node.packageName == TongyiEngineeringRunner.PACKAGE && node.enabled && node.text.isEmpty() &&
                    node.resourceId.isEmpty() && node.description.isEmpty() && node.hint == null &&
                    !node.clickable && !node.editable && !node.focused && !node.setTextAvailable && !node.showingHint &&
                    children(node).isEmpty()
            }
            requireEmpty(rootLeaves.size <= 1)
            rootLeaves.forEach { classified.add(it.id) } // Keep every original node/ID/parent/value/order in the Frame.
            val remaining = visible.filter { it.id !in classified }
            remaining.filter { it.parent in classified }.forEach { requireEmpty(decoration(it)) }
            requireEmpty(visible.all { it.id in classified })
        }
        fun home(value: Frame): Boolean {
            val current = nodes(value)
            check(current.none { it.editable }) { "UNKNOWN_EXISTING_DRAFT_NOT_TOUCHED" }
            val quick = current.filter { it.text == "快速" }
            val placeholder = current.filter { it.text == "发消息或按住说话..." }
            check(quick.size <= 1 && placeholder.size <= 1) { "CURRENT_SEMANTIC_WITNESS_NOT_UNIQUE" }
            val homeObserved = quick.singleOrNull()?.className == "android.widget.TextView" &&
                placeholder.singleOrNull()?.className == "android.widget.TextView"
            if (homeObserved) emptyHomeEvidence(value)
            return homeObserved
        }
        fun actionNode(value: Frame, node: Node): Node {
            check(node.visible && node.packageName == TongyiEngineeringRunner.PACKAGE && node.enabled &&
                inside(node.bounds, value.rootBounds)) { "CURRENT_ACTION_NODE_INVALID" }
            return node
        }
        fun directParent(value: Frame, text: String, resource: String?, className: String): Node {
            val current = nodes(value)
            val witness = current.singleOrNull { it.text == text && it.className == "android.widget.TextView" &&
                (resource == null || it.resourceId == resource) } ?: error("CURRENT_SEMANTIC_WITNESS_NOT_UNIQUE")
            actionNode(value, witness)
            val parent = current.singleOrNull { it.id == witness.parent && it.className == className && it.clickable }
                ?: error("EXACT_DIRECT_PARENT_REQUIRED")
            check(inside(witness.bounds, parent.bounds)) { "CURRENT_DIRECT_PARENT_BOUNDS_INVALID" }
            return actionNode(value, parent)
        }
        fun drawer(value: Frame): Boolean {
            val matches = nodes(value).filter { it.text == "新建对话" &&
                it.resourceId == "com.aliyun.tongyi:id/tv_new_session" }
            check(matches.size <= 1) { "CURRENT_SEMANTIC_WITNESS_NOT_UNIQUE" }
            if (matches.isEmpty()) return false
            directParent(value, "新建对话", "com.aliyun.tongyi:id/tv_new_session", "android.widget.LinearLayout")
            return true
        }
        fun menu(value: Frame, requireReference: Boolean = true): Node {
            check(home(value) && (if (requireReference) value.menuArgbSha256 == TongyiPreparationRunner.MENU_SHA256
                else value.menuArgbSha256?.matches(Regex("[0-9a-f]{64}")) == true)) {
                "CURRENT_MENU_PIXELS_MISMATCH"
            }
            val current = nodes(value)
            val node = current.singleOrNull { it.className == "android.widget.FrameLayout" &&
                it.bounds == Bounds(56, 193, 156, 293) && it.clickable && it.text.isEmpty() &&
                it.description.isEmpty() && it.resourceId.isEmpty() && it.hint.isNullOrEmpty() }
                ?: error("CURRENT_IMAGE_NODE_NOT_UNIQUE")
            val wrapper = current.singleOrNull { it.id == node.parent &&
                it.className == "android.widget.LinearLayout" && !it.clickable && it.bounds == node.bounds }
                ?: error("CURRENT_MENU_PARENT_MISMATCH")
            val toolbar = current.singleOrNull { it.id == wrapper.parent &&
                it.className == "android.widget.FrameLayout" && it.bounds == Bounds(0, 154, 1200, 333) }
                ?: error("CURRENT_TOOLBAR_MISMATCH")
            check(current.filter { it.parent == toolbar.id }.map { it.className } ==
                List(3) { "android.widget.LinearLayout" }) { "CURRENT_TOOLBAR_STRUCTURE_MISMATCH" }
            actionNode(value, wrapper)
            actionNode(value, toolbar)
            return actionNode(value, node)
        }
        fun editor(value: Frame): Node? {
            val current = nodes(value)
            val candidates = current.filter { it.editable }
            check(candidates.size <= 1) { "CURRENT_EDITOR_NOT_UNIQUE" }
            val input = candidates.singleOrNull() ?: return null
            check(input.className == "android.widget.EditText" && current.count { it.text == "快速" } == 1) {
                "CURRENT_ORDINARY_EDITOR_REQUIRED"
            }
            actionNode(value, input)
            return input
        }
        fun empty(input: Node) = if (!input.showingHint) input.text.isEmpty()
            else !input.hint.isNullOrEmpty() && (input.text.isEmpty() || input.text == input.hint)
        fun editorReady(value: Frame, stage: Stage): Boolean {
            val input = editor(value) ?: return false
            if (!input.focused || !input.setTextAvailable) return false
            return when (stage) {
                Stage.EDITOR_EMPTY -> {
                    check(empty(input)) { "UNKNOWN_EXISTING_DRAFT_NOT_TOUCHED" }
                    true
                }
                Stage.OWN_DRAFT -> {
                    if (empty(input)) false else {
                        check(!input.showingHint && input.text == TongyiEngineeringRunner.PAYLOAD &&
                            input.text.toByteArray(Charsets.UTF_8).size == 239) { "COMPLETE_239B_READBACK_MISMATCH" }
                        true
                    }
                }
                Stage.OWN_CLEARED -> {
                    if (empty(input)) true else {
                        check(!input.showingHint && input.text == TongyiEngineeringRunner.PAYLOAD) {
                            "UNKNOWN_REPLACEMENT_DRAFT_NOT_TOUCHED"
                        }
                        false
                    }
                }
                else -> error("EDITOR_STAGE_REQUIRED")
            }
        }
        fun state(until: Long): Frame {
            if (sourceInspection) sourceFrame = null // A failing query must not relabel the previous Frame as current.
            gate(until)
            val value = android.currentState(until)
            if (sourceInspection) sourceFrame = value
            gate(until)
            check(value.menuArgbSha256 == null) { "READINESS_MUST_NOT_ACQUIRE_CANONICAL_PIXELS" }
            return validate(value)
        }
        fun await(until: Long, predicate: (Frame) -> Boolean) {
            while (true) {
                if (predicate(state(until))) return
                gate(until)
                android.sleepMillis(50)
                gate(until)
            }
        }
        fun awaitHomeGraphics(until: Long) {
            if (sourceInspection) sourceFrame = null // Graphics has not returned a semantic Frame.
            gate(until)
            android.awaitOwnedHomeGraphics(until)
            gate(until)
            check(home(state(until))) { "CURRENT_HOME_CHANGED_AFTER_GRAPHICS" }
        }
        fun capture(stage: Stage, until: Long, predicate: (Frame) -> Boolean): Frame {
            gate(until)
            check(retained.none { it.stage == stage }) { "QUALIFICATION_STAGE_ALREADY_CONSUMED" }
            val observed = android.currentFrame(stage == Stage.HOME)
            gate(until)
            try {
                validate(observed)
                check(predicate(observed)) { "CURRENT_STAGE_CHANGED_DURING_ACQUISITION" }
            } catch (failure: Exception) {
                // Acquisition has returned a complete Frame. Retention must not replace its original failure.
                runCatching {
                    android.retainRejectedObservation(stage, observed,
                        failure.message ?: failure.javaClass.simpleName, minOf(deadline, until))
                }
                throw failure
            }
            check(android.retainStage(stage, observed)) { "STAGE_ORIGINALS_NOT_RETAINED" }
            gate(until)
            retained.add(StageObservation(stage, observed))
            return observed
        }
        fun click(role: NamedClick, observed: Frame, node: Node, until: Long) {
            gate(until)
            check(retained.lastOrNull()?.frame === observed && node.clickable) { "RETAINED_ACTION_BINDING_REQUIRED" }
            actionNode(observed, node)
            check(android.clickNamedCurrent(role, observed, node)) { "PUBLIC_ACTION_REFUSED" }
            gate(until)
        }
        fun text(observed: Frame, node: Node, value: String, until: Long) {
            gate(until)
            check(retained.lastOrNull()?.frame === observed && node.editable && node.focused && node.setTextAvailable) {
                "RETAINED_EDITOR_BINDING_REQUIRED"
            }
            actionNode(observed, node)
            check(android.setTextCurrent(observed, node, value)) { "PUBLIC_ACTION_REFUSED" }
            gate(until)
        }
        try {
            gate()
            android.launchOwned(deadline)
            gate()
            val homeUntil = limit(TongyiEngineeringRunner.READY_NANOS)
            if (sourceInspection) sourceContext(SourcePhase.INITIAL_READINESS, "source-home-1", homeUntil)
            await(homeUntil, ::home)
            if (sourceInspection) {
                repeat(2) { index ->
                    val until = if (index == 0) homeUntil else limit(TongyiEngineeringRunner.READY_NANOS)
                    val label = "source-home-${index + 1}"
                    if (index > 0) {
                        sourceContext(SourcePhase.SECOND_READINESS, label, until)
                        await(until, ::home)
                    }
                    awaitHomeGraphics(until)
                    sourceContext(SourcePhase.SOURCE_ACQUISITION, label, until)
                    gate(until)
                    val observed = android.currentFrame(menuPixels = true)
                    sourceContext(SourcePhase.SOURCE_VALIDATION, label, until, observed)
                    // Preserve the returned originals even when later source validation rejects them.
                    check(android.retainSource(label, observed)) { "SOURCE_ORIGINALS_NOT_RETAINED" }
                    sources.add(SourceObservation(label, observed))
                    gate(until)
                    validate(observed)
                    menu(observed, requireReference = false)
                    if (index > 0) {
                        sourceContext(SourcePhase.PAIR_VALIDATION, label, until, observed)
                        check(observed == sources.first().frame) { "SOURCE_HOME_PAIR_UNSTABLE" }
                        check(observed !== sources.first().frame) { "SOURCE_HOME_REUSED_FRAME" }
                    }
                }
                gate()
                completed = true
            } else {
            awaitHomeGraphics(homeUntil)
            val initial = capture(Stage.HOME, homeUntil) { menu(it); true }
            click(NamedClick.MENU, initial, menu(initial), limit(TongyiEngineeringRunner.INPUT_NANOS))
            val drawerUntil = limit(TongyiEngineeringRunner.READY_NANOS)
            await(drawerUntil, ::drawer)
            val opened = capture(Stage.DRAWER, drawerUntil, ::drawer)
            click(NamedClick.NEW_SESSION, opened,
                directParent(opened, "新建对话", "com.aliyun.tongyi:id/tv_new_session", "android.widget.LinearLayout"),
                limit(TongyiEngineeringRunner.INPUT_NANOS))
            val emptyUntil = limit(TongyiEngineeringRunner.READY_NANOS)
            await(emptyUntil, ::home)
            val newEmpty = capture(Stage.NEW_EMPTY, emptyUntil, ::home)
            val focusUntil = limit(TongyiEngineeringRunner.INPUT_NANOS)
            click(NamedClick.COMPOSER_FOCUS, newEmpty,
                directParent(newEmpty, "发消息或按住说话...", null, "android.view.ViewGroup"), focusUntil)
            await(focusUntil) { editorReady(it, Stage.EDITOR_EMPTY) }
            val focused = capture(Stage.EDITOR_EMPTY, focusUntil) { editorReady(it, Stage.EDITOR_EMPTY) }
            val inputUntil = limit(TongyiEngineeringRunner.INPUT_NANOS)
            text(focused, editor(focused)!!, TongyiEngineeringRunner.PAYLOAD, inputUntil)
            await(inputUntil) { editorReady(it, Stage.OWN_DRAFT) }
            val draft = capture(Stage.OWN_DRAFT, inputUntil) { editorReady(it, Stage.OWN_DRAFT) }
            val draftEditor = editor(draft)!!
            val clearUntil = limit(TongyiEngineeringRunner.INPUT_NANOS)
            check(editor(state(clearUntil)) == draftEditor) { "UNKNOWN_REPLACEMENT_DRAFT_NOT_TOUCHED" }
            text(draft, draftEditor, "", clearUntil)
            await(clearUntil) { editorReady(it, Stage.OWN_CLEARED) }
            capture(Stage.OWN_CLEARED, clearUntil) { editorReady(it, Stage.OWN_CLEARED) }
            gate()
            completed = true
            }
        } catch (failure: Exception) {
            reason = failure.message ?: failure.javaClass.simpleName
            if (sourceInspection) {
                val returned = sourceFrame
                var saved = false
                var retentionFailure: String? = null
                if (returned != null) try {
                    saved = android.retainRejectedSourceObservation(sourcePhase, sourceLabel, returned,
                        checkNotNull(reason), minOf(deadline, sourceUntil))
                    if (!saved) retentionFailure = "REJECTION_DIAGNOSTIC_NOT_RETAINED"
                } catch (retention: Exception) {
                    retentionFailure = retention.javaClass.simpleName + ":" + (retention.message ?: "UNKNOWN")
                }
                // Diagnostic failure never replaces the primary rejection or changes accepted-source/cleanup facts.
                sourceRejection = SourceRejection(sourcePhase, sourceLabel, returned, returned != null, saved, retentionFailure)
            }
        } finally {
            // Owned closure is still attempted on expiry/cancel; it cannot rescue an incomplete path.
            cleaned = try { android.cleanupOwned() } catch (_: Exception) { false }
            if (completed) try { gate() } catch (failure: Exception) {
                completed = false
                reason = failure.message ?: failure.javaClass.simpleName
            }
        }
        val mode = if (sourceInspection) "UI_SOURCE_INSPECTION" else "UI_REFERENCE_QUALIFICATION"
        return Result(if (completed && cleaned) "${mode}_CLOSED" else "${mode}_UNKNOWN",
            "0", cleaned, reason ?: if (!cleaned) "OWNED_CLEANUP_UNKNOWN" else null, retained.toList(),
            retainedSources = sources.toList(), sourceRejection = sourceRejection)
    }
}
