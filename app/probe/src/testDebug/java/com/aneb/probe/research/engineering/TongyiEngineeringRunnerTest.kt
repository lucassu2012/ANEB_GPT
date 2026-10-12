package com.aneb.probe.research.engineering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.io.File
import java.util.StringTokenizer

class TongyiEngineeringRunnerTest {
    @Test fun preparationPublicEntryBindsCurrentAnonymousMenuAndOwnDraftWithoutSendCapability() {
        val root = Files.createTempDirectory("tongyi-preparation-external-android").toFile()
        val android = PreparationAndroid()
        val result = TongyiPreparationRunner(android, android).runOnce("host-preparation", root.resolve("attempt"))
        assertEquals("PREPARATION_CLOSED; failure=${result.reason}", "PREPARATION_CLOSED", result.state)
        assertEquals("0", result.sendCalls)
        assertTrue(result.ownedCleanup)
        assertEquals(listOf("new-empty", "draft-current"), android.originals)
        assertEquals(TongyiEngineeringRunner.PAYLOAD, android.savedDraft)
        assertEquals("", android.actualDraft)
        assertFalse(root.resolve("attempt/SEND_INTENT.json").exists())
    }

    /** External Android observations/actions only; production selection/orchestration is not replaced. */
    private class PreparationAndroid : TongyiPreparationRunner.Android, TongyiEngineeringRunner.MonotonicClock {
        var state = "home"
        var actualDraft = ""
        var savedDraft: String? = null
        val originals = ArrayList<String>()
        val clicks = ArrayList<String>()
        var menuHash = TongyiPreparationRunner.MENU_SHA256
        var driftMenuParentOnRecheck = false
        var pixelReads = 0
        var replaceDraftAfterSaving = false
        var now = 1_000_000_000L
        override fun nowNanos() = now
        override fun launchOwned(deadlineNanos: Long) = Unit
        override fun currentFrame(menuPixels: Boolean): TongyiPreparationRunner.Frame {
            if (menuPixels) pixelReads++
            fun node(id: String, parent: String?, cls: String, bounds: TongyiPreparationRunner.Bounds,
                clickable: Boolean = false, text: String = "", resource: String = "") =
                TongyiPreparationRunner.Node(id, parent, TongyiEngineeringRunner.PACKAGE, cls, bounds,
                    true, true, clickable, text, resource)
            val nodes = if (state == "drawer") listOf(
                node("new-parent", "root", "android.widget.LinearLayout", TongyiPreparationRunner.Bounds(242,2339,813,2486), true),
                node("new-text", "new-parent", "android.widget.TextView", TongyiPreparationRunner.Bounds(468,2385,680,2456),
                    text="新建对话", resource="com.aliyun.tongyi:id/tv_new_session"),
            ) else if (state == "editor") listOf(
                node("quick", "root", "android.widget.TextView", TongyiPreparationRunner.Bounds(142,1246,228,1297), text="快速"),
                node("editor", "root", "android.widget.EditText", TongyiPreparationRunner.Bounds(40,1371,1147,1523), true,
                    text=if (actualDraft.isEmpty()) "发消息..." else actualDraft).copy(hint="发消息...",
                    showingHint=actualDraft.isEmpty(), editable=true, focused=true, setTextAvailable=true),
            ) else listOf(
                node("toolbar", "root", "android.widget.FrameLayout", TongyiPreparationRunner.Bounds(0,154,1200,333)),
                node("menu-wrapper", "toolbar", "android.widget.LinearLayout", TongyiPreparationRunner.Bounds(56,193,156,293)),
                node("menu", "menu-wrapper", "android.widget.FrameLayout", TongyiPreparationRunner.Bounds(56,193,156,293), true),
                node("toolbar-center", "toolbar", "android.widget.LinearLayout", TongyiPreparationRunner.Bounds(426,193,774,293)),
                node("toolbar-right", "toolbar", "android.widget.LinearLayout", TongyiPreparationRunner.Bounds(862,193,1144,293)),
                node("quick", "root", "android.widget.TextView", TongyiPreparationRunner.Bounds(142,2223,228,2274), text="快速"),
                node("focus-parent", "root", "android.view.ViewGroup", TongyiPreparationRunner.Bounds(40,2348,1160,2486), true),
                node("focus-text", "focus-parent", "android.widget.TextView", TongyiPreparationRunner.Bounds(197,2399,860,2470),
                    text="发消息或按住说话..."),
            )
            return TongyiPreparationRunner.Frame(1200,2640,0,TongyiPreparationRunner.Bounds(0,0,1200,2486),
                "external-PID-UID-ticks-raw-commandline",nodes.map {
                    if(driftMenuParentOnRecheck && pixelReads > 1 && it.id == "menu-wrapper") it.copy(className="android.view.View") else it
                },if(menuPixels) menuHash else null)
        }
        override fun clickCurrent(node: TongyiPreparationRunner.Node): Boolean {
            clicks.add(node.id)
            state = when(node.id) { "menu" -> "drawer"; "new-parent" -> "home"; "focus-parent" -> "editor"; else -> error("UNEXPECTED_PUBLIC_CLICK") }
            return true
        }
        override fun setTextCurrent(node: TongyiPreparationRunner.Node, text: String): Boolean {
            check(state == "editor" && node.id == "editor")
            actualDraft = text
            return true
        }
        override fun saveOriginals(label: String) {
            originals.add(label)
            if(label == "draft-current") {
                savedDraft=actualDraft
                if(replaceDraftAfterSaving) actualDraft="HOST_ONLY_REPLACEMENT_DRAFT"
            }
        }
        override fun sleepMillis(milliseconds: Long) { now += milliseconds * 1_000_000L }
        override fun cleanupOwned() = actualDraft.isEmpty()
    }

