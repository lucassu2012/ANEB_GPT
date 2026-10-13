package com.aneb.probe.research.engineering

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QualificationOwnerShellTransportTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun closesFreshOwnedIdentityWithinOriginalInputBudgetThroughOwnedScriptPipe() {
        val clock = SyntheticClock(100_000_000_000L)
        val rawCommand = TongyiEngineeringRunner.PACKAGE + "\u0000\u0000\u0000\u0000"
        val owner = OwnedProcessLifecycle.Owner("4242", 10118, "450161014",
            listOf(TongyiEngineeringRunner.PACKAGE), rawCommand)
        val directory = files.newFolder("owned").canonicalFile
        val carrier = CapableOsPipe(owner, clock, directory)
        val producer = QualificationOwnerShellTransport(OwnedShellScriptTransport(directory), carrier, clock)
        val started = clock.nowNanos()
        val until = started + TongyiEngineeringRunner.INPUT_NANOS

        val failure = runCatching { QualificationOwnerProbeScope(producer, clock).requireAlive(owner, until) }
            .exceptionOrNull()

        println("SYNTHETIC_OWNER_SHELL_PUBLIC_RESULT closed=${failure == null} " +
            "reason=${failure?.message} failureType=${failure?.javaClass?.name} " +
            "elapsedNanos=${clock.nowNanos() - started} callerUntil=$until " +
            "forbiddenEffects=${carrier.forbiddenEffects} " +
            "ownedScriptPipeAcquired=${carrier.submittedScript != null} " +
            "observations=${carrier.observations.map { it.command to it.reply.completed }}")
        // Observable OS raw/deadline/no-action safety, not script-file counts or private parser assertions.
        assertEquals(0, carrier.forbiddenEffects)
        assertTrue(carrier.observations.all { it.until == until })
        val commandObservation = carrier.observations.single { it.command == "cat /proc/${owner.pid}/cmdline" }
        assertArrayEquals(rawCommand.toByteArray(Charsets.UTF_8), commandObservation.reply.stdout.toByteArray(Charsets.UTF_8))
        assertEquals(0, commandObservation.reply.exitCode)
        assertTrue(commandObservation.reply.completed)
        assertNull("fresh exact ownership must close within the original INPUT5 through the capable OS pipe", failure)
        assertTrue(clock.nowNanos() < until)
        assertEquals(5_000_000_000L, until - started)
    }

    /** External monotonic clock; invocation costs are synthetic, not measured Android latency. */
    private class SyntheticClock(private var value: Long) : TongyiEngineeringRunner.MonotonicClock {
        override fun nowNanos() = value
        fun advance(nanos: Long) { value += nanos }
    }

    /** External OS/file byte-pipe carrier only; no internal parser, Alive, requireAlive or predicate substitute. */
    private class CapableOsPipe(owner: OwnedProcessLifecycle.Owner, private val clock: SyntheticClock,
        private val directory: File) : QualificationOwnerShellTransport.OsPipe {
        data class Observation(val command: String, val until: Long, val reply: OwnedProcessLifecycle.ShellResult)
        val observations = mutableListOf<Observation>()
        var forbiddenEffects = 0
            private set
        var submittedScript: ByteArray? = null
            private set
        private val pid = owner.pid
        private val stat = "$pid (${TongyiEngineeringRunner.PACKAGE}) " +
            MutableList(20) { "0" }.also { it[0] = "S"; it[19] = owner.startTicks }.joinToString(" ") + "\n"
        private val outputs = mapOf(
            "ps -A -o PID" to "PID\n7\n$pid\n9999\n",
            "cat /proc/$pid/stat" to stat,
            "cat /proc/$pid/status" to "Name:\t${TongyiEngineeringRunner.PACKAGE}\nUid:\t${owner.uid}\t${owner.uid}\t${owner.uid}\t${owner.uid}\n",
            "cat /proc/$pid/cmdline" to owner.rawCommandLine,
        )
        private fun reply(command: String, until: Long): OwnedProcessLifecycle.ShellResult {
            if (command !in outputs) forbiddenEffects++
            val result = OwnedProcessLifecycle.ShellResult(clock.nowNanos() <= until,
                if (command in outputs) 0 else 1, outputs[command].orEmpty())
            observations.add(Observation(command, until, result))
            return result
        }
        override fun execute(command: String, deadlineNanos: Long): OwnedProcessLifecycle.ShellResult {
            clock.advance(1_100_000_000L)
            return reply(command, deadlineNanos)
        }
        override fun executeOwnedScript(apiCommand: String, pid: String, nonce: String,
            deadlineNanos: Long): QualificationOwnerShellTransport.Acquisition {
            // Synthetic OS acquisition binds the real submitted file and requested identity/deadline.
            // This capability is not called by the first serial scaffold; no Android shell/EOF claim.
            require(pid == this.pid && nonce.matches(Regex("[A-Za-z0-9_-]+")))
            require(apiCommand.startsWith("sh "))
            val scriptFile = File(apiCommand.removePrefix("sh "))
            require(scriptFile.isFile && scriptFile.canonicalFile == scriptFile.absoluteFile &&
                scriptFile.parentFile == directory)
            val script = scriptFile.readBytes()
            val text = script.toString(Charsets.UTF_8)
            require(script.contentEquals(text.toByteArray(Charsets.UTF_8)))
            val prefix = "ANEB_OWNER_${nonce}_${pid}_${deadlineNanos}"
            val commands = listOf("ps -A -o PID", "cat /proc/$pid/stat", "cat /proc/$pid/status",
                "cat /proc/$pid/cmdline", "cat /proc/$pid/stat")
            require(text.contains(prefix) && commands.all { text.contains(it) })
            submittedScript = script
            clock.advance(1_100_000_000L)
            val packet = ByteArrayOutputStream()
            commands.forEachIndexed { index, command ->
                val result = reply(command, deadlineNanos)
                packet.write("\n${prefix}_${index}_BEGIN\n".toByteArray(Charsets.UTF_8))
                packet.write(result.stdout.toByteArray(Charsets.UTF_8))
                packet.write("\n${prefix}_${index}_END_${result.exitCode}\n".toByteArray(Charsets.UTF_8))
            }
            packet.write("\n${prefix}_DONE\n".toByteArray(Charsets.UTF_8))
            return QualificationOwnerShellTransport.Acquisition(packet.toByteArray(), true)
        }
        override fun sleepMillis(milliseconds: Long) { clock.advance(milliseconds * 1_000_000L) }
    }
}
