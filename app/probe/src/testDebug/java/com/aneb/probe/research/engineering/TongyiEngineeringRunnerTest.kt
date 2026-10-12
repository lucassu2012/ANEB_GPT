package com.aneb.probe.research.engineering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.io.File
import java.util.StringTokenizer

class TongyiEngineeringRunnerTest {
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
