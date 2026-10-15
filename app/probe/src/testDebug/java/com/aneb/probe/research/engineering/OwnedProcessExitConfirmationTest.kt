package com.aneb.probe.research.engineering

import java.util.ArrayDeque
import org.junit.Assert.*
import org.junit.Test

class OwnedProcessExitConfirmationTest {
    @Test fun successfulOwnedStopConfirmsFreshAbsenceAfterPidDisappearsDuringPostStopRead() {
        val clock = object : TongyiEngineeringRunner.MonotonicClock {
            override fun nowNanos() = 100L // Deterministic boundary, not simulated phone latency.
        }
        val pid = "9966"
        val owner = OwnedProcessLifecycle.Owner(pid, 10118, "462910452",
            listOf(TongyiEngineeringRunner.PACKAGE), TongyiEngineeringRunner.PACKAGE + "\u0000\u0000\u0000\u0000")
        val stat = "$pid (${TongyiEngineeringRunner.PACKAGE}) " +
            MutableList(20) { "0" }.also { it[0] = "S"; it[19] = owner.startTicks }.joinToString(" ") + "\n"
        val until = 10_000L
        val steps = listOf(
            Step("ps -A -o PID", OwnedProcessLifecycle.ShellResult(true, 0, "PID\n1\n$pid\n")),
            Step("cat /proc/$pid/stat", OwnedProcessLifecycle.ShellResult(true, 0, stat)),
            Step("cat /proc/$pid/status", OwnedProcessLifecycle.ShellResult(true, 0,
                "Name:\t${TongyiEngineeringRunner.PACKAGE}\nUid:\t10118\t10118\t10118\t10118\n")),
            Step("cat /proc/$pid/cmdline", OwnedProcessLifecycle.ShellResult(true, 0, owner.rawCommandLine)),
            Step("cat /proc/$pid/stat", OwnedProcessLifecycle.ShellResult(true, 0, stat)),
            Step("am force-stop ${TongyiEngineeringRunner.PACKAGE}", OwnedProcessLifecycle.ShellResult(true, 0, "")),
            Step("ps -A -o PID", OwnedProcessLifecycle.ShellResult(true, 0, "PID\n1\n$pid\n")),
            Step("cat /proc/$pid/stat", OwnedProcessLifecycle.ShellResult(true, 1,
                "cat: /proc/$pid/stat: No such file or directory\n")),
            // A new complete full-list witness, not ENOENT-as-absence or relabeled point output.
            Step("ps -A -o PID", OwnedProcessLifecycle.ShellResult(true, 0, "PID\n1\n")),
        )
        val shell = ScriptedExitRaceShell(steps, until)
        val lifecycle = OwnedProcessLifecycle(shell, clock)

        val result = runCatching { lifecycle.stopAndConfirm(owner, OwnedProcessLifecycle.Stop.TARGET_FORCE_STOP, until) }

        assertNull("one successful owned stop must allow a fresh complete absence witness after the post-stop stat race",
            result.exceptionOrNull())
        assertEquals(true, result.getOrNull())
        assertEquals("all external replies must be consumed in their real command order", steps.map { it.command }, shell.commands)
        assertTrue("the fresh absence reply must actually be acquired", shell.remaining.isEmpty())
        assertEquals("do not issue a second stop", 1,
            shell.commands.count { it == "am force-stop ${TongyiEngineeringRunner.PACKAGE}" })
        assertTrue("exit confirmation requires full-list evidence, never point presence",
            shell.commands.none { it.startsWith("ps -p ") })
    }

