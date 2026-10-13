package com.aneb.probe.research.engineering

import com.aneb.probe.research.engineering.TongyiPreparationRunner.Bounds
import com.aneb.probe.research.engineering.TongyiPreparationRunner.Frame
import com.aneb.probe.research.engineering.TongyiPreparationRunner.Node
import com.aneb.probe.research.engineering.TongyiUiReferenceQualificationRunner.NamedClick
import com.aneb.probe.research.engineering.TongyiUiReferenceQualificationRunner.Stage
import com.aneb.probe.research.engineering.TongyiUiReferenceQualificationRunner.StageObservation
import com.aneb.probe.research.engineering.TongyiUiReferenceQualificationRunner.SourcePhase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.IdentityHashMap

class TongyiUiReferenceQualificationRunnerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun inspectsTwoIndependentHomeSourcesWithoutActionsOrAcceptingAnyUiqStage() {
        val home = homeFrame("home").copy(menuArgbSha256 = "f".repeat(64))
        val directory = File(temporary.root, "source-one")
        val android = SourceAndroid(listOf(home, home.copy()), directory)

        val result = TongyiUiReferenceQualificationRunner(android, System::nanoTime)
            .runSourceInspectionOnce("source-one", directory)

        assertEquals("UI_SOURCE_INSPECTION_CLOSED", result.state)
        assertNull(result.reason)
        assertEquals("0", result.sendCalls)
        assertTrue(result.ownedCleanup && android.cleaned)
        assertFalse(result.referenceAccepted)
        assertNull(result.sourceRejection)
        assertTrue(result.retainedStages.isEmpty())
        assertEquals(listOf("source-home-1", "source-home-2"), result.retainedSources.map { it.sourceLabel })
        assertEquals(2, android.acquired.size)
        assertNotSame(android.acquired[0], android.acquired[1])
        assertEquals(android.acquired, result.retainedSources.map { it.frame })
        result.retainedSources.zip(android.acquired).forEach { (retained, acquired) -> assertSame(acquired, retained.frame) }
        assertEquals(listOf("f".repeat(64), "f".repeat(64)), result.retainedSources.map { it.frame.menuArgbSha256 })
        assertEquals(0, android.businessActions)
        assertEquals(8, android.originals.size)
        android.originals.forEach { (name, raw) -> assertArrayEquals(raw, File(directory, name).readBytes()) }
        assertFalse(File(directory, "SEND_INTENT.json").exists())
        assertEquals(listOf(Stage.HOME, Stage.DRAWER, Stage.NEW_EMPTY, Stage.EDITOR_EMPTY, Stage.OWN_DRAFT, Stage.OWN_CLEARED), Stage.values().toList())
        try {
            TongyiUiReferenceQualificationRunner(android, System::nanoTime).runSourceInspectionOnce("source-one", directory)
            fail("consumed source transaction must not replay")
        } catch (expected: IllegalStateException) {
            assertEquals("QUALIFICATION_ALREADY_CONSUMED_NO_RETRY", expected.message)
        }
        assertEquals(2, android.acquired.size)
    }

    @Test fun unequalIndependentHomePixelsStopUnknownAndRetainBothActualSourcesWithoutActions() {
        val home = homeFrame("home")
        val directory = File(temporary.root, "source-unstable")
        val android = SourceAndroid(listOf(home.copy(menuArgbSha256 = "f".repeat(64)),
            home.copy(menuArgbSha256 = "e".repeat(64))), directory)

        val result = TongyiUiReferenceQualificationRunner(android, System::nanoTime)
            .runSourceInspectionOnce("source-unstable", directory)

        assertEquals("UI_SOURCE_INSPECTION_UNKNOWN", result.state)
        assertEquals("SOURCE_HOME_PAIR_UNSTABLE", result.reason)
        assertEquals("0", result.sendCalls)
        assertFalse(result.referenceAccepted)
        assertTrue(result.retainedStages.isEmpty())
        assertEquals(listOf("source-home-1", "source-home-2"), result.retainedSources.map { it.sourceLabel })
        assertEquals(listOf("f".repeat(64), "e".repeat(64)), result.retainedSources.map { it.frame.menuArgbSha256 })
        assertEquals(2, android.acquired.size)
        assertEquals(0, android.businessActions)
        assertTrue(result.ownedCleanup && android.cleaned)
        assertEquals(8, android.originals.size)
        android.originals.forEach { (name, raw) -> assertArrayEquals(raw, File(directory, name).readBytes()) }
        assertEquals(SourcePhase.PAIR_VALIDATION, result.sourceRejection?.phase)
        assertEquals("source-home-2", result.sourceRejection?.sourceLabel)
        assertSame(android.acquired.last(), result.sourceRejection?.frame)
    }

    @Test fun retainsExactRejectedSecondReadinessSourceFrameWithoutReplacingPrimaryUnknown() {
        val home = homeFrame("home").copy(menuArgbSha256 = "f".repeat(64))
        // Explicit synthetic counterexample. This is not the phone's absent second readiness Frame.
        val rejected = home.copy(nodes = home.nodes + node("HOST_ONLY_UNKNOWN_CONVERSATION", "home-root",
            "android.widget.TextView", Bounds(80,480,1120,720), text = "PURE_SYNTHETIC_UNKNOWN_CONVERSATION"))
        val directory = File(temporary.root, "source-second-readiness-rejected")
        val android = SourceAndroid(listOf(home, home.copy()), directory, readinessSamples = listOf(home, rejected))

        val result = TongyiUiReferenceQualificationRunner(android, System::nanoTime)
            .runSourceInspectionOnce("source-second-readiness-rejected", directory)

        assertEquals("UI_SOURCE_INSPECTION_UNKNOWN", result.state)
        assertEquals("UNKNOWN_EXISTING_CONVERSATION_NOT_TOUCHED", result.reason)
        assertEquals("0", result.sendCalls)
        assertFalse(result.referenceAccepted)
        assertTrue(result.retainedStages.isEmpty())
        assertEquals(listOf("source-home-1"), result.retainedSources.map { it.sourceLabel })
        assertEquals(1, android.acquired.size)
        assertEquals(2, android.readinessFrames.size)
        assertEquals(0, android.businessActions)
        assertTrue(result.ownedCleanup && android.cleaned)
        assertSame("the actually returned rejected readiness Frame must reach the existing diagnostic boundary",
            android.readinessFrames.last(), android.rejectedFrame)
        assertEquals("UNKNOWN_EXISTING_CONVERSATION_NOT_TOUCHED", android.rejectedReason)
        assertEquals(1, android.rejectionRetentionCalls)
        assertTrue(File(directory, "HOST_ONLY-rejected-native-projection.raw").readText().contains("PURE_SYNTHETIC_UNKNOWN_CONVERSATION"))
        assertEquals(SourcePhase.SECOND_READINESS, result.sourceRejection?.phase)
        assertEquals("source-home-2", result.sourceRejection?.sourceLabel)
        assertSame(android.readinessFrames.last(), result.sourceRejection?.frame)
        assertTrue(result.sourceRejection!!.retentionAttempted && result.sourceRejection!!.retained)
        assertNull(result.sourceRejection?.retentionFailure)
    }

    @Test fun retainsActualCapturedSourceRejectionNotItsEarlierReadinessFrame() {
        val home = homeFrame("home").copy(menuArgbSha256 = "f".repeat(64))
        val rejectedCapture = home.copy(nodes = home.nodes + node("HOST_ONLY_CAPTURE_UNKNOWN_CONVERSATION", "home-root",
            "android.widget.TextView", Bounds(80,480,1120,720), text = "PURE_SYNTHETIC_CAPTURE_UNKNOWN_CONVERSATION"))
        val directory = File(temporary.root, "source-captured-rejected")
        val android = SourceAndroid(listOf(rejectedCapture, home), directory, readinessSamples = listOf(home, home))

        val result = TongyiUiReferenceQualificationRunner(android, System::nanoTime)
            .runSourceInspectionOnce("source-captured-rejected", directory)

        assertEquals("UI_SOURCE_INSPECTION_UNKNOWN", result.state)
        assertEquals("UNKNOWN_EXISTING_CONVERSATION_NOT_TOUCHED", result.reason)
        assertEquals("0", result.sendCalls)
        assertFalse(result.referenceAccepted)
        assertTrue(result.ownedCleanup && android.cleaned)
        assertEquals(listOf("source-home-1"), result.retainedSources.map { it.sourceLabel })
        assertEquals(1, android.acquired.size)
        assertEquals(1, android.readinessFrames.size)
        assertEquals(0, android.businessActions)
        assertEquals(SourcePhase.SOURCE_VALIDATION, result.sourceRejection?.phase)
        assertEquals("source-home-1", result.sourceRejection?.sourceLabel)
        assertSame(android.acquired.single(), android.rejectedFrame)
        assertNotSame(android.readinessFrames.single(), android.rejectedFrame)
        assertSame(android.acquired.single(), result.sourceRejection?.frame)
        assertTrue(result.sourceRejection!!.retained)
        assertNull(result.sourceRejection?.retentionFailure)
        assertEquals(4, android.originals.size)
    }

    @Test fun failedSourceRejectionFileRetentionCannotReplacePrimaryReasonSourceCountOrCleanup() {
        val home = homeFrame("home").copy(menuArgbSha256 = "f".repeat(64))
        val rejected = home.copy(nodes = home.nodes + node("HOST_ONLY_REJECTED_RETENTION_CONVERSATION", "home-root",
            "android.widget.TextView", Bounds(80,480,1120,720), text = "PURE_SYNTHETIC_RETENTION_FAILURE_CONVERSATION"))
        val directory = File(temporary.root, "source-rejected-retention-failed")
        val missingParentPath = "HOST_ONLY-missing-parent/rejected-native-projection.raw"
        val android = SourceAndroid(listOf(home, home.copy()), directory, readinessSamples = listOf(home, rejected),
            rejectionPath = missingParentPath) // Real external File.createNewFile IOException; no validator mock.

        val result = TongyiUiReferenceQualificationRunner(android, System::nanoTime)
            .runSourceInspectionOnce("source-rejected-retention-failed", directory)

        assertEquals("UI_SOURCE_INSPECTION_UNKNOWN", result.state)
        assertEquals("UNKNOWN_EXISTING_CONVERSATION_NOT_TOUCHED", result.reason)
        assertEquals("0", result.sendCalls)
        assertFalse(result.referenceAccepted)
        assertTrue(result.retainedStages.isEmpty())
        assertEquals(listOf("source-home-1"), result.retainedSources.map { it.sourceLabel })
        assertEquals(1, android.acquired.size)
        assertEquals(0, android.businessActions)
        assertTrue(result.ownedCleanup && android.cleaned)
        assertEquals(SourcePhase.SECOND_READINESS, result.sourceRejection?.phase)
        assertSame(android.readinessFrames.last(), result.sourceRejection?.frame)
        assertTrue(result.sourceRejection!!.retentionAttempted)
        assertFalse(result.sourceRejection!!.retained)
        assertTrue(result.sourceRejection?.retentionFailure?.startsWith("IOException:") == true)
        assertEquals(1, android.rejectionRetentionCalls)
        assertFalse(File(directory, missingParentPath).exists())
        assertEquals(4, android.originals.size)
    }

    /** Synthetic Android/time/file boundary only; real core decides source stability and action policy. */
    private class SourceAndroid(private val samples: List<Frame>, private val directory: File,
        private val readinessSamples: List<Frame> = samples,
        private val rejectionPath: String = "HOST_ONLY-rejected-native-projection.raw") : TongyiUiReferenceQualificationRunner.Android {
        val acquired = mutableListOf<Frame>()
        val readinessFrames = mutableListOf<Frame>()
        val originals = linkedMapOf<String, ByteArray>()
        private val cachedProjections = IdentityHashMap<Frame, ByteArray>()
        var rejectedFrame: Frame? = null
        var rejectedReason: String? = null
        var rejectionRetentionCalls = 0
        var businessActions = 0
        var cleaned = false
        override fun launchOwned(deadlineNanos: Long) { check(System.nanoTime() < deadlineNanos) }
        override fun currentState(deadlineNanos: Long): Frame = readinessSamples.getOrElse(acquired.size) { readinessSamples.last() }
            .copy(menuArgbSha256 = null).also {
                readinessFrames.add(it)
                cachedProjections[it] = ("HOST_ONLY_OPAQUE_NATIVE_PROJECTION_NO_SDK:" + it).toByteArray(Charsets.UTF_8)
            }
        override fun currentFrame(menuPixels: Boolean): Frame {
            check(menuPixels && acquired.size < 2)
            val observed = samples[acquired.size].copy()
            val label = "source-home-${acquired.size + 1}"
            // These opaque fixture bytes are acquired at the external boundary, not measured Native/PNG/XML.
            for (suffix in listOf("native-before.json", "hierarchy.xml", "screen.png", "native-after.json")) {
                val name = "$label-$suffix"
                val raw = "HOST_ONLY_OPAQUE_EXTERNAL_$name".toByteArray(Charsets.UTF_8)
                originals[name] = raw
                check(File(directory, name).createNewFile())
                File(directory, name).writeBytes(raw)
            }
            acquired.add(observed)
            cachedProjections[observed] = ("HOST_ONLY_OPAQUE_NATIVE_PROJECTION_NO_SDK:" + observed).toByteArray(Charsets.UTF_8)
            return observed
        }
        override fun retainSource(sourceLabel: String, observed: Frame): Boolean {
            check(observed === acquired.last() && sourceLabel == "source-home-${acquired.size}")
            return originals.filterKeys { it.startsWith("$sourceLabel-") }.all { (name, raw) -> File(directory, name).readBytes().contentEquals(raw) }
        }
        override fun retainRejectedObservation(stage: Stage, observed: Frame, reason: String, deadlineNanos: Long): Boolean {
            check(stage == Stage.HOME && System.nanoTime() < deadlineNanos)
            rejectionRetentionCalls++
            val cached = checkNotNull(cachedProjections[observed])
            rejectedFrame = observed
            rejectedReason = reason
            val file = File(directory, rejectionPath)
            check(file.createNewFile())
            FileOutputStream(file).use { it.write(cached); it.fd.sync() }
            return file.readBytes().contentEquals(cached)
        }
        override fun retainStage(stage: Stage, observed: Frame): Boolean { businessActions++; return false }
        override fun clickNamedCurrent(role: NamedClick, observed: Frame, node: Node): Boolean { businessActions++; return false }
        override fun setTextCurrent(observed: Frame, editor: Node, text: String): Boolean { businessActions++; return false }
        override fun sleepMillis(milliseconds: Long) {}
        override fun cleanupOwned(): Boolean { cleaned = true; return true }
    }

    @Test fun qualifiesWholeCurrentUiPathWithoutPromotingReferenceOrRelaxingOrdinaryPreparation() {
        val home = homeFrame("home")
        val newEmpty = homeFrame("new")
        // Drawer/editor are synthetic HOST boundary observations, not measured or accepted UI references.
        val drawer = frame(listOf(
            node("drawer-root", null, "android.widget.FrameLayout", FULL_ROOT),
            node("drawer-new-parent", "drawer-root", "android.widget.LinearLayout", Bounds(242,2339,813,2502), clickable = true),
            node("drawer-new-text", "drawer-new-parent", "android.widget.TextView", Bounds(320,2385,736,2456),
                text = "新建对话", resource = "com.aliyun.tongyi:id/tv_new_session"),
        ))
        val editorEmpty = editorFrame("", showingHint = true)
        val ownDraft = editorFrame(TongyiEngineeringRunner.PAYLOAD, showingHint = false)
        val ownCleared = editorFrame("", showingHint = true)

        // Public control: the ordinary PREPARATION route must still reject this current-shaped HOME.
        var ordinaryBusinessActions = 0
        var ordinaryCleaned = false
        val ordinary = object : TongyiPreparationRunner.Android {
            override fun launchOwned(deadlineNanos: Long) {}
            override fun currentFrame(menuPixels: Boolean) = home
            override fun clickCurrent(node: Node): Boolean { ordinaryBusinessActions++; return false }
            override fun setTextCurrent(node: Node, text: String): Boolean { ordinaryBusinessActions++; return false }
            override fun saveOriginals(label: String) { error("ORDINARY_ORIGINALS_UNEXPECTED") }
            override fun sleepMillis(milliseconds: Long) { error("ORDINARY_WAIT_UNEXPECTED") }
            override fun cleanupOwned(): Boolean { ordinaryCleaned = true; return true }
        }
        val ordinaryDirectory = File(temporary.root, "ordinary-control")
        val ordinaryResult = TongyiPreparationRunner(ordinary, System::nanoTime)
            .runOnce("ordinary-control", ordinaryDirectory)
        assertEquals("PREPARATION_UNKNOWN", ordinaryResult.state)
        assertEquals("CURRENT_VIEWPORT_MISMATCH", ordinaryResult.reason)
        assertEquals("0", ordinaryResult.sendCalls)
        assertTrue(ordinaryResult.ownedCleanup && ordinaryCleaned)
        assertEquals(0, ordinaryBusinessActions)

        val stages = linkedMapOf(Stage.HOME to home, Stage.DRAWER to drawer, Stage.NEW_EMPTY to newEmpty,
            Stage.EDITOR_EMPTY to editorEmpty, Stage.OWN_DRAFT to ownDraft, Stage.OWN_CLEARED to ownCleared)
        val android = CurrentUiAndroid(stages)
        val directory = File(temporary.root, "qualification-one")
        val result = TongyiUiReferenceQualificationRunner(android, System::nanoTime)
            .runOnce("qualification-one", directory)

        assertTrue("the qualification opportunity remains consumed", directory.isDirectory)
        assertFalse(File(directory, "SEND_INTENT.json").exists())
        assertEquals("UI_REFERENCE_QUALIFICATION_CLOSED", result.state)
        assertNull(result.reason)
        assertEquals("0", result.sendCalls)
        assertTrue(result.ownedCleanup && android.cleaned)
        assertFalse("candidate observations are not an accepted reusable reference", result.referenceAccepted)
        assertEquals(Stage.values().toList(), result.retainedStages.map { it.stage })
        assertEquals(Stage.values().toList(), android.retained.map { it.stage })
        assertEquals("exactly one complete acquisition in each named stage", Stage.values().toList(), android.captured)
        result.retainedStages.zip(android.retained).forEach { (returned, saved) ->
            assertEquals(returned.stage, saved.stage)
            assertSame("stage facts must refer to the retained observation", saved.frame, returned.frame)
            val supplied = stages.getValue(returned.stage)
            assertEquals(supplied.width, returned.frame.width)
            assertEquals(supplied.height, returned.frame.height)
            assertEquals(supplied.rotation, returned.frame.rotation)
            assertEquals(supplied.rootBounds, returned.frame.rootBounds)
            assertEquals(supplied.owner, returned.frame.owner)
            assertEquals(supplied.nodes, returned.frame.nodes)
        }
        assertEquals(TongyiPreparationRunner.MENU_SHA256, result.retainedStages.first().frame.menuArgbSha256)
        assertEquals(FULL_ROOT, result.retainedStages.first().frame.rootBounds)
        assertEquals(Bounds(40,2348,1160,2520), result.retainedStages[2].frame.nodes.single { it.id == "new-composer-parent" }.bounds)
        assertEquals(FOCUSED_ROOT, result.retainedStages[3].frame.rootBounds)
        assertEquals(listOf(NamedClick.MENU, NamedClick.NEW_SESSION, NamedClick.COMPOSER_FOCUS), android.clicks.map { it.first })
        assertEquals(listOf("home-menu", "drawer-new-parent", "new-composer-parent"), android.clicks.map { it.second.id })
        assertEquals(listOf(TongyiEngineeringRunner.PAYLOAD, ""), android.enteredTexts)
        assertEquals(239, result.retainedStages[4].frame.nodes.single { it.editable }.text.toByteArray(Charsets.UTF_8).size)
        assertEquals(TongyiEngineeringRunner.PAYLOAD, result.retainedStages[4].frame.nodes.single { it.editable }.text)
        assertTrue(result.retainedStages[5].frame.nodes.single { it.editable }.text.isEmpty())
        assertEquals(Stage.OWN_CLEARED, android.scene)
        assertTrue(android.launched)
    }

    @Test fun rejectsVisibleExistingConversationBeforeAnyQualificationAction() {
        val emptyHome = homeFrame("home")
        // HOST-only counterexample, not a measured resource ID, message detector or device observation.
        val existingConversation = emptyHome.copy(nodes = emptyHome.nodes + listOf(
            node("HOST_ONLY_BODY", "home-root", "androidx.recyclerview.widget.RecyclerView", Bounds(0,400,1200,2000)),
            node("HOST_ONLY_PRIOR_MESSAGE", "HOST_ONLY_BODY", "android.widget.TextView", Bounds(80,480,1120,720),
                text = "HOST_ONLY_PRIOR_CONVERSATION_CONTENT"),
        ))
        val drawer = frame(listOf(
            node("drawer-root", null, "android.widget.FrameLayout", FULL_ROOT),
            node("drawer-new-parent", "drawer-root", "android.widget.LinearLayout", Bounds(242,2339,813,2502), clickable = true),
            node("drawer-new-text", "drawer-new-parent", "android.widget.TextView", Bounds(320,2385,736,2456),
                text = "新建对话", resource = "com.aliyun.tongyi:id/tv_new_session"),
        ))
        // The unchanged external boundary otherwise supports the full legal path; no forced mock failure.
        val android = CurrentUiAndroid(linkedMapOf(
            Stage.HOME to existingConversation, Stage.DRAWER to drawer, Stage.NEW_EMPTY to homeFrame("new"),
            Stage.EDITOR_EMPTY to editorFrame("", showingHint = true),
            Stage.OWN_DRAFT to editorFrame(TongyiEngineeringRunner.PAYLOAD, showingHint = false),
            Stage.OWN_CLEARED to editorFrame("", showingHint = true),
        ))
        val directory = File(temporary.root, "initial-existing-conversation")
        val result = TongyiUiReferenceQualificationRunner(android, System::nanoTime)
            .runOnce("initial-existing-conversation", directory)

        assertTrue("the rejected opportunity is consumed without replay", directory.isDirectory)
        assertFalse(File(directory, "SEND_INTENT.json").exists())
        assertEquals("UI_REFERENCE_QUALIFICATION_UNKNOWN", result.state)
        assertEquals("UNKNOWN_EXISTING_CONVERSATION_NOT_TOUCHED", result.reason)
        assertEquals("0", result.sendCalls)
        assertFalse(result.referenceAccepted)
        assertTrue(result.retainedStages.isEmpty())
        assertTrue(android.retained.isEmpty())
        assertTrue(android.captured.isEmpty())
        assertTrue(android.clicks.isEmpty())
        assertTrue(android.enteredTexts.isEmpty())
        assertEquals(Stage.HOME, android.scene)
        assertTrue(android.launched)
        assertTrue("owned closure is attempted but cannot manufacture clean-state authority", android.cleanupAttempted)
        assertFalse(result.ownedCleanup || android.cleaned)
    }

    @Test fun retainsAlreadyCapturedRejectedHomeOriginalsWithoutAcceptingOrActing() {
        val home = homeFrame("home")
        val drawer = frame(listOf(
            node("drawer-root", null, "android.widget.FrameLayout", FULL_ROOT),
            node("drawer-new-parent", "drawer-root", "android.widget.LinearLayout", Bounds(242,2339,813,2502), clickable = true),
            node("drawer-new-text", "drawer-new-parent", "android.widget.TextView", Bounds(320,2385,736,2456),
                text = "新建对话", resource = "com.aliyun.tongyi:id/tv_new_session"),
        ))
        // Opaque HOST-only originals supplied at the external boundary, not PNG/XML/Native SDK evidence.
        val originals = linkedMapOf(
            "HOST_ONLY_home-hierarchy.xml" to "HOST_ONLY_OPAQUE_XML_ORIGINAL_NOT_UIAUTOMATOR",
            "HOST_ONLY_home-screen.png" to "HOST_ONLY_OPAQUE_PNG_ORIGINAL_NOT_A_MEASURED_IMAGE",
            "HOST_ONLY_home-native-before.json" to "HOST_ONLY_OPAQUE_NODE_ORIGINAL_BEFORE_NOT_NATIVE_SDK",
            "HOST_ONLY_home-native-after.json" to "HOST_ONLY_OPAQUE_NODE_ORIGINAL_AFTER_NOT_NATIVE_SDK",
        ).mapValues { it.value.toByteArray(Charsets.UTF_8) }
        val sink = File(temporary.root, "HOST_ONLY-rejected-originals")
        val nonmatchingMenuDigest = "0".repeat(64) // Synthetic counterexample, not the consumed phone's digest.
        // Every later scene still supports the whole legal path; only this capture's menu digest differs.
        val android = CurrentUiAndroid(linkedMapOf(
            Stage.HOME to home, Stage.DRAWER to drawer, Stage.NEW_EMPTY to homeFrame("new"),
            Stage.EDITOR_EMPTY to editorFrame("", showingHint = true),
            Stage.OWN_DRAFT to editorFrame(TongyiEngineeringRunner.PAYLOAD, showingHint = false),
            Stage.OWN_CLEARED to editorFrame("", showingHint = true),
        ), homeMenuArgbSha256 = nonmatchingMenuDigest, rejectedOriginalsDirectory = sink,
            captureOriginals = originals)
        val directory = File(temporary.root, "rejected-home-originals")
        val result = TongyiUiReferenceQualificationRunner(android, System::nanoTime)
            .runOnce("rejected-home-originals", directory)

        assertTrue("the rejected qualification opportunity remains consumed", directory.isDirectory)
        assertFalse(File(directory, "SEND_INTENT.json").exists())
        assertEquals("UI_REFERENCE_QUALIFICATION_UNKNOWN", result.state)
        assertEquals("CURRENT_MENU_PIXELS_MISMATCH", result.reason)
        assertEquals("0", result.sendCalls)
        assertFalse(result.referenceAccepted)
        assertTrue(result.retainedStages.isEmpty())
        assertTrue(android.retained.isEmpty())
        assertEquals("no replacement acquisition for rejected evidence", listOf(Stage.HOME), android.captured)
        assertTrue(android.clicks.isEmpty())
        assertTrue(android.enteredTexts.isEmpty())
        assertEquals(Stage.HOME, android.scene)
        assertTrue(android.launched && android.cleanupAttempted)
        assertEquals("diagnostics do not change existing owned-closure reporting", android.cleaned, result.ownedCleanup)
        val observed = android.completeCapturedFrame
            ?: throw AssertionError("a complete HOME observation was returned before rejection")
        assertEquals(nonmatchingMenuDigest, observed.menuArgbSha256)
        assertEquals(home.owner, observed.owner)
        assertEquals(home.rootBounds, observed.rootBounds)
        assertEquals(home.nodes, observed.nodes)

        assertEquals("the complete already-acquired rejected bundle must survive predicate failure",
            originals.keys.sorted(), sink.list()?.toList()?.sorted().orEmpty())
        originals.forEach { (name, bytes) -> assertArrayEquals(bytes, File(sink, name).readBytes()) }
        assertSame("diagnostic bytes stay bound to the returned rejected observation", observed, android.rejectedOriginalFrame)
        assertEquals(result.reason, android.rejectedOriginalReason)
    }

    /** External Android substitute; optional opaque originals are HOST-only and never SDK acquisition proof. */
    private class CurrentUiAndroid(
        private val scenes: Map<Stage, Frame>,
        private val homeMenuArgbSha256: String = TongyiPreparationRunner.MENU_SHA256,
        private val rejectedOriginalsDirectory: File? = null,
        private val captureOriginals: Map<String, ByteArray> = emptyMap(),
    ) : TongyiUiReferenceQualificationRunner.Android {
        var scene = Stage.HOME
        var launched = false
        var cleaned = false
        var cleanupAttempted = false
        val retained = mutableListOf<StageObservation>()
        val captured = mutableListOf<Stage>()
        val clicks = mutableListOf<Pair<NamedClick, Node>>()
        val enteredTexts = mutableListOf<String>()
        var completeCapturedFrame: Frame? = null
            private set
        var rejectedOriginalFrame: Frame? = null
            private set
        var rejectedOriginalReason: String? = null
            private set
        private var latest: Frame? = null
        private var capturedOriginalBytes: Map<String, ByteArray>? = null

        override fun launchOwned(deadlineNanos: Long) {
            check(!launched && System.nanoTime() < deadlineNanos)
            launched = true
        }
        override fun currentState(deadlineNanos: Long): Frame {
            check(launched && !cleaned && System.nanoTime() < deadlineNanos)
            return scenes.getValue(scene).copy(menuArgbSha256 = null)
        }
        override fun currentFrame(menuPixels: Boolean): Frame {
            check(launched && !cleaned)
            check(scene !in captured) { "DUPLICATE_STAGE_PNG_ACQUISITION" }
            captured.add(scene)
            val supplied = scenes.getValue(scene)
            val observed = if (menuPixels) supplied.copy(menuArgbSha256 = homeMenuArgbSha256)
                else supplied.copy(menuArgbSha256 = null)
            latest = observed
            if (rejectedOriginalsDirectory != null && scene == Stage.HOME) {
                check(menuPixels && captureOriginals.size == 4)
                // Cache a copy during this acquisition; retention never creates replacement observation bytes.
                capturedOriginalBytes = captureOriginals.mapValues { it.value.copyOf() }
                completeCapturedFrame = observed
            }
            return observed
        }
        // The external diagnostic port retains only the already acquired HOST-only bundle.
        override fun retainRejectedObservation(stage: Stage, observed: Frame, reason: String, deadlineNanos: Long): Boolean {
            check(launched && !cleaned && System.nanoTime() < deadlineNanos)
            check(stage == scene && observed === latest && observed === completeCapturedFrame)
            check(retained.none { it.frame === observed })
            val originals = checkNotNull(capturedOriginalBytes)
            val directory = checkNotNull(rejectedOriginalsDirectory)
            check(!directory.exists() && directory.mkdir())
            originals.forEach { (name, bytes) ->
                check(System.nanoTime() < deadlineNanos)
                val file = File(directory, name)
                check(file.createNewFile())
                file.writeBytes(bytes)
                check(file.readBytes().contentEquals(bytes))
            }
            check(System.nanoTime() < deadlineNanos)
            rejectedOriginalFrame = observed
            rejectedOriginalReason = reason
            return true
        }
        override fun retainStage(stage: Stage, observed: Frame): Boolean {
            check(stage == scene && observed === latest)
            check(retained.none { it.stage == stage })
            if (stage == Stage.HOME) check(observed.menuArgbSha256 == TongyiPreparationRunner.MENU_SHA256)
            retained.add(StageObservation(stage, observed))
            return true // HOST boundary receipt only; not proof of Native raw-original acquisition.
        }
        override fun clickNamedCurrent(role: NamedClick, observed: Frame, node: Node): Boolean {
            requireCurrentBinding(observed, node)
            check(retained.any { it.stage == scene })
            val expected = when (role) {
                NamedClick.MENU -> Triple(Stage.HOME, "home-menu", Stage.DRAWER)
                NamedClick.NEW_SESSION -> Triple(Stage.DRAWER, "drawer-new-parent", Stage.NEW_EMPTY)
                NamedClick.COMPOSER_FOCUS -> Triple(Stage.NEW_EMPTY, "new-composer-parent", Stage.EDITOR_EMPTY)
            }
            check(scene == expected.first && node.id == expected.second && node.enabled && node.clickable)
            clicks.add(role to node)
            scene = expected.third
            latest = null
            return true
        }
        override fun setTextCurrent(observed: Frame, editor: Node, text: String): Boolean {
            requireCurrentBinding(observed, editor)
            check(editor.editable && editor.focused && editor.enabled && editor.setTextAvailable)
            check(retained.any { it.stage == scene })
            scene = when {
                scene == Stage.EDITOR_EMPTY && text == TongyiEngineeringRunner.PAYLOAD && editor.text.isEmpty() -> Stage.OWN_DRAFT
                scene == Stage.OWN_DRAFT && text.isEmpty() && !editor.showingHint && editor.text == TongyiEngineeringRunner.PAYLOAD -> Stage.OWN_CLEARED
                else -> error("UNKNOWN_OR_REPLACEMENT_DRAFT_NOT_TOUCHED")
            }
            enteredTexts.add(text)
            latest = null
            return true
        }
        override fun sleepMillis(milliseconds: Long) { check(milliseconds == 50L) }
        override fun cleanupOwned(): Boolean {
            cleanupAttempted = true
            cleaned = launched && scene == Stage.OWN_CLEARED && retained.map { it.stage } == Stage.values().toList()
            return cleaned
        }
        private fun requireCurrentBinding(observed: Frame, node: Node) {
            check(launched && !cleaned && observed === latest)
            check(observed.nodes.singleOrNull { it.id == node.id } == node)
        }
    }

    private fun homeFrame(prefix: String) = frame(listOf(
        // Whole public historical HOME shape; IDs are HOST roles, not selectors or an accepted reference.
        // All 99 visible observations plus four inert invisible absence/decorative nodes; no hidden history.
        node("$prefix-root", null, "android.widget.FrameLayout", Bounds(0,0,1200,2640)),
        node("$prefix-node-1", "$prefix-root", "android.widget.LinearLayout", Bounds(0,0,1200,2640)),
        node("$prefix-node-2", "$prefix-node-1", "android.widget.FrameLayout", Bounds(0,0,1200,2640)),
        node("$prefix-actionbar-root", "$prefix-node-2", "android.widget.LinearLayout", Bounds(0,0,1200,2640), resource = "com.aliyun.tongyi:id/action_bar_root"),
        node("$prefix-android-content", "$prefix-actionbar-root", "android.widget.FrameLayout", Bounds(0,0,1200,2640), resource = "android:id/content"),
        node("$prefix-node-5", "$prefix-android-content", "android.widget.FrameLayout", Bounds(0,0,1200,2640)),
        node("$prefix-node-6", "$prefix-node-5", "android.widget.FrameLayout", Bounds(0,0,1200,2640)),
        node("$prefix-node-7", "$prefix-node-5", "android.widget.FrameLayout", Bounds(0,0,1200,2640)),
        node("$prefix-node-8", "$prefix-node-5", "android.widget.FrameLayout", Bounds(0,0,1200,2640)),
        node("$prefix-node-9", "$prefix-node-8", "android.widget.FrameLayout", Bounds(0,0,1200,2640)),
        node("$prefix-node-10", "$prefix-node-9", "android.view.ViewGroup", Bounds(0,0,1200,2640)),
        node("$prefix-node-11", "$prefix-node-10", "android.widget.FrameLayout", Bounds(0,0,1200,2640)),
        node("$prefix-node-12", "$prefix-node-11", "android.widget.FrameLayout", Bounds(0,0,1200,2640)),
        node("$prefix-node-13", "$prefix-node-12", "androidx.viewpager.widget.ViewPager", Bounds(0,0,1200,2640)),
        node("$prefix-node-42", "$prefix-node-13", "android.widget.FrameLayout", Bounds(0,0,1200,2640)),
        node("$prefix-node-43", "$prefix-node-42", "android.view.View", Bounds(0,0,1200,2640)).copy(visible = false),
        node("$prefix-node-44", "$prefix-node-42", "android.widget.FrameLayout", Bounds(0,0,1200,2640)),
        node("$prefix-node-45", "$prefix-node-44", "android.widget.FrameLayout", Bounds(0,0,1200,2640)),
        node("$prefix-node-46", "$prefix-node-45", "android.widget.FrameLayout", Bounds(0,0,1200,2640)),
        node("$prefix-node-47", "$prefix-node-45", "android.widget.FrameLayout", Bounds(0,0,1200,2640)),
        node("$prefix-root-overlay", "$prefix-node-47", "android.widget.FrameLayout", Bounds(0,0,1200,2640)),
        node("$prefix-root-overlay-inner", "$prefix-root-overlay", "android.widget.FrameLayout", Bounds(0,0,1200,2640)),
        node("$prefix-root-overlay-background", "$prefix-root-overlay-inner", "android.view.View", Bounds(0,0,1200,2640)),
        node("$prefix-status-overlay", "$prefix-root-overlay", "android.view.View", Bounds(0,0,1200,333)),
        node("$prefix-node-52", "$prefix-node-47", "android.widget.FrameLayout", Bounds(0,154,1200,2640)),
        node("$prefix-main", "$prefix-node-47", "android.widget.FrameLayout", Bounds(0,154,1200,2640)),
        node("$prefix-node-54", "$prefix-main", "android.widget.FrameLayout", Bounds(0,154,1200,2640)),
        node("$prefix-node-55", "$prefix-node-54", "android.widget.FrameLayout", Bounds(0,154,1200,2640)),
        node("$prefix-node-56", "$prefix-node-55", "android.widget.FrameLayout", Bounds(0,2640,1200,2640)).copy(visible = false),
        node("$prefix-welcome-frame", "$prefix-main", "android.widget.FrameLayout", Bounds(0,154,1200,2560)),
        node("$prefix-node-58", "$prefix-welcome-frame", "android.widget.FrameLayout", Bounds(0,154,1200,2149)),
        node("$prefix-node-59", "$prefix-node-58", "android.widget.FrameLayout", Bounds(0,154,1200,2149)),
        node("$prefix-node-60", "$prefix-node-59", "android.widget.FrameLayout", Bounds(0,154,1200,2149)),
        node("$prefix-node-61", "$prefix-node-60", "android.widget.FrameLayout", Bounds(0,333,1200,2149)),
        node("$prefix-empty-body", "$prefix-node-61", "androidx.recyclerview.widget.RecyclerView", Bounds(0,333,1200,333)).copy(visible = false),
        node("$prefix-welcome", "$prefix-welcome-frame", "android.widget.LinearLayout", Bounds(0,154,1200,2149)),
        node("$prefix-node-64", "$prefix-welcome", "android.widget.LinearLayout", Bounds(80,764,1200,879)),
        node("$prefix-title", "$prefix-node-64", "android.widget.TextView", Bounds(80,764,424,879), text = "我是千问"),
        node("$prefix-subtitle", "$prefix-welcome", "android.widget.TextView", Bounds(80,906,903,977), text = "为你答疑 办事 创作，随时找我聊天"),
        node("$prefix-suggestions", "$prefix-welcome", "android.widget.LinearLayout", Bounds(0,977,1200,1528)),
        node("$prefix-node-68", "$prefix-suggestions", "android.widget.TextView", Bounds(80,1076,676,1200), text = "如何快速知道自己的血型？", clickable = true),
        node("$prefix-node-69", "$prefix-suggestions", "android.widget.TextView", Bounds(80,1240,677,1364), text = "“三寸不烂之舌”指的是谁？", clickable = true),
        node("$prefix-node-70", "$prefix-suggestions", "android.widget.TextView", Bounds(80,1404,719,1528), text = "自首的路上被抓还算自首吗？", clickable = true),
        node("$prefix-toolbar", "$prefix-main", "android.widget.FrameLayout", Bounds(0,154,1200,333)),
        node("$prefix-wrapper", "$prefix-toolbar", "android.widget.LinearLayout", Bounds(56,193,156,293)),
        node("$prefix-menu", "$prefix-wrapper", "android.widget.FrameLayout", Bounds(56,193,156,293), clickable = true),
        node("$prefix-node-74", "$prefix-menu", "android.widget.ImageView", Bounds(66,203,146,283)),
        node("$prefix-toolbar-center", "$prefix-toolbar", "android.widget.LinearLayout", Bounds(469,160,731,326), clickable = true),
        node("$prefix-node-76", "$prefix-toolbar-center", "android.widget.LinearLayout", Bounds(469,160,731,326)),
        node("$prefix-node-77", "$prefix-node-76", "android.widget.LinearLayout", Bounds(542,160,658,326), clickable = true),
        node("$prefix-node-78", "$prefix-node-77", "android.widget.FrameLayout", Bounds(542,213,658,273)),
        node("$prefix-node-79", "$prefix-node-78", "android.widget.ImageView", Bounds(542,213,658,273)),
        node("$prefix-node-80", "$prefix-node-76", "android.widget.ImageView", Bounds(658,206,731,279), clickable = true),
        node("$prefix-toolbar-right", "$prefix-toolbar", "android.widget.LinearLayout", Bounds(894,177,1160,310)),
        node("$prefix-node-82", "$prefix-toolbar-right", "android.widget.FrameLayout", Bounds(894,177,1027,310), clickable = true),
        node("$prefix-node-83", "$prefix-node-82", "android.widget.ImageView", Bounds(894,177,1027,310)),
        node("$prefix-node-84", "$prefix-toolbar-right", "android.widget.FrameLayout", Bounds(1027,177,1160,310), clickable = true),
        node("$prefix-node-85", "$prefix-node-84", "android.widget.ImageView", Bounds(1027,177,1160,310)),
        node("$prefix-control-area", "$prefix-main", "android.widget.FrameLayout", Bounds(0,2149,1200,2560), clickable = true),
        node("$prefix-node-87", "$prefix-control-area", "android.view.View", Bounds(0,2149,1200,2560)),
        node("$prefix-control-outer", "$prefix-control-area", "android.widget.LinearLayout", Bounds(0,2189,1200,2560)),
        node("$prefix-control-row", "$prefix-control-outer", "android.widget.LinearLayout", Bounds(0,2189,1200,2560)),
        node("$prefix-control-inactive", "$prefix-control-row", "android.widget.FrameLayout", Bounds(40,2189,1160,2189)).copy(visible = false),
        node("$prefix-controls", "$prefix-control-row", "android.view.ViewGroup", Bounds(0,2189,1200,2560)),
        node("$prefix-picker-wrapper", "$prefix-controls", "android.widget.FrameLayout", Bounds(0,2189,1200,2308)),
        node("$prefix-picker", "$prefix-picker-wrapper", "androidx.recyclerview.widget.RecyclerView", Bounds(0,2189,1200,2308)),
        node("$prefix-node-94", "$prefix-picker", "android.widget.FrameLayout", Bounds(40,2189,311,2308), clickable = true),
        node("$prefix-node-95", "$prefix-node-94", "android.widget.FrameLayout", Bounds(40,2189,311,2308)),
        node("$prefix-node-96", "$prefix-node-95", "android.widget.LinearLayout", Bounds(40,2189,311,2308)),
        node("$prefix-node-97", "$prefix-node-96", "android.widget.ImageView", Bounds(83,2225,129,2271)),
        node("$prefix-quick", "$prefix-node-96", "android.widget.TextView", Bounds(142,2223,228,2274), text = "快速"),
        node("$prefix-node-99", "$prefix-node-96", "android.widget.ImageView", Bounds(241,2235,268,2262)),
        node("$prefix-node-100", "$prefix-picker", "android.widget.FrameLayout", Bounds(338,2189,655,2308), clickable = true),
        node("$prefix-node-101", "$prefix-node-100", "android.widget.FrameLayout", Bounds(338,2189,655,2308)),
        node("$prefix-node-102", "$prefix-node-101", "android.widget.LinearLayout", Bounds(338,2189,655,2308)),
        node("$prefix-node-103", "$prefix-node-102", "android.widget.ImageView", Bounds(381,2225,427,2271)),
        node("$prefix-node-104", "$prefix-node-102", "android.widget.TextView", Bounds(440,2223,612,2274), text = "工作助理"),
        node("$prefix-node-105", "$prefix-picker", "android.widget.FrameLayout", Bounds(682,2189,996,2308), clickable = true),
        node("$prefix-node-106", "$prefix-node-105", "android.widget.FrameLayout", Bounds(682,2189,996,2308)),
        node("$prefix-node-107", "$prefix-node-106", "android.widget.LinearLayout", Bounds(682,2189,996,2308)),
        node("$prefix-node-108", "$prefix-node-107", "android.widget.ImageView", Bounds(725,2225,771,2271)),
        node("$prefix-node-109", "$prefix-node-107", "android.widget.TextView", Bounds(784,2223,953,2274), text = "AI生视频"),
        node("$prefix-node-110", "$prefix-picker", "android.widget.FrameLayout", Bounds(1023,2189,1200,2308), clickable = true),
        node("$prefix-node-111", "$prefix-node-110", "android.widget.FrameLayout", Bounds(1023,2189,1200,2308)),
        node("$prefix-node-112", "$prefix-node-111", "android.widget.LinearLayout", Bounds(1023,2189,1200,2308)),
        node("$prefix-node-113", "$prefix-node-112", "android.widget.ImageView", Bounds(1066,2225,1112,2271)),
        node("$prefix-node-114", "$prefix-node-112", "android.widget.TextView", Bounds(1125,2223,1200,2274), text = "生活帮手"),
        node("$prefix-composer-outer", "$prefix-controls", "android.view.ViewGroup", Bounds(40,2348,1160,2520), clickable = true),
        node("$prefix-composer-parent", "$prefix-composer-outer", "android.view.ViewGroup", Bounds(40,2348,1160,2520), clickable = true),
        node("$prefix-accessories", "$prefix-composer-parent", "android.widget.LinearLayout", Bounds(40,2348,1133,2520)),
        node("$prefix-node-118", "$prefix-accessories", "android.widget.FrameLayout", Bounds(887,2372,1010,2495), clickable = true),
        node("$prefix-node-119", "$prefix-node-118", "android.widget.ImageView", Bounds(911,2396,985,2470)),
        node("$prefix-node-120", "$prefix-accessories", "android.widget.FrameLayout", Bounds(1010,2372,1133,2495), clickable = true),
        node("$prefix-node-121", "$prefix-node-120", "android.widget.ImageView", Bounds(1034,2396,1108,2470)),
        node("$prefix-leading", "$prefix-composer-parent", "android.widget.FrameLayout", Bounds(67,2373,190,2496), clickable = true),
        node("$prefix-node-123", "$prefix-leading", "android.widget.ImageView", Bounds(91,2397,165,2471)),
        node("$prefix-placeholder", "$prefix-composer-parent", "android.widget.TextView", Bounds(197,2399,860,2470), text = "发消息或按住说话..."),
        node("$prefix-node-125", "$prefix-control-area", "android.view.View", Bounds(0,2308,1200,2560)),
        node("$prefix-node-126", "$prefix-control-area", "android.view.View", Bounds(0,2308,1200,2560)),
        node("$prefix-footer", "$prefix-main", "android.widget.FrameLayout", Bounds(0,2566,1200,2607)),
        node("$prefix-disclaimer", "$prefix-footer", "android.widget.TextView", Bounds(419,2566,781,2607), text = "内容由 AI 生成，请注意核实"),
        node("$prefix-node-129", "$prefix-node-5", "android.widget.FrameLayout", Bounds(0,0,1200,2640)),
        node("$prefix-node-130", "$prefix-node-129", "android.widget.ImageView", Bounds(0,0,1200,2640)),
    ))
    private fun editorFrame(text: String, showingHint: Boolean) = frame(listOf(
        node("editor-root", null, "android.widget.FrameLayout", FOCUSED_ROOT),
        node("editor-quick", "editor-root", "android.widget.TextView", Bounds(142,1450,228,1501), text = "快速"),
        node("editor", "editor-root", "android.widget.EditText", Bounds(40,1530,1160,1660), text = text,
            hint = "发消息", showingHint = showingHint, editable = true, focused = true, setTextAvailable = true),
    ), FOCUSED_ROOT)
    private fun frame(nodes: List<Node>, root: Bounds = FULL_ROOT) = Frame(1200,2640,0,root,"HOST_ONLY_OWNED_BOUNDARY",nodes)
    private fun node(id: String, parent: String?, className: String, bounds: Bounds, text: String = "",
        resource: String = "", clickable: Boolean = false, hint: String? = null, showingHint: Boolean = false,
        editable: Boolean = false, focused: Boolean = false, setTextAvailable: Boolean = false) =
        Node(id,parent,TongyiEngineeringRunner.PACKAGE,className,bounds,true,true,clickable,text,resource,hint,
            showingHint,editable,focused,setTextAvailable)

    companion object {
        private val FULL_ROOT = Bounds(0,0,1200,2640)
        private val FOCUSED_ROOT = Bounds(0,0,1200,1680)
    }
}
