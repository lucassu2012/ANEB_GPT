package com.aneb.probe.research.engineering

import java.io.File
import java.nio.file.Files
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
        override fun nowNanos() = now
        override fun sleepMillis(milliseconds: Long) { now += milliseconds * 1_000_000 }
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