    @Test fun successfulOwnedStopRetainsOriginalUnknownWhenFreshAbsenceWitnessIsUnproven() {
        data class WitnessCase(val name: String, val reply: OwnedProcessLifecycle.ShellResult,
            val expireOnReply: Boolean = false)
        val pid = "9966"
        val owner = OwnedProcessLifecycle.Owner(pid, 10118, "462910452",
            listOf(TongyiEngineeringRunner.PACKAGE), TongyiEngineeringRunner.PACKAGE + "\u0000\u0000\u0000\u0000")
        val stat = "$pid (${TongyiEngineeringRunner.PACKAGE}) " +
            MutableList(20) { "0" }.also { it[0] = "S"; it[19] = owner.startTicks }.joinToString(" ") + "\n"
        val until = 10_000L
        val cases = listOf(
            WitnessCase("still contains owned PID", OwnedProcessLifecycle.ShellResult(true, 0, "PID\n1\n$pid\n")),
            WitnessCase("malformed full list", OwnedProcessLifecycle.ShellResult(true, 0, "PID\nnot-a-pid\n")),
            WitnessCase("header only", OwnedProcessLifecycle.ShellResult(true, 0, "PID\n")),
            WitnessCase("empty output", OwnedProcessLifecycle.ShellResult(true, 0, "")),
            WitnessCase("nonzero status", OwnedProcessLifecycle.ShellResult(true, 1, "PID\n1\n")),
            WitnessCase("stderr present", OwnedProcessLifecycle.ShellResult(true, 0, "PID\n1\n", "ps failed\n")),
            WitnessCase("incomplete EOF", OwnedProcessLifecycle.ShellResult(false, 0, "PID\n1\n")),
            WitnessCase("read returned after deadline", OwnedProcessLifecycle.ShellResult(true, 0, "PID\n1\n"), true),
        )
        for (case in cases) {
            var now = 100L
            val clock = object : TongyiEngineeringRunner.MonotonicClock {
                override fun nowNanos() = now
            }
            val steps = listOf(
                Step("ps -A -o PID", OwnedProcessLifecycle.ShellResult(true, 0, "PID\n1\n$pid\n")),
                Step("cat /proc/$pid/stat", OwnedProcessLifecycle.ShellResult(true, 0, stat)),
                Step("cat /proc/$pid/status", OwnedProcessLifecycle.ShellResult(true, 0,
                    "Name:\t${TongyiEngineeringRunner.PACKAGE}\nUid:\t10118\t10118\t10118\t10118\n")),
                Step("cat /proc/$pid/cmdline", OwnedProcessLifecycle.ShellResult(true, 0, owner.rawCommandLine)),
                Step("cat /proc/$pid/stat", OwnedProcessLifecycle.ShellResult(true, 0, stat)),
                Step("am force-stop ${TongyiEngineeringRunner.PACKAGE}", OwnedProcessLifecycle.ShellResult(true, 0, "")),
                Step("ps -A -o PID", OwnedProcessLifecycle.ShellResult(true, 0, "PID\n1\n$pid\n")),
                Step("cat /proc/$pid/stat", OwnedProcessLifecycle.ShellResult(true, 1,
                    "cat: /proc/$pid/stat: No such file or directory\n")),
                Step("ps -A -o PID", case.reply),
            )
            val scripted = ScriptedExitRaceShell(steps, until)
            val shell = object : OwnedProcessLifecycle.Shell {
                override fun execute(command: String, deadlineNanos: Long): OwnedProcessLifecycle.ShellResult =
                    scripted.execute(command, deadlineNanos).also {
                        // An external clock boundary at this reply, not a model of phone latency.
                        if (case.expireOnReply && scripted.remaining.isEmpty()) now = until + 1L
                    }
                override fun sleepMillis(milliseconds: Long) = scripted.sleepMillis(milliseconds)
            }
            val lifecycle = OwnedProcessLifecycle(shell, clock)

            val result = runCatching { lifecycle.stopAndConfirm(owner, OwnedProcessLifecycle.Stop.TARGET_FORCE_STOP, until) }

            assertEquals(case.name, "OWN_PROCESS_EXIT_UNKNOWN_TEST_SHELL_RESULT_UNKNOWN", result.exceptionOrNull()?.message)
            assertTrue(case.name, result.exceptionOrNull() is IllegalStateException)
            assertNull(case.name, result.getOrNull())
            assertEquals(case.name, steps.map { it.command }, scripted.commands)
            assertTrue(case.name, scripted.remaining.isEmpty())
            assertEquals("${case.name}: exactly one owned stop", 1,
                scripted.commands.count { it == "am force-stop ${TongyiEngineeringRunner.PACKAGE}" })
            assertEquals("${case.name}: initial list, post-stop list, one fresh witness only", 3,
                scripted.commands.count { it == "ps -A -o PID" })
            assertTrue(case.name, scripted.commands.none { it.startsWith("ps -p ") })
        }
    }

    private data class Step(val command: String, val reply: OwnedProcessLifecycle.ShellResult)

    /** Only external shell replies are scripted; observe, identity decoding and stopAndConfirm are real. */
    private class ScriptedExitRaceShell(steps: List<Step>, private val deadline: Long) : OwnedProcessLifecycle.Shell {
        val remaining = ArrayDeque(steps)
        val commands = ArrayList<String>()

        override fun execute(command: String, deadlineNanos: Long): OwnedProcessLifecycle.ShellResult {
            commands.add(command)
            check(deadlineNanos == deadline && remaining.isNotEmpty()) { "UNEXPECTED_EXTERNAL_REQUEST" }
            val step = remaining.removeFirst()
            check(command == step.command) { "UNEXPECTED_EXTERNAL_COMMAND_$command" }
            return step.reply
        }

        override fun sleepMillis(milliseconds: Long) = error("UNEXPECTED_SLEEP_IN_ONE_WITNESS_EXIT_RACE")
    }
}
