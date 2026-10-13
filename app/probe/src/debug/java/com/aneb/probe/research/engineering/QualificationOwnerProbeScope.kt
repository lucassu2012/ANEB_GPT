package com.aneb.probe.research.engineering

/** Debug-only fresh per-call owner scope; raw identity is decided by the unchanged real lifecycle. */
class QualificationOwnerProbeScope(
    private val transport: Transport,
    private val clock: TongyiEngineeringRunner.MonotonicClock,
) {
    /** External OS/time boundary only. No Native glue or identity cache across calls. */
    interface Transport : OwnedProcessLifecycle.Shell {
        /** Null means capability absent only; failed supported groups must return failure or throw. */
        fun executeOwnerGroup(pid: String, deadlineNanos: Long): List<OwnedProcessLifecycle.ShellResult>? = null
    }

    fun requireAlive(owner: OwnedProcessLifecycle.Owner, deadlineNanos: Long) {
        check(owner.pid.matches(Regex("[1-9][0-9]*")) && clock.nowNanos() < deadlineNanos) {
            "OWN_PROCESS_IDENTITY_UNKNOWN_OR_CHANGED"
        }
        val group = transport.executeOwnerGroup(owner.pid, deadlineNanos)?.toList()
        if (group == null) {
            OwnedProcessLifecycle(transport, clock).requireAlive(owner, deadlineNanos)
            return // Only absent capability retains serial; supported failures never fall back.
        }
        val commands = listOf("ps -A -o PID", "cat /proc/${owner.pid}/stat", "cat /proc/${owner.pid}/status",
            "cat /proc/${owner.pid}/cmdline", "cat /proc/${owner.pid}/stat")
        check(clock.nowNanos() < deadlineNanos && group.size == commands.size &&
            group.all { it.completed && it.exitCode == 0 && it.stderr.isEmpty() }) {
            "OWN_PROCESS_IDENTITY_UNKNOWN_OR_CHANGED"
        }
        var next = 0 // Local to this one fresh group; never retained by the scope.
        val acquired = object : OwnedProcessLifecycle.Shell {
            override fun execute(command: String, until: Long): OwnedProcessLifecycle.ShellResult {
                check(until == deadlineNanos && clock.nowNanos() < until && next < commands.size &&
                    command == commands[next]) { "OWN_PROCESS_IDENTITY_UNKNOWN_OR_CHANGED" }
                return group[next++] // Unmodified raw fields, including all cmdline NULs.
            }
            override fun sleepMillis(milliseconds: Long) = error("OWN_PROCESS_IDENTITY_UNKNOWN_OR_CHANGED")
        }
        OwnedProcessLifecycle(acquired, clock).requireAlive(owner, deadlineNanos)
        check(next == commands.size && clock.nowNanos() <= deadlineNanos) {
            "OWN_PROCESS_IDENTITY_UNKNOWN_OR_CHANGED"
        }
    }
}
