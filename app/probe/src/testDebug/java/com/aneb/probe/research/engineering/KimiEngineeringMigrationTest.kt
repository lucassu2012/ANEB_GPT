package com.aneb.probe.research.engineering

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KimiEngineeringMigrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun clippedKimiSendCenterNeverInvokesOrChangesOwnedDraft() {
        val viewport = TongyiPreparationRunner.Bounds(0, 0, 1200, 2640)
        val composer = TongyiPreparationRunner.Bounds(40, 1000, 1160, 1700)
        val input = TongyiPreparationRunner.Bounds(90, 1100, 990, 1450)
        val send = TongyiPreparationRunner.Bounds(1000, 1530, 1130, 1660)
        fun node(id: String, parent: String?, bounds: TongyiPreparationRunner.Bounds = viewport,
            text: String = "", description: String = "", clazz: String = "android.view.View") =
            DeepSeekUiAdapter.Node(id, parent, bounds, text, description, windowId = 7,
                packageName = "com.moonshot.kimichat", className = clazz)
        // External Android frame: partial visibility need not make the raw center touchable.
        val frame = DeepSeekUiAdapter.Frame("EXTERNAL_CURRENT_OWNER", 7, 1200, 2640, 0, "root", listOf(
            node("root", null), node("composer", "root", composer), node("input-parent", "composer", input),
            node("editor", "input-parent", input, TongyiEngineeringRunner.PAYLOAD, clazz = "android.widget.EditText").copy(
                editable = true, focused = true, setTextAvailable = true),
            node("modes", "root"), node("quick", "modes", text = "快速", clazz = "android.widget.TextView"),
            node("advanced", "modes", text = "进阶", clazz = "android.widget.TextView"),
            node("clipping-ancestor", "composer", TongyiPreparationRunner.Bounds(1000, 1530, 1040, 1660)),
            node("send-parent", "clipping-ancestor", send),
            node("send", "send-parent", send, description = "发送讯息"),
        ))
        var invocations = 0
        var draft = TongyiEngineeringRunner.PAYLOAD
        val android = object : KimiUiAdapter.Android {
            override fun read(until: Long) = frame
            override fun focusCurrentEmptyEditor(frame: DeepSeekUiAdapter.Frame, editor: DeepSeekUiAdapter.Node, until: Long) {
                error("UNEXPECTED_FOCUS")
            }
            override fun clickCurrentSemanticSend(frame: DeepSeekUiAdapter.Frame, send: DeepSeekUiAdapter.Node, until: Long) {
                invocations++
                draft = ""
            }
            override fun nowNanos() = 1_000_000_000L
            override fun sleepMillis(milliseconds: Long) { error("UNEXPECTED_RETRY") }
        }
        val failure = runCatching { KimiUiAdapter(android, 100_000_000_000L).sendOnce(frame, "发送讯息") }.exceptionOrNull()
        assertEquals("KIMI_CURRENT_SEND_CENTER_CLIPPED", failure?.message)
        assertEquals(0, invocations)
        assertEquals(TongyiEngineeringRunner.PAYLOAD, draft)
    }

    @Test fun refusedExternalKimiTouchKeepsConsumedUnknownAndOwnCleanup() {
        var now = 1_000_000_000L
        var owned = false
        var focused = false
        var draft = ""
        var recording = false
        var attempts = 0
        var cleaned = false
        var baseline: DeepSeekUiAdapter.Frame? = null
        fun current(): DeepSeekUiAdapter.Frame {
            val viewport = TongyiPreparationRunner.Bounds(0, 0, 1200, 2640)
            val composer = TongyiPreparationRunner.Bounds(40, 1000, 1160, 1700)
            val input = TongyiPreparationRunner.Bounds(90, 1100, 990, 1450)
            val send = TongyiPreparationRunner.Bounds(1000, 1530, 1130, 1660)
            fun node(id: String, parent: String?, bounds: TongyiPreparationRunner.Bounds = viewport,
                text: String = "", description: String = "", clazz: String = "android.view.View") =
                DeepSeekUiAdapter.Node(id, parent, bounds, text, description, windowId = 7,
                    packageName = "com.moonshot.kimichat", className = clazz)
            return DeepSeekUiAdapter.Frame("EXTERNAL_KIMI_OWNER", 7, 1200, 2640, 0, "root", listOf(
                node("root", null), node("greeting", "root", text = "测试账户，今天要和 Kimi 一起做点什么", clazz = "android.widget.TextView"),
                node("composer", "root", composer), node("input-parent", "composer", input),
                node("input", "input-parent", input, draft, clazz = "android.widget.EditText").copy(
                    editable = true, focused = focused, focusAvailable = true, setTextAvailable = true),
                node("hint", "input", input, if (draft.isEmpty()) "尽管问，带图也行" else "", clazz = "android.widget.TextView"),
                node("modes", "root"), node("quick", "modes", text = "快速", clazz = "android.widget.TextView"),
                node("advanced", "modes", text = "进阶", clazz = "android.widget.TextView"),
                node("send-parent", "composer", send), node("send", "send-parent", send, description = "发送讯息"),
            ))
        }
        // Explicit external Android refusal, not a mock of Native or evidence of real injection.
        val android = object : KimiUiAdapter.Android {
            override fun read(until: Long): DeepSeekUiAdapter.Frame { check(now < until); return current() }
            override fun focusCurrentEmptyEditor(frame: DeepSeekUiAdapter.Frame, editor: DeepSeekUiAdapter.Node, until: Long) {
                check(now < until && draft.isEmpty() && !focused); focused = true
            }
            override fun clickCurrentSemanticSend(frame: DeepSeekUiAdapter.Frame, send: DeepSeekUiAdapter.Node, until: Long) {
                check(now < until && frame == current() && recording && draft == TongyiEngineeringRunner.PAYLOAD)
                attempts++
                val externalTouchReturned = false
                check(externalTouchReturned) { "KIMI_CURRENT_SEMANTIC_TOUCH_REFUSED" }
            }
            override fun nowNanos() = now
            override fun sleepMillis(milliseconds: Long) { now += milliseconds * 1_000_000L }
        }
        val adapter = KimiUiAdapter(android, now + TongyiEngineeringRunner.PREP_NANOS)
        val phone = object : TongyiEngineeringRunner.Phone {
            override fun prepare(deadlineNanos: Long): TongyiEngineeringRunner.Snapshot {
                owned = true; adapter.prepareEmpty(); return snapshot()
            }
            override fun enterExact(text: String) { check(owned && focused && draft.isEmpty()); draft = text }
            override fun snapshot(): TongyiEngineeringRunner.Snapshot {
                val frame = current(); baseline = frame
                return TongyiEngineeringRunner.Snapshot("com.moonshot.kimichat", owned,
                    adapter.ordinaryTextReady(frame), true, draft.isEmpty(), draft, focused,
                    adapter.sendCandidate(frame, "发送讯息") != null, "W1", true)
            }
            override fun saveOriginals(label: String) = Unit
            override fun startRecording(readyDeadlineNanos: Long) { check(now < readyDeadlineNanos); recording = true }
            override fun sendOnce() = adapter.sendOnce(checkNotNull(baseline), "发送讯息")
            override fun observeUntil(deadlineNanos: Long) { error("REFUSED_TOUCH_CANNOT_OBSERVE") }
            override fun stopOwnedRecording() { recording = false }
            override fun cleanupOwned(): Boolean {
                if (owned) recording = false // External cleanup owns capture shutdown, as Native cleanup does.
                if (owned && draft == TongyiEngineeringRunner.PAYLOAD) draft = ""
                cleaned = owned && !recording && draft.isEmpty(); owned = false; return cleaned
            }
        }
        val output = File(temporary.root, "refused-touch")
        val result = TongyiEngineeringRunner(phone, { now }, EngineeringTarget.KIMI)
            .runOnce("KIMI_REFUSED_EXTERNAL_TOUCH", "W1", output)
        assertEquals("KIMI_CURRENT_SEMANTIC_TOUCH_REFUSED", result.reason)
        assertEquals("OBSERVATION_UNKNOWN", result.state)
        assertEquals("UNKNOWN", result.sendCalls)
        assertTrue(File(output, "SEND_INTENT.json").readText().contains("consumed_before_invocation"))
        assertEquals(1, attempts)
        assertTrue(result.ownedCleanup && cleaned)
        assertFalse(recording || owned)
        assertEquals("", draft)
    }

    @Test fun sameCoreCollectsKimiViaOnePublicSemanticSendAndOwnCleanup() {
        var now = 1_000_000_000L
        var owned = false
        var focused = false
        var draft = ""
        var recording = false
        var semanticSends = 0
        var cleaned = false
        var baseline: DeepSeekUiAdapter.Frame? = null
        // Only descriptor/class and small send-wrapper/editor-composer topology
        // are grounded in R5. All owner, focus, clocks, action responses and
        // successor frames here are external OS hypotheses, not Native proof.
        fun current(): DeepSeekUiAdapter.Frame {
            val viewport = TongyiPreparationRunner.Bounds(0, 0, 1200, 2640)
            val composer = TongyiPreparationRunner.Bounds(40, 1000, 1160, 1700)
            val input = TongyiPreparationRunner.Bounds(90, 1100, 990, 1450)
            val send = TongyiPreparationRunner.Bounds(1000, 1530, 1130, 1660)
            fun node(id: String, parent: String?, box: TongyiPreparationRunner.Bounds = viewport,
                text: String = "", description: String = "", clazz: String = "android.view.View") =
                DeepSeekUiAdapter.Node(id, parent, box, text, description, windowId = 7,
                    packageName = "com.moonshot.kimichat", className = clazz)
            return DeepSeekUiAdapter.Frame("EXTERNAL_KIMI_OWNER", 7, 1200, 2640, 0, "root", listOf(
                node("root", null), node("greeting", "root", text = "测试账户，今天要和 Kimi 一起做点什么", clazz = "android.widget.TextView"),
                node("composer", "root", composer), node("input-parent", "composer", input),
                node("input", "input-parent", input, text = draft, clazz = "android.widget.EditText").copy(
                    editable = true, focused = focused, focusAvailable = true, setTextAvailable = true),
                node("hint", "input", input, text = if (draft.isEmpty()) "尽管问，带图也行" else "", clazz = "android.widget.TextView"),
                node("modes", "root"), node("quick", "modes", text = "快速", clazz = "android.widget.TextView"),
                node("advanced", "modes", text = "进阶", clazz = "android.widget.TextView"),
                node("small-send-parent", "composer", send),
                node("semantic-send", "small-send-parent", send, description = "发送讯息"),
            ))
        }
        val android = object : KimiUiAdapter.Android {
            override fun read(until: Long): DeepSeekUiAdapter.Frame { check(now < until); return current() }
            override fun focusCurrentEmptyEditor(frame: DeepSeekUiAdapter.Frame, editor: DeepSeekUiAdapter.Node, until: Long) {
                check(now < until && frame == current() && !focused && draft.isEmpty())
                focused = true
            }
            override fun clickCurrentSemanticSend(frame: DeepSeekUiAdapter.Frame, send: DeepSeekUiAdapter.Node, until: Long) {
                check(now < until && frame == current() && recording && draft == TongyiEngineeringRunner.PAYLOAD)
                check(send.description == "发送讯息" && !send.clickable && !send.clickAvailable)
                semanticSends++ // This is the explicit external public semantic-object operation, NOT A11y CLICK.
                draft = ""
            }
            override fun nowNanos() = now
            override fun sleepMillis(milliseconds: Long) { now += milliseconds * 1_000_000L }
        }
        val adapter = KimiUiAdapter(android, now + TongyiEngineeringRunner.PREP_NANOS)
        val phone = object : TongyiEngineeringRunner.Phone {
            override fun prepare(deadlineNanos: Long): TongyiEngineeringRunner.Snapshot {
                owned = true; adapter.prepareEmpty(); return snapshot()
            }
            override fun enterExact(text: String) { check(owned && focused && draft.isEmpty()); draft = text }
            override fun snapshot(): TongyiEngineeringRunner.Snapshot {
                val frame = current()
                baseline = frame
                return TongyiEngineeringRunner.Snapshot("com.moonshot.kimichat", owned,
                    adapter.ordinaryTextReady(frame), true, draft.isEmpty(), draft, focused,
                    adapter.sendCandidate(frame, "发送讯息") != null, "W1", true)
            }
            override fun saveOriginals(label: String) = Unit
            override fun startRecording(readyDeadlineNanos: Long) { check(now < readyDeadlineNanos); recording = true }
            override fun sendOnce() { adapter.sendOnce(checkNotNull(baseline), "发送讯息") }
            override fun observeUntil(deadlineNanos: Long) { check(recording && semanticSends == 1); now = deadlineNanos }
            override fun stopOwnedRecording() { recording = false }
            override fun cleanupOwned(): Boolean { cleaned = owned && !recording && draft.isEmpty(); owned = false; return cleaned }
        }
        val result = TongyiEngineeringRunner(phone, { now }, EngineeringTarget.KIMI)
            .runOnce("KIMI_PUBLIC_SEMANTIC_WHOLE", "W1", File(temporary.root, "semantic-whole"))
        assertEquals(result.reason, "OBSERVATION_CLOSED", result.state)
        assertEquals("1", result.sendCalls)
        assertEquals(1, semanticSends)
        assertTrue(result.ownedCleanup && cleaned)
        assertFalse(recording || owned)
        assertEquals("", draft)
    }

    @Test fun ambiguousUnknownOrChangedKimiSendNeverInvokesOrReplacesDraft() {
        val root = TongyiPreparationRunner.Bounds(0, 0, 1200, 2640)
        val composer = TongyiPreparationRunner.Bounds(40, 1000, 1160, 1700)
        val input = TongyiPreparationRunner.Bounds(90, 1100, 990, 1450)
        val send = TongyiPreparationRunner.Bounds(1000, 1530, 1130, 1660)
        fun node(id: String, parent: String?, bounds: TongyiPreparationRunner.Bounds = root,
            text: String = "", description: String = "", clazz: String = "android.view.View") =
            DeepSeekUiAdapter.Node(id, parent, bounds, text, description, windowId = 7,
                packageName = "com.moonshot.kimichat", className = clazz)
        // External OS source/action boundary hypotheses, not real UI/provider identity proof.
        val valid = DeepSeekUiAdapter.Frame("EXTERNAL_CURRENT_OWNER", 7, 1200, 2640, 0, "root", listOf(
            node("root", null), node("composer", "root", composer), node("input-parent", "composer", input),
            node("editor", "input-parent", input, TongyiEngineeringRunner.PAYLOAD, clazz = "android.widget.EditText").copy(
                editable = true, focused = true, setTextAvailable = true),
            node("modes", "root"), node("quick", "modes", text = "快速", clazz = "android.widget.TextView"),
            node("advanced", "modes", text = "进阶", clazz = "android.widget.TextView"),
            node("send-parent", "composer", send), node("send", "send-parent", send, description = "发送讯息"),
        ))
        for (kind in listOf("ambiguous", "unknown-draft", "changed-at-operation")) {
            val observed = when (kind) {
                "ambiguous" -> valid.copy(nodes = valid.nodes + valid.nodes.last().copy(id = "duplicate-send"))
                "unknown-draft" -> valid.copy(nodes = valid.nodes.map { if (it.editable) it.copy(text = "用户未发送的私人草稿") else it })
                else -> valid
            }
            val live = if (kind == "changed-at-operation") observed.copy(owner = "EXTERNAL_REPLACEMENT_OWNER") else observed
            var invocations = 0
            var draft = observed.nodes.single { it.editable }.text
            val originalDraft = draft
            val android = object : KimiUiAdapter.Android {
                override fun read(until: Long) = live
                override fun focusCurrentEmptyEditor(frame: DeepSeekUiAdapter.Frame, editor: DeepSeekUiAdapter.Node, until: Long) {
                    error("UNEXPECTED_FOCUS")
                }
                override fun clickCurrentSemanticSend(frame: DeepSeekUiAdapter.Frame, send: DeepSeekUiAdapter.Node, until: Long) {
                    check(frame == live) { "EXTERNAL_CURRENT_TARGET_CHANGED" }
                    invocations++
                    draft = ""
                }
                override fun nowNanos() = 1_000_000_000L
                override fun sleepMillis(milliseconds: Long) { error("UNEXPECTED_RETRY") }
            }
            val failure = runCatching { KimiUiAdapter(android, 100_000_000_000L).sendOnce(observed, "发送讯息") }.exceptionOrNull()
            val expected = when (kind) {
                "ambiguous" -> "KIMI_SEND_SEMANTIC_NOT_UNIQUE"
                "unknown-draft" -> "UNKNOWN_DRAFT_NOT_TOUCHED"
                else -> "EXTERNAL_CURRENT_TARGET_CHANGED"
            }
            assertEquals(kind, expected, failure?.message)
            assertEquals(kind, 0, invocations)
            assertEquals(kind, originalDraft, draft)
        }
    }

    @Test fun sameCoreClosesOneOwnedKimiAttempt() {
        val target = EngineeringTarget.fromId("kimi")
        var now = 1_000_000_000L
        var owned = false
        var draft = ""
        var entered = ""
        var recording = false
        var invocationCount = 0
        var cleanupFinished = false
        // Only this package identity is grounded in PMO812. All capability,
        // mode/focus, recording and SEND responses below are hypothetical
        // external Android boundary responses, NOT observed Kimi UI/provider
        // facts, Native wiring, a device qualification or backend delivery.
        val phone = object : TongyiEngineeringRunner.Phone {
            override fun prepare(deadlineNanos: Long): TongyiEngineeringRunner.Snapshot {
                check(now < deadlineNanos)
                owned = true
                return snapshot()
            }

            override fun enterExact(text: String) {
                check(owned && draft.isEmpty())
                entered = text
                draft = text
                now += 100_000_000L
            }

            override fun snapshot() = TongyiEngineeringRunner.Snapshot(
                packageName = "com.moonshot.kimichat", ownedLifecycle = owned,
                ordinaryTextVisible = true, noAttachments = true,
                emptyNewSession = owned && draft.isEmpty(), composerText = draft,
                composerFocused = owned, uniqueSend = owned && draft == TongyiEngineeringRunner.PAYLOAD,
                networkCondition = "W1", notVpn = true,
            )

            override fun saveOriginals(label: String) = Unit

            override fun startRecording(readyDeadlineNanos: Long) {
                check(now < readyDeadlineNanos && owned)
                recording = true
            }

            override fun sendOnce() {
                check(owned && recording && draft == TongyiEngineeringRunner.PAYLOAD)
                invocationCount++
                now += 10_000_000L
            }

            override fun observeUntil(deadlineNanos: Long) {
                check(recording && invocationCount == 1)
                now = maxOf(now, deadlineNanos)
            }

            override fun stopOwnedRecording() { recording = false }

            override fun cleanupOwned(): Boolean {
                cleanupFinished = !recording
                draft = ""
                owned = false
                return cleanupFinished
            }
        }
        val result = TongyiEngineeringRunner(phone, { now }, target)
            .runOnce("KIMI_CORE_TRACER", "W1", File(temporary.root, "one-attempt"))

        assertEquals("OBSERVATION_CLOSED", result.state)
        assertEquals(TongyiEngineeringRunner.PAYLOAD, entered)
        assertEquals(239, entered.toByteArray(Charsets.UTF_8).size)
        assertEquals("1", result.sendCalls)
        assertEquals(1, invocationCount)
        assertTrue(result.ownedCleanup && cleanupFinished)
        assertFalse(owned || recording)
        assertEquals("", draft)
        assertNull(result.reason)
    }

    @Test fun sameNoSendQualifierClosesOwnedKimiDraftAndCleanup() {
        val target = EngineeringTarget.fromId("kimi")
        var now = 1_000_000_000L
        var owned = false
        var draft = ""
        var entered = ""
        var cleaned = false
        val retained = mutableListOf<String>()
        // Hypothetical external Android capability responses only. PMO812/814
        // ground package/visible-source identity, NOT focus, SET_TEXT, readback,
        // mode semantics, a SEND role or a real Kimi device qualification.
        val android = object : DeepSeekDraftRoleQualification.Android {
            override fun prepareEmpty(deadlineNanos: Long) {
                check(now < deadlineNanos && draft.isEmpty())
                owned = true
            }

            override fun enterExactAndReadback(text: String): DeepSeekDraftRoleQualification.Readback {
                check(owned && draft.isEmpty())
                entered = text
                draft = text
                now += 100_000_000L
                return DeepSeekDraftRoleQualification.Readback(
                    packageName = "com.moonshot.kimichat",
                    owner = "EXTERNAL_KIMI_QUALIFICATION_OWNER", windowId = 1, parentWindowId = 1,
                    sameEditorAndParent = true, text = draft, showingHint = false, focused = true,
                )
            }

            override fun retainCurrent(label: String) {
                check(owned)
                check(if (label == "new-empty") draft.isEmpty()
                    else label == "owned-draft" && draft == TongyiEngineeringRunner.PAYLOAD)
                retained += label
            }

            override fun retainRejectedCached() = false

            override fun cleanupOwned(): Boolean {
                draft = ""
                owned = false
                cleaned = true
                return true
            }
        }
        val result = DeepSeekDraftRoleQualification(android, { now }, target)
            .runOnce("KIMI_NO_SEND_CORE_TRACER", File(temporary.root, "one-qualification"))

        assertEquals(result.failure, "DRAFT_ROLE_QUALIFICATION_CLOSED", result.state)
        assertTrue(result.inputReadbackConfirmed)
        assertEquals(listOf("new-empty", "owned-draft"), result.retainedStages)
        assertEquals(result.retainedStages, retained)
        assertEquals(TongyiEngineeringRunner.PAYLOAD, entered)
        assertEquals(239, entered.toByteArray(Charsets.UTF_8).size)
        assertTrue(result.ownedCleanup && cleaned)
        assertFalse(owned)
        assertEquals("", draft)
        assertNull(result.failure)
    }

    @Test fun currentKimiHomePreparesOneOwnedEmptyEditorWithoutModeActions() {
        var now = 1_000_000_000L
        var focused = false
        // PMO814 home XML grounds only these visible literals and hint->editor/
        // editor->parent relationships in a simplified copied graph. Geometry,
        // editable/action flags, focus and successor frames are hypothetical OS
        // responses, not captured Kimi capabilities or raw Android identities.
        fun current(): DeepSeekUiAdapter.Frame {
            val viewport = TongyiPreparationRunner.Bounds(0, 0, 1200, 2640)
            val editorBox = if (focused) TongyiPreparationRunner.Bounds(50, 800, 1150, 1400)
                else TongyiPreparationRunner.Bounds(50, 1800, 1150, 2400)
            fun node(id: String, parent: String?, text: String = "", className: String = "android.view.View") =
                DeepSeekUiAdapter.Node(id, parent, viewport, text = text, windowId = 7,
                    packageName = "com.moonshot.kimichat", className = className)
            return DeepSeekUiAdapter.Frame("EXTERNAL_KIMI_UI_OWNER", 7, 1200, 2640, 0, "root", listOf(
                node("root", null, className = "android.widget.FrameLayout"),
                node("greeting-parent", "root"),
                node("greeting", "greeting-parent", "测试账户，今天要和 Kimi 一起做点什么",
                    "android.widget.TextView"),
                node("editor-parent", "root").copy(bounds = editorBox),
                node("editor", "editor-parent", className = "android.widget.EditText").copy(
                    bounds = editorBox, editable = true, focused = focused, focusAvailable = true),
                node("hint", "editor", "尽管问，带图也行", "android.widget.TextView").copy(bounds = editorBox),
                node("mode-parent", "root").copy(clickable = true),
                node("quick", "mode-parent", "快速", "android.widget.TextView"),
                node("advanced", "mode-parent", "进阶", "android.widget.TextView"),
            ))
        }
        val android = object : KimiUiAdapter.Android {
            override fun read(until: Long): DeepSeekUiAdapter.Frame {
                check(now < until)
                return current()
            }

            override fun focusCurrentEmptyEditor(frame: DeepSeekUiAdapter.Frame,
                editor: DeepSeekUiAdapter.Node, until: Long) {
                check(now < until && frame == current() && !focused)
                check(editor.editable && editor.text.isEmpty() && editor.focusAvailable)
                focused = true
                now += 100_000_000L
            }

            override fun nowNanos() = now
            override fun sleepMillis(milliseconds: Long) { now += milliseconds * 1_000_000L }
        }
        val prepared = KimiUiAdapter(android, now + TongyiEngineeringRunner.PREP_NANOS).prepareEmpty()
        val editor = prepared.nodes.single { it.editable }

        assertTrue(focused)
        assertTrue(editor.enabled && editor.focused && editor.text.isEmpty())
        assertEquals("EXTERNAL_KIMI_UI_OWNER", prepared.owner)
        assertEquals(7, prepared.windowId)
    }

    @Test fun unknownKimiDraftRemainsUntouchedBeforeFocus() {
        var now = 1_000_000_000L
        val originalDraft = "尚未发送的个人草稿，请保持原样"
        val viewport = TongyiPreparationRunner.Bounds(0, 0, 1200, 2640)
        val editorBox = TongyiPreparationRunner.Bounds(50, 1800, 1150, 2400)
        // External Android/clock hypothesis only: otherwise eligible home shape
        // and focus capability, but an unknown user's nonempty draft. This is
        // not an observed live Kimi state or a SEND-role/capability qualification.
        fun node(id: String, parent: String?, text: String = "", className: String = "android.view.View") =
            DeepSeekUiAdapter.Node(id, parent, viewport, text = text, windowId = 7,
                packageName = "com.moonshot.kimichat", className = className)
        val original = DeepSeekUiAdapter.Frame("EXTERNAL_KIMI_UI_OWNER", 7, 1200, 2640, 0, "root", listOf(
            node("root", null, className = "android.widget.FrameLayout"),
            node("greeting-parent", "root"),
            node("greeting", "greeting-parent", "测试账户，今天要和 Kimi 一起做点什么",
                "android.widget.TextView"),
            node("editor-parent", "root").copy(bounds = editorBox),
            node("editor", "editor-parent", originalDraft, "android.widget.EditText").copy(
                bounds = editorBox, editable = true, focused = false, focusAvailable = true),
            node("hint", "editor", "尽管问，带图也行", "android.widget.TextView").copy(bounds = editorBox),
            node("mode-parent", "root").copy(clickable = true),
            node("quick", "mode-parent", "快速", "android.widget.TextView"),
            node("advanced", "mode-parent", "进阶", "android.widget.TextView"),
        ))
        var current = original
        var focusRequests = 0
        val android = object : KimiUiAdapter.Android {
            override fun read(until: Long): DeepSeekUiAdapter.Frame {
                check(now < until)
                return current
            }

            override fun focusCurrentEmptyEditor(frame: DeepSeekUiAdapter.Frame,
                editor: DeepSeekUiAdapter.Node, until: Long) {
                check(now < until)
                focusRequests++
                current = current.copy(nodes = current.nodes.map {
                    if (it.id == editor.id) it.copy(focused = true) else it
                })
            }

            override fun nowNanos() = now
            override fun sleepMillis(milliseconds: Long) { now += milliseconds * 1_000_000L }
        }
        val failure = runCatching {
            KimiUiAdapter(android, now + TongyiEngineeringRunner.PREP_NANOS).prepareEmpty()
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("UNKNOWN_DRAFT_NOT_TOUCHED", failure?.message)
        assertEquals(0, focusRequests)
        assertEquals(original, current)
        assertEquals(originalDraft, current.nodes.single { it.editable }.text)
        assertFalse(current.nodes.single { it.editable }.focused)
    }


    @Test fun prepareEmptyAcceptsFocusedCurrentGenerationAfterDfsReindex() {
        var now = 1_000_000_000L
        var focused = false
        // External OS fixture hypothesis, not actual raw Android identities or
        // a device qualification. The valid current graph may be reindexed after
        // focus; Native must independently retain the actual editor/parent proof.
        fun current(): DeepSeekUiAdapter.Frame {
            val viewport = TongyiPreparationRunner.Bounds(0, 0, 1200, 2640)
            val editorId = if (focused) "21" else "41"
            val parentId = if (focused) "20" else "40"
            val editorBox = if (focused) TongyiPreparationRunner.Bounds(94, 1208, 1106, 1366)
                else TongyiPreparationRunner.Bounds(266, 2335, 1093, 2494)
            fun node(id: String, parent: String?, text: String = "", className: String = "android.view.View") =
                DeepSeekUiAdapter.Node(id, parent, viewport, text = text, windowId = 43641,
                    packageName = "com.moonshot.kimichat", className = className)
            return DeepSeekUiAdapter.Frame("HYPOTHETICAL_KIMI_OWNER", 43641, 1200, 2640, 0, "0", listOf(
                node("0", null, className = "android.widget.FrameLayout"),
                node("1", "0"),
                node("2", "1", "测试账户，今天要和 Kimi 一起做点什么", "android.widget.TextView"),
                node(parentId, "0").copy(bounds = editorBox),
                node(editorId, parentId, className = "android.widget.EditText").copy(
                    bounds = editorBox, editable = true, focused = focused, focusAvailable = true),
                node(if (focused) "22" else "42", editorId, "尽管问，带图也行",
                    "android.widget.TextView").copy(bounds = editorBox),
                node("6", "0").copy(clickable = true),
                node("7", "6", "快速", "android.widget.TextView"),
                node("8", "6", "进阶", "android.widget.TextView"),
            ))
        }
        val android = object : KimiUiAdapter.Android {
            override fun read(until: Long): DeepSeekUiAdapter.Frame {
                check(now < until)
                return current()
            }

            override fun focusCurrentEmptyEditor(frame: DeepSeekUiAdapter.Frame,
                editor: DeepSeekUiAdapter.Node, until: Long) {
                check(now < until && frame == current() && !focused)
                check(editor.id == "41" && editor.parent == "40" && editor.focusAvailable && editor.text.isEmpty())
                focused = true
                now += 100_000_000L
            }

            override fun nowNanos() = now
            override fun sleepMillis(milliseconds: Long) { now += milliseconds * 1_000_000L }
        }
        val prepared = KimiUiAdapter(android, now + TongyiEngineeringRunner.PREP_NANOS).prepareEmpty()
        val editor = prepared.nodes.single { it.editable }

        assertEquals(current(), prepared)
        assertTrue(focused && editor.focused && editor.text.isEmpty())
        assertEquals("21", editor.id)
        assertEquals("20", editor.parent)
    }

}