    @Test fun preparationRejectsOnePixelMismatchBeforeAnyPublicClick() {
        val root = Files.createTempDirectory("tongyi-preparation-pixel-mismatch").toFile()
        val android = PreparationAndroid().apply { menuHash="0".repeat(64) }
        val result = TongyiPreparationRunner(android, android).runOnce("host-pixel-hold", root.resolve("attempt"))
        assertEquals("PREPARATION_UNKNOWN",result.state)
        assertEquals("CURRENT_MENU_PIXELS_MISMATCH",result.reason)
        assertTrue(android.clicks.isEmpty())
        assertEquals("0",result.sendCalls)
    }

    @Test fun preparationRejectsCurrentMenuParentDriftAtTheRightBeforeActionObservation() {
        val root = Files.createTempDirectory("tongyi-preparation-current-parent-drift").toFile()
        val android = PreparationAndroid().apply { driftMenuParentOnRecheck=true }
        val result = TongyiPreparationRunner(android, android).runOnce("host-tree-hold", root.resolve("attempt"))
        assertEquals("PREPARATION_UNKNOWN", result.state)
        assertTrue("Changed current parent must prevent the menu invocation", android.clicks.isEmpty())
        assertEquals("0", result.sendCalls)
    }

    @Test fun preparationDoesNotLiteralNormalizeOrDeleteARealPlaceholderShapedDraft() {
        val root = Files.createTempDirectory("tongyi-preparation-literal-draft").toFile()
        val android = PreparationAndroid().apply { actualDraft="发消息..." }
        val result = TongyiPreparationRunner(android, android).runOnce("host-real-draft", root.resolve("attempt"))
        assertEquals("PREPARATION_UNKNOWN",result.state)
        assertEquals("CURRENT_EMPTY_FOCUS_REQUIRED",result.reason)
        assertEquals("发消息...",android.actualDraft)
        assertFalse(result.ownedCleanup)
        assertTrue(android.originals.none { it == "draft-current" })
    }

