package com.aneb.probe.research.engineering

import java.util.UUID

/** Debug-only fresh owned-script byte acquisition; identity is still decided by the real lifecycle. */
class QualificationOwnerShellTransport(
    private val ownedScripts: OwnedShellScriptTransport,
    private val os: OsPipe,
    private val clock: TongyiEngineeringRunner.MonotonicClock,
) : QualificationOwnerProbeScope.Transport {
        /** External OS boundary; metadata binds one actual owned-script acquisition, not an identity verdict. */
    interface OsPipe : OwnedProcessLifecycle.Shell {
        fun executeOwnedScript(apiCommand: String, pid: String, nonce: String,
            deadlineNanos: Long): Acquisition
    }

    /** Descriptor EOF is separate from payload markers. No OS child exit is inferred from script status. */
    data class Acquisition(val rawBytes: ByteArray, val eof: Boolean, val stderr: String = "")

    override fun execute(command: String, deadlineNanos: Long): OwnedProcessLifecycle.ShellResult =
        os.execute(command, deadlineNanos)

    override fun sleepMillis(milliseconds: Long) = os.sleepMillis(milliseconds)

    override fun executeOwnerGroup(pid: String, deadlineNanos: Long): List<OwnedProcessLifecycle.ShellResult> =
        executeGroup(pid, deadlineNanos, "ps -A -o PID")

    override fun executeOwnedPidPositiveGroup(pid: String, deadlineNanos: Long): List<OwnedProcessLifecycle.ShellResult> =
        executeGroup(pid, deadlineNanos, "ps -p $pid -o PID")

    private fun executeGroup(pid: String, deadlineNanos: Long,
        presenceCommand: String): List<OwnedProcessLifecycle.ShellResult> {
        requireComplete(pid.matches(Regex("[1-9][0-9]*")) && clock.nowNanos() < deadlineNanos)
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val prefix = "ANEB_OWNER_${nonce}_${pid}_${deadlineNanos}"
        val commands = listOf(presenceCommand, "cat /proc/$pid/stat", "cat /proc/$pid/status",
            "cat /proc/$pid/cmdline", "cat /proc/$pid/stat")
        val script = buildString {
            commands.forEachIndexed { index, command ->
                append("printf '\\n${prefix}_${index}_BEGIN\\n'\n")
                append("{ $command; } 2>&1\n")
                append("aneb_owner_rc=\$?\n") // Immediate command script status, not an OS direct-child exit.
                append("printf '\\n${prefix}_${index}_END_%s\\n' \"\$aneb_owner_rc\"\n")
            }
            append("printf '\\n${prefix}_DONE\\n'\n")
        }
        val acquired = ownedScripts.execute(script) { apiCommand ->
            requireComplete(clock.nowNanos() < deadlineNanos)
            os.executeOwnedScript(apiCommand, pid, nonce, deadlineNanos)
        }.handle
        requireComplete(acquired.eof && acquired.stderr.isEmpty() && clock.nowNanos() < deadlineNanos)
        val raw = acquired.rawBytes.copyOf() // A local packet only, never an identity cache.
        val binding = prefix.toByteArray(Charsets.UTF_8)
        var bindings = 0
        var search = 0
        while (true) {
            val found = indexOf(raw, binding, search)
            if (found < 0) break
            bindings++
            search = found + binding.size
        }
        requireComplete(bindings == commands.size * 2 + 1) // Any payload/duplicate marker collision rejects.
        var cursor = 0
        val results = commands.indices.map { index ->
            cursor = consume(raw, "\n${prefix}_${index}_BEGIN\n", cursor)
            val endMarker = "\n${prefix}_${index}_END_".toByteArray(Charsets.UTF_8)
            val end = indexOf(raw, endMarker, cursor)
            requireComplete(end >= cursor)
            val payload = raw.copyOfRange(cursor, end)
            val rcStart = end + endMarker.size
            val rcEnd = indexOf(raw, byteArrayOf(10), rcStart)
            requireComplete(rcEnd >= rcStart)
            val rcBytes = raw.copyOfRange(rcStart, rcEnd)
            requireComplete(rcBytes.isNotEmpty() && rcBytes.all { it in 48..57 })
            val rc = rcBytes.toString(Charsets.UTF_8).toIntOrNull()
            requireComplete(rc != null && rc in 0..255)
            val text = payload.toString(Charsets.UTF_8)
            requireComplete(payload.contentEquals(text.toByteArray(Charsets.UTF_8)))
            cursor = rcEnd + 1
            // Five results share the one completed byte acquisition, not five independent EOF observations.
            OwnedProcessLifecycle.ShellResult(true, rc, text)
        }
        cursor = consume(raw, "\n${prefix}_DONE\n", cursor)
        requireComplete(cursor == raw.size && clock.nowNanos() < deadlineNanos)
        return results // Bad per-command statuses remain actual values; the unchanged real scope rejects them.
    }

    private fun consume(raw: ByteArray, marker: String, cursor: Int): Int {
        val expected = marker.toByteArray(Charsets.UTF_8)
        requireComplete(cursor >= 0 && cursor <= raw.size - expected.size &&
            expected.indices.all { raw[cursor + it] == expected[it] })
        return cursor + expected.size
    }

    private fun indexOf(raw: ByteArray, needle: ByteArray, start: Int): Int {
        for (index in start..raw.size - needle.size) {
            if (needle.indices.all { raw[index + it] == needle[it] }) return index
        }
        return -1
    }

    private fun requireComplete(value: Boolean) = check(value) { "OWN_PROCESS_IDENTITY_UNKNOWN_OR_CHANGED" }
}
