package com.aneb.probe.research.engineering

import org.junit.Assert.*
import org.junit.Test

class QualificationOwnerProbeScopeTest {
    @Test fun closesExactOwnedIdentityWithinCallerInputBudgetUsingCapableGroupedOsTransport() {
        val clock = SyntheticClock(100_000_000_000L)
        val rawCommand = TongyiEngineeringRunner.PACKAGE + "\u0000\u0000\u0000\u0000"
        val owner = OwnedProcessLifecycle.Owner("4242", 10118, "450161014",
            listOf(TongyiEngineeringRunner.PACKAGE), rawCommand)
        val carrier = CapableOsCarrier(owner, clock)
        val started = clock.nowNanos()
        val until = started + TongyiEngineeringRunner.INPUT_NANOS

        val failure = runCatching { QualificationOwnerProbeScope(carrier, clock).requireAlive(owner, until) }
            .exceptionOrNull()

        println("SYNTHETIC_OWNER_SCOPE_PUBLIC_RESULT closed=${failure == null} " +
            "reason=${failure?.message} failureType=${failure?.javaClass?.name} " +
            "elapsedNanos=${clock.nowNanos() - started} callerUntil=$until " +
            "forbiddenEffects=${carrier.forbiddenEffects} " +
            "observations=${carrier.observations.map { it.command to it.reply.completed }}")
        // Observable external ownership/raw/deadline invariants, not private parser/order/call-count assertions.
        assertEquals(0, carrier.forbiddenEffects)
        assertTrue(carrier.observations.all { it.until == until })
        val commandObservation = carrier.observations.single { it.command == "cat /proc/${owner.pid}/cmdline" }
        assertArrayEquals(rawCommand.toByteArray(Charsets.UTF_8), commandObservation.reply.stdout.toByteArray(Charsets.UTF_8))
        assertEquals(0, commandObservation.reply.exitCode)
        assertTrue(commandObservation.reply.completed)
        assertNull("the correctly owned raw identity must close within the unchanged caller INPUT5 budget", failure)
        assertTrue(clock.nowNanos() < until)
        assertEquals(5_000_000_000L, until - started)
    }

    /** Synthetic monotonic external clock, not measured phone overhead or a production clock change. */
    private class SyntheticClock(private var value: Long) : TongyiEngineeringRunner.MonotonicClock {
        override fun nowNanos() = value
        fun advance(nanos: Long) { value += nanos }
    }

    /** Actual-shaped external OS responses; every public transport launch costs synthetic1.1s. */
    private class CapableOsCarrier(owner: OwnedProcessLifecycle.Owner, private val clock: SyntheticClock) :
        QualificationOwnerProbeScope.Transport {
        data class Observation(val command: String, val until: Long, val reply: OwnedProcessLifecycle.ShellResult)
        val observations = mutableListOf<Observation>()
        var forbiddenEffects = 0
            private set
        private val pid = owner.pid
        private val stat = "$pid (${TongyiEngineeringRunner.PACKAGE}) " +
            MutableList(20) { "0" }.also { it[0] = "S"; it[19] = owner.startTicks }.joinToString(" ") + "\n"
        private val files = mapOf(
            "ps -A -o PID" to "PID\n7\n$pid\n9999\n",
            "cat /proc/$pid/stat" to stat,
            "cat /proc/$pid/status" to "Name:\t${TongyiEngineeringRunner.PACKAGE}\nUid:\t${owner.uid}\t${owner.uid}\t${owner.uid}\t${owner.uid}\n",
            "cat /proc/$pid/cmdline" to owner.rawCommandLine,
        )
        private fun reply(command: String, until: Long): OwnedProcessLifecycle.ShellResult {
            if (command !in files) forbiddenEffects++
            val result = OwnedProcessLifecycle.ShellResult(clock.nowNanos() <= until,
                if (command in files) 0 else 1, files[command].orEmpty())
            observations.add(Observation(command, until, result))
            return result
        }
        override fun execute(command: String, deadlineNanos: Long): OwnedProcessLifecycle.ShellResult {
            clock.advance(1_100_000_000L)
            return reply(command, deadlineNanos)
        }
        override fun executeOwnerGroup(pid: String, deadlineNanos: Long): List<OwnedProcessLifecycle.ShellResult> {
            // One capable OS invocation returns all original-shaped outputs, without an internal owner/result mock.
            clock.advance(1_100_000_000L)
            return listOf("ps -A -o PID", "cat /proc/$pid/stat", "cat /proc/$pid/status",
                "cat /proc/$pid/cmdline", "cat /proc/$pid/stat").map { reply(it, deadlineNanos) }
        }
        override fun sleepMillis(milliseconds: Long) { clock.advance(milliseconds * 1_000_000L) }
    }
}