    @Test fun preparationRetainsAReplacementDraftAndUnknownClosureAfterCompleteReadback() {
        val root = Files.createTempDirectory("tongyi-preparation-replacement-draft").toFile()
        val android = PreparationAndroid().apply { replaceDraftAfterSaving=true }
        val result = TongyiPreparationRunner(android, android).runOnce("host-draft-drift", root.resolve("attempt"))
        assertEquals("PREPARATION_UNKNOWN",result.state)
        assertEquals("UNKNOWN_REPLACEMENT_DRAFT_NOT_TOUCHED",result.reason)
        assertEquals(TongyiEngineeringRunner.PAYLOAD,android.savedDraft)
        assertEquals("HOST_ONLY_REPLACEMENT_DRAFT",android.actualDraft)
        assertFalse(result.ownedCleanup)
        assertEquals("0",result.sendCalls)
    }

    @Test fun publicStringDispatchReachesCompleteOwnedProcessAndRecordingScripts() {
        val root = Files.createTempDirectory("tongyi-host-platform-string-dispatch").toFile()
        val transport = OwnedShellScriptTransport(root)
        val scripts = listOf(
            "{ ps -A -o PID; } 2>&1; aneb_rc=\$?; printf \"\\nANEB807_HOST:%s\\n\" \"\$aneb_rc\"",
            "echo \$\$ > /owned/capture.pid; exec screenrecord --time-limit 90 /owned/video.mp4 2>/owned/record.stderr",
        )
        // External AOSP Runtime.exec(String) carrier only; no Android/device execution.
        val reached = scripts.map { script ->
            transport.execute(script) { command ->
                val argv = StringTokenizer(command).let { tokens ->
                    buildList { while (tokens.hasMoreTokens()) add(tokens.nextToken()) }
                }
                if (argv.size == 2 && argv[0] == "sh") File(argv[1]).readText()
                else argv.getOrElse(2) { "NO_SCRIPT_OPERAND" }
            }.handle
        }
        assertEquals("The actual public String emission must reach every complete owned script",
            scripts.map { it + "\n" }, reached)
    }

    private class ExternalAndroid(private val ownProcesses: OwnedProcessLifecycle? = null,
        private val ownCapture: OwnedProcessLifecycle.Owner? = null) : TongyiEngineeringRunner.Phone, TongyiEngineeringRunner.MonotonicClock {
        var now = 1_000_000_000L
        var text = ""
        var sends = 0
        var recording = false
        var cleaned = false
        var ordinary = true
        var sendThrows = false
        var preparationElapsed = 0L
        var entries = 0
        override fun nowNanos() = now
        override fun prepare(deadlineNanos: Long): TongyiEngineeringRunner.Snapshot { now += preparationElapsed; return snapshot() }
        override fun enterExact(text: String) { entries++; this.text = text }
        override fun snapshot() = TongyiEngineeringRunner.Snapshot(
            TongyiEngineeringRunner.PACKAGE, true, ordinary, true, text.isEmpty(), text,
            true, true, "W1", true,
        )
        override fun saveOriginals(label: String) = Unit
        override fun startRecording(readyDeadlineNanos: Long) { recording = true }
        override fun sendOnce() { check(recording); sends++; if (sendThrows) error("HOST_ONLY_AMBIGUOUS_SEND"); text = "" }
        override fun observeUntil(deadlineNanos: Long) { now = deadlineNanos }
        override fun stopOwnedRecording() {
            ownCapture?.let { checkNotNull(ownProcesses).stopAndConfirm(it,
                OwnedProcessLifecycle.Stop.CAPTURE_SIGINT, now + 20_000_000_000L) }
            recording = false
        }
        override fun cleanupOwned(): Boolean {
            cleaned = true
            if (ownProcesses == null) return true
            val deadline = now + 20_000_000_000L
            val stopped = ownProcesses.stopAndConfirm(TARGET_OWNER, OwnedProcessLifecycle.Stop.TARGET_FORCE_STOP, deadline)
            ownProcesses.requirePackageAbsent(TongyiEngineeringRunner.PACKAGE, deadline)
            return stopped
        }
    }

