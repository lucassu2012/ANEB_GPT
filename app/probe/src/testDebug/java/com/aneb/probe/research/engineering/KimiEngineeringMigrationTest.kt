package com.aneb.probe.research.engineering

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KimiEngineeringMigrationTest {
    @get:Rule val temporary = TemporaryFolder()

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

}
