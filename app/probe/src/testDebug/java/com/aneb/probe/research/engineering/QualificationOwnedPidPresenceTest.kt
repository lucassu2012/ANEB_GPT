package com.aneb.probe.research.engineering

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QualificationOwnedPidPresenceTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun ownedPidPositiveRequiresFreshMatchingOwnerAndRejectsSecondStatDrift() {
        val clock = object : TongyiEngineeringRunner.MonotonicClock {
            override fun nowNanos() = 100L // No modeled OS duration or performance claim.
        }
        val owner = OwnedProcessLifecycle.Owner("4242", 10118, "450161014",
            listOf(TongyiEngineeringRunner.PACKAGE), TongyiEngineeringRunner.PACKAGE + "\u0000\u0000\u0000\u0000")
        val directory = files.newFolder("owned-positive").canonicalFile
        val pipe = FreshIdentityOsPipe(owner, directory)
        val producer = QualificationOwnerShellTransport(OwnedShellScriptTransport(directory), pipe, clock)
        val scope = QualificationOwnerProbeScope(producer, clock,
            QualificationOwnerProbeScope.Presence.OWNED_PID_POSITIVE)
        val until = 10_000L

        val initialFailure = runCatching { scope.requireAlive(owner, until) }.exceptionOrNull()

        assertNull("first fresh exact-owner positive packet must succeed; missing capability is assertion RED",
            initialFailure)
        // Observed external OS requests, paired with real packet parsing and identity behavior below.
        assertEquals(listOf("ps -p ${owner.pid} -o PID"), pipe.presenceCommands)

        val driftFailure = runCatching { scope.requireAlive(owner, until) }.exceptionOrNull()

        assertTrue("same PID with changed stat-after ticks must refuse", driftFailure is IllegalStateException)
        assertEquals("OWN_PROCESS_IDENTITY_UNKNOWN_OR_CHANGED", driftFailure?.message)
        assertEquals(listOf("ps -p ${owner.pid} -o PID", "ps -p ${owner.pid} -o PID"), pipe.presenceCommands)
        assertEquals("each public guard must acquire its own bound packet", 2, pipe.nonces.size)
        assertEquals("no identity packet is reused across calls", 2, pipe.nonces.distinct().size)
    }

    @Test fun ownedPidPositiveRejectsCompleteNonExactPresenceWithoutSerialFallback() {
        val clock = object : TongyiEngineeringRunner.MonotonicClock {
            override fun nowNanos() = 100L
        }
        val owner = OwnedProcessLifecycle.Owner("4242", 10118, "450161014",
            listOf(TongyiEngineeringRunner.PACKAGE), TongyiEngineeringRunner.PACKAGE + "\u0000\u0000\u0000\u0000")
        val cases = listOf(
            "header-only" to "PID\n",
            "wrong-pid" to "PID\n9999\n",
            "extra-pid" to "PID\n${owner.pid}\n9999\n",
            "duplicate-pid" to "PID\n${owner.pid}\n${owner.pid}\n",
        )
        for ((label, presenceOutput) in cases) {
            val directory = files.newFolder("presence-$label").canonicalFile
            val pipe = FreshIdentityOsPipe(owner, directory, presenceOutput)
            val producer = QualificationOwnerShellTransport(OwnedShellScriptTransport(directory), pipe, clock)
            val scope = QualificationOwnerProbeScope(producer, clock,
                QualificationOwnerProbeScope.Presence.OWNED_PID_POSITIVE)

            val failure = runCatching { scope.requireAlive(owner, 10_000L) }.exceptionOrNull()

            assertTrue("$label complete successful packet must refuse", failure is IllegalStateException)
            assertEquals(label, "OWN_PROCESS_IDENTITY_UNKNOWN_OR_CHANGED", failure?.message)
            assertEquals(label, listOf("ps -p ${owner.pid} -o PID"), pipe.presenceCommands)
            assertEquals("$label must use a fresh acquired packet", 1, pipe.nonces.size)
            assertEquals("$label must not attempt serial fallback", 0, pipe.serialFallbackAttempts)
        }
    }

    /** External OS byte-pipe fixture only; Scope, script transport, packet parser and lifecycle are real. */
    private class FreshIdentityOsPipe(private val owner: OwnedProcessLifecycle.Owner,
        private val directory: File,
        private val presenceOutput: String? = null) : QualificationOwnerShellTransport.OsPipe {
        val presenceCommands = ArrayList<String>()
        val nonces = ArrayList<String>()
        var serialFallbackAttempts = 0
            private set

        override fun execute(command: String, deadlineNanos: Long): OwnedProcessLifecycle.ShellResult {
            serialFallbackAttempts++
            error("UNEXPECTED_SERIAL_FALLBACK")
        }

        override fun sleepMillis(milliseconds: Long) = error("UNEXPECTED_SLEEP")

        override fun executeOwnedScript(apiCommand: String, pid: String, nonce: String,
            deadlineNanos: Long): QualificationOwnerShellTransport.Acquisition {
            require(pid == owner.pid && apiCommand.startsWith("sh "))
            val scriptFile = File(apiCommand.removePrefix("sh "))
            require(scriptFile.isFile && scriptFile.canonicalFile == scriptFile.absoluteFile &&
                scriptFile.parentFile == directory)
            val submitted = scriptFile.readBytes()
            val text = submitted.toString(Charsets.UTF_8)
            require(submitted.contentEquals(text.toByteArray(Charsets.UTF_8)))
            val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
            // Derive the response binding from this actual submitted script, not a saved old packet.
            val prefix = lines.first().removePrefix("printf '\\n").removeSuffix("_0_BEGIN\\n'")
            require(lines.first() == "printf '\\n${prefix}_0_BEGIN\\n'" &&
                prefix == "ANEB_OWNER_${nonce}_${pid}_${deadlineNanos}")
            val commands = lines.filter { it.startsWith("{ ") && it.endsWith("; } 2>&1") }
                .map { it.removePrefix("{ ").removeSuffix("; } 2>&1") }
            require(commands.size == 5)
            require(commands.drop(1) == listOf("cat /proc/$pid/stat", "cat /proc/$pid/status",
                "cat /proc/$pid/cmdline", "cat /proc/$pid/stat"))
            presenceCommands.add(commands.first())
            nonces.add(nonce)
            val secondPacket = nonces.size == 2
            require(nonces.size <= 2)
            val packet = ByteArrayOutputStream()
            commands.forEachIndexed { index, command ->
                val stdout = when (command) {
                    "ps -p $pid -o PID" -> presenceOutput ?: "PID\n$pid\n"
                    // If a wrong full-list route is used, return a genuine full-list fixture, never point rows.
                    "ps -A -o PID" -> "PID\n7\n$pid\n9999\n"
                    "cat /proc/$pid/stat" -> stat(if (secondPacket && index == 4)
                        (owner.startTicks.toLong() + 1).toString() else owner.startTicks)
                    "cat /proc/$pid/status" -> "Name:\t${TongyiEngineeringRunner.PACKAGE}\n" +
                        "Uid:\t${owner.uid}\t${owner.uid}\t${owner.uid}\t${owner.uid}\n"
                    "cat /proc/$pid/cmdline" -> owner.rawCommandLine
                    else -> error("UNEXPECTED_OS_COMMAND")
                }
                packet.write("\n${prefix}_${index}_BEGIN\n".toByteArray(Charsets.UTF_8))
                packet.write(stdout.toByteArray(Charsets.UTF_8))
                packet.write("\n${prefix}_${index}_END_0\n".toByteArray(Charsets.UTF_8))
            }
            packet.write("\n${prefix}_DONE\n".toByteArray(Charsets.UTF_8))
            return QualificationOwnerShellTransport.Acquisition(packet.toByteArray(), eof = true)
        }

        private fun stat(ticks: String) = "${owner.pid} (${TongyiEngineeringRunner.PACKAGE}) " +
            MutableList(20) { "0" }.also { it[0] = "S"; it[19] = ticks }.joinToString(" ") + "\n"
    }
}