    private class ExternalProcessOs : OwnedProcessLifecycle.Shell {
        var alive = true
        var stops = 0
        var captureAlive = true
        var captureStops = 0
        var observationOverride: OwnedProcessLifecycle.ShellResult? = null
        var actualUid = 10344
        override fun execute(command: String, deadlineNanos: Long): OwnedProcessLifecycle.ShellResult {
            if (command == "am force-stop ${TongyiEngineeringRunner.PACKAGE}") {
                stops++; alive = false
                return OwnedProcessLifecycle.ShellResult(true, 0, "")
            }
            if (command == "kill -2 2000043") {
                captureStops++; captureAlive = false
                return OwnedProcessLifecycle.ShellResult(true, 0, "")
            }
            if (command == "ps -A -o PID" && observationOverride != null) return checkNotNull(observationOverride)
            return when (command) {
                "ps -A -o PID" -> OwnedProcessLifecycle.ShellResult(true, 0, "PID\n1\n" +
                    (if (alive) "2000042\n" else "") + (if (captureAlive) "2000043\n" else ""))
                "ps -A -o PID,NAME" -> OwnedProcessLifecycle.ShellResult(true, 0, "PID NAME\n1 init\n" +
                    (if (alive) "2000042 ${TongyiEngineeringRunner.PACKAGE}\n" else "") +
                    (if (captureAlive) "2000043 screenrecord\n" else ""))
                "cat /proc/2000042/stat" -> OwnedProcessLifecycle.ShellResult(true, 0,
                    "2000042 (com.aliyun.tongyi) S " + (1..18).joinToString(" ") { "0" } + " 41234 0\n")
                "cat /proc/2000042/status" -> OwnedProcessLifecycle.ShellResult(true, 0, "Name:\ttongyi\nUid:\t$actualUid\t$actualUid\t$actualUid\t$actualUid\n")
                "cat /proc/2000042/cmdline" -> OwnedProcessLifecycle.ShellResult(true, 0, "com.aliyun.tongyi\u0000")
                "cat /proc/2000043/stat" -> OwnedProcessLifecycle.ShellResult(true, 0,
                    "2000043 (screenrecord) S " + (1..18).joinToString(" ") { "0" } + " 42345 0\n")
                "cat /proc/2000043/status" -> OwnedProcessLifecycle.ShellResult(true, 0, "Uid:\t2000\t2000\t2000\t2000\n")
                "cat /proc/2000043/cmdline" -> OwnedProcessLifecycle.ShellResult(true, 0,
                    "screenrecord\u0000--time-limit\u000090\u0000/own-run/video.mp4\u0000")
                else -> error("UNEXPECTED_EXTERNAL_OS_COMMAND_$command")
            }
        }
        override fun sleepMillis(milliseconds: Long) = Unit
    }

    @Test fun wholeCleanupStopsShellVisibleOwnedTargetDespiteInvisibleAppProcFile() {
        val root = Files.createTempDirectory("tongyi-host-only-cross-uid").toFile()
        val os = ExternalProcessOs()
        val processClock = TongyiEngineeringRunner.MonotonicClock { 1_000_000_000L }
        val processes = OwnedProcessLifecycle(os, processClock)
        val phone = ExternalAndroid(processes)
        val result = TongyiEngineeringRunner(phone, phone).runOnce("host-W1", "W1", root.resolve("attempt"))
        assertEquals("OBSERVATION_CLOSED", result.state)
        assertTrue(result.ownedCleanup)
        assertFalse("The shell still sees the owned process; invisible App /proc is not absence", os.alive)
        assertEquals(1, os.stops)
    }

    @Test fun wholeUnknownObservationCannotStopOwnedTargetOrReportCleanup() {
        val unknownReplies = listOf(
            OwnedProcessLifecycle.ShellResult(true, 1, "", "Permission denied"),
            OwnedProcessLifecycle.ShellResult(true, 0, ""),
            OwnedProcessLifecycle.ShellResult(false, null, "PID\n1\n", "timeout"),
        )
        for (reply in unknownReplies) {
            val root = Files.createTempDirectory("tongyi-host-only-unknown-process").toFile()
            val os = ExternalProcessOs().apply { observationOverride = reply }
            val processes = OwnedProcessLifecycle(os, TongyiEngineeringRunner.MonotonicClock { 1_000_000_000L })
            val phone = ExternalAndroid(processes)
            val result = TongyiEngineeringRunner(phone, phone).runOnce("host-W1", "W1", root.resolve("attempt"))
            assertEquals("OBSERVATION_UNKNOWN", result.state)
            assertFalse(result.ownedCleanup)
            assertTrue(os.alive)
            assertEquals(0, os.stops)
            assertTrue(root.resolve("attempt/SEND_INTENT.json").isFile)
        }
    }

    @Test fun wholeCaptureAndTargetUseSharedShellClosureAndRejectReplacementUid() {
        for (replaceUid in listOf(false, true)) {
            val root = Files.createTempDirectory("tongyi-host-only-capture-closure").toFile()
            val os = ExternalProcessOs().apply { if (replaceUid) actualUid++ }
            val processes = OwnedProcessLifecycle(os, TongyiEngineeringRunner.MonotonicClock { 1_000_000_000L })
            val phone = ExternalAndroid(processes, CAPTURE_OWNER)
            val result = TongyiEngineeringRunner(phone, phone).runOnce("host-W1", "W1", root.resolve("attempt"))
            assertFalse(os.captureAlive)
            assertEquals(1, os.captureStops)
            assertEquals(if (replaceUid) "OBSERVATION_UNKNOWN" else "OBSERVATION_CLOSED", result.state)
            assertEquals(!replaceUid, result.ownedCleanup)
            assertEquals(if (replaceUid) 0 else 1, os.stops)
            assertEquals(replaceUid, os.alive)
        }
    }

    @Test fun wholePublicEntryPreservesExactDraftRecordsSingleSendAndClosesOwnResources() {
        val root = Files.createTempDirectory("tongyi-host-only").toFile()
        val phone = ExternalAndroid()
        val result = TongyiEngineeringRunner(phone, phone).runOnce("host-W1", "W1", root.resolve("attempt"))
        assertEquals("OBSERVATION_CLOSED", result.state)
        assertEquals("1", result.sendCalls)
        assertEquals(1, phone.sends)
        assertTrue(result.ownedCleanup)
        assertTrue(phone.cleaned)
        assertTrue(root.resolve("attempt/SEND_INTENT.json").isFile)
    }

    @Test fun unknownSendRetainsConsumedIntentAndCannotBeReplayed() {
        val root = Files.createTempDirectory("tongyi-host-only-unknown").toFile()
        val phone = ExternalAndroid().apply { sendThrows = true }
        val runner = TongyiEngineeringRunner(phone, phone)
        val directory = root.resolve("attempt")
        val result = runner.runOnce("host-W1", "W1", directory)
        assertEquals("OBSERVATION_UNKNOWN", result.state)
        assertEquals("UNKNOWN", result.sendCalls)
        assertEquals(1, phone.sends)
        assertTrue(directory.resolve("SEND_INTENT.json").isFile)
        val replay = runCatching { runner.runOnce("host-W1", "W1", directory) }
        assertTrue(replay.isFailure)
        assertEquals(1, phone.sends)
    }

    @Test fun invalidOrdinaryStateOrExpiredPreparationHasZeroInputAndZeroSend() {
        for (expired in listOf(false, true)) {
            val root = Files.createTempDirectory("tongyi-host-only-admission").toFile()
            val phone = ExternalAndroid().apply {
                ordinary = expired
                preparationElapsed = if (expired) TongyiEngineeringRunner.PREP_NANOS else 0
            }
            val result = TongyiEngineeringRunner(phone, phone).runOnce("host-W1", "W1", root.resolve("attempt"))
            assertEquals("OBSERVATION_UNKNOWN", result.state)
            assertEquals("0", result.sendCalls)
            assertEquals(0, phone.entries)
            assertEquals(0, phone.sends)
            assertTrue(phone.cleaned)
        }
    }

    companion object {
        private val TARGET_OWNER = OwnedProcessLifecycle.Owner("2000042", 10344, "41234", listOf(TongyiEngineeringRunner.PACKAGE))
        private val CAPTURE_OWNER = OwnedProcessLifecycle.Owner("2000043", 2000, "42345",
            listOf("screenrecord", "--time-limit", "90", "/own-run/video.mp4"))
    }
}
